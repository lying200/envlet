package io.github.salatmaster.direnv.rust

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.nio.file.Path

@EnabledOnOs(OS.LINUX, OS.MAC)
class ManagedRustToolchainHomeTest {
    @TempDir lateinit var root: Path

    private fun tool(name: String): Path = root.resolve(name).also { Files.writeString(it, "tool") }
    private fun project(name: String = "project"): Path = Files.createDirectory(root.resolve(name))

    @Test fun `assembles separate tools and ignores only its own cache`() {
        val rustc = tool("rust compiler")
        val cargo = tool("cargo")
        val project = project()
        Files.writeString(project.resolve(".gitignore"), "user content\n")
        val home = ManagedRustToolchainHome.prepare(project, mapOf("rustc" to rustc, "cargo" to cargo))
        assertThat(Files.isSymbolicLink(home.resolve("rustc"))).isTrue()
        assertThat(Files.isSameFile(home.resolve("rustc"), rustc)).isTrue()
        assertThat(Files.isSameFile(home.resolve("cargo"), cargo)).isTrue()
        assertThat(Files.readString(project.resolve(".gitignore"))).isEqualTo("user content\n")
        assertThat(Files.readString(project.resolve(".direnv/envlet/.gitignore"))).isEqualTo("*\n")
        assertThat(Files.list(home).use { it.count() }).isEqualTo(2)
    }

    @Test fun `reuses a mapping but gives changed tools a new immutable home`() {
        val project = project()
        val first = mapOf("rustc" to tool("rustc-v1"), "cargo" to tool("cargo"))
        val home = ManagedRustToolchainHome.prepare(project, first)
        assertThat(ManagedRustToolchainHome.prepare(project, first.entries.reversed().associate { it.toPair() })).isEqualTo(home)
        val updated = first + ("rustc" to tool("rustc-v2"))
        val next = ManagedRustToolchainHome.prepare(project, updated)
        assertThat(next).isNotEqualTo(home)
        assertThat(Files.isSameFile(home.resolve("rustc"), first.getValue("rustc"))).isTrue()
        assertThat(Files.isSameFile(next.resolve("rustc"), updated.getValue("rustc"))).isTrue()
    }

    @Test fun `shared tools still have distinct project homes`() {
        val tools = mapOf("rustc" to tool("rustc"), "cargo" to tool("cargo"))
        val first = ManagedRustToolchainHome.prepare(project("first"), tools)
        val second = ManagedRustToolchainHome.prepare(project("second"), tools)
        assertThat(first).isNotEqualTo(second)
        assertThat(Files.isSameFile(first.resolve("rustc"), second.resolve("rustc"))).isTrue()
    }

    @Test fun `does not overwrite a conflicting entry`() {
        val project = project()
        val tools = mapOf("rustc" to tool("rustc"), "cargo" to tool("cargo"))
        val home = ManagedRustToolchainHome.prepare(project, tools)
        Files.delete(home.resolve("cargo"))
        Files.writeString(home.resolve("cargo"), "existing user content")
        assertThatThrownBy { ManagedRustToolchainHome.prepare(project, tools) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(Files.readString(home.resolve("cargo"))).isEqualTo("existing user content")
    }

    @Test fun `rejects a redirected cache and unsafe tool names before writing links`() {
        val project = project()
        val elsewhere = Files.createDirectory(root.resolve("elsewhere"))
        Files.createSymbolicLink(project.resolve(".direnv"), elsewhere)
        val tools = mapOf("rustc" to tool("rustc"), "cargo" to tool("cargo"))
        assertThatThrownBy { ManagedRustToolchainHome.prepare(project, tools) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(Files.list(elsewhere).use { it.count() }).isZero()
        assertThatThrownBy { ManagedRustToolchainHome.prepare(project("safe"), tools + ("../escape" to tools.getValue("cargo"))) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
