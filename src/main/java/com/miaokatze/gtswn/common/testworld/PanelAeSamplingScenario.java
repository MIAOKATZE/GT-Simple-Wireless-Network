package com.miaokatze.gtswn.common.testworld;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.panel.AEMonitorDataSet;
import com.miaokatze.gtswn.common.panel.AEMonitorDataStore;
import com.miaokatze.gtswn.common.panel.AEMonitorSample;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.config.Config;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.networking.IGridHost;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.MachineSource;
import appeng.api.storage.data.IAEItemStack;
import appeng.me.GridAccessException;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import appeng.tile.storage.TileDrive;
import appeng.util.item.AEItemStack;

/** Actual AE storage stock -> natural panel sampling -> persisted server data, followed by physical disconnect. */
final class PanelAeSamplingScenario implements TestScenario {

    private TileEntityNetworkInfoPanel panel;
    private TileEntity controller;
    private TileDrive drive;
    private FakePlayer player;
    private PanelOwnerScope scope;
    private IAEItemStack remaining;
    private int stage;
    private long stageTick;
    private String dataKey, itemKey;

    @Override
    public String id() {
        return "panel.ae_sampling";
    }

    @Override
    public String description() {
        return "Actual 1k-cell stock, natural AE panel sampling, panel NBT replay and physical disconnect";
    }

    @Override
    public int timeoutTicks() {
        return Math.max(500, Math.min(10000, Config.aeSampleInterval * 4 + 300));
    }

    @Override
    public void start(TestContext ctx) {
        scope = null;
        player = null;
        controller = null;
        drive = null;
        panel = null;
        stage = 0;
        stageTick = 0;
        if (!Config.aeChartEnabled || Config.aeSampleInterval > 2000) {
            ctx.skip(
                "Optional AE charts disabled or configured interval exceeds bounded test window; Config unchanged");
            return;
        }
        player = TestActors.player(ctx.world(), "owner");
        scope = new PanelOwnerScope(ctx.world(), player.getUniqueID());
        panel = TestWorldFixtures.panel(ctx.world(), player.getUniqueID());
        panel.getProxy()
            .setOwner(player);
        panel.setChartItem(new ItemStack(Items.iron_ingot));
        panel.toggleItemMonitor(new ItemStack(Items.iron_ingot));
        controller = TestWorldFixtures.aeOwnedBlock(
            ctx.world(),
            TestWorldFixtures.PANEL_X,
            TestWorldFixtures.Y,
            TestWorldFixtures.PANEL_Z - 1,
            AEApi.instance()
                .definitions()
                .blocks()
                .controller()
                .maybeStack(1)
                .get(),
            player);
        drive = (TileDrive) TestWorldFixtures.aeOwnedBlock(
            ctx.world(),
            TestWorldFixtures.PANEL_X - 1,
            TestWorldFixtures.Y,
            TestWorldFixtures.PANEL_Z - 1,
            AEApi.instance()
                .definitions()
                .blocks()
                .drive()
                .maybeStack(1)
                .get(),
            player);
        drive.getInternalInventory()
            .setInventorySlotContents(
                0,
                AEApi.instance()
                    .definitions()
                    .items()
                    .cell1k()
                    .maybeStack(1)
                    .get());
        remaining = AEItemStack.create(new ItemStack(Items.iron_ingot, 7));
        dataKey = panel.getAEMonitorDataKey();
        itemKey = TileEntityNetworkInfoPanel.getAEKey(new ItemStack(Items.iron_ingot));
    }

    @Override
    public void tick(TestContext ctx) {
        AENetworkProxy proxy = ((IGridProxyable) controller).getProxy();
        try {
            if (proxy.isReady()) proxy.getEnergy()
                .injectPower(200, Actionable.MODULATE);
            if (remaining != null && panel.isAEConnected()
                && ((IGridHost) drive).getGridNode(ForgeDirection.UNKNOWN) != null
                && ((IGridHost) drive).getGridNode(ForgeDirection.UNKNOWN)
                    .isActive()) {
                remaining = proxy.getStorage()
                    .getItemInventory()
                    .injectItems(remaining, Actionable.MODULATE, new MachineSource((IActionHost) controller));
                if (remaining == null) ctx.event(
                    "fixture_stock",
                    "Production AE storage accepted exactly seven iron ingots into a real 1k cell");
            }
        } catch (GridAccessException ignored) { /* Wait for actual grid boot-up. */ }
        AEMonitorDataSet data = AEMonitorDataStore.get(ctx.world())
            .getIfPresent(dataKey);
        AEMonitorSample latest = data == null ? null : data.newest(itemKey);
        if (stage == 0 && remaining == null && latest != null && latest.amount == 7) {
            TestWorldFixtures.require(
                ctx,
                panel.getAEItemAmount(new ItemStack(Items.iron_ingot)) == 7,
                "Production panel query sees actual seven-item cell stock");
            TestWorldFixtures.require(
                ctx,
                TestWorldProbes.count(TestWorldProbes.Type.PANEL_SAMPLE) > 0,
                "Natural panel sampling hook recorded real AE inventory data");
            panel = (TileEntityNetworkInfoPanel) TestWorldFixtures.reload(ctx.world(), panel);
            TestWorldFixtures.require(
                ctx,
                panel.getChartItem() != null && panel.getChartItem()
                    .getItem() == Items.iron_ingot,
                "Panel NBT preserves actual inventory chart binding");
            stage = 1;
            stageTick = ctx.tick();
        } else if (stage == 1 && ctx.tick() - stageTick >= Math.max(20, Config.aeSampleInterval + 5)
            && panel.isAEConnected()
            && latest != null
            && latest.amount == 7) {
                ctx.world()
                    .setBlockToAir(controller.xCoord, controller.yCoord, controller.zCoord);
                stage = 2;
                stageTick = ctx.tick();
            } else if (stage == 2 && ctx.tick() - stageTick >= 20) {
                TestWorldFixtures.require(
                    ctx,
                    !panel.isAEConnected(),
                    "Physical controller removal disconnects the actual panel grid");
                TestWorldFixtures.require(
                    ctx,
                    latest != null && latest.amount == 7,
                    "Offline transition preserves historical server sample without fabricating new stock");
                ctx.pass(
                    "Actual AE cell stock reached natural panel sampler, persisted across tile NBT replay, and survived physical grid disconnect");
            }
    }

    @Override
    public void cleanup(TestContext ctx) {
        if (panel != null) TestWorldFixtures.panel(ctx.world(), null);
        if (controller != null) ctx.world()
            .setBlockToAir(controller.xCoord, controller.yCoord, controller.zCoord);
        if (drive != null) ctx.world()
            .setBlockToAir(drive.xCoord, drive.yCoord, drive.zCoord);
        if (scope != null) scope.close();
        if (player != null) player.inventory.clearInventory(null, -1);
        scope = null;
        player = null;
    }
}
