pluginManagement {
    repositories {
        // Restrict the Google repo to the Android/AndroidX/Google artefacts only, so
        // plugin resolution does not pay for a full Google index lookup.
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
    // No dependency repositories may be declared in project build scripts; all of them
    // are centralised here so builds are reproducible across machines and CI runners.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "earpiece-switch"

include(":app")
