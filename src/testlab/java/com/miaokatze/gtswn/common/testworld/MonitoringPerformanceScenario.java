// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.testworld;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.FakePlayer;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.device.DeviceFlowMonitor;
import com.miaokatze.gtswn.common.device.DeviceRegistryData;
import com.miaokatze.gtswn.common.device.DeviceTerminalDataStore;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.config.Config;

import gregtech.api.enums.ItemList;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;

/** Explicit, bounded native background-monitoring comparison. No synthetic processing or performance threshold. */
final class MonitoringPerformanceScenario implements TestScenario {

    private static final int[] SCALES = { 0, 15, 128 };
    private static final int WARMUP = 220, WINDOW = 400;
    private final List<BaseMetaTileEntity> machines = new ArrayList<>();
    private final Map<String, DeviceRegistryData.DeviceEntry> previousEntries = new LinkedHashMap<>();
    private FakePlayer player;
    private ItemStack terminal;
    private UUID terminalId;
    private DeviceTerminalDataStore store;
    private DeviceRegistryData registry;
    private NBTTagCompound terminalBefore;
    private TestWorldReport.Workload workload;
    private TestWorldReport.WorkloadPhase phase;
    private int scale;
    private boolean provisionPending;
    private long phaseStarted, allocatedBefore;
    private com.sun.management.ThreadMXBean allocations;
    private PanelAeSamplingScenario panelCheck;

    @Override
    public String id() {
        return "monitoring.explicit_performance";
    }

    @Override
    public String description() {
        return "0/15/128 real macerators with natural background terminal sampling";
    }

    @Override
    public boolean includeInAll() {
        return false;
    }

    @Override
    public int timeoutTicks() {
        return 2800;
    }

    @Override
    public void start(TestContext ctx) {
        machines.clear();
        previousEntries.clear();
        scale = 0;
        provisionPending = false;
        phase = null;
        panelCheck = null;
        phaseStarted = 0;
        player = null;
        store = null;
        if (Config.deviceSampleIntervalSeconds > 10 || Config.deviceTerminalMaxMachines < 128) {
            ctx.skip("Requires the normal <=10-second cadence and >=128 bindings; global configuration unchanged");
            return;
        }
        player = TestActors.player(ctx.world(), "owner");
        terminalId = TestWorldFixtures.owner(id() + ":terminal");
        store = DeviceTerminalDataStore.get(ctx.world());
        registry = DeviceRegistryData.get(ctx.world());
        terminalBefore = TestFixtureAccess.snapshotTerminal(store, ctx.world(), terminalId);
        TestFixtureAccess.restoreTerminal(store, ctx.world(), terminalId, null);
        terminal = GTSWNItemList.Device_Info_Terminal.get(1);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString(ItemDeviceInfoTerminal.NBT_UUID, terminalId.toString());
        terminal.setTagCompound(tag);
        player.inventory.setInventorySlotContents(0, terminal);
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        com.sun.management.ThreadMXBean extended = bean instanceof com.sun.management.ThreadMXBean
            ? (com.sun.management.ThreadMXBean) bean
            : null;
        allocations = extended != null && extended.isThreadAllocatedMemorySupported()
            && extended.isThreadAllocatedMemoryEnabled() ? extended : null;
        workload = new TestWorldReport.Workload();
        workload.kind = "native-background-device-monitoring";
        workload.retention = "Only owned fixture terminal/registry scope is restored; test-world machines stop on cleanup.";
        workload.coverageLimitations.add(
            "No physical GUI, player production save, many-output multiblock or Web workload. Server timing includes all running mods.");
        workload.coverageLimitations.add(
            "Warmup 220 ticks then 400 measured ticks per scale; fixture power is supplied outside controller calls. No manual recipe progress or completion.");
        ctx.result.workload = workload;
        begin(ctx, "warmup_0");
    }

    @Override
    public void tick(TestContext ctx) {
        if (panelCheck != null) {
            panelCheck.tick(ctx);
            return;
        }
        // Provision on a warmup frame, never inside the measured phase's final server window.
        if (provisionPending) {
            provision(ctx, SCALES[scale]);
            provisionPending = false;
        }
        for (BaseMetaTileEntity tile : machines) tile.increaseStoredEnergyUnits(2048, true);
        long elapsed = ctx.tick() - phaseStarted;
        if (phase.name.startsWith("warmup_") && elapsed >= WARMUP) {
            begin(ctx, "measured_" + SCALES[scale]);
        } else if (phase.name.startsWith("measured_") && elapsed >= WINDOW) {
            verify(ctx);
            if (++scale == SCALES.length) {
                begin(ctx, "ae_sampling_regression");
                for (BaseMetaTileEntity tile : machines) tile.disableWorking();
                panelCheck = new PanelAeSamplingScenario();
                panelCheck.start(ctx);
                ctx.event(
                    "device_comparison_complete",
                    "All scales retained actual consumption and empty recipe summaries; continuing the existing native AE sampling/NBT/disconnect regression");
                return;
            }
            begin(ctx, "warmup_" + SCALES[scale]);
            provisionPending = true;
        }
    }

    private void provision(TestContext ctx, int count) {
        for (int index = machines.size(); index < count; index++) {
            int x = -24 + index % 16 * 3, z = -24 + index / 16 * 3;
            String key = DeviceRegistryData.makeKey(ctx.world().provider.dimensionId, x, TestWorldFixtures.Y, z);
            DeviceRegistryData.DeviceEntry old = registry.getEntry(key);
            previousEntries.put(
                key,
                old == null ? null : new DeviceRegistryData.DeviceEntry(old.owner, old.localName, old.metaTileId));
            registry.unregister(key);
            BaseMetaTileEntity tile = TestWorldFixtures
                .machine(ctx.world(), x, z, ItemList.Machine_LV_Macerator.get(1), player.getUniqueID());
            MTEBasicMachine machine = (MTEBasicMachine) tile.getMetaTileEntity();
            machine.setInventorySlotContents(machine.getInputSlot(), new ItemStack(Items.iron_ingot, 64));
            tile.increaseStoredEnergyUnits(tile.getEUCapacity(), true);
            tile.enableWorking();
            ((ItemDeviceInfoTerminal) terminal.getItem())
                .onItemUseFirst(terminal, player, ctx.world(), tile.xCoord, tile.yCoord, tile.zCoord, 1, .5f, .5f, .5f);
            machines.add(tile);
        }
        DeviceTerminalDataStore.ensureActiveTerminal(player.getUniqueID(), terminalId);
        TestWorldFixtures.require(
            ctx,
            store.getTerminal(terminalId).boundKeys.size() == count,
            "Native terminal binding retains exactly " + count + " real fixture controllers");
    }

    private void verify(TestContext ctx) {
        int observed = 0;
        long samples = 0;
        if (!machines.isEmpty()) {
            for (BaseMetaTileEntity tile : machines) {
                String key = DeviceRegistryData
                    .makeKey(ctx.world().provider.dimensionId, tile.xCoord, tile.yCoord, tile.zCoord);
                DeviceTerminalDataStore.MachineRecord record = store.getTerminal(terminalId).records.get(key);
                TestWorldFixtures.require(
                    ctx,
                    record != null && record.count > 0
                        && record.evidenceKind == 0
                        && record.inAvg > 0
                        && record.outAvg == 0,
                    "Observed real consumption for " + key);
                TestWorldFixtures.require(
                    ctx,
                    record.recipeIn.isEmpty() && record.recipeOut.isEmpty(),
                    "No recipe summary for " + key);
                observed++;
                samples += record.count;
            }
        }
        phase.observed.put("controllers", (long) observed);
        phase.observed.put("retainedSamples", samples);
        phase.observed.put("physicalGui", 0L);
    }

    private long allocated() {
        return allocations == null ? -1
            : allocations.getThreadAllocatedBytes(
                Thread.currentThread()
                    .getId());
    }

    private void finish(TestContext ctx) {
        if (phase == null) return;
        phase.endedTick = ctx.tick();
        long after = allocated();
        phase.observed.put(
            "serverThreadAllocatedBytes",
            allocatedBefore < 0 || after < allocatedBefore ? -1 : after - allocatedBefore);
    }

    private void begin(TestContext ctx, String name) {
        finish(ctx);
        phaseStarted = ctx.tick();
        phase = new TestWorldReport.WorkloadPhase(name, phaseStarted);
        workload.phases.add(phase);
        workload.phase = name;
        allocatedBefore = allocated();
        ctx.beginWorkloadPhase(phase);
        ctx.event("monitoring_phase", name);
    }

    @Override
    public void cleanup(TestContext ctx) {
        if (store == null) return;
        if (panelCheck != null) panelCheck.cleanup(ctx);
        finish(ctx);
        for (BaseMetaTileEntity tile : machines) {
            DeviceFlowMonitor.forget(tile.getMetaTileEntity());
            tile.disableWorking();
        }
        DeviceTerminalDataStore.onPlayerLoggedOut(player.getUniqueID());
        player.inventory.clearInventory(null, -1);
        TestFixtureAccess.restoreTerminal(store, ctx.world(), terminalId, terminalBefore);
        for (Map.Entry<String, DeviceRegistryData.DeviceEntry> entry : previousEntries.entrySet()) {
            registry.unregister(entry.getKey());
            DeviceRegistryData.DeviceEntry old = entry.getValue();
            if (old != null) registry.register(entry.getKey(), old.owner, old.localName, old.metaTileId);
        }
        machines.clear();
    }
}
