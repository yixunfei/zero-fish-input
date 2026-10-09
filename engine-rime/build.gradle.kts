import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val librimeDirectory = rootProject.file("third_party/librime")
val hasNativeRime = librimeDirectory.resolve("CMakeLists.txt").isFile
val requireRime = providers.gradleProperty("requireRime").orNull == "true"

check(!requireRime || hasNativeRime) {
    "librime sources are required. Run tools/bootstrap-rime.ps1 first."
}

val openCcAssets = layout.buildDirectory.dir("generated/openccAssets")
val syllableAssets = layout.buildDirectory.dir("generated/syllableAssets")
val publicDictionaryAssets = layout.buildDirectory.dir("generated/publicDictionaryAssets")
val resourceBundle = providers.gradleProperty("resourceBundle").orElse("full")
check(resourceBundle.get() in setOf("full", "lite")) { "resourceBundle must be full or lite" }
val packagedDictionaryAssets = layout.buildDirectory.dir("generated/packagedDictionaryAssets")
val baseRimeAssets = layout.buildDirectory.dir("generated/baseRimeAssets")
val packageBaseRimeAssets = tasks.register<Sync>("packageBaseRimeAssets") {
    from("src/main/assets")
    exclude("rime/luna_pinyin.dict.yaml", "rime/essay.txt")
    into(baseRimeAssets)
}
val glideAssets = layout.buildDirectory.dir("generated/glideAssets")
val prepareGlideAssets = tasks.register<Exec>("prepareGlideAssets") {
    inputs.file(rootProject.file("tools/dictionaries/prepare-glide.py"))
    inputs.files(publicDictionaryAssets.map { it.dir("rime/dicts") })
    outputs.dir(glideAssets)
    commandLine("python", rootProject.file("tools/dictionaries/prepare-glide.py").absolutePath)
}
val packageDictionaryAssets = tasks.register<Sync>("packageDictionaryAssets") {
    from(publicDictionaryAssets)
    into(packagedDictionaryAssets)
    if (resourceBundle.get() == "lite") exclude("**/*.gram")
}
val verifyPublicDictionaryAssets = tasks.register("verifyPublicDictionaryAssets") {
    doLast {
        val root = publicDictionaryAssets.get().dir("rime").asFile
        val manifest = root.resolve("public-dictionaries.json")
        check(manifest.isFile) { "Run python tools/dictionaries/prepare.py before building." }
        val metadata = groovy.json.JsonSlurper().parse(manifest) as Map<*, *>
        val lock = groovy.json.JsonSlurper().parse(rootProject.file("tools/dictionaries/sources.lock.json")) as Map<*, *>
        check(metadata["revision"] == lock["revision"]) { "Stale public dictionary assets" }
        val files = metadata["files"] as Map<*, *>
        check(files == lock["preparedFiles"]) { "Unreviewed public dictionary artifacts" }
        val expectedNames = (lock["tables"] as List<*>).map { "dicts/$it.dict.yaml" }.toSet() +
            setOf("zeroinput_public.dict.yaml", "wanxiang-lts-zh-hans.gram")
        check(files.keys == expectedNames) { "Incomplete public dictionary assets" }
        check(root.resolve("wanxiang-lts-zh-hans.gram").length() ==
            ((lock["model"] as Map<*, *>)["size"] as Number).toLong())
        check(files["wanxiang-lts-zh-hans.gram"] == (lock["model"] as Map<*, *>)["sha256"])
        files.forEach { (name, expected) ->
            val asset = root.resolve(name as String)
            val digest = MessageDigest.getInstance("SHA-256")
            asset.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == expected) {
                "Public dictionary checksum mismatch"
            }
        }
    }
}
val prepareSyllableAssets = tasks.register("prepareSyllableAssets") {
    val dictionary = file("src/main/assets/rime/luna_pinyin.dict.yaml")
    inputs.file(dictionary)
    outputs.dir(syllableAssets)
    doLast {
        val syllables = sortedSetOf<String>()
        var data = false
        dictionary.useLines(Charsets.UTF_8) { lines ->
            lines.forEach { line ->
                if (line == "...") data = true
                else if (data && !line.startsWith("#")) {
                    val fields = line.split('\t')
                    if (fields.size >= 2) fields[1].split(' ').filter { word ->
                        word.length in 1..8 && word.all { it in 'a'..'z' }
                    }.forEach(syllables::add)
                }
            }
        }
        check(syllables.size in 100..1024) { "Unexpected public syllable dictionary" }
        val output = syllableAssets.get().file("pinyin-syllables.txt").asFile
        output.parentFile.mkdirs()
        output.writeText(syllables.joinToString("\n"), Charsets.US_ASCII)
    }
}
val openCcDictionaryRoot = rootProject.file("third_party/rime-deps/opencc/data/dictionary")
val openCcHashes = mapOf(
    "TSCharacters.txt" to "6b5a0a799bea2bb22c001f635eaa3fc2904310f0c08addbff275477a80ecf09a",
    "TSPhrases.txt" to "b2ef895dd4953b4bb77fc8ef8d26a2a9ca6d43a760ed9a1d767672cfafa6324f",
    "STCharacters.txt" to "9207708da9f2e2a248f39c457b2fccad26ec42e7efaf47a860e6900464f4cac5",
    "STPhrases.txt" to "1411418f98dd7666a4ee673619654ed1e0518ec97953315cc10656c30c7015bb",
)
val verifyOpenCcAssets = tasks.register("verifyOpenCcAssets") {
    doLast {
        openCcHashes.forEach { (name, expected) ->
            val file = openCcDictionaryRoot.resolve(name)
            check(file.isFile) { "Pinned OpenCC dictionary is missing: $name" }
            val actual = MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            check(actual == expected) { "Pinned OpenCC dictionary checksum mismatch: $name" }
        }
    }
}
val prepareOpenCcAssets = tasks.register<Sync>("prepareOpenCcAssets") {
    onlyIf { hasNativeRime }
    from(openCcDictionaryRoot) { include(openCcHashes.keys) }
    into(openCcAssets.map { it.dir("rime/opencc") })
}

android {
    sourceSets["main"].assets.setSrcDirs(listOf(baseRimeAssets, glideAssets))
    sourceSets.getByName("main").assets.srcDir(packagedDictionaryAssets)
    namespace = "dev.zeroinput.engine.rime"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        buildConfigField("boolean", "HAS_NATIVE_RIME", hasNativeRime.toString())

        if (hasNativeRime) {
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DANDROID_STL=c++_shared",
                        "-DZEROINPUT_THIRD_PARTY_DIR=${rootProject.file("third_party").absolutePath.replace('\\', '/')}",
                    )
                    cppFlags += listOf("-std=c++17", "-fvisibility=hidden")
                    abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
                    targets += listOf("zeroinput_rime")
                }
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets["main"].assets.srcDir(openCcAssets)
    sourceSets["main"].assets.srcDir(syllableAssets)

    if (hasNativeRime) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
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
    api(project(":engine-api"))
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    testImplementation(project(":engine-dictionary"))
    testImplementation(project(":engine-english"))
}

packageDictionaryAssets.configure { dependsOn(verifyPublicDictionaryAssets) }
prepareGlideAssets.configure { dependsOn(verifyPublicDictionaryAssets) }
tasks.named("preBuild").configure { dependsOn(verifyOpenCcAssets, prepareOpenCcAssets, prepareSyllableAssets,
    packageDictionaryAssets, packageBaseRimeAssets, prepareGlideAssets) }
