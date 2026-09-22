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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class EnvletGoStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        val sync = project.service<EnvletToolchainSync>()
        val settings = DirenvSettings.getInstance(project)
        sync.watch({ settings.state.autoGoToolchain }) { environment ->
            val machine = DirenvMachine.toolchainMachine(project)
            val executable = ToolchainCandidateResolver.resolveExecutable(
                environment.entries, machine.executable("go"), machine,
            ) ?: return@watch
            val output = sync.probe(environment, executable, listOf("env", "-json", "GOROOT", "GOPATH"))
                ?: return@watch
            val values = Json.parseToJsonElement(output).jsonObject
            val goroot = values["GOROOT"]?.jsonPrimitive?.content?.let(machine::path) ?: return@watch
            val sdk = GoSdk.fromHomePath(goroot.toString())
            if (!sdk.isValid) return@watch
            val gopath = values["GOPATH"]?.jsonPrimitive?.content ?: return@watch
            val roots = machine.splitPath(gopath).filter { it.isNotBlank() }
                .mapNotNull(machine::path).map { VfsUtilCore.pathToUrl(it.toString().replace('\\', '/')) }

            withContext(Dispatchers.EDT) {
                if (!sync.isCurrent(environment) || !settings.state.autoGoToolchain) return@withContext
                val sdkService = GoSdkService.getInstance(project)
                if (sdkService.getSdk(null).homeUrl != sdk.homeUrl) sdkService.setSdk(sdk)
                val libraries = GoProjectLibrariesService.getInstance(project)
                if (libraries.isUseGoPathFromSystemEnvironment) libraries.setUseGoPathFromSystemEnvironment(false)
                if (libraries.libraryRootUrls.toSet() != roots.toSet()) libraries.setLibraryRootUrls(roots)
            }
        }
    }
}
