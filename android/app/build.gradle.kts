import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val pantryLocal = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun injected(name: String, default: String = ""): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: pantryLocal.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: default

fun String.asBuildConfig(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val serverUrl = injected("PANTRY_SERVER_URL", "http://127.0.0.1:18280")
val caPins = injected("PANTRY_CA_PINS")
val debugPhone = injected("PANTRY_DEBUG_PHONE")
val debugCode = injected("PANTRY_DEBUG_CODE")

android {
    namespace = "com.damy.pantry"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.damy.pantry"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "SERVER_URL", serverUrl.asBuildConfig())
        buildConfigField("String", "CA_PINS", caPins.asBuildConfig())
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    buildTypes {
        debug {
            buildConfigField("String", "DEBUG_PHONE", debugPhone.asBuildConfig())
            buildConfigField("String", "DEBUG_CODE", debugCode.asBuildConfig())
        }
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
    }
}

dependencies {
    implementation(project(":android:data"))
    implementation(project(":android:capture"))
    implementation(platform(libs.compose.bom))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.process)
    implementation(libs.navigation.compose)
    implementation(libs.serialization.json)
    debugImplementation("androidx.compose.ui:ui-tooling")
}
