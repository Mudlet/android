pluginManagement {
    repositories {
        google()
        // Google's mirror of Maven Central first: Maven Central answers 429 when several builds share one IP.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's mirror of Maven Central first: Maven Central answers 429 when several builds share one IP.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "MudletProtoB"
include(":app")
