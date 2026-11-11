// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidStack;

import org.junit.Test;

import com.miaokatze.gtswn.common.panel.AEMonitorDataSet;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.test.FluidRegistryTestBootstrap;

public class PortableMonitorRowReuseTest {

    @Test
    public void duplicateItemOnlyMergesMonitoredFlagAndEachOperationHashesOnce() throws Exception {
        CountingItem item = fixtureItem();
        item.names = 0;
        item.label = "First";
        ItemStack stack = new ItemStack(item);
        CountingTag tag = tag(1);
        stack.setTagCompound(tag);
        PacketSyncPortableMonitor.Panel data = new PacketSyncPortableMonitor.Panel();
        Map<String, PacketSyncPortableMonitor.Row> keys = new HashMap<>();
        add("addItem", data, keys, stack, true, Collections.emptySet(), false);
        assertEquals(1, tag.copies);
        assertEquals(1, item.names);
        assertNotNull(data.rows.get(0).icon);
        add("addItem", data, keys, stack, true, Collections.emptySet(), true);
        assertEquals(2, tag.copies);
        assertEquals(1, item.names);
        assertEquals(1, data.rows.size());
        assertTrue(data.rows.get(0).monitored);

        item.label = "Next tick";
        PacketSyncPortableMonitor.Panel next = new PacketSyncPortableMonitor.Panel();
        add("addItem", next, new HashMap<>(), stack, false, Collections.singleton(data.rows.get(0).key), false);
        assertEquals(3, tag.copies);
        assertEquals(2, item.names);
        assertEquals("Next tick", next.rows.get(0).name);
    }

    @Test
    public void duplicateFluidOnlyMergesMonitoredFlagAndNextResponseRefreshesName() throws Exception {
        CountingFluid fluid = new CountingFluid();
        CountingTag tag = tag(1);
        fluid.tag = tag;
        PacketSyncPortableMonitor.Panel data = new PacketSyncPortableMonitor.Panel();
        Map<String, PacketSyncPortableMonitor.Row> keys = new HashMap<>();
        add("addFluid", data, keys, fluid, true, Collections.emptySet(), false);
        assertEquals(1, tag.copies);
        assertEquals(1, fluid.names);
        assertNotNull(data.rows.get(0).icon);
        add("addFluid", data, keys, fluid, true, Collections.emptySet(), true);
        assertEquals(2, tag.copies);
        assertEquals(1, fluid.names);
        assertTrue(data.rows.get(0).monitored);
        assertEquals(1, data.rows.size());

        fluid.label = "Next tick";
        PacketSyncPortableMonitor.Panel next = new PacketSyncPortableMonitor.Panel();
        add("addFluid", next, new HashMap<>(), fluid, false, Collections.singleton(data.rows.get(0).key), false);
        assertEquals(3, tag.copies);
        assertEquals(2, fluid.names);
        assertEquals("Next tick", next.rows.get(0).name);
    }

    @Test
    public void capacityAndUnselectedPinsSkipNamesButDuplicatesStillBecomeMonitored() throws Exception {
        CountingItem item = fixtureItem();
        item.names = 0;
        ItemStack stack = new ItemStack(item);
        stack.setTagCompound(tag(1));
        CountingFluid fluid = new CountingFluid();
        fluid.tag = tag(1);
        PacketSyncPortableMonitor.Panel data = new PacketSyncPortableMonitor.Panel();
        Map<String, PacketSyncPortableMonitor.Row> keys = new HashMap<>();
        add("addItem", data, keys, stack, false, Collections.emptySet(), false);
        add("addFluid", data, keys, fluid, false, Collections.emptySet(), false);
        assertTrue(data.rows.isEmpty());
        assertEquals(0, item.names);
        assertEquals(0, fluid.names);
        add("addItem", data, keys, stack, true, Collections.emptySet(), false);
        PacketSyncPortableMonitor.Row existing = data.rows.get(0);
        while (data.rows.size() < PacketSyncPortableMonitor.MAX_ROWS)
            data.rows.add(new PacketSyncPortableMonitor.Row());
        add("addItem", data, keys, stack, true, Collections.emptySet(), true);
        assertTrue(existing.monitored);
        assertEquals(1, item.names);
        ItemStack other = new ItemStack(item, 1, 2);
        add("addItem", data, keys, other, true, Collections.emptySet(), true);
        add("addFluid", data, keys, fluid, true, Collections.emptySet(), true);
        assertEquals(1, item.names);
        assertEquals(0, fluid.names);
        assertEquals(PacketSyncPortableMonitor.MAX_ROWS, data.rows.size());
    }

    @Test
    public void completeNbtIdentityKeepsDifferentRowsAndTheirSampleAmounts() throws Exception {
        CountingItem item = fixtureItem();
        ItemStack first = new ItemStack(item), second = new ItemStack(item);
        first.setTagCompound(tag(1));
        second.setTagCompound(tag(2));
        AEMonitorDataSet set = new AEMonitorDataSet();
        String firstKey = TileEntityNetworkInfoPanel.getAEKey(first),
            secondKey = TileEntityNetworkInfoPanel.getAEKey(second);
        set.addSample(firstKey, 100, 1, 1000);
        set.addSample(secondKey, 200, 1, 1000);
        PacketSyncPortableMonitor.Panel data = new PacketSyncPortableMonitor.Panel();
        Map<String, PacketSyncPortableMonitor.Row> keys = new HashMap<>();
        Method add = method("addItem", ItemStack.class);
        add.invoke(null, data, keys, set, first, true, Collections.emptySet(), false);
        add.invoke(null, data, keys, set, second, true, Collections.emptySet(), true);
        assertEquals(2, data.rows.size());
        assertNotEquals(firstKey, secondKey);
        assertEquals("100", data.rows.get(0).amount);
        assertEquals("200", data.rows.get(1).amount);
        assertFalse(data.rows.get(0).monitored);
        assertTrue(data.rows.get(1).monitored);
    }

    private static void add(String name, PacketSyncPortableMonitor.Panel data,
        Map<String, PacketSyncPortableMonitor.Row> keys, Object stack, boolean detail, Set<String> pins,
        boolean monitored) throws Exception {
        method(name, stack instanceof ItemStack ? ItemStack.class : FluidStack.class)
            .invoke(null, data, keys, null, stack, detail, pins, monitored);
    }

    private static Method method(String name, Class<?> type) throws Exception {
        Method method = PortableMonitorRequestQueue.class.getDeclaredMethod(
            name,
            PacketSyncPortableMonitor.Panel.class,
            Map.class,
            AEMonitorDataSet.class,
            type,
            boolean.class,
            Set.class,
            boolean.class);
        method.setAccessible(true);
        return method;
    }

    private static CountingItem fixtureItem() throws Exception {
        String name = "minecraft:gtswn_portable_row_fixture";
        Object registered = Item.itemRegistry.getObject(name);
        if (registered instanceof CountingItem) return (CountingItem) registered;
        CountingItem item = new CountingItem();
        Method register = Item.itemRegistry.getClass()
            .getDeclaredMethod("addObjectRaw", int.class, String.class, Object.class);
        register.setAccessible(true);
        register.invoke(Item.itemRegistry, 31888, name, item);
        return item;
    }

    private static CountingTag tag(int identity) {
        CountingTag tag = new CountingTag();
        tag.setInteger("identity", identity);
        return tag;
    }

    private static final class CountingTag extends NBTTagCompound {

        int copies;

        @Override
        public NBTBase copy() {
            copies++;
            return super.copy();
        }
    }

    private static final class CountingItem extends Item {

        int names;
        String label = "First";

        @Override
        public String getItemStackDisplayName(ItemStack stack) {
            names++;
            return label;
        }
    }

    private static final class CountingFluid extends FluidStack {

        int names;
        String label = "First";

        CountingFluid() {
            super(FluidRegistryTestBootstrap.water(1000), 1000);
        }

        @Override
        public String getLocalizedName() {
            names++;
            return label;
        }
    }
}
