package io.github.salatmaster.direnv

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.salatmaster.direnv.settings.DirenvSettings

/**
 * Warms the environment cache when a project opens.
 *
 * The command line customizer can wait only on background threads without IDE locks.
 * Startup warming avoids that wait and makes the root environment available to cache-only
 * callers on the EDT or under a read/write lock.
 */
class DirenvStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (!DirenvSettings.getInstance(project).state.autoLoad) return
        if (!DirenvGuard.mayRun(project)) return

        val workingDir = DirenvMachine.projectDir(project) ?: return

        DirenvService.getInstance(project).load(workingDir)
    }
}
