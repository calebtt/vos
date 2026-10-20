plugins {
    alias(libs.plugins.android.application)
}

@Suppress("UNCHECKED_CAST")
val releaseSigning = rootProject.extra["vosReleaseSigning"] as Map<String, String>?

android {
    namespace = "com.vos.session"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vos.session"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1"
        // M1-AUTH-01 probe (eval/m1/auth-probe), in the debug androidTest APK only.
        testInstrumentationRunner = "com.vos.eval.auth.AuthProbe"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getValue("storeFile"))
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // AGP's default debug keystore: a different cert from release by construction.
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    sourceSets["androidTest"].java.srcDir(rootProject.file("eval/m1/auth-probe/java"))

    buildFeatures {
        buildConfig = true // BuildConfig.DEBUG gates debug-only code paths
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":ipc"))
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.orgjson)
}
