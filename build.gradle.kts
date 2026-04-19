import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.intellij.platform") version "2.3.0"
    kotlin("jvm") version "1.9.25"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.0"
}

group = "com.tokenslayer"
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion").get())
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")

        // NOTE: GitHub Copilot plugin is NOT added here as a compile-time dependency.
        // It is not published to any Maven repository (Marketplace-only).
        // The MCP server integration uses an HTTP server on localhost that Copilot
        // discovers at runtime. The optional tokenslayer-copilot.xml config is only
        // loaded when the Copilot plugin is installed by the user.

        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }

    // MCP protocol — JSON-RPC 2.0 implementation
    implementation("com.google.code.gson:gson:2.10.1")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.2")
    testImplementation("io.mockk:mockk:1.13.10")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
}

intellijPlatform {
    pluginConfiguration {
        id = "com.tokenslayer.token-slayer-jb"
        name = "TokenSlayer"
        version = providers.gradleProperty("pluginVersion").get()
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText
        changeNotes =
            """
            <h2>0.1.0</h2>
            <ul>
                <li>Initial release: AST-driven skeleton extraction for Java, Kotlin, Python, JS/TS, Go, Rust</li>
                <li>GitHub Copilot MCP server integration</li>
                <li>Live dashboard tool window with token savings analytics</li>
                <li>Inline inlay hints (⚡ N→M lines skeleton)</li>
                <li>Secrets detection and exclusion</li>
                <li>Skeleton preview (diff view)</li>
                <li>Export savings report</li>
            </ul>
            """.trimIndent()

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild").get()
            untilBuild = providers.gradleProperty("pluginUntilBuild").get()
        }

        vendor {
            name = "TokenSlayer"
            url = "https://github.com/donco-labs/token-slayer-jb"
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels =
            providers.gradleProperty("pluginVersion").map {
                listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
            }
    }

    pluginVerification {
        ides {
            ide(providers.gradleProperty("platformVersion").get())
        }
    }
}

ktlint {
    version.set("1.2.1")
    android.set(false)
    ignoreFailures.set(false)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
    }
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    test {
        useJUnitPlatform()
        systemProperty("idea.tests.overwrite.data", true)
    }

    publishPlugin {
        // dependsOn(patchChangelog)  // enable if using changelog plugin
    }
}
