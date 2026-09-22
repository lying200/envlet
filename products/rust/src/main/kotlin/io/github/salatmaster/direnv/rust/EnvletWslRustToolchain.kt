package io.github.salatmaster.direnv.rust

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.wsl.WslPath
import org.rust.cargo.toolchain.RsToolchainBase
import org.rust.cargo.toolchain.wsl.RsWslToolchain
import org.rust.openapiext.RsPathManager
import java.nio.file.Path

/**
 * Retains WSL path/tool discovery while letting the platform start UNC executables via EEL.
 * RsWslToolchain.patchCommandLine forces wsl.exe and serializes env before the platform's
 * environment customizer runs. Inheriting the base patch instead keeps env at process start.
 * Do not depend on Rust's analogous RsEelToolchain: that implementation is Internal in 262.
 */
class EnvletWslRustToolchain private constructor(private val wsl: RsWslToolchain) :
    RsToolchainBase(wsl.location) {

    constructor(path: WslPath) : this(RsWslToolchain(path))

    override fun patchCommandLine(
        commandLine: GeneralCommandLine,
        withSudo: Boolean,
        ensureToolchainInPath: Boolean,
    ): GeneralCommandLine {
        // Cargo selects its bundled build-script helper by concrete toolchain type.
        // Correct only that helper; a user's custom RUSTC_WRAPPER must be preserved.
        val wrapper = commandLine.environment[RUSTC_WRAPPER]
        if (wrapper != null) {
            val hostHelper = RsPathManager.nativeHelper(this)?.let(::toRemotePath)
            if (wrapper == hostHelper) {
                RsPathManager.nativeHelper(wsl)?.let(::toRemotePath)?.let {
                    commandLine.environment[RUSTC_WRAPPER] = it
                }
            }
        }
        return super.patchCommandLine(commandLine, withSudo, ensureToolchainInPath)
    }

    override val fileSeparator: String get() = wsl.fileSeparator
    override val executionTimeoutInMilliseconds: Int get() = wsl.executionTimeoutInMilliseconds
    override fun toLocalPath(remotePath: String): String = wsl.toLocalPath(remotePath)
    override fun toRemotePathInner(localPath: Path): String? = wsl.toRemotePath(localPath)
    override fun expandUserHome(remotePath: String): String = wsl.expandUserHome(remotePath)
    override fun getExecutableName(toolName: String): String = wsl.getExecutableName(toolName)
    // EEL infers the execution machine from the UNC executable, not a drive-relative /home path.
    override fun pathToExecutable(toolName: String): Path = location.resolve(getExecutableName(toolName))
    override fun remotePathToExecutable(toolName: String): String = wsl.remotePathToExecutable(toolName)
    override fun hasExecutable(exec: String): Boolean = wsl.hasExecutable(exec)
    override fun hasCargoExecutable(exec: String): Boolean = wsl.hasCargoExecutable(exec)
}
