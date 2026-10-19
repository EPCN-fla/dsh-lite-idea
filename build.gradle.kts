import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel

plugins {
    java
    kotlin("jvm") version "2.1.21"
    id("org.jetbrains.intellij.platform") version "2.10.5"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))
        instrumentationTools()
        pluginVerifier()
    }
    // Markdown -> HTML for the Swing chat view (pure Java, small).
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0")
    // Bundled deliberately: IC ships snakeyaml in lib-client.jar, but Android
    // Studio does NOT put it on the plugin classpath (NoClassDefFoundError).
    implementation("org.yaml:snakeyaml:2.2")
    testImplementation("junit:junit:4.13.2")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.dshlite.idea"
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        description = providers.fileContents(layout.projectDirectory.file("docs/overview.html")).asText
        changeNotes = providers.fileContents(layout.projectDirectory.file("docs/whats-new.html")).asText
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
        }
    }
    pluginVerification {
        failureLevel = listOf(
            FailureLevel.COMPATIBILITY_PROBLEMS,
            FailureLevel.INVALID_PLUGIN,
            FailureLevel.MISSING_DEPENDENCIES,
        )
        ides {
            create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))
        }
    }
    publishing {
        token = providers.environmentVariable("JETBRAINS_MARKETPLACE_TOKEN")
    }
}

tasks {
    test {
        // Swing rendering tests do not require a desktop or display server.
        systemProperty("java.awt.headless", "true")
    }
    jar {
        from("LICENSE") {
            into("META-INF")
        }
    }
    publishPlugin {
        // CI publishes the exact ZIP that passed verification and was uploaded as a CI artifact.
        providers.gradleProperty("pluginArchive").orNull?.let { path ->
            archiveFile.set(layout.projectDirectory.file(path))
            setDependsOn(emptyList<Any>())
        }
    }
    wrapper {
        gradleVersion = "8.14.3"
        distributionType = Wrapper.DistributionType.BIN
    }
}
