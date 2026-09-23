import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("org.jetbrains.intellij.platform")
    alias(libs.plugins.kotlin)
    alias(libs.plugins.changelog)
}

group = providers.gradleProperty("pluginGroup").get()

// The release workflow passes the version it is cutting, so the number in the artifact,
// the tag and the changelog cannot drift apart. Local builds fall back to gradle.properties.
val pluginVersion = providers.environmentVariable("PLUGIN_VERSION")
    .orElse(providers.gradleProperty("pluginVersion"))

version = pluginVersion.get()

kotlin { jvmToolchain(25) }

listOf(configurations.runtimeClasspath, configurations.testRuntimeClasspath).forEach { cfg ->
    cfg.configure {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
    }
}

dependencies {
    intellijPlatform {
        // Modified for Envlet: IDEA 262 is unified and requires Java 25.
        val localIde = providers.gradleProperty("localIdePath").orNull
        if (localIde != null) local(localIde)
        else create(IntelliJPlatformType.IntellijIdea, providers.gradleProperty("platformVersion")) {
            useInstaller = false
        }
        pluginComposedModule(implementation(project(":core")))
        pluginComposedModule(implementation(project(":products:terminal")))
        pluginComposedModule(implementation(project(":products:gradle")))
        pluginComposedModule(implementation(project(":products:java")))
        pluginComposedModule(implementation(project(":products:javascript")))
        pluginComposedModule(implementation(project(":products:go")))
        pluginComposedModule(implementation(project(":products:rust")))
        pluginComposedModule(implementation(project(":products:python")))
        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }
}

// Developer convenience: ./gradlew runIde -PsampleProject=/path/to/project opens that project
// directly, so the plugin can be exercised without clicking through the welcome screen.
tasks.runIde {
    providers.gradleProperty("sampleProject").orNull?.let { path ->
        args(path)
        // The plugin refuses to run direnv in an untrusted project, which is the point; this
        // skips the trust dialog for a throwaway sandbox only.
        systemProperty("idea.trust.all.projects", "true")
        // A fresh sandbox otherwise blocks on the end-user agreement dialog before any plugin
        // code runs, which makes the sandbox useless for exercising the plugin.
        systemProperty("jb.consents.confirmation.enabled", "false")
        systemProperty("jb.privacy.policy.text", "<!--999.999-->")
        systemProperty("idea.initially.ask.config", "never")
        systemProperty("idea.log.debug.categories", "io.github.salatmaster.direnv")
    }
}

// Read-only use of the changelog: the file is cut by .github/scripts/cut-changelog.sh before the
// release builds, so patchChangelog is deliberately never run and cannot disagree with it.
changelog {
    version = pluginVersion
}

intellijPlatform {
    pluginConfiguration {
        version = pluginVersion

        // <change-notes> is what the Marketplace shows as "What's new" for a version, and what the
        // Plugins dialog shows when an update is offered. Leaving it unset is why 0.1.0 through
        // 0.1.3 published a blank one.
        //
        // The section of the version being released is used rather than [Unreleased], because the
        // workflow cuts the file before building and by then the entry has already moved. That is
        // also why the plugin's own convention, which reads [Unreleased], is not relied on.
        changeNotes = pluginVersion.map { version ->
            with(changelog) {
                val item = getOrNull(version) ?: getUnreleased()
                renderItem(item.withHeader(false).withEmptySections(false), Changelog.OutputType.HTML)
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // Validate a new platform before widening the supported range.
            untilBuild = "262.*"
        }
    }
    pluginVerification {
        // Python 262 exposes its WSL SDK/launch seams as Internal. Check the exact
        // reviewed uses below; keep binary and OverrideOnly errors fatal.
        failureLevel = listOf(VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES)
        ides {
            create(IntelliJPlatformType.IntellijIdea, providers.gradleProperty("platformVersion"))
        }
    }

    // Credentials come from the environment so nothing sensitive lives in the repository.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

// Do not turn off Internal API checking globally. Only the reviewed Python 262
// references in this baseline are allowed; a new caller or API fails verification.
tasks.verifyPlugin {
    val baseline = layout.projectDirectory.file("config/python-262-internal-api.txt")
    val verifiedVersion = pluginVersion.get()
    inputs.file(baseline)
    doLast {
        val allowed = baseline.asFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.toSet()
        val reports = verificationReportsDirectory.get().asFile.walkTopDown()
            .filter { it.name == "internal-api-usages.txt" && it.parentFile.name == verifiedVersion }.toList()
        check(reports.isNotEmpty()) { "Missing Internal API report; the Python API baseline was not checked" }
        val unexpected = reports.flatMap { it.readLines() }.filter { it.isNotBlank() }
            .map { it.substringBefore(" This ") }.toSet() - allowed
        check(unexpected.isEmpty()) { "Unreviewed Internal API usages:\n" + unexpected.joinToString("\n") }
    }
}
