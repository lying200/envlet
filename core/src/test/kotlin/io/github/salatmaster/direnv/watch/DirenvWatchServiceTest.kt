package io.github.salatmaster.direnv.watch

import io.github.salatmaster.direnv.DirenvLightTestCase
import io.github.salatmaster.direnv.DirenvService
import io.github.salatmaster.direnv.DirenvState
import io.github.salatmaster.direnv.direnv.DirenvCli
import io.github.salatmaster.direnv.direnv.DirenvProcessResult
import io.github.salatmaster.direnv.direnv.DirenvWatch
import io.github.salatmaster.direnv.direnv.DirenvWatchesCodec
import io.github.salatmaster.direnv.direnv.FakeDirenvProcessRunner
import io.github.salatmaster.direnv.settings.DirenvSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Paths
import org.assertj.core.api.Assertions.assertThat

class DirenvWatchServiceTest : DirenvLightTestCase() {

    private lateinit var runner: FakeDirenvProcessRunner
    private lateinit var service: DirenvService
    private lateinit var watchService: DirenvWatchService

    override fun setUp() {
        super.setUp()
        runner = FakeDirenvProcessRunner()
        service = DirenvService.getInstance(project)
        watchService = DirenvWatchService.getInstance(project)
        service.cliOverride = DirenvCli(
            runner = runner,
            executableProvider = { "direnv" },
            extraEnvProvider = { emptyMap() },
            timeoutMsProvider = { 5_000 },
        )
    }

    fun `test root reload then shared approval revocation removes root cache`() = sharedRevocation(false)
    fun `test polling after root reload removes revoked root cache`() = sharedRevocation(true)

    private fun sharedRevocation(poll: Boolean) = runBlocking<Unit> {
        DirenvSettings.getInstance(project).state.watchFiles = false
        val rc = Files.writeString(workDir.resolve(".envrc"), "export ENV18_CANARY=fixture")
        val child = Files.createDirectories(workDir.resolve("shared"))
        val exported = exportWith(rc.toString()).dropLast(1) +
            ",\"DIRENV_FILE\":\"" + rc.toString().replace("\\", "\\\\") + "\"}"
        runner.respondTo("export", DirenvProcessResult(0, exported, ""))
        service.load(workDir)
        service.load(child)
        service.load(workDir, force = true)
        assertThat(service.cachedFor(child)).isNull() // The child alias was invalidated.
        assertThat(service.cachedFor(workDir)).isNotNull()

        runner.respondTo("export", DirenvProcessResult(1, "", "direnv: error $rc is blocked."))
        DirenvSettings.getInstance(project).state.watchFiles = true
        if (poll) {
            val changed = Files.getLastModifiedTime(rc).toMillis() + 60_000
            Files.setLastModifiedTime(rc, java.nio.file.attribute.FileTime.fromMillis(changed))
        } else watchService.handleChangedPaths(listOf(rc))
        withTimeout(7_000) {
            while (service.state() !is DirenvState.Blocked) delay(10)
        }
        assertThat(service.cachedFor(workDir))
            .withFailMessage("Shared watch reloaded an orphan child while the revoked root remained cached")
            .isNull()
    }

    fun `test all verified children keep intermediate envrc watches until scope reload`() = runBlocking<Unit> {
        DirenvSettings.getInstance(project).state.watchFiles = false
        val rootRc = Files.writeString(workDir.resolve(".envrc"), "export ENV18_CANARY=root")
        val first = Files.createDirectories(workDir.resolve("first"))
        val second = Files.createDirectories(workDir.resolve("second"))
        fun export(rc: java.nio.file.Path) = exportWith(rc.toString()).dropLast(1) +
            ",\"DIRENV_FILE\":\"" + rc.toString().replace("\\", "\\\\") + "\"}"
        runner.respondTo("export", DirenvProcessResult(0, export(rootRc), ""))
        service.load(workDir)
        service.load(first)
        service.load(second)
        val previous = service.cachedFor(workDir)
        assertThat(service.cachedFor(first)).isNotNull()
        assertThat(watchService.watchedPaths()).contains(first.resolve(".envrc"), second.resolve(".envrc"))
        val nestedRc = Files.writeString(first.resolve(".envrc"), "export ENV18_CANARY=nested")
        DirenvSettings.getInstance(project).state.watchFiles = true
        watchService.handleChangedPaths(listOf(nestedRc))
        withTimeout(7_000) {
            while (service.cachedFor(workDir) == null || service.cachedFor(workDir) === previous) delay(10)
        }
        assertThat(service.cachedFor(first)).isNull()
        assertThat(service.cachedFor(second)).isNull()
        runner.respondTo("export", DirenvProcessResult(0, export(nestedRc), ""))
        assertThat(service.environmentForProcess(first)?.loadedRcPath).isEqualTo(nestedRc)
    }

    fun `test new nested blocked envrc owns its watches instead of former parent scope`() = nestedApprovalScope(false)
    fun `test new nested denied envrc owns its watches instead of former parent scope`() = nestedApprovalScope(true)

    private fun nestedApprovalScope(denied: Boolean) = runBlocking<Unit> {
        DirenvSettings.getInstance(project).state.watchFiles = false
        val rootRc = Files.writeString(workDir.resolve(".envrc"), "export ENV18_CANARY=root")
        val child = Files.createDirectories(workDir.resolve("becomes-independent"))
        fun export(rc: java.nio.file.Path, watched: java.nio.file.Path) = exportWith(watched.toString()).dropLast(1) +
            ",\"DIRENV_FILE\":\"" + rc.toString().replace("\\", "\\\\") + "\"}"
        runner.respondTo("export", DirenvProcessResult(0, export(rootRc, rootRc), ""))
        service.load(workDir)
        service.load(child)
        val childRc = Files.writeString(child.resolve(".envrc"), "export ENV18_CANARY=child")
        if (denied) {
            val stamp = Files.writeString(Files.createDirectories(workDir.resolve("direnv/deny")).resolve("fixture-stamp"), "")
            runner.respondTo("export", DirenvProcessResult(0, export(childRc, stamp), ""))
        } else runner.respondTo("export", DirenvProcessResult(1, exportWith(childRc.toString()), "direnv: error $childRc is blocked."))
        service.load(child, force = true)
        assertThat(service.state().needsApproval).isTrue()
        assertThat(service.watchSnapshot().entries[child]?.scope).isEqualTo(child)
        assertThat(service.cachedFor(child)).isNull()
        assertThat(service.cachedFor(workDir)).isNull()
    }

    /**
     * Builds an export payload whose watches point at files that actually exist, recording their
     * real modification time. The service polls watched files, so fictional paths would look like
     * a change on the first tick and trigger reloads unrelated to what a test is asserting.
     */
    private fun exportWith(vararg watched: String): String {
        val entries = watched.map { raw ->
            val path = Paths.get(raw)
            path.parent?.let { Files.createDirectories(it) }
            if (!Files.exists(path)) Files.writeString(path, "fixture")
            DirenvWatch(path, Files.getLastModifiedTime(path).toInstant().epochSecond, true)
        }
        val encoded = DirenvWatchesCodec.encode(entries)
        return """{"FOO":"bar","DIRENV_WATCHES":"$encoded"}"""
    }

    fun `test loading an environment registers the files it depends on`() = runBlocking<Unit> {
        val flake = workDir.resolve("flake.lock").toString()
        runner.respondTo("export", DirenvProcessResult(0, exportWith(flake), ""))

        service.load(workDir, force = true)

        assertThat(watchService.watchedPaths()).contains(Paths.get(flake))
    }

    fun `test files outside the project are registered too`() = runBlocking<Unit> {
        // direnv's allow stamps live under the user's data directory. Missing them is what makes
        // an external `direnv allow` go unnoticed by the IDE.
        val stamp = Files.createTempFile("direnv-allow-stamp-outside", ".test").toString()
        runner.respondTo("export", DirenvProcessResult(0, exportWith(stamp), ""))

        service.load(workDir, force = true)

        assertThat(watchService.watchedPaths()).contains(Paths.get(stamp))
    }

    fun `test reloading replaces the previous watch set`() = runBlocking<Unit> {
        val first = workDir.resolve("first.lock").toString()
        runner.respondTo("export", DirenvProcessResult(0, exportWith(first), ""))
        service.load(workDir, force = true)

        val second = workDir.resolve("second.lock").toString()
        runner.respondTo("export", DirenvProcessResult(0, exportWith(second), ""))
        service.load(workDir, force = true)

        assertThat(watchService.watchedPaths()).doesNotContain(Paths.get(first))
        assertThat(watchService.watchedPaths()).contains(Paths.get(second))
    }

    fun `test a change to an unwatched file does not trigger direnv`() = runBlocking<Unit> {
        runner.respondTo("export", DirenvProcessResult(0, exportWith(workDir.resolve("a.lock").toString()), ""))
        service.load(workDir, force = true)
        val before = runner.invocations.size

        watchService.handleChangedPaths(listOf(workDir.resolve("unrelated.txt")))
        delay(DEBOUNCE_PLUS_MARGIN_MS)

        assertThat(runner.invocations).hasSize(before)
    }

    fun `test a change to a watched file triggers a reload`() = runBlocking<Unit> {
        val lock = workDir.resolve("watched.lock")
        runner.respondTo("export", DirenvProcessResult(0, exportWith(lock.toString()), ""))
        service.load(workDir, force = true)
        val before = runner.invocations.size

        watchService.handleChangedPaths(listOf(lock))
        delay(DEBOUNCE_PLUS_MARGIN_MS)

        assertThat(runner.invocations).withFailMessage("expected a reload after a watched file changed")
            .hasSizeGreaterThan(before)
    }

    fun `test a blocked envrc still registers its allow stamp for watching`() = runBlocking<Unit> {
        // Without this, approving the .envrc in an external terminal would never reach the IDE,
        // because a blocked project has no environment and therefore had no watches at all.
        val stamp = Files.createTempFile("direnv-allow-stamp", ".test").toString()
        val encoded = DirenvWatchesCodec.encode(
            listOf(
                DirenvWatch(
                    Paths.get(stamp),
                    Files.getLastModifiedTime(Paths.get(stamp)).toInstant().epochSecond,
                    true,
                )
            )
        )
        runner.respondTo(
            "export",
            DirenvProcessResult(1, """{"DIRENV_WATCHES":"$encoded"}""", "direnv: error $workDir/.envrc is blocked."),
        )

        service.load(workDir, force = true)

        assertThat(watchService.watchedPaths()).contains(Paths.get(stamp))
    }

    fun `test watching can be disabled in settings`() = runBlocking<Unit> {
        val lock = workDir.resolve("watched.lock")
        runner.respondTo("export", DirenvProcessResult(0, exportWith(lock.toString()), ""))
        service.load(workDir, force = true)
        // Not restored here on purpose: a failing assertion would skip the restore and leak the
        // setting into the next test. DirenvLightTestCase puts the settings back either way.
        DirenvSettings.getInstance(project).state.watchFiles = false
        val before = runner.invocations.size

        watchService.handleChangedPaths(listOf(lock))
        delay(DEBOUNCE_PLUS_MARGIN_MS)

        assertThat(runner.invocations).hasSize(before)
    }

    fun `test a registered watch set stays quiet while its files are unchanged`() = runBlocking<Unit> {
        // The poll is what makes a terminal `direnv allow` reach the IDE, and it runs for as long
        // as the project lives — across every later test, since the light project is shared. A
        // watch set that reports a change when nothing changed therefore forces a reload into an
        // unrelated test, which is how this suite produced failures that could not be reproduced.
        runner.respondTo("export", DirenvProcessResult(0, exportWith(workDir.resolve("quiet.lock").toString()), ""))
        service.load(workDir, force = true)
        val before = runner.invocations.size

        delay(POLL_PLUS_MARGIN_MS)

        assertThat(runner.invocations).withFailMessage("the poll reloaded although nothing changed")
            .hasSize(before)
    }

    private companion object {
        /** The service debounces by 500 ms; wait past that plus scheduling slack. */
        const val DEBOUNCE_PLUS_MARGIN_MS = 1_500L

        /** The service polls every 2 s; wait past one full tick plus scheduling slack. */
        const val POLL_PLUS_MARGIN_MS = 3_000L
    }
}
