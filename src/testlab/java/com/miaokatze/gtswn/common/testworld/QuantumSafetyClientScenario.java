// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.testworld;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.server.S09PacketHeldItemChange;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.Teleporter;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.ForgeDirection;

import com.google.gson.GsonBuilder;
import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.gui.ContainerTerminal;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.items.TerminalAirInteraction;
import com.miaokatze.gtswn.common.monitoring.MonitoringProfiles;
import com.miaokatze.gtswn.common.quantum.QuantumConnectionMembership;
import com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkSecurityRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumTerminalAccess;
import com.miaokatze.gtswn.common.quantum.QuantumTerminalRequestQueue;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.common.util.DebugTestGate;
import com.miaokatze.gtswn.common.util.PlayerRequestQueue;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.network.PacketRequestQuantumTerminalData;
import com.miaokatze.gtswn.network.PacketSetQuantumNetworkSafety;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.config.SecurityPermissions;
import appeng.api.implementations.items.IBiometricCard;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.pathing.ControllerState;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.networking.security.ISecurityProvider;
import appeng.api.networking.security.PlayerSource;
import appeng.api.storage.ITerminalHost;
import appeng.api.storage.data.IAEItemStack;
import appeng.me.GridAccessException;
import appeng.me.helpers.IGridProxyable;
import appeng.tile.AEBaseTile;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLEmbeddedChannel;
import cpw.mods.fml.common.network.FMLIndexedMessageToMessageCodec;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.relauncher.Side;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/** Console-owned, explicit real TCP actor case; no business handler is invoked by the fixture. */
public final class QuantumSafetyClientScenario implements TestScenario {

    public static final String RUN = "GTSWN_QuantumSafety_Run", PHASE = "GTSWN_QuantumSafety_Phase";
    private static final String BACKUP = "GTSWN_QuantumSafety_Backup_v1", OBSERVER = "gtswn-safety-server-observer";
    private static final int X = 8, Y = TestWorldFixtures.Y, Z = 12;
    private static final int NODE_X = 10, NODE_Y = 65, NODE_Z = 14;
    private static final int ALL_NODE_Z = 16;
    private static QuantumSafetyClientScenario active;
    private final ArrayBlockingQueue<Wire> observed = new ArrayBlockingQueue<>(256);
    private TestContext context;
    private EntityPlayerMP actor;
    private FakePlayer owner;
    private Object connection, grid;
    private TileEntity controller, station;
    private ItemStack terminal, card;
    private ItemStack ownerTerminal;
    private TileEntityNetworkQuantumNode buildNode;
    private TileEntityNetworkQuantumNode allGrantsNode;
    private boolean allGrantsReadRequest, allGrantsPositive;
    private boolean allGrantsPendingRecorded;
    private long lastAllGrantsSample;
    private Map<String, Object> lastAllGrantsFullFacts;
    private boolean buildFixtureReserved, buildPositive, buildDeniedRequest;
    private FMLEmbeddedChannel channel;
    private NBTTagCompound original, terminalAtRevoke, controllersAtRevoke, securityAtRevoke, providersAtRevoke;
    private String identity, key;
    private volatile int stage;
    private volatile Wire pendingStale;
    private Wire witnessedStale;
    private int requests, intents;
    private long stationKey, introducedKey, retainedToken, contextDeadline, staleArrived, witnessedTick;
    private boolean introducedProvider, controllerPlaced, stationPlaced, backupWritten, granted, staleObserved,
        listenerRegistered, airWitness, guiWitness;

    public String id() {
        return "quantum.safety_client";
    }

    public boolean includeInAll() {
        return false;
    }

    public int timeoutTicks() {
        return 1800;
    }

    public String description() {
        return "Real non-OP BUILD teammate: encoded terminal, C08 binding/node/native link, full GUI data and revocation; then SECURITY toggle/stale-intent chain";
    }

    public void start(TestContext ctx) {
        if (active != null) throw new IllegalStateException("Safety client fixture already active");
        actor = null;
        owner = null;
        controller = station = null;
        terminal = card = null;
        ownerTerminal = null;
        buildNode = null;
        allGrantsNode = null;
        allGrantsReadRequest = allGrantsPositive = false;
        allGrantsPendingRecorded = false;
        lastAllGrantsSample = -20;
        lastAllGrantsFullFacts = null;
        buildFixtureReserved = buildPositive = buildDeniedRequest = false;
        channel = null;
        original = terminalAtRevoke = controllersAtRevoke = securityAtRevoke = providersAtRevoke = null;
        connection = grid = null;
        identity = key = null;
        stage = requests = intents = 0;
        stationKey = introducedKey = retainedToken = contextDeadline = staleArrived = 0;
        pendingStale = witnessedStale = null;
        witnessedTick = -1;
        introducedProvider = controllerPlaced = stationPlaced = backupWritten = granted = staleObserved = false;
        airWitness = guiWitness = false;
        observed.clear();
        context = ctx;
        DebugTestGate.requireEnabled();
        TestWorldFixtures.requireTestWorld(ctx.world());
        if (!Config.quantumSafetyEnabled)
            throw new IllegalStateException("Requires existing global safety ON; Config is not changed");
        for (Object candidate : MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList) {
            EntityPlayerMP player = (EntityPlayerMP) candidate;
            if (player instanceof FakePlayer) continue;
            if (actor != null) throw new IllegalStateException("Exactly one actual online actor is required");
            actor = player;
        }
        require(actor != null, "Actual TCP actor is online");
        ordinary();
        require(actor.inventory.getItemStack() == null && !TestUiKit.hasBackup(actor), "No active UI fixture/cursor");
        require(!persisted(actor).hasKey(BACKUP, 10), "No pending safety recovery backup");
        connection = actor.playerNetServerHandler;
        require(
            !actor.playerNetServerHandler.netManager.isLocalChannel()
                && actor.playerNetServerHandler.netManager.getSocketAddress() instanceof InetSocketAddress,
            "Actual actor uses a real TCP socket, not an integrated local channel");
        ctx.result.actor = "actual online non-OP actor UUID=" + actor.getUniqueID()
            + "; fixed TCP connection; BUILD-only teammate then SECURITY-only manager";
        ctx.result.actorLevel2 = false;
        ctx.result.actorLevel4 = false;
        original = new NBTTagCompound();
        original.setString("Run", ctx.runId());
        original.setInteger("Selected", actor.inventory.currentItem);
        original.setInteger("Dimension", actor.dimension);
        original.setBoolean("Sneaking", actor.isSneaking());
        original.setDouble("X", actor.posX);
        original.setDouble("Y", actor.posY);
        original.setDouble("Z", actor.posZ);
        original.setFloat("Yaw", actor.rotationYaw);
        original.setFloat("Pitch", actor.rotationPitch);
        ItemStack old = actor.inventory.mainInventory[0];
        if (old != null) original.setTag("Stack", old.writeToNBT(new NBTTagCompound()));
        persisted(actor).setTag(BACKUP, original.copy());
        save(actor);
        backupWritten = true;
        active = this;
        actor.closeScreen();
        transfer(actor, ctx.world(), X + .5, Y, Z + 2.5, 0, 0);
        owner = TestWorldFixtures.player(ctx.world(), TestWorldFixtures.owner("safety-client:" + ctx.runId()));
        owner.setPosition(X + .5, Y, Z + 2.5);
        require(
            !owner.canCommandSenderUseCommand(2, "gtswn") && !owner.canCommandSenderUseCommand(4, "gtswn"),
            "Synthetic fixture owner is ordinary");
        controllerPlaced = true;
        controller = TestWorldFixtures.aeOwnedBlock(
            ctx.world(),
            X,
            Y,
            Z,
            AEApi.instance()
                .definitions()
                .blocks()
                .controller()
                .maybeStack(1)
                .get(),
            owner);
        stationPlaced = true;
        station = TestWorldFixtures.aeOwnedBlock(
            ctx.world(),
            X + 1,
            Y,
            Z,
            AEApi.instance()
                .definitions()
                .blocks()
                .security()
                .maybeStack(1)
                .get(),
            owner);
        channel = NetworkRegistry.INSTANCE.getChannel("gtswn", Side.SERVER);
        require(channel != null, "Native server FML channel exists");
        ChannelHandlerContext codec = channel.pipeline()
            .context(FMLIndexedMessageToMessageCodec.class);
        require(codec != null, "Native server FML decoder exists");
        channel.pipeline()
            .addAfter(codec.name(), OBSERVER, new ChannelInboundHandlerAdapter() {

                @Override
                public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
                    if (ctx.channel()
                        .attr(NetworkRegistry.NET_HANDLER)
                        .get() == connection) {
                        Wire wire = null;
                        if (message instanceof PacketRequestQuantumTerminalData)
                            wire = new Wire(message, 5, 0, false, 0, "", null);
                        if (message instanceof PacketSetQuantumNetworkSafety) wire = new Wire(
                            message,
                            29,
                            ((Number) field(message, "token")).longValue(),
                            (Boolean) field(message, "enabled"),
                            ((Number) field(message, "slot")).intValue(),
                            (String) field(message, "identity"),
                            (int[]) field(message, "anchor"));
                        if (wire != null) {
                            if (wire.discriminator == 29 && stage == 7 && pendingStale == null) pendingStale = wire;
                            if (!observed.offer(wire))
                                throw new IllegalStateException("Safety observer capacity exceeded");
                        }
                    }
                    ctx.fireChannelRead(message);
                }
            });
        FMLCommonHandler.instance()
            .bus()
            .register(this);
        listenerRegistered = true;
        ctx.event(
            "transport_observer",
            "Actual server FML decoded observer after=" + codec.name() + " actor=" + actor.getUniqueID());
    }

    /** Read-only witness before the untouched production NORMAL-priority safety drain runs. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void beforeSafetyDrain(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || active != this
            || stage != 7
            || pendingStale == null
            || witnessedStale != null) return;
        try {
            Wire wire = pendingStale;
            // The production Handler must already have enqueued this exact decoded object. Never enqueue it here.
            if (!isPendingInProduction(wire)) return;
            ordinary();
            require(
                QuantumTerminalRequestQueue.isOnline(actor) && actor.playerNetServerHandler == connection
                    && actor.getHeldItem() == terminal
                    && actor.inventory.currentItem == 0,
                "Pre-drain actual online actor/connection/slot/stack are unchanged");
            require(
                (Boolean) field(wire.message, "valid") && wire.token == retainedToken
                    && wire.slot == 0
                    && !wire.enabled
                    && identity.equals(wire.identity)
                    && Arrays.equals(wire.anchor, ItemNetworkQuantumTerminal.getAnchor(terminal)),
                "Pre-drain actual decoded stale packet is valid and all outer context guards match");
            requireLiveContext();
            Object capability = ((Map<?, ?>) field(null, PacketSetQuantumNetworkSafety.class, "CONTEXTS"))
                .get(actor.getUniqueID());
            Method matches = capability.getClass()
                .getDeclaredMethod("matches", EntityPlayerMP.class, ItemStack.class, String.class);
            matches.setAccessible(true);
            require(
                identity.equals(QuantumTerminalAccess.networkSafetyIdentity(context.world(), X, Y, Z)) && policy()
                    && !security().hasPermission(actor, SecurityPermissions.SECURITY)
                    && !QuantumTerminalAccess.canChangeNetworkSafety(actor, terminal),
                "Pre-drain controller generation is unchanged and native SECURITY is actually revoked");
            require(
                Boolean.TRUE.equals(matches.invoke(capability, actor, terminal, wire.identity)),
                "Pre-drain real production Context.matches is true, including TTL/key/anchor/stack");
            witnessedStale = wire;
            witnessedTick = context.tick();
            context.event(
                "pre_drain_native_acl_witness",
                "Actual Handler job is pending; Context.matches=true; token/slot/anchor/key/generation/TTL valid; native SECURITY=false");
        } catch (Exception | LinkageError failure) {
            context.callbackFailure("pre-drain safety witness", failure);
        }
    }

    /** Read only under the production pending lock; never invokes queue methods or changes jobs. */
    private boolean isPendingInProduction(Wire wire) throws Exception {
        Object queue = field(null, PacketSetQuantumNetworkSafety.class, "QUEUE");
        Object pending = field(queue, PlayerRequestQueue.class, "pending");
        synchronized (pending) {
            for (Object job : (Iterable<?>) pending)
                if (field(job, "player") == actor && field(job, "message") == wire.message) return true;
        }
        return false;
    }

    public void tick(TestContext ctx) throws Exception {
        ordinary();
        guard(
            QuantumTerminalRequestQueue.isOnline(actor) && actor.playerNetServerHandler == connection
                && actor.worldObj == ctx.world(),
            "Actor connection/world remains fixed");
        power();
        if (stage == 0) {
            if (!ready()) return;
            int ownerId = AEApi.instance()
                .registries()
                .players()
                .getID(owner);
            require(
                ((ISecurityProvider) station).getOwner() == ownerId
                    && ((IGridHost) controller).getGridNode(ForgeDirection.UNKNOWN)
                        .getPlayerID() == ownerId,
                "Native placement assigned controller and station ownership to fixture owner");
            require(security().hasPermission(owner, SecurityPermissions.SECURITY), "Real station owner has SECURITY");
            require(
                !owner.getUniqueID()
                    .equals(actor.getUniqueID()),
                "Actual BUILD teammate is a different native station/controller owner");
            card = NativeSecurityStationSupport.card(actor, SecurityPermissions.BUILD);
            IAEItemStack remainder = ((ITerminalHost) station).getItemInventory()
                .injectItems(
                    AEApi.instance()
                        .storage()
                        .createItemStack(card.copy()),
                    Actionable.MODULATE,
                    new PlayerSource(owner, (IActionHost) station));
            require(remainder == null, "Real actor BUILD-only card inserted natively");
            ownerTerminal = GTSWNItemList.ME_Network_Quantum_Terminal.get(1);
            owner.inventory.mainInventory[0] = ownerTerminal;
            owner.setSneaking(false);
            ((ItemNetworkQuantumTerminal) ownerTerminal.getItem())
                .onItemUseFirst(ownerTerminal, owner, ctx.world(), X, Y, Z, 1, .5f, .5f, .5f);
            require(
                ItemNetworkQuantumTerminal.isBound(ownerTerminal),
                "Owner production click quantized the fixture controller");
            terminal = GTSWNItemList.ME_Network_Quantum_Terminal.get(1);
            terminal = NativeSecurityStationSupport.encodeAt(ctx, terminal, station, owner, value -> {
                introducedProvider = true;
                introducedKey = value;
            });
            stationKey = ((ISecurityProvider) station).getSecurityKey();
            key = terminal.getTagCompound()
                .getString(QuantumTerminalAccess.NBT_ENCRYPTION_KEY);
            identity = QuantumTerminalAccess.networkSafetyIdentity(ctx.world(), X, Y, Z);
            grid = ((IGridHost) controller).getGridNode(ForgeDirection.UNKNOWN)
                .getGrid();
            require(
                !ItemNetworkQuantumTerminal.isBound(terminal) && !MonitoringProfiles.hasProfileReference(terminal),
                "Actor receives a real encoded terminal with no anchor or managed Profile");
            require(
                QuantumTerminalAccess.setNetworkSafety(owner, ownerTerminal, true),
                "Native owner enables safety before teammate use");
            require(
                ctx.world()
                    .isAirBlock(NODE_X, NODE_Y, NODE_Z)
                    && ctx.world()
                        .isAirBlock(NODE_X, NODE_Y - 1, NODE_Z),
                "Exact teammate node/support positions are initially empty");
            require(
                ctx.world()
                    .isAirBlock(NODE_X, NODE_Y, ALL_NODE_Z)
                    && ctx.world()
                        .isAirBlock(NODE_X, NODE_Y - 1, ALL_NODE_Z),
                "Exact all-grants node/support positions are initially empty");
            buildFixtureReserved = true;
            TestWorldFixtures.place(ctx.world(), NODE_X, NODE_Y - 1, NODE_Z, Blocks.stone, 0);
            TestWorldFixtures.place(ctx.world(), NODE_X, NODE_Y - 1, ALL_NODE_Z, Blocks.stone, 0);
            stage = 1;
            return;
        }
        if (stage == 1) {
            if (!ready() || !security().hasPermission(actor, SecurityPermissions.BUILD)) return;
            require(onlyNativePermission(SecurityPermissions.BUILD), "Actual teammate has BUILD but no SECURITY");
            require(policy(), "Teammate ordinary-use preface starts with safety ON");
            terminal.getTagCompound()
                .setString(RUN, ctx.runId());
            terminal.getTagCompound()
                .setString(PHASE, "build_bind");
            terminal.getTagCompound()
                .setBoolean("GTSWN_QuantumSafety_ActorLevel2", false);
            terminal.getTagCompound()
                .setBoolean("GTSWN_QuantumSafety_ActorLevel4", false);
            terminal.getTagCompound()
                .setTag("GTSWN_QuantumSafety_Original", original.copy());
            actor.inventory.mainInventory[0] = terminal;
            granted = true;
            actor.inventory.currentItem = 0;
            sync();
            stage = -10;
            ctx.event(
                "fixture_ready",
                "Run=" + ctx.runId()
                    + " real non-OP BUILD-only teammate="
                    + actor.getUniqueID()
                    + " slot=0 policy=ON; BUILD-only; encoded unbound terminal");
        }
        guard(
            actor.inventory.currentItem == 0 && actor.getHeldItem() == terminal,
            "Actual server slot and stack identity unchanged");
        guard(
            key.equals(
                terminal.getTagCompound()
                    .getString(QuantumTerminalAccess.NBT_ENCRYPTION_KEY))
                && identity.equals(QuantumTerminalAccess.networkSafetyIdentity(ctx.world(), X, Y, Z)),
            "Native key and controller identity unchanged");
        if (stage == -10 && ItemNetworkQuantumTerminal.isBound(terminal)) {
            int[] bound = ItemNetworkQuantumTerminal.getAnchor(terminal);
            require(
                Arrays.equals(bound, new int[] { ctx.world().provider.dimensionId, X, Y, Z })
                    && !terminal.getTagCompound()
                        .getString(ItemNetworkQuantumTerminal.NBT_ANCHOR_GENERATION)
                        .isEmpty(),
                "Actual client controller C08 bound the encoded teammate terminal to the live generation");
            require(
                QuantumTerminalAccess.validate(actor, terminal) && !QuantumTerminalAccess.canManage(actor, terminal),
                "Bound BUILD-only teammate can use but cannot manage");
            terminal.getTagCompound()
                .setString(PHASE, "build_bound");
            sync();
            stage = -9;
            ctx.event(
                "teammate_bound",
                "Actual ordinary client C08 bound the previously unbound native-encoded terminal; native owner differs from actor");
        }
        if (stage == -9) {
            TileEntity candidate = ctx.world()
                .getTileEntity(NODE_X, NODE_Y, NODE_Z);
            if (candidate instanceof TileEntityNetworkQuantumNode) buildNode = (TileEntityNetworkQuantumNode) candidate;
            if (buildNode != null && buildNode.isLinked()) {
                IGridConnection connection = (IGridConnection) field(buildNode, "connection");
                require(
                    buildNode.getNativeOwnerID() == AEApi.instance()
                        .registries()
                        .players()
                        .getID(actor) && buildNode.getNativeOwnerID() != ((ISecurityProvider) station).getOwner(),
                    "Actual C08-created node carries teammate native actor, distinct from owner");
                require(
                    buildNode.getAnchorDim() == ctx.world().provider.dimensionId && buildNode.getAnchorX() == X
                        && buildNode.getAnchorY() == Y
                        && buildNode.getAnchorZ() == Z
                        && buildNode.getAnchorGeneration()
                            .equals(
                                terminal.getTagCompound()
                                    .getString(ItemNetworkQuantumTerminal.NBT_ANCHOR_GENERATION)),
                    "Client-placed node has the exact live anchor/generation");
                require(
                    connection != null && QuantumConnectionMembership.contains(connection.a(), connection)
                        && QuantumConnectionMembership.contains(connection.b(), connection)
                        && connection.a()
                            .getGrid()
                            == connection.b()
                                .getGrid()
                        && connection.a()
                            .getGrid()
                            == ((IGridHost) controller).getGridNode(ForgeDirection.UNKNOWN)
                                .getGrid(),
                    "Natural real AE bridge exists in both endpoint lists on the controller grid");
                grid = ((IGridHost) controller).getGridNode(ForgeDirection.UNKNOWN)
                    .getGrid();
                buildPositive = true;
                stage = -8;
                terminal.getTagCompound()
                    .setString(PHASE, "build_linked");
                sync();
                ctx.event(
                    "teammate_node_linked",
                    "Actual C08 node placement and natural native AE two-ended link passed; no fixture node placement or handler call");
            }
        }
        if (stage == -7 && !security().hasPermission(actor, SecurityPermissions.BUILD)) {
            require(
                policy() && !security().hasPermission(actor, SecurityPermissions.SECURITY)
                    && !QuantumTerminalAccess.validate(actor, terminal),
                "Removing the real BUILD card denies teammate ordinary access with the same valid stack/key");
            if (buildNode.isLinked()) return;
            stage = -6;
            writeFixtureReceipt("build-revoked");
        }
        if (stage == -5 && allNativePermissions()) {
            guard(policy(), "All-native-grants teammate must operate with safety ON");
            if (ctx.tick() - lastAllGrantsSample < 20) return;
            lastAllGrantsSample = ctx.tick();
            QuantumNetworkData full = QuantumNetworkData.assemble(actor, terminal);
            Map<String, Object> facts = allGrantsFullFacts(ctx, full, "natural-readiness");
            boolean ready = Boolean.TRUE.equals(facts.get("bridgeReady"))
                && Boolean.TRUE.equals(facts.get("fullBusinessReady"));
            if (!allGrantsPendingRecorded || ready) {
                ctx.event(
                    ready ? "all_grants_ready" : "all_grants_pending",
                    new GsonBuilder().serializeNulls()
                        .create()
                        .toJson(facts));
                allGrantsPendingRecorded = true;
            }
            if (!ready) return;
            airWitness = guiWitness = false;
            stage = -4;
            terminal.getTagCompound()
                .setString(PHASE, "ready");
            sync();
            writeFixtureReceipt("security-ready");
        }
        if (stage == -4 && allGrantsReadRequest) {
            TileEntity candidate = ctx.world()
                .getTileEntity(NODE_X, NODE_Y, ALL_NODE_Z);
            if (candidate instanceof TileEntityNetworkQuantumNode)
                allGrantsNode = (TileEntityNetworkQuantumNode) candidate;
            if (allGrantsNode != null && allGrantsNode.isLinked()) {
                IGridConnection edge = (IGridConnection) field(allGrantsNode, "connection");
                require(
                    allNativePermissions() && policy() && !MonitoringProfiles.hasProfileReference(terminal),
                    "All-native-grants C08 operation retains ON policy and no managed Profile");
                require(
                    allGrantsNode.getNativeOwnerID() == AEApi.instance()
                        .registries()
                        .players()
                        .getID(actor) && allGrantsNode.getNativeOwnerID() != ((ISecurityProvider) station).getOwner(),
                    "Second real C08 node carries nonowner full-grants teammate actor");
                require(
                    allGrantsNode.getAnchorDim() == ctx.world().provider.dimensionId && allGrantsNode.getAnchorX() == X
                        && allGrantsNode.getAnchorY() == Y
                        && allGrantsNode.getAnchorZ() == Z
                        && allGrantsNode.getAnchorGeneration()
                            .equals(
                                terminal.getTagCompound()
                                    .getString(ItemNetworkQuantumTerminal.NBT_ANCHOR_GENERATION)),
                    "All-grants actual node has exact valid anchor/generation");
                require(
                    edge != null && QuantumConnectionMembership.contains(edge.a(), edge)
                        && QuantumConnectionMembership.contains(edge.b(), edge)
                        && edge.a()
                            .getGrid()
                            == edge.b()
                                .getGrid()
                        && edge.a()
                            .getGrid()
                            == ((IGridHost) controller).getGridNode(ForgeDirection.UNKNOWN)
                                .getGrid(),
                    "All-grants second actual node naturally joins both native endpoint lists on controller grid");
                allGrantsPositive = true;
                recordAccessFacts(ctx, "all-grants-success");
                ctx.event(
                    "all_grants_native_business",
                    "Full native grants actual TCP data request and second C08/native bridge passed; clickingActor="
                        + actor.getUniqueID()
                        + "; nodePlacer="
                        + allGrantsNode.getPlacerUUID());
                extractActorCard();
                card = NativeSecurityStationSupport.card(actor, SecurityPermissions.SECURITY);
                require(
                    ((ITerminalHost) station).getItemInventory()
                        .injectItems(
                            AEApi.instance()
                                .storage()
                                .createItemStack(card.copy()),
                            Actionable.MODULATE,
                            new PlayerSource(owner, (IActionHost) station))
                        == null,
                    "Owner restores SECURITY-only card after actual all-grants operation");
                stage = -2;
            }
        }
        if (stage == -2 && onlyNativePermission(SecurityPermissions.SECURITY)) {
            require(
                QuantumTerminalAccess.setNetworkSafety(owner, ownerTerminal, false),
                "Owner prepares SECURITY-only OFF chain after full-grants proof");
            airWitness = guiWitness = false;
            stage = 2;
            terminal.getTagCompound()
                .setString(PHASE, "ready");
            sync();
        }
        if ((stage == 2 || stage == -8 || stage == -4) && !airWitness
            && actor.isSneaking()
            && actor.rotationPitch < -80
            && TerminalAirInteraction.isAir(actor)) {
            airWitness = true;
            terminal.getTagCompound()
                .setString(PHASE, "air_ready");
            sync();
            ctx.event(
                "native_air_ready",
                "run=" + ctx.runId()
                    + " actor="
                    + actor.getUniqueID()
                    + " real server sneaking=true; production air guard=true");
        }
        Wire wire;
        while ((wire = observed.poll()) != null) {
            if (wire.discriminator == 5) {
                require(
                    stage < 6 || staleObserved,
                    "No polling request can clear the retained context before stale C2S29");
                requests++;
                if (stage == -8) {
                    QuantumNetworkData full = QuantumNetworkData.assemble(actor, terminal);
                    require(
                        buildPositive && full != null
                            && full.online
                            && !full.managementOnly
                            && full.totalChannels > 0
                            && full.quantumNodeCount > 0
                            && !QuantumTerminalAccess.canChangeNetworkSafety(actor, terminal),
                        "Real teammate request has full online business data and no safety-toggle authority");
                }
                if (stage == -4) {
                    QuantumNetworkData full = QuantumNetworkData.assemble(actor, terminal);
                    ctx.event(
                        "all_grants_actual_request_full",
                        new GsonBuilder().serializeNulls()
                            .create()
                            .toJson(allGrantsFullFacts(ctx, full, "actual-c2s5")));
                    require(
                        allNativePermissions() && full != null
                            && full.online
                            && !full.managementOnly
                            && full.totalChannels > 0
                            && full.quantumNodeCount > 0
                            && policy(),
                        "Actual all-native-grants request resolves full business data on live protected grid");
                    allGrantsReadRequest = true;
                }
                if (stage == -6) {
                    require(
                        !QuantumTerminalAccess.validate(actor, terminal)
                            && !QuantumTerminalAccess.canManage(actor, terminal)
                            && policy(),
                        "Actual post-BUILD-revocation request is denied while original network safety stays ON");
                    buildDeniedRequest = true;
                }
                if (!guiWitness) {
                    require(
                        airWitness && actor.openContainer instanceof ContainerTerminal
                            && ((ContainerTerminal) actor.openContainer).quantum,
                        "First real C2S5 follows server-witnessed sneak-air use and a real quantum container");
                    guiWitness = true;
                    terminal.getTagCompound()
                        .setString(PHASE, "gui_ready");
                    sync();
                    ctx.event(
                        "native_gui_request",
                        "run=" + ctx.runId()
                            + " actor="
                            + actor.getUniqueID()
                            + " native quantum container and actual decoded request5 observed");
                }
                continue;
            }
            intents++;
            require(
                wire.slot == 0 && identity.equals(wire.identity)
                    && Arrays.equals(wire.anchor, ItemNetworkQuantumTerminal.getAnchor(terminal))
                    && wire.token != 0,
                "Actual decoded C2S29 preserves slot/key-bound identity/anchor/token");
            if (intents <= 3) {
                require((intents == 2) != wire.enabled, "Native button ON/OFF/ON intent order");
                retainedToken = wire.token;
                ctx.event(
                    "native_c2s29",
                    "button intent=" + intents + " enabled=" + wire.enabled + " token=" + wire.token);
            } else {
                require(
                    intents == 4 && stage == 7
                        && !wire.enabled
                        && wire.token == retainedToken
                        && wire.arrived < contextDeadline
                        && System.nanoTime() < contextDeadline,
                    "Stale intent is decoded and its server drain tick completes while original context is within TTL");
                require(
                    witnessedStale == wire && witnessedTick + 1 == ctx.tick() && !isPendingInProduction(wire),
                    "The same real stale job has a pre-drain valid-context/native-ACL witness and was drained this tick");
                staleObserved = true;
                staleArrived = ctx.tick();
                ctx.event(
                    "stale_c2s29",
                    "Real decoded stale intent received before native context TTL; policy target=OFF");
            }
        }
        if (stage == 2 && intents == 1 && policy()) {
            stage = 3;
            ctx.event("policy", "Actual server ON after first GUI intent");
        }
        if (stage == 3 && intents == 2 && !policy()) {
            stage = 4;
            ctx.event("policy", "Actual server OFF after second GUI intent");
        }
        if (stage == 4 && intents == 3 && policy()) {
            stage = 5;
            ctx.event("policy", "Actual server ON after third GUI intent; ready to revoke");
        }
        if (stage == 6 && !security().hasPermission(actor, SecurityPermissions.SECURITY)) {
            require(
                policy() && !QuantumTerminalAccess.canChangeNetworkSafety(actor, terminal)
                    && !QuantumTerminalAccess.validate(actor, terminal)
                    && !QuantumTerminalAccess.canManage(actor, terminal),
                "Native revocation denies both BUILD and SECURITY while safety remains ON");
            requireLiveContext();
            terminalAtRevoke = businessTerminalNbt(terminal);
            controllersAtRevoke = new NBTTagCompound();
            QuantumControllerRegistry.get(ctx.world())
                .writeToNBT(controllersAtRevoke);
            controllersAtRevoke = (NBTTagCompound) controllersAtRevoke.copy();
            securityAtRevoke = new NBTTagCompound();
            QuantumNetworkSecurityRegistry.get(ctx.world())
                .writeToNBT(securityAtRevoke);
            securityAtRevoke = (NBTTagCompound) securityAtRevoke.copy();
            providersAtRevoke = new NBTTagCompound();
            QuantumNetworkSecurityRegistry.providerRegistry(ctx.world())
                .writeToNBT(providersAtRevoke);
            providersAtRevoke = (NBTTagCompound) providersAtRevoke.copy();
            stage = 7;
            writeFixtureReceipt("revoked");
            ctx.event(
                "revoked",
                "Actual native security cache denies actor; retained server context valid; awaiting real stale C2S29");
        }
        if (stage == 7 && staleObserved && ctx.tick() - staleArrived >= 20) {
            unchanged();
            stage = 8;
            writeFixtureReceipt("rejected");
            ctx.event(
                "stale_rejected",
                "Stale real C2S29 left policy ON and persistent controller/security resources unchanged");
        }
    }

    /** Only the server console initiates fixture mutation; business actor never runs a test command. */
    public static void control(ICommandSender sender, String action, String runId) throws Exception {
        DebugTestGate.requireEnabled();
        if (sender != MinecraftServer.getServer() || !sender.canCommandSenderUseCommand(2, "gtswn"))
            throw new IllegalStateException("Safety fixture controls require server console OP2");
        UUID.fromString(runId);
        QuantumSafetyClientScenario run = active;
        if (run == null || !run.context.runId()
            .equals(runId)) throw new IllegalStateException("No matching active safety run");
        if ("abort".equals(action)) {
            run.context.fail("Host explicitly aborted safety client fixture");
            return;
        }
        run.ordinary();
        if ("revoke-build".equals(action) && run.stage == -8) {
            run.require(
                run.buildPositive && run.guiWitness,
                "BUILD revocation follows actual client binding, placement, linking and native GUI read");
            run.extractActorCard();
            run.stage = -7;
        } else if ("prepare-security".equals(action) && run.stage == -6) {
            run.require(run.buildDeniedRequest, "Management setup follows the actual denied same-stack BUILD request");
            run.card = NativeSecurityStationSupport.card(run.actor, SecurityPermissions.SECURITY);
            for (SecurityPermissions permission : SecurityPermissions.values())
                ((IBiometricCard) run.card.getItem()).addPermission(run.card, permission);
            run.require(
                ((ITerminalHost) run.station).getItemInventory()
                    .injectItems(
                        AEApi.instance()
                            .storage()
                            .createItemStack(run.card.copy()),
                        Actionable.MODULATE,
                        new PlayerSource(run.owner, (IActionHost) run.station))
                    == null,
                "Owner inserts real all-native-permissions card after teammate rejection");
            run.stage = -5;
        } else if ("revoke".equals(action) && run.stage == 5) {
            run.require(run.policy(), "Revoke begins with real local safety ON");
            run.requireLiveContext();
            IAEItemStack extracted = ((ITerminalHost) run.station).getItemInventory()
                .extractItems(
                    AEApi.instance()
                        .storage()
                        .createItemStack(run.card),
                    Actionable.MODULATE,
                    new PlayerSource(run.owner, (IActionHost) run.station));
            run.require(
                extracted != null && extracted.getStackSize() == 1,
                "Owner removes actual actor SECURITY card through native inventory");
            run.stage = 6;
        } else if ("complete".equals(action) && run.stage == 8) {
            run.unchanged();
            run.require(
                run.requests > 0 && run.intents == 4
                    && run.staleObserved
                    && run.witnessedStale != null
                    && run.guiWitness
                    && run.buildPositive
                    && run.buildDeniedRequest
                    && run.allGrantsPositive,
                "Actual server decoder received native requests and all four safety intents with native ACL pre-drain witness");
            run.context.result.transport = "real actor TCP -> native FML decoded C2S5/29 -> untouched production handler -> server ticks; independent client observed Lite7 denial";
            run.context.pass(
                "Actual BUILD-only teammate C08 bind/node/native link and full data passed, revoked same-key read denied; SECURITY GUI/stale-intent chain passed; require client report PASS");
            run.context.event(
                "real_actor_complete",
                "run=" + run.context.runId()
                    + " actor="
                    + run.actor.getUniqueID()
                    + " actorLevel2=false actorLevel4=false");
        } else throw new IllegalStateException(
            "Invalid safety fixture control transition: " + action + " stage=" + run.stage);
    }

    private void extractActorCard() {
        IAEItemStack removed = ((ITerminalHost) station).getItemInventory()
            .extractItems(
                AEApi.instance()
                    .storage()
                    .createItemStack(card),
                Actionable.MODULATE,
                new PlayerSource(owner, (IActionHost) station));
        require(removed != null && removed.getStackSize() == 1, "Owner extracts exact real actor biometric card");
    }

    private boolean allNativePermissions() {
        for (SecurityPermissions permission : SecurityPermissions.values())
            if (!security().hasPermission(actor, permission)) return false;
        return true;
    }

    /** Only observes normal AE maintenance and ordinary cached assembly; never drives either lifecycle. */
    private Map<String, Object> allGrantsFullFacts(TestContext ctx, QuantumNetworkData full, String point)
        throws Exception {
        Map<String, Object> facts = new LinkedHashMap<>();
        boolean linked = buildNode != null && buildNode.isLinked();
        IGridConnection edge = buildNode == null ? null : (IGridConnection) field(buildNode, "connection");
        boolean memberA = edge != null && QuantumConnectionMembership.contains(edge.a(), edge);
        boolean memberB = edge != null && QuantumConnectionMembership.contains(edge.b(), edge);
        boolean sameGrid = memberA && memberB
            && edge.a()
                .getGrid()
                == edge.b()
                    .getGrid()
            && edge.a()
                .getGrid()
                == ((IGridHost) controller).getGridNode(ForgeDirection.UNKNOWN)
                    .getGrid();
        boolean fullReady = allNativePermissions() && full != null
            && full.online
            && !full.managementOnly
            && full.totalChannels > 0
            && full.quantumNodeCount > 0
            && policy();
        facts.put("point", point);
        facts.put("serverTick", ctx.tick());
        facts.put(
            "worldTick",
            ctx.world()
                .getTotalWorldTime());
        facts.put("nativeAll", allNativePermissions());
        facts.put("policy", policy());
        facts.put("fullPresent", full != null);
        facts.put("online", full == null ? null : full.online);
        facts.put("managementOnly", full == null ? null : full.managementOnly);
        facts.put("totalChannels", full == null ? null : full.totalChannels);
        facts.put("quantumNodeCount", full == null ? null : full.quantumNodeCount);
        facts.put("nodeLinked", linked);
        facts.put(
            "nodeReason",
            buildNode == null ? "absent"
                : buildNode.getOfflineReason()
                    .name());
        facts.put("nativeMemberA", memberA);
        facts.put("nativeMemberB", memberB);
        facts.put("sameControllerGrid", sameGrid);
        facts.put("bridgeReady", linked && memberA && memberB && sameGrid);
        facts.put("fullBusinessReady", fullReady);
        lastAllGrantsFullFacts = facts;
        return facts;
    }

    private boolean onlyNativePermission(SecurityPermissions allowed) {
        for (SecurityPermissions permission : SecurityPermissions.values())
            if (security().hasPermission(actor, permission) != (permission == allowed)) return false;
        return true;
    }

    private void requireLiveContext() throws Exception {
        Object value = ((Map<?, ?>) field(null, PacketSetQuantumNetworkSafety.class, "CONTEXTS"))
            .get(actor.getUniqueID());
        require(
            value != null && ((Number) field(value, "token")).longValue() == retainedToken
                && field(value, "player") == actor
                && field(value, "stack") == terminal
                && ((Number) field(value, "slot")).intValue() == 0
                && key.equals(field(value, "key"))
                && identity.equals(field(value, "identity")),
            "Original production token context is live and bound to exact actor/stack/slot/key/identity");
        contextDeadline = ((Number) field(value, "lastSeenNanos")).longValue() + 30_000_000_000L;
        require(System.nanoTime() < contextDeadline, "Original production token has not expired");
    }

    private void unchanged() {
        NBTTagCompound controllersNow = new NBTTagCompound(), securityNow = new NBTTagCompound(),
            providersNow = new NBTTagCompound();
        QuantumControllerRegistry.get(context.world())
            .writeToNBT(controllersNow);
        QuantumNetworkSecurityRegistry.get(context.world())
            .writeToNBT(securityNow);
        QuantumNetworkSecurityRegistry.providerRegistry(context.world())
            .writeToNBT(providersNow);
        require(
            policy() && !security().hasPermission(actor, SecurityPermissions.SECURITY),
            "Rejected stale intent preserves policy ON and actor revocation");
        require(
            terminalAtRevoke.equals(businessTerminalNbt(terminal)) && controllersAtRevoke.equals(controllersNow)
                && securityAtRevoke.equals(securityNow)
                && providersAtRevoke.equals(providersNow),
            "Terminal and persistent controller/security/provider resource snapshots unchanged after rejection");
        require(
            ((ISecurityProvider) station).getSecurityKey() == stationKey
                && ((IGridHost) controller).getGridNode(ForgeDirection.UNKNOWN)
                    .getGrid() == grid
                && ((IGridHost) station).getGridNode(ForgeDirection.UNKNOWN)
                    .getGrid() == grid,
            "Native station key and grid resource identity unchanged");
    }

    static NBTTagCompound businessTerminalNbt(ItemStack terminal) {
        NBTTagCompound snapshot = (NBTTagCompound) terminal.writeToNBT(new NBTTagCompound())
            .copy();
        snapshot.getCompoundTag("tag")
            .removeTag(PHASE); // Test-only handshake state is not a business resource.
        return snapshot;
    }

    private void ordinary() {
        guard(
            actor != null && !actor.canCommandSenderUseCommand(2, "gtswn")
                && !actor.canCommandSenderUseCommand(4, "gtswn"),
            "Actual business actor remains OP2=false OP4=false");
    }

    private boolean policy() {
        return QuantumTerminalAccess.isNetworkSafetyEnabled(context.world(), X, Y, Z);
    }

    private void require(boolean value, String detail) {
        TestWorldFixtures.require(context, value, detail);
    }

    private void guard(boolean value, String detail) {
        if (!value) TestWorldFixtures.require(context, false, detail);
    }

    /** Coordination only. Native ACL, context, transport and unchanged resources are checked independently. */
    private void writeFixtureReceipt(String phase) throws Exception {
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("schema", 1);
        receipt.put("runId", context.runId());
        receipt.put(
            "actorUuid",
            actor.getUniqueID()
                .toString());
        receipt.put("phase", phase);
        receipt.put("serverTick", context.tick());
        receipt.put("stage", stage);
        receipt.put("policy", policy());
        receipt.put("nativeSecurity", security().hasPermission(actor, SecurityPermissions.SECURITY));
        receipt.put("nativeBuild", security().hasPermission(actor, SecurityPermissions.BUILD));
        receipt.put("nativeAll", allNativePermissions());
        receipt.put("artifact", TestLabIdentity.ARTIFACT);
        Path directory = new File(
            MinecraftServer.getServer()
                .worldServerForDimension(0)
                .getSaveHandler()
                .getWorldDirectory(),
            "gtswn-test-reports").toPath();
        Files.createDirectories(directory);
        Path target = directory.resolve(context.runId() + ".fixture-" + phase + ".json");
        if (Files.exists(target))
            throw new IllegalStateException("Immutable fixture receipt already exists: " + target);
        Path temporary = directory.resolve(context.runId() + ".fixture-" + phase + ".tmp-" + UUID.randomUUID());
        Files.write(
            temporary,
            new GsonBuilder().setPrettyPrinting()
                .create()
                .toJson(receipt)
                .getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE);
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        context.event(
            "fixture_receipt",
            "run=" + context.runId() + " actor=" + actor.getUniqueID() + " phase=" + phase + " path=" + target);
    }

    private void recordTerminalState(TestContext ctx) {
        if (lastAllGrantsFullFacts != null) {
            Map<String, Object> facts = new LinkedHashMap<>(lastAllGrantsFullFacts);
            facts.put("terminalServerTick", ctx.tick());
            ctx.event(
                "all_grants_terminal_last_full",
                new GsonBuilder().serializeNulls()
                    .create()
                    .toJson(facts));
        }
        recordAccessFacts(ctx, "terminal");
        String nativeSecurity = "unavailable", actualPolicy = "unavailable";
        try {
            if (controller != null && station != null && !controller.isInvalid() && !station.isInvalid()) {
                actualPolicy = Boolean.toString(policy());
                nativeSecurity = Boolean.toString(security().hasPermission(actor, SecurityPermissions.SECURITY));
            }
        } catch (RuntimeException unavailable) {
            nativeSecurity = "unavailable:" + unavailable.getClass()
                .getSimpleName();
        }
        ctx.event(
            "terminal_fixture_state",
            "run=" + ctx.runId()
                + " actor="
                + (actor == null ? "none" : actor.getUniqueID())
                + " stage="
                + stage
                + " terminalPhase="
                + (terminal == null || !terminal.hasTagCompound() ? "none"
                    : terminal.getTagCompound()
                        .getString(PHASE))
                + " requests="
                + requests
                + " intents="
                + intents
                + " policy="
                + actualPolicy
                + " nativeSecurity="
                + nativeSecurity
                + " preDrainWitness="
                + (witnessedStale != null));
    }

    private void recordAccessFacts(TestContext ctx, String point) {
        if (actor == null || terminal == null) return;
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("point", point);
        facts.put(
            "clickingActor",
            actor.getUniqueID()
                .toString());
        facts.put("profileReferencePresent", MonitoringProfiles.hasProfileReference(terminal));
        try {
            facts.put("worldCanMineNode1", actor.worldObj.canMineBlock(actor, NODE_X, NODE_Y, NODE_Z));
            facts.put("actorCanEditNode1", actor.canPlayerEdit(NODE_X, NODE_Y, NODE_Z, 1, terminal));
            facts.put("worldCanMineNode2", actor.worldObj.canMineBlock(actor, NODE_X, NODE_Y, ALL_NODE_Z));
            facts.put("actorCanEditNode2", actor.canPlayerEdit(NODE_X, NODE_Y, ALL_NODE_Z, 1, terminal));
        } catch (RuntimeException unavailable) {
            facts.put(
                "worldEditFactsUnavailable",
                unavailable.getClass()
                    .getSimpleName());
        }
        try {
            facts.put(
                "nativeActorId",
                AEApi.instance()
                    .registries()
                    .players()
                    .getID(actor));
            facts.put("nativeStationOwnerId", station == null ? null : ((ISecurityProvider) station).getOwner());
            facts.put(
                "keyMatchesNativeStation",
                station != null && terminal.hasTagCompound()
                    && Long.toString(((ISecurityProvider) station).getSecurityKey())
                        .equals(
                            terminal.getTagCompound()
                                .getString(QuantumTerminalAccess.NBT_ENCRYPTION_KEY)));
            facts.put(
                "controllerAndStationSameGrid",
                controller != null && station != null
                    && ((IGridHost) controller).getGridNode(ForgeDirection.UNKNOWN)
                        .getGrid()
                        == ((IGridHost) station).getGridNode(ForgeDirection.UNKNOWN)
                            .getGrid());
            Map<String, Boolean> permissions = new LinkedHashMap<>();
            for (SecurityPermissions permission : SecurityPermissions.values())
                permissions.put(permission.name(), security().hasPermission(actor, permission));
            facts.put("nativePermissions", permissions);
        } catch (RuntimeException unavailable) {
            facts.put(
                "nativeFactsUnavailable",
                unavailable.getClass()
                    .getSimpleName());
        }
        for (TileEntityNetworkQuantumNode node : new TileEntityNetworkQuantumNode[] { buildNode, allGrantsNode })
            if (node != null) {
                String prefix = node == buildNode ? "buildNode" : "allGrantsNode";
                facts.put(prefix + "NativeActor", node.getNativeOwnerID());
                facts.put(prefix + "Placer", node.getPlacerUUID());
                facts.put(
                    prefix + "Reason",
                    node.getOfflineReason()
                        .name());
            }
        ctx.event(
            "testlab_access_facts",
            new GsonBuilder().create()
                .toJson(facts));
    }

    private ISecurityGrid security() {
        return ((IGridHost) controller).getGridNode(ForgeDirection.UNKNOWN)
            .getGrid()
            .getCache(ISecurityGrid.class);
    }

    private boolean ready() {
        IGridNode a = ((IGridHost) controller).getGridNode(ForgeDirection.UNKNOWN),
            b = ((IGridHost) station).getGridNode(ForgeDirection.UNKNOWN);
        if (a == null || b == null || a.getGrid() != b.getGrid()) return false;
        try {
            return ((IGridProxyable) controller).getProxy()
                .getPath()
                .getControllerState() == ControllerState.CONTROLLER_ONLINE && ((IGridProxyable) controller).getProxy()
                    .getEnergy()
                    .isNetworkPowered()
                && security().isAvailable();
        } catch (GridAccessException warmingUp) {
            return false;
        }
    }

    private void power() {
        if (controller == null || !((IGridProxyable) controller).getProxy()
            .isReady()) return;
        try {
            ((IGridProxyable) controller).getProxy()
                .getEnergy()
                .injectPower(200, Actionable.MODULATE);
        } catch (GridAccessException warmingUp) { /* Natural AE ticks establish the grid. */ }
    }

    private static Object field(Object target, String name) throws Exception {
        return field(target, target.getClass(), name);
    }

    private static Object field(Object target, Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static NBTTagCompound persisted(EntityPlayer player) {
        if (!player.getEntityData()
            .hasKey(EntityPlayer.PERSISTED_NBT_TAG, 10))
            player.getEntityData()
                .setTag(EntityPlayer.PERSISTED_NBT_TAG, new NBTTagCompound());
        return player.getEntityData()
            .getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
    }

    private static void save(EntityPlayerMP player) {
        player.worldObj.getSaveHandler()
            .getSaveHandler()
            .writePlayerData(player);
    }

    private void sync() {
        actor.inventory.markDirty();
        actor.inventoryContainer.detectAndSendChanges();
        actor.sendContainerToPlayer(actor.inventoryContainer);
    }

    public void cleanup(TestContext ctx) {
        recordTerminalState(ctx);
        try {
            if (listenerRegistered) {
                FMLCommonHandler.instance()
                    .bus()
                    .unregister(this);
                listenerRegistered = false;
            }
            if (channel != null && channel.pipeline()
                .get(OBSERVER) != null) channel.pipeline()
                    .remove(OBSERVER);
            if (buildFixtureReserved) {
                ctx.world()
                    .setBlockToAir(NODE_X, NODE_Y, ALL_NODE_Z);
                ctx.world()
                    .setBlockToAir(NODE_X, NODE_Y - 1, ALL_NODE_Z);
                ctx.world()
                    .setBlockToAir(NODE_X, NODE_Y, NODE_Z);
                ctx.world()
                    .setBlockToAir(NODE_X, NODE_Y - 1, NODE_Z);
            }
            if (stationPlaced) {
                TileEntity tile = ctx.world()
                    .getTileEntity(X + 1, Y, Z);
                if (tile instanceof AEBaseTile) ((AEBaseTile) tile).disableDrops();
                ctx.world()
                    .setBlockToAir(X + 1, Y, Z);
            }
            if (controllerPlaced) {
                QuantumControllerRegistry.get(ctx.world())
                    .dequantize(X, Y, Z);
                TileEntity tile = ctx.world()
                    .getTileEntity(X, Y, Z);
                if (tile instanceof AEBaseTile) ((AEBaseTile) tile).disableDrops();
                ctx.world()
                    .setBlockToAir(X, Y, Z);
            }
            if (introducedProvider) TestFixtureAccess.forgetProvider(
                QuantumNetworkSecurityRegistry.providerRegistry(ctx.world()),
                ctx.world(),
                introducedKey);
        } finally {
            try {
                if (owner != null) owner.inventory.clearInventory(null, -1);
                if (backupWritten) {
                    ItemStack current = actor.inventory.mainInventory[0];
                    require(!granted || current == terminal, "Only the exact reserved fixture stack is removed");
                    actor.inventory.mainInventory[0] = original.hasKey("Stack", 10)
                        ? ItemStack.loadItemStackFromNBT(original.getCompoundTag("Stack"))
                        : null;
                    actor.inventory.currentItem = original.getInteger("Selected");
                    if (!QuantumTerminalRequestQueue.isOnline(actor)) {
                        save(actor);
                        ctx.event(
                            "offline_recovery_required",
                            "run=" + ctx.runId()
                                + " actor="
                                + actor.getUniqueID()
                                + " exact slot/selected restored; durable original return backup retained; no offline dimension transfer attempted");
                        throw new IllegalStateException(
                            "Actor disconnected: original slot restored, persistent return-position backup retained for offline recovery");
                    }
                    actor.closeScreen();
                    actor.setSneaking(original.getBoolean("Sneaking"));
                    actor.playerNetServerHandler.sendPacket(new S09PacketHeldItemChange(actor.inventory.currentItem));
                    int dimension = original.getInteger("Dimension");
                    if (DimensionManager.getWorld(dimension) == null) DimensionManager.initDimension(dimension);
                    transfer(
                        actor,
                        DimensionManager.getWorld(dimension),
                        original.getDouble("X"),
                        original.getDouble("Y"),
                        original.getDouble("Z"),
                        original.getFloat("Yaw"),
                        original.getFloat("Pitch"));
                    sync();
                    persisted(actor).removeTag(BACKUP);
                    save(actor);
                    ctx.check(
                        actor.dimension == dimension && actor.posX == original.getDouble("X")
                            && actor.posY == original.getDouble("Y")
                            && actor.posZ == original.getDouble("Z"),
                        "Exact original actor position restored");
                    ItemStack restored = actor.inventory.mainInventory[0];
                    ctx.check(
                        original.hasKey("Stack", 10) ? restored != null && original.getCompoundTag("Stack")
                            .equals(restored.writeToNBT(new NBTTagCompound())) : restored == null,
                        "Exact original slot restored and durable backup retired");
                    ctx.event(
                        "real_actor_restored",
                        "run=" + ctx.runId()
                            + " actor="
                            + actor.getUniqueID()
                            + " exact slot/selected/position restored; actorLevel2="
                            + actor.canCommandSenderUseCommand(2, "gtswn")
                            + " actorLevel4="
                            + actor.canCommandSenderUseCommand(4, "gtswn"));
                }
                ctx.check(
                    ctx.world()
                        .isAirBlock(X, Y, Z)
                        && ctx.world()
                            .isAirBlock(X + 1, Y, Z),
                    "Exact run-owned native fixture blocks removed");
            } finally {
                active = null;
            }
        }
    }

    private static void transfer(EntityPlayerMP player, WorldServer world, double x, double y, double z, float yaw,
        float pitch) {
        if (world == null)
            throw new IllegalStateException("Original actor world unavailable; recovery backup retained");
        world.theChunkProviderServer.loadChunk(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4);
        if (player.dimension != world.provider.dimensionId) MinecraftServer.getServer()
            .getConfigurationManager()
            .transferPlayerToDimension(player, world.provider.dimensionId, new Teleporter(world) {

                @Override
                public void placeInPortal(Entity entity, double a, double b, double c, float rotation) {
                    entity.setLocationAndAngles(x, y, z, yaw, pitch);
                }

                @Override
                public boolean placeInExistingPortal(Entity entity, double a, double b, double c, float rotation) {
                    return true;
                }

                @Override
                public boolean makePortal(Entity entity) {
                    return false;
                }
            });
        player.playerNetServerHandler.setPlayerLocation(x, y, z, yaw, pitch);
    }

    private static final class Wire {

        final int discriminator, slot;
        final Object message;
        final long token, arrived = System.nanoTime();
        final boolean enabled;
        final String identity;
        final int[] anchor;

        Wire(Object message, int discriminator, long token, boolean enabled, int slot, String identity, int[] anchor) {
            this.message = message;
            this.discriminator = discriminator;
            this.token = token;
            this.enabled = enabled;
            this.slot = slot;
            this.identity = identity;
            this.anchor = anchor == null ? null : anchor.clone();
        }
    }
}
