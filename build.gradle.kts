import org.jetbrains.changelog.Changelog

plugins {
    id("org.jetbrains.intellij.platform") version "2.14.0"
    id("org.jetbrains.changelog") version "2.2.1"
    kotlin("jvm") version "1.9.25"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.0"
}

group = "com.tokenslayer"

// Plugin version resolution:
//   - In CI, the Release workflow derives PLUGIN_VERSION from the pushed git tag
//     (e.g. tag "v0.3.0" -> "0.3.0") and exports it, so the tag is the single source of truth
//     for the version stamped into plugin.xml and the built ZIP.
//   - Locally (and in non-release CI), it falls back to `pluginVersion` in gradle.properties.
val pluginVersion: String =
    providers.environmentVariable("PLUGIN_VERSION")
        .orElse(providers.gradleProperty("pluginVersion"))
        .get()

version = pluginVersion

// Expose the resolved version to runtime code as a resource. The MCP handshake reports it in
// serverInfo, and both PluginManagerCore.getPlugin and PluginManager.getPluginByClass are
// @ApiStatus.Internal (the Plugin Verifier flags them), while a hardcoded literal silently went
// stale at 0.2.0 for several releases. A generated properties file keeps one source of truth
// with no internal API and no extra plugin.
val generateVersionResource by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/tokenslayer-version")
    val versionValue = pluginVersion
    inputs.property("version", versionValue)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().asFile.resolve("tokenslayer-version.properties")
        file.parentFile.mkdirs()
        file.writeText("version=$versionValue\n")
    }
}

sourceSets {
    named("main") {
        resources.srcDir(generateVersionResource)
    }
}

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
    }

    // MCP protocol — JSON-RPC 2.0 implementation
    implementation("com.google.code.gson:gson:2.10.1")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.2")
    testImplementation("io.mockk:mockk:1.13.10")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

intellijPlatform {
    pluginConfiguration {
        id = "com.tokenslayer.token-slayer-jb"
        name = "TokenSlayer"
        version = pluginVersion
        // "What's New" is rendered from CHANGELOG.md: use the section matching the release
        // version if present, otherwise fall back to the [Unreleased] section (e.g. pre-release
        // builds). See the `changelog { }` block below.
        changeNotes =
            provider {
                with(changelog) {
                    renderItem(
                        (getOrNull(pluginVersion) ?: getUnreleased())
                            .withHeader(false)
                            .withEmptySections(false),
                        Changelog.OutputType.HTML,
                    )
                }
            }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild").get()
            // No upper bound: do NOT cap until-build. A hardcoded cap (e.g. 261.*) is exactly
            // what makes the plugin "incompatible" the moment a newer IDE ships. The plugin uses
            // stable platform APIs, and every language-specific extension is isolated in an
            // optional module, so it degrades gracefully on future builds instead of being blocked.
            // Forward compatibility is validated by the Plugin Verifier (./gradlew verifyPlugin).
            untilBuild = provider { null }
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
        // Marketplace release channel derived from the version's pre-release suffix:
        //   "0.3.0"        -> "default" (stable)
        //   "0.3.0-beta.1" -> "beta"
        //   "0.3.0-alpha.2"-> "alpha"
        channels = listOf(pluginVersion.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

changelog {
    version.set(pluginVersion)
    groups.set(listOf("Added", "Changed", "Deprecated", "Removed", "Fixed", "Security"))
    repositoryUrl.set("https://github.com/donco-labs/token-slayer-jb")
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
