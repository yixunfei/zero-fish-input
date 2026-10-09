plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.snakeyaml)
    implementation(project(":engine-api"))
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    providers.gradleProperty("dictionarySample").orNull?.let { systemProperty("dictionary.sample", it) }
    providers.gradleProperty("sogouSample").orNull?.let { systemProperty("dictionary.sogouSample", it) }
}

tasks.register<JavaExec>("evaluateWordAssociations") {
    group = "verification"
    description = "Evaluates the bundled predictor on frozen public synthetic fixtures."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.zeroinput.engine.dictionary.AssociationQualityEvaluation")
    args(layout.buildDirectory.file("reports/association-quality.tsv").get().asFile.absolutePath)
}

tasks.register<JavaExec>("evaluateCandidateQuality") {
    group = "verification"
    description = "Evaluates the bundled reference dictionary on frozen public synthetic fixtures."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.zeroinput.engine.dictionary.CandidateQualityEvaluation")
    args(layout.buildDirectory.file("reports/candidate-quality.tsv").get().asFile.absolutePath)
}
