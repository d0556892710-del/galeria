pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Tesseract for Android (Hebrew Live Text).
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "Galeria"
include(":app")
