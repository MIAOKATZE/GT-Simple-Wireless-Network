
plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

// Ensure UniMixins is available for runClient/runServer tasks even if the mod itself doesn't use mixins.
// This fixes ClassNotFoundException for MixinTweaker in CI environments.
dependencies {
    runtimeOnlyNonPublishable("com.github.GTNewHorizons:UniMixins:0.4.19")
}
