package com.miaokatze.gtswn.common.testworld;

import java.util.UUID;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.FakePlayer;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.device.DeviceFlowMonitor;
import com.miaokatze.gtswn.common.device.DeviceMachineIdentity;
import com.miaokatze.gtswn.common.device.DeviceMachineTypes;
import com.miaokatze.gtswn.common.device.DeviceRegistryData;
import com.miaokatze.gtswn.common.device.DeviceTerminalDataStore;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.config.Config;

import gregtech.api.enums.ItemList;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;

/** Real recipe, production terminal binding and the naturally scheduled observed-flow pipeline. */
final class DeviceWorldScenario implements TestScenario {

    private FakePlayer player;
    private BaseMetaTileEntity host;
    private MTEBasicMachine machine;
    private UUID terminalId;
    private String key;
    private DeviceTerminalDataStore store;
    private DeviceRegistryData registry;
    private NBTTagCompound terminalBefore;
    private DeviceRegistryData.DeviceEntry registryBefore;
    private boolean scopeCaptured;
    private boolean observedRecipe;

    @Override
    public String id() {
        return "device.binding_flow";
    }

    @Override
    public String description() {
        return "Real GT recipe, terminal click binding, identity and naturally scheduled observed flow/list data";
    }

    @Override
    public int timeoutTicks() {
        return Math.max(600, Math.min(10000, Config.deviceSampleIntervalSeconds * 60 + 200));
    }

    @Override
    public void start(TestContext ctx) {
        scopeCaptured = false;
        observedRecipe = false;
        player = null;
        host = null;
        if (Config.deviceSampleIntervalSeconds > 150) {
            ctx.skip("Configured device sampling interval exceeds bounded test window; global Config is unchanged");
            return;
        }
        player = TestActors.player(ctx.world(), "owner");
        terminalId = TestWorldFixtures.owner(id() + ":terminal");
        store = DeviceTerminalDataStore.get(ctx.world());
        registry = DeviceRegistryData.get(ctx.world());
        key = DeviceRegistryData.makeKey(
            ctx.world().provider.dimensionId,
            TestWorldFixtures.DEVICE_X,
            TestWorldFixtures.Y,
            TestWorldFixtures.DEVICE_Z);
        terminalBefore = store.snapshotTestTerminal(ctx.world(), terminalId);
        DeviceRegistryData.DeviceEntry old = registry.getEntry(key);
        registryBefore = old == null ? null
            : new DeviceRegistryData.DeviceEntry(old.owner, old.localName, old.metaTileId);
        scopeCaptured = true;
        // Clear only this dedicated synthetic terminal key for a repeatable first binding.
        store.restoreTestTerminal(ctx.world(), terminalId, null);
        registry.unregister(key);
        host = TestWorldFixtures.machine(
            ctx.world(),
            TestWorldFixtures.DEVICE_X,
            TestWorldFixtures.DEVICE_Z,
            ItemList.Machine_LV_Macerator.get(1),
            player.getUniqueID());
        machine = (MTEBasicMachine) host.getMetaTileEntity();
        TestWorldFixtures.cover(host, GTSWNItemList.GTswn_Cover_Energy_Wireless);
        TestWorldFixtures.require(
            ctx,
            DeviceMachineTypes.isWorkingMachine(machine) && !DeviceMachineTypes.isGeneratorMachine(machine),
            "Production recognition identifies actual LV macerator as consumer");
        TestWorldFixtures.require(
            ctx,
            DeviceMachineIdentity.linkNodeType(machine) == 1,
            "Production machine identity discovers actual installed energy link cover");
        machine.setInventorySlotContents(machine.getInputSlot(), new ItemStack(Items.iron_ingot, 64));
        host.increaseStoredEnergyUnits(host.getEUCapacity(), true);
        host.enableWorking();
        ItemStack terminal = GTSWNItemList.Device_Info_Terminal.get(1);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString(ItemDeviceInfoTerminal.NBT_UUID, terminalId.toString());
        terminal.setTagCompound(tag);
        player.inventory.setInventorySlotContents(0, terminal);
        ((ItemDeviceInfoTerminal) terminal.getItem())
            .onItemUseFirst(terminal, player, ctx.world(), host.xCoord, host.yCoord, host.zCoord, 1, .5f, .5f, .5f);
        TestWorldFixtures.require(ctx, registry.getEntry(key) != null, "Real terminal click registered fixture device");
        TestWorldFixtures.require(
            ctx,
            store.getTerminal(terminalId) != null && store.getTerminal(terminalId).boundKeys.contains(key),
            "Real terminal click populated actual list binding");
        DeviceTerminalDataStore.ensureActiveTerminal(player.getUniqueID(), terminalId);
        TestWorldFixtures.require(
            ctx,
            DeviceFlowMonitor.watch(machine, timeoutTicks() + 100) != null,
            "Production flow observer enrolled real controller on server thread");
        ctx.event(
            "scope",
            "Shared WSD touches only fixed synthetic terminal UUID and test-dimension device key; exact state restored on cleanup");
    }

    @Override
    public void tick(TestContext ctx) {
        // Fixture power enters outside the controller execution window, exactly as external cable power would.
        host.increaseStoredEnergyUnits(2048, true);
        if (machine.mMaxProgresstime > 0 && machine.mEUt > 0) observedRecipe = true;
        DeviceTerminalDataStore.MachineRecord record = store.getTerminal(terminalId).records.get(key);
        if (record == null || record.count == 0 || record.evidenceKind != 0 || record.inAvg <= 0) return;
        TestWorldFixtures
            .require(ctx, observedRecipe, "Actual registered GT recipe progressed under natural machine ticks");
        TestWorldFixtures.require(
            ctx,
            record.metaTileId == host.getMetaTileID() && record.linkNodeType == 1,
            "Natural scheduler list sample retains actual machine ID and link-node identity");
        TestWorldFixtures.require(
            ctx,
            record.inAvg > 0 && record.outAvg == 0,
            "Natural controller mixins observed real consumption and no invented production");
        TestWorldFixtures.require(
            ctx,
            TestWorldProbes.count(TestWorldProbes.Type.DEVICE_REGISTER) > 0,
            "Production device registration hook observed the real binding");
        TestWorldFixtures.require(
            ctx,
            TestWorldProbes.count(TestWorldProbes.Type.DEVICE_FLOW) > 0,
            "Production device flow hook observed naturally sampled consumption");
        NBTTagCompound sampled = store.snapshotTestTerminal(ctx.world(), terminalId);
        store.restoreTestTerminal(ctx.world(), terminalId, sampled);
        DeviceTerminalDataStore.MachineRecord reloaded = store.getTerminal(terminalId).records.get(key);
        TestWorldFixtures.require(
            ctx,
            reloaded != null && reloaded.inAvg == record.inAvg
                && reloaded.metaTileId == record.metaTileId
                && store.getTerminal(terminalId).boundKeys.contains(key),
            "Actual terminal dataset NBT replay preserves observed sample and binding");
        host.disableWorking();
        ctx.pass(
            "Real terminal binding/list and natural recipe consumption reached production scheduler as observed evidence");
    }

    @Override
    public void cleanup(TestContext ctx) {
        if (machine != null) DeviceFlowMonitor.forget(machine);
        if (host != null && !host.isInvalid()) {
            host.disableWorking();
            host.detachCover(TestWorldFixtures.COVER_SIDE);
        }
        if (player != null) {
            DeviceTerminalDataStore.onPlayerLoggedOut(player.getUniqueID());
            player.inventory.clearInventory(null, -1);
        }
        if (scopeCaptured) {
            store.restoreTestTerminal(ctx.world(), terminalId, terminalBefore);
            registry.unregister(key);
            if (registryBefore != null)
                registry.register(key, registryBefore.owner, registryBefore.localName, registryBefore.metaTileId);
        }
        scopeCaptured = false;
        machine = null;
        player = null;
    }
}
