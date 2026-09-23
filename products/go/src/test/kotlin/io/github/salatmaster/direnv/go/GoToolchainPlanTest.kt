// Added for ENV-17: output validation must finish before SDK mutation.
package io.github.salatmaster.direnv.go

import io.github.salatmaster.direnv.toolchain.ToolchainMachine
import io.github.salatmaster.direnv.toolchain.ToolchainReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path

class GoToolchainPlanTest {
    private val root = Path.of("go-plan").toAbsolutePath()
    // Model a WSL-style mapping without requiring Windows or WSL in unit tests.
    private val machine = ToolchainMachine(false) { if (it.startsWith("/")) root.resolve(it.removePrefix("/")) else null }

    @Test fun `reject malformed missing null non-string and blank home values safely`() {
        for (text in listOf("{private-canary", "[]", "{}", "null",
            """{"GOROOT":null,"GOPATH":"/work"}""",
            """{"GOROOT":42,"GOPATH":"/work"}""",
            """{"GOROOT":" ","GOPATH":"/work"}""",
            """{"GOROOT":"/go"}""", """{"GOROOT":"/go","GOPATH":[]}""")) {
            val result = GoToolchainPlan.fromOutput(text, machine)
            assertThat(result).isEqualTo(GoToolchainPlan.Result.Rejected(ToolchainReason.INVALID_OUTPUT))
            assertThat(result.toString()).doesNotContain("private-canary")
        }
    }

    @Test fun `map all roots using the target convention and remove duplicates`() {
        val result = GoToolchainPlan.fromOutput("""{"GOROOT":"/go","GOPATH":"/one:/two:/one"}""", machine)
        val plan = (result as GoToolchainPlan.Result.Ready).plan
        assertThat(plan.home).isEqualTo(root.resolve("go"))
        assertThat(plan.roots).containsExactly(root.resolve("one"), root.resolve("two"))
    }

    @Test fun `unmappable root rejects the whole plan instead of silently dropping it`() {
        val result = GoToolchainPlan.fromOutput("""{"GOROOT":"/go","GOPATH":"/one:invalid"}""", machine)
        assertThat(result).isEqualTo(GoToolchainPlan.Result.Rejected(ToolchainReason.PATH_MAPPING))
    }

    @Test fun `explicit empty GOPATH is valid`() {
        val result = GoToolchainPlan.fromOutput("""{"GOROOT":"/go","GOPATH":""}""", machine)
        assertThat((result as GoToolchainPlan.Result.Ready).plan.roots).isEmpty()
    }
}
