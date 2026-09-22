package io.github.salatmaster.direnv.rust

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.salatmaster.direnv.direnv.DirenvEnvironment
import io.github.salatmaster.direnv.toolchain.EnvletToolchainSync
import java.nio.file.Path

/** In-memory ownership, published only after successful discovery and SDK validation. */
@Service(Service.Level.PROJECT)
class EnvletRustToolchainBinding(private val project: Project) {
    private class Binding(val home: Path, val environment: DirenvEnvironment)
    @Volatile private var binding: Binding? = null

    fun publish(home: Path, environment: DirenvEnvironment) {
        binding = Binding(home.normalize(), environment)
    }

    fun owns(home: Path): Boolean {
        val current = binding ?: return false
        return current.home == home.normalize() && project.service<EnvletToolchainSync>().isCurrent(current.environment)
    }
}
