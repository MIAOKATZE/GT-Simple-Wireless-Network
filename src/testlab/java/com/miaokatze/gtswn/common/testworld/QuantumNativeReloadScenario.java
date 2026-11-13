// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.testworld;

import java.util.UUID;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraftforge.common.util.FakePlayer;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;
import com.miaokatze.gtswn.common.quantum.QuantumTerminalAccess;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.register.BlockRegistrar;

import appeng.api.AEApi;
import appeng.api.config.SecurityPermissions;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.networking.security.ISecurityProvider;
import appeng.api.util.AEColor;
import appeng.tile.networking.TileCableBus;

/** Two explicit runs in distinct real JVMs. Verify never reconstructs or refreshes its persisted fixture. */
final class QuantumNativeReloadScenario implements TestScenario {

    private static final String PROCESS = UUID.randomUUID()
        .toString();
    private static final String MARKER = "GTSWNNativeReload128";
    private static final int X = -24, Z = -24, Y = TestWorldFixtures.Y, AX = 24, AZ = 24;
    private final boolean verify;
    private FakePlayer owner, member;
    private TileEntity controller, station, direct, cable, downstream;
    private TileEntityNetworkQuantumNode node;
    private TileEntityNetworkInfoPanel panel;
    private TileEntityChest chest;
    private ItemStack terminal;
    private int stage;

    QuantumNativeReloadScenario(boolean verify) {
        this.verify = verify;
    }

    @Override
    public String id() {
        return verify ? "quantum.native_reload_verify" : "quantum.native_reload_prepare";
    }

    @Override
    public String description() {
        return verify
            ? "Distinct JVM: persisted secured node, reciprocal cable/interface edges and panel naturally recover"
            : "Save secured node with real cable/interface/panel endpoints for explicit next-JVM verification";
    }

    @Override
    public boolean includeInAll() {
        return false;
    }

    @Override
    public boolean preserveFixturesBeforeRun() {
        return verify;
    }

    @Override
    public int timeoutTicks() {
        return 900;
    }

    @Override
    public void start(TestContext ctx) {
        TestWorldFixtures.require(ctx, Config.quantumSafetyEnabled, "Global safety ON is required; no config mutation");
        owner = NativeSecurityStationSupport.actor(ctx, "owner");
        member = NativeSecurityStationSupport.actor(ctx, "member");
        NativeSecurityMatrix.assertOrdinaryActors(ctx, id(), owner, member);
        stage = 0;
        if (verify) {
            chest = (TileEntityChest) ctx.world()
                .getTileEntity(X + 4, Y, Z + 2);
            terminal = chest == null ? null : chest.getStackInSlot(0);
            TestWorldFixtures.require(
                ctx,
                terminal != null && terminal.hasTagCompound()
                    && terminal.getTagCompound()
                        .hasKey(MARKER),
                "Persisted fixture receipt exists");
            NBTTagCompound saved = terminal.getTagCompound()
                .getCompoundTag(MARKER);
            TestWorldFixtures
                .require(ctx, !PROCESS.equals(saved.getString("process")), "Verification runs in a distinct JVM");
            TestWorldFixtures.require(
                ctx,
                saved.getString("owner")
                    .equals(
                        owner.getUniqueID()
                            .toString())
                    && saved.getString("member")
                        .equals(
                            member.getUniqueID()
                                .toString()),
                "Persisted actor UUIDs match; no OP substituted");
            ctx.event(
                "cross_jvm",
                "prepareProcess=" + saved.getString("process")
                    + "; verifyProcess="
                    + PROCESS
                    + "; prepareRun="
                    + saved.getString("run")
                    + "; fixture loaded directly from region files; no NBT replay");
            terminal = chest.getStackInSlot(0);
            controller = ctx.world()
                .getTileEntity(AX, Y, AZ);
            station = ctx.world()
                .getTileEntity(AX + 1, Y, AZ);
            node = (TileEntityNetworkQuantumNode) ctx.world()
                .getTileEntity(X, Y, Z);
            direct = ctx.world()
                .getTileEntity(X - 1, Y, Z);
            cable = ctx.world()
                .getTileEntity(X + 1, Y, Z);
            downstream = ctx.world()
                .getTileEntity(X + 2, Y, Z);
            panel = (TileEntityNetworkInfoPanel) ctx.world()
                .getTileEntity(X, Y, Z - 1);
            TestWorldFixtures.require(
                ctx,
                node != null && direct != null
                    && cable instanceof TileCableBus
                    && downstream != null
                    && panel != null
                    && terminal != null,
                "All physical endpoints and original terminal survived disk load");
            TestWorldFixtures.require(
                ctx,
                saved.getLong("key") == ((ISecurityProvider) station).getSecurityKey(),
                "Native security key survived JVM restart");
            TestWorldFixtures.require(
                ctx,
                saved.getString("generation")
                    .equals(node.getAnchorGeneration()),
                "Logical anchor generation survived JVM restart");
            stage = 2;
            return;
        }
        controller = place(
            ctx,
            AX,
            AZ,
            AEApi.instance()
                .definitions()
                .blocks()
                .controller()
                .maybeStack(1)
                .get());
        station = place(
            ctx,
            AX + 1,
            AZ,
            AEApi.instance()
                .definitions()
                .blocks()
                .security()
                .maybeStack(1)
                .get());
        TestWorldFixtures.place(ctx.world(), X + 4, Y, Z + 2, Blocks.chest, 2);
        chest = (TileEntityChest) ctx.world()
            .getTileEntity(X + 4, Y, Z + 2);
        ctx.event(
            "reload_scope",
            "Real dedicated server disk save/shutdown/relaunch; no integrated-client or GUI claim. Anchor chunk loads after endpoint chunk.");
    }

    private TileEntity place(TestContext ctx, int x, int z, ItemStack stack) {
        return TestWorldFixtures.aeOwnedBlock(ctx.world(), x, Y, z, stack, owner);
    }

    @Override
    public void tick(TestContext ctx) throws Exception {
        NativeSecurityStationSupport.power(controller);
        if (stage == 0) {
            if (!NativeSecurityStationSupport.ready(controller, station)) return;
            TestWorldFixtures.require(
                ctx,
                NativeSecurityStationSupport
                    .insertCard(ctx, station, owner, NativeSecurityStationSupport.allPermissionsCard(member)) == null,
                "Actual native all-grants card installed for non-OP member");
            terminal = GTSWNItemList.ME_Network_Quantum_Terminal.get(1);
            click(ctx, owner, terminal, AX, Y, AZ);
            terminal = NativeSecurityStationSupport.encodeAt(ctx, terminal, station, owner, key -> {});
            TestWorldFixtures.require(
                ctx,
                QuantumTerminalAccess.setNetworkSafety(owner, terminal, true),
                "Production owner enables protected network");
            stage = 1;
            return;
        }
        if (stage == 1) {
            ISecurityGrid security = NativeSecurityStationSupport.node(controller)
                .getGrid()
                .getCache(ISecurityGrid.class);
            for (SecurityPermissions permission : SecurityPermissions.values())
                if (!security.hasPermission(member, permission)) return;
            TestWorldFixtures.place(ctx.world(), X, Y, Z, BlockRegistrar.networkQuantumNode, 0);
            node = (TileEntityNetworkQuantumNode) ctx.world()
                .getTileEntity(X, Y, Z);
            ItemNetworkQuantumTerminal.applyAnchorToNode(terminal, node, member);
            direct = place(
                ctx,
                X - 1,
                Z,
                AEApi.instance()
                    .definitions()
                    .blocks()
                    .iface()
                    .maybeStack(1)
                    .get());
            TestWorldFixtures.place(ctx.world(), X + 1, Y - 1, Z, Blocks.stone, 0);
            ItemStack wire = AEApi.instance()
                .definitions()
                .parts()
                .cableGlass()
                .stack(AEColor.Transparent, 1);
            owner.inventory.setInventorySlotContents(0, wire);
            owner.theItemInWorldManager
                .activateBlockOrUseItem(owner, ctx.world(), wire, X + 1, Y - 1, Z, 1, .5f, .5f, .5f);
            cable = ctx.world()
                .getTileEntity(X + 1, Y, Z);
            TestWorldFixtures.require(
                ctx,
                wire.stackSize == 0 && cable instanceof TileCableBus,
                "Native item placement consumes cable and creates real cable bus");
            downstream = place(
                ctx,
                X + 2,
                Z,
                AEApi.instance()
                    .definitions()
                    .blocks()
                    .iface()
                    .maybeStack(1)
                    .get());
            TestWorldFixtures.place(ctx.world(), X, Y, Z - 1, BlockRegistrar.networkInfoPanel, 3);
            panel = (TileEntityNetworkInfoPanel) ctx.world()
                .getTileEntity(X, Y, Z - 1);
            panel.bindOwner(owner.getUniqueID(), "native reload fixture");
            panel.getProxy()
                .setOwner(owner);
            stage = 2;
            return;
        }
        if (ctx.tick() % 40 == 0) ctx.event("natural_connectivity", facts());
        if (!connected()) {
            if (ctx.tick() >= 600) ctx.fail("Natural native adjacency did not recover: " + facts());
            return;
        }
        if (ctx.tick() < 100) return;
        NativeSecurityMatrix.assertOrdinaryActors(ctx, "completed", owner, member);
        TestWorldFixtures.require(
            ctx,
            node.getNativeOwnerID() == AEApi.instance()
                .registries()
                .players()
                .getID(member),
            "Persisted native node owner is the ordinary member");
        ISecurityGrid security = NativeSecurityStationSupport.node(controller)
            .getGrid()
            .getCache(ISecurityGrid.class);
        for (SecurityPermissions permission : SecurityPermissions.values()) TestWorldFixtures
            .require(ctx, security.hasPermission(member, permission), "Native permission retained: " + permission);
        QuantumNetworkData full = QuantumNetworkData.assemble(member, terminal);
        TestWorldFixtures.require(
            ctx,
            full != null && full.online && !full.managementOnly && full.networkSafetyEnabled,
            "Original persisted terminal delivers full production business data with safety ON");
        ctx.event("reciprocal_edges", facts());
        if (!verify) {
            NBTTagCompound receipt = new NBTTagCompound();
            receipt.setString("process", PROCESS);
            receipt.setString("run", ctx.runId());
            receipt.setString(
                "owner",
                owner.getUniqueID()
                    .toString());
            receipt.setString(
                "member",
                member.getUniqueID()
                    .toString());
            receipt.setLong("key", ((ISecurityProvider) station).getSecurityKey());
            receipt.setString("generation", node.getAnchorGeneration());
            terminal.getTagCompound()
                .setTag(MARKER, receipt);
            chest.setInventorySlotContents(0, terminal.copy());
            chest.markDirty();
            ctx.world()
                .saveAllChunks(true, null);
            ctx.event(
                "fixture_saved",
                "Actual region save completed; retained for next explicit JVM. process=" + PROCESS);
        }
        ctx.pass(
            verify
                ? "Distinct-JVM natural recovery: reciprocal node/interface, node/cable, cable/interface and panel edges; persisted identity/key/grants and full business data"
                : "Prepared and disk-saved naturally connected native fixture for a separate JVM; this phase alone is not reload proof");
    }

    private boolean connected() {
        return node.isLinked() && edge(core(), NativeSecurityStationSupport.node(direct))
            && edge(core(), NativeSecurityStationSupport.node(cable))
            && edge(NativeSecurityStationSupport.node(cable), NativeSecurityStationSupport.node(downstream))
            && edge(core(), NativeSecurityStationSupport.node(panel))
            && panel.isAEConnected()
            && NativeSecurityStationSupport.node(direct)
                .isActive()
            && NativeSecurityStationSupport.node(downstream)
                .isActive();
    }

    private IGridNode core() {
        return node == null ? null : node.getCoreGridNode();
    }

    private static boolean edge(IGridNode first, IGridNode second) {
        if (first == null || second == null) return false;
        for (IGridConnection connection : first.getConnections())
            if (connection.getOtherSide(first) == second) for (IGridConnection reverse : second.getConnections())
                if (reverse == connection && reverse.getOtherSide(second) == first) return true;
        return false;
    }

    private String facts() {
        return "linked=" + node.isLinked()
            + "; interface="
            + edge(core(), NativeSecurityStationSupport.node(direct))
            + "; cable="
            + edge(core(), NativeSecurityStationSupport.node(cable))
            + "; downstream="
            + edge(NativeSecurityStationSupport.node(cable), NativeSecurityStationSupport.node(downstream))
            + "; panelEdge="
            + edge(core(), NativeSecurityStationSupport.node(panel))
            + "; panelConnected="
            + panel.isAEConnected()
            + "; offline="
            + node.getOfflineReason();
    }

    private static void click(TestContext ctx, FakePlayer actor, ItemStack held, int x, int y, int z) {
        actor.inventory.setInventorySlotContents(0, held);
        actor.setSneaking(false);
        ((ItemNetworkQuantumTerminal) held.getItem())
            .onItemUseFirst(held, actor, ctx.world(), x, y, z, 1, .5f, .5f, .5f);
    }

    @Override
    public void cleanup(TestContext ctx) {
        if (owner != null) owner.inventory.clearInventory(null, -1);
        if (member != null) member.inventory.clearInventory(null, -1);
        // Persistent authored fixture intentionally remains, like the shared manager's standard retention contract.
    }
}
