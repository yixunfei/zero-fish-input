plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val resourceCatalogAssets = layout.buildDirectory.dir("generated/resourceCatalogAssets")
val prepareResourceCatalog = tasks.register<Sync>("prepareResourceCatalog") {
    from(rootProject.file("tools/resources/catalog.json"))
    into(resourceCatalogAssets.map { it.dir("public-resources") })
}

android {
    sourceSets["main"].assets.srcDir(resourceCatalogAssets)
    namespace = "dev.zeroinput.languagepack"
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

tasks.named("preBuild").configure { dependsOn(prepareResourceCatalog) }

dependencies {
    implementation(project(":engine-dictionary"))
    api(project(":engine-api"))
    implementation(project(":security"))
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
