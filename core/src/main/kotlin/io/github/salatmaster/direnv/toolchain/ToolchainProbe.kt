// Added for ENV-17: classify probe outcomes without retaining unsafe failure output.
package io.github.salatmaster.direnv.toolchain

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.salatmaster.direnv.direnv.DirenvEnvironment
import io.github.salatmaster.direnv.direnv.DirenvExecutableNotFoundException
import io.github.salatmaster.direnv.direnv.DirenvPathMapper
import io.github.salatmaster.direnv.direnv.DirenvProcessRunner
import kotlinx.coroutines.CancellationException
import java.nio.file.Path

enum class ToolchainLanguage { GO, RUST, UNSPECIFIED }
enum class ToolchainStage { DISCOVERY, GO_ENV, RUST_SYSROOT, CARGO_VERSION, CONFIGURATION, SYNCHRONIZATION }
enum class ToolchainReason {
    NOT_CONFIGURED, PATH_MAPPING, EXECUTABLE_MISSING, EXECUTION_FAILED, NONZERO_EXIT,
    INVALID_OUTPUT, INVALID_SDK, MISSING_SOURCES, UNSUPPORTED_TARGET, UNEXPECTED,
}

/** Only finite categories and an exit code may enter diagnostics. */
data class ToolchainDiagnostic(
    val language: ToolchainLanguage, val stage: ToolchainStage,
    val reason: ToolchainReason, val exitCode: Int? = null,
)

sealed interface ToolchainProbeResult {
    class Success(val output: String) : ToolchainProbeResult {
        override fun toString(): String = "Success(output=<redacted>)"
    }
    data object Stale : ToolchainProbeResult
    data class Failure(val reason: ToolchainReason, val exitCode: Int? = null) : ToolchainProbeResult
}

/** Process/mapping seam shared by local and remote probes; no IDE SDK writes. */
internal class ToolchainProbe(private val runner: DirenvProcessRunner, private val mapper: DirenvPathMapper) {
    fun run(
        environment: DirenvEnvironment, executable: Path, arguments: List<String>,
        direnvExecutable: String, extraEnv: Map<String, String>, timeoutMs: Int,
        isCurrent: () -> Boolean,
    ): ToolchainProbeResult {
        if (!isCurrent()) return ToolchainProbeResult.Stale
        return try {
            val cwd = mapper.toDirenv(environment.workingDir)
                ?: return ToolchainProbeResult.Failure(ToolchainReason.PATH_MAPPING)
            val tool = mapper.toDirenv(executable)
                ?: return ToolchainProbeResult.Failure(ToolchainReason.PATH_MAPPING)
            val result = runner.run(direnvExecutable, listOf("exec", cwd, tool) + arguments,
                environment.workingDir, extraEnv, timeoutMs)
            when {
                !isCurrent() -> ToolchainProbeResult.Stale
                result.exitCode != 0 -> ToolchainProbeResult.Failure(ToolchainReason.NONZERO_EXIT, result.exitCode)
                else -> ToolchainProbeResult.Success(result.stdout)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: DirenvExecutableNotFoundException) {
            if (isCurrent()) ToolchainProbeResult.Failure(ToolchainReason.EXECUTABLE_MISSING) else ToolchainProbeResult.Stale
        } catch (_: Exception) {
            if (isCurrent()) ToolchainProbeResult.Failure(ToolchainReason.EXECUTION_FAILED) else ToolchainProbeResult.Stale
        }
    }
}
