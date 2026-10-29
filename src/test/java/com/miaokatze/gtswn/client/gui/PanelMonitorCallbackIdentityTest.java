package com.miaokatze.gtswn.client.gui;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidStack;

import org.junit.BeforeClass;
import org.junit.Test;

import com.miaokatze.gtswn.test.FluidRegistryTestBootstrap;

/** A native row callback may survive rebuild; its displayed resource must never become an array index. */
public class PanelMonitorCallbackIdentityTest {

    private static Item item;

    @BeforeClass
    public static void registerItem() throws Exception {
        item = new Item();
        java.lang.reflect.Method register = Item.itemRegistry.getClass()
            .getDeclaredMethod("addObjectRaw", int.class, String.class, Object.class);
        register.setAccessible(true);
        register.invoke(Item.itemRegistry, 31019, "gtswn_test:panel_callback", item);
    }

    @Test
    public void reorderKeepsTheDisplayedResourceRatherThanItsOldIndex() {
        ItemStack first = new ItemStack(item, 1, 0), second = new ItemStack(item, 1, 1);
        String captured = GuiNetworkInfoPanel.entryKey(first);
        assertSame(first, GuiNetworkInfoPanel.currentMonitorEntry(captured,
            Arrays.asList(second, first), Collections.emptyList()));
    }

    @Test
    public void sameLengthReplacementRejectsRemovedResourceInsteadOfTogglingReplacement() {
        ItemStack first = new ItemStack(item, 1, 0), replacement = new ItemStack(item, 1, 1);
        String captured = GuiNetworkInfoPanel.entryKey(first);
        assertNull(GuiNetworkInfoPanel.currentMonitorEntry(captured,
            Collections.singletonList(replacement), Collections.emptyList()));
    }

    @Test
    public void refreshedQuantityUsesLiveDescriptorButDifferentNbtDoesNotMatch() {
        ItemStack old = new ItemStack(item, 1, 0), live = new ItemStack(item, 64, 0);
        String captured = GuiNetworkInfoPanel.entryKey(old);
        assertSame(live, GuiNetworkInfoPanel.currentMonitorEntry(captured,
            Collections.singletonList(live), Collections.emptyList()));
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("source", "different");
        live.setTagCompound(tag);
        assertNull(GuiNetworkInfoPanel.currentMonitorEntry(captured,
            Collections.singletonList(live), Collections.emptyList()));
    }

    @Test
    public void fluidRemovalDoesNotShiftWhenAnItemBeforeItDisappears() {
        FluidStack fluid = FluidRegistryTestBootstrap.water(1000);
        String captured = GuiNetworkInfoPanel.entryKey(fluid);
        assertSame(fluid, GuiNetworkInfoPanel.currentMonitorEntry(captured,
            Collections.emptyList(), Collections.singletonList(fluid)));
        assertNull(GuiNetworkInfoPanel.currentMonitorEntry(captured,
            Collections.emptyList(), Collections.emptyList()));
    }
}
