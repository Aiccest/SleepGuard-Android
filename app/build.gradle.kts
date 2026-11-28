plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sleepmate.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.sleepmate.app"
        minSdk = 28
        targetSdk = 33
        versionCode = 4
        versionName = "1.3.0"
    }
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("SLEEPMATE_STORE_FILE")
            storeFile = if (keystorePath.isNullOrBlank()) file("missing-release-keystore.jks") else file(keystorePath)
            storePassword = System.getenv("SLEEPMATE_STORE_PASSWORD")
            keyAlias = System.getenv("SLEEPMATE_KEY_ALIAS")
            keyPassword = System.getenv("SLEEPMATE_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mediapipe:tasks-vision:0.10.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// Pin the official MediaPipe Face Landmarker model and verify its bytes.
// The model is downloaded only during the build and packaged as an APK asset;
// the installed app loads it locally and does not need network access.
val faceLandmarkerModel = layout.projectDirectory.file("src/main/assets/face_landmarker.task")
val downloadFaceLandmarkerModel by tasks.registering {
    outputs.file(faceLandmarkerModel)
    doLast {
        val modelFile = faceLandmarkerModel.asFile
        val expectedSha256 = "64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff"
        fun sha256(file: java.io.File): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }

        if (!modelFile.exists() || sha256(modelFile) != expectedSha256) {
            modelFile.parentFile.mkdirs()
            val temporaryFile = java.io.File(modelFile.parentFile, modelFile.name + ".part")
            temporaryFile.delete()
            val url = java.net.URL("https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task")
            url.openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 60_000
            }.getInputStream().use { input ->
                temporaryFile.outputStream().use { output -> input.copyTo(output) }
            }
            check(sha256(temporaryFile) == expectedSha256) {
                "MediaPipe model SHA-256 mismatch; refusing to package an unverified model."
            }
            check(temporaryFile.length() == 3_758_596L) {
                "Unexpected MediaPipe model size: ${temporaryFile.length()} bytes"
            }
            check(temporaryFile.renameTo(modelFile)) { "Unable to install verified MediaPipe model asset." }
        }
        logger.lifecycle("Verified MediaPipe Face Landmarker model: ${modelFile.length()} bytes; SHA-256 OK")
    }
}

tasks.named("preBuild").configure {
    dependsOn(downloadFaceLandmarkerModel)
}
