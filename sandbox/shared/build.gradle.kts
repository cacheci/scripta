plugins {
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "top.yukonga.scripta.sandbox.shared"
        compileSdk {
            version = release(37) {
                minorApiLevel = 0
            }
        }
        minSdk = 24
    }

    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            api(projects.editor)
            api(libs.compose.foundation)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
    }
}
