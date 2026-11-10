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
import com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkSecurityRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumTerminalAccess;
import com.miaokatze.gtswn.common.quantum.QuantumTerminalRequestQueue;
import com.miaokatze.gtswn.common.util.DebugTestGate;
import com.miaokatze.gtswn.common.util.PlayerRequestQueue;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.network.PacketRequestQuantumTerminalData;
import com.miaokatze.gtswn.network.PacketSetQuantumNetworkSafety;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.config.SecurityPermissions;
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
    private static QuantumSafetyClientScenario active;
    private final ArrayBlockingQueue<Wire> observed = new ArrayBlockingQueue<>(256);
    private TestContext context;
    private EntityPlayerMP actor;
    private FakePlayer owner;
    private Object connection, grid;
    private TileEntity controller, station;
    private ItemStack terminal, card;
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
        return "Real non-OP TCP actor: native safety GUI OFF/ON/OFF/ON, native card revocation and stale C2S intent rejection";
    }

    public void start(TestContext ctx) {
        if (active != null) throw new IllegalStateException("Safety client fixture already active");
        actor = null;
        owner = null;
        controller = station = null;
        terminal = card = null;
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
            + "; fixed TCP connection; SECURITY-only native card";
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
            card = NativeSecurityStationSupport.card(actor, SecurityPermissions.SECURITY);
            IAEItemStack remainder = ((ITerminalHost) station).getItemInventory()
                .injectItems(
                    AEApi.instance()
                        .storage()
                        .createItemStack(card.copy()),
                    Actionable.MODULATE,
                    new PlayerSource(owner, (IActionHost) station));
            require(remainder == null, "Real actor SECURITY-only card inserted natively");
            terminal = GTSWNItemList.ME_Network_Quantum_Terminal.get(1);
            owner.inventory.mainInventory[0] = terminal;
            owner.setSneaking(false);
            ((ItemNetworkQuantumTerminal) terminal.getItem())
                .onItemUseFirst(terminal, owner, ctx.world(), X, Y, Z, 1, .5f, .5f, .5f);
            require(ItemNetworkQuantumTerminal.isBound(terminal), "Production controller click bound fixture terminal");
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
            stage = 1;
            return;
        }
        if (stage == 1) {
            if (!ready() || !security().hasPermission(actor, SecurityPermissions.SECURITY)) return;
            require(
                !security().hasPermission(actor, SecurityPermissions.BUILD),
                "Actual actor has SECURITY but no BUILD");
            require(!policy(), "Initial local safety is OFF");
            terminal.getTagCompound()
                .setString(RUN, ctx.runId());
            terminal.getTagCompound()
                .setString(PHASE, "ready");
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
            stage = 2;
            ctx.event(
                "fixture_ready",
                "Run=" + ctx.runId()
                    + " real non-OP SECURITY-only actor="
                    + actor.getUniqueID()
                    + " slot=0 policy=OFF");
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
        if (stage == 2 && !airWitness
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
            terminalAtRevoke = businessTerminalNbt();
            controllersAtRevoke = new NBTTagCompound();
            QuantumControllerRegistry.get(ctx.world())
                .writeToNBT(controllersAtRevoke);
            securityAtRevoke = new NBTTagCompound();
            QuantumNetworkSecurityRegistry.get(ctx.world())
                .writeToNBT(securityAtRevoke);
            providersAtRevoke = new NBTTagCompound();
            QuantumNetworkSecurityRegistry.providerRegistry(ctx.world())
                .writeToNBT(providersAtRevoke);
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
        if ("revoke".equals(action) && run.stage == 5) {
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
                    && run.guiWitness,
                "Actual server decoder received native requests and all four safety intents with native ACL pre-drain witness");
            run.context.result.transport = "real actor TCP -> native FML decoded C2S5/29 -> untouched production handler -> server ticks; independent client observed Lite7 denial";
            run.context.pass(
                "Actual non-OP actor GUI ON/OFF/ON and stale same-context intent rejected after native card revocation; require client report PASS");
            run.context.event(
                "real_actor_complete",
                "run=" + run.context.runId()
                    + " actor="
                    + run.actor.getUniqueID()
                    + " actorLevel2=false actorLevel4=false");
        } else throw new IllegalStateException(
            "Invalid safety fixture control transition: " + action + " stage=" + run.stage);
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
            terminalAtRevoke.equals(businessTerminalNbt()) && controllersAtRevoke.equals(controllersNow)
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

    private NBTTagCompound businessTerminalNbt() {
        NBTTagCompound snapshot = terminal.writeToNBT(new NBTTagCompound());
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
