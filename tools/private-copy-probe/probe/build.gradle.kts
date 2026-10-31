import java.security.MessageDigest

plugins { id("com.android.application") }
layout.buildDirectory.set(file("../../../build/private-copy-probe/probe"))
android {
    namespace = "dev.zeroinput.copyprobe"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.zeroinput.copyprobe"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "research"
        testInstrumentationRunner = "dev.zeroinput.copyprobe.ProbeInstrumentation"
        ndk { abiFilters += (providers.gradleProperty("probeAbi").orNull ?: "x86_64") }
    }
    flavorDimensions += "recognition"
    productFlavors {
        create("plain") { dimension = "recognition" }
        create("ocr") { dimension = "recognition"; applicationIdSuffix = ".ocr" }
    }
    sourceSets["main"].assets.srcDir("../common/assets")
    sourceSets["main"].res.srcDir("../common/res")
    sourceSets["ocr"].assets.srcDir(layout.buildDirectory.dir("generated/ocr-assets"))
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    androidResources { noCompress += "onnx" }
    lint {
        warningsAsErrors = true
        abortOnError = true
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency")
    }
}
dependencies {
    "ocrImplementation"("dev.zeroinput:onnxruntime-reduced:1.26.0-zero1")
    testImplementation("junit:junit:4.13.2")
}

val stageOcr = tasks.register("stageOcr") {
    doLast {
        val assets = file("../../../build/ocr-evaluation/android-assets/ocr")
        val hashes = mapOf("rec.onnx" to "da72dc72ca4dc220df0dfde68c1dedc31c58d3e76a25871122e5056227d50092",
            "characters.txt" to "d1979e9f794c464c0d2e0b70a7fe14dd978e9dc644c0e71f14158cdf8342af1b")
        hashes.forEach { (name, hash) ->
            val source = File(assets, name)
            check(source.isFile) { "Stage pinned OCR assets with tools/prepare-ocr-evaluation.py first" }
            val actual = MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") { "%02x".format(it) }
            check(hash == actual) { "OCR asset checksum mismatch" }
            source.copyTo(layout.buildDirectory.file("generated/ocr-assets/ocr/$name").get().asFile.apply { parentFile.mkdirs() }, true)
        }
        listOf("LICENSES/Apache-2.0.txt", "LICENSES/onnxruntime-MIT.txt", "LICENSES/onnxruntime-ThirdPartyNotices.txt", "NOTICE").forEach {
            file("../../../$it").copyTo(layout.buildDirectory.file("generated/ocr-assets/licenses/${File(it).name}").get().asFile.apply { parentFile.mkdirs() }, true)
        }
    }
}
val verifyOcrRuntime = tasks.register("verifyOcrRuntime") {
    doLast {
        val artifact = configurations["ocrDebugRuntimeClasspath"].resolvedConfiguration.resolvedArtifacts.single {
            it.name == "onnxruntime-reduced"
        }.file
        check(MessageDigest.getInstance("SHA-256").digest(artifact.readBytes()).joinToString("") { "%02x".format(it) } ==
            "91a413c817fe07db18fa8e9b013b53170ffefcd7c7366edfff40305f67bd6fbe")
    }
}
tasks.matching { it.name == "preOcrDebugBuild" || it.name == "preOcrReleaseBuild" }.configureEach {
    dependsOn(stageOcr, verifyOcrRuntime)
}
