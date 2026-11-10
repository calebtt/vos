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
        buildConfig = true // BuildConfig.DEBUG: only a debug build mirrors audit lines to logcat (#340)
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

// The audit chain's host tool (M2-AUDIT-01, #154): AuditChain.verify on audit JSONL files, on a
// plain JVM, with the unit tests' classpath (real org.json); never in an APK. For example:
//   ./gradlew -q :ipc:auditVerify --args="verify --head HEX /abs/path/audit.jsonl"
// Use absolute paths (the tool runs in ipc/). eval/m2/audit-01.sh drives it.
tasks.register<JavaExec>("auditVerify") {
    group = "verification"
    description = "Verifies an audit JSONL file's hash chain (AuditVerify: verify | generate)."
    dependsOn("compileDebugUnitTestJavaWithJavac")
    mainClass.set("com.vos.ipc.AuditVerify")
    classpath(provider { tasks.named<Test>("testDebugUnitTest").get().classpath })
}
