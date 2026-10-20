// M1-AUTH-01 fixtures: two APKs that try to bind and call the VOS services (eval/m1/auth-01.sh).
//   wrongpkg  com.vos.fixture.wrongpkg, signed with this machine's debug key: the same cert as the VOS
//             debug build, so it holds the signature permissions but is the wrong package.
//   foreign   com.vos.fixture.foreign, signed with a throwaway key made in build/ at build time: the
//             system refuses its bind before the VOS guard sees it.
// Debug variants only; never installed outside the emulator and Phone B.
plugins {
    alias(libs.plugins.android.application)
}

val foreignKeystore = layout.buildDirectory.file("foreign-key/foreign.p12").get().asFile
val makeForeignKey = tasks.register<Exec>("makeForeignKey") {
    outputs.file(foreignKeystore)
    onlyIf { !foreignKeystore.exists() }
    doFirst { foreignKeystore.parentFile.mkdirs() }
    commandLine(
        File(System.getProperty("java.home"), "bin/keytool").path, "-genkeypair", "-noprompt",
        "-keystore", foreignKeystore.path, "-storetype", "PKCS12", "-storepass", "android", "-keypass", "android",
        "-alias", "foreign", "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
        "-dname", "CN=VOS throwaway foreign fixture",
    )
}

android {
    namespace = "com.vos.fixture.auth"
    compileSdk = 35

    defaultConfig {
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1-fixture"
    }

    signingConfigs {
        create("foreign") {
            storeFile = foreignKeystore
            storeType = "PKCS12"
            storePassword = "android"
            keyAlias = "foreign"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            // Each flavor names its signer; the build type must not override it.
            signingConfig = null
        }
    }

    flavorDimensions += "signer"
    productFlavors {
        create("wrongpkg") {
            dimension = "signer"
            applicationId = "com.vos.fixture.wrongpkg"
            signingConfig = signingConfigs.getByName("debug")
        }
        create("foreign") {
            dimension = "signer"
            applicationId = "com.vos.fixture.foreign"
            signingConfig = signingConfigs.getByName("foreign")
        }
    }

    sourceSets["main"].java.srcDir(rootProject.file("eval/m1/auth-probe/java"))

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = false }
}

tasks.configureEach {
    if (name.startsWith("package") || name.startsWith("validateSigning")) dependsOn(makeForeignKey)
}

dependencies {
    implementation(project(":ipc"))
}
