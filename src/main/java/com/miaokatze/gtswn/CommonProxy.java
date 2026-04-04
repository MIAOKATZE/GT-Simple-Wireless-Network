package com.miaokatze.gtswn;

// ...existing code...
import net.minecraft.util.StatCollector;

import com.miaokatze.gtswn.machine.MTETestMachine;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;

public class CommonProxy {

    // preInit "Run before anything else. Read your config, create blocks, items, etc, and register them with the
    // GameRegistry." (Remove if not needed)
    public void preInit(FMLPreInitializationEvent event) {
        Config.synchronizeConfiguration(event.getSuggestedConfigurationFile());

        GTSimpleWirelessNetwork.LOG.info(Config.greeting);
        GTSimpleWirelessNetwork.LOG.info("I am GTSimpleWirelessNetwork at version " + Tags.VERSION);
        // Register the MTETestMachine registration runnable early so GregTech can pick it up
        // during its load-phase. Use sBeforeGTLoad as the single reliable registration point.
        Runnable registerRunnable = () -> {
            GTSimpleWirelessNetwork.LOG.info("registration runnable started");
            try {
                registerFixedMTETestMachines();
            } catch (Throwable t) {
                GTSimpleWirelessNetwork.LOG.error("注册 MTETestMachine 时出错 (preInit)", t);
            }
            GTSimpleWirelessNetwork.LOG.info("registration runnable finished");
        };
        // Single registration point: before GregTech load-phase. This avoids double-registration
        // caused by registering the same runnable multiple times in different lifecycle hooks.
        boolean runnableAdded = false;
        try {
            if (GregTechAPI.sBeforeGTLoad == null) {
                GTSimpleWirelessNetwork.LOG.warn("GregTechAPI.sBeforeGTLoad == null; cannot add registration runnable");
            } else {
                int before = GregTechAPI.sAfterGTLoad.size();
                GregTechAPI.sAfterGTLoad.add(registerRunnable);
                int after = GregTechAPI.sAfterGTLoad.size();
                GTSimpleWirelessNetwork.LOG.info("Added registration runnable to GregTechAPI.sAfterGTLoad (size: " + before + " -> " + after + ")");
                runnableAdded = (after > before);
            }
        } catch (Throwable t) {
            GTSimpleWirelessNetwork.LOG.error("无法将 registration runnable 添加到 GregTechAPI.sBeforeGTLoad", t);
        }

        // Regardless of whether we added the runnable, attempt immediate registration
        // if GregTech has already initialized its METATILEENTITIES array.
        try {
            if (GregTechAPI.METATILEENTITIES == null) {
                GTSimpleWirelessNetwork.LOG.info("GregTechAPI.METATILEENTITIES is not ready yet; registration will be attempted later");
            } else {
                GTSimpleWirelessNetwork.LOG.info("GregTechAPI.METATILEENTITIES already initialized; attempting immediate registration");
                registerFixedMTETestMachines();
            }
        } catch (Throwable t) {
            GTSimpleWirelessNetwork.LOG.error("立即注册时出错", t);
        }
    }

    // Helper to perform strict fixed-ID registration for the MTETestMachine series.
    // This is callable from multiple lifecycle hooks as a fallback if the sBeforeGTLoad
    // runnable did not run.
    private void registerFixedMTETestMachines() {
        GTSimpleWirelessNetwork.LOG.info("registerFixedMTETestMachines invoked");
        // Define the tiers and suffixes similar to NH-Utilities egg machine registration
        String[] suffixes = new String[] { "EV", "IV", "LuV", "ZPM", "UV", "UHV", "UEV", "UIV", "UMV", "UXV",
            "MAX" };
        int[] tiers = new int[] { 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14 };

        // Diagnostic: check METATILEENTITIES array state before attempting registrations
        int len = -1;
        int effectiveBase = -1;
        try {
            if (GregTechAPI.METATILEENTITIES == null) {
                GTSimpleWirelessNetwork.LOG.warn("GregTechAPI.METATILEENTITIES == null at registration time");
            } else {
                len = GregTechAPI.METATILEENTITIES.length;
                GTSimpleWirelessNetwork.LOG.info("GregTechAPI.METATILEENTITIES length=" + len);

                // Compute a safe effective base using preferredMetaBase bounded by the highest fit index
                int neededSlots = suffixes.length + 1; // inclusive
                int highestFitBase = Math.max(1, len - neededSlots - 1);
                effectiveBase = Math.max(1, Math.min(Config.preferredMetaBase, highestFitBase));
                GTSimpleWirelessNetwork.LOG.info("Using effectiveBase " + effectiveBase + " (preferredMetaBase=" + Config.preferredMetaBase + ", highestFitBase=" + highestFitBase + ")");

                int scanStart = Math.max(0, effectiveBase - 10);
                int scanEnd = Math.min(len - 1, effectiveBase + suffixes.length + 10);
                GTSimpleWirelessNetwork.LOG.info("Scanning METATILEENTITIES around effective base " + effectiveBase + " (" + scanStart + ".." + scanEnd + ") for existing entries");
                int found = 0;
                for (int j = scanStart; j <= scanEnd; j++) {
                    IMetaTileEntity m = GregTechAPI.METATILEENTITIES[j];
                    if (m != null) {
                        String name = "<unknown>";
                        try {
                            name = m.getMetaName();
                        } catch (Throwable ignore) {}
                        GTSimpleWirelessNetwork.LOG.info("  index=" + j + " -> " + name);
                        found++;
                    }
                }
                if (found == 0) GTSimpleWirelessNetwork.LOG.info("  (no entries found in that window)");

                // Also scan the older default base region (25000) for comparison
                int oldBase = 25000;
                int oStart = Math.max(0, oldBase - 5);
                int oEnd = Math.min(len - 1, oldBase + suffixes.length + 5);
                GTSimpleWirelessNetwork.LOG.info("Scanning METATILEENTITIES around " + oldBase + " (" + oStart + ".." + oEnd + ") for existing entries");
                found = 0;
                for (int j = oStart; j <= oEnd; j++) {
                    IMetaTileEntity m = GregTechAPI.METATILEENTITIES[j];
                    if (m != null) {
                        String name = "<unknown>";
                        try {
                            name = m.getMetaName();
                        } catch (Throwable ignore) {}
                        GTSimpleWirelessNetwork.LOG.info("  index=" + j + " -> " + name);
                        found++;
                    }
                }
                if (found == 0) GTSimpleWirelessNetwork.LOG.info("  (no entries found in older base window)");
            }
        } catch (Throwable t) {
            GTSimpleWirelessNetwork.LOG.error("在注册前扫描 METATILEENTITIES 时出错", t);
        }

                // Try to use enum IDs directly if available (ensures enum BASE is respected)
                com.miaokatze.gtswn.common.api.enums.MetaTileEntityID[] enumIds = null;
                try {
                    enumIds = com.miaokatze.gtswn.common.api.enums.MetaTileEntityID.values();
                } catch (Throwable ignore) {}

                for (int i = 0; i < suffixes.length; i++) {
            String sfx = suffixes[i];
            int tier = tiers[i];
            String internalName = "gtswn.mtetest." + sfx.toLowerCase();
            String langKey = "wts.mtetest.tier." + sfx; // we provide these in lang files
            String regional = StatCollector.translateToLocal(langKey);
                try {
                    int desired;
                    if (enumIds != null && i < enumIds.length) {
                        desired = enumIds[i].ID;
                    } else {
                        desired = effectiveBase + Config.metaIdOffset + i;
                    }

                    GTSimpleWirelessNetwork.LOG.info("MTETestMachine " + internalName + " target index: " + desired + " (using enum? " + (enumIds != null && i < enumIds.length) + ")");

                    if (GregTechAPI.METATILEENTITIES == null || desired <= 0 || desired >= GregTechAPI.METATILEENTITIES.length) {
                        GTSimpleWirelessNetwork.LOG.error("固定 ID " + desired + " 超出范围或 METATILEENTITIES 未就绪，跳过注册 " + internalName);
                        continue;
                    }

                IMetaTileEntity existing = GregTechAPI.METATILEENTITIES[desired];
                if (existing != null) {
                    try {
                        String existingName = "<unknown>";
                        try {
                            existingName = existing.getMetaName();
                        } catch (Throwable ignoreName) {
                            // ignore
                        }
                        if (internalName.equals(existingName)) {
                            GTSimpleWirelessNetwork.LOG.info("MTETestMachine (" + internalName + ") 已存在于 " + desired + "，跳过注册");
                        } else {
                            GTSimpleWirelessNetwork.LOG.error("固定索引 " + desired + " 已被其他 MetaTileEntity 占用 (" + existingName + ")，无法注册 " + internalName);
                        }
                    } catch (Throwable ignored) {
                        GTSimpleWirelessNetwork.LOG.error("检查已有 MetaTileEntity 时出错，跳过注册 " + internalName, ignored);
                    }
                    continue;
                }

                short idx = (short) desired;
                GregTechAPI.METATILEENTITIES[idx] = new MTETestMachine(idx, internalName, regional, tier);
                GTSimpleWirelessNetwork.LOG.info(
                    "已在 GregTech METATILEENTITIES[" + idx
                        + "] 注册 MTETestMachine ("
                        + internalName
                        + ") tier="
                        + tier);
            } catch (Throwable t) {
                GTSimpleWirelessNetwork.LOG.error("注册 MTETestMachine 时出错 (preInit)", t);
            }
        }
    }

    // load "Do your mod setup. Build whatever data structures you care about. Register recipes." (Remove if not needed)
    @SuppressWarnings({ "unused" })
    public void init(FMLInitializationEvent event) {
        // Try a fallback registration here in case the GregTech sBeforeGTLoad runnable wasn't executed.
        try {
            registerFixedMTETestMachines();
        } catch (Throwable t) {
            GTSimpleWirelessNetwork.LOG.error("在 init() 回退注册 MTETestMachine 时出错", t);
        }
    }

    // postInit "Handle interaction with other mods, complete your setup based on this." (Remove if not needed)
    @SuppressWarnings({ "unused" })
    public void postInit(FMLPostInitializationEvent event) {
        // Final fallback: attempt registration again during postInit in case earlier hooks didn't run
        try {
            GTSimpleWirelessNetwork.LOG.info("postInit: attempting fallback registration of MTETestMachine series");
            registerFixedMTETestMachines();
        } catch (Throwable t) {
            GTSimpleWirelessNetwork.LOG.error("postInit 回退注册 MTETestMachine 时出错", t);
        }
    }

    // register server commands in this event handler (Remove if not needed)
    @SuppressWarnings({ "unused" })
    public void serverStarting(FMLServerStartingEvent event) {}

    // Called at the end of mod loading lifecycle. Final attempt to register if needed.
    public void loadComplete(cpw.mods.fml.common.event.FMLLoadCompleteEvent event) {
        try {
            GTSimpleWirelessNetwork.LOG.info("loadComplete: attempting final registration of MTETestMachine series");
            registerFixedMTETestMachines();
        } catch (Throwable t) {
            GTSimpleWirelessNetwork.LOG.error("loadComplete 回退注册 MTETestMachine 时出错", t);
        }
    }

    // Helper: allocate a preferred index within GregTechAPI.METATILEENTITIES for the given metaName.
    // Return values:
    // >=0 : index chosen for registration
    // -1 : no free slot found
    // -2 : an existing entry with the same metaName was found (do not register)
    private int allocateMetaIndex(String metaName) {
        try {
            int length = GregTechAPI.METATILEENTITIES.length;
            // First, detect whether a meta with the same name already exists -> avoid duplicate
            for (int i = 0; i < length; i++) {
                IMetaTileEntity mte = GregTechAPI.METATILEENTITIES[i];
                if (mte != null) {
                    try {
                        if (metaName.equals(mte.getMetaName())) {
                            return -2; // already present
                        }
                    } catch (Throwable ignore) {
                        // Some MTE implementations may throw on getMetaName(); ignore and continue
                    }
                }
            }

            // Use Config.preferredMetaBase (configured default 25000)
            int preferredBase = Math.max(1, Config.preferredMetaBase);

            // Phase A: search within preferred range window first
            int rangeWindow = 1000; // window size to prefer
            int end = Math.min(length - 1, preferredBase + rangeWindow - 1);
            if (preferredBase < length) {
                for (int i = preferredBase; i <= end; i++) {
                    if (GregTechAPI.METATILEENTITIES[i] == null) {
                        return i;
                    }
                }
            }

            // Phase B: fallback to scanning from 1 upwards to find the first free slot
            for (int i = 1; i < length; i++) {
                if (GregTechAPI.METATILEENTITIES[i] == null) {
                    return i;
                }
            }

            return -1; // no slot found
        } catch (Throwable t) {
            GTSimpleWirelessNetwork.LOG.error("allocateMetaIndex 出现异常", t);
            return -1;
        }
    }
}
