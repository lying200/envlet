package io.github.salatmaster.direnv.rust

import com.intellij.execution.wsl.WslPath
import com.intellij.openapi.project.ProjectManager
import io.github.salatmaster.direnv.DirenvGuard
import io.github.salatmaster.direnv.DirenvMachine
import io.github.salatmaster.direnv.settings.DirenvSettings
import org.rust.cargo.toolchain.RsToolchainBase
import org.rust.cargo.toolchain.RsToolchainProvider
import java.nio.file.Path

/** Rust reconstructs its toolchain from the saved home path; scope the provider to project profiles. */
class EnvletRustToolchainProvider : RsToolchainProvider {
    override fun getToolchain(homePath: Path): RsToolchainBase? {
        val wsl = WslPath.parseWindowsUncPath(homePath.toString()) ?: return null
        val projects = ProjectManager.getInstanceIfCreated()?.openProjects ?: return null
        val managed = projects.any { project ->
            !project.isDisposed &&
                DirenvMachine.projectDir(project)?.resolve(PROFILE_BIN)?.normalize() == homePath.normalize() &&
                DirenvGuard.mayRun(project) && DirenvSettings.getInstance(project).state.autoRustToolchain
        }
        return if (managed) EnvletWslRustToolchain(wsl) else null
    }

    companion object {
        const val PROFILE_BIN = ".devenv/profile/bin"
    }
}
