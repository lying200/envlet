// Investigation harness for two disposable, approved plain-direnv Cargo projects.
// No SDK is assigned by this script. Cargo manifests are attached as import setup.
// Properties: envlet.validation.project = WSL parent of split-tools/common-tools,
// envlet.validation.result = status-only output file. Use an isolated IDEA profile.
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.process.CapturingProcessHandler
import java.nio.file.Paths
import java.nio.file.Files
import java.util.concurrent.TimeUnit

def parent = Paths.get(System.getProperty("envlet.validation.project"))
def report = new File(System.getProperty("envlet.validation.result"))
report.text = "starting\n"
def record = { String message -> report.append(message + "\n") }
def waitUntil = { String description, Closure ready ->
    def deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3)
    while (!ready() && System.nanoTime() < deadline) Thread.sleep(200)
    if (!ready()) throw new AssertionError(description + " timed out")
}
def plugin = PluginManagerCore.getPlugin(PluginId.getId("io.github.lying200.envlet"))
assert plugin?.enabled
record("envlet=" + plugin.version)
def loader = plugin.pluginClassLoader

for (layout in ["split-tools", "common-tools"]) {
    def root = parent.resolve(layout)
    assert !Files.exists(root.resolve(".devenv"))
    assert Files.readString(root.resolve("Cargo.toml")).contains("libsqlite3-sys")
    record("case=" + layout)
    try {
        def project = ProjectManagerEx.instanceEx.openProject(root, OpenProjectTask.build().withForceOpenInNewFrame(true))
        assert project != null
        ApplicationManager.application.invokeAndWait({
            ApplicationManager.application.runWriteAction({
                def manager = com.intellij.openapi.module.ModuleManager.getInstance(project)
                if (manager.modules.length == 0) {
                    def module = manager.newModule(root.resolve("envlet-plain.iml"), "JAVA_MODULE")
                    def model = com.intellij.openapi.roots.ModuleRootManager.getInstance(module).modifiableModel
                    model.addContentEntry(VfsUtil.pathToUrl(project.basePath))
                    model.commit()
                }
            } as Runnable)
        } as Runnable)
        def environment = project.getService(loader.loadClass("io.github.salatmaster.direnv.DirenvService"))
        waitUntil("direnv load") { !(environment.state().class.simpleName in ["NotLoaded", "Loading"]) }
        record("direnv=" + environment.state().class.simpleName)
        assert environment.state().class.simpleName == "Loaded"
        def probe = new GeneralCommandLine(root.root.resolve("run/current-system/sw/bin/bash").toString(),
            "-c", 'test "$ENVLET_DIRENV_TEST" = "$1" && test "$CC" = clang && test -z "${DEVENV_ROOT-}" && command -v cc >/dev/null',
            "envlet-probe", layout).withWorkingDirectory(root)
        def probeOutput = new CapturingProcessHandler(probe).runProcess(15000)
        record("generic-process.environment=" + (probeOutput.exitCode == 0 && !probeOutput.timeout))
        assert probeOutput.exitCode == 0 && !probeOutput.timeout

        def settings = project.getService(loader.loadClass("org.rust.cargo.project.settings.RustProjectSettingsService"))
        def cargo = project.getService(loader.loadClass("org.rust.cargo.project.model.CargoProjectsService"))
        def imported = cargo.attachCargoProject(root.resolve("Cargo.toml"))
        waitUntil("Cargo import") { imported.isCompleted() }
        imported.getCompleted()
        // Give automatic discovery a bounded chance, including ordinary Cargo import.
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (settings.toolchain == null && System.nanoTime() < deadline) Thread.sleep(200)
        record("toolchain=" + (settings.toolchain?.class?.simpleName ?: "none"))
        if (settings.toolchain != null) record("toolchain.home=" + settings.toolchain.location)
        if (settings.toolchain != null) {
            // The settings event can schedule a second sync after initial attachment.
            // Await a refresh with the selected toolchain before reading final statuses.
            def refreshed = cargo.refreshAllProjects(false)
            waitUntil("Cargo refresh") { refreshed.isCompleted() }
            refreshed.getCompleted()
        }
        def loaded = cargo.allProjects.find { it.manifest == root.resolve("Cargo.toml") }
        record("cargo.workspace=" + loaded?.workspaceStatus?.class?.simpleName)
        record("cargo.buildScripts=" + loaded?.buildScriptEvaluationStatus?.class?.simpleName)
        if (settings.toolchain != null) {
            def cargoTool = loader.loadClass("org.rust.cargo.toolchain.tools.Cargo").getConstructors()
                .find { it.parameterCount == 2 }.newInstance(settings.toolchain, false)
            def commandClass = loader.loadClass("org.rust.cargo.toolchain.CargoCommandLine")
            def backtrace = loader.loadClass("org.rust.cargo.toolchain.BacktraceMode").DEFAULT
            def channel = loader.loadClass("org.rust.cargo.toolchain.RustChannel").DEFAULT
            for (terminal in [false, true]) {
                def command = commandClass.getConstructors().find { it.parameterCount == 12 }.newInstance(
                    "build", root, ["--offline", "--locked"], null, terminal, backtrace, null,
                    channel, EnvironmentVariablesData.DEFAULT, false, false, false)
                def output = new CapturingProcessHandler(cargoTool.toGeneralCommandLine(project, command)).runProcess(90000)
                record("cargo.build.terminal=" + terminal + ", exit=" + output.exitCode + ", timeout=" + output.timeout)
                // Classify the known compiler failure without persisting output/environment.
                def combined = output.stdout + output.stderr
                record("cargo.build.missing-cc=" + (combined.contains('tool "cc"') || combined.contains('linker `cc` not found')))
            }
        } else {
            record("cargo.build=skipped-no-toolchain")
        }
    } catch (Throwable failure) {
        record("ERROR=" + failure.class.simpleName)
        record("ERROR.at=" + failure.stackTrace.find { it.fileName?.endsWith(".groovy") })
        // Continue the independent second layout; no raw exception messages/env dumps.
    }
}
record("FINISHED")
