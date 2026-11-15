// Command-entry suggestions (docs/architecture.md, "Command suggestions"): plain Kotlin with no android.* and no
// dependencies, so its tests run on the JVM, with Gradle or with kotlinc alone (suggest/run-tests.sh)
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
    testImplementation(libs.junit)
}

tasks.test {
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
