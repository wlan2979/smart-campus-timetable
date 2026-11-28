pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

rootProject.name = "SmartCampusTimetable"

include(":app")
include(":core")
include(":domain")
include(":data")
include(":school-adapters")
include(":feature-timetable")
include(":feature-import")
include(":feature-alarm")
include(":feature-weather")
include(":feature-holiday")
include(":feature-sync")
include(":feature-settings")
include(":feature-widgets")
