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

// The copy baked into /product is relocked on Phone B, so it is a release build (not debuggable)
// signed with a throwaway key made outside the repo for that one image (ci/make-throwaway-release-key.sh,
// VOS_RELEASE_* env vars) and deleted afterwards. Never the machine's debug keystore, whose password
// is public: nothing can be installed over the baked copy later. Debug builds are disabled.
val helloSigning = listOf("STORE_FILE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
    .associateWith { System.getenv("VOS_RELEASE_$it")?.takeIf(String::isNotBlank) }

android {
    signingConfigs {
        if (helloSigning.values.all { it != null }) {
            create("release") {
                storeFile = file(helloSigning.getValue("STORE_FILE")!!)
                storePassword = helloSigning.getValue("STORE_PASSWORD")
                keyAlias = helloSigning.getValue("KEY_ALIAS")
                keyPassword = helloSigning.getValue("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("debug")) { it.enable = false }
}

// Without the four VOS_RELEASE_* values the release APK would be unsigned; fail instead.
tasks.matching { it.name == "assembleRelease" }.configureEach {
    doFirst {
        val missing = helloSigning.filterValues { it == null }.keys
        if (missing.isNotEmpty()) {
            throw GradleException("probe-s01-hello needs a throwaway release key: VOS_RELEASE_${missing.joinToString(", VOS_RELEASE_")} unset (see docs/spikes/S-01.md step 2)")
        }
    }
}
