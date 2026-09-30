plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val includeTestUniversalApk = providers.gradleProperty("testUniversalApk").orNull == "true"
val licenseAssets = layout.buildDirectory.dir("generated/licenseAssets")
val prepareLicenseAssets = tasks.register<Sync>("prepareLicenseAssets") {
    from(rootProject.projectDir) {
        include("LICENSE", "NOTICE", "THIRD_PARTY.md", "LICENSES/**", "SOURCES.md")
    }
    into(licenseAssets.map { it.dir("licenses") })
}

android {
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("build/handwriting-evaluation/platform-fixtures"))
    namespace = "dev.zeroinput.ime"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.zeroinput.ime"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    sourceSets["main"].assets.srcDir(licenseAssets)
    sourceSets["androidTest"].assets.srcDir(rootProject.file("tools/model-quality-fixtures"))

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            // Release builds stay ABI-split by default. The local test-packaging
            // script opts in to one installable APK for supported physical devices.
            isUniversalApk = includeTestUniversalApk
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        // ONNX Runtime also ships x86; universal IME packages support only the three Rime ABIs.
        jniLibs.excludes += "lib/x86/**"
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        checkReleaseBuilds = true
        lintConfig = file("lint.xml")
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency")
    }
}

tasks.named("preBuild").configure { dependsOn(prepareLicenseAssets) }

dependencies {
    implementation(project(":engine-api"))
    implementation(project(":engine-english"))
    implementation(project(":engine-rime"))
    implementation(project(":engine-dictionary"))
    implementation(project(":ime-core"))
    implementation(project(":model-scoring"))
    implementation(project(":ime-ui"))
    implementation(project(":language-pack"))
    implementation(project(":security"))
    implementation(project(":user-data"))
    implementation(project(":ai-api"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.recyclerview)
    implementation(libs.material)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
