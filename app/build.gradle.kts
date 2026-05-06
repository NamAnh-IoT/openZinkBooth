import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.photo.openzinkbooth"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.photo.openzinkbooth"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        configureEach {
            buildConfigField("String", "GIT_SHA", "\"${gitSha()}\"")
            buildConfigField("String", "BUILD_TIME_UTC", "\"${buildTimeUtc()}\"")
        }

        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                output.outputFileName.set("openZinkBooth-${variant.name}.apk")
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3.window.size.class1)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // BLE
    implementation(libs.blessed.android.coroutines)

    // Persistent settings
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

fun safeExec(vararg cmd: String): String = try {
    val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
    p.inputStream.bufferedReader().use { it.readText() }.trim().ifEmpty { "unknown" }
} catch (_: Exception) { "unknown" }

fun gitSha(): String {
    System.getenv("GIT_SHA")?.takeIf { it.isNotBlank() }?.let { return it }
    return safeExec("git", "rev-parse", "--short", "HEAD")
}

fun buildTimeUtc(): String {
    System.getenv("BUILD_TIME_UTC")?.takeIf { it.isNotBlank() }?.let { return it }
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
    sdf.timeZone = TimeZone.getTimeZone("UTC")
    return sdf.format(Date())
}