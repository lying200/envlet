// Added for ENV-17: turn discovered tools/sources into a plan independently of SDK writes.
package io.github.salatmaster.direnv.rust

import java.nio.file.Path

internal class RustToolchainPlan(val tools: Map<String, Path>, val sources: Path?) {
    companion object {
        val OPTIONAL_TOOLS = listOf("rustdoc", "rustfmt", "cargo-fmt", "clippy-driver", "cargo-clippy", "rust-gdb", "rust-lldb")

        fun fromDiscovery(
            rustc: Path, cargo: Path, configuredSources: Path?, sysroot: Path,
            optionalTools: Map<String, Path>, hasSources: (Path) -> Boolean,
        ): RustToolchainPlan {
            val sources = sequenceOf(configuredSources, sysroot.resolve("lib/rustlib/src/rust/library"))
                .filterNotNull().firstOrNull(hasSources)
            val tools = linkedMapOf("rustc" to rustc, "cargo" to cargo)
            for (name in OPTIONAL_TOOLS) optionalTools[name]?.let { tools[name] = it }
            // Missing sources/formatters do not make the compiler unavailable.
            return RustToolchainPlan(tools, sources)
        }
    }
}
