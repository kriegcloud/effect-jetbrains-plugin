import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    alias(libs.plugins.kotlin)
    id("org.jetbrains.intellij.platform")
    alias(libs.plugins.changelog)
    alias(libs.plugins.qodana)
    alias(libs.plugins.kover)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(libs.commonsCompress)
    implementation(libs.jacksonDatabind)
    implementation(libs.javaWebSocket)

    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)

    intellijPlatform {
        webstorm(providers.gradleProperty("platformVersion"))
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { value ->
            value.split(',').map(String::trim).filter(String::isNotEmpty)
        })
        plugins(providers.gradleProperty("platformPlugins").map { value ->
            value.split(',').map(String::trim).filter(String::isNotEmpty)
        })
        bundledModules(providers.gradleProperty("platformBundledModules").map { value ->
            value.split(',').map(String::trim).filter(String::isNotEmpty)
        })
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        id = providers.gradleProperty("pluginId")
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }

                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        vendor {
            name = "Effect"
            email = "hello@effect.website"
            url = "https://effect.website"
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            listOf(pluginVersion.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.WebStorm, providers.gradleProperty("platformVersion"))
            create(IntelliJPlatformType.WebStorm, providers.gradleProperty("pluginVerifierWebStormVersion"))
            create(IntelliJPlatformType.IntellijIdeaUltimate, providers.gradleProperty("pluginVerifierIntelliJIdeaVersion"))
        }
    }
}

intellijPlatformTesting {
    runIde {
        // Sandbox launch against the newest verified WebStorm line (currently the 2026.3 EAP), so the
        // plugin can be smoked on the upper edge of the compatibility range without replacing the
        // stable compile target.
        register("runIdeVerifierWebStorm") {
            type = IntelliJPlatformType.WebStorm
            version = providers.gradleProperty("pluginVerifierWebStormVersion")
        }
    }
}

changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
    versionPrefix = ""
}

kover {
    reports {
        total {
            xml {
                onCheck = true
            }
        }
    }
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    publishPlugin {
        dependsOn(patchChangelog)
    }
}

tasks.named("qodanaScan") {
    mustRunAfter(tasks.matching { candidate -> candidate.name != name })
}
