package io.github.salatmaster.direnv.inject

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import io.github.salatmaster.direnv.DirenvLightTestCase
import io.github.salatmaster.direnv.DirenvService
import io.github.salatmaster.direnv.direnv.DirenvCli
import io.github.salatmaster.direnv.direnv.DirenvInternalMarker
import io.github.salatmaster.direnv.direnv.DirenvProcessResult
import io.github.salatmaster.direnv.direnv.FakeDirenvProcessRunner
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import io.github.salatmaster.direnv.DirenvState
import java.nio.file.Paths
import org.assertj.core.api.Assertions.assertThat

class DirenvCommandLineEnvCustomizerTest : DirenvLightTestCase() {

    private lateinit var runner: FakeDirenvProcessRunner
    private lateinit var service: DirenvService
    private lateinit var customizer: DirenvCommandLineEnvCustomizer

    override fun setUp() {
        super.setUp()
        runner = FakeDirenvProcessRunner()
        runner.beforeRun = {
            assertThat(ApplicationManager.getApplication().isDispatchThread).isFalse()
            assertThat(ApplicationManager.getApplication().isReadAccessAllowed).isFalse()
        }
        customizer = DirenvCommandLineEnvCustomizer()
        service = DirenvService.getInstance(project)
        service.cliOverride = DirenvCli(
            runner = runner,
            executableProvider = { "direnv" },
            extraEnvProvider = { emptyMap() },
            timeoutMsProvider = { 5_000 },
        )
    }

    private fun commandLineInProject(): GeneralCommandLine =
        GeneralCommandLine("echo").withWorkingDirectory(Paths.get(project.basePath!!))

    private fun loadEnvironment(json: String) = runBlocking {
        runner.respondTo("export", DirenvProcessResult(0, json, ""))
        service.load(Paths.get(project.basePath!!), force = true)
    }

    fun `test injects loaded variables into the environment`() {
        loadEnvironment("""{"FOO":"bar"}""")
        val environment = mutableMapOf<String, String>()

        customizer.customizeEnv(commandLineInProject(), environment)

        assertThat(environment["FOO"]).isEqualTo("bar")
    }

    fun `test removes variables direnv reported as unset`() {
        loadEnvironment("""{"OBSOLETE":null}""")
        val environment = mutableMapOf("OBSOLETE" to "old")

        customizer.customizeEnv(commandLineInProject(), environment)

        assertThat(environment).doesNotContainKey("OBSOLETE")
    }

    fun `test leaves unrelated variables untouched`() {
        loadEnvironment("""{"FOO":"bar"}""")
        val environment = mutableMapOf("HOME" to "/home/u")

        customizer.customizeEnv(commandLineInProject(), environment)

        assertThat(environment["HOME"]).isEqualTo("/home/u")
    }

    fun `test skips command lines the plugin created itself`() {
        loadEnvironment("""{"FOO":"bar"}""")
        val commandLine = commandLineInProject().also { DirenvInternalMarker.mark(it) }
        val environment = mutableMapOf<String, String>()

        customizer.customizeEnv(commandLine, environment)

        assertThat(environment).isEmpty()
    }

    fun `test skips command lines without a working directory`() {
        loadEnvironment("""{"FOO":"bar"}""")
        val environment = mutableMapOf<String, String>()

        customizer.customizeEnv(GeneralCommandLine("echo"), environment)

        assertThat(environment).isEmpty()
    }

    fun `test skips working directories outside any open project`() {
        loadEnvironment("""{"FOO":"bar"}""")
        val outside = GeneralCommandLine("echo")
            .withWorkingDirectory(Paths.get(System.getProperty("java.io.tmpdir")))
        val environment = mutableMapOf<String, String>()

        customizer.customizeEnv(outside, environment)

        assertThat(environment).doesNotContainKey("FOO")
    }

    fun `test does nothing when no environment has been loaded`() {
        val environment = mutableMapOf<String, String>()

        customizer.customizeEnv(commandLineInProject(), environment)

        assertThat(environment).isEmpty()
        awaitScheduledLoad(workDir)
    }

    fun `test EDT miss only loads on a background thread`() {
        val environment = mutableMapOf<String, String>()

        customizer.customizeEnv(commandLineInProject(), environment)

        assertThat(environment).isEmpty()
        awaitScheduledLoad(workDir)
        assertThat(runner.invocations).hasSize(1)
    }
    // Wait for the load scheduled by the hook itself. Calling load() here could win the race
    // and leave the scheduled coroutine to start only after the shared fixture is torn down.
    private fun awaitScheduledLoad(directory: Path) = runBlocking {
        withTimeout(5_000) {
            while (service.cachedFor(directory) == null || service.state() !is DirenvState.Loaded) delay(10)
        }
    }

    // Exercise the synchronous launch hook on the background thread used by process runners.
    // Awaiting service.load(child) here would hide the first-launch regression.
    private fun backgroundEnvironment(directory: Path): Map<String, String> =
        ApplicationManager.getApplication().executeOnPooledThread<Map<String, String>> {
            val environment = mutableMapOf<String, String>()
            customizer.customizeEnv(GeneralCommandLine("echo").withWorkingDirectory(directory), environment)
            environment
        }.get(10, TimeUnit.SECONDS)

    private fun rootExport(): String {
        val file = workDir.resolve(".envrc").toString().replace("\\", "\\\\")
        return """{"FOO":"parent","DIRENV_FILE":"$file"}"""
    }

    fun `test first background process in an unseen directory gets its environment`() {
        val child = Files.createDirectories(workDir.resolve("first"))
        loadEnvironment(rootExport())

        assertThat(backgroundEnvironment(child)["FOO"]).isEqualTo("parent")
    }

    fun `test first background process after shared environment reload gets its environment`() {
        val child = Files.createDirectories(workDir.resolve("reload"))
        loadEnvironment(rootExport())
        runBlocking { service.load(child) }
        loadEnvironment(rootExport())
        assertThat(service.cachedFor(child)).isNull()

        assertThat(backgroundEnvironment(child)["FOO"]).isEqualTo("parent")
    }

    fun `test blocked directory does not prevent first process in a healthy directory`() {
        val blocked = Files.createDirectories(workDir.resolve("blocked"))
        val healthy = Files.createDirectories(workDir.resolve("healthy"))
        loadEnvironment(rootExport())
        runner.respondTo("export", DirenvProcessResult(1, "", "direnv: error $blocked/.envrc is blocked. Run `direnv allow` to approve its content"))
        runBlocking { service.load(blocked) }
        runner.respondTo("export", DirenvProcessResult(0, rootExport(), ""))

        assertThat(backgroundEnvironment(healthy)["FOO"]).isEqualTo("parent")
        assertThat(service.cachedFor(blocked)).isNull()
    }

    fun `test nested blocked envrc never receives the parent environment and retries are bounded`() {
        val child = Files.createDirectories(workDir.resolve("unapproved"))
        Files.writeString(child.resolve(".envrc"), "export FOO=child")
        loadEnvironment(rootExport())
        runner.respondTo("export", DirenvProcessResult(1, "", "direnv: error $child/.envrc is blocked. Run `direnv allow` to approve its content"))

        repeat(3) { assertThat(backgroundEnvironment(child)).doesNotContainKey("FOO") }

        assertThat(runner.invocations).hasSize(2) // Root once, blocked child once.
        assertThat(service.cachedFor(workDir)?.entries?.get("FOO")).isEqualTo("parent")
    }

    fun `test read action miss returns without waiting for direnv`() {
        val child = Files.createDirectories(workDir.resolve("read-action"))
        loadEnvironment(rootExport())
        val environment = ApplicationManager.getApplication().executeOnPooledThread<Map<String, String>> {
            ReadAction.compute<Map<String, String>, RuntimeException> {
                val result = mutableMapOf<String, String>()
                customizer.customizeEnv(GeneralCommandLine("echo").withWorkingDirectory(child), result)
                result
            }
        }.get(10, TimeUnit.SECONDS)
        assertThat(environment).isEmpty()
        awaitScheduledLoad(child)
        assertThat(service.cachedFor(child)?.entries?.get("FOO")).isEqualTo("parent")
    }

    fun `test cancelled launch propagates cancellation and can load on a later attempt`() {
        val child = Files.createDirectories(workDir.resolve("cancelled"))
        loadEnvironment(rootExport())
        runner.beforeRun = { throw ProcessCanceledException() }

        // The platform's pooled-thread wrapper consumes PCE; capture at the hook boundary.
        val cancellation = ApplicationManager.getApplication().executeOnPooledThread<Throwable?> {
            runCatching {
                customizer.customizeEnv(GeneralCommandLine("echo").withWorkingDirectory(child), mutableMapOf())
            }.exceptionOrNull()
        }.get(10, TimeUnit.SECONDS)
        assertThat(cancellation).isInstanceOf(ProcessCanceledException::class.java)
        assertThat(service.cachedFor(child)).isNull()
        runner.beforeRun = null
        assertThat(backgroundEnvironment(child)["FOO"]).isEqualTo("parent")
    }

}
