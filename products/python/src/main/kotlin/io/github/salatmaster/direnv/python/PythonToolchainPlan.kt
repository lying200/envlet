package io.github.salatmaster.direnv.python

import io.github.salatmaster.direnv.toolchain.ToolchainMachine
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

/** Only SDK paths/version leave the probe. The interpreter path must retain venv symlinks. */
internal class PythonToolchainPlan(val executable: Path, val version: String, val roots: List<Path>) {
    override fun toString() = "PythonToolchainPlan(<paths redacted>)"

    companion object {
        const val PROBE = "import json,sys; print(json.dumps(dict(executable=sys.executable,version=list(sys.version_info[:3]),paths=sys.path)))"

        fun discover(entries: Map<String, String?>, machine: ToolchainMachine): Path? {
            for (entry in machine.splitPath(entries["PATH"] ?: "")) {
                val directory = entry.takeIf(String::isNotBlank)?.let(machine::path)?.takeIf(Path::isAbsolute) ?: continue
                for (name in listOf("python", "python3")) {
                    val file = directory.resolve(machine.executable(name))
                    if (Files.isRegularFile(file)) return file
                }
            }
            return null
        }

        fun fromOutput(output: String, machine: ToolchainMachine, launcher: Path? = null): PythonToolchainPlan? = try {
            val value = Json.parseToJsonElement(output) as? JsonObject
            fun path(text: String) = machine.path(text)?.takeIf(Path::isAbsolute)
            val executable = (value?.get("executable") as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::path)
            val version = (value?.get("version") as? JsonArray)?.map { (it as? JsonPrimitive)?.intOrNull }
            val paths = value?.get("paths") as? JsonArray
            if (executable == null || version?.size != 3 || version.any { it == null || it < 0 } || paths == null) null
            else {
                val roots = paths.map {
                    val text = (it as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                    if (text.isBlank()) null else path(text) ?: return null
                }.filterNotNull().distinct()
                // Keep a Nix wrapper selected from PATH; sys.executable can name its unwrapped binary.
                PythonToolchainPlan(launcher ?: executable, "Python ${version.joinToString(".")}", roots)
            }
        } catch (_: IllegalArgumentException) { null }
    }
}
