package io.github.salatmaster.direnv.python

import io.github.salatmaster.direnv.toolchain.ToolchainMachine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PythonToolchainPlanTest {
    @TempDir lateinit var directory: Path
    private val machine = ToolchainMachine(false) {
        if (it.startsWith("/")) directory.resolve(it.removePrefix("/")) else Path.of(it)
    }

    @Test fun `earlier python3 takes precedence over later python`() {
        val first = Files.createDirectories(directory.resolve("environment/bin"))
        val second = Files.createDirectories(directory.resolve("system/bin"))
        Files.createFile(first.resolve("python3"))
        Files.createFile(second.resolve("python"))
        val result = PythonToolchainPlan.discover(mapOf("PATH" to "/environment/bin:/system/bin", "VIRTUAL_ENV" to second.toString()), machine)
        assertThat(result).isEqualTo(first.resolve("python3"))
    }

    @Test fun `relative PATH entries do not guess against the IDE directory`() {
        assertThat(PythonToolchainPlan.discover(mapOf("PATH" to ".:bin::"), machine)).isNull()
        assertThat(PythonToolchainPlan.discover(emptyMap(), machine)).isNull()
    }

    @Test fun `probe preserves venv executable and maps all SDK roots to the target machine`() {
        val mapped = ToolchainMachine(false) { directory.resolve(it.removePrefix("/")) }
        val plan = PythonToolchainPlan.fromOutput("""{"executable":"/project/.venv/bin/python","version":[3,14,7],"paths":["","/project/extras","/nix/store/python/lib","/project/extras"]}""", mapped)!!
        assertThat(plan.executable).isEqualTo(directory.resolve("project/.venv/bin/python"))
        assertThat(plan.version).isEqualTo("Python 3.14.7")
        assertThat(plan.roots).containsExactly(directory.resolve("project/extras"), directory.resolve("nix/store/python/lib"))
        assertThat(plan.toString()).doesNotContain("project", "extras", "nix/store")
    }

    @Test fun `Nix wrapper remains the launcher even when sys executable is the unwrapped binary`() {
        val launcher = directory.resolve("profile/bin/python")
        val plan = PythonToolchainPlan.fromOutput("""{"executable":"/nix/store/unwrapped/bin/python","version":[3,14,0],"paths":[]}""", machine, launcher)!!
        assertThat(plan.executable).isEqualTo(launcher)
    }

    @Test fun `malformed output and relative interpreter paths never become SDK plans`() {
        for (output in listOf("not JSON", "[]", "{}",
            """{"executable":"python","version":[3,14,0],"paths":[]}""",
            """{"executable":"/bin/python","version":[3,-1,0],"paths":[]}""",
            """{"executable":"/bin/python","version":[3,14,0],"paths":[42]}""",
            """{"executable":"/bin/python","version":[3,14,0],"paths":["relative"]}""")) {
            assertThat(PythonToolchainPlan.fromOutput(output, machine)).isNull()
        }
    }
}
