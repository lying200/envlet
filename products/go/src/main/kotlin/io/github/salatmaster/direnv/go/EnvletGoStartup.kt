// Modified for ENV-17: separate planning, safe diagnostics and guarded SDK publication.
package io.github.salatmaster.direnv.go

import com.goide.project.GoProjectLibrariesService
import com.goide.sdk.GoSdk
import com.goide.sdk.GoSdkService
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VfsUtilCore
import io.github.salatmaster.direnv.DirenvMachine
import io.github.salatmaster.direnv.settings.DirenvSettings
import io.github.salatmaster.direnv.toolchain.EnvletToolchainSync
import io.github.salatmaster.direnv.toolchain.ToolchainCandidateResolver
import io.github.salatmaster.direnv.toolchain.ToolchainLanguage
import io.github.salatmaster.direnv.toolchain.ToolchainReason
import io.github.salatmaster.direnv.toolchain.ToolchainStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EnvletGoStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        val sync = project.service<EnvletToolchainSync>()
        val settings = DirenvSettings.getInstance(project)
        sync.watch({ settings.state.autoGoToolchain }, ToolchainLanguage.GO) { environment ->
            val machine = DirenvMachine.toolchainMachine(project)
            val executable = ToolchainCandidateResolver.resolveExecutable(
                environment.entries, machine.executable("go"), machine,
            ) ?: run {
                sync.report(ToolchainLanguage.GO, ToolchainStage.DISCOVERY, ToolchainReason.NOT_CONFIGURED)
                return@watch
            }
            val output = sync.outputOrReport(ToolchainLanguage.GO, ToolchainStage.GO_ENV,
                sync.probe(environment, executable, listOf("env", "-json", "GOROOT", "GOPATH"))) ?: return@watch
            val plan = when (val result = GoToolchainPlan.fromOutput(output, machine)) {
                is GoToolchainPlan.Result.Ready -> result.plan
                is GoToolchainPlan.Result.Rejected -> {
                    sync.report(ToolchainLanguage.GO, ToolchainStage.CONFIGURATION, result.reason)
                    return@watch
                }
            }
            val sdk = GoSdk.fromHomePath(plan.home.toString())
            if (!sdk.isValid) {
                sync.report(ToolchainLanguage.GO, ToolchainStage.CONFIGURATION, ToolchainReason.INVALID_SDK)
                return@watch
            }
            val roots = plan.roots.map { VfsUtilCore.pathToUrl(it.toString().replace('\\', '/')) }
            withContext(Dispatchers.EDT) {
                sync.applyIfCurrent(environment, { settings.state.autoGoToolchain }) {
                    val sdkService = GoSdkService.getInstance(project)
                    if (sdkService.getSdk(null).homeUrl != sdk.homeUrl) sdkService.setSdk(sdk)
                    val libraries = GoProjectLibrariesService.getInstance(project)
                    if (libraries.isUseGoPathFromSystemEnvironment) libraries.setUseGoPathFromSystemEnvironment(false)
                    if (libraries.libraryRootUrls.toSet() != roots.toSet()) libraries.setLibraryRootUrls(roots)
                }
            }
        }
    }
}
