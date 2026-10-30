plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.qtproject.qt.gradleplugin") version "1.4"
}

android {
    namespace = "org.mudlet.proto.b"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "org.mudlet.proto.b"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        ndk {
            abiFilters += providers.gradleProperty("androidAbi").get()
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(21)
}

QtBuild {
    ninjaPath = rootProject.file("../tools/ninja-j2").absolutePath
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.03.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.core:core-ktx:1.17.0")
}

// The Qt plugin only watches qtProjectPath; the engine lives beside it in ../engine.
tasks.matching { it.name == "QtBuildTask" }.configureEach {
    inputs.dir(rootProject.file("../engine/src")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("../engine/CMakeLists.txt")).withPathSensitivity(PathSensitivity.RELATIVE)
}
