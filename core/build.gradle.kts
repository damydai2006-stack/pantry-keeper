plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
}

kotlin {
    jvmToolchain(21)
    androidTarget()
    jvm()
    sourceSets {
        commonMain.dependencies {
            implementation(libs.datetime)
            implementation(libs.serialization.json)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test.junit5)
        }
    }
}

android {
    namespace = "com.pantrykeeper.core"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
