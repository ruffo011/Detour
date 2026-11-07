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

rootProject.name = "Detour"

// Pure-Kotlin logic (no Android), tested on the JVM.
include(":core")      // categories, classify(), budget/day logic, ranking, backup rules
include(":dnscore")   // DNS packet parsing/building and domain matching

// Android modules.
include(":ipc")       // shared signature permission + guard<->filter contract
include(":guard")     // main app: all personal data, NO network permission
include(":filter")    // browser-only DNS filter VPN: knows only the blocklist
