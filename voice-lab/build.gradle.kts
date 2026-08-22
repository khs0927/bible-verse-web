plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

repositories {
    mavenCentral()
}

kotlin {
    js {
        browser {
            distribution {
                outputDirectory.set(projectDir.resolve("build/web-dist"))
            }
        }
        binaries.executable()
    }

    sourceSets {
        val jsMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-browser:0.5.0")
                implementation(npm("onnxruntime-web", "1.27.0"))
                implementation(npm("@sctg/sentencepiece-js", "1.3.3"))
            }
        }
    }
}
