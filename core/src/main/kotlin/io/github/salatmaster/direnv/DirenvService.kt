// Modified for ENV-16: generation-checked commits and independent environment notifications.
package io.github.salatmaster.direnv

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.ui.EditorNotifications
import io.github.salatmaster.direnv.direnv.DirenvCli
import io.github.salatmaster.direnv.direnv.DirenvEnvironment
import io.github.salatmaster.direnv.direnv.DirenvOutcome
import io.github.salatmaster.direnv.direnv.DirenvWatch
import io.github.salatmaster.direnv.direnv.EelDirenvProcessRunner
import io.github.salatmaster.direnv.direnv.GeneralCommandLineRunner
import io.github.salatmaster.direnv.settings.DirenvSettings
import io.github.salatmaster.direnv.watch.DirenvWatchService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the loaded direnv environments for one project.
 *
 * Environments are cached by the directory holding the resolved .envrc, so subdirectories sharing
 * an .envrc share one entry while a nested .envrc gets its own — that is what makes nested
 * environments work without special casing.
 *
 * Nothing here reaches disk: the values are frequently secrets.
 */
@Service(Service.Level.PROJECT)
class DirenvService(private val project: Project, private val scope: CoroutineScope) {

    private val log = Logger.getInstance(DirenvService::class.java)

    private val cache = DirenvCache()
    private val loadMutex = Mutex()
    private val deliveringChanges = AtomicBoolean(false)
    private val scheduledLoads = ConcurrentHashMap.newKeySet<Path>()

    /** Test seam: lets tests supply a CLI backed by a fake process runner. */
    var cliOverride: DirenvCli? = null

    private val defaultCli: DirenvCli by lazy {
        val settings = DirenvSettings.getInstance(project)
        val local = DirenvMachine.isLocal(project)
        // Which machine direnv runs on decides how every path in this plugin is read, and until now
        // nothing said which one had been chosen. Two WSL reports were spent working that out.
        log.info("direnv will run on ${DirenvMachine.name(project)}")
        DirenvCli(
            // A project in WSL or on a remote host needs direnv started over there: the binary is
            // installed on that machine and the working directory is written in its path syntax.
            runner = if (local) GeneralCommandLineRunner() else EelDirenvProcessRunner(project),
            executableProvider = { settings.state.executablePath },
            extraEnvProvider = { settings.state.extraEnv.toMap() },
            timeoutMsProvider = { settings.timeoutMs() },
            pathMapper = DirenvMachine.pathMapper(project),
        )
    }

    private fun cli(): DirenvCli = cliOverride ?: defaultCli

    fun state(): DirenvState = cache.state()

    /** Read-only and cache-only: no parent guessing, filesystem access or alias creation. */
    fun cachedFor(workingDir: Path): DirenvEnvironment? = cache.cached(workingDir.toAbsolutePath().normalize())

    internal fun watchSnapshot(): DirenvCache.Watches = cache.watchSnapshot()

    /**
     * Loads the environment for [workingDir].
     *
     * Serialised through a mutex so that concurrent triggers — startup activity, a watched file
     * changing, an explicit reload — collapse into one direnv invocation instead of competing.
     */
    suspend fun load(workingDir: Path, force: Boolean = false): DirenvState =
        loadDirectory(workingDir, force, automatic = false)

    /**
     * Prepare an unknown directory before a background process starts. Failures are throttled
     * per directory; the last result displayed in the status bar is not a loading policy.
     * Explicit reloads and file/approval changes bypass this automatic retry delay.
     */
    suspend fun environmentForProcess(workingDir: Path): DirenvEnvironment? {
        if (!DirenvGuard.mayRun(project)) return null
        loadDirectory(workingDir, force = false, automatic = true)
        return if (DirenvGuard.mayRun(project)) cachedFor(workingDir) else null
    }

    private suspend fun loadDirectory(workingDir: Path, force: Boolean, automatic: Boolean): DirenvState {
        if (!DirenvGuard.mayRun(project)) return DirenvState.NotLoaded
        val normalised = workingDir.toAbsolutePath().normalize()
        if (!force) cachedFor(normalised)?.let { return DirenvState.Loaded(it.diffAgainst(System.getenv())) }

        return loadMutex.withLock {
            if (!DirenvGuard.mayRun(project)) return@withLock DirenvState.NotLoaded
            if (!force) cachedFor(normalised)?.let { return@withLock DirenvState.Loaded(it.diffAgainst(System.getenv())) }
            if (automatic) cache.recentFailure(normalised)?.let { return@withLock it }

            val load = cache.begin(normalised)
            deliverChanges()
            try {
                val prepared = withContext(Dispatchers.IO) { prepareOutcome(normalised, cli().export(normalised)) }
                if (!DirenvGuard.mayRun(project)) {
                    cache.cancel(load)
                    deliverChanges()
                    return@withLock DirenvState.NotLoaded
                }
                val committed = cache.complete(load, prepared.environment, prepared.state, prepared.watches, prepared.resolvedScope)
                deliverChanges()
                if (committed) {
                    withContext(Dispatchers.IO) { DirenvWatchService.getInstance(project).refreshWatches() }
                    prepared.state
                } else DirenvState.NotLoaded
            } catch (e: CancellationException) {
                cache.cancel(load)
                deliverChanges()
                throw e
            } catch (e: ProcessCanceledException) {
                cache.cancel(load)
                deliverChanges()
                throw e
            } catch (e: Exception) {
                cache.cancel(load)
                deliverChanges()
                throw e
            }
        }
    }

    private class Prepared(
        val state: DirenvState,
        val environment: DirenvEnvironment? = null,
        val watches: List<DirenvWatch>? = null,
        val resolvedScope: Path? = null,
    )

    /** Filesystem and diff work precede the short, version-checked metadata commit. */
    private fun prepareOutcome(workingDir: Path, outcome: DirenvOutcome): Prepared = when (outcome) {
        is DirenvOutcome.Loaded -> {
            val environment = outcome.environment
            val key = environment.loadedRcPath?.parent?.toAbsolutePath()?.normalize() ?: workingDir
            val scopeWatches = mutableListOf<DirenvWatch>()
            var directory: Path? = workingDir
            while (directory != null && directory.startsWith(key)) {
                val file = directory.resolve(ENVRC_FILE_NAME)
                val exists = Files.exists(file)
                val modtime = if (exists) Files.getLastModifiedTime(file).toInstant().epochSecond else 0L
                scopeWatches += DirenvWatch(file, modtime, exists)
                if (directory == key) break
                directory = directory.parent
            }
            Prepared(DirenvState.Loaded(environment.diffAgainst(System.getenv())), environment,
                (environment.watches + scopeWatches).distinctBy { it.path })
        }
        is DirenvOutcome.Blocked -> Prepared(DirenvState.Blocked(outcome.envrcPath), watches = outcome.watches, resolvedScope = scopeOf(outcome.envrcPath))
        is DirenvOutcome.Denied -> Prepared(DirenvState.Denied(outcome.envrcPath), watches = outcome.watches, resolvedScope = scopeOf(outcome.envrcPath))
        is DirenvOutcome.ExecutableNotFound -> Prepared(DirenvState.ExecutableMissing(outcome.executable))
        is DirenvOutcome.Failed -> {
            log.warn("direnv failed with exit code ${outcome.exitCode}")
            Prepared(DirenvState.Failed(outcome.message))
        }
    }

    /** CLI approval outcomes already map their .envrc path into this IDE's filesystem. */
    private fun scopeOf(envrc: String): Path? = runCatching {
        Path.of(envrc).takeIf { it.isAbsolute }?.parent?.normalize()
    }.getOrNull()

    /** Reloads the environment for [workingDir] from a non-suspending caller, e.g. an action. */
    fun scheduleReload(workingDir: Path) {
        scope.launch { load(workingDir, force = true) }
    }

    /** Resolves an unknown process directory without blocking its caller or forcing a reload. */
    fun scheduleLoad(workingDir: Path) {
        val directory = workingDir.toAbsolutePath().normalize()
        if (cache.recentFailure(directory) != null || !scheduledLoads.add(directory)) return
        scope.launch {
            try {
                environmentForProcess(directory)
            } finally {
                scheduledLoads.remove(directory)
            }
        }
    }

    /**
     * Approves an .envrc and reloads.
     *
     * Only ever reached from an explicit user action: nothing in the plugin calls this on its own.
     */
    fun scheduleAllow(envrcPath: Path, workingDir: Path) {
        scope.launch {
            withContext(Dispatchers.IO) { cli().allow(envrcPath) }
            load(workingDir, force = true)
        }
    }

    /** Revokes approval of an .envrc and drops the environment it produced. */
    fun scheduleBlock(envrcPath: Path, workingDir: Path) {
        scope.launch {
            withContext(Dispatchers.IO) { cli().deny(envrcPath) }
            invalidate(workingDir)
            load(workingDir, force = true)
        }
    }

    /** The .envrc backing the environment for [workingDir], if one is known. */
    fun envrcPathFor(workingDir: Path): Path? = cachedFor(workingDir)?.loadedRcPath
        ?: unapprovedRcPath()

    /**
     * The .envrc named by a state that produced no environment.
     *
     * Neither Blocked nor Denied caches an environment, so without this the UI would lose the one
     * file it needs to act on precisely when approval is the only thing left to do.
     */
    private fun unapprovedRcPath(): Path? = when (val state = cache.state()) {
        is DirenvState.Blocked -> runCatching { Path.of(state.envrcPath) }.getOrNull()
        is DirenvState.Denied -> runCatching { Path.of(state.envrcPath) }.getOrNull()
        else -> null
    }

    /** Drain committed changes in order, without holding metadata locks across callbacks.
     * Reentrant invalidations append to this same queue. UI skips superseded results;
     * environment subscribers re-read the current cache rather than applying old payloads.
     */
    private fun deliverChanges() {
        if (!deliveringChanges.compareAndSet(false, true)) return
        try {
            while (true) {
                val change = cache.nextChange() ?: break
                if (project.isDisposed) continue
                change.environment?.let {
                    project.messageBus.syncPublisher(DirenvEnvironmentListener.TOPIC).environmentChanged(it)
                }
                if (cache.isLatest(change)) {
                    project.messageBus.syncPublisher(DirenvStateListener.TOPIC).stateChanged(change.state)
                    EditorNotifications.getInstance(project).updateAllNotifications()
                }
            }
        } finally {
            deliveringChanges.set(false)
        }
        // A producer can enqueue between the last poll and releasing the drainer flag.
        if (cache.hasChanges()) deliverChanges()
    }

    /** Drops cached environments immediately; never waits for exports or filesystem IO. */
    fun invalidate(workingDir: Path?) {
        cache.invalidate(workingDir?.toAbsolutePath()?.normalize())
        deliverChanges()
        scope.launch(Dispatchers.IO) { DirenvWatchService.getInstance(project).refreshWatches() }
    }

    companion object {
        fun getInstance(project: Project): DirenvService = project.service()
    }
}
