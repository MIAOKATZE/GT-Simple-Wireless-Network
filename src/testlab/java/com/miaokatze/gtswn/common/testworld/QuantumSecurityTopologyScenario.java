package com.miaokatze.gtswn.common.testworld;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.StatCollector;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.monitoring.MonitoringProfiles;
import com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkSecurityRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumTerminalAccess;
import com.miaokatze.gtswn.config.Config;

import appeng.api.AEApi;
import appeng.api.config.SecurityPermissions;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.networking.security.ISecurityProvider;
import appeng.api.util.AEColor;
import appeng.me.helpers.IGridProxyable;
import appeng.parts.networking.PartCable;
import appeng.tile.AEBaseTile;
import appeng.tile.networking.TileCableBus;

/** Reproduces the deliberate ordinary-cable isolation, then proves recovery through a supported native topology. */
final class QuantumSecurityTopologyScenario implements TestScenario {

    private static final int X = 8, Y = TestWorldFixtures.Y, Z = 8, REMOTE_STATION_X = X + 3;
    private final Set<Long> introducedKeys = new HashSet<>();
    private NativeSecurityStationSupport.RecordingActor owner, member;
    private TileEntity controller, station;
    private ItemStack ownerTerminal, handoff;
    private int stage;
    private long stageTick, originalKey;
    private boolean started;

    @Override
    public String id() {
        return "quantum.native_security_topology";
    }

    @Override
    public String description() {
        return "Native all-grants teammate: ordinary glass-cable isolation after quantization and direct-station re-pairing recovery";
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
        if (!Config.quantumSafetyEnabled) {
            ctx.skip("Global safety master is disabled; topology case leaves configuration unchanged");
            return;
        }
        introducedKeys.clear();
        stage = 0;
        stageTick = ctx.tick();
        owner = NativeSecurityStationSupport.actor(ctx, "owner");
        member = NativeSecurityStationSupport.actor(ctx, "member");
        NativeSecurityMatrix.assertOrdinaryActors(ctx, "topology initial", owner, member);
        owner.setPosition(X + .5, Y, Z + 2.5);
        member.setPosition(X + .5, Y, Z + 2.5);
        for (int x = X; x <= REMOTE_STATION_X; x++) {
            require(
                ctx,
                ctx.world()
                    .isAirBlock(x, Y, Z),
                "Topology fixture positions start empty");
            if (x > X && x < REMOTE_STATION_X) require(
                ctx,
                ctx.world()
                    .isAirBlock(x, Y - 1, Z),
                "Cable support positions start empty");
        }
        started = true;
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
        placeCable(ctx, X + 1);
        placeCable(ctx, X + 2);
        station = placeStation(ctx, REMOTE_STATION_X);
        ctx.event("transport", "Synthetic non-OP server actors and real native callbacks; no TCP or client claim");
    }

    @Override
    public void tick(TestContext ctx) {
        NativeSecurityStationSupport.power(controller);
        NativeSecurityStationSupport.power(station);
        if (!Config.quantumSafetyEnabled) {
            ctx.fail("Global safety master changed during topology test");
            return;
        }
        if (ctx.tick() - stageTick > 300) {
            observe(ctx, "TIMEOUT stage=" + stage);
            ctx.fail("Native topology transition timed out at stage=" + stage);
            return;
        }
        switch (stage) {
            case 0:
                if (!NativeSecurityStationSupport.ready(controller, station)) return;
                require(
                    ctx,
                    !QuantumControllerRegistry.get(ctx.world())
                        .isQuantized(X, Y, Z),
                    "Glass-cable fixture begins as an ordinary unquantized native network");
                require(
                    ctx,
                    NativeSecurityStationSupport
                        .insertCard(ctx, station, owner, NativeSecurityStationSupport.allPermissionsCard(member))
                        == null,
                    "Owner inserts actual teammate card carrying every native AE permission");
                next(ctx, 1);
                return;
            case 1:
                if (!NativeSecurityStationSupport.ready(controller, station) || !allNativeGrants()) return;
                assertGrants(ctx);
                require(
                    ctx,
                    proxySides().contains(ForgeDirection.EAST),
                    "Ordinary glass-cable side is connected before quantization");
                ownerTerminal = NativeSecurityStationSupport.encodeAt(
                    ctx,
                    GTSWNItemList.ME_Network_Quantum_Terminal.get(1),
                    station,
                    owner,
                    introducedKeys::add);
                originalKey = ((ISecurityProvider) station).getSecurityKey();
                require(
                    ctx,
                    encodedProvider(originalKey) == station,
                    "Pre-quantization native key resolves to the actual same-grid station");
                observe(
                    ctx,
                    "BEFORE_QUANTIZATION: same native grid; all native permissions; AE encoder paired terminal");
                click(ctx, owner, ownerTerminal);
                require(
                    ctx,
                    ItemNetworkQuantumTerminal.isBound(ownerTerminal) && QuantumControllerRegistry.get(ctx.world())
                        .isQuantized(X, Y, Z),
                    "Production owner gesture quantizes controller and writes its real terminal binding");
                handoff = ownerTerminal.copy();
                next(ctx, 2);
                return;
            case 2:
                if (NativeSecurityStationSupport.node(controller) == null
                    || NativeSecurityStationSupport.node(station) == null
                    || grid(controller) == grid(station)
                    || proxySides().contains(ForgeDirection.EAST)) return;
                require(
                    ctx,
                    QuantumTerminalAccess.setNetworkSafety(owner, ownerTerminal, true),
                    "Actual controller owner enables safety ON after native cable isolation");
                require(
                    ctx,
                    QuantumTerminalAccess.isNetworkSafetyEnabled(ctx.world(), X, Y, Z),
                    "Local safety is ON during refusal");
                assertGrants(ctx);
                require(
                    ctx,
                    encodedProvider(originalKey) == station && key(handoff) == originalKey,
                    "Handed-off terminal key remains valid and resolves to the original live station");
                require(
                    ctx,
                    !MonitoringProfiles.hasProfileReference(handoff),
                    "Refusal fixture has no independent Profile ACL");
                QuantumTerminalAccess.synchronize(member, handoff);
                require(
                    ctx,
                    bindingExists(ctx, handoff),
                    "Already-bound handed-off terminal retains a current real controller generation");
                rejectBoundAndFresh(ctx, "REPRODUCED_DENIAL_AFTER_QUANTIZATION");
                observe(
                    ctx,
                    "REPRODUCED_DENIAL_AFTER_QUANTIZATION: valid key and all cards; providerGrid != controllerGrid; ordinary EAST cable excluded");
                remove(ctx, REMOTE_STATION_X, Y);
                remove(ctx, X + 2, Y);
                remove(ctx, X + 1, Y);
                station = placeStation(ctx, X + 1);
                next(ctx, 3);
                return;
            case 3:
                if (!NativeSecurityStationSupport.ready(controller, station)) return;
                require(
                    ctx,
                    proxySides().contains(ForgeDirection.EAST),
                    "Production filter now admits the actual adjacent security station");
                require(
                    ctx,
                    NativeSecurityStationSupport
                        .insertCard(ctx, station, owner, NativeSecurityStationSupport.allPermissionsCard(member))
                        == null,
                    "Owner inserts all-permissions card into the replacement native station");
                next(ctx, 4);
                return;
            case 4:
                if (!NativeSecurityStationSupport.ready(controller, station) || !allNativeGrants()) return;
                assertGrants(ctx);
                require(
                    ctx,
                    ((ISecurityProvider) station).getSecurityKey() != originalKey,
                    "Replacement station has its own native key; same actor grants do not repoint the old pairing");
                rejectBoundAndFresh(ctx, "OLD_KEY_AFTER_SUPPORTED_TOPOLOGY_REPAIR");
                ItemStack repaired = NativeSecurityStationSupport
                    .encodeAt(ctx, handoff, station, owner, introducedKeys::add);
                require(
                    ctx,
                    key(repaired) == ((ISecurityProvider) station).getSecurityKey()
                        && encodedProvider(key(repaired)) == station,
                    "Native re-encoding pairs terminal to the unique current station");
                repaired.getTagCompound()
                    .removeTag(ItemNetworkQuantumTerminal.NBT_BOUND);
                member.clearMessages();
                click(ctx, member, repaired);
                require(
                    ctx,
                    bindingExists(ctx, repaired),
                    "Non-owner all-grants teammate actually binds through production callback after re-pairing");
                require(
                    ctx,
                    QuantumTerminalAccess.validate(member, repaired),
                    "Repaired terminal permits ordinary teammate access");
                QuantumNetworkData full = QuantumNetworkData.assemble(member, repaired);
                require(
                    ctx,
                    full != null && full.online
                        && !full.managementOnly
                        && full.networkSafetyEnabled
                        && (full.channelsInfinite || full.totalChannels > 0),
                    "Supported same-grid topology and native re-encoding restore full positive data");
                NativeSecurityMatrix.assertOrdinaryActors(ctx, "topology recovered", owner, member);
                observe(
                    ctx,
                    "RECOVERED_DIRECT_STATION_AND_NATIVE_REENCODING: same native grid and full ordinary access");
                ctx.event(
                    "recovery_chat",
                    member.messages()
                        .toString());
                ctx.pass(
                    "Mechanism reproduced: ordinary-cable filtering caused same-key/all-grants denial; legal direct-station topology and native re-encoding restored positive access. No player incident resolution claim.");
                return;
            default:
                throw new IllegalStateException("Unknown topology stage " + stage);
        }
    }

    private void rejectBoundAndFresh(TestContext ctx, String reason) {
        require(
            ctx,
            bindingExists(ctx, handoff),
            "Negative control starts with a legitimate bound controller generation");
        member.inventory.setInventorySlotContents(0, handoff);
        QuantumTerminalAccess.synchronize(member, handoff);
        NBTTagCompound before = (NBTTagCompound) handoff.writeToNBT(new NBTTagCompound())
            .copy();
        member.clearMessages();
        click(ctx, member, handoff);
        require(
            ctx,
            before.equals(handoff.writeToNBT(new NBTTagCompound())),
            "Denied bound interaction leaves all terminal NBT intact");
        require(
            ctx,
            !QuantumTerminalAccess.validate(member, handoff),
            "Bound terminal format does not replace native same-grid permission");
        QuantumNetworkData denied = QuantumNetworkData.assemble(member, handoff);
        require(ctx, denied == null || denied.managementOnly, "Refused business access returns no full network data");
        if (denied != null) NativeSecurityMatrix.assertManagementOnly(ctx, member, handoff);
        require(ctx, noPermissionChat(), "Real bound-terminal callback emits native no-permission chat");
        ctx.event("topology_denial_chat", reason + "; bound=" + member.messages());
        ItemStack fresh = handoff.copy();
        fresh.getTagCompound()
            .removeTag(ItemNetworkQuantumTerminal.NBT_BOUND);
        member.clearMessages();
        click(ctx, member, fresh);
        require(
            ctx,
            !ItemNetworkQuantumTerminal.isBound(fresh) && noPermissionChat(),
            "Same key on an unbound control is refused without writing a controller binding");
        ctx.event("topology_denial_chat", reason + "; fresh=" + member.messages());
    }

    private boolean noPermissionChat() {
        String expected = StatCollector.translateToLocal("gtswn.chat.quantum.no_permission");
        for (String message : member.messages()) if (message.contains(expected)) return true;
        return false;
    }

    private void click(TestContext ctx, NativeSecurityStationSupport.RecordingActor actor, ItemStack held) {
        actor.inventory.currentItem = 0;
        actor.inventory.setInventorySlotContents(0, held);
        actor.setSneaking(false);
        actor.theItemInWorldManager.activateBlockOrUseItem(actor, ctx.world(), held, X, Y, Z, 1, .5f, .5f, .5f);
    }

    private void placeCable(TestContext ctx, int x) {
        TestWorldFixtures.place(ctx.world(), x, Y - 1, Z, Blocks.stone, 0);
        ItemStack cable = AEApi.instance()
            .definitions()
            .parts()
            .cableGlass()
            .stack(AEColor.Transparent, 1);
        owner.inventory.setInventorySlotContents(0, cable);
        owner.theItemInWorldManager.activateBlockOrUseItem(owner, ctx.world(), cable, x, Y - 1, Z, 1, .5f, .5f, .5f);
        TileEntity placed = ctx.world()
            .getTileEntity(x, Y, Z);
        require(
            ctx,
            cable.stackSize == 0 && placed instanceof TileCableBus
                && ((TileCableBus) placed).getPart(ForgeDirection.UNKNOWN) instanceof PartCable,
            "Real ordinary AE glass cable placement consumes its item and creates native cable part");
        TestWorldProbes.addPosition(x, Y, Z);
    }

    private TileEntity placeStation(TestContext ctx, int x) {
        return TestWorldFixtures.aeOwnedBlock(
            ctx.world(),
            x,
            Y,
            Z,
            AEApi.instance()
                .definitions()
                .blocks()
                .security()
                .maybeStack(1)
                .get(),
            owner);
    }

    private boolean allNativeGrants() {
        if (NativeSecurityStationSupport.node(station) == null) return false;
        ISecurityGrid security = grid(station).getCache(ISecurityGrid.class);
        for (SecurityPermissions permission : SecurityPermissions.values())
            if (!security.hasPermission(member, permission)) return false;
        return true;
    }

    private void assertGrants(TestContext ctx) {
        NativeSecurityMatrix.assertOrdinaryActors(ctx, "topology all native grants", owner, member);
        HashMap<Integer, EnumSet<SecurityPermissions>> cards = new HashMap<>();
        ((ISecurityProvider) station).readPermissions(cards);
        int actor = AEApi.instance()
            .registries()
            .players()
            .getID(member);
        int nativeOwner = AEApi.instance()
            .registries()
            .players()
            .getID(owner);
        require(
            ctx,
            nativeOwner >= 0 && actor >= 0 && nativeOwner != actor,
            "Topology teammate B has a distinct ordinary native AE identity from owner A");
        require(
            ctx,
            ((ISecurityProvider) station).getOwner() == nativeOwner && NativeSecurityStationSupport.node(controller)
                .getPlayerID() == nativeOwner,
            "Native station and controller retain owner A throughout teammate access tests");
        EnumSet<SecurityPermissions> actual = cards.get(actor);
        ISecurityGrid security = grid(station).getCache(ISecurityGrid.class);
        for (SecurityPermissions permission : SecurityPermissions.values()) {
            require(
                ctx,
                actual != null && actual.contains(permission),
                "Actual biometric card grants teammate " + permission);
            ctx.event(
                "topology_card",
                "actor=" + actor
                    + "; "
                    + permission
                    + "=true; nativeCache="
                    + security.hasPermission(member, permission));
        }
    }

    private boolean bindingExists(TestContext ctx, ItemStack stack) {
        int[] anchor = ItemNetworkQuantumTerminal.getAnchor(stack);
        return anchor != null && anchor[0] == ctx.world().provider.dimensionId
            && anchor[1] == X
            && anchor[2] == Y
            && anchor[3] == Z
            && QuantumControllerRegistry.get(ctx.world())
                .resolveAnchor(
                    QuantumControllerRegistry.pack(X, Y, Z),
                    stack.getTagCompound()
                        .getString(ItemNetworkQuantumTerminal.NBT_ANCHOR_GENERATION))
                != null;
    }

    private long key(ItemStack stack) {
        return Long.parseLong(((ItemNetworkQuantumTerminal) stack.getItem()).getEncryptionKey(stack));
    }

    private Object encodedProvider(long key) {
        return AEApi.instance()
            .registries()
            .locatable()
            .getLocatableBy(key);
    }

    private IGrid grid(TileEntity tile) {
        return NativeSecurityStationSupport.node(tile)
            .getGrid();
    }

    private EnumSet<ForgeDirection> proxySides() {
        return ((IGridProxyable) controller).getProxy()
            .getConnectableSides();
    }

    private void observe(TestContext ctx, String phase) {
        IGridNode control = NativeSecurityStationSupport.node(controller),
            provider = NativeSecurityStationSupport.node(station);
        ISecurityGrid security = provider == null ? null
            : provider.getGrid()
                .getCache(ISecurityGrid.class);
        ctx.event(
            "topology",
            phase + "; controllerGrid="
                + (control == null ? "absent" : System.identityHashCode(control.getGrid()))
                + "; providerGrid="
                + (provider == null ? "absent" : System.identityHashCode(provider.getGrid()))
                + "; filter="
                + (controller == null ? "absent" : proxySides())
                + "; providerAvailable="
                + (security != null && security.isAvailable())
                + "; providerKey="
                + (station instanceof ISecurityProvider ? ((ISecurityProvider) station).getSecurityKey() : "absent")
                + "; handedOffKey="
                + (handoff == null ? "absent" : key(handoff))
                + "; nativeOwner="
                + (station instanceof ISecurityProvider ? ((ISecurityProvider) station).getOwner() : -1));
    }

    private void next(TestContext ctx, int value) {
        stage = value;
        stageTick = ctx.tick();
        ctx.event("stage", id() + ":" + value);
    }

    private static void require(TestContext ctx, boolean value, String detail) {
        TestWorldFixtures.require(ctx, value, detail);
    }

    private void remove(TestContext ctx, int x, int y) {
        TileEntity tile = ctx.world()
            .getTileEntity(x, y, Z);
        if (tile instanceof AEBaseTile) ((AEBaseTile) tile).disableDrops();
        ctx.world()
            .setBlockToAir(x, y, Z);
    }

    @Override
    public void cleanup(TestContext ctx) {
        if (!started) return;
        try {
            QuantumControllerRegistry.get(ctx.world())
                .dequantize(X, Y, Z);
            for (int x = REMOTE_STATION_X; x >= X; x--) remove(ctx, x, Y);
            remove(ctx, X + 1, Y - 1);
            remove(ctx, X + 2, Y - 1);
        } finally {
            if (owner != null) owner.inventory.clearInventory(null, -1);
            if (member != null) member.inventory.clearInventory(null, -1);
            QuantumNetworkSecurityRegistry providers = QuantumNetworkSecurityRegistry.providerRegistry(ctx.world());
            for (long key : introducedKeys) TestFixtureAccess.forgetProvider(providers, ctx.world(), key);
            introducedKeys.clear();
            started = false;
        }
    }
}
