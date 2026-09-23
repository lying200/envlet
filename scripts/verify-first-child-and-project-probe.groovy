// ENV-20/21/22: module selection, first-child refusal and immediate child recovery.
// An external controller runs direnv deny/allow after the control-file handshakes.
// Watches are disabled until a first-time child observes refusal, then enabled for recovery.
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.execution.configurations.GeneralCommandLine
import java.nio.file.Paths
import java.nio.file.Files
import java.util.concurrent.TimeUnit

def root = Paths.get(System.getProperty('envlet.validation.project'))
def report = new File(System.getProperty('envlet.validation.result'))
report.text = 'STARTING\n'
def record = { report.append(it + '\n') }
def waitUntil = { String label, Closure ready ->
    def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
    while (!ready() && System.nanoTime() < deadline) Thread.sleep(100)
    assert ready() : label
}
try {
    def plugin = PluginManagerCore.getPlugin(PluginId.getId('io.github.lying200.envlet'))
    record('plugin=' + plugin.version)
    def loader = plugin.pluginClassLoader
    def project = ProjectManagerEx.instanceEx.openProject(root, OpenProjectTask.build().withForceOpenInNewFrame(true))
    assert project != null
    ApplicationManager.application.invokeAndWait({
        ApplicationManager.application.runWriteAction({
            def manager = com.intellij.openapi.module.ModuleManager.getInstance(project)
            if (manager.modules.length == 0) {
                def module = manager.newModule(root.resolve('envlet-watch.iml'), 'JAVA_MODULE')
                def model = com.intellij.openapi.roots.ModuleRootManager.getInstance(module).modifiableModel
                model.addContentEntry(VfsUtil.pathToUrl(project.basePath))
                model.commit()
            }
        } as Runnable)
    } as Runnable)
    def service = project.getService(loader.loadClass('io.github.salatmaster.direnv.DirenvService'))
    def customizer = loader.loadClass('io.github.salatmaster.direnv.inject.DirenvCommandLineEnvCustomizer').getConstructor().newInstance()
    def injected = {
        def values = new HashMap<String, String>()
        def command = new GeneralCommandLine('envlet-fixture-probe').withWorkingDirectory(root)
        ApplicationManager.application.invokeAndWait({ customizer.customizeEnv(command, values) } as Runnable)
        values.containsKey('ENVLET_ENV20_TEST')
    }
    def processProbe = { workingDirectory = root ->
        def shell = root.root.resolve('run/current-system/sw/bin/sh')
        def command = new GeneralCommandLine(shell.toString(), '-c', 'test -n "${ENVLET_ENV20_TEST-}"')
            .withWorkingDirectory(workingDirectory)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
        def process = command.createProcess()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw new AssertionError('Fixture process timed out')
        }
        assert process.exitValue() in [0, 1]
        process.exitValue() == 0
    }
    waitUntil('root loaded') { service.cachedFor(root) != null }
    def settings = project.getService(loader.loadClass('io.github.salatmaster.direnv.settings.DirenvSettings'))
    settings.state.watchFiles = false
    def sync = project.getService(loader.loadClass('io.github.salatmaster.direnv.toolchain.EnvletToolchainSync'))
    def go = project.getService(loader.loadClass('com.goide.sdk.GoSdkService'))
    def expectedHome = System.getProperty('envlet.validation.expectedGoHome')
    waitUntil('root Go SDK publication') {
        go.getSdk(null).isValid() && go.getSdk(null).homeUrl.replace('\\', '/').endsWith(expectedHome)
    }
    def rootHome = go.getSdk(null).homeUrl
    record('root.sdk=go1.26.0')
    def cwdLog = root.resolve('.probe/go-cwds')
    def initialCount = Files.readAllLines(cwdLog).size()
    def child = root.resolve('shared')
    service.scheduleLoad(child)
    waitUntil('shared child loaded') { service.cachedFor(child) != null }
    assert service.cachedFor(child).is(service.cachedFor(root))
    assert service.cachedFor(root).workingDir == child
    waitUntil('replacement project Go probe') { Files.readAllLines(cwdLog).size() > initialCount }
    assert Files.readAllLines(cwdLog).last() == System.getProperty('envlet.validation.linuxRoot')
    assert go.getSdk(null).homeUrl == rootHome
    record('shared-child.export-provenance-retained=true')
    record('shared-child.project-probe-cwd=root')
    record('shared-child.project-sdk-unchanged=true')
    def previous = service.cachedFor(root)
    assert injected()
    assert processProbe()
    assert service.cachedFor(root.resolve('unseen')) == null
    Files.writeString(root.resolve('.control/ready-revoke'), 'ready\n')
    waitUntil('controller revoked approval') { Files.exists(root.resolve('.control/revoked')) }
    assert !settings.state.watchFiles
    assert service.cachedFor(root).is(previous) // Watcher has not removed it for us.
    service.scheduleLoad(root.resolve('unseen'))
    waitUntil('first child removes revoked root') {
        service.cachedFor(root) == null && service.state().class.simpleName in ['Blocked', 'Denied']
    }
    assert !sync.isCurrent(previous)
    def refusalObservedAt = System.nanoTime()
    record('first-child.revocation-invalidates-root-and-sdk=true')
    assert !injected()
    assert !processProbe()
    record('automatic-revocation.root-cache-empty=true')
    record('automatic-revocation.process-injection=false')
    settings.state.watchFiles = true
    Files.writeString(root.resolve('.control/ready-allow'), 'ready\n')
    waitUntil('controller restored approval') { Files.exists(root.resolve('.control/allowed')) }
    waitUntil('watcher restores root') { service.cachedFor(root) != null }
    // Exercise the directory that recorded the refusal, not just the restored root.
    // The old implementation returned no environment here until its 60-second cooldown expired.
    def previouslyDeniedChild = root.resolve('unseen')
    assert service.cachedFor(previouslyDeniedChild) == null
    assert processProbe(previouslyDeniedChild)
    assert System.nanoTime() - refusalObservedAt < TimeUnit.SECONDS.toNanos(60)
    assert service.cachedFor(previouslyDeniedChild) != null
    record('automatic-approval.child-process-without-cooldown=true')
    assert injected()
    assert processProbe()
    record('automatic-approval.process-injection=true')
    record('real-wsl-processes.verified=true')
    record('ACCEPTANCE=true')
    record('FINISHED')
} catch (Throwable failure) {
    record('ERROR=' + failure.class.simpleName)
    record('ERROR.at=' + failure.stackTrace.find { it.fileName?.endsWith('.groovy') })
    throw failure
}
