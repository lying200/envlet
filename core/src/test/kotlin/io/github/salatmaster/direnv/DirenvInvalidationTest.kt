// Added for ENV-16: invalidation must reject an older in-flight load.
package io.github.salatmaster.direnv

import io.github.salatmaster.direnv.watch.DirenvWatchService
import io.github.salatmaster.direnv.direnv.DirenvCli
import io.github.salatmaster.direnv.direnv.DirenvProcessResult
import io.github.salatmaster.direnv.direnv.FakeDirenvProcessRunner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DirenvInvalidationTest : DirenvLightTestCase() {
    fun `test global invalidation rejects an older export`() = verifyInvalidation(global = true, failed = false)
    fun `test directory invalidation rejects an older export`() = verifyInvalidation(global = false, failed = false)
    fun `test obsolete blocked export cannot suppress automatic retry`() = verifyInvalidation(global = true, failed = true)

    fun `test reentrant invalidation suppresses the superseded success UI event`() = runBlocking<Unit> {
        val service = DirenvService.getInstance(project)
        val runner = FakeDirenvProcessRunner()
        runner.respondTo("export", DirenvProcessResult(0, """{"TOKEN":"env16-event-canary"}""", ""))
        service.cliOverride = DirenvCli(runner, { "direnv" }, { emptyMap() }, { 5_000 })
        val states = mutableListOf<DirenvState>()
        val connection = project.messageBus.connect(testRootDisposable)
        connection.subscribe(DirenvEnvironmentListener.TOPIC, object : DirenvEnvironmentListener {
            override fun environmentChanged(change: DirenvEnvironmentChange) {
                if (service.cachedFor(workDir) != null) service.invalidate(null)
            }
        })
        connection.subscribe(DirenvStateListener.TOPIC, object : DirenvStateListener {
            override fun stateChanged(state: DirenvState) { states.add(state) }
        })
        service.load(workDir)
        assertThat(service.cachedFor(workDir)).isNull()
        assertThat(service.state()).isSameAs(DirenvState.NotLoaded)
        assertThat(states.filterIsInstance<DirenvState.Loaded>()).isEmpty()
    }

    private fun verifyInvalidation(global: Boolean, failed: Boolean) = runBlocking<Unit> {
        val service = DirenvService.getInstance(project)
        val runner = FakeDirenvProcessRunner()
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        runner.respondTo("export", if (failed) DirenvProcessResult(1, "", "direnv: error $workDir/.envrc is blocked.")
            else DirenvProcessResult(0, """{"REVIEW_CANARY":"old"}""", ""))
        val published = mutableListOf<DirenvState>()
        project.messageBus.connect(testRootDisposable).subscribe(DirenvStateListener.TOPIC, object : DirenvStateListener {
            override fun stateChanged(state: DirenvState) { published.add(state) }
        })
        runner.beforeRun = {
            started.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS))
        }
        service.cliOverride = DirenvCli(runner, { "direnv" }, { emptyMap() }, { 5_000 })
        val loading = async(Dispatchers.Default) { service.load(workDir) }
        try {
            withTimeout(5_000) { started.await() }
            service.invalidate(if (global) null else workDir)
            assertThat(service.cachedFor(workDir)).isNull()
            assertThat(service.state()).isSameAs(DirenvState.NotLoaded)
            published.clear()
            release.countDown()
            withTimeout(5_000) { loading.await() }
            assertThat(service.cachedFor(workDir))
                .withFailMessage("An export started before invalidate(null) restored the invalidated cache")
                .isNull()
            assertThat(service.state()).isSameAs(DirenvState.NotLoaded)
            assertThat(published).isEmpty()
            assertThat(DirenvWatchService.getInstance(project).watchedPaths()).isEmpty()
            runner.beforeRun = {}
            runner.respondTo("export", DirenvProcessResult(0, """{"REVIEW_CANARY":"new"}""", ""))
            assertThat(service.environmentForProcess(workDir)?.entries?.get("REVIEW_CANARY")).isEqualTo("new")
        } finally {
            release.countDown()
            withTimeout(5_000) { loading.join() }
        }
    }
}
