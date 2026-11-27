pluginManagement {
    repositories {
        google()
        // Use an alternate Maven Central mirror first to avoid transient 429
        // responses from repo.maven.apache.org during CI dependency resolution.
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Keep Maven Central as a fallback if the mirror is unavailable.
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        mavenCentral()
    }
}

rootProject.name = "SleepMate"
include(":app")
