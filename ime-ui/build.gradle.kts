import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val expressionSources = layout.buildDirectory.dir("generated/expressionSources")
val prepareExpressionSources = tasks.register<Sync>("prepareExpressionSources") {
    val upstream = file("src/main/assets/expressions/rime-kaomoji-source.txt")
    inputs.file(upstream)
    doFirst {
        val digest = MessageDigest.getInstance("SHA-256").digest(upstream.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(digest == "0772e42f7410b4ed452b1103bcf2d9360ec952318383631d0702bc0418931d62") {
            "Pinned kaomoji source checksum mismatch"
        }
    }
    from("src/main/kotlin/dev/zeroinput/ime/ui/KaomojiCatalog.kt")
    into(expressionSources.map { it.dir("expressions") })
}

val verifyEmojiAssets = tasks.register("verifyEmojiAssets") {
    val assets = file("src/main/assets/emoji/18.0")
    val official = file("src/test/resources/emoji/18.0/emoji-test.txt")
    val stamp = layout.buildDirectory.file("verified/emoji-18.0.sha256")
    inputs.dir(assets)
    inputs.file(official)
    outputs.file(stamp)
    doLast {
        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        check(sha256(official.readBytes()) == "8f3735cda1f92a779d78af67cf86066bb1f07143dc22f2ac29394d9bc57ab21a") {
            "Pinned Unicode 18.0 source checksum mismatch"
        }
        val lines = assets.resolve("checksums.sha256").readLines()
        check(lines.size == 3973) { "Incomplete offline emoji artwork" }
        val paths = lines.map { line ->
            val fields = line.split("  ", limit = 2)
            check(fields.size == 2 && fields[0].matches(Regex("[0-9a-f]{64}"))) { "Invalid emoji checksum record" }
            val path = fields[1]
            check(path == "catalog.tsv" || path.matches(Regex("images/[0-9a-f_]+\\.webp"))) { "Invalid emoji asset path" }
            check(sha256(assets.resolve(path).readBytes()) == fields[0]) { "Pinned emoji asset checksum mismatch" }
            path
        }.toSet()
        val actual = assets.resolve("images").listFiles().orEmpty().map { "images/${it.name}" }.toSet() + "catalog.tsv"
        check(paths == actual && paths.size == lines.size) { "Missing, duplicate or unexpected emoji artwork" }
        stamp.get().asFile.apply {
            parentFile.mkdirs()
            writeText(sha256(assets.resolve("checksums.sha256").readBytes()))
        }
    }
}

android {
    namespace = "dev.zeroinput.ime.ui"
    compileSdk = 36
    sourceSets["main"].assets.srcDir(expressionSources)

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        viewBinding = true
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

tasks.named("preBuild").configure { dependsOn(prepareExpressionSources, verifyEmojiAssets) }

dependencies {
    api(project(":engine-api"))
    api(project(":ai-api"))
    implementation(project(":ime-core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.material)
    testImplementation(libs.junit)
}
