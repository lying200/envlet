// ENV-14 acceptance. Run only against an authored, approved plain-direnv fixture.
// envlet.validation.project: parent containing split-tools (verify-plain-direnv-rust fixture).
// envlet.validation.result: status-only report path. Use an isolated IDEA profile.
// Creates unapproved nested .envrc files; never approves or modifies the root .envrc.
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import java.nio.file.Paths
import java.nio.file.Files
import java.util.concurrent.TimeUnit

def report = new File(System.getProperty("envlet.validation.result"))
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
    def root = Paths.get(System.getProperty("envlet.validation.project")).resolve("split-tools")
    def fixture = Files.createTempDirectory(root, 'review-subdir-')
    def first = Files.createDirectories(fixture.resolve('first'))
    def blocked = Files.createDirectories(fixture.resolve('blocked'))
    def healthy = Files.createDirectories(fixture.resolve('healthy'))
    Files.writeString(blocked.resolve('.envrc'), 'export ENVLET_REVIEW_BLOCKED=1\n')
    record('fixture=' + fixture.fileName)
    def project = ProjectManagerEx.instanceEx.openProject(root, OpenProjectTask.build().withForceOpenInNewFrame(true))
    assert project != null
    def service = project.getService(plugin.pluginClassLoader.loadClass('io.github.salatmaster.direnv.DirenvService'))
    waitUntil('root load') { service.cachedFor(root) != null && service.state().class.simpleName == 'Loaded' }
    def probe = { dir ->
        def cmd = new GeneralCommandLine(root.root.resolve('run/current-system/sw/bin/bash').toString(),
            '-c', 'test "$ENVLET_DIRENV_TEST" = split-tools').withWorkingDirectory(dir)
        def out = new CapturingProcessHandler(cmd).runProcess(15000)
        assert !out.timeout
        return out.exitCode == 0
    }
    assert probe(root)
    record('root.environment=true')
    assert service.cachedFor(first) == null
    assert probe(first) : 'First subdirectory process missed its environment'
    record('P1.first.environment=true')
    waitUntil('first subdir async load') { service.cachedFor(first) != null && service.state().class.simpleName == 'Loaded' }
    assert probe(first)
    record('P1.second.environment=true')
    def old = service.cachedFor(root)
    service.scheduleReload(root)
    waitUntil('root reload') { service.cachedFor(root) != null && !service.cachedFor(root).is(old) && service.state().class.simpleName == 'Loaded' }
    assert service.cachedFor(first) == null
    record('P1.after-reload.subdir-cached=false')
    assert probe(first) : 'First process after shared reload missed its environment'
    record('P1.after-reload.first.environment=true')
    waitUntil('first subdir reload') { service.cachedFor(first) != null && service.state().class.simpleName == 'Loaded' }
    service.scheduleLoad(blocked)
    waitUntil('blocked subdir') { service.state().class.simpleName in ['Blocked', 'Denied', 'Failed'] }
    record('P2.state.after-A=' + service.state().class.simpleName)
    assert service.cachedFor(healthy) == null
    assert probe(healthy) : 'Blocked A prevented first process in healthy B'
    record('P2.B.first.environment=true')
    Thread.sleep(2000)
    assert probe(healthy)
    record('P2.B.second.environment=true')
    Thread.sleep(2000)
    assert service.cachedFor(healthy) != null
    record('P2.B.cached=true')
    record('P2.state.after-B=' + service.state().class.simpleName)
    assert probe(root)
    record('P2.root.environment=true')
    assert !probe(blocked) : 'Unapproved nested envrc inherited the parent environment'
    assert service.cachedFor(blocked) == null
    record('nested-unapproved.parent-environment=false')
    record('ACCEPTANCE=true')
    record('FINISHED')
} catch (Throwable t) {
    record('ERROR=' + t.class.simpleName)
    record('ERROR.at=' + t.stackTrace.find { it.fileName?.endsWith('.groovy') })
    throw t
}
