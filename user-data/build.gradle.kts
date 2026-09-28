plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.zeroinput.userdata"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
    }
}

dependencies {
    api(project(":ai-api"))
    api(project(":engine-api"))
    api(project(":security"))
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
