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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
}
