// Standalone build for throwaway spike probes (S-01 hello, S-07, S-08). Deliberately NOT included in the root
// settings.gradle.kts, so root CI and ci/policy_check.py never see these APKs.
// Build from the repo root: ./gradlew -p spikes/probes assembleDebug
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
    // Same AGP version as the root build.
    versionCatalogs {
        create("libs") { from(files("../../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "vos-probes"

include(":probe-s01-hello", ":probe-s07", ":probe-s08-actuator", ":probe-s08-session")
