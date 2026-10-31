plugins { id("com.android.application") }
layout.buildDirectory.set(file("../../../build/private-copy-probe/fixture"))
android {
    namespace = "dev.zeroinput.copyfixture"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.zeroinput.copyfixture"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "public-fixture"
    }
    sourceSets["main"].assets.srcDir("../common/assets")
    sourceSets["main"].res.srcDir("../common/res")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint { warningsAsErrors = true; abortOnError = true; disable += setOf("AndroidGradlePluginVersion", "GradleDependency") }
}
