import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "effect-jetbrains-plugin"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.18.0"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()

        intellijPlatform {
            defaultRepositories()
        }
    }
}
