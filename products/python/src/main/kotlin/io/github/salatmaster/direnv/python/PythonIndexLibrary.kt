package io.github.salatmaster.direnv.python

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.VirtualFile

/** Python resolves library SOURCES, but adds only library CLASSES to Run's PYTHONPATH.
 * Keep environment discovery out of SDK user-added paths, which are runtime overrides.
 * A module library also survives the Python SDK updater replacing interpreter roots.
 */
internal object PythonIndexLibrary {
    private const val NAME = "Envlet Python search paths"

    /** Called in the same guarded write action that publishes the selected SDK. */
    fun update(project: Project, sdk: Sdk, paths: List<VirtualFile>) {
        ApplicationManager.getApplication().assertWriteAccessAllowed()
        for (module in ModuleManager.getInstance(project).modules) {
            val manager = ModuleRootManager.getInstance(module)
            if (manager.sdk !== sdk) continue
            val model = manager.modifiableModel
            try {
                val library = model.moduleLibraryTable.getLibraryByName(NAME)
                    ?: model.moduleLibraryTable.createLibrary(NAME)
                val libraryModel = library.modifiableModel
                // This library is exclusively owned by Envlet, not a user's SDK path list.
                for (type in listOf(OrderRootType.SOURCES, OrderRootType.CLASSES)) {
                    libraryModel.getUrls(type).forEach { libraryModel.removeRoot(it, type) }
                }
                paths.filter { it !in manager.contentRoots }.forEach {
                    libraryModel.addRoot(it, OrderRootType.SOURCES)
                }
                libraryModel.commit()
                model.commit()
            } finally {
                if (!model.isDisposed) model.dispose()
            }
        }
    }
}
