plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.vos.spike.s08.actuator"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vos.spike.s08.actuator"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1-spike"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Throwaway probe: debug builds only. Both S-08 APKs use this machine's AGP debug key, which the
// signature permission guarding ActuatorService relies on.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = false }
}
