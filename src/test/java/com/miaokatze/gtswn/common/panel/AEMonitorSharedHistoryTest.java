package com.miaokatze.gtswn.common.panel;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.Collections;

import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.profiler.Profiler;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.MapStorage;
import net.minecraftforge.fluids.FluidStack;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.test.FluidRegistryTestBootstrap;

/** Chart and monitor roles on one panel must not delete each other's shared source-qualified series. */
public class AEMonitorSharedHistoryTest {

    private static Item item;
    private AEMonitorController controller;
    private AEMonitorDataSet data;

    @BeforeClass
    public static void registerItem() throws Exception {
        item = new Item();
        java.lang.reflect.Method register = Item.itemRegistry.getClass()
            .getDeclaredMethod("addObjectRaw", int.class, String.class, Object.class);
        register.setAccessible(true);
        register.invoke(Item.itemRegistry, 31018, "gtswn_test:shared_history", item);
    }

    @Before
    public void setup() throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafe.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        FixtureWorld world = (FixtureWorld) unsafe.getMethod("allocateInstance", Class.class)
            .invoke(singleton.get(null), FixtureWorld.class);
        world.perWorldStorage = new MapStorage(null);
        world.playerEntities = Collections.emptyList();
        TileEntityNetworkInfoPanel core = new TileEntityNetworkInfoPanel() {
            @Override
            public String getAEMonitorDataKey() { return "shared-history-fixture"; }
        };
        core.setWorldObj(world);
        controller = new AEMonitorController(core, null, null, () -> {});
        data = AEMonitorDataStore.get(world).getOrCreate("shared-history-fixture");
    }

    @Test
    public void unbindingItemChartPreservesMonitoredSeriesUntilItsLastRoleIsRemoved() {
        ItemStack stack = new ItemStack(item, 1, 0);
        controller.setChartItem(stack);
        controller.toggleItemMonitor(stack);
        sample(AEMonitorController.aeKey(stack));
        controller.clearAEBinding();
        assertNotNull(data.newest(AEMonitorController.aeKey(stack)));
        controller.toggleItemMonitor(stack);
        assertNull(data.newest(AEMonitorController.aeKey(stack)));
    }

    @Test
    public void removingMonitorPreservesChartHistoryAndItsSampleLock() {
        ItemStack stack = new ItemStack(item, 1, 0);
        controller.setChartItem(stack);
        controller.toggleItemMonitor(stack);
        sample(AEMonitorController.aeKey(stack));
        controller.toggleItemMonitor(stack);
        String key = AEMonitorController.aeKey(stack);
        assertNotNull(data.newest(key));
        assertFalse(data.tryAcquireSampleLock(key, 101, 100));
        controller.setChartItem(stack); // Toggle final chart role off.
        assertNull(data.newest(key));
    }

    @Test
    public void clearingAllMonitorsRetainsOnlyTheStillBoundChartSeries() {
        ItemStack chart = new ItemStack(item, 1, 0), other = new ItemStack(item, 1, 1);
        controller.setChartItem(chart);
        controller.toggleItemMonitor(chart);
        controller.toggleItemMonitor(other);
        sample(AEMonitorController.aeKey(chart));
        sample(AEMonitorController.aeKey(other));
        controller.clearAllAEMonitors();
        assertNotNull(data.newest(AEMonitorController.aeKey(chart)));
        assertNull(data.newest(AEMonitorController.aeKey(other)));
    }

    @Test
    public void changingChartTypePreservesOldFluidHistoryWhileMonitored() {
        FluidStack fluid = FluidRegistryTestBootstrap.water(1000);
        controller.setChartFluid(fluid);
        controller.toggleFluidMonitor(fluid);
        sample(AEMonitorController.aeKey(fluid));
        controller.setChartItem(new ItemStack(item, 1, 0));
        assertNotNull(data.newest(AEMonitorController.aeKey(fluid)));
        controller.toggleFluidMonitor(fluid);
        assertNull(data.newest(AEMonitorController.aeKey(fluid)));
    }

    @Test
    public void clearingFluidMonitorsPreservesFluidChartUntilUnbound() {
        FluidStack fluid = FluidRegistryTestBootstrap.water(1000);
        controller.setChartFluid(fluid);
        controller.toggleFluidMonitor(fluid);
        sample(AEMonitorController.aeKey(fluid));
        controller.clearAllAEMonitors();
        assertNotNull(data.newest(AEMonitorController.aeKey(fluid)));
        controller.setChartFluid(fluid);
        assertNull(data.newest(AEMonitorController.aeKey(fluid)));
    }

    private void sample(String key) { data.addSample(key, 100, 100, 1000); }

    private static final class FixtureWorld extends World {
        private FixtureWorld() {
            super((ISaveHandler) null, "fixture", (WorldProvider) null, (WorldSettings) null, (Profiler) null);
        }
        @Override
        protected IChunkProvider createChunkProvider() { return null; }
        @Override
        protected int func_152379_p() { return 0; }
        @Override
        public Entity getEntityByID(int id) { return null; }
    }
}
