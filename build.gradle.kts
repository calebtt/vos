import java.util.Properties
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

plugins {
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

// Release signing comes from env vars, else from the ignored keystore.properties. A release key is
// never committed. With neither present, release APKs build unsigned (app-release-unsigned.apk).
// Debug builds use AGP's per-machine debug keystore, so debug and release certs always differ;
// ci/policy_check.py fails if a signed release shares its cert with the debug build.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)?.takeIf { it.isNotBlank() }

val releaseSigning: Map<String, String>? = run {
    val values = mapOf(
        "storeFile" to signingValue("VOS_RELEASE_STORE_FILE", "storeFile"),
        "storePassword" to signingValue("VOS_RELEASE_STORE_PASSWORD", "storePassword"),
        "keyAlias" to signingValue("VOS_RELEASE_KEY_ALIAS", "keyAlias"),
        "keyPassword" to signingValue("VOS_RELEASE_KEY_PASSWORD", "keyPassword"),
    )
    if (values.values.all { it != null }) values.mapValues { it.value!! } else null
}
extra["vosReleaseSigning"] = releaseSigning

// Every module writes its resolved dependency coordinates for the policy check (M0-02), which
// rejects Play services, Firebase and ML Kit download artifacts.
subprojects {
    val sub = this
    tasks.register("writePolicyDeps") {
        group = "verification"
        description = "Writes resolved dependency coordinates to build/policy/deps.txt for ci/policy_check.py."
        val out = layout.buildDirectory.file("policy/deps.txt")
        outputs.file(out)
        outputs.upToDateWhen { false }
        doLast {
            val coords = sortedSetOf<String>()
            val confs = sub.configurations
                .filter { it.isCanBeResolved && (it.name.endsWith("RuntimeClasspath") || it.name.endsWith("CompileClasspath")) }
                .filter { !it.name.contains("UnitTest") && !it.name.contains("AndroidTest") }
            if (confs.isEmpty()) throw GradleException("writePolicyDeps: no classpath configurations in ${sub.path}")
            confs.forEach { conf ->
                conf.incoming.resolutionResult.allComponents.forEach { c ->
                    val mv = c.moduleVersion
                    if (mv != null && c.id !is ProjectComponentIdentifier) coords += "${mv.group}:${mv.name}:${mv.version}"
                }
            }
            out.get().asFile.apply {
                parentFile.mkdirs()
                writeText("# configurations: ${confs.joinToString(" ") { it.name }}\n" + coords.joinToString("") { "$it\n" })
            }
        }
    }
}

val policyCheck = tasks.register<Exec>("policyCheck") {
    group = "verification"
    description = "Static policy check of the built APKs (M0-02). See ci/README.md."
    dependsOn(
        ":actuator:assembleRelease", ":session:assembleRelease",
        ":actuator:assembleDebug", ":session:assembleDebug",
        ":actuator:writePolicyDeps", ":session:writePolicyDeps", ":vtb-sdk:writePolicyDeps",
    )
    workingDir = rootDir
    commandLine(
        "python3", "-I", "ci/policy_check.py",
        "--root", rootDir.absolutePath,
        "--gitleaks", System.getenv("VOS_GITLEAKS") ?: "auto",
    )
}

val policyCheckTests = tasks.register<Exec>("policyCheckTests") {
    group = "verification"
    description = "Negative tests: every policy rule must fail on a violating fixture."
    workingDir = rootDir
    commandLine("python3", "-I", "-m", "unittest", "discover", "-s", "ci/tests", "-t", "ci", "-v")
}

tasks.named("check") { dependsOn(policyCheckTests, policyCheck) }
