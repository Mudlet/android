// The UI process: Compose, no Qt. It starts the engine (:engine-service) and talks to it over :protocol.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val androidAbi = providers.gradleProperty("androidAbi").get()

android {
    namespace = "org.mudlet.android"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "org.mudlet.mudlet"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += androidAbi }
    }

    buildTypes {
        getByName("debug") { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    }

    buildFeatures { compose = true }

    androidResources {
        noCompress += "rcc"
    }

    packaging {
        // By default uncompressed and page-aligned in the APK, loaded in place. -PextractNativeLibs=true has the
        // installer extract them, which Lua's require() needs to find a C module on package.cpath
        // (docs/architecture.md, "Native libraries")
        jniLibs {
            useLegacyPackaging = providers.gradleProperty("extractNativeLibs").map { it.toBooleanStrict() }.getOrElse(false)
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(libs.versions.jvmTarget.get())) }
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":engine-service"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
}
