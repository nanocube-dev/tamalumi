import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val litertlmVersion: String = providers.gradleProperty("litertlm.version").getOrElse("0.19.0")

val uploadKey: Properties? = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

android {
    namespace = "com.lou.lumi"
    compileSdk = 36

    defaultConfig {
        // Play package name (reverse of tamalumi.de), permanent after the first upload.
        // The Kotlin namespace above stays com.lou.lumi.
        applicationId = "de.tamalumi.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Release signing with Lumi's own upload key (Play App Signing). keystore.properties (git-ignored,
    // template: keystore.properties.example) names the keystore OUTSIDE the project and the alias.
    // Passwords are never stored: tools/build-release.ps1 asks for them and passes them via the
    // environment (LUMI_STORE_PASSWORD / LUMI_KEY_PASSWORD) of that one build only.
    // Without keystore.properties, release falls back to the local debug key (installable test builds);
    // bundleRelease refuses that, so a debug-signed bundle can never reach the Play Console.
    signingConfigs {
        if (uploadKey != null) {
            create("upload") {
                storeFile = file(uploadKey.getProperty("storeFile"))
                keyAlias = uploadKey.getProperty("keyAlias")
                storePassword = System.getenv("LUMI_STORE_PASSWORD")
                keyPassword = System.getenv("LUMI_KEY_PASSWORD") ?: System.getenv("LUMI_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("lumi.renderDir", layout.buildDirectory.dir("renders").get().asFile.absolutePath)
            // -Plumi.net=true runs BrainDownloadNetTest against the real Hugging Face servers
            it.systemProperty("lumi.net", providers.gradleProperty("lumi.net").getOrElse("false"))
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // On-device LLM: Gemma 4 E2B through LiteRT-LM (same stack as Gemma Voice)
    implementation("com.google.ai.edge.litertlm:litertlm-android:$litertlmVersion")

    // VAD + STT + TTS (ONNX, fully offline) – vendored, same build as Gemma Voice
    implementation(files("libs/sherpa-onnx-1.13.2.aar"))

    testImplementation("junit:junit:4.13.2")
    // Renders PetView frames to PNG on the PC (native graphics), so the look can be checked without a phone
    testImplementation("org.robolectric:robolectric:4.14.1")
}

// A bundle for the Play Store must carry the upload key and its passwords – fail early instead of
// producing a debug-signed .aab or failing deep inside the signing task.
gradle.taskGraph.whenReady {
    if (allTasks.any { it.project == project && it.name == "bundleRelease" }) {
        check(uploadKey != null) {
            "bundleRelease braucht den Upload-Schlüssel: keystore.properties fehlt (Vorlage: keystore.properties.example)."
        }
        check(file(uploadKey.getProperty("storeFile")).isFile) {
            "Keystore nicht gefunden: ${uploadKey.getProperty("storeFile")}"
        }
        check(!System.getenv("LUMI_STORE_PASSWORD").isNullOrEmpty()) {
            "Passwort fehlt – bitte über tools/build-release.ps1 bauen."
        }
    }
}
