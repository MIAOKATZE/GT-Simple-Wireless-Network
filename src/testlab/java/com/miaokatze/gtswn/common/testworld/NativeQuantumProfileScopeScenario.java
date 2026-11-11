// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.testworld;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.StatCollector;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.device.DeviceTerminalDataStore;
import com.miaokatze.gtswn.common.gui.TerminalSupplyStore;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.monitoring.MonitorProfile;
import com.miaokatze.gtswn.common.monitoring.MonitoringProfiles;
import com.miaokatze.gtswn.common.monitoring.QuantumProfileAdapter;
import com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumIncorporationRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkSecurityRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumTerminalAccess;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.network.PacketQuantumIncorporation;

import appeng.api.AEApi;
import appeng.api.config.SecurityPermissions;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.networking.security.ISecurityProvider;
import appeng.tile.AEBaseTile;
import cpw.mods.fml.common.gameevent.PlayerEvent;

/** Native credentials and all five AE grants do not confer ownership of a shared monitoring profile. */
final class NativeQuantumProfileScopeScenario implements TestScenario {

    private static final int X = 8, Y = TestWorldFixtures.Y, Z = 8, TARGET_X = 12;
    private final Set<Long> introducedKeys = new HashSet<>();
    private NativeSecurityStationSupport.RecordingActor owner, member;
    private TileEntity controller, station, target;
    private ItemStack terminal, memberTerminal;
    private MonitoringProfiles profiles;
    private MonitorProfile profile;
    private String fixedSources;
    private UUID expectedOwner;
    private String expectedName;
    private long expectedRevision;
    private Set<UUID> expectedReaders;
    private NativePacketCallbackSession session;
    private NBTTagCompound deniedBaseline;
    private boolean controllerPlaced, stationPlaced, targetPlaced;
    private int stage;
    private long stageTick;

    @Override
    public String id() {
        return "quantum.native_profile_scope";
    }

    @Override
    public String description() {
        return "Native same-network all-AE-permission member reads a shared profile but cannot modify/incorporate; owner and production transfer controls";
    }

    @Override
    public boolean includeInAll() {
        return false;
    }

    @Override
    public int timeoutTicks() {
        return 1800;
    }

    @Override
    public void start(TestContext ctx) {
        TestWorldFixtures.requireTestWorld(ctx.world());
        owner = null;
        member = null;
        controller = null;
        station = null;
        target = null;
        terminal = null;
        memberTerminal = null;
        profiles = null;
        profile = null;
        fixedSources = null;
        expectedOwner = null;
        expectedName = null;
        expectedReaders = null;
        expectedRevision = 0;
        session = null;
        deniedBaseline = null;
        controllerPlaced = false;
        stationPlaced = false;
        targetPlaced = false;
        introducedKeys.clear();
        stage = 0;
        stageTick = ctx.tick();
        if (!Config.quantumSafetyEnabled) {
            ctx.skip("Global quantum safety disabled; profile scope case does not change Config");
            return;
        }
        require(
            ctx,
            MinecraftServer.getServer()
                .getConfigurationManager().playerEntityList.isEmpty(),
            "Synthetic callback fixture requires an otherwise empty test server");
        require(
            ctx,
            ctx.world()
                .isAirBlock(X, Y, Z)
                && ctx.world()
                    .getTileEntity(X, Y, Z) == null
                && ctx.world()
                    .isAirBlock(X + 1, Y, Z)
                && ctx.world()
                    .getTileEntity(X + 1, Y, Z) == null
                && ctx.world()
                    .isAirBlock(TARGET_X, Y, Z)
                && ctx.world()
                    .getTileEntity(TARGET_X, Y, Z) == null,
            "All three native fixture positions are empty before any placement flag or world mutation");
        owner = NativeSecurityStationSupport.actor(ctx, "owner");
        member = NativeSecurityStationSupport.actor(ctx, "member");
        NativeSecurityMatrix.assertOrdinaryActors(ctx, "profile scope", owner, member);
        require(
            ctx,
            !owner.capabilities.isCreativeMode && !member.capabilities.isCreativeMode,
            "Both business actors use survival permissions and physical consumables");
        owner.setPosition(TARGET_X + .5, Y, Z + 2.5);
        member.setPosition(TARGET_X + .5, Y, Z + 2.5);
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
        targetPlaced = true;
        target = TestWorldFixtures.aeOwnedBlock(
            ctx.world(),
            TARGET_X,
            Y,
            Z,
            AEApi.instance()
                .definitions()
                .blocks()
                .iface()
                .maybeStack(1)
                .get(),
            owner);
        terminal = GTSWNItemList.ME_Network_Quantum_Terminal.get(1);
        owner.inventory.setInventorySlotContents(0, terminal);
        owner.inventory.setInventorySlotContents(1, GTSWNItemList.Quantum_Incorporation_Anchor.get(2));
        member.inventory.setInventorySlotContents(1, GTSWNItemList.Quantum_Incorporation_Anchor.get(2));
        ctx.event(
            "transport_scope",
            "Synthetic server callbacks and natural END drain; no socket, client login or TCP claim");
    }

    @Override
    public void tick(TestContext ctx) throws Exception {
        NativeSecurityStationSupport.power(controller);
        if (ctx.tick() - stageTick > 300) {
            ctx.fail("Profile scope natural transition timeout at stage=" + stage);
            return;
        }
        switch (stage) {
            case 0:
                if (!NativeSecurityStationSupport.ready(controller, station)) return;
                require(
                    ctx,
                    NativeSecurityStationSupport.node(controller)
                        .getPlayerID() == ((ISecurityProvider) station).getOwner(),
                    "Real controller and station have the same native owner A");
                require(
                    ctx,
                    NativeSecurityStationSupport.node(controller)
                        .getPlayerID()
                        != AEApi.instance()
                            .registries()
                            .players()
                            .getID(member),
                    "Member B is a distinct native AE actor");
                require(
                    ctx,
                    NativeSecurityStationSupport
                        .insertCard(ctx, station, owner, NativeSecurityStationSupport.allPermissionsCard(member))
                        == null,
                    "A inserts B's real card granting every native AE permission");
                owner.setSneaking(false);
                ((ItemNetworkQuantumTerminal) terminal.getItem())
                    .onItemUseFirst(terminal, owner, ctx.world(), X, Y, Z, 1, .5f, .5f, .5f);
                require(
                    ctx,
                    ItemNetworkQuantumTerminal.isBound(terminal),
                    "A binds the terminal by the production controller click");
                terminal = encode(ctx, terminal);
                owner.inventory.setInventorySlotContents(0, terminal);
                require(
                    ctx,
                    QuantumTerminalAccess.setNetworkSafety(owner, terminal, true),
                    "A enables real network safety");
                next(ctx, 1);
                return;
            case 1:
                if (!NativeSecurityStationSupport.ready(controller, station) || !allPermissions()) return;
                for (SecurityPermissions permission : SecurityPermissions.values()) ctx.event(
                    "native-ae-grant",
                    "B " + permission + "=true; actual station card and current security grid");
                ItemStack ordinary = terminal.copy();
                member.inventory.setInventorySlotContents(0, ordinary);
                require(
                    ctx,
                    !MonitoringProfiles.hasProfileReference(ordinary)
                        && QuantumTerminalAccess.validate(member, ordinary)
                        && QuantumTerminalAccess.canManage(member, ordinary)
                        && QuantumTerminalAccess.canChangeNetworkSafety(member, ordinary),
                    "Positive native control: B's same-network encoded terminal and all five AE grants permit management");
                importAndShare(ctx);
                requireRead(ctx, member, memberTerminal);
                deniedBaseline = resources(ctx);
                denyOwnerActions(ctx);
                require(
                    ctx,
                    deniedBaseline.equals(resources(ctx)),
                    "Same-callback profile and safety denials leave typed profile, inventory, supplies and registry unchanged");
                deniedBaseline = resources(ctx, true);
                request(ctx, member);
                next(ctx, 2);
                return;
            case 2:
                if (!hasMessage(member, "gtswn.chat.quantum.profile_readonly")) return;
                require(
                    ctx,
                    allPermissions() && QuantumTerminalAccess.validate(member, memberTerminal)
                        && !QuantumProfileAdapter.profilePermits(member, memberTerminal, true),
                    "Denied incorporation retains live native all-permission and valid read-only terminal prerequisites");
                require(
                    ctx,
                    deniedBaseline.equals(resources(ctx, true)),
                    "Natural production incorporation rejection changes no typed profile, supply, inventory or incorporation registration");
                require(
                    ctx,
                    !QuantumIncorporationRegistry.isIncorporated(ctx.world(), TARGET_X, Y, Z),
                    "Read-only B did not incorporate the target");
                ctx.event(
                    "mechanism-reproduced",
                    "PROFILE_OWNER_ONLY: native AE all permissions=true, paired same-grid=true, profile read=true, modify=false, production incorporation=profile_readonly");
                closeSession();
                profiles.rename(owner.getUniqueID(), profile.id, "A-owned-profile");
                rememberProfileState();
                require(ctx, "A-owned-profile".equals(profile.name()), "A succeeds at the same profile rename");
                require(
                    ctx,
                    QuantumTerminalAccess.setNetworkSafety(owner, terminal, false)
                        && QuantumTerminalAccess.setNetworkSafety(owner, terminal, true),
                    "A succeeds at the same safety modification and restores safety ON");
                request(ctx, owner);
                next(ctx, 3);
                return;
            case 3:
                if (!hasMessage(owner, "gtswn.chat.quantum.incorporation_added")) return;
                requireAdded(ctx, owner);
                require(
                    ctx,
                    owner.inventory.getStackInSlot(1).stackSize == 1,
                    "A's positive incorporation consumes exactly one physical anchor");
                ctx.event(
                    "positive-control",
                    "Original profile owner A: rename, safety modification and production incorporation succeeded");
                request(ctx, owner);
                next(ctx, 4);
                return;
            case 4:
                if (!hasMessage(owner, "gtswn.chat.quantum.incorporation_removed")) return;
                require(
                    ctx,
                    !QuantumIncorporationRegistry.isIncorporated(ctx.world(), TARGET_X, Y, Z),
                    "A removes its own positive-control incorporation through the production packet callback");
                closeSession();
                profiles.transfer(owner.getUniqueID(), profile.id, member.getUniqueID());
                rememberProfileState();
                require(
                    ctx,
                    !QuantumTerminalAccess.validate(owner, terminal) && "gtswn.chat.quantum.profile_not_shared"
                        .equals(QuantumProfileAdapter.profileDenialKey(owner, terminal, false)),
                    "Public transfer clears A's former profile ownership/read ACL instead of retaining a misleading readonly grant");
                require(
                    ctx,
                    profile.owner()
                        .equals(member.getUniqueID())
                        && profile.readers()
                            .isEmpty()
                        && fixedSources.equals(profiles.sourceRevision(profile.id)),
                    "Public production transfer validates B's native source authority, preserves fixed sources and clears old ACL");
                profiles.rename(member.getUniqueID(), profile.id, "B-owned-profile");
                rememberProfileState();
                require(ctx, "B-owned-profile".equals(profile.name()), "B now succeeds at its formerly denied rename");
                require(
                    ctx,
                    QuantumTerminalAccess.setNetworkSafety(member, memberTerminal, false)
                        && QuantumTerminalAccess.setNetworkSafety(member, memberTerminal, true),
                    "B now succeeds at its formerly denied safety modification and restores safety ON");
                requireRead(ctx, member, memberTerminal);
                request(ctx, member);
                next(ctx, 5);
                return;
            case 5:
                if (!hasMessage(member, "gtswn.chat.quantum.incorporation_added")) return;
                requireAdded(ctx, member);
                require(
                    ctx,
                    member.inventory.getStackInSlot(1).stackSize == 1,
                    "After real ownership transfer B's production incorporation consumes exactly one physical anchor");
                next(ctx, 6);
                return;
            case 6:
                QuantumIncorporationRegistry.Entry entry = QuantumIncorporationRegistry.get(ctx.world())
                    .find(ctx.world(), TARGET_X, Y, Z);
                if (entry == null || !entry.isConnected()) return;
                require(
                    ctx,
                    NativeSecurityStationSupport.node(target)
                        .getGrid()
                        == NativeSecurityStationSupport.node(controller)
                            .getGrid(),
                    "After transfer B's actual incorporation connects to the protected native controller grid");
                NativeSecurityMatrix.assertOrdinaryActors(ctx, "profile transfer complete", owner, member);
                ctx.event(
                    "transfer-positive-control",
                    "Public MonitoringProfiles.transfer A->B; same fixed source, AE card and pairing; B rename/safety/incorporation now succeed");
                ctx.pass(
                    "mechanism-reproduced: shared Profile owner-only gate independently denies a fully AE-authorized member; owner and public-transfer controls pass (synthetic server callbacks, no TCP)");
                return;
            default:
                throw new IllegalStateException("Unexpected profile stage " + stage);
        }
    }

    private void importAndShare(TestContext ctx) {
        profiles = MonitoringProfiles.get(ctx.world());
        Set<UUID> previousIds = new HashSet<>(profileMap().keySet());
        Set<UUID> previousProtected = new HashSet<>(protectedIds());
        Set<UUID> previousLegacy = new HashSet<>(migrationMap().keySet());
        Set<UUID> previousData = new HashSet<>();
        for (Map.Entry<UUID, DeviceTerminalDataStore.TerminalData> old : DeviceTerminalDataStore.get(ctx.world())
            .getAllTerminals()) previousData.add(old.getKey());
        owner.inventory.setInventorySlotContents(0, terminal);
        profile = profiles.importHeld(owner);
        fixedSources = profiles.sourceRevision(profile.id);
        rememberProfileState();
        require(
            ctx,
            !previousIds.contains(profile.id) && !previousProtected.contains(profile.id)
                && !previousLegacy.contains(profile.legacyId)
                && !previousData.contains(profile.id),
            "Production import generates a fresh profile ID/lineage with no prior profile or TerminalData to overwrite");
        require(
            ctx,
            profile.owner()
                .equals(owner.getUniqueID())
                && profile.sources()
                    .size() == 1
                && QuantumProfileAdapter.matches(terminal, profile),
            "Production import captures A's actual fixed quantum source");
        profiles.share(owner.getUniqueID(), profile.id, member.getUniqueID());
        rememberProfileState();
        terminal = encode(ctx, terminal);
        require(
            ctx,
            profile.id.equals(MonitoringProfiles.readProfileReference(terminal))
                && fixedSources.equals(profiles.sourceRevision(profile.id)),
            "Real native security encoding preserves the production profile reference and fixed source boundary");
        owner.inventory.setInventorySlotContents(0, terminal);
        memberTerminal = terminal.copy();
        member.inventory.setInventorySlotContents(0, memberTerminal);
    }

    private void denyOwnerActions(TestContext ctx) {
        boolean ownerDenied = false;
        try {
            profiles.rename(member.getUniqueID(), profile.id, "B-owned-profile");
        } catch (IllegalArgumentException denied) {
            ownerDenied = "Only the profile owner may do this".equals(denied.getMessage());
        }
        require(
            ctx,
            ownerDenied && !profiles.canModify(member.getUniqueID(), profile.id),
            "Production profile rename rejects read-only B specifically with the owner-only error");
        require(
            ctx,
            !QuantumTerminalAccess.setNetworkSafety(member, memberTerminal, false)
                && QuantumTerminalAccess.isNetworkSafetyEnabled(ctx.world(), X, Y, Z),
            "Read-only profile independently blocks B's safety change despite real native SECURITY");
        member.clearMessages();
        ((ItemNetworkQuantumTerminal) memberTerminal.getItem())
            .onItemUseFirst(memberTerminal, member, ctx.world(), X, Y, Z, 1, .5f, .5f, .5f);
        for (String message : member.messages()) ctx.event("profile-controller-message", message);
        require(
            ctx,
            hasMessage(member, "gtswn.chat.quantum.profile_readonly")
                && !hasMessage(member, "gtswn.chat.quantum.no_permission"),
            "Production controller click identifies B's read-only profile rather than reporting missing network permission");
    }

    private void requireRead(TestContext ctx, NativeSecurityStationSupport.RecordingActor actor, ItemStack held) {
        QuantumNetworkData data = QuantumNetworkData.assemble(actor, held);
        require(
            ctx,
            allPermissions() && QuantumTerminalAccess.validate(actor, held)
                && QuantumProfileAdapter.profilePermits(actor, held, false)
                && data != null
                && data.online
                && !data.managementOnly
                && data.totalChannels > 0,
            "B reads actual full native quantum data using the shared fixed-source profile terminal");
        ctx.event(
            "profile-data",
            "online=" + data.online
                + "; managementOnly="
                + data.managementOnly
                + "; channels="
                + data.totalChannels
                + "; used="
                + data.usedChannels);
    }

    private void request(TestContext ctx, NativeSecurityStationSupport.RecordingActor actor)
        throws ReflectiveOperationException {
        if (session == null) session = new NativePacketCallbackSession(ctx, actor);
        actor.clearMessages();
        actor.setPosition(TARGET_X + .5, Y, Z + 2.5);
        double vertical = Y + .5 - (actor.posY + actor.getEyeHeight());
        actor.rotationYaw = 180;
        actor.rotationPitch = (float) -Math.toDegrees(Math.atan2(vertical, 2));
        new PacketQuantumIncorporation.Handler()
            .onMessage(new PacketQuantumIncorporation(TARGET_X, Y, Z, false), session.context());
        ctx.event(
            "packet-callback",
            "Production incorporation Handler queued " + actor.getCommandSenderName()
                + "; natural server END drain will recheck online identity, held item and profile/native authority");
    }

    private static boolean hasMessage(NativeSecurityStationSupport.RecordingActor actor, String key) {
        String localized = StatCollector.translateToLocalFormatted(key);
        for (String message : actor.messages()) if (message.contains(key) || message.contains(localized)) return true;
        return false;
    }

    private void requireAdded(TestContext ctx, NativeSecurityStationSupport.RecordingActor actor) {
        QuantumIncorporationRegistry.Entry entry = QuantumIncorporationRegistry.get(ctx.world())
            .find(ctx.world(), TARGET_X, Y, Z);
        require(
            ctx,
            entry != null && entry.owner()
                .equals(
                    actor.getUniqueID()
                        .toString()),
            "Production incorporation records the actual successful actor as entry owner");
    }

    private boolean allPermissions() {
        ISecurityGrid security = NativeSecurityStationSupport.node(controller)
            .getGrid()
            .getCache(ISecurityGrid.class);
        for (SecurityPermissions permission : SecurityPermissions.values())
            if (!security.hasPermission(member, permission)) return false;
        return true;
    }

    private ItemStack encode(TestContext ctx, ItemStack input) {
        return NativeSecurityStationSupport.encodeAt(ctx, input, station, owner, introducedKeys::add);
    }

    /** Complete profile state for same-callback comparisons; no AE energy or tile-clock claim. */
    private NBTTagCompound resources(TestContext ctx) {
        return resources(ctx, false);
    }

    /** Cross-tick comparison excludes only the profile's natural sample timestamp/status. */
    private NBTTagCompound resources(TestContext ctx, boolean acrossTicks) {
        NBTTagCompound state = new NBTTagCompound(), profileState = new NBTTagCompound(),
            incorporation = new NBTTagCompound();
        NBTTagCompound library = new NBTTagCompound();
        profiles.writeToNBT(library);
        NBTTagList entries = library.getTagList("profiles", 10);
        for (int i = 0; i < entries.tagCount(); i++) {
            NBTTagCompound candidate = entries.getCompoundTagAt(i);
            if (profile.id.toString()
                .equals(candidate.getString("id"))) profileState = (NBTTagCompound) candidate.copy();
        }
        if (acrossTicks) {
            profileState.removeTag("sampleTime");
            profileState.removeTag("status");
        }
        QuantumIncorporationRegistry.get(ctx.world())
            .writeToNBT(incorporation);
        state.setTag("profiles", profileState);
        state.setTag("incorporation", incorporation);
        state.setBoolean("safety", QuantumTerminalAccess.isNetworkSafetyEnabled(ctx.world(), X, Y, Z));
        for (NativeSecurityStationSupport.RecordingActor actor : new NativeSecurityStationSupport.RecordingActor[] {
            owner, member }) {
            NBTTagCompound inventory = new NBTTagCompound();
            inventory.setTag("slots", actor.inventory.writeToNBT(new NBTTagList()));
            inventory.setInteger("selected", actor.inventory.currentItem);
            inventory.setInteger("anchors", TerminalSupplyStore.count(actor, TerminalSupplyStore.Kind.ANCHOR));
            state.setTag(
                actor.getUniqueID()
                    .toString(),
                inventory);
        }
        return (NBTTagCompound) state.copy();
    }

    private void next(TestContext ctx, int value) {
        stage = value;
        stageTick = ctx.tick();
        ctx.event("profile-stage", Integer.toString(value));
    }

    private void closeSession() {
        if (session != null) session.close();
        session = null;
    }

    @Override
    public void cleanup(TestContext ctx) {
        TestWorldFixtures.requireTestWorld(ctx.world());
        try {
            // Exact production logout removal, never clear another actor's pending queue.
            if (owner != null)
                new PacketQuantumIncorporation.Drain().logout(new PlayerEvent.PlayerLoggedOutEvent(owner));
            if (member != null)
                new PacketQuantumIncorporation.Drain().logout(new PlayerEvent.PlayerLoggedOutEvent(member));
            closeSession();
        } finally {
            try {
                if (targetPlaced) remove(ctx, TARGET_X);
                if (stationPlaced) remove(ctx, X + 1);
                if (controllerPlaced) {
                    QuantumControllerRegistry.get(ctx.world())
                        .dequantize(X, Y, Z);
                    remove(ctx, X);
                }
            } finally {
                try {
                    removeIntroducedProfile(ctx);
                } finally {
                    if (owner != null) owner.inventory.clearInventory(null, -1);
                    if (member != null) member.inventory.clearInventory(null, -1);
                    for (long key : introducedKeys) TestFixtureAccess
                        .forgetProvider(QuantumNetworkSecurityRegistry.providerRegistry(ctx.world()), ctx.world(), key);
                    introducedKeys.clear();
                }
            }
        }
    }

    private static void remove(TestContext ctx, int x) {
        TileEntity tile = ctx.world()
            .getTileEntity(x, Y, Z);
        if (tile instanceof AEBaseTile) ((AEBaseTile) tile).disableDrops();
        ctx.world()
            .setBlockToAir(x, Y, Z);
    }

    /** Reflection is confined to exact deletion of this real import; never creates a source or changes authority. */
    private void removeIntroducedProfile(TestContext ctx) {
        if (profile == null) return;
        require(
            ctx,
            profiles.profileForTerminal(profile.id) == profile && profile.owner()
                .equals(expectedOwner)
                && profile.name()
                    .equals(expectedName)
                && profile.readers()
                    .equals(expectedReaders)
                && profiles.authorizationRevision(profile.id) == expectedRevision
                && fixedSources.equals(profiles.sourceRevision(profile.id))
                && profile.id.equals(migrationMap().get(profile.legacyId))
                && DeviceTerminalDataStore.get(ctx.world())
                    .getTerminal(profile.id) == null,
            "Exact imported profile object, lineage and unchanged source verified before scoped cleanup");
        profileMap().remove(profile.id);
        migrationMap().remove(profile.legacyId);
        protectedIds().remove(profile.id);
        profiles.markDirty();
        require(
            ctx,
            profiles.profileForTerminal(profile.id) == null && !migrationMap().containsKey(profile.legacyId)
                && !protectedIds().contains(profile.id),
            "Only this run's random imported profile and lineage were removed");
        ctx.event(
            "profile-cleanup",
            "Exact generated profile/lineage removed; no whole-library restore or authority mutation");
    }

    private void rememberProfileState() {
        expectedOwner = profile.owner();
        expectedName = profile.name();
        expectedReaders = new HashSet<>(profile.readers());
        expectedRevision = profiles.authorizationRevision(profile.id);
    }

    private Object field(String name) {
        try {
            Field field = MonitoringProfiles.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(profiles);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported profile fixture cleanup: " + name, failure);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, MonitorProfile> profileMap() {
        return (Map<UUID, MonitorProfile>) field("profiles");
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, UUID> migrationMap() {
        return (Map<UUID, UUID>) field("migrations");
    }

    @SuppressWarnings("unchecked")
    private Set<UUID> protectedIds() {
        return (Set<UUID>) field("protectedIds");
    }

    private static void require(TestContext ctx, boolean value, String message) {
        TestWorldFixtures.require(ctx, value, message);
    }
}
