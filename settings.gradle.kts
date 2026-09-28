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

rootProject.name = "zero fish input"

include(
    ":app",
    ":engine-api",
    ":engine-english",
    ":engine-rime",
    ":engine-dictionary",
    ":ime-core",
    ":model-scoring",
    ":ime-ui",
    ":language-pack",
    ":security",
    ":user-data",
    ":ai-api",
)
