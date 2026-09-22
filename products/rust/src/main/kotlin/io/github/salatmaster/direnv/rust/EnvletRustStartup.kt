// Modified for ENV-13: assemble project-owned SDKs from independently selected tools.
package io.github.salatmaster.direnv.rust

import com.intellij.execution.wsl.WslPath
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.SystemInfo
import io.github.salatmaster.direnv.DirenvMachine
import io.github.salatmaster.direnv.settings.DirenvSettings
import io.github.salatmaster.direnv.toolchain.EnvletToolchainSync
import io.github.salatmaster.direnv.toolchain.ToolchainCandidateResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.rust.cargo.project.model.CargoProjectsService
import org.rust.cargo.project.settings.RustProjectSettingsService
import org.rust.cargo.toolchain.RsLocalToolchain
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
            val root = DirenvMachine.projectDir(project) ?: return@watch
            val wsl = WslPath.parseWindowsUncPath(root.toString())
            if (wsl == null && !DirenvMachine.isLocal(project)) return@watch
            val sysroot = sync.probe(environment, rustc, listOf("--print", "sysroot"))?.trim()
                ?.takeIf { it.isNotBlank() }?.let(machine::path) ?: return@watch
            if (sync.probe(environment, cargo, listOf("--version")) == null) return@watch
            val sources = sequenceOf(
                environment.entries["RUST_SRC_PATH"]?.let(machine::path),
                sysroot.resolve("lib/rustlib/src/rust/library"),
            ).filterNotNull().firstOrNull { Files.isRegularFile(it.resolve("core/src/lib.rs")) }
            val bin = if (wsl != null || SystemInfo.isUnix) {
                val tools = linkedMapOf("rustc" to rustc, "cargo" to cargo)
                for (name in listOf("rustdoc", "rustfmt", "cargo-fmt", "clippy-driver", "cargo-clippy", "rust-gdb", "rust-lldb")) {
                    ToolchainCandidateResolver.resolveExecutable(environment.entries, name, machine)?.let { tools[name] = it }
                }
                if (!sync.isCurrent(environment)) return@watch
                ManagedRustToolchainHome.prepare(root, tools)
            } else {
                // Preserve native Windows behavior without requiring symlink privileges.
                machine.splitPath(environment.entries["PATH"] ?: return@watch)
                    .filter { it.isNotBlank() }.mapNotNull(machine::path).firstOrNull { candidate ->
                        Files.isRegularFile(candidate.resolve(rustcName)) && Files.isRegularFile(candidate.resolve(cargoName)) &&
                            Files.isSameFile(candidate.resolve(rustcName), rustc) && Files.isSameFile(candidate.resolve(cargoName), cargo)
                    } ?: return@watch
            }
            val toolchain = if (wsl != null) EnvletWslRustToolchain(WslPath.parseWindowsUncPath(bin.toString())!!)
                else RsLocalToolchain(bin)
            if (!toolchain.looksLikeValidToolchain()) return@watch

            withContext(Dispatchers.EDT) {
                if (!sync.isCurrent(environment) || !options.state.autoRustToolchain) return@withContext
                project.service<EnvletRustToolchainBinding>().publish(bin, environment)
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
