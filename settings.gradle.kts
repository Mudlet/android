pluginManagement {
    repositories {
        google()
        // Google's mirror of Maven Central; repo.maven.apache.org answered 429 while three agents built at once
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's mirror of Maven Central; repo.maven.apache.org answered 429 while three agents built at once
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}
rootProject.name = "mudlet-android"
include(":protocol", ":engine-service", ":app")
