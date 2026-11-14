plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// ktlint over every module's Kotlin and the build scripts, with the rules in .editorconfig: ./gradlew ktlintCheck,
// and ./gradlew ktlintFormat to fix what it can
val ktlint: Configuration by configurations.creating

dependencies {
    ktlint(libs.ktlint.cli) {
        attributes { attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL)) }
    }
}

val ktlintFiles = listOf("**/src/**/*.kt", "**/*.gradle.kts", "!**/build/**")

tasks.register<JavaExec>("ktlintCheck") {
    group = "verification"
    description = "Checks the Kotlin sources and build scripts with ktlint"
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args(ktlintFiles)
}

tasks.register<JavaExec>("ktlintFormat") {
    group = "formatting"
    description = "Fixes what ktlint can in the Kotlin sources and build scripts"
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    // ktlint's own advice for JDK 16 and later
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
    args(listOf("-F") + ktlintFiles)
}
