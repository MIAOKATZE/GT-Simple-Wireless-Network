package com.miaokatze.gtswn;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

public class Config {

    public static String greeting = "Hello World";
    // Preferred base index for allocating MetaTileEntity indices. Default to 25000 (common GT machine base).
    public static int preferredMetaBase = 60100;
    // Offset to add to meta ID base (mirrors NH-Utilities metaIdOffset)
    public static int metaIdOffset = 0;

    public static void synchronizeConfiguration(File configFile) {
        Configuration configuration = new Configuration(configFile);

        greeting = configuration.getString("greeting", Configuration.CATEGORY_GENERAL, greeting, "How shall I greet?");
        preferredMetaBase = configuration.getInt(
            "preferredMetaBase",
            Configuration.CATEGORY_GENERAL,
            preferredMetaBase,
            1,
            Integer.MAX_VALUE,
            "Preferred base index for GregTech METATILEENTITIES allocation (e.g. 25000)");

        metaIdOffset = configuration.getInt(
            "metaIdOffset",
            Configuration.CATEGORY_GENERAL,
            metaIdOffset,
            -5000,
            5000,
            "Offset to apply to meta id base (used to reserve id ranges similar to NH-Utilities)");

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }
}
