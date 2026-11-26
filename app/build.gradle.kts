import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest


plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.secrets)
}


/**
 * The preview eye-focus detector loads this model from Android assets at runtime.
 * Keep it generated under build/ so the APK always includes the real model without
 * requiring a network connection on the device. The pinned SHA-256 prevents silently
 * packaging an incompatible or changed upstream model.
 */
val eyeFocusAssetDirectory = layout.buildDirectory.dir("generated/eyeFocusAssets")
val eyeFocusModelFile = eyeFocusAssetDirectory.map { it.file("blaze_face_full_range.tflite") }
val eyeFocusModelUrl =
    "https://huggingface.co/litert-community/MediaPipe-FaceDetector-BlazeFace-Full-Range/resolve/main/blaze_face_full_range.tflite"
val eyeFocusModelSha256 = "3698b18f063835bc609069ef052228fbe86d9c9a6dc8dcb7c7c2d69aed2b181b"

val downloadEyeFocusModel by tasks.registering {
    inputs.property("modelUrl", eyeFocusModelUrl)
    inputs.property("modelSha256", eyeFocusModelSha256)
    outputs.file(eyeFocusModelFile)

    doLast {
        val outputFile = eyeFocusModelFile.get().asFile

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        }

        if (outputFile.isFile && sha256(outputFile) == eyeFocusModelSha256) {
            return@doLast
        }

        outputFile.parentFile.mkdirs()
        val tempFile = File(outputFile.parentFile, "${outputFile.name}.download")
        val connection = URL(eyeFocusModelUrl).openConnection()
            as HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("User-Agent", "Photon.camera-Android-build")
            connection.inputStream.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }

            val actualSha256 = sha256(tempFile)
            if (actualSha256 != eyeFocusModelSha256) {
                throw GradleException(
                    "Eye-focus model checksum mismatch: expected $eyeFocusModelSha256, got $actualSha256"
                )
            }
            if (!tempFile.renameTo(outputFile)) {
                tempFile.copyTo(outputFile, overwrite = true)
            }
        } finally {
            connection.disconnect()
            tempFile.delete()
        }
    }
}

fun String.toBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.hinnka.mycamera"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.hinnka.mycamera"
        minSdk = 30
        targetSdk = 36
        versionCode = 162
        versionName = "1.28.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
                // Build the JNI library and its dependencies, excluding libjpeg tools/tests.
                targets += "my-native-lib"
            }
        }

        buildConfigField(
            "String",
            "BUILT_IN_API_URL",
            "https://camera-api.hinnka.me/v1".toBuildConfigString()
        )
        buildConfigField(
            "String",
            "FLAVOR",
            "\"default\""
        )
    }

    secrets {
        propertiesFileName = ".env"
        defaultPropertiesFileName = ".env.example"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val storeFileProp = project.findProperty("RELEASE_STORE_FILE") as? String
            if (storeFileProp != null) {
                storeFile = file(storeFileProp)
                storePassword = project.findProperty("RELEASE_STORE_PASSWORD") as? String
                keyAlias = project.findProperty("RELEASE_KEY_ALIAS") as? String
                keyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as? String
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // Run the same R8 code shrinking and resource shrinking in CI's
            // debug APK so the artifact published by GitHub Actions is minified.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        // Keep fatal lint analysis off the Release packaging path.
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += "tflite"
    }



    sourceSets {
        getByName("main") {
            java {
                srcDir("src/default/java")
            }
        }
    }
    
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

// Package the generated MediaPipe model as a normal Android asset.
android.sourceSets.getByName("main").assets.srcDir(eyeFocusAssetDirectory)

tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) {
        dependsOn(downloadEyeFocusModel)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(project(":Prismal"))
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.recyclerview)
    
    // ViewModel Compose
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    
    // Material Icons Core
    implementation(libs.androidx.material.icons.core)
    
    // Coil for image loading
    implementation(libs.coil.compose)

    // Telephoto for large image viewing with zoom support
    implementation("me.saket.telephoto:zoomable-image-coil:0.18.0")
    
    // Navigation Compose
    implementation(libs.androidx.navigation.compose)
    
    // ExifInterface for writing EXIF metadata
    implementation(libs.androidx.exifinterface)

    // HEIC export through the platform image encoder
    implementation(libs.androidx.heifwriter)
    
    // DataStore for user preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation(libs.androidx.animation.core)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.gson)

    // MediaPipe Face Detector for human eye autofocus
    implementation("com.google.mediapipe:tasks-vision:1.0.0")


    // Bugly crash reporting
    implementation(libs.bugly.crashreport)

    // Reorderable for drag-and-drop list reordering
    implementation("sh.calvin.reorderable:reorderable:2.4.3")

    // Media3 for video playback and export
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.effect)
    implementation(libs.media3.transformer)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // TensorFlow Lite for Depth Estimator
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.17.0")
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.17.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.5.0")
}
