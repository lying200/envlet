// Modified for ENV-21: project probes use the project directory, not the last export directory.
package io.github.salatmaster.direnv.toolchain

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProcessCanceledException
import io.github.salatmaster.direnv.DirenvGuard
import io.github.salatmaster.direnv.DirenvMachine
import io.github.salatmaster.direnv.DirenvService
import io.github.salatmaster.direnv.DirenvEnvironmentChange
import io.github.salatmaster.direnv.DirenvEnvironmentListener
import io.github.salatmaster.direnv.direnv.DirenvEnvironment
import io.github.salatmaster.direnv.direnv.DirenvProcessRunner
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

    fun watch(enabled: () -> Boolean, language: ToolchainLanguage = ToolchainLanguage.UNSPECIFIED, synchronize: suspend (DirenvEnvironment) -> Unit) {
        var job: Job? = null
        var synchronizedEnvironment: DirenvEnvironment? = null
        val listener = object : DirenvEnvironmentListener {
            override fun environmentChanged(change: DirenvEnvironmentChange) = reconcile()

            @Synchronized
            fun reconcile() {
                // Events carry scope/revision, never environment values. Re-read the root
                // snapshot: delivery may race with a newer commit or invalidation.
                val environment = if (enabled() && DirenvGuard.mayRun(project)) {
                    DirenvMachine.projectDir(project)?.let { DirenvService.getInstance(project).cachedFor(it) }
                } else null
                if (environment === synchronizedEnvironment) return

                job?.cancel()
                job = null
                synchronizedEnvironment = environment
                if (environment == null) return
                // Remember completed attempts too: unrelated events must not repeat SDK writes
                // or Cargo refreshes. A root reload produces a new environment and retries.
                job = scope.launch(Dispatchers.IO) {
                    try {
                        synchronize(environment)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ProcessCanceledException) {
                        throw e
                    } catch (_: Exception) {
                        // Process diagnostics may contain environment values; keep them out of logs.
                        if (isCurrent(environment)) report(language, ToolchainStage.SYNCHRONIZATION, ToolchainReason.UNEXPECTED)
                    }
                }
            }
        }
        project.messageBus.connect(scope).subscribe(DirenvEnvironmentListener.TOPIC, listener)
        listener.reconcile()
    }

    fun isCurrent(environment: DirenvEnvironment): Boolean {
        if (!DirenvGuard.mayRun(project)) return false
        val root = DirenvMachine.projectDir(project) ?: return false
        return DirenvService.getInstance(project).cachedFor(root) === environment
    }

    /** Must be called at the actual SDK publication point, after background discovery. */
    fun applyIfCurrent(environment: DirenvEnvironment, enabled: () -> Boolean, apply: () -> Unit): Boolean {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (!enabled() || !isCurrent(environment)) return false
        apply()
        return true
    }

    fun report(language: ToolchainLanguage, stage: ToolchainStage, reason: ToolchainReason, exitCode: Int? = null) {
        val diagnostic = ToolchainDiagnostic(language, stage, reason, exitCode)
        if (reason == ToolchainReason.NOT_CONFIGURED) log.debug("Envlet: $diagnostic")
        else log.warn("Envlet: $diagnostic")
    }

    fun outputOrReport(language: ToolchainLanguage, stage: ToolchainStage, result: ToolchainProbeResult): String? =
        when (result) {
            is ToolchainProbeResult.Success -> result.output
            ToolchainProbeResult.Stale -> null // Normal cancellation/reload; not a warning.
            is ToolchainProbeResult.Failure -> {
                report(language, stage, result.reason, result.exitCode)
                null
            }
        }

    /** Run the selected tool inside direnv itself, including its unset-variable semantics. */
    suspend fun probe(environment: DirenvEnvironment, executable: Path, arguments: List<String>): ToolchainProbeResult =
        probe(environment, executable, arguments,
            if (DirenvMachine.isLocal(project)) GeneralCommandLineRunner() else EelDirenvProcessRunner(project))

    /** Same project probe with an explicit process adapter, also used by cross-component tests. */
    internal suspend fun probe(
        environment: DirenvEnvironment, executable: Path, arguments: List<String>, runner: DirenvProcessRunner,
    ): ToolchainProbeResult =
        withContext(Dispatchers.IO) {
            if (!isCurrent(environment)) return@withContext ToolchainProbeResult.Stale
            val projectDirectory = DirenvMachine.projectDir(project)
                ?: return@withContext ToolchainProbeResult.Failure(ToolchainReason.PATH_MAPPING)
            val settings = DirenvSettings.getInstance(project)
            ToolchainProbe(runner, DirenvMachine.pathMapper(project)).run(
                projectDirectory, executable, arguments, settings.state.executablePath,
                settings.state.extraEnv.toMap(), settings.timeoutMs(),
            ) { isCurrent(environment) }
        }
}
