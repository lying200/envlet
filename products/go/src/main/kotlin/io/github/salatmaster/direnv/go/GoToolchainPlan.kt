// Added for ENV-17: parse discovery output before touching IDE SDK settings.
package io.github.salatmaster.direnv.go

import io.github.salatmaster.direnv.toolchain.ToolchainMachine
import io.github.salatmaster.direnv.toolchain.ToolchainReason
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path

internal class GoToolchainPlan(val home: Path, val roots: List<Path>) {
    sealed interface Result {
        class Ready(val plan: GoToolchainPlan) : Result
        data class Rejected(val reason: ToolchainReason) : Result
    }

    companion object {
        fun fromOutput(output: String, machine: ToolchainMachine): Result {
            val values = try { Json.parseToJsonElement(output) as? JsonObject }
                catch (_: SerializationException) { null }
                catch (_: IllegalArgumentException) { null }
                ?: return Result.Rejected(ToolchainReason.INVALID_OUTPUT)
            fun string(name: String): String? = (values[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val homeValue = string("GOROOT")?.takeIf { it.isNotBlank() }
                ?: return Result.Rejected(ToolchainReason.INVALID_OUTPUT)
            val pathValue = string("GOPATH") ?: return Result.Rejected(ToolchainReason.INVALID_OUTPUT)
            val home = machine.path(homeValue)?.takeIf { it.isAbsolute }
                ?: return Result.Rejected(ToolchainReason.PATH_MAPPING)
            val roots = mutableListOf<Path>()
            for (part in machine.splitPath(pathValue).filter { it.isNotBlank() }) {
                val path = machine.path(part)?.takeIf { it.isAbsolute }
                    ?: return Result.Rejected(ToolchainReason.PATH_MAPPING)
                roots.add(path)
            }
            return Result.Ready(GoToolchainPlan(home, roots.distinct()))
        }
    }
}
