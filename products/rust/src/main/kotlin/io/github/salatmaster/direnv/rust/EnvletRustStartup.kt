// Modified for ENV-17: separate configuration decisions and guard SDK publication.
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
import io.github.salatmaster.direnv.toolchain.ToolchainLanguage
import io.github.salatmaster.direnv.toolchain.ToolchainReason
import io.github.salatmaster.direnv.toolchain.ToolchainStage
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
        sync.watch({ options.state.autoRustToolchain }, ToolchainLanguage.RUST) { environment ->
            fun report(stage: ToolchainStage, reason: ToolchainReason) = sync.report(ToolchainLanguage.RUST, stage, reason)
            val machine = DirenvMachine.toolchainMachine(project)
            val rustcName = machine.executable("rustc")
            val cargoName = machine.executable("cargo")
            val rustc = ToolchainCandidateResolver.resolveExecutable(environment.entries, rustcName, machine)
                ?: run {
                    report(ToolchainStage.DISCOVERY, ToolchainReason.NOT_CONFIGURED)
                    return@watch
                }
            val cargo = ToolchainCandidateResolver.resolveExecutable(environment.entries, cargoName, machine)
                ?: run {
                    report(ToolchainStage.DISCOVERY, ToolchainReason.NOT_CONFIGURED)
                    return@watch
                }
            val root = DirenvMachine.projectDir(project) ?: return@watch
            val wsl = WslPath.parseWindowsUncPath(root.toString())
            if (wsl == null && !DirenvMachine.isLocal(project)) {
                report(ToolchainStage.DISCOVERY, ToolchainReason.UNSUPPORTED_TARGET)
                return@watch
            }
            val rawSysroot = sync.outputOrReport(ToolchainLanguage.RUST, ToolchainStage.RUST_SYSROOT,
                sync.probe(environment, rustc, listOf("--print", "sysroot"))) ?: return@watch
            if (rawSysroot.isBlank()) {
                report(ToolchainStage.RUST_SYSROOT, ToolchainReason.INVALID_OUTPUT)
                return@watch
            }
            val sysroot = machine.path(rawSysroot.trim())?.takeIf { it.isAbsolute } ?: run {
                report(ToolchainStage.RUST_SYSROOT, ToolchainReason.PATH_MAPPING)
                return@watch
            }
            if (sync.outputOrReport(ToolchainLanguage.RUST, ToolchainStage.CARGO_VERSION,
                    sync.probe(environment, cargo, listOf("--version"))) == null) return@watch
            val optional = RustToolchainPlan.OPTIONAL_TOOLS.mapNotNull { name ->
                ToolchainCandidateResolver.resolveExecutable(environment.entries, machine.executable(name), machine)?.let { name to it }
            }.toMap()
            val plan = RustToolchainPlan.fromDiscovery(rustc, cargo,
                environment.entries["RUST_SRC_PATH"]?.let(machine::path), sysroot, optional,
            ) { Files.isRegularFile(it.resolve("core/src/lib.rs")) }
            if (plan.sources == null) report(ToolchainStage.CONFIGURATION, ToolchainReason.MISSING_SOURCES)
            val bin = if (wsl != null || SystemInfo.isUnix) {
                if (!sync.isCurrent(environment)) return@watch
                ManagedRustToolchainHome.prepare(root, plan.tools)
            } else {
                // Preserve native Windows behavior without requiring symlink privileges.
                machine.splitPath(environment.entries["PATH"] ?: return@watch)
                    .filter { it.isNotBlank() }.mapNotNull(machine::path).firstOrNull { candidate ->
                        Files.isRegularFile(candidate.resolve(rustcName)) && Files.isRegularFile(candidate.resolve(cargoName)) &&
                            Files.isSameFile(candidate.resolve(rustcName), rustc) && Files.isSameFile(candidate.resolve(cargoName), cargo)
                    } ?: run {
                        report(ToolchainStage.CONFIGURATION, ToolchainReason.INVALID_SDK)
                        return@watch
                    }
            }
            val toolchain = if (wsl != null) EnvletWslRustToolchain(WslPath.parseWindowsUncPath(bin.toString())!!)
                else RsLocalToolchain(bin)
            if (!toolchain.looksLikeValidToolchain()) {
                report(ToolchainStage.CONFIGURATION, ToolchainReason.INVALID_SDK)
                return@watch
            }

            withContext(Dispatchers.EDT) {
                sync.applyIfCurrent(environment, { options.state.autoRustToolchain }) {
                    project.service<EnvletRustToolchainBinding>().publish(bin, environment)
                    val settings = project.service<RustProjectSettingsService>()
                    val sourcePath = plan.sources?.toString()?.replace('\\', '/')
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
}
