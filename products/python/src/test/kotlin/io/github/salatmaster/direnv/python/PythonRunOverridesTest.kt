package io.github.salatmaster.direnv.python

import com.intellij.execution.EnvFilesOptions
import com.intellij.execution.ExecutionException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PythonRunOverridesTest {
    @TempDir lateinit var directory: Path

    private fun options(vararg contents: String): EnvFilesOptions = object : EnvFilesOptions {
        override var envFilePaths = contents.mapIndexed { index, text ->
            Files.writeString(directory.resolve("run-$index.env"), text).toString()
        }
    }

    @Test fun `env file overrides direnv while implicit execution values do not`() {
        val explicit = PythonRunOverrides.read(options("MODE=test\n"), emptyMap())
        val result = PythonRunEnvironment.merge(mapOf("MODE" to "test", "IMPLICIT" to "ide"),
            mapOf("MODE" to "development", "IMPLICIT" to "direnv"), explicit, ":")
        assertThat(result).containsEntry("MODE", "test").containsEntry("IMPLICIT", "direnv")
    }

    @Test fun `env file supplies an explicit replacement for a direnv unset`() {
        val explicit = PythonRunOverrides.read(options("MODE=test\n"), emptyMap())
        val direnv = mapOf("MODE" to null)
        assertThat(PythonRunEnvironment.requiresInheritedUnset(direnv, explicit)).isFalse()
        assertThat(PythonRunEnvironment.merge(mapOf("MODE" to "test"), direnv, explicit, ":"))
            .containsEntry("MODE", "test")
    }

    @Test fun `direct settings win over ordered files without mutating settings or leaking into another run`() {
        val direct = mapOf("MODE" to "direct")
        val explicit = PythonRunOverrides.read(options("MODE=first\nTOKEN=envlet-envfile-canary\n", "MODE=second\n"), direct)
        assertThat(explicit).containsEntry("MODE", "direct").containsEntry("TOKEN", "envlet-envfile-canary")
        assertThat(direct).containsExactlyEntriesOf(mapOf("MODE" to "direct"))
        assertThat(PythonRunOverrides.read(options(), emptyMap())).isEmpty()
    }

    @Test fun `later env files win and env file pythonpath keeps platform helper paths`() {
        val explicit = PythonRunOverrides.read(options("MODE=first\n", "MODE=second\nPYTHONPATH=/explicit\n"), emptyMap())
        val result = PythonRunEnvironment.merge(mapOf("PYTHONPATH" to "/explicit:/helpers"),
            mapOf("MODE" to "direnv", "PYTHONPATH" to "/direnv"), explicit, ":")
        assertThat(result).containsEntry("MODE", "second").containsEntry("PYTHONPATH", "/explicit:/helpers")
    }

    @Test fun `empty env file value is an explicit replacement not an unset`() {
        val explicit = PythonRunOverrides.read(options("MODE=\n"), emptyMap())
        assertThat(PythonRunEnvironment.requiresInheritedUnset(mapOf("MODE" to null), explicit)).isFalse()
        assertThat(PythonRunEnvironment.merge(emptyMap(), mapOf("MODE" to null), explicit, ":"))
            .containsEntry("MODE", "")
    }

    @Test fun `environment scripts are never executed again to recover their variables`() {
        val marker = directory.resolve("must-not-exist")
        for (extension in listOf("sh", "BAT")) {
            val script = Files.writeString(directory.resolve("run.$extension"), "echo ran > \"$marker\"\n")
            val options = object : EnvFilesOptions { override var envFilePaths = listOf(script.toString()) }
            assertThatThrownBy { PythonRunOverrides.read(options, emptyMap()) }
                .isInstanceOf(ExecutionException::class.java).hasMessageContaining("environment scripts")
            assertThat(marker).doesNotExist()
        }
    }
}
