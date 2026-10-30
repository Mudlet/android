pluginManagement {
    repositories {
        google()
        // Google's mirror of Maven Central: repo.maven.apache.org answered 429 under shared load
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's mirror of Maven Central: repo.maven.apache.org answered 429 under shared load
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}
rootProject.name = "proto-a-inprocess-service"
include(":app")
