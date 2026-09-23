// Modified for ENV-20/21: cross-component approval and project probe directory regressions.
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

    fun `test first child observing denied root invalidates cached root`() = sharedApprovalRefusal(false)
    fun `test first child observing blocked root invalidates cached root`() = sharedApprovalRefusal(true)

    private fun sharedApprovalRefusal(blocked: Boolean) = runBlocking<Unit> {
        val rc = Files.writeString(workDir.resolve(".envrc"), "export REVIEW_FIXTURE=1")
        val stamp = Files.createDirectories(workDir.resolve("review/direnv/deny")).resolve("stamp")
        Files.deleteIfExists(stamp) // The light fixture can reuse its directory between methods.
        fun payload(): String {
            val watches = listOf(rc, stamp).map {
                val exists = Files.exists(it)
                DirenvWatch(it, if (exists) Files.getLastModifiedTime(it).toInstant().epochSecond else 0, exists)
            }
            val escaped = rc.toString().replace("\\", "\\\\")
            return """{"DIRENV_FILE":"$escaped","DIRENV_WATCHES":"${DirenvWatchesCodec.encode(watches)}"}"""
        }
        runner.respondTo("export", DirenvProcessResult(0, payload(), ""))
        service.load(workDir)
        val original = nextProbe()
        val child = Files.createDirectories(workDir.resolve("first-visit"))
        if (blocked) Files.setLastModifiedTime(rc, java.nio.file.attribute.FileTime.fromMillis(Files.getLastModifiedTime(rc).toMillis() + 60_000))
        else Files.writeString(stamp, "denied")
        runner.respondTo("export", DirenvProcessResult(if (blocked) 1 else 0, payload(),
            if (blocked) "direnv: error $rc is blocked." else ""))
        assertThat(service.environmentForProcess(child)).isNull()
        assertThat(service.state().needsApproval).isTrue()

        // Inspect the same committed baselines used by the watcher, without waiting for a poll.
        val registry = io.github.salatmaster.direnv.watch.DirenvWatchRegistry()
        service.watchSnapshot().entries.values.groupBy { it.scope }.forEach { (scope, records) ->
            registry.replace(scope, records.sortedBy { it.revision }.flatMap { it.files }.associateBy { it.path }.values.toList())
        }
        assertThat(registry.staleTargets()).isEmpty()
        org.assertj.core.api.SoftAssertions.assertSoftly { checks ->
            checks.assertThat(service.cachedFor(workDir)).describedAs("revoked root cache").isNull()
            checks.assertThat(sync.isCurrent(original.environment)).describedAs("old root still current").isFalse()
            checks.assertThat(sync.applyIfCurrent(original.environment, { true }) {}).describedAs("old SDK publication allowed").isFalse()
        }
        withTimeout(5_000) { original.job.join() }
        assertThat(original.job.isCancelled).isTrue()
        assertThat(service.watchSnapshot().entries.keys).containsExactly(child)
        assertThat(service.watchSnapshot().entries[child]?.scope).isEqualTo(workDir)

        // A later external approval must still recover the root through the real watcher.
        Files.deleteIfExists(stamp)
        runner.respondTo("export", DirenvProcessResult(0, payload(), ""))
        DirenvSettings.getInstance(project).state.watchFiles = true
        DirenvWatchService.getInstance(project).handleChangedPaths(listOf(rc, stamp))
        val restored = nextProbe()
        assertThat(restored.environment).isSameAs(service.cachedFor(workDir))
        assertThat(restored.environment).isNotSameAs(original.environment)
        finish(restored)
    }

    fun `test shared child export must not move project toolchain probe cwd`() = runBlocking<Unit> {
        finish(startRootProbe())
        val child = Files.createDirectories(workDir.resolve("submodule"))
        Files.writeString(workDir.resolve("go.mod"), "module root\ngo 1.25.0\n")
        Files.writeString(child.resolve("go.mod"), "module child\ngo 1.26.0\n")
        // The CLI still resolves the root .envrc; only the export working directory changes.
        service.load(child)
        val replacement = nextProbe()
        assertThat(replacement.environment).isSameAs(service.cachedFor(workDir))
        assertThat(replacement.environment.workingDir).isEqualTo(child) // Preserve export provenance.
        val commands = FakeDirenvProcessRunner()
        val result = sync.probe(replacement.environment, workDir.resolve("go"),
            listOf("env", "-json", "GOROOT", "GOPATH"), commands)
        assertThat(result).isInstanceOf(ToolchainProbeResult.Success::class.java)
        org.assertj.core.api.SoftAssertions.assertSoftly { checks ->
            checks.assertThat(commands.invocations.single().workingDir).describedAs("project probe process cwd").isEqualTo(workDir)
            checks.assertThat(commands.invocations.single().args[1]).describedAs("direnv exec directory").isEqualTo(workDir.toString())
        }
        finish(replacement)
    }

    fun `test project probe uses project directory when envrc is above the project`() = runBlocking<Unit> {
        val rc = workDir.parent.resolve(".envrc").toString().replace("\\", "\\\\")
        runner.respondTo("export", DirenvProcessResult(0, """{"DIRENV_FILE":"$rc"}""", ""))
        val child = Files.createDirectories(workDir.resolve("nested-module"))
        service.load(workDir)
        finish(nextProbe())
        service.load(child)
        val replacement = nextProbe()
        val commands = FakeDirenvProcessRunner()
        assertThat(sync.probe(replacement.environment, workDir.resolve("go"), listOf("env"), commands))
            .isInstanceOf(ToolchainProbeResult.Success::class.java)
        assertThat(commands.invocations.single().workingDir).isEqualTo(workDir)
        assertThat(commands.invocations.single().args[1]).isEqualTo(workDir.toString())
        finish(replacement)
        service.invalidate(null)
        assertThat(sync.probe(replacement.environment, workDir.resolve("go"), listOf("env"), commands))
            .isSameAs(ToolchainProbeResult.Stale)
        assertThat(commands.invocations).hasSize(1)
    }

}
