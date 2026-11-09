pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // libsu публикуется через JitPack
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "vowifi-watch"
include(":app")
