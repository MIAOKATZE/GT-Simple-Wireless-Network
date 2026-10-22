package com.miaokatze.gtswn.register;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;
import com.miaokatze.miaokatzecore.api.items.ItemOrderCatalog;

public class CreativeTabManagerTest {

    private static final GTSWNItemList[] EXPECTED = { GTSWNItemList.Portable_Wireless_Network_Monitor,
        GTSWNItemList.Device_Info_Terminal, GTSWNItemList.Wireless_Energy_Tap,
        GTSWNItemList.ME_Network_Quantum_Terminal, GTSWNItemList.Wireless_Energy_Monitor,
        GTSWNItemList.GTswn_Cover_Energy_Wireless, GTSWNItemList.GTswn_Cover_Dynamo_Wireless,
        GTSWNItemList.Network_Info_Panel, GTSWNItemList.Network_Info_Panel_Extender,
        GTSWNItemList.ME_Network_Quantum_Node, GTSWNItemList.Quantum_Incorporation_Anchor, GTSWNItemList.TestCoin };

    private final List<ItemStack> savedItems = new ArrayList<>();
    private final ItemStack[] savedStacks = new ItemStack[GTSWNItemList.values().length];
    private final boolean[] savedUnset = new boolean[GTSWNItemList.values().length];
    private List<ItemStack> registered;
    private Field stackField;
    private Field unsetField;
    private Item sharedItem;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        Field items = CreativeTabManager.class.getDeclaredField("itemsToAdd");
        items.setAccessible(true);
        registered = (List<ItemStack>) items.get(null);
        savedItems.addAll(registered);
        registered.clear();
        stackField = GTSWNItemList.class.getDeclaredField("mStack");
        unsetField = GTSWNItemList.class.getDeclaredField("mHasNotBeenSet");
        stackField.setAccessible(true);
        unsetField.setAccessible(true);
        sharedItem = new Item().setHasSubtypes(true);
        for (GTSWNItemList entry : GTSWNItemList.values()) {
            savedStacks[entry.ordinal()] = (ItemStack) stackField.get(entry);
            savedUnset[entry.ordinal()] = unsetField.getBoolean(entry);
            stackField.set(entry, new ItemStack(sharedItem, 1, entry.ordinal()));
            unsetField.setBoolean(entry, false);
        }
        CreativeTabManager.initCreativeTab();
    }

    @After
    public void tearDown() throws Exception {
        registered.clear();
        registered.addAll(savedItems);
        for (GTSWNItemList entry : GTSWNItemList.values()) {
            stackField.set(entry, savedStacks[entry.ordinal()]);
            unsetField.setBoolean(entry, savedUnset[entry.ordinal()]);
        }
        ItemOrderCatalog.invalidate();
    }

    private ItemStack fixture(GTSWNItemList entry) {
        return new ItemStack(sharedItem, 1, entry.ordinal());
    }

    private void registerAllExcept(GTSWNItemList excluded) {
        List<GTSWNItemList> reversed = new ArrayList<>(Arrays.asList(EXPECTED));
        Collections.reverse(reversed);
        for (GTSWNItemList entry : reversed) {
            if (entry != excluded) CreativeTabManager.addItemToTab(fixture(entry));
        }
    }

    @Test
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void allRegisteredItemsHaveOneOrderAcrossCreativeAndCore() {
        assertEquals(
            EXPECTED.length,
            EnumSet.copyOf(Arrays.asList(EXPECTED))
                .size());
        assertEquals(EnumSet.allOf(GTSWNItemList.class), EnumSet.copyOf(Arrays.asList(EXPECTED)));
        registerAllExcept(null);
        List<ItemStack> creative = new ArrayList<>();
        CreativeTabManager.CREATIVE_TAB.displayAllReleventItems((List) creative);
        List<ItemStack> neiInput = new ArrayList<>(registered);
        ItemOrderCatalog.sort(GTSimpleWirelessNetwork.MODID, neiInput);
        assertEquals(EXPECTED.length, creative.size());
        assertEquals(creative.size(), neiInput.size());
        for (int i = 0; i < EXPECTED.length; i++) {
            assertEquals(
                EXPECTED[i].ordinal(),
                creative.get(i)
                    .getItemDamage());
            assertTrue(ItemStack.areItemStacksEqual(creative.get(i), neiInput.get(i)));
        }
    }

    @Test
    public void lateGtMachineRegistrationRefreshesTheCatalog() {
        GTSWNItemList machine = GTSWNItemList.Wireless_Energy_Monitor;
        unsetFieldUnchecked(machine, true);
        registerAllExcept(machine);
        assertEquals(
            EXPECTED.length - 1,
            CreativeTabManager.getOrderedCreativeItems()
                .size());
        unsetFieldUnchecked(machine, false);
        CreativeTabManager.addItemToTab(fixture(machine));
        List<ItemStack> ordered = CreativeTabManager.getOrderedCreativeItems();
        assertEquals(EXPECTED.length, ordered.size());
        assertEquals(
            machine.ordinal(),
            ordered.get(4)
                .getItemDamage());
    }

    @Test
    public void sharedGtItemMetadataDoesNotIntroduceOtherMachinesOrDropExtensions() {
        registerAllExcept(null);
        List<ItemStack> input = Arrays
            .asList(new ItemStack(sharedItem, 1, 1000), fixture(EXPECTED[1]), fixture(EXPECTED[0]));
        List<ItemStack> sorted = new ArrayList<>(input);
        ItemOrderCatalog.sort(GTSimpleWirelessNetwork.MODID, sorted);
        assertEquals(3, sorted.size());
        assertEquals(
            EXPECTED[0].ordinal(),
            sorted.get(0)
                .getItemDamage());
        assertEquals(
            EXPECTED[1].ordinal(),
            sorted.get(1)
                .getItemDamage());
        assertSame(input.get(0), sorted.get(2));
        assertEquals(
            1000,
            sorted.get(2)
                .getItemDamage());
        CreativeTabManager.addItemToTab(new ItemStack(sharedItem, 1, 1001));
        List<ItemStack> ordered = CreativeTabManager.getOrderedCreativeItems();
        assertEquals(EXPECTED.length + 1, ordered.size());
        assertEquals(
            1001,
            ordered.get(EXPECTED.length)
                .getItemDamage());
    }

    @Test
    public void catalogOwnsItsStacksAndPreservesNestedNbt() {
        ItemStack original = fixture(EXPECTED[0]);
        original.setTagCompound(new NBTTagCompound());
        original.getTagCompound()
            .setString("owner", "original");
        CreativeTabManager.addItemToTab(original);
        original.getTagCompound()
            .setString("owner", "caller mutation");
        List<ItemStack> first = CreativeTabManager.getItemsToAdd();
        assertEquals(
            "original",
            first.get(0)
                .getTagCompound()
                .getString("owner"));
        first.get(0)
            .getTagCompound()
            .setString("owner", "returned mutation");
        first.clear();
        List<ItemStack> second = CreativeTabManager.getItemsToAdd();
        assertEquals(1, second.size());
        assertEquals(
            "original",
            second.get(0)
                .getTagCompound()
                .getString("owner"));
    }

    private void unsetFieldUnchecked(GTSWNItemList entry, boolean unset) {
        try {
            unsetField.setBoolean(entry, unset);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }
}
