package com.miaokatze.gtswn.client;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

import cpw.mods.fml.common.Loader;

/** Client preferences only; server settings live in DeviceTerminalClientCache. */
public final class DeviceTerminalVisualConfig {

    public static boolean enabled = true;
    private static boolean explicitHeatChoice;
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
    public static int labelBudget = 24;
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
        explicitHeatChoice = configuration.getBoolean(
            "explicitHeatChoice",
            category,
            false,
            "True after the player explicitly changes the heat-view switch.");
        enabled = configuration
            .getBoolean("enabled", category, true, "Show filled energy heat layers in visualization mode.");
        // Older releases wrote the default false without recording whether it was chosen.
        // Migrate that default once; subsequent explicit Off selections are preserved.
        if (!configuration
            .getBoolean("heatDefaultsMigrated", category, false, "Heat-view default migration completed.")) {
            if (!explicitHeatChoice) enabled = true;
            configuration.get(category, "enabled", true)
                .set(enabled);
            configuration.get(category, "heatDefaultsMigrated", false)
                .set(true);
        }
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
        labelBudget = configuration
            .getInt("labelBudget", category, 24, 8, 64, "Nearest labels per frame; aimed label has priority.");
        opacity = configuration.getFloat("opacity", category, 0.40F, 0.05F, 0.85F, "Overlay opacity.");
        averageWeight = configuration
            .getFloat("averageWeight", category, 0.65F, 0F, 1F, "Average versus instant weight.");
        smoothing = configuration.getFloat("smoothing", category, 0.50F, 0F, 0.95F, "Temporal smoothing retention.");
        if (configuration.hasChanged()) configuration.save();
    }

    public static void save() {
        load();
        String category = "visual";
        configuration.get(category, "enabled", true)
            .set(enabled);
        configuration.get(category, "explicitHeatChoice", false)
            .set(explicitHeatChoice);
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
        configuration.get(category, "labelBudget", 24)
            .set(labelBudget);
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

    public static void chooseHeatEnabled(boolean value) {
        load();
        enabled = value;
        explicitHeatChoice = true;
    }

    public static int appearanceKey() {
        int bits = (showConsumers ? 1 : 0) | (showGenerators ? 2 : 0)
            | (showSteamConsumers ? 4 : 0)
            | (showSteamProducers ? 8 : 0)
            | (showLabels ? 16 : 0)
            | (showLabelName ? 32 : 0)
            | (showLabelPower ? 64 : 0)
            | (showLabelState ? 128 : 0)
            | (showLabelRecipe ? 256 : 0);
        return 31 * bits + (int) Math.round(averageWeight * 100D);
    }

    public static boolean visible(boolean steam, boolean producer) {
        return steam ? producer ? showSteamProducers : showSteamConsumers : producer ? showGenerators : showConsumers;
    }
}
