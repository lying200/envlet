// Run with idea64.exe ideScript in an isolated IDEA profile, against a disposable,
// trusted WSL fixture with an approved .envrc and libsqlite3-sys 0.35.0 (bundled).
// Set envlet.validation.project and envlet.validation.result in idea.properties.
// The script imports the fixture; Envlet itself does not attach Cargo projects.
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import java.nio.file.Paths
import java.nio.file.Files
import java.util.concurrent.TimeUnit

def projectPath = System.getProperty("envlet.validation.project")
def resultPath = System.getProperty("envlet.validation.result")
assert projectPath && resultPath : "Explicit disposable fixture and result paths are required"
def root = Paths.get(projectPath)
def manifest = Files.readString(root.resolve("Cargo.toml"))
assert manifest.contains("libsqlite3-sys") && manifest.contains("bundled")
def report = new File(resultPath)
report.text = "starting\n"
def record = { String message -> report.append(message + "\n") }
def waitUntil = { String description, Closure ready ->
    def deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3)
    while (!ready() && System.nanoTime() < deadline) Thread.sleep(200)
    assert ready() : description + " timed out"
}

try {
    def plugin = PluginManagerCore.getPlugin(PluginId.getId("io.github.lying200.envlet"))
    assert plugin?.enabled
    def loader = plugin.pluginClassLoader
    def project = ProjectManagerEx.instanceEx.openProject(root, OpenProjectTask.build().withForceOpenInNewFrame(true))
    assert project != null
    // ideScript does not perform the ordinary project import wizard.
    ApplicationManager.application.invokeAndWait({
        ApplicationManager.application.runWriteAction({
            def manager = com.intellij.openapi.module.ModuleManager.getInstance(project)
            if (manager.modules.length == 0) {
                def module = manager.newModule(root.resolve("envlet-native.iml"), "JAVA_MODULE")
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

    def settings = project.getService(loader.loadClass("org.rust.cargo.project.settings.RustProjectSettingsService"))
    waitUntil("automatic Rust toolchain") { settings.toolchain != null }
    record("toolchain=" + settings.toolchain.class.simpleName)
    // Exercise PATH construction before Envlet's loaded environment can hide it.
    def linuxHome = settings.toolchain.toRemotePath(settings.toolchain.location)
    def canary = "envlet-private-path-canary"
    for (suppliedPath in ["/fixture/${canary}/tool dir:/fixture/second::/fixture/last".toString(), "", linuxHome + ":/fixture/bin"]) {
        for (parent in GeneralCommandLine.ParentEnvironmentType.values()) {
            def explicitPath = new GeneralCommandLine("cargo")
                .withParentEnvironmentType(parent).withEnvironment("PATH", suppliedPath)
            settings.toolchain.patchCommandLine(explicitPath, false, true)
            def expected = suppliedPath.startsWith(linuxHome + ":") ? suppliedPath : linuxHome + ":" + suppliedPath
            assert explicitPath.environment["PATH"] == expected : "WSL PATH must use POSIX home and separator"
            settings.toolchain.patchCommandLine(explicitPath, false, true)
            assert explicitPath.environment["PATH"] == expected : "Repeated patch must not duplicate the toolchain"
            assert !explicitPath.commandLineString.contains(canary) : "Environment value leaked into command arguments"
        }
    }
    def untouched = new GeneralCommandLine("cargo").withEnvironment("PATH", "/fixture/unchanged")
    settings.toolchain.patchCommandLine(untouched, false, false)
    assert untouched.environment["PATH"] == "/fixture/unchanged"
    record("path.explicit-posix=passed")
    // Outside the open project's roots: no direnv cache may conceal a bad PATH.
    def probe = {
        new GeneralCommandLine(settings.toolchain.toLocalPath("/run/current-system/sw/bin/bash"),
            "-c", 'printf "%s" "$PATH"').withWorkingDirectory(root.parent)
    }
    for (parent in GeneralCommandLine.ParentEnvironmentType.values()) {
        def inherited = probe().withParentEnvironmentType(parent)
        settings.toolchain.patchCommandLine(inherited, false, true)
        assert !inherited.environment.containsKey("PATH") : "Missing PATH override must remain absent"
        if (parent == GeneralCommandLine.ParentEnvironmentType.NONE) {
            assert !inherited.effectiveEnvironment.containsKey("PATH") : "Disabled inheritance must remain disabled"
        } else {
            def baseline = new CapturingProcessHandler(probe().withParentEnvironmentType(parent)).runProcess(15000)
            def actual = new CapturingProcessHandler(inherited).runProcess(15000)
            assert !baseline.timeout && !actual.timeout && baseline.exitCode == 0 && actual.exitCode == 0
            // Keep environment values in memory, including assertion diagnostics.
            if (actual.stdout != baseline.stdout) throw new AssertionError("WSL inherited PATH changed")
        }
    }
    record("path.target-inheritance-and-none=passed")
    def provider = loader.loadClass("io.github.salatmaster.direnv.rust.EnvletRustToolchainProvider")
        .getConstructor().newInstance()
    def home = root.resolve(".devenv/profile/bin")
    assert provider.getToolchain(home) != null
    assert provider.getToolchain(root.resolve("unmanaged/bin")) == null
    assert provider.getToolchain(root.resolve("../other-project/.devenv/profile/bin").normalize()) == null
    def options = project.getService(loader.loadClass("io.github.salatmaster.direnv.settings.DirenvSettings"))
    options.state.autoRustToolchain = false
    try { assert provider.getToolchain(home) == null }
    finally { options.state.autoRustToolchain = true }
    options.state.enabled = false
    try { assert provider.getToolchain(home) == null }
    finally { options.state.enabled = true }
    def customWrapper = new com.intellij.execution.configurations.GeneralCommandLine("cargo")
        .withEnvironment("RUSTC_WRAPPER", "/fixture/custom-rustc-wrapper")
    settings.toolchain.patchCommandLine(customWrapper, false, false)
    assert customWrapper.environment["RUSTC_WRAPPER"] == "/fixture/custom-rustc-wrapper"
    record("provider.scope-and-disable=passed")
    record("custom-rustc-wrapper=preserved")
    def cargo = project.getService(loader.loadClass("org.rust.cargo.project.model.CargoProjectsService"))
    def imported = cargo.attachCargoProject(root.resolve("Cargo.toml"))
    waitUntil("Cargo import") { imported.isCompleted() }
    imported.getCompleted()
    def loaded = cargo.allProjects.find { it.manifest == root.resolve("Cargo.toml") }
    assert loaded != null
    record("cargo.workspace=" + loaded.workspaceStatus.class.simpleName)
    record("cargo.buildScripts=" + loaded.buildScriptEvaluationStatus.class.simpleName)
    assert loaded.workspaceStatus.class.simpleName == "UpToDate"
    assert loaded.buildScriptEvaluationStatus.class.simpleName == "UpToDate" : "Native build-script evaluation failed"

    def cargoTool = loader.loadClass("org.rust.cargo.toolchain.tools.Cargo").getConstructors()
        .find { it.parameterCount == 2 }.newInstance(settings.toolchain, false)
    def commandClass = loader.loadClass("org.rust.cargo.toolchain.CargoCommandLine")
    def backtrace = loader.loadClass("org.rust.cargo.toolchain.BacktraceMode").DEFAULT
    def channel = loader.loadClass("org.rust.cargo.toolchain.RustChannel").DEFAULT
    for (terminal in [false, true]) {
        def command = commandClass.getConstructors().find { it.parameterCount == 12 }.newInstance(
            "build", root, ["--offline", "--locked"], null, terminal, backtrace, null,
            channel, EnvironmentVariablesData.DEFAULT, false, false, false)
        def process = cargoTool.toGeneralCommandLine(project, command)
        def output = new CapturingProcessHandler(process).runProcess(90000)
        record("cargo.build.terminal=" + terminal + ", exit=" + output.exitCode)
        // Do not persist subprocess environment values or arbitrary compiler output.
        assert !output.timeout && output.exitCode == 0 : "Cargo build failed"
    }
    waitUntil("indexing") { !com.intellij.openapi.project.DumbService.getInstance(project).dumb }
    def source = VfsUtil.findFile(root.resolve("src/main.rs"), true)
    def resolved = ApplicationManager.application.runReadAction({
        def psi = com.intellij.psi.PsiManager.getInstance(project).findFile(source)
        assert psi.text.contains("std::env::consts::OS") : "Fixture must reference the Rust standard library"
        psi.findReferenceAt(psi.text.indexOf("OS"))?.resolve()?.containingFile?.virtualFile?.path
    } as com.intellij.openapi.util.Computable)
    assert resolved != null : "Rust standard library reference unresolved"
    record("rust.stdlib-reference=resolved")
    record("FINISHED")
} catch (Throwable failure) {
    record("FAILED=" + failure.class.simpleName)
    throw failure
}
