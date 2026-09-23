package io.github.salatmaster.direnv.terminal

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.platform.eel.EelDescriptor
import com.intellij.platform.eel.path.EelPath
import com.intellij.platform.eel.provider.asEelPath
import com.intellij.platform.eel.provider.asNioPath
import org.jetbrains.plugins.terminal.LocalTerminalCustomizer
import io.github.salatmaster.direnv.DirenvGuard
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Deprecated public terminal seam, limited to matching project/shell EEL descriptors in 262.
 * Copy scripts only, never environment values; preserve the IDE's companion scripts.
 */
@Suppress("UnstableApiUsage", "DEPRECATION")
class EnvletFishCompatibility : LocalTerminalCustomizer() {
    private val log = Logger.getInstance(EnvletFishCompatibility::class.java)

    override fun customizeCommandAndEnvironment(
        project: Project, workingDirectory: String?, shellCommand: List<String>,
        envs: MutableMap<String, String>, eelDescriptor: EelDescriptor,
    ): List<String> {
        if (!DirenvGuard.mayRun(project)) return shellCommand
        return try {
            patchCommand(shellCommand, eelDescriptor)
        } catch (_: Exception) {
            log.warn("Envlet could not prepare fish compatibility; original terminal command retained")
            shellCommand
        }
    }

    private fun patchCommand(shellCommand: List<String>, descriptor: EelDescriptor): List<String> {
        val index = shellCommand.indexOfFirst { FishIntegrationPatch.sourcePath(it) != null }
        if (index < 0) return shellCommand
        val source = FishIntegrationPatch.sourcePath(shellCommand[index]) ?: return shellCommand
        val original = EelPath.parse(source, descriptor).asNioPath()
        val patched = FishIntegrationPatch.patch(Files.readString(original))
        if (patched == null) {
            log.warn("Envlet fish compatibility skipped: unrecognized IDE integration script")
            return shellCommand
        }
        // The IDE already owns and cleans up this temporary integration parent directory.
        val directory = Files.createTempDirectory(original.parent, "envlet-")
        for (name in listOf("command-block-support.fish", "command-block-support-reworked.fish")) {
            val sibling = original.parent.resolve(name)
            if (Files.exists(sibling)) Files.copy(sibling, directory.resolve(name), StandardCopyOption.REPLACE_EXISTING)
        }
        val target = Files.writeString(directory.resolve("fish-integration.fish"), patched)
        val command = shellCommand.toMutableList()
        command[index] = FishIntegrationPatch.sourceArgument(target.asEelPath().toString())
        log.info("Envlet applied fish integration scope compatibility")
        return command
    }
}
