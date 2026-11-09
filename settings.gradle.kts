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

rootProject.name = "kiosk-lens"

// 순수 Kotlin (Android SDK 참조 불가)
include(":core-model", ":domain")
// 어댑터 (각 파트 소유)
include(":vision", ":voice", ":agent", ":overlay")
// 조립점
include(":app")
