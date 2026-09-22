import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("org.jetbrains.intellij.platform.module")
    alias(libs.plugins.kotlin)
}

kotlin { jvmToolchain(25) }

dependencies {
    implementation(project(":core"))
    compileOnly(libs.kotlinx.serialization.json)
    intellijPlatform {
        val localIde = providers.gradleProperty("localIdePath").orNull
        if (localIde != null) local(localIde)
        else create(IntelliJPlatformType.IntellijIdea, providers.gradleProperty("platformVersion")) {
            useInstaller = false
        }
        val localGo = providers.gradleProperty("localGoPluginPath").orNull
        if (localGo != null) localPlugin(localGo)
        else plugin("org.jetbrains.plugins.go", "262.10968.63")
    }
}
