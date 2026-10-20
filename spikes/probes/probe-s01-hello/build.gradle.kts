plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.vos.hello"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vos.hello"
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

// Throwaway probe: debug builds only. The copy baked into /product is signed with this machine's
// AGP debug key, so later probe builds (S-04, S-05, S-27a) can be installed over it with
// `adb install -r` and keep the privileged grants of the system copy.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = false }
}
