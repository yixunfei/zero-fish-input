plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":engine-api"))
    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":engine-api")))
}

tasks.test {
    useJUnit()
}

