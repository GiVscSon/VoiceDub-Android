import java.io.File as JavaFile
import java.net.URL
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.voicedub.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.voicedub.app.preview"
        minSdk = 29
        targetSdk = 35
        versionCode = 8
        versionName = "0.6.0-local-preview"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "runtime"
    productFlavors {
        create("core") { dimension = "runtime"; versionNameSuffix = "-core"; manifestPlaceholders["appLabel"] = "VoiceDub 0.6 Core" }
        create("tiny") { dimension = "runtime"; versionNameSuffix = "-tiny"; manifestPlaceholders["appLabel"] = "VoiceDub 0.6 Tiny" }
    }

    sourceSets.getByName("tiny").assets.srcDir(rootProject.file("third-party"))

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug/voicedub-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
}

dependencies {
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("com.google.mlkit:translate:17.0.3")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// Pinned optional Android runtime. Core builds never download or package it.
val sherpaAar = layout.buildDirectory.file("sherpa/sherpa-onnx-2.0.1.aar")
val fetchSherpaRuntime = tasks.register("fetchSherpaRuntime") {
    outputs.file(sherpaAar)
    doLast {
        val file = sherpaAar.get().asFile
        file.parentFile.mkdirs()
        val temp = JavaFile(file.parentFile, file.name + ".part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val connection = URL("https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar").openConnection()
            connection.connectTimeout = 15000; connection.readTimeout = 30000
            connection.getInputStream().use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var size = 0L
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        size += n; check(size <= 49441341L)
                        digest.update(buffer, 0, n); output.write(buffer, 0, n)
                    }
                }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7") { "Sherpa runtime checksum mismatch" }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }
}
dependencies { add("tinyImplementation", files(sherpaAar).builtBy(fetchSherpaRuntime)) }
