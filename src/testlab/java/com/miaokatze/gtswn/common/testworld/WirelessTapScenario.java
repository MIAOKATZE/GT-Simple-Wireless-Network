package com.miaokatze.gtswn.common.testworld;

import java.math.BigInteger;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.covers.GTswnCoverWirelessBase;
import com.miaokatze.gtswn.common.covers.GTswn_Cover_EnergyWireless;
import com.miaokatze.gtswn.common.covers.WirelessNodeIndexCodec;
import com.miaokatze.gtswn.common.covers.WirelessNodeRegistry;
import com.miaokatze.gtswn.common.items.WirelessEnergyTap;
import com.miaokatze.gtswn.network.NodeRevealRequestQueue;

import gregtech.api.enums.ItemList;
import gregtech.api.interfaces.tileentity.ICoverable;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.common.misc.WirelessNetworkManager;

final class WirelessTapScenario implements TestScenario {

    private static final int STALE_X = TestWorldFixtures.ENERGY_X - 4;
    private FakePlayer player;
    private BaseMetaTileEntity host;
    private BaseMetaTileEntity staleHost;
    private ItemStack tap;
    private TestEuScope scope;
    private long additionsAfterImmediateRemoval;
    private boolean immediateRemovalPending;

    @Override
    public String id() {
        return "tap.install_remove";
    }

    @Override
    public String description() {
        return "Real link-terminal item callback installs/configures a cover, natural injection, cooldown and removal";
    }

    @Override
    public void start(TestContext ctx) {
        scope = null;
        host = null;
        staleHost = null;
        immediateRemovalPending = false;
        player = TestActors.player(ctx.world(), "owner");
        scope = new TestEuScope(player.getUniqueID());
        WirelessNetworkManager.addEUToGlobalEnergyMap(player.getUniqueID(), BigInteger.valueOf(1000000000L));
        host = TestWorldFixtures.machine(
            ctx.world(),
            TestWorldFixtures.ENERGY_X,
            TestWorldFixtures.EU_Z,
            ItemList.Hatch_Energy_LV.get(1),
            player.getUniqueID());
        tap = GTSWNItemList.Wireless_Energy_Tap.get(1);
        player.inventory.setInventorySlotContents(0, tap);
        clickAndRequireDirty(ctx, "installation");
        TestWorldFixtures.require(
            ctx,
            host.getCoverAtSide(TestWorldFixtures.COVER_SIDE) instanceof GTswn_Cover_EnergyWireless,
            "Real terminal callback used registered CoverPlacer to install energy link cover");
        click(ctx);
        TestWorldFixtures.require(
            ctx,
            host.hasCoverAtSide(TestWorldFixtures.COVER_SIDE),
            "Immediate repeated callback is blocked by real tap cooldown");
    }

    @Override
    public void tick(TestContext ctx) {
        if (ctx.tick() == 40) {
            TestWorldFixtures.require(
                ctx,
                host.getStoredEUuncapped() > 0,
                "Naturally ticked cover configured by terminal supplies actual hatch EU");
            clickAndRequireDirty(ctx, "removal");
            TestWorldFixtures.require(
                ctx,
                !host.hasCoverAtSide(TestWorldFixtures.COVER_SIDE),
                "Real terminal callback removes installed link cover after cooldown");
            player.setSneaking(true);
        }
        if (ctx.tick() == 50 && immediateRemovalPending) {
            TestWorldFixtures.require(
                ctx,
                !indexed(ctx),
                "Natural END drains discard the detached cover request without resurrecting its index");
            TestWorldFixtures.require(
                ctx,
                TestWorldProbes.count(TestWorldProbes.Type.WIRELESS_NODE_ADD) == additionsAfterImmediateRemoval,
                "No deferred ADD was emitted for the cover detached in its installation tick");
            tap = GTSWNItemList.Wireless_Energy_Tap.get(1);
            player.inventory.setInventorySlotContents(0, tap);
            click(ctx);
            TestWorldFixtures.require(
                ctx,
                host.getCoverAtSide(TestWorldFixtures.COVER_SIDE) instanceof GTswn_Cover_EnergyWireless && indexed(ctx),
                "A fresh real tap reinstalls and registers the cover after the deferred removal regression");
            TestWorldFixtures.require(
                ctx,
                TestWorldProbes.count(TestWorldProbes.Type.WIRELESS_NODE_ADD) > additionsAfterImmediateRemoval,
                "Fresh real tap reinstallation creates a new business ADD transition");
            pruneReplacedHost(ctx);
            host.detachCover(TestWorldFixtures.COVER_SIDE);
            ctx.pass(
                "Real terminal power/cooldown/removal/NBT, deferred-registration lifecycle and stale-index query/prune passed");
            return;
        }
        if (ctx.tick() != 45) return;
        click(ctx);
        TestWorldFixtures.require(
            ctx,
            WirelessEnergyTap.getOutputModeStatic(tap),
            "Real Shift callback switches tap to dynamo mode");
        NBTTagCompound saved = new NBTTagCompound();
        tap.writeToNBT(saved);
        TestWorldFixtures.require(
            ctx,
            WirelessEnergyTap.getOutputModeStatic(ItemStack.loadItemStackFromNBT(saved)),
            "Tap item NBT preserves selected mode");
        TestWorldFixtures.require(
            ctx,
            TestWorldProbes.count(TestWorldProbes.Type.WIRELESS_NODE_ADD) > 0
                && TestWorldProbes.count(TestWorldProbes.Type.WIRELESS_NODE_REMOVE) > 0,
            "Real tap installation and removal reached business lifecycle hooks");
        player.setSneaking(false);
        tap = GTSWNItemList.Wireless_Energy_Tap.get(1);
        player.inventory.setInventorySlotContents(0, tap);
        click(ctx);
        TestWorldFixtures.require(
            ctx,
            host.getCoverAtSide(TestWorldFixtures.COVER_SIDE) instanceof GTswn_Cover_EnergyWireless && indexed(ctx),
            "Real tap installs a fresh cover for the same-tick removal regression");
        host.detachCover(TestWorldFixtures.COVER_SIDE);
        TestWorldFixtures.require(ctx, !indexed(ctx), "Same-tick real cover removal clears the index immediately");
        additionsAfterImmediateRemoval = TestWorldProbes.count(TestWorldProbes.Type.WIRELESS_NODE_ADD);
        immediateRemovalPending = true;
        ctx.event("regression", "Cover installed and detached in the same callback; waiting for natural END drains");
    }

    private boolean indexed(TestContext ctx) {
        return WirelessNodeRegistry.get(ctx.world())
            .snapshot(ctx.world())
            .containsKey(WirelessNodeIndexCodec.pack(host.xCoord, host.yCoord, host.zCoord));
    }

    private void pruneReplacedHost(TestContext ctx) {
        staleHost = TestWorldFixtures.machine(
            ctx.world(),
            STALE_X,
            TestWorldFixtures.EU_Z,
            ItemList.Hatch_Energy_LV.get(1),
            player.getUniqueID());
        tap = GTSWNItemList.Wireless_Energy_Tap.get(1);
        player.inventory.setInventorySlotContents(0, tap);
        ((WirelessEnergyTap) tap.getItem()).onItemUseFirst(
            tap,
            player,
            ctx.world(),
            staleHost.xCoord,
            staleHost.yCoord,
            staleHost.zCoord,
            1,
            .5f,
            1f,
            .5f);
        WirelessNodeRegistry registry = WirelessNodeRegistry.get(ctx.world());
        long stale = WirelessNodeIndexCodec.pack(staleHost.xCoord, staleHost.yCoord, staleHost.zCoord);
        long live = WirelessNodeIndexCodec.pack(host.xCoord, host.yCoord, host.zCoord);
        TestWorldFixtures.require(
            ctx,
            staleHost.getCoverAtSide(TestWorldFixtures.COVER_SIDE) instanceof GTswn_Cover_EnergyWireless
                && registry.snapshot(ctx.world())
                    .containsKey(stale),
            "Real tap installed and indexed a second host before its non-drop replacement");
        // GT205 notifies cover destruction from getDrops(), not from non-drop World.setBlockToAir().
        // This real block lifecycle is a reachable stale-index source; no registry key is fabricated.
        ctx.world()
            .setBlockToAir(staleHost.xCoord, staleHost.yCoord, staleHost.zCoord);
        TestWorldFixtures.require(
            ctx,
            ctx.world()
                .getTileEntity(staleHost.xCoord, staleHost.yCoord, staleHost.zCoord) == null
                && registry.snapshot(ctx.world())
                    .containsKey(stale),
            "Non-drop host removal leaves a real persisted-index candidate requiring query-time repair");
        WirelessNodeIndexCodec.RadiusSelection selection = WirelessNodeIndexCodec.selectWithinRadius(
            registry.snapshot(ctx.world()),
            live,
            NodeRevealRequestQueue.REVEAL_RADIUS_SQ,
            NodeRevealRequestQueue.REVEAL_LIMIT,
            (packed, x, y, z, type) -> {
                if (!ctx.world()
                    .blockExists(x, y, z)) return WirelessNodeIndexCodec.ProbeResult.UNLOADED;
                TileEntity tile = ctx.world()
                    .getTileEntity(x, y, z);
                if (tile instanceof ICoverable) {
                    for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                        if (((ICoverable) tile).getCoverAtSide(side) instanceof GTswnCoverWirelessBase)
                            return WirelessNodeIndexCodec.ProbeResult.VALID;
                    }
                }
                return WirelessNodeIndexCodec.ProbeResult.INVALID;
            });
        TestWorldFixtures.require(
            ctx,
            selection.pruned.contains(stale) && !selection.pruned.contains(live)
                && selection.selected.stream()
                    .anyMatch(node -> node.packed == live),
            "Production radius query separates the physically missing host from the still-valid node");
        long pruneBefore = TestWorldProbes.count(TestWorldProbes.Type.WIRELESS_NODE_PRUNE);
        registry.prune(ctx.world(), selection.pruned);
        TestWorldFixtures.require(
            ctx,
            !registry.snapshot(ctx.world())
                .containsKey(stale) && registry.snapshot(ctx.world())
                    .containsKey(live)
                && TestWorldProbes.count(TestWorldProbes.Type.WIRELESS_NODE_PRUNE) == pruneBefore + 1,
            "Production prune removes exactly the stale test position and preserves the valid wireless node");
    }

    private void click(TestContext ctx) {
        ((WirelessEnergyTap) tap.getItem())
            .onItemUseFirst(tap, player, ctx.world(), host.xCoord, host.yCoord, host.zCoord, 1, .5f, 1f, .5f);
    }

    private void clickAndRequireDirty(TestContext ctx, String mutation) {
        Chunk chunk = ctx.world()
            .getChunkFromBlockCoords(host.xCoord, host.zCoord);
        boolean wasDirty = chunk.isModified;
        chunk.isModified = false;
        try {
            click(ctx);
            TestWorldFixtures
                .require(ctx, chunk.isModified, "Real cover " + mutation + " dirties its host chunk for saving");
        } finally {
            if (wasDirty) chunk.setChunkModified();
        }
    }

    @Override
    public void cleanup(TestContext ctx) {
        if (staleHost != null) {
            if (!staleHost.isInvalid()) staleHost.detachCover(TestWorldFixtures.COVER_SIDE);
            WirelessNodeRegistry.get(ctx.world())
                .unregister(ctx.world(), staleHost.xCoord, staleHost.yCoord, staleHost.zCoord);
            ctx.world()
                .setBlockToAir(staleHost.xCoord, staleHost.yCoord, staleHost.zCoord);
        }
        if (host != null && !host.isInvalid()) host.detachCover(TestWorldFixtures.COVER_SIDE);
        if (scope != null) scope.close();
        if (player != null) {
            player.setSneaking(false);
            player.inventory.clearInventory(null, -1);
        }
        scope = null;
        player = null;
        staleHost = null;
    }
}
