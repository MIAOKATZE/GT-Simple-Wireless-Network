
plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

// JUnit entry points use *Test; avoid extracting large mod JARs to inspect fixture superclasses.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    isScanForTestClasses = false
    include("**/*Test.class")
    providers.gradleProperty("testlabCatalogOutput").orNull?.let { systemProperty("gtswn.testlab.catalog", it) }
}

// A complete replacement variant, never an addon to the ordinary GTSWN jar.
val testlabSourceSet = sourceSets.create("testlab") {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += output + compileClasspath + sourceSets.main.get().runtimeClasspath
}
configurations.named(testlabSourceSet.implementationConfigurationName) { extendsFrom(configurations.implementation.get()) }
configurations.named(testlabSourceSet.compileOnlyConfigurationName) { extendsFrom(configurations.compileOnly.get()) }
configurations.named(testlabSourceSet.annotationProcessorConfigurationName) { extendsFrom(configurations.annotationProcessor.get()) }
sourceSets.test {
    compileClasspath += testlabSourceSet.output
    runtimeClasspath += testlabSourceSet.output
}
val testlabJar = tasks.register<Jar>("testlabJar") {
    group = "build"
    description = "Complete opt-in testlab replacement; do not install alongside the ordinary jar."
    archiveClassifier.set("testlab-dev")
    // Reuse every production CopySpec, including convention-added injected tags and mixin source sets.
    with(tasks.jar.get())
    from(testlabSourceSet.output)
    manifest.from(tasks.jar.get().manifest)
    manifest.attributes("Miaokatze-Testlab-Variant" to "replacement")
}
// RFG 2.x creates and fully configures this task through its reobf<JarTaskName> rule.
tasks.named<com.gtnewhorizons.retrofuturagradle.mcp.ReobfuscatedJar>("reobfTestlabJar") {
    archiveClassifier.set("testlab")
    referenceClasspath.from(testlabSourceSet.compileClasspath)
    val productionReobf = tasks.named<com.gtnewhorizons.retrofuturagradle.mcp.ReobfuscatedJar>("reobfJar")
    extraSrgFiles.from(productionReobf.map { it.extraSrgFiles })
    extraSrgEntries.addAll(productionReobf.flatMap { it.extraSrgEntries })
}

// Stamp both replacement inputs so the final reobfuscated artifact can report its source identity.
val testlabSourceCommit = providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.map { it.trim() }
val testlabSourceDirty = providers.exec { commandLine("git", "status", "--porcelain", "--untracked-files=normal") }.standardOutput.asText.map { it.isNotBlank().toString() }
tasks.withType<Jar>().configureEach {
    manifest.attributes("Miaokatze-Source-Commit" to testlabSourceCommit.get(), "Miaokatze-Source-Dirty" to testlabSourceDirty.get())
}
