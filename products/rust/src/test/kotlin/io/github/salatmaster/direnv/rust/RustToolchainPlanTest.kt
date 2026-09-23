// Added for ENV-17: source and optional-tool policy independent of SDK writes.
package io.github.salatmaster.direnv.rust

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path

class RustToolchainPlanTest {
    private val root = Path.of("rust-plan").toAbsolutePath()
    private val rustc = root.resolve("rustc/bin/rustc")
    private val cargo = root.resolve("cargo/bin/cargo")
    private val sources = root.resolve("sources")
    private val fallback = root.resolve("lib/rustlib/src/rust/library")

    @Test fun `configured valid sources take precedence over sysroot sources`() {
        val plan = RustToolchainPlan.fromDiscovery(rustc, cargo, sources, root, emptyMap()) { true }
        assertThat(plan.sources).isEqualTo(sources)
    }

    @Test fun `missing configured sources falls back to validated sysroot sources`() {
        val plan = RustToolchainPlan.fromDiscovery(rustc, cargo, sources, root, emptyMap()) { it == fallback }
        assertThat(plan.sources).isEqualTo(fallback)
    }

    @Test fun `missing sources and optional tools still produce a usable compiler plan`() {
        val plan = RustToolchainPlan.fromDiscovery(rustc, cargo, null, root, emptyMap()) { false }
        assertThat(plan.sources).isNull()
        assertThat(plan.tools).containsExactlyEntriesOf(linkedMapOf("rustc" to rustc, "cargo" to cargo))
    }

    @Test fun `optional tools cannot override selected compilers or add unsupported entries`() {
        val formatter = root.resolve("fmt/rustfmt")
        val plan = RustToolchainPlan.fromDiscovery(rustc, cargo, null, root,
            mapOf("rustfmt" to formatter, "rustc" to formatter, "other" to formatter)) { false }
        assertThat(plan.tools).containsExactlyEntriesOf(linkedMapOf("rustc" to rustc, "cargo" to cargo, "rustfmt" to formatter))
    }
}
