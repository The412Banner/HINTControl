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
    }
}

rootProject.name = "HINTControl"
include(":gateway")
// -PgatewayOnly=true builds only the plain Kotlin module (no Android SDK needed).
if (providers.gradleProperty("gatewayOnly").orNull != "true") {
    include(":app")
}
