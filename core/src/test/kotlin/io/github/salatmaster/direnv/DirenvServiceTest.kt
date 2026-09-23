// Modified for Envlet: regressions for environment scope and revoked/failed reloads.
package io.github.salatmaster.direnv

import io.github.salatmaster.direnv.direnv.DirenvCli
import io.github.salatmaster.direnv.direnv.DirenvProcessResult
import io.github.salatmaster.direnv.direnv.DirenvWatch
import io.github.salatmaster.direnv.direnv.DirenvWatchesCodec
import io.github.salatmaster.direnv.direnv.FakeDirenvProcessRunner
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import java.nio.file.Files
import org.assertj.core.api.Assertions.assertThat

class DirenvServiceTest : DirenvLightTestCase() {

    private lateinit var runner: FakeDirenvProcessRunner
    private lateinit var service: DirenvService

    override fun setUp() {
        super.setUp()
        runner = FakeDirenvProcessRunner()
        service = DirenvService.getInstance(project)
        service.cliOverride = DirenvCli(
            runner = runner,
            executableProvider = { "direnv" },
            extraEnvProvider = { emptyMap() },
            timeoutMsProvider = { 5_000 },
        )
    }

    fun `test loads and caches an environment`() = runBlocking<Unit> {
        runner.respondTo("export", DirenvProcessResult(0, """{"FOO":"bar"}""", ""))

        val state = service.load(workDir)

        assertThat(state).isInstanceOf(DirenvState.Loaded::class.java)
        assertThat(service.cachedFor(workDir)?.entries?.get("FOO")).isEqualTo("bar")
    }

    fun `test a second load reuses the cache without invoking direnv again`() = runBlocking<Unit> {
        runner.respondTo("export", DirenvProcessResult(0, """{"FOO":"bar"}""", ""))
        service.load(workDir)

        service.load(workDir)

        assertThat(runner.invocations).hasSize(1)
    }

    fun `test force reload bypasses the cache`() = runBlocking<Unit> {
        runner.respondTo("export", DirenvProcessResult(0, """{"FOO":"bar"}""", ""))
        service.load(workDir)

        service.load(workDir, force = true)

        assertThat(runner.invocations).hasSize(2)
    }

    fun `test a removed variable is reflected after reload`() = runBlocking<Unit> {
        runner.respondTo("export", DirenvProcessResult(0, """{"FOO":"bar"}""", ""))
        service.load(workDir)

        runner.respondTo("export", DirenvProcessResult(0, """{"FOO":null}""", ""))
        service.load(workDir, force = true)

        val cached = service.cachedFor(workDir)!!
        assertThat(cached.entries).containsKey("FOO")
        assertThat(cached.entries["FOO"]).isNull()
    }

    fun `test blocked envrc does not populate the cache`() = runBlocking<Unit> {
        runner.respondTo(
            "export",
            DirenvProcessResult(1, "", "direnv: error $workDir/.envrc is blocked."),
        )

        val state = service.load(workDir)

        assertThat(state).isInstanceOf(DirenvState.Blocked::class.java)
        assertThat(service.cachedFor(workDir)).isNull()
    }

    fun `test blocked envrc never triggers an allow invocation`() = runBlocking<Unit> {
        runner.respondTo(
            "export",
            DirenvProcessResult(1, "", "direnv: error $workDir/.envrc is blocked."),
        )

        service.load(workDir)

        assertThat(runner.invocations).noneMatch { it.args.firstOrNull() == "allow" }
    }

    fun `test blocked reload removes previously loaded environment`() = runBlocking<Unit> {
        runner.respondTo("export", DirenvProcessResult(0, """{"TOKEN":"stale-canary-envlet"}""", ""))
        service.load(workDir)
        runner.respondTo("export", DirenvProcessResult(1, "", "direnv: error $workDir/.envrc is blocked."))

        service.load(workDir, force = true)

        assertThat(service.cachedFor(workDir)).isNull()
        assertThat(service.state().toString()).doesNotContain("stale-canary-envlet")
    }

    fun `test failed reload removes previously loaded environment`() = runBlocking<Unit> {
        runner.respondTo("export", DirenvProcessResult(0, """{"FOO":"old"}""", ""))
        service.load(workDir)
        runner.respondTo("export", DirenvProcessResult(1, "", "evaluation failed"))

        service.load(workDir, force = true)

        assertThat(service.cachedFor(workDir)).isNull()
    }

    fun `test missing executable on reload removes previously loaded environment`() = runBlocking<Unit> {
        runner.respondTo("export", DirenvProcessResult(0, """{"FOO":"old"}""", ""))
        service.load(workDir)
        runner.executableMissing = true

        service.load(workDir, force = true)

        assertThat(service.cachedFor(workDir)).isNull()
    }

    fun `test nested envrc does not reuse an unverified parent environment`() = runBlocking<Unit> {
        runner.respondTo("export", DirenvProcessResult(0, """{"FOO":"parent"}""", ""))
        service.load(workDir)
        val nested = Files.createDirectories(workDir.resolve("nested"))
        Files.writeString(nested.resolve(".envrc"), "export FOO=child")
        assertThat(service.cachedFor(nested)).isNull()
        runner.respondTo("export", DirenvProcessResult(0, """{"FOO":"child"}""", ""))

        service.load(nested)

        assertThat(runner.invocations).hasSize(2)
        assertThat(service.cachedFor(nested)?.entries?.get("FOO")).isEqualTo("child")
        assertThat(service.cachedFor(workDir)?.entries?.get("FOO")).isEqualTo("parent")
    }

    fun `test a revoked approval is not cached and still names the file to allow`() = runBlocking<Unit> {
        runner.respondTo("export", DirenvProcessResult(0, """{"FOO":"before-deny"}""", ""))
        service.load(workDir)
        val envrc = workDir.resolve(".envrc")
        // A real file, recorded with its real modification time. direnv reports the stamp as
        // existing, and a path that exists only in the fixture would not: the watch service polls
        // it, correctly calls that a change, and forces a reload seconds later. The directory
        // names matter — the stamp is recognised by living under direnv/deny.
        val stamp = Files.writeString(
            Files.createDirectories(workDir.resolve("direnv").resolve("deny")).resolve("abc"),
            "",
        )
        val modtime = Files.getLastModifiedTime(stamp).toInstant().epochSecond
        val watches = DirenvWatchesCodec.encode(listOf(DirenvWatch(stamp, modtime, true)))
        // Backslashes in a Windows path would otherwise be read as JSON escapes.
        val envrcJson = envrc.toString().replace("\\", "\\\\")
        runner.respondTo(
            "export",
            DirenvProcessResult(0, """{"DIRENV_FILE":"$envrcJson","DIRENV_WATCHES":"$watches"}""", ""),
        )

        val state = service.load(workDir, force = true)

        assertThat(state).isInstanceOf(DirenvState.Denied::class.java)
        // direnv exports nothing once approval is revoked; caching that would leave the plugin
        // reporting a loaded environment that no longer exists.
        assertThat(service.cachedFor(workDir)).isNull()
        // Nothing is cached, so this is the only thing left that names the file — and without it
        // the Allow action loses its target and vanishes from the menu.
        assertThat(service.envrcPathFor(workDir)).isEqualTo(envrc)
    }

    fun `test missing executable is reported without throwing`() = runBlocking<Unit> {
        runner.executableMissing = true

        assertThat(service.load(workDir)).isInstanceOf(DirenvState.ExecutableMissing::class.java)
    }

    fun `test invalidate clears the cached environment`() = runBlocking<Unit> {
        runner.respondTo("export", DirenvProcessResult(0, """{"FOO":"bar"}""", ""))
        service.load(workDir)

        service.invalidate(null)

        assertThat(service.cachedFor(workDir)).isNull()
    }

    fun `test cachedFor returns null before anything is loaded`() {
        assertThat(service.cachedFor(workDir)).isNull()
    }

    fun `test a subdirectory is resolved before its environment is reused`() = runBlocking<Unit> {
        runner.respondTo("export", DirenvProcessResult(0, """{"FOO":"bar"}""", ""))
        service.load(workDir)

        val nested = workDir.resolve("sub").resolve("deeper")

        assertThat(service.cachedFor(nested)).isNull()
        service.load(nested)

        assertThat(service.cachedFor(nested)?.entries?.get("FOO")).isEqualTo("bar")
    }

    fun `test the directory holding the envrc finds its own environment`() = runBlocking<Unit> {
        // The first load can come from a subdirectory — a process started by a build tool, say —
        // and direnv then resolves the .envrc of the parent. The environment is filed under that
        // parent, so a later lookup for the parent itself must find it. It used to miss: the walk
        // started at the parent of the queried directory and never examined the directory itself.
        val envrcJson = workDir.resolve(".envrc").toString().replace("\\", "\\\\")
        runner.respondTo(
            "export",
            DirenvProcessResult(0, """{"FOO":"bar","DIRENV_FILE":"$envrcJson"}""", ""),
        )
        service.load(workDir.resolve("sub"))

        assertThat(service.cachedFor(workDir)?.entries?.get("FOO")).isEqualTo("bar")
    }

    fun `test secret values never reach rendered state`() = runBlocking<Unit> {
        val secret = "leak-canary-8f2a1c"
        runner.respondTo("export", DirenvProcessResult(0, """{"TOKEN":"$secret"}""", ""))

        service.load(workDir)

        val rendered = service.cachedFor(workDir).toString() + service.state().toString()
        assertThat(rendered).withFailMessage("secret leaked: $rendered").doesNotContain(secret)
    }
    fun `test automatic failures are per directory and explicit reload bypasses delay`() = runBlocking<Unit> {
        val blocked = Files.createDirectories(workDir.resolve("blocked"))
        val healthy = Files.createDirectories(workDir.resolve("healthy"))
        runner.respondTo("export", DirenvProcessResult(1, "", "direnv: error $blocked/.envrc is blocked. Run `direnv allow` to approve its content"))
        assertThat(service.environmentForProcess(blocked)).isNull()
        repeat(3) { assertThat(service.environmentForProcess(blocked)).isNull() }
        assertThat(runner.invocations).hasSize(1)

        runner.respondTo("export", DirenvProcessResult(0, """{"FOO":"healthy"}""", ""))
        assertThat(service.environmentForProcess(healthy)?.entries?.get("FOO")).isEqualTo("healthy")
        assertThat(service.environmentForProcess(blocked)).isNull()
        assertThat(runner.invocations).hasSize(2)

        service.load(blocked, force = true)
        assertThat(service.environmentForProcess(blocked)?.entries?.get("FOO")).isEqualTo("healthy")
        assertThat(runner.invocations).hasSize(3)
    }

    fun `test concurrent automatic attempts share a failed load`() = runBlocking<Unit> {
        runner.respondTo("export", DirenvProcessResult(1, "", "direnv: error $workDir/.envrc is blocked. Run `direnv allow` to approve its content"))
        val results = (1..8).map { async(Dispatchers.Default) { service.environmentForProcess(workDir) } }.awaitAll()
        assertThat(results).containsOnlyNulls()
        assertThat(runner.invocations).hasSize(1)
    }

    fun `test cached load reports the requested directory rather than the last failed directory`() = runBlocking<Unit> {
        runner.respondTo("export", DirenvProcessResult(0, """{"FOO":"root"}""", ""))
        service.load(workDir)
        val blocked = Files.createDirectories(workDir.resolve("blocked-state"))
        runner.respondTo("export", DirenvProcessResult(1, "", "direnv: error $blocked/.envrc is blocked. Run `direnv allow` to approve its content"))
        service.load(blocked)
        assertThat(service.state()).isInstanceOf(DirenvState.Blocked::class.java)

        assertThat(service.load(workDir)).isInstanceOf(DirenvState.Loaded::class.java)
    }

}
