pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "vos"

include(":actuator", ":session", ":vtb-sdk", ":ipc")

// Test fixtures (debug only): eval/fixtures/<name>.
include(":fixture-auth")
project(":fixture-auth").projectDir = file("eval/fixtures/auth")
