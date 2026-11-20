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
        maven("https://api.xposed.info/")
        google()
        mavenCentral()
    }
}
rootProject.name = "ADBLive"
include(":app")
