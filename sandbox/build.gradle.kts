plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "top.yukonga.scripta"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }
    defaultConfig {
        applicationId = "top.yukonga.scripta"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(projects.editor)
    implementation(libs.androidx.activity.compose)

    // Spike: pure-logic unit tests for the editor model (runs on the JVM, no device).
    testImplementation("junit:junit:4.13.2")
}
