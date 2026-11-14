// The UI process: Compose, no Qt. It starts the engine (:engine-service) and talks to it over :protocol.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// One ABI, or a comma-separated list for a release: scripts/build-release.sh stages each ABI's engine
val androidAbis = providers.gradleProperty("androidAbi").get().split(',').map { it.trim() }

// -PsplitApks=true: an APK for each ABI ("splits" below)
val splitApks = providers.gradleProperty("splitApks").map { it.toBooleanStrict() }.getOrElse(false)

// -PreleaseChannel=ptb: the public test build, an app of its own beside the stable one (docs/ptb.md, "Identity")
val releaseChannel = providers.gradleProperty("releaseChannel").getOrElse("stable")
require(releaseChannel in setOf("stable", "ptb")) { "releaseChannel is stable or ptb, not '$releaseChannel'" }

// The release's upload key comes from the environment, so no key or password is ever in a file here. Without
// MUDLET_KEYSTORE the release AAB and APKs come out unsigned; CI signs with a throwaway key when it has no secrets.
fun env(name: String) = providers.environmentVariable(name).orNull?.takeIf { it.isNotEmpty() }
val uploadKeystore = env("MUDLET_KEYSTORE")

android {
    namespace = "org.mudlet.android"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "org.mudlet.mudlet"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // CI passes both for a PTB (.github/workflows/ptb.yml, docs/ptb.md "Versions")
        versionCode = providers.gradleProperty("versionCode").map { it.toInt() }.getOrElse(1)
        versionName = providers.gradleProperty("versionName").getOrElse("0.1-dev")
        // Only the ABIs the engine is staged for: Compose brings a library for every ABI, and Play would offer the app
        // to devices of any ABI the AAB holds a library for. With splitApks, the splits' include does this instead
        if (!splitApks) ndk { abiFilters += androidAbis }
        if (releaseChannel == "ptb") {
            applicationIdSuffix = ".ptb"
            resValue("string", "app_name", "Mudlet PTB")
        } else {
            resValue("string", "app_name", "Mudlet")
        }
        // The instrumented tests in src/androidTest; native/tests/android_instrumented.py runs them on a device
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (uploadKeystore != null) {
            create("upload") {
                storeFile = file(uploadKeystore)
                storePassword = env("MUDLET_KEYSTORE_PASSWORD") ?: error("MUDLET_KEYSTORE is set, MUDLET_KEYSTORE_PASSWORD is not")
                keyAlias = env("MUDLET_KEY_ALIAS") ?: error("MUDLET_KEYSTORE is set, MUDLET_KEY_ALIAS is not")
                keyPassword = env("MUDLET_KEY_PASSWORD") ?: storePassword
            }
        }
    }

    buildTypes {
        getByName("debug") { isMinifyEnabled = false }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            // What Qt and the engine reach by name is kept by :engine-service's consumer-rules.pro
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("upload")
            // The native libraries' symbol tables go into the AAB, so Play's crash reports name native functions
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
        }
        // The release build, minified and shrunk the same way, with the debug build's SmokeTestReceiver and the debug
        // key: what CI's emulator smoke test runs, to show R8 kept what Qt and the engine reach by name
        create("smoke") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            ndk { debugSymbolLevel = "NONE" }
        }
    }

    sourceSets {
        getByName("smoke") {
            manifest.srcFile("src/debug/AndroidManifest.xml")
            kotlin.srcDir("src/debug/kotlin")
        }
    }

    // -PsplitApks=true: an APK for each ABI, for the GitHub pre-release. Only for the APKs: AGP cannot build an AAB
    // while splits are on with resource shrinking (issuetracker.google.com/402800800), so scripts/build-release.sh
    // builds the AAB, which holds every ABI and from which Play gives each device its own, in a Gradle run of its own
    splits {
        abi {
            isEnable = splitApks
            reset()
            include(*androidAbis.toTypedArray())
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    }

    buildFeatures { compose = true }

    androidResources {
        noCompress += "rcc"
    }

    lint {
        // Android lint: a warning fails the build too (CI runs it), and lint.xml says what is off and why
        warningsAsErrors = true
        lintConfig = rootProject.file("lint.xml")
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

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
