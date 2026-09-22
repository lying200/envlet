package io.github.salatmaster.direnv.terminal

import java.security.MessageDigest

/** Compatibility seam for IDEA 262.10968.63; unknown upstream scripts stay untouched. */
object FishIntegrationPatch {
    private const val KNOWN_SHA256 = "aa88f5dfd44a3b16a8a6307aacfcc7c92a29a8207d3b7793b1d496e4e93b70c2"
    const val SOURCE_PREFIX = "--init-command=source "

    fun patch(original: String): String? {
        val normalized = original.replace("\r\n", "\n")
        val hash = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
            .joinToString("") { "%02x".format(it) }
        if (hash != KNOWN_SHA256) return null
        var patched = normalized.replace("  for variable in (env)", "  set -l -u variable\n  for variable in (env)")
        for (name in listOf("name_and_value", "name", "value", "new_name")) {
            patched = patched.replace("set $name ", "set -l -u $name ")
        }
        return "# Envlet: local, unexported scratch variables prevent devenv name collisions.\n$patched"
    }

    /** Decode only the quoting forms emitted for a simple integration script path. */
    fun sourcePath(argument: String): String? {
        if (!argument.startsWith(SOURCE_PREFIX)) return null
        val quoted = argument.removePrefix(SOURCE_PREFIX)
        val path = when {
            quoted.startsWith("'") && quoted.endsWith("'") ->
                quoted.substring(1, quoted.length - 1).replace("'\\''", "'")
            quoted.none { it.isWhitespace() || it in "'\";$`\\" } -> quoted
            else -> return null
        }
        return path.takeIf { it.endsWith("/fish-integration.fish") }
    }

    fun sourceArgument(path: String): String = SOURCE_PREFIX + "'" + path.replace("'", "'\\''") + "'"
}
