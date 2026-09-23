package io.github.salatmaster.direnv.python

import com.intellij.execution.wsl.WslPath
import com.intellij.execution.wsl.target.WslTargetEnvironmentConfiguration
import com.intellij.execution.wsl.target.WslTargetEnvironmentRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.backend.workspace.impl.WorkspaceModelInternal
import com.jetbrains.python.run.PythonInterpreterTargetEnvironmentFactory
import com.jetbrains.python.sdk.PythonSdkAdditionalData
import com.jetbrains.python.sdk.PythonSdkType
import com.jetbrains.python.sdk.PythonSdkUpdater
import com.jetbrains.python.sdk.flavors.PyFlavorAndData
import com.jetbrains.python.sdk.flavors.PyFlavorData
import com.jetbrains.python.sdk.flavors.UnixPythonSdkFlavor
import com.jetbrains.python.target.PyTargetAwareAdditionalData
import io.github.salatmaster.direnv.DirenvMachine
import io.github.salatmaster.direnv.settings.DirenvSettings
import io.github.salatmaster.direnv.toolchain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files

/** Python APIs stay in this optional module; compatibility is pinned to the inspected 262 build. */
@Suppress("UnstableApiUsage")
class EnvletPythonStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Cached projects open before their disk model finishes loading. SDK writes before
        // this boundary can be discarded by JPS synchronization (including a Java SDK guard).
        (WorkspaceModel.getInstance(project) as WorkspaceModelInternal).awaitSynchronizationWithJpsModel()
        val sync = project.service<EnvletToolchainSync>()
        val settings = DirenvSettings.getInstance(project)
        sync.watch({ settings.state.autoPythonToolchain }, ToolchainLanguage.PYTHON) { environment ->
            fun report(reason: ToolchainReason) = sync.report(ToolchainLanguage.PYTHON, ToolchainStage.CONFIGURATION, reason)
            val root = DirenvMachine.projectDir(project) ?: return@watch
            val wsl = WslPath.parseWindowsUncPath(root.toString())
            if (wsl == null && (!DirenvMachine.isLocal(project) || !SystemInfo.isUnix)) {
                report(ToolchainReason.UNSUPPORTED_TARGET)
                return@watch
            }
            val machine = DirenvMachine.toolchainMachine(project)
            val executable = PythonToolchainPlan.discover(environment.entries, machine) ?: return@watch
            val output = sync.outputOrReport(ToolchainLanguage.PYTHON, ToolchainStage.PYTHON_INFO,
                sync.probe(environment, executable, listOf("-c", PythonToolchainPlan.PROBE))) ?: return@watch
            val plan = PythonToolchainPlan.fromOutput(output, machine, executable)?.takeIf { Files.isRegularFile(it.executable) }
                ?: run { report(ToolchainReason.INVALID_OUTPUT); return@watch }
            val configuration = wsl?.let { WslTargetEnvironmentConfiguration(it.distribution) }
            if (configuration != null && PythonInterpreterTargetEnvironmentFactory.EP_NAME.extensionList.none { it.isFor(configuration) }) {
                report(ToolchainReason.UNSUPPORTED_TARGET)
                return@watch
            }
            val flavor = PyFlavorAndData(PyFlavorData.Empty, UnixPythonSdkFlavor.getInstance())
            val data = if (wsl == null) PythonSdkAdditionalData(flavor, root)
                else PyTargetAwareAdditionalData(flavor, root, configuration, null).apply {
                    val mapper = DirenvMachine.pathMapper(project)
                    interpreterPath = mapper.toDirenv(plan.executable) ?: return@watch
                    // The platform discovers ordinary interpreter mappings. Envlet's probe
                    // paths belong only to the index library, not SDK runtime/transfer roots.
                }
            // Probe roots are for editor resolution, not unconditional Run PYTHONPATH additions.
            val indexRoots = plan.roots.mapNotNull { VfsUtil.findFile(it, true) }
            val home = if (data is PyTargetAwareAdditionalData) data.sdkId else plan.executable.toString()
            val name = "Envlet Python (${project.locationHash})"
            val table = ProjectJdkTable.getInstance()
            val sdk = withContext(Dispatchers.EDT) {
                if (!sync.isCurrent(environment) || !settings.state.autoPythonToolchain) return@withContext null
                if (!mayConfigure(ProjectRootManager.getInstance(project))) {
                    report(ToolchainReason.SDK_CONFLICT)
                    return@withContext null
                }
                // Discovery mutates an unpublished candidate, never the active SDK.
                val candidate = table.createSdk("$name ${plan.executable}", PythonSdkType.getInstance())
                ApplicationManager.getApplication().runWriteAction {
                    val model = candidate.sdkModificator
                    model.homePath = home
                    model.versionString = plan.version
                    model.sdkAdditionalData = data
                    model.commitChanges()
                }
                candidate
            } ?: return@watch
            if (!sync.isCurrent(environment)) return@watch
            if (configuration != null && PythonInterpreterTargetEnvironmentFactory.findPythonTargetInterpreter(sdk, project)
                    .targetEnvironmentRequest !is WslTargetEnvironmentRequest) {
                report(ToolchainReason.UNSUPPORTED_TARGET)
                return@watch
            }
            withContext(Dispatchers.EDT) {
                sync.applyIfCurrent(environment, { settings.state.autoPythonToolchain }) {
                    val roots = ProjectRootManager.getInstance(project)
                    if (mayConfigure(roots)) {
                        val published = ApplicationManager.getApplication().runWriteAction<com.intellij.openapi.projectRoots.Sdk> {
                            val existing = table.allJdks.firstOrNull { it.name.startsWith("$name ") && it.homePath == home }
                            val published = if (existing == null) {
                                table.addJdk(sdk)
                                sdk
                            } else {
                                table.updateJdk(existing, sdk)
                                existing
                            }
                            roots.projectSdk = published
                            PythonIndexLibrary.update(project, published, indexRoots)
                            published
                        }
                        // Only the published SDK owns background indexing. Updating a temporary
                        // candidate too can start concurrent skeleton generators for one path.
                        PythonSdkUpdater.scheduleUpdate(published, project)
                    }
                }
            }
        }
    }

    private fun mayConfigure(roots: ProjectRootManager): Boolean =
        // A named but unresolved SDK still belongs to the user (for example a local
        // Java JDK in a WSL project). A null resolved SDK does not mean no selection.
        roots.projectSdkName == null || roots.projectSdkTypeName == PythonSdkType.getInstance().name
}
