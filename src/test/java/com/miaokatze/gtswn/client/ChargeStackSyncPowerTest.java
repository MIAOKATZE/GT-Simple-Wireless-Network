package com.miaokatze.gtswn.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.google.common.base.Optional;
import com.miaokatze.gtswn.common.charging.MonitorBattery;
import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;

import appeng.items.tools.powered.powersink.AERootPoweredItem;
import cofh.api.energy.IEnergyContainerItem;
import gregtech.common.items.ItemGTToolbox;

public class ChargeStackSyncPowerTest {

    @Test
    public void travelStaffChargingReplaysEnergyAndItsDamageBarWithoutChangingInputs() {
        TravelStaff item = new TravelStaff();
        ItemStack current = new ItemStack(item);
        item.receiveEnergy(current, 100, false);
        ItemStack incoming = current.copy();
        item.receiveEnergy(incoming, 100, false);
        assertTrue(ChargeStackSync.isChargeOnlyUpdate(current, incoming));
        assertEquals(100, item.getEnergyStored(current));
        assertEquals(15, current.getItemDamage());
        assertEquals(200, item.getEnergyStored(incoming));
        assertEquals(13, incoming.getItemDamage());
    }

    @Test
    public void nativeAeChargeWithoutIc2InterfacePreservesHeldStack() {
        ItemStack current = new ItemStack(new NativeAeItem());
        current.setTagCompound(new NBTTagCompound());
        current.getTagCompound()
            .setDouble("internalCurrentPower", 10);
        ItemStack incoming = current.copy();
        incoming.getTagCompound()
            .setDouble("internalCurrentPower", 42.5);
        assertTrue(ChargeStackSync.isChargeOnlyUpdate(current, incoming));
        assertEquals(
            10,
            current.getTagCompound()
                .getDouble("internalCurrentPower"),
            0);
        assertEquals(
            42.5,
            incoming.getTagCompound()
                .getDouble("internalCurrentPower"),
            0);
    }

    @Test
    public void chargerKeepsOriginalHeldObjectAndCopiesAuthoritativeRfData() throws Exception {
        TravelStaff item = new TravelStaff();
        ItemStack current = new ItemStack(item);
        item.receiveEnergy(current, 100, false);
        ItemStack incoming = current.copy();
        item.receiveEnergy(incoming, 100, false);
        incoming.animationsToGo = 3;
        EntityPlayer player = playerWithCharger();
        assertSame(current, ChargeStackSync.mergeHeldCharge(player, current, incoming));
        assertTrue(ItemStack.areItemStacksEqual(current, incoming));
        assertEquals(3, current.animationsToGo);
        incoming.getTagCompound()
            .setInteger("Energy", 400);
        assertEquals(200, item.getEnergyStored(current));
    }

    @Test
    public void rejectsRfModeBindingToolboxAndOtherNbtChangesAlongsideCharging() {
        TravelStaff item = new TravelStaff();
        ItemStack current = new ItemStack(item);
        item.receiveEnergy(current, 100, false);
        for (String key : new String[] { "mode", "owner", "boundDestination", "GT.ToolStats",
            ItemGTToolbox.CONTENTS_KEY, MonitorBattery.NBT_BATTERY }) {
            ItemStack incoming = current.copy();
            item.receiveEnergy(incoming, 100, false);
            incoming.getTagCompound()
                .setString(key, "changed");
            assertFalse(key, ChargeStackSync.isChargeOnlyUpdate(current, incoming));
            assertEquals(100, item.getEnergyStored(current));
        }
    }

    @Test
    public void rejectsSameRfEnergyWithChangedDamageAndInconsistentChargedDamage() {
        TravelStaff item = new TravelStaff();
        ItemStack current = new ItemStack(item);
        item.receiveEnergy(current, 100, false);
        ItemStack incoming = current.copy();
        incoming.setItemDamage(14);
        assertFalse(ChargeStackSync.isChargeOnlyUpdate(current, incoming));
        item.receiveEnergy(incoming, 100, false);
        incoming.setItemDamage(12);
        assertFalse(ChargeStackSync.isChargeOnlyUpdate(current, incoming));
    }

    @Test
    public void rejectsTrueRfToolWearAndSubtypeChangesAlongsideCharging() {
        TravelStaff item = new TravelStaff() {

            @Override
            public int receiveEnergy(ItemStack stack, int amount, boolean simulate) {
                int damage = stack.getItemDamage();
                int accepted = super.receiveEnergy(stack, amount, simulate);
                stack.setItemDamage(damage);
                return accepted;
            }
        };
        item.setHasSubtypes(true);
        ItemStack current = new ItemStack(item, 1, 4);
        item.receiveEnergy(current, 100, false);
        ItemStack incoming = current.copy();
        item.receiveEnergy(incoming, 100, false);
        assertTrue(ChargeStackSync.isChargeOnlyUpdate(current, incoming));
        incoming.setItemDamage(5);
        assertFalse(ChargeStackSync.isChargeOnlyUpdate(current, incoming));
    }

    @Test
    public void rejectsReplacementCountAndOrdinaryItemsWithEnergyNamedTags() {
        TravelStaff item = new TravelStaff();
        ItemStack current = new ItemStack(item);
        item.receiveEnergy(current, 100, false);
        ItemStack incoming = current.copy();
        item.receiveEnergy(incoming, 100, false);
        incoming.stackSize = 2;
        assertFalse(ChargeStackSync.isChargeOnlyUpdate(current, incoming));
        assertFalse(ChargeStackSync.isChargeOnlyUpdate(current, new ItemStack(new TravelStaff())));
        ItemStack ordinary = new ItemStack(new Item());
        ordinary.setTagCompound(new NBTTagCompound());
        ItemStack changed = ordinary.copy();
        changed.getTagCompound()
            .setInteger("Energy", 100);
        assertFalse(ChargeStackSync.isChargeOnlyUpdate(ordinary, changed));
    }

    @Test
    public void rfReplayHandlesChargeTagCreationAndDecreasingAuthoritativeEnergy() {
        TravelStaff item = new TravelStaff();
        ItemStack current = new ItemStack(item);
        ItemStack incoming = current.copy();
        item.receiveEnergy(incoming, 100, false);
        assertTrue(ChargeStackSync.isChargeOnlyUpdate(current, incoming));
        assertFalse(current.hasTagCompound());
        item.receiveEnergy(current, 200, false);
        assertTrue(ChargeStackSync.isChargeOnlyUpdate(current, incoming));
        assertEquals(200, item.getEnergyStored(current));
        assertEquals(100, item.getEnergyStored(incoming));
    }

    @Test
    public void rfReplayBudgetAndFailedApiKeepBothInputsUnchanged() {
        TravelStaff item = new TravelStaff();
        ItemStack current = new ItemStack(item);
        item.receiveEnergy(current, 100, false);
        ItemStack incoming = current.copy();
        item.receiveEnergy(incoming, 100, false);
        item.transfer = 5;
        item.calls = 0;
        assertTrue(ChargeStackSync.isChargeOnlyUpdate(current, incoming));
        assertEquals(20, item.calls);
        item.transfer = 4;
        item.calls = 0;
        assertFalse(ChargeStackSync.isChargeOnlyUpdate(current, incoming));
        assertEquals(20, item.calls);
        item.fail = true;
        assertFalse(ChargeStackSync.isChargeOnlyUpdate(current, incoming));
        assertEquals(100, item.getEnergyStored(current));
        assertEquals(200, item.getEnergyStored(incoming));
        assertEquals(15, current.getItemDamage());
        assertEquals(13, incoming.getItemDamage());
    }

    @Test
    public void aeReplayRejectsOtherNbtAndMetadataChanges() throws Exception {
        ItemStack current = new ItemStack(new NativeAeItem());
        current.setTagCompound(new NBTTagCompound());
        current.getTagCompound()
            .setDouble("internalCurrentPower", 10);
        for (String key : new String[] { "internalMaxPower", "mode", "encryptionKey", "boundNetwork" }) {
            ItemStack incoming = current.copy();
            incoming.getTagCompound()
                .setDouble("internalCurrentPower", 20);
            incoming.getTagCompound()
                .setString(key, "changed");
            assertFalse(key, ChargeStackSync.isChargeOnlyUpdate(current, incoming));
        }
        ItemStack changedMetadata = current.copy();
        changedMetadata.getTagCompound()
            .setDouble("internalCurrentPower", 20);
        // AERootPoweredItem.setDamage deliberately does nothing. Model packet metadata directly.
        Field damage = ItemStack.class.getDeclaredField("itemDamage");
        damage.setAccessible(true);
        damage.setInt(changedMetadata, 1);
        assertFalse(ChargeStackSync.isChargeOnlyUpdate(current, changedMetadata));
        ItemStack samePower = current.copy();
        samePower.getTagCompound()
            .setString("boundNetwork", "changed");
        assertFalse(ChargeStackSync.isChargeOnlyUpdate(current, samePower));
        assertEquals(
            10,
            current.getTagCompound()
                .getDouble("internalCurrentPower"),
            0);
    }

    @Test
    public void aeReplayAcceptsLowerAuthoritativePowerButRejectsPartialReception() {
        NativeAeItem item = new NativeAeItem();
        ItemStack current = new ItemStack(item);
        current.setTagCompound(new NBTTagCompound());
        current.getTagCompound()
            .setDouble("internalCurrentPower", 42.5);
        ItemStack incoming = current.copy();
        incoming.getTagCompound()
            .setDouble("internalCurrentPower", 10);
        assertTrue(ChargeStackSync.isChargeOnlyUpdate(current, incoming));
        assertEquals(
            42.5,
            current.getTagCompound()
                .getDouble("internalCurrentPower"),
            0);
        assertEquals(
            10,
            incoming.getTagCompound()
                .getDouble("internalCurrentPower"),
            0);

        NativeAeItem partial = new NativeAeItem() {

            @Override
            public double injectAEPower(ItemStack stack, double amount) {
                return amount / 2 + super.injectAEPower(stack, amount / 2);
            }
        };
        current = new ItemStack(partial);
        current.setTagCompound(new NBTTagCompound());
        current.getTagCompound()
            .setDouble("internalCurrentPower", 10);
        incoming = current.copy();
        incoming.getTagCompound()
            .setDouble("internalCurrentPower", 42.5);
        assertFalse(ChargeStackSync.isChargeOnlyUpdate(current, incoming));
        assertEquals(
            10,
            current.getTagCompound()
                .getDouble("internalCurrentPower"),
            0);
        assertEquals(
            42.5,
            incoming.getTagCompound()
                .getDouble("internalCurrentPower"),
            0);
    }

    @Test
    public void invalidPowerValuesAndInconsistentRfReceptionKeepOrdinarySync() {
        ItemStack ae = new ItemStack(new NativeAeItem());
        ae.setTagCompound(new NBTTagCompound());
        ItemStack incoming = ae.copy();
        incoming.getTagCompound()
            .setDouble("internalCurrentPower", 10);
        for (double value : new double[] { -1, Double.NaN, Double.POSITIVE_INFINITY }) {
            ae.getTagCompound()
                .setDouble("internalCurrentPower", value);
            assertFalse(ChargeStackSync.isChargeOnlyUpdate(ae, incoming));
        }
        TravelStaff item = new TravelStaff();
        ItemStack rf = new ItemStack(item);
        rf.setTagCompound(new NBTTagCompound());
        rf.getTagCompound()
            .setInteger("Energy", -1);
        incoming = new ItemStack(item);
        item.receiveEnergy(incoming, 100, false);
        assertFalse(ChargeStackSync.isChargeOnlyUpdate(rf, incoming));

        TravelStaff inconsistent = new TravelStaff() {

            @Override
            public int receiveEnergy(ItemStack stack, int amount, boolean simulate) {
                return super.receiveEnergy(stack, amount, simulate) + 1;
            }
        };
        rf = new ItemStack(inconsistent);
        inconsistent.receiveEnergy(rf, 100, false);
        incoming = rf.copy();
        inconsistent.receiveEnergy(incoming, 100, false);
        assertFalse(ChargeStackSync.isChargeOnlyUpdate(rf, incoming));
        assertEquals(100, inconsistent.getEnergyStored(rf));
        assertEquals(200, inconsistent.getEnergyStored(incoming));
    }

    @Test
    public void rfApiAbsenceIsSafe() throws Exception {
        String className = ChargeStackReplay.Rf.class.getName();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream stream = ChargeStackReplay.class.getResourceAsStream("ChargeStackReplay$Rf.class")) {
            byte[] buffer = new byte[4096];
            for (int count; (count = stream.read(buffer)) >= 0;) bytes.write(buffer, 0, count);
        }
        byte[] compiled = bytes.toByteArray();
        ClassLoader noRf = new ClassLoader(ChargeStackReplay.class.getClassLoader()) {

            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("cofh.")) throw new ClassNotFoundException(name);
                if (name.equals(className)) return defineClass(name, compiled, 0, compiled.length);
                return super.loadClass(name, resolve);
            }
        };
        Class<?> bridge = Class.forName(className, true, noRf);
        Method matches = bridge.getDeclaredMethod("matches", ItemStack.class, ItemStack.class);
        matches.setAccessible(true);
        ItemStack stack = new ItemStack(new Item());
        assertEquals(false, matches.invoke(null, stack, stack.copy()));
    }

    private static EntityPlayer playerWithCharger() throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeClass.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        EntityPlayer player = (EntityPlayer) unsafeClass.getMethod("allocateInstance", Class.class)
            .invoke(singleton.get(null), EntityPlayerMP.class);
        player.inventory = new InventoryPlayer(player);
        ItemStack monitor = new ItemStack(new PortableWirelessNetworkMonitor());
        monitor.setTagCompound(new NBTTagCompound());
        monitor.getTagCompound()
            .setTag(MonitorBattery.NBT_BATTERY, new NBTTagCompound());
        player.inventory.mainInventory[0] = monitor;
        return player;
    }

    /** Reproduces EnderIO 2.10.48 ItemTravelStaff's CoFH Energy key and 16-step damage bar. */
    public static class TravelStaff extends Item implements IEnergyContainerItem {

        int transfer = 1000;
        int calls;
        boolean fail;

        public TravelStaff() {
            setMaxDamage(16);
            setHasSubtypes(false);
        }

        @Override
        public int receiveEnergy(ItemStack stack, int amount, boolean simulate) {
            calls++;
            if (fail) throw new IllegalStateException("Unavailable charge API");
            int accepted = Math.min(amount, Math.min(transfer, 1000 - getEnergyStored(stack)));
            if (accepted > 0 && !simulate) {
                if (!stack.hasTagCompound()) stack.setTagCompound(new NBTTagCompound());
                stack.getTagCompound()
                    .setInteger("Energy", getEnergyStored(stack) + accepted);
                float ratio = (float) getEnergyStored(stack) / getMaxEnergyStored(stack);
                stack.setItemDamage(16 - (int) (ratio * 16));
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
                .getInteger("Energy") : 0;
        }

        @Override
        public int getMaxEnergyStored(ItemStack stack) {
            return 1000;
        }
    }

    private static class NativeAeItem extends AERootPoweredItem {

        NativeAeItem() {
            super(1000, Optional.absent());
        }
    }
}
