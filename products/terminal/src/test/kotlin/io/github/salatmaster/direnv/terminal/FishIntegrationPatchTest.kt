package io.github.salatmaster.direnv.terminal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class FishIntegrationPatchTest {
    private fun original() = javaClass.getResource("/fish-integration-262.fish")!!.readText()

    @Test
    fun `unknown scripts are never silently replaced`() {
        assertThat(FishIntegrationPatch.patch(original() + "# changed upstream\n")).isNull()
    }

    @Test
    fun `quoted source paths round trip without changing unrelated arguments`() {
        val path = "/tmp/space and 'quote/fish-integration.fish"
        assertThat(FishIntegrationPatch.sourcePath(FishIntegrationPatch.sourceArgument(path))).isEqualTo(path)
        assertThat(FishIntegrationPatch.sourcePath("--init-command=echo hello")).isNull()
    }

    @Test
    fun `real fish preserves collision variables and consumes markers`() {
        val fish = System.getenv("ENVLET_TEST_FISH") ?: "/run/current-system/sw/bin/fish"
        assumeTrue(Files.isExecutable(java.nio.file.Path.of(fish)), "Set ENVLET_TEST_FISH to run fish integration regression")
        val trigger = mapOf("name" to "devenv-shell-env", "_INTELLIJ_FORCE_SET_name" to "devenv-shell-env")
        fun run(script: String, variables: Map<String, String>): Pair<String, String> {
            val builder = ProcessBuilder(fish, "--no-config", "-c", script + "\ncommand env\n")
            builder.environment().clear()
            builder.environment()["PATH"] = java.nio.file.Path.of(fish).parent.toString() + ":/usr/bin:/bin"
            builder.environment().putAll(variables)
            val process = builder.start()
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue()
            return process.inputStream.bufferedReader().readText() to process.errorStream.bufferedReader().readText()
        }
        assertThat(run(original(), trigger).second).contains("set: devenv-shell-env: invalid variable name")
        val fixed = FishIntegrationPatch.patch(original())!!
        for (key in listOf("name", "variable", "name_and_value", "value", "new_name", "DEMO")) {
            val expected = if (key == "name") "devenv-shell-env" else "canary=spaces and symbols-$key"
            val variables = trigger + mapOf(key to "inherited", "_INTELLIJ_FORCE_SET_$key" to expected)
            val (stdout, stderr) = run(fixed, variables)
            assertThat(stderr).isEmpty()
            val env = stdout.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            assertThat(env[key]).isEqualTo(expected)
            assertThat(env.keys).noneMatch { it.startsWith("_INTELLIJ_FORCE_SET_") }
        }
    }
}
