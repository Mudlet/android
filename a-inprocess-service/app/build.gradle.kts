plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val qtAndroidDir: String = providers.gradleProperty("qtAndroidDir").get()
val nativeStage = rootProject.layout.projectDirectory.dir("build-android/stage")

android {
    namespace = "org.mudlet.proto.a"
    compileSdk = 36
    // AGP 8.13 defaults to 35.0.0 and silently installs it into the shared SDK; pin the installed one
    buildToolsVersion = "37.0.0"
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "org.mudlet.proto.a"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-a"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures { compose = true }

    sourceSets["main"].apply {
        // libmudletproto + the Qt libraries androiddeployqt picked, and the libs.xml that
        // tells Qt's QtLoader which of them to System.load() and in what order
        jniLibs.srcDir(nativeStage.dir("libs"))
        res.srcDirs("$qtAndroidDir/src/android/java/res", nativeStage.dir("res"))
        assets.srcDir(nativeStage.dir("assets"))
    }

    packaging {
        // Uncompressed, page-aligned .so files loaded straight from the APK (Qt's default too)
        jniLibs { useLegacyPackaging = false }
    }

    androidResources { noCompress += "rcc" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // Qt's Java side; the classes Qt's C++ calls into must be in the APK's dex
    implementation(files(
        "$qtAndroidDir/jar/Qt6Android.jar",
        "$qtAndroidDir/jar/Qt6AndroidNetwork.jar",
        "$qtAndroidDir/jar/Qt6AndroidNetworkInformationBackend.jar",
    ))

    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-service:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}

// The C++ side: CMake + ninja (-j2) + androiddeployqt --aux-mode into build-android/stage.
val buildNative = tasks.register<Exec>("buildNative") {
    group = "build"
    description = "Builds libmudletproto with Qt for Android and stages it for packaging"
    workingDir = rootProject.projectDir
    commandLine("bash", rootProject.file("scripts/build-native.sh").absolutePath)
    environment("ANDROID_SDK_ROOT", android.sdkDirectory.absolutePath)
    inputs.dir(rootProject.file("engine"))
    inputs.dir(rootProject.file("native"))
    inputs.file(rootProject.file("scripts/build-native.sh"))
    outputs.dir(nativeStage)
}
tasks.named("preBuild") { dependsOn(buildNative) }
