package com.miaokatze.gtswn.common.testworld;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumConnectionMembership;
import com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkSecurityRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumTerminalAccess;
import com.miaokatze.gtswn.common.testworld.NativeSecurityStationSupport.RecordingActor;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.config.Config;

import appeng.api.AEApi;
import appeng.api.config.SecurityPermissions;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.security.ISecurityGrid;
import appeng.tile.AEBaseTile;

/** Native server callbacks only: clicking actor authority and historical node placement authority differ. */
final class NativeHistoricalNodeScenario implements TestScenario {

    private static final int X = -8, Y = TestWorldFixtures.Y, Z = 20, NX = -4, NZ = 24;
    private RecordingActor owner, member, historical;
    private TileEntity controller, station;
    private ItemStack ownerTerminal, memberTerminal, historicalTerminal;
    private TileEntityNetworkQuantumNode oldNode, newNode;
    private IGridConnection oldEdge;
    private NBTTagCompound historicalBinding;
    private final List<int[]> positions = new ArrayList<>();
    private final List<Long> introduced = new ArrayList<>();
    private int stage;

    public String id() {
        return "quantum.native_historical_actor";
    }

    public boolean includeInAll() {
        return false;
    }

    public int timeoutTicks() {
        return 1800;
    }

    public String description() {
        return "Native OFF-placed C node versus full-permission B status/placement, then C permission regrant; server callbacks, no TCP";
    }

    public void start(TestContext ctx) {
        positions.clear();
        introduced.clear();
        stage = 0;
        oldNode = newNode = null;
        oldEdge = null;
        owner = member = historical = null;
        controller = station = null;
        ownerTerminal = memberTerminal = historicalTerminal = null;
        TestWorldFixtures.requireTestWorld(ctx.world());
        if (!Config.quantumSafetyEnabled) throw new IllegalStateException("Requires existing global safety ON");
        for (int[] p : new int[][] { { X, Y, Z }, { X + 1, Y, Z }, { NX, Y - 1, Z }, { NX, Y, Z }, { NX, Y + 1, Z },
            { NX, Y - 1, NZ }, { NX, Y, NZ } }) {
            require(
                ctx,
                ctx.world()
                    .isAirBlock(p[0], p[1], p[2]),
                "Every historical fixture position is empty before the first block mutation");
            require(
                ctx,
                noEntry(ctx, p[0], p[1], p[2]),
                "No reserved historical position has an existing incorporation entry");
        }
        owner = NativeSecurityStationSupport.actor(ctx, "owner");
        member = NativeSecurityStationSupport.actor(ctx, "member");
        historical = NativeSecurityStationSupport.actor(ctx, "outsider");
        NativeSecurityMatrix.assertOrdinaryActors(ctx, "historical identity", owner, member, historical);
        controller = place(
            ctx,
            X,
            Y,
            Z,
            AEApi.instance()
                .definitions()
                .blocks()
                .controller()
                .maybeStack(1)
                .get());
        station = place(
            ctx,
            X + 1,
            Y,
            Z,
            AEApi.instance()
                .definitions()
                .blocks()
                .security()
                .maybeStack(1)
                .get());
        for (int z : new int[] { Z, NZ }) {
            positions.add(new int[] { NX, Y - 1, z });
            positions.add(new int[] { NX, Y, z });
            TestWorldFixtures.place(ctx.world(), NX, Y - 1, z, Blocks.stone, 0);
        }
        positions.add(new int[] { NX, Y + 1, Z });
        TestWorldFixtures.place(ctx.world(), NX, Y + 1, Z, Blocks.stone, 0);
        ctx.result.transport = "Synthetic native item and block callbacks with natural AE ticks; no socket, client login or TCP claim";
    }

    public void tick(TestContext ctx) throws Exception {
        NativeSecurityStationSupport.power(controller);
        if (!NativeSecurityStationSupport.ready(controller, station)) return;
        if (stage == 0) {
            require(
                ctx,
                NativeSecurityStationSupport
                    .insertCard(ctx, station, owner, NativeSecurityStationSupport.allPermissionsCard(member)) == null,
                "Owner grants B all actual native permissions");
            ownerTerminal = GTSWNItemList.ME_Network_Quantum_Terminal.get(1);
            owner.inventory.mainInventory[0] = ownerTerminal;
            owner.setSneaking(false);
            ((ItemNetworkQuantumTerminal) ownerTerminal.getItem())
                .onItemUseFirst(ownerTerminal, owner, ctx.world(), X, Y, Z, 1, .5f, .5f, .5f);
            ownerTerminal = NativeSecurityStationSupport.encodeAt(ctx, ownerTerminal, station, owner, introduced::add);
            owner.inventory.mainInventory[0] = ownerTerminal;
            memberTerminal = ownerTerminal.copy();
            historicalTerminal = ownerTerminal.copy();
            member.inventory.mainInventory[0] = memberTerminal;
            historical.inventory.mainInventory[0] = historicalTerminal;
            require(ctx, !policy(ctx), "Historical placement starts with local safety OFF");
            placeByItem(ctx, historical, historicalTerminal, Z);
            oldNode = (TileEntityNetworkQuantumNode) ctx.world()
                .getTileEntity(NX, Y, Z);
            require(ctx, oldNode != null, "C real item callback placed the old node while OFF");
            stage = 1;
        } else if (stage == 1 && linked(oldNode)) {
            oldEdge = edge(oldNode);
            historicalBinding = binding(oldNode);
            require(ctx, oldNode.getNativeOwnerID() == actorId(historical), "Old node stores C native identity");
            require(
                ctx,
                QuantumTerminalAccess.setNetworkSafety(owner, ownerTerminal, true),
                "A enables safety on the same real native network");
            stage = 2;
        } else if (stage == 2 && !oldNode.isLinked()
            && oldNode.getOfflineReason() == TileEntityNetworkQuantumNode.OfflineReason.NO_PERMISSION) {
                require(
                    ctx,
                    !QuantumConnectionMembership.contains(oldEdge.a(), oldEdge)
                        && !QuantumConnectionMembership.contains(oldEdge.b(), oldEdge),
                    "ON naturally removes C historical edge from both native endpoint lists");
                for (SecurityPermissions permission : SecurityPermissions.values())
                    if (!security().hasPermission(member, permission)) return;
                require(
                    ctx,
                    QuantumTerminalAccess.validate(member, memberTerminal),
                    "B full native authority and valid same-grid terminal are independently true");
                member.setPosition(NX + .5, Y, Z + 2.5);
                member.setSneaking(false);
                member.clearMessages();
                NBTTagCompound before = snapshot(member, memberTerminal, oldNode);
                ((ItemNetworkQuantumTerminal) memberTerminal.getItem())
                    .onItemUseFirst(memberTerminal, member, ctx.world(), NX, Y, Z, 1, .5f, .5f, .5f);
                require(
                    ctx,
                    before.equals(snapshot(member, memberTerminal, oldNode)),
                    "B occupied-face item callback neither rejects native authority nor changes old node/resources");
                List<String> itemMessages = member.messages();
                member.clearMessages();
                require(
                    ctx,
                    !itemMessages.toString()
                        .contains("gtswn.chat.quantum.no_permission"),
                    "Authorized B item callback emits no actor-permission denial");
                observeStatusWithEmptyHand(ctx);
                require(
                    ctx,
                    member.messages()
                        .toString()
                        .contains("gtswn.chat.quantum.no_permission"),
                    "Actual block status callback reports old node NO_PERMISSION even though B currently has full authority");
                require(
                    ctx,
                    before.equals(snapshot(member, memberTerminal, oldNode))
                        && historicalBinding.equals(binding(oldNode)),
                    "Status callback preserves terminal, inventory and historical C binding");
                ctx.event(
                    "REPRODUCED_HISTORICAL_NODE_STATUS",
                    "clickingActor=" + member.getUniqueID()
                        + "; currentNativeActor="
                        + actorId(member)
                        + "; nodePlacer="
                        + oldNode.getPlacerUUID()
                        + "; nodeNativeActor="
                        + oldNode.getNativeOwnerID()
                        + "; reason="
                        + oldNode.getOfflineReason()
                        + "; itemMessages="
                        + itemMessages
                        + "; blockStatusMessages="
                        + member.messages()
                        + "; status used ordinary ItemInWorldManager empty-hand route after validating and temporarily stowing the same terminal, no TCP claim");
                placeByItem(ctx, member, memberTerminal, NZ);
                newNode = (TileEntityNetworkQuantumNode) ctx.world()
                    .getTileEntity(NX, Y, NZ);
                require(
                    ctx,
                    newNode != null,
                    "B full-permission real item callback places its own fresh node while ON");
                stage = 3;
            } else if (stage == 3 && linked(newNode)) {
                require(
                    ctx,
                    newNode.getNativeOwnerID() == actorId(member) && newNode.getPlacerUUID()
                        .equals(
                            member.getUniqueID()
                                .toString()),
                    "Fresh B node naturally links under B, without taking over C node");
                require(
                    ctx,
                    historicalBinding.equals(binding(oldNode)),
                    "B operations never changed historical C node ownership or anchor");
                require(
                    ctx,
                    NativeSecurityStationSupport.insertCard(
                        ctx,
                        station,
                        owner,
                        NativeSecurityStationSupport.card(historical, SecurityPermissions.BUILD)) == null,
                    "A grants actual BUILD to C through the native card inventory");
                stage = 4;
            } else
                if (stage == 4 && security().hasPermission(historical, SecurityPermissions.BUILD) && linked(oldNode)) {
                    require(
                        ctx,
                        historicalBinding.equals(binding(oldNode)),
                        "The same C node naturally recovers after permission regrant without rewriting owner or connecting manually");
                    member.clearMessages();
                    observeStatusWithEmptyHand(ctx);
                    require(
                        ctx,
                        member.messages()
                            .toString()
                            .contains("gtswn.chat.quantum.node_online"),
                        "Native block status now reports ONLINE to the same B observer");
                    ctx.pass(
                        "REPRODUCED_HISTORICAL_NODE_STATUS: B is authorized; old C node status follows C identity; B fresh node and C natural regrant recovery passed");
                }
    }

    private TileEntity place(TestContext ctx, int x, int y, int z, ItemStack item) {
        positions.add(new int[] { x, y, z });
        return TestWorldFixtures.aeOwnedBlock(ctx.world(), x, y, z, item, owner);
    }

    private static boolean noEntry(TestContext ctx, int x, int y, int z) {
        for (com.miaokatze.gtswn.common.quantum.QuantumIncorporationRegistry.Entry entry : com.miaokatze.gtswn.common.quantum.QuantumIncorporationRegistry
            .get(ctx.world())
            .snapshot()) if (entry.position == QuantumControllerRegistry.pack(x, y, z)) return false;
        return true;
    }

    private void observeStatusWithEmptyHand(TestContext ctx) {
        ItemStack held = member.inventory.mainInventory[0], spare = member.inventory.mainInventory[1];
        require(
            ctx,
            held == memberTerminal && spare == null,
            "Only the owned synthetic terminal and empty spare slot are used for status observation");
        member.inventory.mainInventory[1] = held;
        member.inventory.mainInventory[0] = null;
        try {
            member.theItemInWorldManager.activateBlockOrUseItem(member, ctx.world(), null, NX, Y, Z, 1, .5f, .5f, .5f);
        } finally {
            member.inventory.mainInventory[0] = held;
            member.inventory.mainInventory[1] = spare;
        }
    }

    private void placeByItem(TestContext ctx, RecordingActor actor, ItemStack terminal, int z) {
        actor.inventory.currentItem = 0;
        actor.inventory.mainInventory[0] = terminal;
        actor.setSneaking(false);
        actor.setPosition(NX + .5, Y, z + 2.5);
        require(
            ctx,
            !actor.capabilities.isCreativeMode && !actor.canCommandSenderUseCommand(2, "gtswn")
                && !actor.canCommandSenderUseCommand(4, "gtswn"),
            "Placement actor has no creative/OP bypass");
        ((ItemNetworkQuantumTerminal) terminal.getItem())
            .onItemUseFirst(terminal, actor, ctx.world(), NX, Y - 1, z, 1, .5f, 1, .5f);
    }

    private ISecurityGrid security() {
        return NativeSecurityStationSupport.node(controller)
            .getGrid()
            .getCache(ISecurityGrid.class);
    }

    private static int actorId(RecordingActor actor) {
        return AEApi.instance()
            .registries()
            .players()
            .getID(actor);
    }

    private boolean policy(TestContext ctx) {
        return QuantumTerminalAccess.isNetworkSafetyEnabled(ctx.world(), X, Y, Z);
    }

    private static IGridConnection edge(TileEntityNetworkQuantumNode node) throws Exception {
        Field f = TileEntityNetworkQuantumNode.class.getDeclaredField("connection");
        f.setAccessible(true);
        return (IGridConnection) f.get(node);
    }

    private boolean linked(TileEntityNetworkQuantumNode node) throws Exception {
        IGridConnection edge = node == null ? null : edge(node);
        return node != null && node.isLinked()
            && edge != null
            && QuantumConnectionMembership.contains(edge.a(), edge)
            && QuantumConnectionMembership.contains(edge.b(), edge)
            && edge.a()
                .getGrid()
                == edge.b()
                    .getGrid()
            && edge.a()
                .getGrid()
                == NativeSecurityStationSupport.node(controller)
                    .getGrid();
    }

    private static NBTTagCompound binding(TileEntityNetworkQuantumNode node) {
        NBTTagCompound n = new NBTTagCompound();
        n.setString("placer", node.getPlacerUUID());
        n.setInteger("nativeActor", node.getNativeOwnerID());
        n.setInteger("dim", node.getAnchorDim());
        n.setInteger("x", node.getAnchorX());
        n.setInteger("y", node.getAnchorY());
        n.setInteger("z", node.getAnchorZ());
        n.setString("generation", node.getAnchorGeneration());
        return n;
    }

    private static NBTTagCompound snapshot(RecordingActor actor, ItemStack terminal, TileEntity node) {
        NBTTagCompound n = new NBTTagCompound(), tile = new NBTTagCompound();
        node.writeToNBT(tile);
        n.setTag("node", tile);
        n.setTag("terminal", terminal.writeToNBT(new NBTTagCompound()));
        n.setTag("inventory", actor.inventory.writeToNBT(new NBTTagList()));
        return (NBTTagCompound) n.copy();
    }

    private static void require(TestContext ctx, boolean condition, String detail) {
        TestWorldFixtures.require(ctx, condition, detail);
    }

    public void cleanup(TestContext ctx) {
        for (int i = positions.size() - 1; i >= 0; i--) {
            int[] p = positions.get(i);
            TileEntity tile = ctx.world()
                .getTileEntity(p[0], p[1], p[2]);
            if (tile instanceof AEBaseTile) ((AEBaseTile) tile).disableDrops();
            ctx.world()
                .setBlockToAir(p[0], p[1], p[2]);
        }
        if (controller != null) QuantumControllerRegistry.get(ctx.world())
            .dequantize(X, Y, Z);
        for (long key : introduced) TestFixtureAccess
            .forgetProvider(QuantumNetworkSecurityRegistry.providerRegistry(ctx.world()), ctx.world(), key);
        for (RecordingActor actor : new RecordingActor[] { owner, member, historical })
            if (actor != null) actor.inventory.clearInventory(null, -1);
        ctx.event(
            "cleanup",
            "Exact historical-node/controller/station/support fixtures removed; no online enrollment or packet queue was used");
        positions.clear();
        introduced.clear();
    }
}
