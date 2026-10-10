package com.miaokatze.gtswn.common.charging;

import static org.junit.Assert.assertEquals;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;

import org.junit.Test;

import ic2.api.item.IElectricItemManager;
import ic2.test.FakeRechargeableBattery;

public class ChargingTargetTest {

    @Test
    public void directChargeKeepsNormalTransferAndOnlyOneTerminalPaysPerTick() {
        CountingBattery item = new CountingBattery();
        ItemStack stack = new ItemStack(item);
        ChargingTarget target = new ChargingTarget(stack, 1);
        assertEquals(32, target.charge(1000, 1), 0);
        assertEquals(0, target.charge(1000, 1), 0);
        assertEquals(32, item.delegate.getCharge(stack), 0);
        assertEquals(1, item.calls);
        assertEquals(1, item.lookups);
    }

    @Test
    public void nestedPassUsesTwentyNormalTicksAndRespectsCache() {
        CountingBattery item = new CountingBattery();
        ItemStack stack = new ItemStack(item);
        assertEquals(640, new ChargingTarget(stack, 20).charge(1000, 1), 0);
        assertEquals(360, new ChargingTarget(stack, 20).charge(360, 1), 0);
        assertEquals(1000, item.delegate.getCharge(stack), 0);
    }

    @Test
    public void fullTargetRejectProbeIsSharedAcrossFortyThreeTerminals() {
        CountingBattery item = new CountingBattery();
        ItemStack stack = new ItemStack(item);
        FakeRechargeableBattery.setCharge(stack, 1000);
        ChargingTarget target = new ChargingTarget(stack, 1);
        for (int i = 0; i < 43; i++) assertEquals(0, target.charge(1000, 1), 0);
        assertEquals(1, item.calls);
        assertEquals(1, item.lookups);
    }

    @Test
    public void rejectedLowTierDoesNotBlockHigherTierOrNextTick() {
        CountingBattery item = new CountingBattery();
        ItemStack stack = new ItemStack(item);
        ChargingTarget target = new ChargingTarget(stack, 1);
        assertEquals(0, target.charge(1000, 0), 0);
        assertEquals(32, target.charge(1000, 1), 0);
        assertEquals(32, new ChargingTarget(stack, 1).charge(1000, 1), 0);
    }

    @Test
    public void largerCacheRetriesAnEarlierRejectedOffer() {
        CountingBattery item = new CountingBattery();
        item.minimum = 16;
        ItemStack stack = new ItemStack(item);
        ChargingTarget target = new ChargingTarget(stack, 1);
        assertEquals(0, target.charge(1, 1), 0);
        assertEquals(0, target.charge(1, 1), 0);
        assertEquals(32, target.charge(1000, 1), 0);
        assertEquals(2, item.calls);
    }

    @Test
    public void terminalsWithoutInstalledBatteryOnlyScanOncePerSecond() {
        int idle = 0;
        int active = 0;
        for (int tick = 0; tick < 200; tick++) {
            if (MonitorChargingHandler.shouldScan(false, tick)) idle++;
            if (MonitorChargingHandler.shouldScan(true, tick)) active++;
        }
        assertEquals(10, idle);
        assertEquals(200, active);
    }

    private static class CountingBattery extends FakeRechargeableBattery {

        int calls;
        int lookups;
        double minimum;
        final IElectricItemManager delegate = super.getManager(null);
        final IElectricItemManager counting = new IElectricItemManager() {

            @Override
            public double charge(ItemStack stack, double amount, int tier, boolean ignore, boolean simulate) {
                calls++;
                return amount < minimum ? 0 : delegate.charge(stack, amount, tier, ignore, simulate);
            }

            @Override
            public double discharge(ItemStack stack, double amount, int tier, boolean ignore, boolean external,
                boolean simulate) {
                return delegate.discharge(stack, amount, tier, ignore, external, simulate);
            }

            @Override
            public double getCharge(ItemStack stack) {
                return delegate.getCharge(stack);
            }

            @Override
            public boolean canUse(ItemStack stack, double amount) {
                return delegate.canUse(stack, amount);
            }

            @Override
            public boolean use(ItemStack stack, double amount, EntityLivingBase entity) {
                return false;
            }

            @Override
            public void chargeFromArmor(ItemStack stack, EntityLivingBase entity) {}

            @Override
            public String getToolTip(ItemStack stack) {
                return null;
            }
        };

        @Override
        public IElectricItemManager getManager(ItemStack stack) {
            lookups++;
            return counting;
        }
    }
}
