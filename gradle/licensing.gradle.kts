import org.gradle.api.tasks.bundling.Jar

val legalFiles = listOf(
    "LICENSE", "AGPL-ADDITIONAL-TERMS.md", "AGPL-ADDITIONAL-TERMS.zh-CN.md",
    "ASSET-LICENSE.md", "ASSET-LICENSE.zh-CN.md", "NOTICE.md", "NOTICE.zh-CN.md"
)

tasks.withType<Jar>().configureEach {
    // The complete testlab replacement already inherits the ordinary jar CopySpec.
    if (name != "testlabJar") {
        from(rootProject.projectDir) {
            include(legalFiles)
            include("LICENSES/**")
        }
    }
}

val ordinaryJar = tasks.named<Jar>("jar")
val verifyLicenseBundle = tasks.register("verifyLicenseBundle") {
    group = "verification"
    description = "Verify exact project license bytes and asset designations in the ordinary JAR."
    dependsOn(ordinaryJar)
    inputs.files(legalFiles.map { rootProject.file(it) })
    inputs.dir(rootProject.file("LICENSES"))
    doLast {
        val expected = legalFiles.map { rootProject.file(it) } +
            rootProject.fileTree("LICENSES").files.sortedBy { it.path }
        java.util.zip.ZipFile(ordinaryJar.get().archiveFile.get().asFile).use { archive ->
            expected.forEach { source ->
                check(source.isFile) { "Missing license source: $source" }
                val name = source.relativeTo(rootProject.projectDir).invariantSeparatorsPath
                val entry = archive.getEntry(name) ?: error("Missing packaged license: $name")
                val actual = archive.getInputStream(entry).use { it.readBytes() }
                check(actual.contentEquals(source.readBytes())) { "Stale packaged license: $name" }
                check(archive.entries().asSequence().count { it.name == name } == 1) {
                    "Duplicate packaged license: $name"
                }
            }
        }
    }
}

tasks.named("check") { dependsOn(verifyLicenseBundle) }
