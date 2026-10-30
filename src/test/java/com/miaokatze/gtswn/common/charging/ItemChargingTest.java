package com.miaokatze.gtswn.common.charging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import appeng.api.config.AccessRestriction;
import appeng.api.config.PowerUnits;
import appeng.api.implementations.items.IAEItemPowerStorage;
import cofh.api.energy.IEnergyContainerItem;
import gregtech.api.GregTechAPI;
import ic2.api.item.IElectricItemManager;
import ic2.test.FakeRechargeableBattery;

public class ItemChargingTest {

    private double euRatio;
    private double aeRatio;
    private int rfRatio;

    @Before
    public void setConversions() {
        euRatio = PowerUnits.EU.conversionRatio;
        aeRatio = PowerUnits.AE.conversionRatio;
        rfRatio = GregTechAPI.mEUtoRF;
        PowerUnits.EU.conversionRatio = 2;
        PowerUnits.AE.conversionRatio = 1;
        GregTechAPI.mEUtoRF = 360;
    }

    @After
    public void restoreConversions() {
        PowerUnits.EU.conversionRatio = euRatio;
        PowerUnits.AE.conversionRatio = aeRatio;
        GregTechAPI.mEUtoRF = rfRatio;
    }

    @Test
    public void playerInventoryCollectsNativeAeItems() throws Exception {
        ItemStack ae = new ItemStack(new FakeAePowerItem());
        IInventory inventory = (IInventory) Proxy.newProxyInstance(
            IInventory.class.getClassLoader(),
            new Class<?>[] { IInventory.class },
            (proxy, method, args) -> method.getName()
                .equals("getSizeInventory") ? 1 : ae);
        List<ItemStack> targets = new ArrayList<>();
        Method collect = MonitorChargingHandler.class
            .getDeclaredMethod("collect", IInventory.class, List.class, List.class, boolean.class);
        collect.setAccessible(true);
        collect.invoke(null, inventory, targets, new ArrayList<ItemStack>(), true);
        assertEquals(1, targets.size());
        assertSame(ae, targets.get(0));
    }

    @Test
    public void aePartialAcceptanceDebitsOnlyActualFractionalEuAndChargesOnce() throws Exception {
        FakeAePowerItem item = new FakeAePowerItem();
        item.acceptance = 3.5;
        ItemStack stack = new ItemStack(item);
        ChargingTarget target = new ChargingTarget(stack, 1);
        NBTTagCompound cache = new NBTTagCompound();
        cache.setDouble("Charge", 100);
        cache.setDouble("Capacity", 100);
        cache.setInteger("Tier", 1);
        Method charge = MonitorChargingHandler.class
            .getDeclaredMethod("chargeTargets", NBTTagCompound.class, List.class, int.class);
        charge.setAccessible(true);
        assertEquals(true, charge.invoke(null, cache, Collections.singletonList(target), 0));
        assertEquals(98.25, cache.getDouble("Charge"), 0);
        assertEquals(3.5, item.getAECurrentPower(stack), 0);
        assertEquals(0, target.charge(100, 1), 0);
        assertEquals(1, item.injections);
    }

    @Test
    public void fullOrReadOnlyAeNeverInjects() {
        FakeAePowerItem item = new FakeAePowerItem();
        item.flow = AccessRestriction.READ;
        ItemStack stack = new ItemStack(item);
        assertEquals(0, new ChargingTarget(stack, 1).charge(100, 1), 0);
        item.flow = AccessRestriction.WRITE;
        item.capacity = 0;
        assertEquals(0, new ChargingTarget(stack, 20).charge(100, 1), 0);
        assertEquals(0, item.injections);
    }

    @Test
    public void aeUsesConfiguredRatioAndTwentyTickBudgetWithoutMutatingSimulation() {
        PowerUnits.EU.conversionRatio = 3;
        PowerUnits.AE.conversionRatio = 2;
        FakeAePowerItem item = new FakeAePowerItem();
        ItemStack stack = new ItemStack(item);
        assertEquals(640, new ChargingTarget(stack, 20).charge(1000, 1), 0);
        assertEquals(960, item.getAECurrentPower(stack), 0);
        assertEquals(1, item.injections);
        assertEquals(40 / 1.5, new ChargingTarget(stack, 20).charge(1000, 1), 0.0000001);
    }

    @Test
    public void invalidAeConversionAndNonfiniteCacheNeverInject() {
        FakeAePowerItem item = new FakeAePowerItem();
        ItemStack stack = new ItemStack(item);
        PowerUnits.EU.conversionRatio = Double.NaN;
        assertEquals(0, new ChargingTarget(stack, 1).charge(100, 1), 0);
        PowerUnits.EU.conversionRatio = 2;
        assertEquals(0, new ChargingTarget(stack, 1).charge(Double.NaN, 1), 0);
        assertEquals(0, new ChargingTarget(stack, 1).charge(Double.POSITIVE_INFINITY, 1), 0);
        assertEquals(0, item.injections);
    }

    @Test
    public void aeWinsWhenNativeRfAndIc2AreAllPresent() {
        DualPowerItem item = new DualPowerItem();
        ItemStack stack = new ItemStack(item);
        assertEquals(32, target(stack, 1, true).charge(100, 1), 0);
        assertEquals(64, item.ae.getAECurrentPower(stack), 0);
        assertEquals(0, item.rfCalls);
        assertEquals(0, item.managerLookups);
    }

    @Test
    public void rfWinsOverIc2AndDisabledRfUsesIc2() {
        RfPowerItem item = new RfPowerItem();
        ItemStack stack = new ItemStack(item);
        assertEquals(115 / 3.6, target(stack, 1, true).charge(100, 1), 0.0000001);
        assertEquals(115, item.getEnergyStored(stack));
        assertEquals(0, item.managerLookups);
        assertEquals(32, target(stack, 1, false).charge(100, 1), 0);
        assertEquals(1, item.managerLookups);
    }

    @Test
    public void rfOnlyItemChargesWhenOptionalIntegrationIsEnabled() {
        RfOnlyPowerItem item = new RfOnlyPowerItem();
        ItemStack stack = new ItemStack(item);
        assertNull(ItemCharging.adapter(stack, false));
        assertEquals(115 / 3.6, target(stack, 1, true).charge(100, 1), 0.0000001);
        assertEquals(115, item.getEnergyStored(stack));
    }

    @Test
    public void rfSingleCallLimitIsReplayedForTwentyTicksAndCacheCapsTotal() {
        RfPowerItem item = new RfPowerItem();
        item.transfer = 10;
        ItemStack stack = new ItemStack(item);
        assertEquals(200 / 3.6, target(stack, 20, true).charge(1000, 1), 0.0000001);
        assertEquals(200, item.getEnergyStored(stack));
        assertEquals(20, item.rfCalls);
        assertEquals(1, item.simulatedCalls);
        item.transfer = 1000;
        ItemStack other = new ItemStack(item);
        assertEquals(3 / 3.6, target(other, 20, true).charge(1, 1), 0.0000001);
        assertEquals(3, item.getEnergyStored(other));
    }

    @Test
    public void rfRejectsFullStorageAndTooSmallFractionalBudget() {
        RfPowerItem item = new RfPowerItem();
        item.capacity = 0;
        ChargingTarget target = target(new ItemStack(item), 1, true);
        for (int i = 0; i < 43; i++) assertEquals(0, target.charge(100, 1), 0);
        assertEquals(1, item.rfCalls);
        item.capacity = 1000;
        assertEquals(0, target(new ItemStack(item), 1, true).charge(0.1, 1), 0);
        assertEquals(1, item.rfCalls);
        GregTechAPI.mEUtoRF = 0;
        assertEquals(0, target(new ItemStack(item), 1, true).charge(100, 1), 0);
        assertEquals(1, item.rfCalls);
    }

    @Test
    public void rfFailureAfterSuccessfulBatchCallStillAccountsForDeliveredEnergy() {
        RfPowerItem item = new RfPowerItem();
        item.transfer = 10;
        item.failOnCall = 2;
        ItemStack stack = new ItemStack(item);
        assertEquals(10 / 3.6, target(stack, 20, true).charge(1000, 1), 0.0000001);
        assertEquals(10, item.getEnergyStored(stack));
        assertEquals(2, item.rfCalls);
    }

    @Test
    public void missingRfApiLoadsSafely() throws Exception {
        String className = ItemCharging.Rf.class.getName();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream stream = ItemCharging.class.getResourceAsStream("ItemCharging$Rf.class")) {
            byte[] buffer = new byte[4096];
            for (int count; (count = stream.read(buffer)) >= 0;) bytes.write(buffer, 0, count);
        }
        byte[] compiled = bytes.toByteArray();
        ClassLoader noRf = new ClassLoader(ItemCharging.class.getClassLoader()) {

            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("cofh.")) throw new ClassNotFoundException(name);
                if (name.equals(className)) return defineClass(name, compiled, 0, compiled.length);
                return super.loadClass(name, resolve);
            }
        };
        Class<?> bridge = Class.forName(className, true, noRf);
        Method supports = bridge.getDeclaredMethod("supports", ItemStack.class);
        supports.setAccessible(true);
        assertEquals(false, supports.invoke(null, new ItemStack(new Item())));
        assertNull(ItemCharging.adapter(new ItemStack(new Item()), false));
        assertFalse(ItemCharging.supports(null));
        assertTrue(ItemCharging.supports(new ItemStack(new FakeAePowerItem())));
    }

    private static ChargingTarget target(ItemStack stack, int ticks, boolean rfLoaded) {
        return new ChargingTarget(stack, ticks, ItemCharging.adapter(stack, rfLoaded));
    }

    public static class RfPowerItem extends FakeRechargeableBattery implements IEnergyContainerItem {

        int transfer = 1000;
        int capacity = 10000;
        int rfCalls;
        int simulatedCalls;
        int managerLookups;
        int failOnCall;

        @Override
        public int receiveEnergy(ItemStack stack, int amount, boolean simulate) {
            if (simulate) simulatedCalls++;
            else rfCalls++;
            if (!simulate && rfCalls == failOnCall) throw new IllegalStateException("Rejected batch call");
            int accepted = Math.min(amount, Math.min(transfer, capacity - getEnergyStored(stack)));
            if (!simulate) {
                if (!stack.hasTagCompound()) stack.setTagCompound(new NBTTagCompound());
                stack.getTagCompound()
                    .setInteger("rf", getEnergyStored(stack) + accepted);
            }
            return accepted;
        }

        @Override
        public int extractEnergy(ItemStack stack, int amount, boolean simulate) {
            return 0;
        }

        @Override
        public int getEnergyStored(ItemStack stack) {
            return stack.hasTagCompound() ? stack.getTagCompound()
                .getInteger("rf") : 0;
        }

        @Override
        public int getMaxEnergyStored(ItemStack stack) {
            return capacity;
        }

        @Override
        public IElectricItemManager getManager(ItemStack stack) {
            managerLookups++;
            return super.getManager(stack);
        }
    }

    public static class RfOnlyPowerItem extends Item implements IEnergyContainerItem {

        private final RfPowerItem rf = new RfPowerItem();

        @Override
        public int receiveEnergy(ItemStack stack, int amount, boolean simulate) {
            return rf.receiveEnergy(stack, amount, simulate);
        }

        @Override
        public int extractEnergy(ItemStack stack, int amount, boolean simulate) {
            return 0;
        }

        @Override
        public int getEnergyStored(ItemStack stack) {
            return rf.getEnergyStored(stack);
        }

        @Override
        public int getMaxEnergyStored(ItemStack stack) {
            return rf.getMaxEnergyStored(stack);
        }
    }

    private static class DualPowerItem extends RfPowerItem implements IAEItemPowerStorage {

        final FakeAePowerItem ae = new FakeAePowerItem();

        @Override
        public double injectAEPower(ItemStack stack, double amount) {
            return ae.injectAEPower(stack, amount);
        }

        @Override
        public double extractAEPower(ItemStack stack, double amount) {
            return ae.extractAEPower(stack, amount);
        }

        @Override
        public double getAEMaxPower(ItemStack stack) {
            return ae.getAEMaxPower(stack);
        }

        @Override
        public double getAECurrentPower(ItemStack stack) {
            return ae.getAECurrentPower(stack);
        }

        @Override
        public AccessRestriction getPowerFlow(ItemStack stack) {
            return ae.getPowerFlow(stack);
        }
    }
}
