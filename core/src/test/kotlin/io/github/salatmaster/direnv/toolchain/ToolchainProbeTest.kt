// Added for ENV-17: real mapping/execution classification through the existing runner seam.
package io.github.salatmaster.direnv.toolchain

import io.github.salatmaster.direnv.direnv.DirenvPathMapper
import io.github.salatmaster.direnv.direnv.DirenvProcessResult
import io.github.salatmaster.direnv.direnv.FakeDirenvProcessRunner
import kotlinx.coroutines.CancellationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ToolchainProbeTest {
    private val root = Path.of("probe").toAbsolutePath()
    private val runner = FakeDirenvProcessRunner()
    private var current = true
    private fun probe(mapper: DirenvPathMapper = DirenvPathMapper.SameMachine) =
        ToolchainProbe(runner, mapper).run(root, root.resolve("go"), listOf("env"),
            "direnv", emptyMap(), 5000) { current }

    @Test fun `successful output is available to the parser but hidden from rendering`() {
        runner.respondTo("exec", DirenvProcessResult(0, "private-output", "private-stderr"))
        val result = probe() as ToolchainProbeResult.Success
        assertThat(result.output).isEqualTo("private-output")
        assertThat(result.toString()).doesNotContain("private-output", "private-stderr")
    }

    @Test fun `stale before execution skips the process`() {
        current = false
        assertThat(probe()).isSameAs(ToolchainProbeResult.Stale)
        assertThat(runner.invocations).isEmpty()
    }

    @Test fun `invalidation during a failed process yields a normal stale result`() {
        runner.beforeRun = { current = false }
        runner.respondTo("exec", DirenvProcessResult(9, "private-output", "private-stderr"))
        assertThat(probe()).isSameAs(ToolchainProbeResult.Stale)
    }

    @Test fun `mapping failures are distinct and never start the process`() {
        val mapper = object : DirenvPathMapper {
            override fun toLocal(reported: String): Path? = null
            override fun toDirenv(path: Path): String? = null
        }
        assertThat(probe(mapper)).isEqualTo(ToolchainProbeResult.Failure(ToolchainReason.PATH_MAPPING))
        assertThat(runner.invocations).isEmpty()
    }

    @Test fun `nonzero exit exposes only category and exit code`() {
        runner.respondTo("exec", DirenvProcessResult(17, "private-output", "private-stderr"))
        val result = probe() as ToolchainProbeResult.Failure
        assertThat(result).isEqualTo(ToolchainProbeResult.Failure(ToolchainReason.NONZERO_EXIT, 17))
        val diagnostic = ToolchainDiagnostic(ToolchainLanguage.GO, ToolchainStage.GO_ENV, result.reason, result.exitCode)
        assertThat(diagnostic.toString()).contains("GO_ENV", "NONZERO_EXIT", "17").doesNotContain("private-output", "private-stderr")
    }

    @Test fun `missing executable and launch exceptions remain distinguishable and safe`() {
        runner.executableMissing = true
        assertThat(probe()).isEqualTo(ToolchainProbeResult.Failure(ToolchainReason.EXECUTABLE_MISSING))
        runner.executableMissing = false
        runner.beforeRun = { throw IllegalStateException("private-exception") }
        val result = probe()
        assertThat(result).isEqualTo(ToolchainProbeResult.Failure(ToolchainReason.EXECUTION_FAILED))
        assertThat(result.toString()).doesNotContain("private-exception")
    }

    @Test fun `cancellation is propagated instead of becoming a warning`() {
        runner.beforeRun = { throw CancellationException("private-cancel") }
        assertThatThrownBy { probe() }.isInstanceOf(CancellationException::class.java)
    }
}
