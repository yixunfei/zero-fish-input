import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val modelAssets = layout.buildDirectory.dir("generated/modelAssets")
val resourceBundle = providers.gradleProperty("resourceBundle").orElse("full")
check(resourceBundle.get() in setOf("full", "lite"))
val modelSource = rootProject.file("build/model-evaluation/android-assets/mini-int8/model.onnx")
val vocabSource = rootProject.file("build/model-evaluation/mini/vocab.txt")
val handwritingModel = rootProject.file("build/handwriting-model/inference.onnx")
val handwritingCharacters = rootProject.file("build/handwriting-model/characters.txt")
val strokeModels = listOf(
    Triple(rootProject.file("build/handwriting-stroke-model/stroke-simplified.zsh"), 7_016_330L,
        "fdd47959e8cb95add75fc1e5bd10ff62e88b5d09fc6c6305d284d8f3df57667f"),
    Triple(rootProject.file("build/handwriting-stroke-model/stroke-traditional.zsh"), 39_052_454L,
        "7eaa62001987b03fa0ea24824b1a1203599064db905604026da8bc7e4e3b0288"),
)
val prepareModelAssets = tasks.register("prepareModelAssets") {
    inputs.files(modelSource, vocabSource, handwritingModel, handwritingCharacters)
    inputs.files(strokeModels.map { it.first })
    outputs.dir(modelAssets)
    inputs.property("resourceBundle", resourceBundle)
    doLast {
        val files = listOf(
            Triple(modelSource, 14_898_764L, "5fb4dbe2c618e8757258253e10481ea9181e8a7b9a8efea03ee70c3a5ca19446"),
            Triple(vocabSource, 109_540L, "45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c"),
            Triple(handwritingModel, 16_534_782L, "da72dc72ca4dc220df0dfde68c1dedc31c58d3e76a25871122e5056227d50092"),
            Triple(handwritingCharacters, 74_012L, "d1979e9f794c464c0d2e0b70a7fe14dd978e9dc644c0e71f14158cdf8342af1b"),
        ) + strokeModels
        files.forEach { (file, size, hash) ->
            check(file.isFile && file.length() == size) { "Prepare pinned model assets: see docs/model-integration.md, tools/prepare-handwriting-model.py and tools/prepare-handwriting-stroke-model.py" }
            val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            check(actual == hash) { "Pinned model asset checksum mismatch: ${file.name}" }
        }
        val target = modelAssets.get().dir("mini-int8").asFile.apply { mkdirs() }
        files.take(2).forEach { (source, _, _) -> source.copyTo(target.resolve(source.name), overwrite = true) }
        // These are obsolete generated duplicates, not source assets.
        target.resolve("inference.onnx").delete()
        target.resolve("characters.txt").delete()
        val handwritingTarget = modelAssets.get().dir("handwriting").asFile
        if (resourceBundle.get() == "full") {
            handwritingTarget.mkdirs()
            handwritingModel.copyTo(handwritingTarget.resolve("model.onnx"), overwrite = true)
            handwritingCharacters.copyTo(handwritingTarget.resolve("characters.txt"), overwrite = true)
            strokeModels.forEach { (source, _, _) -> source.copyTo(handwritingTarget.resolve(source.name), overwrite = true) }
        } else delete(handwritingTarget)
    }
}

val verifyRuntimeArtifact = tasks.register("verifyRuntimeArtifact") {
    doLast {
        val artifact = configurations["debugRuntimeClasspath"].resolvedConfiguration.resolvedArtifacts.single {
            it.moduleVersion.id.group == "com.microsoft.onnxruntime" && it.name == "onnxruntime-android"
        }.file
        val hash = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes()).joinToString("") { "%02x".format(it) }
        check(hash == "09c0780ae8d734ef2774bdf498b624729a855e6f9a8e488a0e7398a4e7396032") { "Runtime checksum mismatch" }
    }
}

android {
    namespace = "dev.zeroinput.model"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    sourceSets["main"].assets.srcDir(modelAssets)
    androidResources { noCompress += "onnx" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { warningsAsErrors = true; abortOnError = true; disable += "GradleDependency" }
}

tasks.named("preBuild").configure { dependsOn(prepareModelAssets, verifyRuntimeArtifact) }
dependencies {
    implementation(project(":engine-api"))
    implementation(libs.onnxruntime.android)
    testImplementation(libs.junit)
}
