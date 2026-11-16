// The UI process: Compose, no Qt. It starts the engine (:engine-service) and talks to it over :protocol.
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

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

// The commit of this repository the APK is built from, for the About screen's link to its source; empty outside a
// git checkout, or without git
val sourceCommit = runCatching {
    providers.exec {
        commandLine("git", "rev-parse", "--verify", "-q", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
}.getOrDefault("").takeIf { it.matches(Regex("[0-9a-f]{40}")) } ?: ""

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
        buildConfigField("String", "SOURCE_COMMIT", "\"$sourceCommit\"")
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
        getByName("debug") {
            isMinifyEnabled = false
            // en-XA (accented, longer text) and ar-XB (mirrored, right to left), to see a layout with text it was not
            // drawn for; PseudoLocaleTest runs the app in both (docs/translations.md, "Pseudo-locales")
            isPseudoLocalesEnabled = true
        }
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
        // The launcher icon's channel-specific layers: the PTB's looks out on night (docs/launcher-icon.md)
        getByName("main").res.srcDir("src/$releaseChannel/res")
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

    buildFeatures {
        compose = true
        // BuildConfig.DEBUG keeps the self-test out of release builds; SOURCE_COMMIT is for the About screen
        buildConfig = true
    }

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

/**
 * Writes licences/android.json, assets the About screen lists beside src/main/assets/licences: each module of the
 * variant's Gradle runtime classpath with the licence its POM, or a parent POM, declares, as an SPDX identifier through
 * notices.json's pomLicences. A licence it does not map fails the build, so no dependency ships unlisted;
 * scripts/check-licences.py checks that the APK carries the file and a text for each licence (docs/licences.md).
 */
abstract class GenerateGradleLicences : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val notices: RegularFileProperty

    /** group:name:version of each module the APK bundles */
    @get:Input
    abstract val modules: ListProperty<String>

    // How POMs are resolved; set when the task is registered. No configuration cache here (gradle.properties)
    @get:Internal
    lateinit var dependencyHandler: DependencyHandler

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    private class Pom(val licences: List<String>, val url: String, val parent: String?)

    private fun parsePom(file: File): Pom {
        val project = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        fun children(of: Node, tag: String) = (0 until of.childNodes.length).map { of.childNodes.item(it) }.filter { it.nodeName == tag }
        fun text(of: Node, tag: String) = children(of, tag).firstOrNull()?.textContent?.trim().orEmpty()
        return Pom(
            licences = children(project, "licenses").flatMap { children(it, "license") }.map { text(it, "name") },
            url = text(project, "url"),
            parent = children(project, "parent").firstOrNull()?.let { "${text(it, "groupId")}:${text(it, "artifactId")}:${text(it, "version")}" },
        )
    }

    private fun resolvePoms(coordinates: Collection<String>): Map<String, Pom> {
        val query = dependencyHandler.createArtifactResolutionQuery()
        coordinates.forEach { c -> c.split(':').let { query.forModule(it[0], it[1], it[2]) } }
        val poms = query.withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java).execute().resolvedComponents.mapNotNull { component ->
            val id = component.id as ModuleComponentIdentifier
            component.getArtifacts(MavenPomArtifact::class.java).filterIsInstance<ResolvedArtifactResult>().firstOrNull()
                ?.let { "${id.group}:${id.module}:${id.version}" to parsePom(it.file) }
        }.toMap()
        val missing = coordinates - poms.keys
        if (missing.isNotEmpty()) throw GradleException("No POM resolved for ${missing.sorted()}")
        return poms
    }

    @TaskAction
    fun write() {
        @Suppress("UNCHECKED_CAST")
        val pomLicences = (groovy.json.JsonSlurper().parse(notices.get().asFile) as Map<String, Any>)["pomLicences"] as Map<String, String>
        val parents = mutableMapOf<String, Pom>()
        val unmapped = mutableListOf<String>()
        val components = resolvePoms(modules.get()).toSortedMap().map { (coordinates, pom) ->
            // Licences are often declared once, in a parent POM
            var declaring = pom
            repeat(5) {
                val parent = declaring.parent
                if (declaring.licences.isEmpty() && parent != null) declaring = parents.getOrPut(parent) { resolvePoms(listOf(parent)).getValue(parent) }
            }
            if (declaring.licences.isEmpty()) unmapped += "$coordinates: neither its POM nor a parent POM declares a licence"
            val spdx = declaring.licences.map { name -> pomLicences[name] ?: name.also { unmapped += "$coordinates: '$name'" } }
            val (group, name, version) = coordinates.split(':')
            // the shape of notices.json's components, so that the app reads both alike
            mapOf("id" to "maven:$group:$name", "name" to "$group:$name", "version" to version, "licence" to spdx.distinct().joinToString(" OR "), "url" to pom.url)
        }
        if (unmapped.isNotEmpty()) {
            throw GradleException(
                "Licences notices.json's pomLicences does not map to an SPDX identifier (docs/licences.md):\n  " + unmapped.joinToString("\n  "),
            )
        }
        val out = outputDir.get().dir("licences").asFile
        out.mkdirs()
        out.resolve("android.json").writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(mapOf("components" to components))) + "\n")
    }
}

/** The external modules a resolved runtime classpath bundles: not this build's projects, and not a platform (a BOM) */
fun bundledModules(root: ResolvedComponentResult): List<String> {
    val seen = mutableSetOf<ResolvedComponentResult>()
    val queue = ArrayDeque(listOf(root))
    while (queue.isNotEmpty()) {
        val component = queue.removeFirst()
        if (seen.add(component)) component.dependencies.filterIsInstance<ResolvedDependencyResult>().mapTo(queue) { it.selected }
    }
    fun category(attributes: AttributeContainer) = attributes.keySet().firstOrNull { it.name == Category.CATEGORY_ATTRIBUTE.name }?.let { attributes.getAttribute(it).toString() }
    return seen.mapNotNull { component ->
        val id = component.id as? ModuleComponentIdentifier
        val platform = component.variants.isNotEmpty() && component.variants.all { category(it.attributes) == Category.REGULAR_PLATFORM }
        if (id == null || platform) null else "${id.group}:${id.module}:${id.version}"
    }.sorted()
}

androidComponents {
    onVariants { variant ->
        val generate = tasks.register<GenerateGradleLicences>("generate${variant.name.replaceFirstChar { it.uppercase() }}Licences") {
            notices.set(layout.projectDirectory.file("src/main/assets/licences/notices.json"))
            modules.set(variant.runtimeConfiguration.incoming.resolutionResult.rootComponent.map(::bundledModules))
            dependencyHandler = project.dependencies
        }
        variant.sources.assets?.addGeneratedSourceDirectory(generate, GenerateGradleLicences::outputDir)
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":suggest"))
    implementation(project(":engine-service"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.androidx.navigation.compose)

    // The JVM tests of the licence data (src/test): org.json, which Android has built in, and JUnit
    testImplementation(libs.json)
    testImplementation(libs.junit)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.compose.ui.test.junit4.accessibility)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
