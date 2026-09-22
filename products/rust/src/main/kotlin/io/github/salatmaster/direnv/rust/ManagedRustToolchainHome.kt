package io.github.salatmaster.direnv.rust

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest

/** A real, project-owned SDK home containing only links to the selected POSIX tools. */
internal object ManagedRustToolchainHome {
    fun prepare(projectRoot: Path, tools: Map<String, Path>): Path {
        require(tools.keys.containsAll(listOf("rustc", "cargo")))
        require(tools.keys.all { it.matches(Regex("[a-z][a-z0-9-]*")) })
        val targets = tools.toSortedMap().mapValues { (_, path) ->
            path.toRealPath().also { require(Files.isRegularFile(it)) }
        }
        val identity = targets.entries.joinToString("\u0000") { (name, path) -> "$name\u0000$path" }
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        var directory = projectRoot
        for (part in listOf(".direnv", "envlet", "rust", digest, "bin")) {
            directory = directory.resolve(part)
            try {
                Files.createDirectory(directory)
            } catch (_: FileAlreadyExistsException) {
                require(Files.isDirectory(directory, NOFOLLOW_LINKS)) { "Managed SDK directory is not a directory" }
            }
        }
        // Ignore only Envlet's generated cache, without editing the project's .gitignore.
        try {
            Files.writeString(projectRoot.resolve(".direnv/envlet/.gitignore"), "*\n", CREATE_NEW)
        } catch (_: FileAlreadyExistsException) {
            // Preserve an existing ignore file; never overwrite project content.
        }
        for ((name, target) in targets) {
            val link = directory.resolve(name)
            try {
                Files.createSymbolicLink(link, target)
            } catch (_: FileAlreadyExistsException) {
                require(Files.isSymbolicLink(link) && Files.isSameFile(link, target)) {
                    "Managed SDK entry does not match the selected tool"
                }
            }
        }
        // Entries are immutable: a changed mapping gets a new home, so in-flight builds
        // keep their old SDK. Only executable paths are persisted, never the environment.
        return directory
    }
}
