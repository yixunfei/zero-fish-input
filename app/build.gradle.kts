import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val includeTestUniversalApk = providers.gradleProperty("testUniversalApk").orNull == "true"
val poetrySource = layout.buildDirectory.dir("generated/stegoPoetry")
val prepareStegoPoetry = tasks.register<Exec>("prepareStegoPoetry") {
    dependsOn(":engine-rime:verifyPublicDictionaryAssets")
    val script = rootProject.file("tools/dictionaries/prepare-stego-poetry.py")
    inputs.file(script)
    inputs.file(rootProject.file("engine-rime/build/generated/publicDictionaryAssets/rime/dicts/shici.dict.yaml"))
    outputs.dir(poetrySource)
    commandLine("python", script.absolutePath)
}
tasks.named("preBuild").configure { dependsOn(prepareStegoPoetry) }
val verseTestAssets = layout.buildDirectory.dir("generated/verifiedWordingAssets")
val verseTestAssetStaging = layout.buildDirectory.dir("generated/wordingAssetsStaging")
val prepareVerseTestAssets = tasks.register<Exec>("prepareVerseTestAssets") {
    dependsOn(":engine-rime:verifyPublicDictionaryAssets")
    val directory = rootProject.file("tools/themed-verse")
    inputs.files(listOf("prepare_android_verse.py", "verse.py", "p2_theme_pools.py", "p2_corpus.py")
        .map(directory::resolve))
    val frozen = directory.resolve("android-test-profile")
    inputs.files(listOf("ATTRIBUTION.md", "fixture.json", "gb18030.zdict", "window-observation-v4.bin",
        "word-context-v1.bin").map(frozen::resolve))
    inputs.file(rootProject.file("docs/themed-verse/p2-corpus-lock.json"))
    inputs.file(rootProject.file("tools/dictionaries/sources.lock.json"))
    inputs.file(rootProject.file("engine-rime/build/generated/publicDictionaryAssets/rime/dicts/shici.dict.yaml"))
    outputs.dir(verseTestAssetStaging)
    commandLine("python", "-B", directory.resolve("prepare_android_verse.py").absolutePath,
        verseTestAssetStaging.get().dir("themed-verse").asFile.absolutePath)
}
val syncVerseTestAssets = tasks.register<Sync>("syncVerseTestAssets") {
    from(prepareVerseTestAssets) {
        val files = listOf("ATTRIBUTION.md", "body.txt", "gb-map.bin", "gb18030.zdict", "host-carrier.txt",
            "opening.bin", "seven.bin", "window-observation.bin", "word-context-sources.json", "word-context-v1.bin")
        include(files.map { "themed-verse/$it" })
    }
    into(verseTestAssets)
}
tasks.matching { it.name == "preDebugBuild" }.configureEach { dependsOn(syncVerseTestAssets) }
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
    sourceSets["main"].java.srcDir(poetrySource)
    sourceSets["debug"].assets.srcDir(verseTestAssets)
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("build/handwriting-evaluation/platform-fixtures"))
    sourceSets.getByName("androidTest").assets.srcDir(handwritingAndroidTestAssets)
    namespace = "dev.zeroinput.ime"
    compileSdk = 36
    // Use the same reviewed toolchain as engine-rime, including APK symbol stripping.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "dev.zeroinput.ime"
        minSdk = 26
        targetSdk = 36
        versionCode = 15
        versionName = "0.7.8"

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
            "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
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

val verifyHanShapeIndex = tasks.register("verifyHanShapeIndex") {
    val index = layout.projectDirectory.file("src/main/assets/shape/han-shapes.tsv").asFile
    inputs.file(index)
    doLast {
        val sha256 = MessageDigest.getInstance("SHA-256").digest(index.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(sha256 == "e51647e0d948d3b2ed93f4a0d22ac106a82e9e82aacd63a795d6ab7414012dc1") {
            "Bundled Han shape index checksum mismatch"
        }
    }
}
tasks.named("preBuild").configure { dependsOn(prepareLicenseAssets, verifyHanShapeIndex) }
tasks.matching {
    (it.name.startsWith("merge") && it.name.endsWith("AndroidTestAssets")) ||
        (it.name.contains("AndroidTest") && it.name.contains("Lint", ignoreCase = true))
}.configureEach {
    dependsOn(prepareHandwritingAndroidTestAssets)
}

dependencies {
    implementation(libs.okhttp)
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
    androidTestImplementation(testFixtures(project(":engine-api")))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
