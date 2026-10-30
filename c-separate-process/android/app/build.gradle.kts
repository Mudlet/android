plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Output of build-apk.sh: androiddeployqt --aux-mode copies the engine .so, its Qt
// dependencies, Qt's jars and the generated libs.xml here
val qtDeployDir = file("../qtdeploy")
val androidAbi = providers.gradleProperty("androidAbi").get()
val qtRoot = providers.gradleProperty("qtRoot").get()
val qtAndroidDir = "$qtRoot/android_${androidAbi.replace('-', '_')}/src/android/java"

android {
    namespace = "org.mudlet.proto.c"
    compileSdk = 36
    buildToolsVersion = "37.0.0"
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "org.mudlet.proto.c"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += androidAbi }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs(qtDeployDir.resolve("libs"))
            res.srcDirs("src/main/res", "$qtAndroidDir/res", qtDeployDir.resolve("res"))
            // Mudlet's Lua files, staged by build-apk.sh when it builds libmudlet in
            assets.srcDirs(qtDeployDir.resolve("assets"))
        }
    }

    buildTypes {
        getByName("debug") { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    androidResources {
        noCompress += "rcc"
    }

    packaging {
        // Uncompressed and page-aligned in the APK, loaded in place
        jniLibs { useLegacyPackaging = false }
    }

    testOptions {
        unitTests.all { test ->
            test.systemProperty("engineBin", project.findProperty("engineBin") ?: "")
            test.systemProperty("fakePort", project.findProperty("fakePort") ?: "")
            test.testLogging { showStandardStreams = true }
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(fileTree(qtDeployDir.resolve("libs")) { include("*.jar") })
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
    // The real org.json; android.jar only has stubs for unit tests
    testImplementation("org.json:json:20250517")
}
