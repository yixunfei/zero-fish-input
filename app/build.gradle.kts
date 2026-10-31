import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val includeTestUniversalApk = providers.gradleProperty("testUniversalApk").orNull == "true"
val licenseAssets = layout.buildDirectory.dir("generated/licenseAssets")
val handwritingAndroidTestAssets = layout.buildDirectory.dir("generated/handwritingAndroidTestAssets")
val handwritingAssetManifest = rootProject.file("app/src/androidTest/assets/handwriting-test-assets.properties")
val handwritingManifestProperties = Properties().apply {
    handwritingAssetManifest.inputStream().use { load(it) }
}
val handwritingAssets = handwritingManifestProperties.stringPropertyNames().sorted().map { relative ->
    val metadata = handwritingManifestProperties.getProperty(relative).split(',', limit = 2)
    check(metadata.size == 2) { "Invalid handwriting asset manifest entry: $relative" }
    val source = when (relative) {
        "handwriting/model.onnx" -> rootProject.file("build/handwriting-model/inference.onnx")
        "handwriting/characters.txt" -> rootProject.file("build/handwriting-model/characters.txt")
        "handwriting/stroke-simplified.zsh", "handwriting/stroke-traditional.zsh" -> rootProject.file("build/handwriting-stroke-model/${relative.substringAfter('/')}")
        else -> error("Unsupported handwriting test asset: $relative")
    }
    Triple(source, relative, metadata[0].toLong() to metadata[1])
}
val prepareLicenseAssets = tasks.register<Sync>("prepareLicenseAssets") {
    from(rootProject.projectDir) {
        include("LICENSE", "NOTICE", "THIRD_PARTY.md", "LICENSES/**", "SOURCES.md")
    }
    into(licenseAssets.map { it.dir("licenses") })
}

val prepareHandwritingAndroidTestAssets = tasks.register("prepareHandwritingAndroidTestAssets") {
    val sources = handwritingAssets
    inputs.files(sources.map { it.first })
    outputs.dir(handwritingAndroidTestAssets)
    doLast {
        val root = handwritingAndroidTestAssets.get().asFile
        root.deleteRecursively()
        sources.forEach { (source, relative, expected) ->
            check(source.isFile && source.length() == expected.first) {
                "Missing pinned handwriting test asset: ${source.name}"
            }
            val target = root.resolve(relative)
            target.parentFile.mkdirs()
            source.copyTo(target, overwrite = true)
            val digest = MessageDigest.getInstance("SHA-256")
            target.inputStream().use { input ->
                val buffer = ByteArray(65_536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == expected.second) {
                "Pinned handwriting test asset checksum mismatch: ${source.name}"
            }
        }
    }
}

android {
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("build/handwriting-evaluation/platform-fixtures"))
    sourceSets.getByName("androidTest").assets.srcDir(handwritingAndroidTestAssets)
    namespace = "dev.zeroinput.ime"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.zeroinput.ime"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "0.7.1"

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
    androidResources { noCompress += "xz" }
    testOptions.unitTests.isIncludeAndroidResources = true

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
        jniLibs.useLegacyPackaging = true
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
tasks.matching {
    (it.name.startsWith("merge") && it.name.endsWith("AndroidTestAssets")) ||
        (it.name.contains("AndroidTest") && it.name.contains("Lint", ignoreCase = true))
}.configureEach {
    dependsOn(prepareHandwritingAndroidTestAssets)
}

dependencies {
    implementation(libs.jsoup)
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
    implementation(libs.androidx.exifinterface)
    implementation(libs.material)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
