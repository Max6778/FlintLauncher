pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "Zalith Launcher"
include(":jre_lwjgl3glfw")
include(":ZalithLauncher")
include(":jre_lwjgl3glfw")
include(":jre_lwjgl3glfw_341")
include(":ZalithLauncher")
