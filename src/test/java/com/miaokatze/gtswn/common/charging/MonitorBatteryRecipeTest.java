package com.miaokatze.gtswn.common.charging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ObjectIntIdentityMap;
import net.minecraft.util.RegistryNamespaced;

import org.junit.BeforeClass;
import org.junit.Test;

import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;
import com.miaokatze.gtswn.recipe.MonitorBatteryRecipe;

import ic2.api.item.IElectricItemManager;
import ic2.test.FakeRechargeableBattery;

public class MonitorBatteryRecipeTest {

    private static final PortableWirelessNetworkMonitor MONITOR = new PortableWirelessNetworkMonitor();
    private static final FakeRechargeableBattery BATTERY = new FakeRechargeableBattery();
    private static final FakeRechargeableBattery SINGLE_USE = new FakeRechargeableBattery();
    private static final FakeRechargeableBattery TOOL = new FakeRechargeableBattery();

    @BeforeClass
    public static void registerFixtureItems() throws Exception {
        // Register IDs only: Forge's full mod registry requires a running LaunchClassLoader.
        ObjectIntIdentityMap ids = null;
        for (Field field : RegistryNamespaced.class.getDeclaredFields()) {
            if (field.getType() == ObjectIntIdentityMap.class) {
                field.setAccessible(true);
                ids = (ObjectIntIdentityMap) field.get(Item.itemRegistry);
            }
        }
        assertNotNull(ids);
        ids.func_148746_a(MONITOR, 31001);
        ids.func_148746_a(BATTERY, 31002);
        ids.func_148746_a(SINGLE_USE, 31003);
        ids.func_148746_a(TOOL, 31004);
        SINGLE_USE.rechargeable = false;
        TOOL.providesEnergy = false;
    }

    @Test
    public void toolFromAnotherModCannotRemoveBattery() {
        ItemStack fakeCrowbar = new ItemStack(TOOL);
        ItemStack installed = MonitorBattery.install(monitor(), battery(123));
        ItemStack before = installed.copy();
        assertFalse(new MonitorBatteryRecipe().matches(matrix(installed, fakeCrowbar), null));
        assertNull(new MonitorBatteryRecipe().getCraftingResult(matrix(installed, fakeCrowbar)));
        assertTrue(ItemStack.areItemStacksEqual(before, installed));
    }

    @Test
    public void nativeTooltipReadsLiveCacheWithoutChangingNestedBatteryOrTerminal() {
        ItemStack installed = MonitorBattery.install(monitor(), battery(700));
        MonitorBattery.data(installed)
            .setDouble("Charge", 321);
        ItemStack before = installed.copy();
        assertEquals("321.0/1000 EU", MonitorElectricItemManager.getChargeTooltip(installed));
        assertNull(
            MONITOR.getManager(installed)
                .getToolTip(installed));
        assertTrue(ItemStack.areItemStacksEqual(before, installed));
        assertNull(
            MONITOR.getManager(monitor())
                .getToolTip(monitor()));
        assertNull(MonitorElectricItemManager.getChargeTooltip(monitor()));
    }

    @Test
    public void removingBatteryPreservesTerminalMetadataAndReturnsLiveChargeWithoutMutation() {
        assertNull(MonitorBattery.withoutBattery(monitor()));
        for (double charge : new double[] { 0, 321, 1000 }) {
            ItemStack installed = MonitorBattery.install(monitor(), battery(700));
            MonitorBattery.data(installed)
                .setDouble("Charge", charge);
            ItemStack before = installed.copy();
            ItemStack empty = MonitorBattery.withoutBattery(installed);
            assertFalse(MonitorBattery.hasBattery(empty));
            assertTrue(ItemStack.areItemStacksEqual(monitor(), empty));
            ItemStack returned = MONITOR.getContainerItem(installed);
            assertEquals(
                charge,
                MonitorBattery.manager(returned)
                    .getCharge(returned),
                0);
            assertTrue(ItemStack.areItemStacksEqual(before, installed));
            assertFalse(MONITOR.hasContainerItem(empty));
            assertNull(MONITOR.getContainerItem(empty));
            assertFalse(new MonitorBatteryRecipe().matches(matrix(installed, new ItemStack(new Item())), null));
        }
    }

    @Test
    public void nativeManagerUsesTheSameCacheAndPureSimulation() {
        ItemStack stack = MonitorBattery.install(monitor(), battery(456));
        IElectricItemManager manager = MONITOR.getManager(stack);
        ItemStack before = stack.copy();
        assertEquals(32, manager.charge(stack, 100, 1, false, true), 0);
        assertEquals(544, manager.charge(stack, Double.POSITIVE_INFINITY, 1, true, true), 0);
        assertEquals(0, manager.charge(stack, 100, 0, true, true), 0);
        assertEquals(0, manager.discharge(stack, 100, 1, true, true, false), 0);
        assertEquals(100, manager.discharge(stack, 100, 1, true, false, true), 0);
        assertTrue(ItemStack.areItemStacksEqual(before, stack));
        assertEquals(544, manager.charge(stack, 10000, 1, true, false), 0);
        assertEquals(1000, manager.getCharge(stack), 0);
        assertEquals(123, manager.discharge(stack, 123, 1, true, false, false), 0);
        assertEquals(877, manager.getCharge(stack), 0);
        assertEquals(
            877,
            MonitorBattery.manager(MONITOR.getContainerItem(stack))
                .getCharge(MONITOR.getContainerItem(stack)),
            0);
        assertEquals(1000, MONITOR.getMaxCharge(stack), 0);
        assertEquals(1, MONITOR.getTier(stack));
        assertFalse(MONITOR.canProvideEnergy(stack));
        assertTrue(MONITOR.showDurabilityBar(stack));
        assertEquals(0.123, MONITOR.getDurabilityForDisplay(stack), 0.00001);
        assertEquals(
            "owner",
            stack.getTagCompound()
                .getString(PortableWirelessNetworkMonitor.NBT_OWNER_UUID));
        assertEquals(
            "kept",
            stack.getTagCompound()
                .getString("OtherModData"));
    }

    @Test
    public void emptyMonitorAndInvalidAmountsCannotCreateOrExtractEnergy() {
        ItemStack empty = monitor();
        IElectricItemManager manager = MONITOR.getManager(empty);
        ItemStack before = empty.copy();
        assertEquals(0, manager.charge(empty, 100, Integer.MAX_VALUE, true, false), 0);
        assertEquals(0, manager.discharge(empty, 100, Integer.MAX_VALUE, true, false, false), 0);
        assertFalse(manager.canUse(empty, 0));
        assertFalse(MONITOR.showDurabilityBar(empty));
        assertEquals(0, MONITOR.getMaxCharge(empty), 0);
        assertTrue(ItemStack.areItemStacksEqual(before, empty));
        ItemStack installed = MonitorBattery.install(empty, battery(456));
        ItemStack installedBefore = installed.copy();
        assertEquals(0, manager.charge(installed, -1, 1, true, false), 0);
        assertEquals(0, manager.charge(installed, Double.NaN, 1, true, false), 0);
        assertEquals(0, manager.discharge(installed, -1, 1, true, false, false), 0);
        assertFalse(manager.use(installed, Double.NaN, null));
        assertFalse(manager.use(installed, 457, null));
        assertTrue(ItemStack.areItemStacksEqual(installedBefore, installed));
        assertTrue(manager.use(installed, 123, null));
        assertEquals(333, manager.getCharge(installed), 0);
    }

    @Test
    public void installingPreservesBindingAndImmediateChargeWithoutMutatingInputs() {
        ItemStack monitor = monitor();
        ItemStack battery = battery(456);
        ItemStack beforeMonitor = monitor.copy();
        ItemStack beforeBattery = battery.copy();
        ItemStack result = new MonitorBatteryRecipe().getCraftingResult(matrix(monitor, battery));
        assertTrue(MonitorBattery.hasBattery(result));
        assertEquals(
            456,
            MonitorBattery.data(result)
                .getDouble("Charge"),
            0);
        assertEquals(
            1000,
            MonitorBattery.data(result)
                .getDouble("Capacity"),
            0);
        assertEquals(
            "owner",
            result.getTagCompound()
                .getString(PortableWirelessNetworkMonitor.NBT_OWNER_UUID));
        assertEquals(
            2,
            result.getTagCompound()
                .getInteger(PortableWirelessNetworkMonitor.NBT_HUD_MODE));
        assertEquals(
            "kept",
            result.getTagCompound()
                .getString("OtherModData"));
        assertTrue(ItemStack.areItemStacksEqual(beforeMonitor, monitor));
        assertTrue(ItemStack.areItemStacksEqual(beforeBattery, battery));
    }

    @Test
    public void replacingReturnsExactlyTheOldRemainingCacheAtZeroPartialAndFull() {
        for (double remaining : new double[] { 0, 321, 1000 }) {
            ItemStack installed = MonitorBattery.install(monitor(), battery(700));
            MonitorBattery.data(installed)
                .setDouble("Charge", remaining);
            ItemStack before = installed.copy();
            ItemStack replacement = new MonitorBatteryRecipe().getCraftingResult(matrix(installed, battery(123)));
            ItemStack returned = ((Item) MONITOR).getContainerItem(installed);
            assertNotNull(returned);
            assertSame(BATTERY, returned.getItem());
            assertEquals(
                remaining,
                MonitorBattery.manager(returned)
                    .getCharge(returned),
                0);
            assertEquals(
                123,
                MonitorBattery.data(replacement)
                    .getDouble("Charge"),
                0);
            assertTrue(ItemStack.areItemStacksEqual(before, installed));
            assertEquals(1, returned.stackSize);
        }
    }

    @Test
    public void stackedBatteryInputInstallsOnlyOneAndDoesNotMutateTheStack() {
        ItemStack input = battery(0);
        input.stackSize = 16;
        MonitorBatteryRecipe recipe = new MonitorBatteryRecipe();
        InventoryCrafting matrix = matrix(monitor(), input);
        assertTrue(recipe.matches(matrix, null));
        ItemStack result = recipe.getCraftingResult(matrix);
        ItemStack installed = ItemStack.loadItemStackFromNBT(
            MonitorBattery.data(result)
                .getCompoundTag("Item"));
        assertEquals(1, installed.stackSize);
        assertEquals(16, input.stackSize);
        assertEquals(
            0,
            MonitorBattery.manager(input)
                .getCharge(input),
            0);
    }

    @Test
    public void craftingIsPureAndRequiresExactlyOneMonitorAndOneRechargeableBattery() {
        MonitorBatteryRecipe recipe = new MonitorBatteryRecipe();
        InventoryCrafting valid = matrix(monitor(), battery(300));
        assertTrue(recipe.matches(valid, null));
        assertTrue(ItemStack.areItemStacksEqual(recipe.getCraftingResult(valid), recipe.getCraftingResult(valid)));
        assertFalse(recipe.matches(matrix(monitor()), null));
        assertFalse(recipe.matches(matrix(battery(1), battery(2)), null));
        assertFalse(recipe.matches(matrix(monitor(), monitor(), battery(1)), null));
        assertFalse(recipe.matches(matrix(monitor(), battery(1), new ItemStack(new Item())), null));
        assertFalse(recipe.matches(matrix(monitor(), new ItemStack(TOOL)), null));
        assertFalse(recipe.matches(matrix(monitor(), new ItemStack(SINGLE_USE)), null));
        assertNull(recipe.getCraftingResult(matrix(monitor(), new ItemStack(SINGLE_USE))));
    }

    private static ItemStack monitor() {
        ItemStack stack = new ItemStack(MONITOR);
        stack.setTagCompound(new NBTTagCompound());
        stack.getTagCompound()
            .setString(PortableWirelessNetworkMonitor.NBT_OWNER_UUID, "owner");
        stack.getTagCompound()
            .setBoolean(PortableWirelessNetworkMonitor.NBT_INITIALIZED, true);
        stack.getTagCompound()
            .setInteger(PortableWirelessNetworkMonitor.NBT_HUD_MODE, 2);
        stack.getTagCompound()
            .setString("OtherModData", "kept");
        return stack;
    }

    private static ItemStack battery(double charge) {
        ItemStack stack = new ItemStack(BATTERY);
        FakeRechargeableBattery.setCharge(stack, charge);
        return stack;
    }

    private static InventoryCrafting matrix(ItemStack... inputs) {
        InventoryCrafting matrix = new InventoryCrafting(new Container() {

            @Override
            public boolean canInteractWith(EntityPlayer player) {
                return false;
            }
        }, 3, 3);
        for (int i = 0; i < inputs.length; i++) matrix.setInventorySlotContents(i, inputs[i]);
        return matrix;
    }
}
