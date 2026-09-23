// ENV-15: use only an authored disposable Go/Rust project with gated probe wrappers.
// go env and rustc --print sysroot touch .probe/{go,rustc}-started and wait for
// .probe/release. Root .envrc is approved; blocked/.envrc is deliberately unapproved.
// envlet.validation.project = fixture WSL UNC path; envlet.validation.result = report.
// Uses an isolated IDEA profile; never modifies SDK settings or approves .envrc itself.
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.vfs.VfsUtil
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
assert Files.exists(root.resolve('tools/bin/go')) && Files.exists(root.resolve('tools/bin/rustc'))
assert !Files.exists(root.resolve('.probe/release')) : 'Reset the disposable probe gate before starting'
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
                def module = manager.newModule(root.resolve('envlet-sync.iml'), 'JAVA_MODULE')
                def model = com.intellij.openapi.roots.ModuleRootManager.getInstance(module).modifiableModel
                model.addContentEntry(VfsUtil.pathToUrl(project.basePath))
                model.commit()
            }
        } as Runnable)
    } as Runnable)
    def environment = project.getService(loader.loadClass('io.github.salatmaster.direnv.DirenvService'))
    waitUntil('root load') { environment.cachedFor(root) != null }
    def rootEnvironment = environment.cachedFor(root)
    waitUntil('real Go and Rust probes reached gate') {
        Files.exists(root.resolve('.probe/go-started')) && Files.exists(root.resolve('.probe/rustc-started'))
    }
    record('go-and-rust-probes.in-flight=true')
    environment.scheduleLoad(root.resolve('blocked'))
    waitUntil('child blocked') { environment.state().class.simpleName == 'Blocked' }
    assert environment.cachedFor(root).is(rootEnvironment)
    record('child=Blocked')
    record('root.environment-retained=true')
    Files.writeString(root.resolve('.probe/release'), 'release\n')

    def go = project.getService(loader.loadClass('com.goide.sdk.GoSdkService'))
    def libraries = project.getService(loader.loadClass('com.goide.project.GoProjectLibrariesService'))
    def rust = project.getService(loader.loadClass('org.rust.cargo.project.settings.RustProjectSettingsService'))
    def binding = project.getService(loader.loadClass('io.github.salatmaster.direnv.rust.EnvletRustToolchainBinding'))
    waitUntil('Go SDK and fixture GOPATH publication') {
        go.getSdk(null).isValid() && libraries.libraryRootUrls.any { it.replace('\\', '/').endsWith('/' + root.fileName.toString() + '/go-path') }
    }
    record('go.sdk-and-gopath.configured=true')
    waitUntil('Rust SDK publication') {
        rust.toolchain?.class?.simpleName == 'EnvletWslRustToolchain' && binding.owns(rust.toolchain.location)
    }
    assert rust.toolchain.location.startsWith(root.resolve('.direnv/envlet/rust'))
    assert rust.explicitPathToStdlib != null
    record('rust.sdk-and-sources.configured=true')
    // A later Loaded event could rescue the old implementation; exclude that explanation.
    assert environment.cachedFor(root).is(rootEnvironment)
    assert environment.state().class.simpleName == 'Blocked'
    record('root.reloaded=false')
    record('global-state.after-sdk-publication=Blocked')
    if (Boolean.getBoolean('envlet.validation.invalidateAfterSync')) {
        environment.invalidate(root)
        assert environment.cachedFor(root) == null
        assert !binding.owns(rust.toolchain.location)
        record('invalidation.removes-rust-binding=true')
        environment.scheduleReload(root)
        waitUntil('root reload and Rust binding recovery') {
            def latest = environment.cachedFor(root)
            latest != null && !latest.is(rootEnvironment) && binding.owns(rust.toolchain.location)
        }
        record('invalidation.reload-restores-rust-binding=true')
    }
    if (Boolean.getBoolean('envlet.validation.testDiscoveryFailures')) {
        def previousHome = go.getSdk(null).homeUrl
        def previousRoots = libraries.libraryRootUrls.toSet()
        def previousEnvironment = environment.cachedFor(root)
        Files.writeString(root.resolve('.probe/go-invalid-output'), 'fixture\n')
        Files.writeString(root.resolve('.probe/rust-missing-sources'), 'fixture\n')
        environment.scheduleReload(root)
        waitUntil('missing sources plan published with a valid compiler') {
            def latest = environment.cachedFor(root)
            latest != null && !latest.is(previousEnvironment) &&
                rust.explicitPathToStdlib == null && binding.owns(rust.toolchain.location) &&
                Files.exists(root.resolve('.probe/go-invalid-seen'))
        }
        assert rust.toolchain.looksLikeValidToolchain()
        assert go.getSdk(null).homeUrl == previousHome
        assert libraries.libraryRootUrls.toSet() == previousRoots
        record('malformed-go-output.retains-sdk=true')
        record('missing-rust-sources.retains-compiler=true')
        Files.delete(root.resolve('.probe/go-invalid-output'))
        Files.delete(root.resolve('.probe/rust-missing-sources'))
        environment.scheduleReload(root)
        waitUntil('sources restored') {
            rust.explicitPathToStdlib != null && binding.owns(rust.toolchain.location)
        }
        record('discovery.restore=true')
    }
    record('ACCEPTANCE=true')
    record('FINISHED')
} catch (Throwable failure) {
    record('ERROR=' + failure.class.simpleName)
    record('ERROR.at=' + failure.stackTrace.find { it.fileName?.endsWith('.groovy') })
    throw failure
} finally {
    // Release actual compiler processes even if an assertion fails.
    Files.writeString(root.resolve('.probe/release'), 'release\n')
}
