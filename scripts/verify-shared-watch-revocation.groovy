// ENV-18/19: authored disposable fixture only, isolated IDEA profile.
// An external controller runs direnv deny/allow after the control-file handshakes.
// No manual service refresh or synthetic VFS event is sent after approval changes.
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
        values.containsKey('ENVLET_ENV18_TEST')
    }
    def processProbe = {
        def shell = root.root.resolve('run/current-system/sw/bin/sh')
        def command = new GeneralCommandLine(shell.toString(), '-c', 'test -n "${ENVLET_ENV18_TEST-}"')
            .withWorkingDirectory(root)
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
    if (Boolean.getBoolean('envlet.validation.ancestorEnvrc')) {
        assert service.cachedFor(root).loadedRcPath == root.parent.resolve('.envrc')
        record('project.uses-ancestor-envrc=true')
    }
    def child = root.resolve('shared')
    service.scheduleLoad(child)
    waitUntil('shared child loaded') { service.cachedFor(child) != null }
    assert service.cachedFor(child).is(service.cachedFor(root))
    record('root-and-child.share-environment=true')
    def previous = service.cachedFor(root)
    service.scheduleReload(root)
    waitUntil('root refreshed') { service.cachedFor(root) != null && !service.cachedFor(root).is(previous) }
    assert service.cachedFor(child) == null
    assert injected()
    assert processProbe()
    record('root.refreshed-child-alias-removed=true')
    Files.writeString(root.resolve('.control/ready-revoke'), 'ready\n')
    waitUntil('controller revoked approval') { Files.exists(root.resolve('.control/revoked')) }
    waitUntil('watcher removes revoked root') {
        service.cachedFor(root) == null && service.state().class.simpleName in ['Blocked', 'Denied']
    }
    assert !injected()
    assert !processProbe()
    record('automatic-revocation.root-cache-empty=true')
    record('automatic-revocation.process-injection=false')
    Files.writeString(root.resolve('.control/ready-allow'), 'ready\n')
    waitUntil('controller restored approval') { Files.exists(root.resolve('.control/allowed')) }
    waitUntil('watcher restores root') { service.cachedFor(root) != null }
    if (Boolean.getBoolean('envlet.validation.ancestorEnvrc')) {
        assert service.cachedFor(root).loadedRcPath == root.parent.resolve('.envrc')
        record('automatic-approval.project-alias-restored=true')
    }
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
