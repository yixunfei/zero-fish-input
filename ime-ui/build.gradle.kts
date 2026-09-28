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

tasks.named("preBuild").configure { dependsOn(prepareExpressionSources) }

dependencies {
    api(project(":engine-api"))
    api(project(":ai-api"))
    implementation(project(":ime-core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.material)
    testImplementation(libs.junit)
}
