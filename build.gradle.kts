import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.jobbar"
version = "0.10.1"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        val localIde = providers.gradleProperty("localIdePath")
        if (localIde.isPresent) local(localIde.get()) else webstorm("2025.3.1.1")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmDefault = org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY }
}

intellijPlatform {
    pluginConfiguration {
        name = "Job Bar"
        ideaVersion { sinceBuild = "253"; untilBuild = "253.*" }
        changeNotes = "Initial release: configurable one-shot and tracked jobs, parameter variants, status rules, and reusable consoles."
    }
}

tasks.test { maxHeapSize = "2g" }
