package io.github.salatmaster.direnv.python

import com.intellij.execution.ExecutionException
import com.intellij.execution.wsl.WslPath
import com.intellij.execution.wsl.target.WslTargetEnvironmentRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.runBlockingMaybeCancellable
import com.intellij.openapi.project.Project
import com.jetbrains.python.run.PythonExecution
import com.jetbrains.python.run.PythonRunParams
import com.jetbrains.python.run.target.HelpersAwareTargetEnvironmentRequest
import com.jetbrains.python.run.target.PythonCommandLineTargetEnvironmentProvider
import io.github.salatmaster.direnv.DirenvGuard
import io.github.salatmaster.direnv.DirenvMachine
import io.github.salatmaster.direnv.DirenvService
import java.util.function.Function

/** 262-only Python compatibility seam. WSL Targets bypass GeneralCommandLine injection.
 * Work only on this execution, after target path mapping, and never on another distribution.
 */
@Suppress("UnstableApiUsage")
class EnvletPythonEnvironmentProvider : PythonCommandLineTargetEnvironmentProvider {
    override fun extendTargetEnvironment(
        project: Project, helpersAwareTargetRequest: HelpersAwareTargetEnvironmentRequest,
        pythonExecution: PythonExecution, runParams: PythonRunParams,
    ) {
        if (!DirenvGuard.mayRun(project)) return
        val request = helpersAwareTargetRequest.targetEnvironmentRequest as? WslTargetEnvironmentRequest ?: return
        val root = DirenvMachine.projectDir(project) ?: return
        val wsl = WslPath.parseWindowsUncPath(root.toString()) ?: return
        if (request.configuration.distribution?.msId != wsl.distribution.msId) return
        request.onEnvironmentPrepared { target, _ ->
            if (!DirenvGuard.mayRun(project)) return@onEnvironmentPrepared
            val mapped = pythonExecution.workingDir?.apply(target) ?: return@onEnvironmentPrepared
            val cwd = DirenvMachine.pathMapper(project).toLocal(mapped)?.toAbsolutePath()?.normalize()
                ?.takeIf { it.startsWith(root.toAbsolutePath().normalize()) } ?: return@onEnvironmentPrepared
            val service = DirenvService.getInstance(project)
            val application = ApplicationManager.getApplication()
            val environment = service.cachedFor(cwd) ?: if (
                !application.isDispatchThread && !application.isReadAccessAllowed && !application.isWriteAccessAllowed
            ) runBlockingMaybeCancellable { service.environmentForProcess(cwd) } else {
                service.scheduleLoad(cwd)
                null
            } ?: return@onEnvironmentPrepared
            if (!DirenvGuard.mayRun(project) || service.cachedFor(cwd) !== environment) return@onEnvironmentPrepared
            // Target preparation runs on the background launch thread. Resolve explicit
            // sources only for a run receiving direnv, not an unrelated/blocked directory.
            val explicit = PythonRunOverrides.read(runParams, runParams.envs)
            if (PythonRunEnvironment.requiresInheritedUnset(environment.entries, explicit)) {
                // Omitting an override would reveal the target's inherited value; an empty
                // string would not be an unset either. Do not silently run with that value.
                throw ExecutionException("Envlet: Python WSL Targets cannot remove inherited environment variables. " +
                    "Run this configuration from a direnv-loaded terminal instead.")
            }
            val original = pythonExecution.envs.mapValues { it.value.apply(target) }
            val merged = PythonRunEnvironment.merge(original, environment.entries, explicit, ":")
            pythonExecution.envs.clear()
            merged.forEach { (name, value) ->
                pythonExecution.envs[name] = Function {
                    if (!DirenvGuard.mayRun(project) || service.cachedFor(cwd) !== environment) {
                        throw ExecutionException("Envlet: Python environment changed while preparing the run; retry it")
                    }
                    value
                }
            }
        }
    }
}
