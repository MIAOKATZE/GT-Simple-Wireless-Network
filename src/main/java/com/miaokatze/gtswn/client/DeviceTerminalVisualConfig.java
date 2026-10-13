package com.miaokatze.gtswn.client;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

import cpw.mods.fml.common.Loader;

/** Client preferences only; server settings live in DeviceTerminalClientCache. */
public final class DeviceTerminalVisualConfig {

    public static boolean enabled;
    public static boolean showConsumers = true;
    public static boolean showGenerators = true;
    public static boolean showSteamConsumers = true;
    public static boolean showSteamProducers = true;
    public static boolean depthTest = true;
    public static boolean showLabels = true;
    public static boolean showLabelName = true;
    public static boolean showLabelState;
    public static boolean showLabelPower = true;
    public static boolean showLabelRecipe;
    public static boolean labelDepthTest;
    public static int range = 64;
    public static int maxDevices = 256;
    public static double opacity = 0.40D;
    public static double averageWeight = 0.65D;
    public static double smoothing = 0.50D;
    private static Configuration configuration;

    private DeviceTerminalVisualConfig() {}

    public static void load() {
        if (configuration != null) return;
        configuration = new Configuration(
            new File(
                Loader.instance()
                    .getConfigDir(),
                "gtswn/device_visual_client.cfg"));
        configuration.load();
        String category = "visual";
        enabled = configuration
            .getBoolean("enabled", category, false, "Show energy heat view while holding a terminal.");
        showConsumers = configuration.getBoolean("showConsumers", category, true, "Electric consumers.");
        showGenerators = configuration.getBoolean("showGenerators", category, true, "Electric generators.");
        showSteamConsumers = configuration.getBoolean("showSteamConsumers", category, true, "Steam consumers.");
        showSteamProducers = configuration.getBoolean("showSteamProducers", category, true, "Steam producers.");
        depthTest = configuration.getBoolean("depthTest", category, true, "Respect world depth occlusion.");
        showLabels = configuration.getBoolean("showLabels", category, true, "Show travel-anchor label panels.");
        showLabelName = configuration.getBoolean("showLabelName", category, true, "Machine names on anchors.");
        showLabelState = configuration.getBoolean("showLabelState", category, false, "Machine states on anchors.");
        showLabelPower = configuration
            .getBoolean("showLabelPower", category, true, "Instant energy input/output on anchors.");
        showLabelRecipe = configuration
            .getBoolean("showLabelRecipe", category, false, "Cached recipe preview on anchors.");
        labelDepthTest = configuration
            .getBoolean("labelDepthTest", category, false, "Depth occlusion for travel markers.");
        range = configuration.getInt("range", category, 64, 8, 128, "Maximum view distance in blocks.");
        maxDevices = configuration.getInt("maxDevices", category, 256, 32, 512, "Maximum rendered devices.");
        opacity = configuration.getFloat("opacity", category, 0.40F, 0.05F, 0.85F, "Overlay opacity.");
        averageWeight = configuration
            .getFloat("averageWeight", category, 0.65F, 0F, 1F, "Average versus instant weight.");
        smoothing = configuration.getFloat("smoothing", category, 0.50F, 0F, 0.95F, "Temporal smoothing retention.");
        if (configuration.hasChanged()) configuration.save();
    }

    public static void save() {
        load();
        String category = "visual";
        configuration.get(category, "enabled", false)
            .set(enabled);
        configuration.get(category, "showConsumers", true)
            .set(showConsumers);
        configuration.get(category, "showGenerators", true)
            .set(showGenerators);
        configuration.get(category, "showSteamConsumers", true)
            .set(showSteamConsumers);
        configuration.get(category, "showSteamProducers", true)
            .set(showSteamProducers);
        configuration.get(category, "depthTest", true)
            .set(depthTest);
        configuration.get(category, "showLabels", true)
            .set(showLabels);
        configuration.get(category, "showLabelName", true)
            .set(showLabelName);
        configuration.get(category, "showLabelState", false)
            .set(showLabelState);
        configuration.get(category, "showLabelPower", true)
            .set(showLabelPower);
        configuration.get(category, "showLabelRecipe", false)
            .set(showLabelRecipe);
        configuration.get(category, "labelDepthTest", false)
            .set(labelDepthTest);
        configuration.get(category, "range", 64)
            .set(range);
        configuration.get(category, "maxDevices", 256)
            .set(maxDevices);
        configuration.get(category, "opacity", 0.40D)
            .set(opacity);
        configuration.get(category, "averageWeight", 0.65D)
            .set(averageWeight);
        configuration.get(category, "smoothing", 0.50D)
            .set(smoothing);
        configuration.save();
    }

    public static boolean visible(boolean steam, boolean producer) {
        return steam ? producer ? showSteamProducers : showSteamConsumers : producer ? showGenerators : showConsumers;
    }
}
