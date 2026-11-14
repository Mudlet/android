// The engine process: EngineService (a QtServiceBase in ":engine"), its notifications, and the Qt libraries and
// jars that scripts/build-apk.sh stages with androiddeployqt --aux-mode, merged into the app from here
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Output of scripts/build-apk.sh: the engine .so, its Qt dependencies, Qt's jars and the generated libs.xml
val qtDeployDir = file("qtdeploy")
// The first of the ABIs (-PandroidAbi, comma-separated for a release): Qt's Java resources are the same for each
val androidAbi = providers.gradleProperty("androidAbi").get().split(',').first().trim()
val qtRoot = providers.gradleProperty("qtRoot").get()
val qtAndroidDir = "$qtRoot/android_${androidAbi.replace('-', '_')}/src/android/java"

android {
    namespace = "org.mudlet.android.engine"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    // to strip the staged libraries in its own release build, as the app strips them again
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        // The app's R8 applies these: Qt's Java classes, and what the engine calls through JNI
        consumerProguardFiles("consumer-rules.pro")
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs(qtDeployDir.resolve("libs"))
            res.srcDirs("src/main/res", "$qtAndroidDir/res", qtDeployDir.resolve("res"))
        }
    }

    lint {
        // Android lint: a warning fails the build too (CI runs it), and lint.xml says what is off and why
        warningsAsErrors = true
        lintConfig = rootProject.file("lint.xml")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(libs.versions.jvmTarget.get())) }
}

dependencies {
    // Qt6Android.jar (QtServiceBase) and the other jars androiddeployqt staged
    implementation(fileTree(qtDeployDir.resolve("libs")) { include("*.jar") })
    implementation(libs.androidx.core.ktx)
}
