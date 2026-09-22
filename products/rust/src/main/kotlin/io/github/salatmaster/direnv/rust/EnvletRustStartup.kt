package io.github.salatmaster.direnv.rust

import com.intellij.execution.wsl.WslPath
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.salatmaster.direnv.DirenvMachine
import io.github.salatmaster.direnv.settings.DirenvSettings
import io.github.salatmaster.direnv.toolchain.EnvletToolchainSync
import io.github.salatmaster.direnv.toolchain.ToolchainCandidateResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.rust.cargo.project.model.CargoProjectsService
import org.rust.cargo.project.settings.RustProjectSettingsService
import org.rust.cargo.toolchain.RsLocalToolchain
import org.rust.cargo.toolchain.wsl.RsWslToolchain
import java.nio.file.Files

class EnvletRustStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        val sync = project.service<EnvletToolchainSync>()
        val options = DirenvSettings.getInstance(project)
        sync.watch({ options.state.autoRustToolchain }) { environment ->
            val machine = DirenvMachine.toolchainMachine(project)
            val rustcName = machine.executable("rustc")
            val cargoName = machine.executable("cargo")
            val rustc = ToolchainCandidateResolver.resolveExecutable(environment.entries, rustcName, machine)
                ?: return@watch
            val cargo = ToolchainCandidateResolver.resolveExecutable(environment.entries, cargoName, machine)
                ?: return@watch
            // A devenv profile can expose two distinct Nix packages through one bin directory.
            val bin = machine.splitPath(environment.entries["PATH"] ?: return@watch)
                .filter { it.isNotBlank() }.mapNotNull(machine::path).firstOrNull { candidate ->
                    Files.isRegularFile(candidate.resolve(rustcName)) && Files.isRegularFile(candidate.resolve(cargoName)) &&
                        Files.isSameFile(candidate.resolve(rustcName), rustc) &&
                        Files.isSameFile(candidate.resolve(cargoName), cargo)
                } ?: return@watch
            val sysroot = sync.probe(environment, rustc, listOf("--print", "sysroot"))?.trim()
                ?.takeIf { it.isNotBlank() }?.let(machine::path) ?: return@watch
            if (sync.probe(environment, cargo, listOf("--version")) == null) return@watch
            val sources = sequenceOf(
                environment.entries["RUST_SRC_PATH"]?.let(machine::path),
                sysroot.resolve("lib/rustlib/src/rust/library"),
            ).filterNotNull().firstOrNull { Files.isRegularFile(it.resolve("core/src/lib.rs")) }
            val wsl = WslPath.parseWindowsUncPath(bin.toString())
            val toolchain = if (wsl != null) RsWslToolchain(wsl) else {
                if (!DirenvMachine.isLocal(project)) return@watch
                RsLocalToolchain(bin)
            }
            if (!toolchain.looksLikeValidToolchain()) return@watch

            withContext(Dispatchers.EDT) {
                if (!sync.isCurrent(environment) || !options.state.autoRustToolchain) return@withContext
                val settings = project.service<RustProjectSettingsService>()
                val sourcePath = sources?.toString()?.replace('\\', '/')
                if (settings.toolchain != toolchain || settings.explicitPathToStdlib != sourcePath) {
                    settings.modify {
                        it.toolchain = toolchain
                        it.explicitPathToStdlib = sourcePath
                    }
                    // modify emits the settings event which schedules Cargo refresh.
                } else {
                    // Environment-only updates still affect Cargo metadata/build scripts.
                    project.service<CargoProjectsService>().refreshAllProjects(false)
                }
            }
        }
    }
}
