package com.miaokatze.gtswn.common.testworld;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Arrays;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.FakePlayer;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.baubles.TerminalBaubles;
import com.miaokatze.gtswn.common.charging.MonitorBattery;
import com.miaokatze.gtswn.common.charging.MonitorChargingHandler;
import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;
import com.miaokatze.gtswn.config.Config;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.enums.ItemList;
import gregtech.api.items.MetaBaseItem;
import gregtech.common.misc.WirelessNetworkManager;
import ic2.api.item.IElectricItemManager;

/** Real registered items and server callbacks; the synthetic actor is not a naturally ticking player. */
final class MonitorChargingScenario implements TestScenario {

    private static final BigDecimal CAPACITY = BigDecimal.valueOf(Long.MAX_VALUE);
    private FakePlayer player;
    private TestEuScope scope;
    private IInventory baubles;
    private ItemStack[] savedBaubles;
    private int savedTicks;
    private boolean savedCreative;
    private ItemStack monitor;
    private MonitorChargingHandler handler;
    private BigInteger refillCost;

    @Override
    public String id() {
        return "charging.native_exact";
    }

    @Override
    public String description() {
        return "Native ZPM2 exact debit, compressed item NBT, physical battery escrow and explicit server refill callbacks";
    }

    @Override
    public int timeoutTicks() {
        return 40;
    }

    @Override
    public void start(TestContext ctx) throws Exception {
        TestWorldFixtures.requireTestWorld(ctx.world());
        scope = new TestEuScope(TestWorldFixtures.owner(id()));
        player = TestWorldFixtures.player(ctx.world(), TestWorldFixtures.owner(id()));
        savedTicks = player.ticksExisted;
        savedCreative = player.capabilities.isCreativeMode;
        baubles = TerminalBaubles.inventory(player);
        require(ctx, !Loader.isModLoaded("Baubles") || baubles != null, "Optional Baubles inventory can be isolated");
        if (baubles != null) {
            ItemStack[] snapshot = new ItemStack[baubles.getSizeInventory()];
            for (int i = 0; i < snapshot.length; i++) snapshot[i] = baubles.getStackInSlot(i);
            savedBaubles = snapshot; // Do not restore a partially captured inventory after a snapshot failure.
            for (int i = 0; i < savedBaubles.length; i++) baubles.setInventorySlotContents(i, null);
        }
        player.capabilities.isCreativeMode = false;

        ItemStack empty = GTSWNItemList.Portable_Wireless_Network_Monitor.get(1);
        player.inventory.setInventorySlotContents(0, empty);
        ((PortableWirelessNetworkMonitor) empty.getItem()).onItemRightClick(empty, ctx.world(), player);
        require(
            ctx,
            PortableWirelessNetworkMonitor.isMonitorBound(empty) && player.getUniqueID()
                .toString()
                .equals(PortableWirelessNetworkMonitor.getOwnerUUID(empty)),
            "Real item callback binds only the isolated synthetic owner");

        ItemStack battery = ItemList.ZPM2.get(1);
        require(
            ctx,
            battery != null && battery.getItem() instanceof MetaBaseItem,
            "Registered native GT ZPM2 battery exists");
        MetaBaseItem nativeItem = (MetaBaseItem) battery.getItem();
        Long[] stats = nativeItem.getElectricStats(battery);
        require(
            ctx,
            stats != null && stats[0] == Long.MAX_VALUE && stats[3] == -3L,
            "Native ZPM2 has positive Long.MAX_VALUE rechargeable capacity");
        require(ctx, MonitorBattery.isBattery(battery), "Normal production battery admission accepts native ZPM2");
        require(
            ctx,
            nativeItem.setCharge(battery, Long.MAX_VALUE) && nativeItem.getRealCharge(battery) == Long.MAX_VALUE,
            "Native GT setter funds one isolated item exactly");
        byte[] nativeBefore = savedBytes(battery);
        monitor = MonitorBattery.install(empty, battery);
        require(
            ctx,
            Arrays.equals(nativeBefore, savedBytes(battery)),
            "Installation does not mutate its physical input");
        exact(ctx, "CapacityExact", CAPACITY);
        exact(ctx, "ChargeExact", CAPACITY);
        IElectricItemManager manager = MonitorBattery.manager(monitor);
        require(ctx, manager != null, "Registered monitor exposes its real IC2 electric manager");
        byte[] beforeSimulation = savedBytes(monitor);
        require(
            ctx,
            manager.discharge(monitor, 32, Integer.MAX_VALUE, true, false, true) == 32,
            "IC2 simulation reports the small exact debit");
        require(
            ctx,
            Arrays.equals(beforeSimulation, savedBytes(monitor)),
            "IC2 simulation leaves serialized state identical");
        require(
            ctx,
            manager.discharge(monitor, 32, Integer.MAX_VALUE, true, false, false) == 32,
            "Real IC2 debit accepts 32 EU");
        monitor = reload(monitor);
        exact(ctx, "ChargeExact", CAPACITY.subtract(BigDecimal.valueOf(32)));
        byte[] beforeRemoval = savedBytes(monitor);
        ItemStack removed = MonitorBattery.remove(monitor);
        require(
            ctx,
            removed != null && nativeItem.getRealCharge(removed) == Long.MAX_VALUE - 32,
            "Physical native battery export preserves Long.MAX_VALUE minus 32 after compressed NBT reload");
        require(
            ctx,
            Arrays.equals(beforeRemoval, savedBytes(monitor)),
            "Battery export preview does not mutate the monitor");

        require(
            ctx,
            MonitorBattery.manager(monitor)
                .discharge(monitor, .25, Integer.MAX_VALUE, true, false, false) == .25,
            "Fractional IC2 debit is retained at Long.MAX_VALUE scale");
        removed = reload(MonitorBattery.remove(reload(monitor)));
        require(
            ctx,
            nativeItem.getRealCharge(removed) == Long.MAX_VALUE - 33,
            "Native long storage receives only the integral part");
        require(
            ctx,
            removed.hasTagCompound() && new BigDecimal(
                removed.getTagCompound()
                    .getString("GTSWNChargeRemainder")).compareTo(new BigDecimal("0.75"))
                == 0,
            "Physical battery NBT retains the exact 0.75 EU escrow");
        monitor = reload(MonitorBattery.install(empty, removed));
        exact(ctx, "ChargeExact", CAPACITY.subtract(new BigDecimal("32.25")));
        ItemStack nested = ItemStack.loadItemStackFromNBT(
            MonitorBattery.data(monitor)
                .getCompoundTag("Item"));
        require(
            ctx,
            nested != null && (!nested.hasTagCompound() || !nested.getTagCompound()
                .hasKey("GTSWNChargeRemainder")),
            "Reinstallation consumes fractional escrow once, not twice");
        ItemStack without = MonitorBattery.withoutBattery(monitor);
        require(
            ctx,
            !MonitorBattery.hasBattery(without) && PortableWirelessNetworkMonitor.getOwnerUUID(empty)
                .equals(PortableWirelessNetworkMonitor.getOwnerUUID(without)),
            "Battery removal preserves monitor owner metadata");

        require(
            ctx,
            MonitorBattery.manager(monitor)
                .charge(monitor, 32.25, Integer.MAX_VALUE, true, false) == 32.25,
            "IC2 manager restores the exact small deficit");
        exact(ctx, "ChargeExact", CAPACITY);

        ItemStack target = ItemList.Battery_RE_LV_Lithium.get(1);
        require(
            ctx,
            target != null && target.getItem() instanceof MetaBaseItem,
            "Registered native LV lithium charging target exists");
        MetaBaseItem targetItem = (MetaBaseItem) target.getItem();
        require(
            ctx,
            targetItem.setCharge(target, 0) && targetItem.getRealCharge(target) == 0
                && targetItem.getTransferLimit(target) == 32,
            "Native target starts empty with its actual 32 EU transfer limit");
        player.inventory.setInventorySlotContents(0, monitor);
        player.inventory.setInventorySlotContents(1, target);
        handler = new MonitorChargingHandler();
        player.ticksExisted = 20;
        handler.onTick(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, player));
        exact(ctx, "ChargeExact", CAPACITY.subtract(BigDecimal.valueOf(32)));
        require(
            ctx,
            targetItem.getRealCharge(reload(target)) == 32,
            "Production inventory charging delivers native 32 EU and debits all 32 at Long.MAX_VALUE scale");
        player.inventory.setInventorySlotContents(1, null);
        require(
            ctx,
            MonitorBattery.manager(monitor)
                .charge(monitor, 32, Integer.MAX_VALUE, true, false) == 32,
            "Restore only the 32 EU used by the isolated native target");
        exact(ctx, "ChargeExact", CAPACITY);
        require(
            ctx,
            MonitorBattery.manager(monitor)
                .discharge(monitor, 20, Integer.MAX_VALUE, true, false, false) == 20,
            "Refill fixture has exactly 20 EU missing");
        require(
            ctx,
            Float.isFinite(Config.downlinkLossEU) && Config.downlinkLossEU >= 0,
            "Configured downlink loss is finite and nonnegative");
        refillCost = BigDecimal.valueOf(20)
            .multiply(BigDecimal.ONE.add(new BigDecimal(Float.toString(Config.downlinkLossEU))))
            .setScale(0, RoundingMode.CEILING)
            .toBigIntegerExact();
        if (Config.downlinkLossEU == .15f) require(
            ctx,
            refillCost.equals(BigInteger.valueOf(23)),
            "Configured decimal 0.15 gives exactly 23 EU for the 20 EU deficit");
        else ctx.event("limitation", "Config unchanged: exact 20/0.15 boundary is not this runtime's loss setting");
        require(
            ctx,
            WirelessNetworkManager.getUserEU(player.getUniqueID())
                .signum() == 0,
            "Isolated synthetic EU account starts at zero");
        require(
            ctx,
            WirelessNetworkManager.addEUToGlobalEnergyMap(player.getUniqueID(), refillCost.subtract(BigInteger.ONE)),
            "Seed only the isolated account with one EU less than required");
        player.inventory.setInventorySlotContents(0, monitor);
        ctx.event(
            "scope",
            "Native registered GT/IC2 items, in-memory compressed ItemStack NBT roundtrip; no disk-save/restart claim");
        ctx.event(
            "scope",
            "Explicit production PlayerTickEvent callbacks at synthetic ticks 20/200/400; not natural player ticks or event-bus-registration coverage; Config unchanged");
        ctx.event(
            "loss",
            "missing=20; configuredFloat=" + Float.toString(Config.downlinkLossEU) + "; expectedDebit=" + refillCost);
    }

    @Override
    public void tick(TestContext ctx) throws Exception {
        if (ctx.tick() == 1) {
            player.ticksExisted = 200;
            handler.onTick(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, player));
            exact(ctx, "ChargeExact", CAPACITY.subtract(BigDecimal.valueOf(20)));
            require(
                ctx,
                WirelessNetworkManager.getUserEU(player.getUniqueID())
                    .equals(refillCost.subtract(BigInteger.ONE)),
                "Insufficient-funds callback changes neither exact cache nor isolated EU balance");
            require(
                ctx,
                WirelessNetworkManager.addEUToGlobalEnergyMap(player.getUniqueID(), BigInteger.ONE),
                "Add the one missing EU only to the isolated account");
        }
        if (ctx.tick() != 2) return;
        player.ticksExisted = 400;
        handler.onTick(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, player));
        exact(ctx, "ChargeExact", CAPACITY);
        require(
            ctx,
            WirelessNetworkManager.getUserEU(player.getUniqueID())
                .equals(BigInteger.ZERO),
            "Successful production refill debits exactly the independently calculated decimal loss");
        handler.onTick(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, player));
        exact(ctx, "ChargeExact", CAPACITY);
        require(
            ctx,
            WirelessNetworkManager.getUserEU(player.getUniqueID())
                .equals(BigInteger.ZERO),
            "Repeated full-cache callback cannot debit twice");
        ItemStack exported = MonitorBattery.remove(reload(monitor));
        require(
            ctx,
            exported != null && exported.getItem() instanceof MetaBaseItem
                && ((MetaBaseItem) exported.getItem()).getRealCharge(exported) == Long.MAX_VALUE,
            "Refilled monitor exports native full capacity after compressed NBT reload");
        ctx.pass(
            "Native ZPM2 Long.MAX_VALUE debit/export, fractional escrow/NBT recovery, and explicit server refill callbacks passed");
    }

    private void exact(TestContext ctx, String key, BigDecimal expected) {
        NBTTagCompound data = MonitorBattery.data(monitor);
        require(
            ctx,
            data.hasKey(key, 8) && new BigDecimal(data.getString(key)).compareTo(expected) == 0,
            key + " equals " + expected.toPlainString());
    }

    private static byte[] savedBytes(ItemStack stack) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompressedStreamTools.writeCompressed(stack.writeToNBT(new NBTTagCompound()), bytes);
        return bytes.toByteArray();
    }

    private static ItemStack reload(ItemStack stack) throws Exception {
        ItemStack restored = ItemStack
            .loadItemStackFromNBT(CompressedStreamTools.readCompressed(new ByteArrayInputStream(savedBytes(stack))));
        if (restored == null)
            throw new IllegalStateException("Registered item could not survive compressed NBT reload");
        return restored;
    }

    private static void require(TestContext ctx, boolean condition, String detail) {
        TestWorldFixtures.require(ctx, condition, detail);
    }

    @Override
    public void cleanup(TestContext ctx) {
        RuntimeException failure = null;
        try {
            if (player != null) {
                failure = cleanupStep(failure, () -> player.inventory.clearInventory(null, -1));
                failure = cleanupStep(failure, () -> player.ticksExisted = savedTicks);
                failure = cleanupStep(failure, () -> player.capabilities.isCreativeMode = savedCreative);
            }
            if (baubles != null && savedBaubles != null) {
                for (int i = 0; i < savedBaubles.length; i++) {
                    final int slot = i;
                    failure = cleanupStep(failure, () -> baubles.setInventorySlotContents(slot, savedBaubles[slot]));
                }
            }
        } finally {
            failure = cleanupStep(failure, () -> { if (scope != null) scope.close(); });
            scope = null;
            player = null;
            monitor = null;
            handler = null;
            baubles = null;
            savedBaubles = null;
        }
        if (failure != null) throw failure;
    }

    private static RuntimeException cleanupStep(RuntimeException failure, Runnable action) {
        try {
            action.run();
        } catch (Exception | LinkageError ex) {
            if (failure == null) return new IllegalStateException("Native charging fixture cleanup failed", ex);
            failure.addSuppressed(ex);
        }
        return failure;
    }
}
