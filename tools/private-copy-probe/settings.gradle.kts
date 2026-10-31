pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository { maven { url = uri("../../build/onnx-maven") } }
            filter { includeModule("dev.zeroinput", "onnxruntime-reduced") }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "ZeroInputPrivateCopyProbe"
include(":probe", ":fixture")
