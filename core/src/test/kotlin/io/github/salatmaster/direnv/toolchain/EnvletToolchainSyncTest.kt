// Added for ENV-15: exercise real service notifications while SDK synchronization is suspended.
package io.github.salatmaster.direnv.toolchain

import io.github.salatmaster.direnv.DirenvLightTestCase
import io.github.salatmaster.direnv.DirenvService
import io.github.salatmaster.direnv.DirenvState
import io.github.salatmaster.direnv.DirenvStateListener
import io.github.salatmaster.direnv.direnv.DirenvCli
import io.github.salatmaster.direnv.direnv.DirenvEnvironment
import io.github.salatmaster.direnv.direnv.DirenvWatch
import io.github.salatmaster.direnv.direnv.DirenvWatchesCodec
import io.github.salatmaster.direnv.direnv.DirenvProcessResult
import io.github.salatmaster.direnv.direnv.FakeDirenvProcessRunner
import io.github.salatmaster.direnv.settings.DirenvSettings
import io.github.salatmaster.direnv.watch.DirenvWatchService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import java.nio.file.Files
import java.nio.file.Path

class EnvletToolchainSyncTest : DirenvLightTestCase() {
    private lateinit var runner: FakeDirenvProcessRunner
    private lateinit var service: DirenvService
    private lateinit var sync: EnvletToolchainSync
    private lateinit var owner: Job
    private lateinit var probes: Channel<Probe>
    private var managementEnabled = true

    private class Probe(val environment: DirenvEnvironment, val job: Job) {
        val release = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
    }

    override fun setUp() {
        super.setUp()
        DirenvSettings.getInstance(project).state.watchFiles = false
        runner = FakeDirenvProcessRunner()
        service = DirenvService.getInstance(project)
        service.cliOverride = DirenvCli(runner, { "direnv" }, { emptyMap() }, { 5_000 })
        owner = SupervisorJob()
        sync = EnvletToolchainSync(project, CoroutineScope(owner + Dispatchers.Default))
        probes = Channel(Channel.UNLIMITED)
        sync.watch({ managementEnabled }) { environment ->
            val probe = Probe(environment, currentCoroutineContext().job)
            probes.send(probe)
            probe.release.await() // Deterministically hold the Go/Rust probe/publication window.
            probe.completed.complete(Unit)
        }
    }

    override fun tearDown() {
        try {
            runBlocking { owner.cancelAndJoin() }
            probes.close()
        } finally {
            super.tearDown()
        }
    }

    private fun respondLoaded(directory: Path) {
        Files.createDirectories(directory)
        val file = Files.writeString(directory.resolve(".envrc"), "export ENV15_CANARY=fixture")
            .toString().replace("\\", "\\\\")
        runner.respondTo("export", DirenvProcessResult(0, """{"DIRENV_FILE":"$file","ENV15_CANARY":"fixture"}""", ""))
    }

    private suspend fun startRootProbe(): Probe {
        respondLoaded(workDir)
        service.load(workDir)
        return nextProbe()
    }

    private suspend fun nextProbe(): Probe = withTimeout(5_000) { probes.receive() }

    private suspend fun finish(probe: Probe) {
        probe.release.complete(Unit)
        withTimeout(5_000) { probe.job.join() }
        assertThat(probe.job.isCancelled).isFalse()
        assertThat(probe.completed.isCompleted).isTrue()
    }

    fun `test blocked child preserves an in-flight root probe through a root cache hit`() = verifyChildFailure(true)
    fun `test failed child preserves an in-flight root probe through a root cache hit`() = verifyChildFailure(false)

    private fun verifyChildFailure(blocked: Boolean) = runBlocking<Unit> {
        val probe = startRootProbe()
        val child = Files.createDirectories(workDir.resolve("child"))
        Files.writeString(child.resolve(".envrc"), "export ENV15_CANARY=child")
        val error = if (blocked) "direnv: error $child/.envrc is blocked. Run `direnv allow` to approve its content"
            else "synthetic child failure"
        runner.respondTo("export", DirenvProcessResult(1, "", error))
        service.environmentForProcess(child)
        assertThat(service.cachedFor(workDir)).isSameAs(probe.environment)
        val invocations = runner.invocations.size
        assertThat(service.load(workDir)).isInstanceOf(DirenvState.Loaded::class.java)
        assertThat(runner.invocations).hasSize(invocations)
        finish(probe)
        assertThat(probes.tryReceive().isFailure).isTrue()
    }

    fun `test independent successful child does not restart active or completed root synchronization`() = runBlocking<Unit> {
        val probe = startRootProbe()
        val child = workDir.resolve("independent")
        respondLoaded(child)
        service.load(child)
        assertThat(service.cachedFor(workDir)).isSameAs(probe.environment)
        finish(probe)
        service.load(child, force = true)
        // Notification delivery is synchronous; no timing-dependent waiting for a second launch.
        // The next root replacement must produce exactly the next observed probe.
        respondLoaded(workDir)
        service.load(workDir, force = true)
        val replacement = nextProbe()
        assertThat(replacement.environment).isSameAs(service.cachedFor(workDir))
        finish(replacement)
        assertThat(probes.tryReceive().isFailure).isTrue()
    }

    fun `test failed root reload cancels stale work and a later successful reload restarts it`() = runBlocking<Unit> {
        val probe = startRootProbe()
        runner.respondTo("export", DirenvProcessResult(1, "", "synthetic root failure"))
        service.load(workDir, force = true)
        assertThat(service.cachedFor(workDir)).isNull()
        assertThat(sync.isCurrent(probe.environment)).isFalse()
        withTimeout(5_000) { probe.job.join() }
        assertThat(probe.job.isCancelled).isTrue()
        assertThat(probe.completed.isCompleted).isFalse()
        respondLoaded(workDir)
        service.load(workDir, force = true)
        val replacement = nextProbe()
        assertThat(replacement.environment).isNotSameAs(probe.environment)
        finish(replacement)
    }

    fun `test reload of a shared child scope still cancels and replaces the root probe`() = runBlocking<Unit> {
        respondLoaded(workDir)
        val child = Files.createDirectories(workDir.resolve("shared"))
        // Resolve a child to the root .envrc so reloading the child invalidates the root cache.
        service.load(child)
        val probe = nextProbe()
        service.load(child, force = true)
        withTimeout(5_000) { probe.job.join() }
        assertThat(probe.job.isCancelled).isTrue()
        val replacement = nextProbe()
        assertThat(replacement.environment).isSameAs(service.cachedFor(workDir))
        assertThat(replacement.environment).isNotSameAs(probe.environment)
        finish(replacement)
    }

    fun `test applying disabled language management invalidates and cancels work`() = runBlocking<Unit> {
        val probe = startRootProbe()
        managementEnabled = false
        service.invalidate(null)
        withTimeout(5_000) { probe.job.join() }
        assertThat(probe.job.isCancelled).isTrue()
        assertThat(service.cachedFor(workDir)).isNull()
        managementEnabled = true
        respondLoaded(workDir)
        service.load(workDir)
        finish(nextProbe())
    }

    fun `test applying disabled plugin invalidates and cancels work`() = runBlocking<Unit> {
        val probe = startRootProbe()
        DirenvSettings.getInstance(project).state.enabled = false
        service.invalidate(null)
        withTimeout(5_000) { probe.job.join() }
        assertThat(probe.job.isCancelled).isTrue()
        assertThat(sync.isCurrent(probe.environment)).isFalse()
    }
    fun `test UI status events cannot cancel or restart root synchronization`() = runBlocking<Unit> {
        val probe = startRootProbe()
        project.messageBus.syncPublisher(DirenvStateListener.TOPIC).stateChanged(DirenvState.Loading)
        project.messageBus.syncPublisher(DirenvStateListener.TOPIC).stateChanged(DirenvState.Failed("child"))
        finish(probe)
        assertThat(probes.tryReceive().isFailure).isTrue()
    }

    fun `test automatic refresh of ancestor envrc restores project cache and toolchain sync`() = ancestorRefresh(false)
    fun `test polling ancestor environment restores project cache and toolchain sync`() = ancestorRefresh(true)

    private fun ancestorRefresh(poll: Boolean) = runBlocking<Unit> {
        // The fake CLI reports an ancestor .envrc; do not create files outside the test project.
        val parentRc = workDir.parent.resolve(".envrc")
        val escaped = parentRc.toString().replace("\\", "\\\\")
        val dependency = Files.writeString(workDir.resolve("ancestor-input"), "fixture")
        val modified = Files.getLastModifiedTime(dependency)
        val watches = DirenvWatchesCodec.encode(listOf(DirenvWatch(dependency, modified.toInstant().epochSecond, true)))
        runner.respondTo("export", DirenvProcessResult(0, """{"DIRENV_FILE":"$escaped","DIRENV_WATCHES":"$watches"}""", ""))
        service.load(workDir)
        val initial = nextProbe()
        finish(initial)
        assertThat(service.cachedFor(workDir)).isSameAs(initial.environment)

        DirenvSettings.getInstance(project).state.watchFiles = true
        if (poll) Files.setLastModifiedTime(dependency, java.nio.file.attribute.FileTime.fromMillis(modified.toMillis() + 60_000))
        else DirenvWatchService.getInstance(project).handleChangedPaths(listOf(parentRc))
        withTimeout(5_000) {
            // Wait for the replacement to commit, independently of the alias being tested.
            while (service.cachedFor(workDir.parent) == null ||
                service.cachedFor(workDir.parent) === initial.environment) delay(10)
        }
        assertThat(service.cachedFor(workDir))
            .withFailMessage("Automatic refresh replaced the ancestor environment but lost the project directory mapping")
            .isSameAs(service.cachedFor(workDir.parent))
        val replacement = nextProbe()
        assertThat(replacement.environment).isSameAs(service.cachedFor(workDir))
        assertThat(runner.invocations.filter { it.args.firstOrNull() == "export" }.map { it.workingDir })
            .containsOnly(workDir)
        finish(replacement)
    }

    fun `test publication guard prevents expired plans from writing SDK settings`() = runBlocking<Unit> {
        val probe = startRootProbe()
        var writes = 0
        assertThat(sync.applyIfCurrent(probe.environment, { true }) { writes++ }).isTrue()
        service.invalidate(null)
        assertThat(sync.applyIfCurrent(probe.environment, { true }) { writes++ }).isFalse()
        assertThat(writes).isEqualTo(1)
        respondLoaded(workDir)
        service.load(workDir)
        val replacement = nextProbe()
        assertThat(sync.applyIfCurrent(probe.environment, { true }) { writes++ }).isFalse()
        assertThat(sync.applyIfCurrent(replacement.environment, { false }) { writes++ }).isFalse()
        assertThat(sync.applyIfCurrent(replacement.environment, { true }) { writes++ }).isTrue()
        assertThat(writes).isEqualTo(2)
        finish(replacement)
    }

    fun `test late subscriber synchronizes cached root despite a blocked child status`() = runBlocking<Unit> {
        val first = startRootProbe()
        finish(first)
        val child = Files.createDirectories(workDir.resolve("already-blocked"))
        runner.respondTo("export", DirenvProcessResult(1, "", "direnv: error $child/.envrc is blocked. Run `direnv allow` to approve its content"))
        service.load(child)
        assertThat(service.state()).isInstanceOf(DirenvState.Blocked::class.java)
        val late = CompletableDeferred<DirenvEnvironment>()
        sync.watch({ true }) { late.complete(it) }

        assertThat(withTimeout(5_000) { late.await() }).isSameAs(first.environment)
    }

}
