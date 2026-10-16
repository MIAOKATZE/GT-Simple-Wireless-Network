package com.miaokatze.gtswn.common.testworld;

import java.io.File;

import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import com.miaokatze.gtswn.common.util.DebugTestGate;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

/** Fixed save identity. A collision is reported immediately and never silently remapped. */
public final class TestDimension {

    private static int id = Integer.MIN_VALUE;
    private static String problem = "not initialized";

    private TestDimension() {}

    public static void init(File directory) {
        if (!DebugTestGate.isEnabled()) {
            problem = DebugTestGate.DISABLED_MESSAGE;
            return;
        }
        int requested = Config.testDimensionId;
        if (DimensionManager.isDimensionRegistered(requested)
            || !DimensionManager.registerProviderType(requested, WorldProviderTest.class, false)) {
            problem = "dimension/provider ID " + requested + " conflicts; check testworld configuration";
            GTSimpleWirelessNetwork.LOG
                .error("[GTSWN-TEST] {}. Test commands disabled; saves were not remapped.", problem);
            return;
        }
        id = requested;
        DimensionManager.registerDimension(id, id);
        problem = null;
    }

    public static int dimId() {
        return id;
    }

    public static String problem() {
        return problem;
    }

    public static boolean isTest(World world) {
        return world != null && world.provider instanceof WorldProviderTest && world.provider.dimensionId == id;
    }

    public static WorldServer ensureWorld() {
        DebugTestGate.requireEnabled();
        if (problem != null) throw new IllegalStateException(problem);
        if (DimensionManager.getWorld(id) == null) DimensionManager.initDimension(id);
        WorldServer world = DimensionManager.getWorld(id);
        if (!isTest(world)) throw new IllegalStateException("GTSWN-TEST world unavailable");
        return world;
    }
}
