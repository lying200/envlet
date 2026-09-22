package io.github.salatmaster.direnv.toolchain

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.salatmaster.direnv.DirenvGuard
import io.github.salatmaster.direnv.DirenvMachine
import io.github.salatmaster.direnv.DirenvService
import io.github.salatmaster.direnv.DirenvState
import io.github.salatmaster.direnv.DirenvStateListener
import io.github.salatmaster.direnv.direnv.DirenvEnvironment
import io.github.salatmaster.direnv.direnv.EelDirenvProcessRunner
import io.github.salatmaster.direnv.direnv.GeneralCommandLineRunner
import io.github.salatmaster.direnv.settings.DirenvSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** Owns cancellable toolchain probes; loaded values never enter persistent settings. */
@Service(Service.Level.PROJECT)
class EnvletToolchainSync(private val project: Project, private val scope: CoroutineScope) {
    private val log = Logger.getInstance(EnvletToolchainSync::class.java)

    fun watch(enabled: () -> Boolean, synchronize: suspend (DirenvEnvironment) -> Unit) {
        var job: Job? = null
        val listener = object : DirenvStateListener {
            @Synchronized
            override fun stateChanged(state: DirenvState) {
                job?.cancel()
                if (state !is DirenvState.Loaded || !enabled() || !DirenvGuard.mayRun(project)) return
                val root = DirenvMachine.projectDir(project) ?: return
                val environment = DirenvService.getInstance(project).cachedFor(root) ?: return
                job = scope.launch(Dispatchers.IO) {
                    try {
                        synchronize(environment)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Process diagnostics may contain environment values; keep them out of logs.
                        log.warn("Envlet could not synchronize a project toolchain; existing SDK settings were retained")
                    }
                }
            }
        }
        project.messageBus.connect().subscribe(DirenvStateListener.TOPIC, listener)
        listener.stateChanged(DirenvService.getInstance(project).state())
    }

    fun isCurrent(environment: DirenvEnvironment): Boolean {
        if (!DirenvGuard.mayRun(project)) return false
        val root = DirenvMachine.projectDir(project) ?: return false
        return DirenvService.getInstance(project).cachedFor(root) === environment
    }

    /** Run the selected tool inside direnv itself, including its unset-variable semantics. */
    suspend fun probe(environment: DirenvEnvironment, executable: Path, arguments: List<String>): String? =
        withContext(Dispatchers.IO) {
            if (!isCurrent(environment)) return@withContext null
            val settings = DirenvSettings.getInstance(project)
            val runner = if (DirenvMachine.isLocal(project)) GeneralCommandLineRunner() else EelDirenvProcessRunner(project)
            val mapper = DirenvMachine.pathMapper(project)
            val cwd = mapper.toDirenv(environment.workingDir) ?: return@withContext null
            val tool = mapper.toDirenv(executable) ?: return@withContext null
            val result = runner.run(
                settings.state.executablePath,
                listOf("exec", cwd, tool) + arguments,
                environment.workingDir,
                settings.state.extraEnv.toMap(),
                settings.timeoutMs(),
            )
            if (result.exitCode == 0 && isCurrent(environment)) result.stdout else null
        }
}
