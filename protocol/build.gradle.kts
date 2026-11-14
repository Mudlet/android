// The socket protocol between the UI and the engine process (docs/protocol.md): plain Kotlin with no android.*,
// so its tests run on the JVM, against the desktop engine too (native/tests/run_jvm_client_test.sh)
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(libs.versions.jvmTarget.get())) }
}

dependencies {
    // Android has org.json built in, so the app must not ship a second copy
    compileOnly(libs.json)
    testImplementation(libs.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.reflect)
}

tasks.test {
    // EngineLinkJvmTest runs only when given a desktop engine and a fake game server's port
    systemProperty("engineBin", providers.gradleProperty("engineBin").getOrElse(""))
    systemProperty("fakePort", providers.gradleProperty("fakePort").getOrElse(""))
    // ProtocolSpecTest reads docs/protocol.md and the engine's sources
    systemProperty("repoRoot", rootDir.absolutePath)
    inputs.files(
        rootProject.file("docs/protocol.md"),
        rootProject.file("native/enginehost/SocketBridge.h"),
        rootProject.file("native/enginehost/SocketBridge.cpp"),
        rootProject.file("native/tests/protocol_client.py"),
    )
    testLogging {
        showStandardStreams = true
        // A failed assertEquals then shows what differed, in the log CI keeps
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
