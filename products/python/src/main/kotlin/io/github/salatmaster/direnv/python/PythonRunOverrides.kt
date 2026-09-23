package io.github.salatmaster.direnv.python

import com.intellij.execution.EnvFilesOptions
import com.intellij.execution.ExecutionException
import com.intellij.execution.util.configureEnvsFromFiles
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.extension

/** Explicit Run configuration values, separate from interpreter/IDE-generated variables. */
internal object PythonRunOverrides {
    fun read(options: EnvFilesOptions, direct: Map<String, String>): Map<String, String> {
        // Python has already evaluated these files before calling our extension. Plain
        // files can be parsed again, but replaying an environment script has side effects.
        // The extension does not expose the provenance of its merged execution map.
        if (options.envFilePaths.any { Path.of(it).extension.lowercase(Locale.ROOT) in setOf("sh", "bat") }) {
            throw ExecutionException("Envlet: Python WSL Run environment scripts are not supported. " +
                "Use a plain env file or run from a direnv-loaded terminal.")
        }
        return configureEnvsFromFiles(options).toMutableMap().apply { putAll(direct) }
    }
}
