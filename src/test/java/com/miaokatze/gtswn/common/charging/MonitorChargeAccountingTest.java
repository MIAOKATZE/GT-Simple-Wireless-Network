package com.miaokatze.gtswn.common.charging;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ObjectIntIdentityMap;
import net.minecraft.util.RegistryNamespaced;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;
import com.miaokatze.gtswn.config.Config;

import cpw.mods.fml.common.registry.FMLControlledNamespacedRegistry;
import gregtech.api.items.MetaBaseItem;
import gregtech.common.misc.GlobalEnergyWorldSavedData;
import gregtech.common.misc.GlobalVariableStorage;
import gregtech.common.misc.spaceprojects.SpaceProjectManager;
import ic2.api.item.IElectricItemManager;
import ic2.test.FakeRechargeableBattery;

public class MonitorChargeAccountingTest {

    private static final PortableWirelessNetworkMonitor MONITOR = new PortableWirelessNetworkMonitor();
    private static final FakeRechargeableBattery IC2_BATTERY = new FakeRechargeableBattery();
    private static final FakeRechargeableBattery WIDE_BATTERY = new FakeRechargeableBattery() {

        @Override
        public double getMaxCharge(ItemStack stack) {
            return Double.MAX_VALUE;
        }
    };
    private static LongBattery gtBattery;
    private static Field registryIdsField;
    private static ObjectIntIdentityMap previousRegistryIds;
    private static final BigDecimal CAPACITY = BigDecimal.valueOf(Long.MAX_VALUE);

    @BeforeClass
    public static void fixtures() throws Exception {
        // Avoid GTGenericItem's mod-registration constructor, as in other isolated item fixtures.
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        gtBattery = (LongBattery) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(LongBattery.class);
        // Unsafe also skips Item's Forge delegate initializer; restore the native delegate, not ItemStack behavior.
        Field delegate = Item.class.getDeclaredField("delegate");
        delegate.setAccessible(true);
        delegate.set(
            gtBattery,
            ((FMLControlledNamespacedRegistry<Item>) Item.itemRegistry).getDelegate(gtBattery, Item.class));
        assertSame(gtBattery, new ItemStack(gtBattery).getItem());
        for (Field field : RegistryNamespaced.class.getDeclaredFields()) {
            if (field.getType() == ObjectIntIdentityMap.class) {
                field.setAccessible(true);
                registryIdsField = field;
            }
        }
        assertNotNull(registryIdsField);
        previousRegistryIds = (ObjectIntIdentityMap) registryIdsField.get(Item.itemRegistry);
        ObjectIntIdentityMap ids = new ObjectIntIdentityMap();
        for (Object item : previousRegistryIds) ids.func_148746_a(item, previousRegistryIds.func_148747_b(item));
        registryIdsField.set(Item.itemRegistry, ids);
        ids.func_148746_a(MONITOR, 31011);
        ids.func_148746_a(IC2_BATTERY, 31012);
        ids.func_148746_a(gtBattery, 31013);
        ids.func_148746_a(WIDE_BATTERY, 31014);
    }

    @AfterClass
    public static void restoreFixtureIds() throws Exception {
        if (previousRegistryIds != null) {
            registryIdsField.set(Item.itemRegistry, previousRegistryIds);
            previousRegistryIds = null;
        }
    }

    @Test
    public void lowTierDeliveryDebitsEveryEuAcrossSaveReload() throws Exception {
        ItemStack monitor = installed();
        Method charge = MonitorChargingHandler.class
            .getDeclaredMethod("chargeTargets", NBTTagCompound.class, List.class, int.class);
        charge.setAccessible(true);
        final long[] delivered = { 0 };
        for (int tick = 0; tick < 400; tick++) {
            ChargingTarget target = new ChargingTarget(new ItemStack(IC2_BATTERY), 1, (s, amount, tier, ticks, sim) -> {
                delivered[0] += (long) amount;
                return amount;
            });
            assertEquals(
                true,
                charge.invoke(null, MonitorBattery.data(monitor), Collections.singletonList(target), tick));
            if (tick % 17 == 0) monitor = reload(monitor);
        }
        assertEquals(12800L, delivered[0]);
        assertDecimal(
            CAPACITY.subtract(BigDecimal.valueOf(delivered[0])),
            BatteryCharge.get(MonitorBattery.data(monitor)));
        assertEquals(Long.MAX_VALUE - delivered[0], gtBattery.getRealCharge(MonitorBattery.remove(monitor)));
    }

    @Test
    public void partiallyUsedNativeLongImportsWithoutRoundingUp() {
        ItemStack battery = new ItemStack(gtBattery);
        gtBattery.setCharge(battery, Long.MAX_VALUE - 257);
        ItemStack monitor = MonitorBattery.install(new ItemStack(MONITOR), battery);
        assertDecimal(CAPACITY.subtract(BigDecimal.valueOf(257)), BatteryCharge.get(MonitorBattery.data(monitor)));
        assertEquals(Long.MAX_VALUE - 257, gtBattery.getRealCharge(MonitorBattery.remove(monitor)));
    }

    @Test
    public void nativeLongInstallationAndRemovalNeverPassThroughDouble() throws Exception {
        ItemStack monitor = installed();
        IElectricItemManager manager = MONITOR.getManager(monitor);
        ItemStack before = monitor.copy();
        assertEquals(32, manager.discharge(monitor, 32, 1, true, false, true), 0);
        assertTrue(ItemStack.areItemStacksEqual(before, monitor));
        assertEquals(32, manager.discharge(monitor, 32, 1, true, false, false), 0);
        assertDecimal(CAPACITY.subtract(BigDecimal.valueOf(32)), BatteryCharge.get(MonitorBattery.data(monitor)));
        assertFalse(manager.canUse(monitor, (double) Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE - 32, gtBattery.getRealCharge(MonitorBattery.remove(reload(monitor))));
        assertEquals(32, manager.charge(monitor, Double.POSITIVE_INFINITY, 1, true, true), 0);
        assertEquals(32, manager.charge(monitor, Double.POSITIVE_INFINITY, 1, true, false), 0);
        assertDecimal(CAPACITY, BatteryCharge.get(MonitorBattery.data(monitor)));
    }

    @Test
    public void fractionalNativeExportCarriesItsRemainderOnThePhysicalBattery() throws Exception {
        ItemStack monitor = installed();
        assertEquals(
            0.25,
            MONITOR.getManager(monitor)
                .discharge(monitor, 0.25, 1, true, false, false),
            0);
        ItemStack battery = MonitorBattery.remove(reload(monitor));
        assertEquals(Long.MAX_VALUE - 1, gtBattery.getRealCharge(battery));
        assertDecimal(new BigDecimal("0.75"), BatteryCharge.remainder(battery));
        ItemStack reinstalled = MonitorBattery.install(new ItemStack(MONITOR), reload(battery));
        assertDecimal(CAPACITY.subtract(new BigDecimal("0.25")), BatteryCharge.get(MonitorBattery.data(reinstalled)));
        assertDecimal(
            BigDecimal.ZERO,
            BatteryCharge.remainder(
                ItemStack.loadItemStackFromNBT(
                    MonitorBattery.data(reinstalled)
                        .getCompoundTag("Item"))));
        assertFalse(MonitorBattery.hasBattery(MonitorBattery.withoutBattery(monitor)));
    }

    @Test
    public void externalNativeTransfersNeitherDuplicateNorDiscardFractionalEscrow() throws Exception {
        ItemStack monitor = installed();
        MONITOR.getManager(monitor)
            .use(monitor, 0.25, null);
        ItemStack battery = MonitorBattery.remove(monitor);
        assertEquals(1, gtBattery.charge(battery, 1, 1, true, false), 0);
        ItemStack full = MonitorBattery.install(new ItemStack(MONITOR), reload(battery));
        assertDecimal(CAPACITY, BatteryCharge.get(MonitorBattery.data(full)));
        ItemStack returned = MonitorBattery.remove(reload(full));
        assertEquals(Long.MAX_VALUE, gtBattery.getRealCharge(returned));
        assertDecimal(new BigDecimal("0.75"), BatteryCharge.remainder(returned));
        assertEquals(64, gtBattery.discharge(returned, 64, 1, true, false, false), 0);
        ItemStack reinstalled = MonitorBattery.install(new ItemStack(MONITOR), reload(returned));
        assertDecimal(CAPACITY.subtract(new BigDecimal("63.25")), BatteryCharge.get(MonitorBattery.data(reinstalled)));
        assertDecimal(
            BigDecimal.ZERO,
            BatteryCharge.remainder(
                ItemStack.loadItemStackFromNBT(
                    MonitorBattery.data(reinstalled)
                        .getCompoundTag("Item"))));
        ItemStack exported = MonitorBattery.remove(reinstalled);
        assertEquals(Long.MAX_VALUE - 64, gtBattery.getRealCharge(exported));
        assertDecimal(new BigDecimal("0.75"), BatteryCharge.remainder(exported));
    }

    @Test
    public void fractionalApiChargesDoNotRoundAwayAtLargeBalances() {
        ItemStack monitor = installed();
        IElectricItemManager manager = MONITOR.getManager(monitor);
        manager.use(monitor, 32, null);
        for (int i = 0; i < 128; i++) assertEquals(0.25, manager.charge(monitor, 0.25, 1, true, false), 0);
        assertDecimal(CAPACITY, BatteryCharge.get(MonitorBattery.data(monitor)));
        assertEquals(0, manager.charge(monitor, 0.25, 1, true, false), 0);
    }

    @Test
    public void foreignFractionalBatteryAndLegacyDoubleSavesRemainCompatible() throws Exception {
        ItemStack nativeBattery = new ItemStack(IC2_BATTERY);
        FakeRechargeableBattery.setCharge(nativeBattery, 321.125);
        ItemStack monitor = MonitorBattery.install(new ItemStack(MONITOR), nativeBattery);
        NBTTagCompound data = MonitorBattery.data(monitor);
        data.removeTag("CapacityExact");
        data.removeTag("ChargeExact");
        ItemStack before = monitor.copy();
        assertEquals(
            321.125,
            MONITOR.getManager(monitor)
                .getCharge(monitor),
            0);
        assertTrue(ItemStack.areItemStacksEqual(before, monitor));
        assertTrue(
            MONITOR.getManager(monitor)
                .use(monitor, 0.125, null));
        assertDecimal(new BigDecimal("321"), BatteryCharge.get(MonitorBattery.data(reload(monitor))));
        assertEquals(
            321,
            IC2_BATTERY.getManager(nativeBattery)
                .getCharge(MonitorBattery.remove(monitor)),
            0);
    }

    @Test
    public void foreignDoubleCapacityIsNotNarrowedToLongAndExportProjectionLosesNothing() throws Exception {
        ItemStack battery = new ItemStack(WIDE_BATTERY);
        FakeRechargeableBattery.setCharge(battery, Double.MAX_VALUE);
        ItemStack monitor = MonitorBattery.install(new ItemStack(MONITOR), battery);
        BigDecimal capacity = new BigDecimal(Double.MAX_VALUE);
        assertDecimal(capacity, BatteryCharge.capacity(MonitorBattery.data(monitor)));
        assertTrue(
            MONITOR.getManager(monitor)
                .use(monitor, 32, null));
        BigDecimal expected = capacity.subtract(BigDecimal.valueOf(32));
        ItemStack exported = MonitorBattery.remove(reload(monitor));
        assertEquals(
            Math.nextDown(Double.MAX_VALUE),
            WIDE_BATTERY.getManager(exported)
                .getCharge(exported),
            0);
        assertDecimal(
            expected,
            BatteryCharge.nativeCharge(exported)
                .add(BatteryCharge.remainder(exported)));
        ItemStack reinstalled = MonitorBattery.install(new ItemStack(MONITOR), reload(exported));
        assertDecimal(expected, BatteryCharge.get(MonitorBattery.data(reinstalled)));
    }

    @Test
    public void smallestDoubleTransfersSurviveRepeatedWidestCapacityExportAndReload() throws Exception {
        ItemStack battery = new ItemStack(WIDE_BATTERY);
        FakeRechargeableBattery.setCharge(battery, Double.MAX_VALUE);
        ItemStack monitor = MonitorBattery.install(new ItemStack(MONITOR), battery);
        BigDecimal expected = new BigDecimal(Double.MAX_VALUE);
        for (int i = 0; i < 64; i++) {
            assertTrue(
                MONITOR.getManager(monitor)
                    .use(monitor, Double.MIN_VALUE, null));
            expected = expected.subtract(new BigDecimal(Double.MIN_VALUE));
            monitor = reload(MonitorBattery.install(new ItemStack(MONITOR), reload(MonitorBattery.remove(monitor))));
            assertDecimal(expected, BatteryCharge.get(MonitorBattery.data(monitor)));
            assertTrue(
                MonitorBattery.data(monitor)
                    .getString("ChargeExact")
                    .length() <= 1400);
        }
    }

    @Test
    public void exactFieldsRejectExponentAndOversizedInputWithoutGrowingThePersistedLedger() {
        ItemStack monitor = installed();
        NBTTagCompound data = MonitorBattery.data(monitor);
        for (String invalid : new String[] { "1E2147483647", "-1", "NaN",
            String.join("", Collections.nCopies(1401, "9")) }) {
            data.setString("ChargeExact", invalid);
            data.setDouble("Charge", 64);
            ItemStack before = monitor.copy();
            assertEquals(
                64,
                MONITOR.getManager(monitor)
                    .getCharge(monitor),
                0);
            assertTrue(ItemStack.areItemStacksEqual(before, monitor));
            assertTrue(
                MONITOR.getManager(monitor)
                    .use(monitor, 32, null));
            assertDecimal(new BigDecimal("32"), BatteryCharge.get(data));
            assertEquals("32", data.getString("ChargeExact"));
        }
    }

    @Test
    public void invalidLegacyBalancesAndSimulationCannotCreateOrMutateEnergy() throws Exception {
        ItemStack monitor = installed();
        NBTTagCompound data = MonitorBattery.data(monitor);
        data.removeTag("ChargeExact");
        for (double invalid : new double[] { Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1 }) {
            data.setDouble("Charge", invalid);
            ItemStack before = monitor.copy();
            assertEquals(
                0,
                MONITOR.getManager(monitor)
                    .getCharge(monitor),
                0);
            assertFalse(
                MONITOR.getManager(monitor)
                    .use(monitor, 1, null));
            assertEquals(
                32,
                MONITOR.getManager(monitor)
                    .charge(monitor, 32, 1, true, true),
                0);
            // NBTTagDouble.equals treats NaN as unequal even to an identical copy.
            assertArrayEquals(savedBytes(before), savedBytes(monitor));
        }
    }

    @Test
    public void oldGtCapacityMigratesToItsNativeLongAndExactBalanceIsAuthoritative() {
        ItemStack monitor = installed();
        NBTTagCompound data = MonitorBattery.data(monitor);
        data.removeTag("CapacityExact");
        data.removeTag("ChargeExact");
        assertDecimal(CAPACITY, BatteryCharge.get(data));
        assertTrue(
            MONITOR.getManager(monitor)
                .use(monitor, 32, null));
        data.setDouble("Charge", 0); // A stale display projection is not an accounting authority.
        assertDecimal(CAPACITY.subtract(BigDecimal.valueOf(32)), BatteryCharge.get(data));
    }

    @Test
    public void refillChargesTheExactSmallDeficitAndFailureLeavesItPending() throws Exception {
        UUID owner = UUID.randomUUID();
        float oldLoss = Config.downlinkLossEU;
        GlobalEnergyWorldSavedData oldSaved = GlobalEnergyWorldSavedData.INSTANCE;
        Map<UUID, UUID> oldTeams = SpaceProjectManager.spaceTeams;
        try {
            Config.downlinkLossEU = 0.15f;
            GlobalEnergyWorldSavedData.INSTANCE = new GlobalEnergyWorldSavedData("monitor-charge-test");
            // Team lookup must not add fixture owners to shared state or dirty a previous world's team data.
            SpaceProjectManager.spaceTeams = new HashMap<>(oldTeams);
            SpaceProjectManager.spaceTeams.put(owner, owner);
            ItemStack monitor = installed();
            monitor.getTagCompound()
                .setString(PortableWirelessNetworkMonitor.NBT_OWNER_UUID, owner.toString());
            monitor.getTagCompound()
                .setBoolean(PortableWirelessNetworkMonitor.NBT_INITIALIZED, true);
            MONITOR.getManager(monitor)
                .use(monitor, 32, null);
            Method refill = MonitorChargingHandler.class
                .getDeclaredMethod("refill", ItemStack.class, NBTTagCompound.class);
            refill.setAccessible(true);
            GlobalVariableStorage.GlobalEnergy.put(owner, BigInteger.valueOf(36));
            refill.invoke(null, monitor, MonitorBattery.data(monitor));
            assertDecimal(new BigDecimal("32"), BatteryCharge.missing(MonitorBattery.data(monitor)));
            assertEquals(BigInteger.valueOf(36), GlobalVariableStorage.GlobalEnergy.get(owner));
            GlobalVariableStorage.GlobalEnergy.put(owner, BigInteger.valueOf(37));
            refill.invoke(null, monitor, MonitorBattery.data(monitor));
            assertDecimal(CAPACITY, BatteryCharge.get(MonitorBattery.data(monitor)));
            assertEquals(BigInteger.ZERO, GlobalVariableStorage.GlobalEnergy.get(owner));
        } finally {
            Config.downlinkLossEU = oldLoss;
            GlobalEnergyWorldSavedData.INSTANCE = oldSaved;
            SpaceProjectManager.spaceTeams = oldTeams;
            GlobalVariableStorage.GlobalEnergy.remove(owner);
        }
    }

    private static ItemStack installed() {
        ItemStack battery = new ItemStack(gtBattery);
        gtBattery.setCharge(battery, Long.MAX_VALUE);
        return MonitorBattery.install(new ItemStack(MONITOR), battery);
    }

    private static ItemStack reload(ItemStack stack) throws Exception {
        return ItemStack
            .loadItemStackFromNBT(CompressedStreamTools.readCompressed(new ByteArrayInputStream(savedBytes(stack))));
    }

    private static byte[] savedBytes(ItemStack stack) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompressedStreamTools.writeCompressed(stack.writeToNBT(new NBTTagCompound()), bytes);
        return bytes.toByteArray();
    }

    private static void assertDecimal(BigDecimal expected, BigDecimal actual) {
        assertEquals(expected.toPlainString() + " != " + actual.toPlainString(), 0, expected.compareTo(actual));
    }

    private static final class LongBattery extends MetaBaseItem {

        private LongBattery() {
            super("gtswn-test-long-battery");
        }

        @Override
        public Long[] getElectricStats(ItemStack stack) {
            return new Long[] { Long.MAX_VALUE, 32L, 1L, -3L };
        }

        @Override
        public Long[] getFluidContainerStats(ItemStack stack) {
            return null;
        }

        @Override
        public boolean isItemStackUsable(ItemStack stack) {
            return true;
        }
    }
}
