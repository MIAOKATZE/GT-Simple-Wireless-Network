// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.testworld;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.gui.TerminalSupplyStore;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumIncorporationIdentity;
import com.miaokatze.gtswn.common.quantum.QuantumIncorporationRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkSecurityRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumTerminalAccess;
import com.miaokatze.gtswn.common.testworld.NativeSecurityStationSupport.RecordingActor;
import com.miaokatze.gtswn.common.util.PlayerRequestQueue;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.network.PacketQuantumIncorporation;

import appeng.api.AEApi;
import appeng.api.config.SecurityPermissions;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.networking.security.ISecurityProvider;
import appeng.api.storage.data.IAEItemStack;
import appeng.me.GridAccessException;
import appeng.me.helpers.IGridProxyable;
import appeng.tile.AEBaseTile;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Native Handler -> normal server Drain with exact synthetic session; explicitly not TCP evidence. */
public final class NativeIncorporationScopeScenario implements TestScenario {

    private static final int X = 8, Y = TestWorldFixtures.Y, Z = 20, DX = 14, BZ = 24;
    private RecordingActor owner, member, outsider, acting;
    private TileEntity controller, station, deviceA, deviceB;
    private ItemStack ownerTerminal;
    private ItemStack memberCard;
    private final List<int[]> positions = new ArrayList<>();
    private final List<Long> introduced = new ArrayList<>();
    private NativePacketCallbackSession session;
    private TestContext context;
    private NBTTagCompound beforeDrain;
    private double energyBefore;
    private boolean registered, negative, queued;
    private int stage, operation;
    private long queuedTick, witnessedTick;
    private String aIdentity, bIdentity;

    public String id() {
        return "quantum.native_incorporation_scope";
    }

    public boolean includeInAll() {
        return false;
    }

    public int timeoutTicks() {
        return 1800;
    }

    public String description() {
        return "Native all-grants/SECURITY incorporation managers, BUILD-only/no-card denials and A/B creator compatibility; synthetic Handler/Drain, no TCP";
    }

    public void start(TestContext ctx) {
        context = ctx;
        positions.clear();
        introduced.clear();
        stage = operation = 0;
        queued = registered = false;
        beforeDrain = null;
        session = null;
        controller = station = deviceA = deviceB = null;
        ownerTerminal = memberCard = null;
        owner = member = outsider = acting = null;
        aIdentity = bIdentity = null;
        TestWorldFixtures.requireTestWorld(ctx.world());
        if (!Config.quantumSafetyEnabled || !MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList.isEmpty())
            throw new IllegalStateException("Requires existing global safety ON and an otherwise empty server");
        for (int[] p : new int[][] { { X, Y, Z }, { X + 1, Y, Z }, { DX, Y, Z }, { DX, Y, BZ } }) {
            require(
                ctx,
                ctx.world()
                    .isAirBlock(p[0], p[1], p[2]),
                "Every operation-scope fixture position is empty before first block mutation");
            require(
                ctx,
                noEntry(ctx, p[0], p[1], p[2]),
                "No reserved scope position has an existing incorporation entry");
        }
        owner = NativeSecurityStationSupport.actor(ctx, "owner");
        member = NativeSecurityStationSupport.actor(ctx, "member");
        outsider = NativeSecurityStationSupport.actor(ctx, "outsider");
        NativeSecurityMatrix.assertOrdinaryActors(ctx, "incorporation operation scope", owner, member, outsider);
        for (RecordingActor actor : actors()) {
            require(ctx, !actor.capabilities.isCreativeMode, "Scope actor has no creative ownership bypass");
            TerminalSupplyStore.set(actor, TerminalSupplyStore.Kind.ANCHOR, actor == owner ? 3 : 2);
        }
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
        deviceA = place(
            ctx,
            DX,
            Y,
            Z,
            AEApi.instance()
                .definitions()
                .blocks()
                .energyCell()
                .maybeStack(1)
                .get());
        deviceB = place(
            ctx,
            DX,
            Y,
            BZ,
            AEApi.instance()
                .definitions()
                .blocks()
                .energyCell()
                .maybeStack(1)
                .get());
        FMLCommonHandler.instance()
            .bus()
            .register(this);
        registered = true;
        ctx.result.transport = "Synthetic native packet Handler/current EmbeddedChannel/ordinary END Drain; no socket, client login or TCP";
    }

    public void tick(TestContext ctx) throws Exception {
        try {
            if (stage == 0) {
                if (!NativeSecurityStationSupport.ready(controller, station)
                    || NativeSecurityStationSupport.node(deviceA) == null
                    || NativeSecurityStationSupport.node(deviceB) == null) return;
                memberCard = NativeSecurityStationSupport.allPermissionsCard(member);
                require(
                    ctx,
                    NativeSecurityStationSupport.insertCard(ctx, station, owner, memberCard) == null,
                    "A grants B all real native permissions");
                ownerTerminal = GTSWNItemList.ME_Network_Quantum_Terminal.get(1);
                owner.inventory.mainInventory[0] = ownerTerminal;
                owner.setSneaking(false);
                ((ItemNetworkQuantumTerminal) ownerTerminal.getItem())
                    .onItemUseFirst(ownerTerminal, owner, ctx.world(), X, Y, Z, 1, .5f, .5f, .5f);
                ownerTerminal = NativeSecurityStationSupport
                    .encodeAt(ctx, ownerTerminal, station, owner, introduced::add);
                owner.inventory.mainInventory[0] = ownerTerminal;
                member.inventory.mainInventory[0] = ownerTerminal.copy();
                outsider.inventory.mainInventory[0] = ownerTerminal.copy();
                require(
                    ctx,
                    QuantumTerminalAccess.setNetworkSafety(owner, ownerTerminal, true),
                    "A enables actual native network safety");
                stage = 1;
                return;
            }
            if (stage == 1) {
                for (SecurityPermissions permission : SecurityPermissions.values())
                    if (!security().hasPermission(member, permission)) return;
                require(
                    ctx,
                    ((ISecurityProvider) station).getOwner() != actorId(member)
                        && QuantumTerminalAccess.validate(member, member.getHeldItem())
                        && !QuantumTerminalAccess.validate(outsider, outsider.getHeldItem()),
                    "B is an authorized native nonowner and C is denied with the same valid key");
                enqueue(ctx, owner, Z, false, false, 1);
                stage = 2;
                return;
            }
            if (stage == 2 && finished(ctx)) {
                require(
                    ctx,
                    entry(ctx, Z) != null && entry(ctx, Z).owner()
                        .equals(
                            owner.getUniqueID()
                                .toString())
                        && TerminalSupplyStore.count(owner, TerminalSupplyStore.Kind.ANCHOR) == 2,
                    "A real packet adds its incorporation and consumes exactly one actual anchor supply");
                aIdentity = entry(ctx, Z).identity();
                closeSession();
                stage = 3;
            }
            if (stage == 3 && entry(ctx, Z).isConnected()) {
                replaceMemberCard(ctx, NativeSecurityStationSupport.card(member, SecurityPermissions.BUILD));
                stage = 30;
            }
            if (stage == 30 && onlyNativePermission(SecurityPermissions.BUILD)) {
                enqueue(ctx, member, Z, true, true, 2);
                stage = 31;
                return;
            }
            if (stage == 31 && finished(ctx)) {
                assertUnchanged(ctx, "B BUILD-only nonowner removal");
                require(
                    ctx,
                    entry(ctx, Z) != null && aIdentity.equals(entry(ctx, Z).identity())
                        && member.messages()
                            .toString()
                            .contains("gtswn.chat.quantum.incorporation_manage_required"),
                    "BUILD-only noncreator cannot bypass the native management/creator scope");
                closeSession();
                replaceMemberCard(ctx, NativeSecurityStationSupport.allPermissionsCard(member));
                stage = 32;
            }
            if (stage == 32 && allNativePermissions()) {
                enqueue(ctx, outsider, Z, true, true, 3);
                stage = 5;
                return;
            }
            if (stage == 5 && finished(ctx)) {
                assertUnchanged(ctx, "C no-card removal");
                require(
                    ctx,
                    outsider.messages()
                        .toString()
                        .contains("gtswn.chat.quantum.no_permission"),
                    "C no-card actual packet fails native access before entry ownership");
                closeSession();
                enqueue(ctx, member, Z, true, false, 8);
                stage = 4;
                return;
            }
            if (stage == 4 && finished(ctx)) {
                require(
                    ctx,
                    entry(ctx, Z) == null && deviceA == ctx.world()
                        .getTileEntity(DX, Y, Z)
                        && member.messages()
                            .toString()
                            .contains("gtswn.chat.quantum.incorporation_removed"),
                    "B all-native-grants current network manager actually removes A entry through Handler/Drain");
                require(
                    ctx,
                    TerminalSupplyStore.count(owner, TerminalSupplyStore.Kind.ANCHOR) == 2
                        && TerminalSupplyStore.count(member, TerminalSupplyStore.Kind.ANCHOR) == 2,
                    "Manager removal refunds or transfers none of A consumed material to B");
                ctx.event(
                    "NATIVE_MANAGER_REMOVE",
                    "actor=" + member.getUniqueID()
                        + "; nativeActor="
                        + actorId(member)
                        + "; nativeStationOwner="
                        + ((ISecurityProvider) station).getOwner()
                        + "; originalEntryOwner="
                        + owner.getUniqueID()
                        + "; originalIdentity="
                        + aIdentity
                        + "; no refund or ownership transfer");
                closeSession();
                enqueue(ctx, owner, Z, false, false, 7);
                stage = 6;
                return;
            }
            if (stage == 6 && finished(ctx)) {
                require(
                    ctx,
                    entry(ctx, Z) != null && entry(ctx, Z).owner()
                        .equals(
                            owner.getUniqueID()
                                .toString())
                        && TerminalSupplyStore.count(owner, TerminalSupplyStore.Kind.ANCHOR) == 1,
                    "A creates another own entry with the second explicitly budgeted anchor");
                closeSession();
                stage = 60;
            }
            if (stage == 60 && entry(ctx, Z).isConnected()) {
                enqueue(ctx, owner, Z, true, false, 4);
                stage = 61;
                return;
            }
            if (stage == 61 && finished(ctx)) {
                require(
                    ctx,
                    entry(ctx, Z) == null && deviceA == ctx.world()
                        .getTileEntity(DX, Y, Z)
                        && owner.messages()
                            .toString()
                            .contains("gtswn.chat.quantum.incorporation_removed"),
                    "A actual packet removes its own entry and preserves the real device");
                closeSession();
                enqueue(ctx, owner, Z, false, false, 7);
                stage = 62;
                return;
            }
            if (stage == 62 && finished(ctx)) {
                require(
                    ctx,
                    entry(ctx, Z) != null && entry(ctx, Z).owner()
                        .equals(
                            owner.getUniqueID()
                                .toString())
                        && TerminalSupplyStore.count(owner, TerminalSupplyStore.Kind.ANCHOR) == 0,
                    "A third owned entry consumes the last explicitly fixture-budgeted anchor");
                closeSession();
                replaceMemberCard(ctx, NativeSecurityStationSupport.card(member, SecurityPermissions.SECURITY));
                stage = 63;
            }
            if (stage == 63 && onlyNativePermission(SecurityPermissions.SECURITY) && entry(ctx, Z).isConnected()) {
                require(
                    ctx,
                    !QuantumTerminalAccess.validate(member, member.getHeldItem())
                        && QuantumIncorporationRegistry.get(ctx.world())
                            .canManageEntry(ctx.world(), DX, Y, Z, member),
                    "SECURITY-only noncreator has actual native removal management without ordinary BUILD access");
                enqueue(ctx, member, Z, true, false, 9);
                stage = 64;
                return;
            }
            if (stage == 64 && finished(ctx)) {
                require(
                    ctx,
                    entry(ctx, Z) == null && deviceA == ctx.world()
                        .getTileEntity(DX, Y, Z)
                        && member.messages()
                            .toString()
                            .contains("gtswn.chat.quantum.incorporation_removed"),
                    "SECURITY-only noncreator actual packet removes A entry without a BUILD bypass");
                require(
                    ctx,
                    TerminalSupplyStore.count(owner, TerminalSupplyStore.Kind.ANCHOR) == 0
                        && TerminalSupplyStore.count(member, TerminalSupplyStore.Kind.ANCHOR) == 2,
                    "SECURITY manager removal neither refunds A anchors nor credits B");
                closeSession();
                replaceMemberCard(ctx, NativeSecurityStationSupport.allPermissionsCard(member));
                stage = 65;
            }
            if (stage == 65 && allNativePermissions()) {
                enqueue(ctx, member, BZ, false, false, 5);
                stage = 7;
                return;
            }
            if (stage == 7 && finished(ctx)) {
                require(
                    ctx,
                    entry(ctx, BZ) != null && entry(ctx, BZ).owner()
                        .equals(
                            member.getUniqueID()
                                .toString())
                        && TerminalSupplyStore.count(member, TerminalSupplyStore.Kind.ANCHOR) == 1,
                    "B all-native-grants actual packet adds its own entry and consumes one anchor");
                bIdentity = entry(ctx, BZ).identity();
                closeSession();
                stage = 8;
            }
            if (stage == 8 && entry(ctx, BZ).isConnected()) {
                enqueue(ctx, member, BZ, true, false, 6);
                stage = 9;
                return;
            }
            if (stage == 9 && finished(ctx)) {
                require(
                    ctx,
                    entry(ctx, BZ) == null && deviceB == ctx.world()
                        .getTileEntity(DX, Y, BZ)
                        && member.messages()
                            .toString()
                            .contains("gtswn.chat.quantum.incorporation_removed"),
                    "B actual packet removes its own entry while preserving the device");
                closeSession();
                ctx.pass(
                    "Native all-grants and SECURITY-only managers remove A entries; BUILD-only/no-card denials and A/B creator positives preserve supplies/devices; synthetic callbacks only");
            }
        } finally {
            NativeSecurityStationSupport.power(controller);
        }
    }

    private void enqueue(TestContext ctx, RecordingActor actor, int targetZ, boolean remove, boolean denied, int op)
        throws Exception {
        acting = actor;
        negative = denied;
        operation = op;
        beforeDrain = null;
        witnessedTick = -1;
        actor.inventory.currentItem = 0;
        actor.setSneaking(false);
        actor.setPosition(DX + .5, Y, targetZ + 2.5);
        actor.rotationYaw = 180;
        actor.rotationPitch = (float) Math.toDegrees(Math.atan2(actor.getEyeHeight() - .5, 2));
        QuantumTerminalAccess.synchronize(actor, actor.getHeldItem());
        actor.clearMessages();
        session = new NativePacketCallbackSession(ctx, actor);
        require(
            ctx,
            PlayerRequestQueue.isOnline(actor),
            "Synthetic exact actor/session satisfies the untouched production current-connection guard");
        new PacketQuantumIncorporation.Handler()
            .onMessage(new PacketQuantumIncorporation(DX, Y, targetZ, remove), session.context());
        require(
            ctx,
            pending().containsKey(actor.getUniqueID()),
            "Actual native Handler enqueues the synthetic actor request; no direct registry operation");
        queuedTick = ctx.tick();
        queued = true;
        ctx.event(
            "native_incorporation_callback",
            "operation=" + op
                + "; actor="
                + actor.getUniqueID()
                + "; target="
                + DX
                + ":"
                + Y
                + ":"
                + targetZ
                + "; requireNativeDenial="
                + denied
                + "; real Handler -> natural Drain, no TCP");
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void beforeDrain(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !queued || beforeDrain != null) return;
        try {
            if (!pending().containsKey(acting.getUniqueID())) return;
            beforeDrain = resources(context);
            energyBefore = energy();
            witnessedTick = context.tick();
        } catch (Exception failure) {
            context.callbackFailure("scope pre-drain snapshot", failure);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public void captureIntroducedIdentity(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !queued || beforeDrain == null) return;
        int z = operation == 1 || operation == 7 ? Z : operation == 5 ? BZ : Integer.MIN_VALUE;
        if (z == Integer.MIN_VALUE) return;
        QuantumIncorporationRegistry.Entry entry = entry(context, z);
        TileEntity expected = z == Z ? deviceA : deviceB;
        if (entry != null && expected == context.world()
            .getTileEntity(DX, Y, z)
            && entry.owner()
                .equals(
                    acting.getUniqueID()
                        .toString())
            && entry.identity()
                .equals(((QuantumIncorporationIdentity) expected).gtswn$getIncorporationIdentity())) {
            if (z == Z) aIdentity = entry.identity();
            else bIdentity = entry.identity();
        }
    }

    private boolean finished(TestContext ctx) throws Exception {
        if (ctx.tick() <= queuedTick) return false;
        require(
            ctx,
            queued && beforeDrain != null
                && witnessedTick + 1 == ctx.tick()
                && !pending().containsKey(acting.getUniqueID()),
            "Same production pending callback was witnessed and drained in this natural tick");
        queued = false;
        return true;
    }

    private void assertUnchanged(TestContext ctx, String label) throws Exception {
        require(
            ctx,
            negative && beforeDrain.equals(resources(ctx)),
            label
                + " preserves exact registration/identity/owner/binding, real device, terminal, inventory and integer supplies");
        ctx.event(
            "native_energy_observation",
            label + "; before="
                + energyBefore
                + "; after="
                + energy()
                + "; window includes ordinary AE END energy accounting; this is not a packet-specific energy-delta assertion");
    }

    private NBTTagCompound resources(TestContext ctx) {
        NBTTagCompound n = new NBTTagCompound(), registry = new NBTTagCompound();
        QuantumIncorporationRegistry.get(ctx.world())
            .writeToNBT(registry);
        n.setTag("registry", registry);
        n.setTag(
            "terminal",
            acting.getHeldItem()
                .writeToNBT(new NBTTagCompound()));
        n.setTag("inventory", acting.inventory.writeToNBT(new NBTTagList()));
        n.setInteger("selected", acting.inventory.currentItem);
        n.setInteger("supply", TerminalSupplyStore.count(acting, TerminalSupplyStore.Kind.ANCHOR));
        TileEntity a = ctx.world()
            .getTileEntity(DX, Y, Z),
            b = ctx.world()
                .getTileEntity(DX, Y, BZ);
        n.setString("deviceAIdentity", ((QuantumIncorporationIdentity) a).gtswn$getIncorporationIdentity());
        n.setString("deviceBIdentity", ((QuantumIncorporationIdentity) b).gtswn$getIncorporationIdentity());
        n.setInteger(
            "deviceANativeOwner",
            NativeSecurityStationSupport.node(a) == null ? -1
                : NativeSecurityStationSupport.node(a)
                    .getPlayerID());
        n.setInteger(
            "deviceBNativeOwner",
            NativeSecurityStationSupport.node(b) == null ? -1
                : NativeSecurityStationSupport.node(b)
                    .getPlayerID());
        n.setBoolean("sameDeviceA", a == deviceA);
        n.setBoolean("sameDeviceB", b == deviceB);
        return (NBTTagCompound) n.copy();
    }

    private double energy() throws GridAccessException {
        return ((IGridProxyable) controller).getProxy()
            .getEnergy()
            .getStoredPower();
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> pending() throws Exception {
        Field f = PacketQuantumIncorporation.class.getDeclaredField("PENDING");
        f.setAccessible(true);
        return (Map<UUID, Object>) f.get(null);
    }

    private void closeSession() {
        if (session != null) session.close();
        session = null;
    }

    private RecordingActor[] actors() {
        return new RecordingActor[] { owner, member, outsider };
    }

    private static boolean noEntry(TestContext ctx, int x, int y, int z) {
        for (QuantumIncorporationRegistry.Entry entry : QuantumIncorporationRegistry.get(ctx.world())
            .snapshot()) if (entry.position == QuantumControllerRegistry.pack(x, y, z)) return false;
        return true;
    }

    private static int actorId(RecordingActor actor) {
        return AEApi.instance()
            .registries()
            .players()
            .getID(actor);
    }

    private ISecurityGrid security() {
        return NativeSecurityStationSupport.node(controller)
            .getGrid()
            .getCache(ISecurityGrid.class);
    }

    private boolean allNativePermissions() {
        for (SecurityPermissions permission : SecurityPermissions.values())
            if (!security().hasPermission(member, permission)) return false;
        return true;
    }

    private boolean onlyNativePermission(SecurityPermissions allowed) {
        for (SecurityPermissions permission : SecurityPermissions.values())
            if (security().hasPermission(member, permission) != (permission == allowed)) return false;
        return true;
    }

    private void replaceMemberCard(TestContext ctx, ItemStack replacement) {
        IAEItemStack removed = NativeSecurityStationSupport.extractCard(ctx, station, owner, memberCard);
        require(
            ctx,
            removed != null && removed.getStackSize() == 1,
            "Owner extracts the exact previous native member card");
        memberCard = replacement;
        require(
            ctx,
            NativeSecurityStationSupport.insertCard(ctx, station, owner, memberCard) == null,
            "Owner inserts the next exact native member-role card");
    }

    private QuantumIncorporationRegistry.Entry entry(TestContext ctx, int z) {
        return QuantumIncorporationRegistry.get(ctx.world())
            .find(ctx.world(), DX, Y, z);
    }

    private TileEntity place(TestContext ctx, int x, int y, int z, ItemStack stack) {
        positions.add(new int[] { x, y, z });
        return TestWorldFixtures.aeOwnedBlock(ctx.world(), x, y, z, stack, owner);
    }

    private static void require(TestContext ctx, boolean condition, String detail) {
        TestWorldFixtures.require(ctx, condition, detail);
    }

    public void cleanup(TestContext ctx) throws Exception {
        if (registered) FMLCommonHandler.instance()
            .bus()
            .unregister(this);
        queued = false;
        try {
            for (RecordingActor actor : actors()) if (actor != null)
                new PacketQuantumIncorporation.Drain().logout(new PlayerEvent.PlayerLoggedOutEvent(actor));
        } finally {
            closeSession();
        }
        for (int z : new int[] { Z, BZ }) {
            TileEntity expected = z == Z ? deviceA : deviceB;
            if (expected == null) continue;
            QuantumIncorporationRegistry.Entry entry = null;
            for (QuantumIncorporationRegistry.Entry candidate : QuantumIncorporationRegistry.get(ctx.world())
                .snapshot()) if (candidate.position == QuantumControllerRegistry.pack(DX, Y, z)) entry = candidate;
            if (entry != null) {
                String captured = z == Z ? aIdentity : bIdentity;
                RecordingActor entryOwner = z == Z ? owner : member;
                require(
                    ctx,
                    captured != null && captured.equals(entry.identity())
                        && entry.owner()
                            .equals(
                                entryOwner.getUniqueID()
                                    .toString())
                        && expected == ctx.world()
                            .getTileEntity(DX, Y, z),
                    "Cleanup removes only the exact captured run-owned entry identity and device");
                require(
                    ctx,
                    QuantumIncorporationRegistry.get(ctx.world())
                        .remove(ctx.world(), DX, Y, z, entryOwner),
                    "Exact captured entry owner performs cleanup removal");
            }
        }
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
        for (RecordingActor actor : actors()) if (actor != null) {
            actor.inventory.clearInventory(null, -1);
            TerminalSupplyStore.set(actor, TerminalSupplyStore.Kind.ANCHOR, 0);
        }
        require(
            ctx,
            MinecraftServer.getServer()
                .getConfigurationManager().playerEntityList.isEmpty(),
            "Owned synthetic session closed before list restoration; no online actor or session remains");
        ctx.event(
            "cleanup",
            "Exact A/B devices, native controller/station, owned queued jobs and synthetic session retired; foreign queue entries/list actors were never cleared");
        positions.clear();
        introduced.clear();
    }
}
