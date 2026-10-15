
plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

// JUnit entry points use *Test; avoid extracting large mod JARs to inspect fixture superclasses.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    isScanForTestClasses = false
    include("**/*Test.class")
}
