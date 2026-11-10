package com.miaokatze.gtswn.common.testworld;

import java.util.UUID;

import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.machine.MTEWirelessEnergyMonitor;

import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.common.misc.WirelessNetworkManager;

final class EnergyMonitorScenario implements TestScenario {

    private BaseMetaTileEntity host;
    private MTEWirelessEnergyMonitor monitor;
    private UUID owner;
    private TestEuScope scope;

    @Override
    public String id() {
        return "monitor.redstone_nbt";
    }

    @Override
    public String description() {
        return "Real energy monitor natural redstone threshold transition and NBT replay";
    }

    @Override
    public void start(TestContext ctx) {
        scope = null;
        owner = TestWorldFixtures.owner(id());
        scope = new TestEuScope(owner);
        TestWorldProbes.addOwner(owner);
        TestWorldFixtures.require(
            ctx,
            WirelessNetworkManager.getUserEU(owner)
                .signum() == 0,
            "Monitor uses a fresh synthetic network");
        host = TestWorldFixtures.machine(
            ctx.world(),
            TestWorldFixtures.MONITOR_X,
            TestWorldFixtures.EU_Z,
            GTSWNItemList.Wireless_Energy_Monitor.get(1),
            owner);
        monitor = (MTEWirelessEnergyMonitor) host.getMetaTileEntity();
        monitor.setAnchorMode(0);
        monitor.setParam1Value(100);
        monitor.setRedstoneMode(1);
        WirelessNetworkManager.addEUToGlobalEnergyMap(owner, 200);
    }

    @Override
    public void tick(TestContext ctx) {
        if (ctx.tick() == 40) {
            TestWorldFixtures.require(
                ctx,
                host.getOutputRedstoneSignal(ForgeDirection.NORTH) > 0,
                "Natural monitor ticks emit redstone above configured network threshold");
            host = (BaseMetaTileEntity) TestWorldFixtures.reload(ctx.world(), host);
            monitor = (MTEWirelessEnergyMonitor) host.getMetaTileEntity();
            TestWorldFixtures.require(
                ctx,
                monitor.getRedstoneMode() == 1 && monitor.getParam1Value() == 100,
                "Monitor NBT preserves threshold and redstone mode");
            WirelessNetworkManager.addEUToGlobalEnergyMap(owner, -200);
        }
        if (ctx.tick() != 80) return;
        TestWorldFixtures.require(
            ctx,
            host.getOutputRedstoneSignal(ForgeDirection.NORTH) == 0,
            "Natural monitor ticks clear redstone after real network balance drops");
        ctx.pass("Real network threshold transition and monitor tile NBT replay passed");
    }

    @Override
    public void cleanup(TestContext ctx) {
        if (host != null) TestWorldFixtures.machine(
            ctx.world(),
            TestWorldFixtures.MONITOR_X,
            TestWorldFixtures.EU_Z,
            GTSWNItemList.Wireless_Energy_Monitor.get(1),
            null);
        if (scope != null) scope.close();
        scope = null;
    }
}
