plugins {
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvmToolchain(21)

    // Android target — the primary platform. This is where the full self-managed IME lives (later).
    android {
        namespace = "top.yukonga.scripta.editor"
        compileSdk {
            version = release(37) {
                minorApiLevel = 0
            }
        }
        minSdk = 24
    }

    // Desktop (JVM/skiko) target — simplified input path; kept so the shared logic stays multiplatform.
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            // Brings compose runtime + ui + ui-text + foundation-layout transitively.
            api(libs.compose.foundation)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
