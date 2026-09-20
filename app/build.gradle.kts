import java.net.URI
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.dropshare.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dropshare.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "2.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = if (project.hasProperty("ciStoreFile")) {
                signingConfigs.create("ciRelease") {
                    storeFile = file(project.property("ciStoreFile") as String)
                    storePassword = project.property("ciStorePassword") as String
                    keyAlias = project.property("ciKeyAlias") as String
                    keyPassword = project.property("ciKeyPassword") as String
                }
            } else signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Nearby Connections: local discovery + high-speed peer-to-peer transfer.
    implementation("com.google.android.gms:play-services-nearby:19.5.0")

    // Camera used only while the user performs the air-grab/drop gesture.
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")

    // On-device palm/fist/open-palm recognition.
    implementation("com.google.mediapipe:tasks-vision:1.0.0")
}

// The gesture model is intentionally downloaded during the build so the GitHub
// repository does not need to store an 8+ MB binary in Git. The resulting APK
// contains the model and therefore does not need internet access for gesture AI.
val gestureModelUrl = "https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task"
val gestureModelFile = file("src/main/assets/gesture_recognizer.task")

tasks.register("downloadGestureModel") {
    outputs.file(gestureModelFile)

    doLast {
        if (!gestureModelFile.exists() || gestureModelFile.length() < 1_000_000) {
            gestureModelFile.parentFile.mkdirs()
            println("Downloading DropShare gesture model…")

            val connection = URI.create(gestureModelUrl).toURL().openConnection()
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000

            connection.getInputStream().use { input ->
                gestureModelFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        check(gestureModelFile.length() > 1_000_000) {
            "Gesture model download failed or is incomplete."
        }
    }
}

tasks.matching { it.name == "preDebugBuild" || it.name == "preReleaseBuild" }.configureEach {
    dependsOn("downloadGestureModel")
}
