import com.android.build.api.artifact.SingleArtifact
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Signing: see README "Signing". guard and filter MUST be signed with the same key,
// otherwise the signature-level IPC permission is not granted between them.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.detour.guard"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.detour.guard"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // Without keystore.properties, release falls back to the debug key (still shared).
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":ipc"))
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}

// ---------------------------------------------------------------------------------------------
// Privacy guarantee: fail the build if guard's MERGED manifest (after every library has been
// merged in) asks for any network permission. Runs for every variant before it is packaged,
// so neither Android Studio's Run button nor `assembleRelease` can produce an APK that has one.
// Run it on its own with:  gradlew :guard:checkReleaseNoNetworkPermissions
// ---------------------------------------------------------------------------------------------
abstract class CheckNoNetworkPermissions : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val forbidden = setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.CHANGE_NETWORK_STATE",
        )
        val androidNs = "http://schemas.android.com/apk/res/android"
        val doc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(mergedManifest.get().asFile)

        val requested = listOf("uses-permission", "uses-permission-sdk-23").flatMap { tag ->
            val nodes = doc.getElementsByTagName(tag)
            (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(androidNs, "name") }
        }
        val found = requested.filter { it in forbidden }
        if (found.isNotEmpty()) {
            throw GradleException(
                "guard must not have network permissions, but its merged manifest contains: " +
                    "${found.joinToString()}. Find the library that adds it and add " +
                    "tools:node=\"remove\" for it in guard/src/main/AndroidManifest.xml.",
            )
        }
        report.get().asFile.writeText(
            "OK: no network permissions in merged manifest.\nRequested: ${requested.joinToString()}\n",
        )
    }
}

androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val checkTask = tasks.register<CheckNoNetworkPermissions>("check${cap}NoNetworkPermissions") {
            group = "verification"
            description = "Fails if guard's merged ${variant.name} manifest requests a network permission."
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            report.set(layout.buildDirectory.file("reports/network-permissions/${variant.name}.txt"))
        }
        tasks.configureEach {
            if (name == "package$cap" || name == "bundle$cap" || name == "check") dependsOn(checkTask)
        }
    }
}
