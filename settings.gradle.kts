// Maven Central drosselt die GitHub-Runner zeitweise (HTTP 429). Der offizielle
// Google-Spiegel von Maven Central steht deshalb jeweils davor.
val mavenCentralMirror = "https://maven-central.storage-download.googleapis.com/maven2/"

pluginManagement {
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven(mavenCentralMirror)
        mavenCentral()
    }
}

rootProject.name = "SleepTvOff"
include(":mobile", ":wear")
