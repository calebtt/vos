plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.vos.ipc"
    compileSdk = 35

    defaultConfig {
        minSdk = 34
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.orgjson) // android.jar's org.json is a stub in JVM tests
}
