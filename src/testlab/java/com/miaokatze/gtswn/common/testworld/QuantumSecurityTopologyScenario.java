// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.testworld;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.StatCollector;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.monitoring.MonitoringProfiles;
import com.miaokatze.gtswn.common.quantum.QuantumControllerEventHandler;
import com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumMigrationJournal;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkSecurityRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumSecurityPreflight;
import com.miaokatze.gtswn.common.quantum.QuantumTerminalAccess;
import com.miaokatze.gtswn.config.Config;

import appeng.api.AEApi;
import appeng.api.config.SecurityPermissions;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridConnection;
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
    private NativeSecurityStationSupport.RecordingActor owner, member, outsider;
    private TileEntity controller, station;
    private ItemStack ownerTerminal, handoff, currentCard;
    private int stage;
    private long stageTick, originalKey;
    private boolean started, openCancellationRestored;

    @Override
    public String id() {
        return "quantum.native_security_topology";
    }

    @Override
    public String description() {
        return "Unsafe topology guard, legacy owner recovery and native manager-only explicit controller re-pairing";
    }

    @Override
    public boolean includeInAll() {
        return false;
    }

    @Override
    public int timeoutTicks() {
        return 2400;
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
        openCancellationRestored = false;
        stageTick = ctx.tick();
        owner = NativeSecurityStationSupport.actor(ctx, "owner");
        member = NativeSecurityStationSupport.actor(ctx, "member");
        outsider = NativeSecurityStationSupport.actor(ctx, "outsider");
        NativeSecurityMatrix.assertOrdinaryActors(ctx, "topology initial", owner, member, outsider);
        owner.setPosition(X + .5, Y, Z + 2.5);
        member.setPosition(X + .5, Y, Z + 2.5);
        outsider.setPosition(X + .5, Y, Z + 2.5);
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
                    "Native encoder pairs to the actual same-grid station");
                assertUnsafeQuantizationRejected(ctx);
                // Seed an explicit legacy fixture using the old saved-state shape, not the guarded player entry.
                Set<Long> legacyMembers = QuantumControllerRegistry.floodControllers(ctx.world(), X, Y, Z);
                require(
                    ctx,
                    QuantumControllerRegistry.get(ctx.world())
                        .quantizeAll(legacyMembers, QuantumControllerRegistry.pack(X, Y, Z)),
                    "Test-only legacy fixture records the pre-fix quantum state");
                QuantumControllerEventHandler.applyConnectionFilter(ctx.world(), X, Y, Z);
                ItemNetworkQuantumTerminal.writeAnchor(ownerTerminal, ctx.world(), X, Y, Z);
                handoff = ownerTerminal.copy();
                ctx.event(
                    "fixture",
                    "LEGACY_SAVED_STATE_SEED: registry/filter/anchor fixture only; guarded production gesture was rejected");
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
                    "Actual controller owner enables local safety for the legacy fixture");
                assertGrants(ctx);
                require(
                    ctx,
                    encodedProvider(originalKey) == station && key(handoff) == originalKey,
                    "Legacy handed-off terminal key still identifies the original station");
                require(
                    ctx,
                    !MonitoringProfiles.hasProfileReference(handoff),
                    "Legacy fixture has no independent Profile ACL");
                rejectBoundAndFresh(ctx, "LEGACY_VALID_KEY_DIFFERENT_GRID");
                require(
                    ctx,
                    !QuantumTerminalAccess.canRecoverController(member, handoff, ctx.world(), X, Y, Z),
                    "SEC grants on a detached grid cannot authorize management of the current controller");
                member.clearMessages();
                click(ctx, member, handoff, true);
                require(
                    ctx,
                    QuantumControllerRegistry.get(ctx.world())
                        .isQuantized(X, Y, Z) && hasChat(member, "gtswn.chat.quantum.security_unavailable"),
                    "Detached teammate cannot cancel legacy control and receives the current-provider diagnostic");
                require(
                    ctx,
                    QuantumTerminalAccess.canRecoverController(owner, ownerTerminal, ctx.world(), X, Y, Z),
                    "Real current controller owner retains explicit keyless recovery");
                click(ctx, owner, ownerTerminal, true);
                require(
                    ctx,
                    !QuantumControllerRegistry.get(ctx.world())
                        .isQuantized(X, Y, Z) && !ItemNetworkQuantumTerminal.isBound(ownerTerminal),
                    "Owner production Shift gesture suspends legacy quantization and clears the held anchor");
                next(ctx, 3);
                return;
            case 3:
                if (!NativeSecurityStationSupport.ready(controller, station)) return;
                require(
                    ctx,
                    grid(controller) == grid(station) && allNativeGrants(),
                    "Legacy cancellation naturally restores the original native same-grid station");
                observe(ctx, "LEGACY_OWNER_RECOVERED_NATIVE_GRID");
                remove(ctx, REMOTE_STATION_X, Y);
                remove(ctx, X + 2, Y);
                remove(ctx, X + 1, Y);
                station = placeStation(ctx, X + 1);
                next(ctx, 4);
                return;
            case 4:
                if (!NativeSecurityStationSupport.ready(controller, station)) return;
                require(
                    ctx,
                    QuantumSecurityPreflight.preservesNativeSecurity(
                        ctx.world(),
                        QuantumControllerRegistry.floodControllers(ctx.world(), X, Y, Z)),
                    "Supported adjacent-station topology passes the same production preflight");
                ownerTerminal = NativeSecurityStationSupport
                    .encodeAt(ctx, ownerTerminal, station, owner, introducedKeys::add);
                click(ctx, owner, ownerTerminal);
                require(
                    ctx,
                    QuantumControllerRegistry.get(ctx.world())
                        .isQuantized(X, Y, Z) && bindingExists(ctx, ownerTerminal),
                    "Actual owner resumes quantum control through the guarded supported gesture");
                require(
                    ctx,
                    QuantumTerminalAccess.setNetworkSafety(owner, ownerTerminal, false),
                    "Native owner selects local OFF for the existing cancellation compatibility control");
                currentCard = NativeSecurityStationSupport.card(member, SecurityPermissions.BUILD);
                require(
                    ctx,
                    NativeSecurityStationSupport.insertCard(ctx, station, owner, currentCard) == null,
                    "Owner inserts BUILD-only pairing negative control");
                next(ctx, 5);
                return;
            case 5:
                if (!NativeSecurityStationSupport.ready(controller, station) || !hasGrant(SecurityPermissions.BUILD)
                    || hasGrant(SecurityPermissions.SECURITY)) return;
                require(
                    ctx,
                    ((ISecurityProvider) station).getSecurityKey() != originalKey,
                    "Replacement station has its own native key");
                if (!openCancellationCompatibility(ctx)) return;
                rejectBoundAndFresh(ctx, "BUILD_ONLY_CANNOT_ISSUE_NEW_PAIRING");
                replaceCard(ctx, SecurityPermissions.SECURITY);
                next(ctx, 6);
                return;
            case 6:
                if (!NativeSecurityStationSupport.ready(controller, station) || !hasGrant(SecurityPermissions.SECURITY)
                    || hasGrant(SecurityPermissions.BUILD)) return;
                rejectBoundAndFresh(ctx, "SECURITY_ONLY_CANNOT_ISSUE_ORDINARY_ACCESS_PAIRING");
                require(
                    ctx,
                    QuantumTerminalAccess.canRecoverController(member, handoff, ctx.world(), X, Y, Z),
                    "Current-grid SECURITY-only actor can manage recovery without gaining ordinary BUILD access");
                require(
                    ctx,
                    NativeSecurityStationSupport.extractCard(ctx, station, owner, currentCard) != null,
                    "Owner removes SECURITY-only card");
                currentCard = NativeSecurityStationSupport.allPermissionsCard(member);
                require(
                    ctx,
                    NativeSecurityStationSupport.insertCard(ctx, station, owner, currentCard) == null,
                    "Owner inserts all-native-grants card for explicit repair");
                next(ctx, 7);
                return;
            case 7:
                if (!NativeSecurityStationSupport.ready(controller, station) || !allNativeGrants()) return;
                assertGrants(ctx);
                require(
                    ctx,
                    key(handoff) == originalKey && !QuantumTerminalAccess.validate(member, handoff),
                    "All-grants terminal is still stale before its explicit repair gesture");
                member.clearMessages();
                click(ctx, member, handoff);
                require(
                    ctx,
                    key(handoff) == ((ISecurityProvider) station).getSecurityKey()
                        && encodedProvider(key(handoff)) == station,
                    "Explicit target BUILD plus native owner/SEC gesture repairs via native INetworkEncodable");
                require(
                    ctx,
                    bindingExists(ctx, handoff) && QuantumTerminalAccess.validate(member, handoff),
                    "Repaired teammate binding permits ordinary access");
                QuantumNetworkData full = QuantumNetworkData.assemble(member, handoff);
                require(
                    ctx,
                    full != null && full.online
                        && !full.managementOnly
                        && full.networkSafetyEnabled
                        && (full.channelsInfinite || full.totalChannels > 0),
                    "Supported native manager repair restores full positive network data");
                NativeSecurityMatrix.assertOrdinaryActors(ctx, "topology recovered", owner, member);
                observe(ctx, "GUARDED_TOPOLOGY_AND_EXPLICIT_NATIVE_MANAGER_REPAIR_PASSED");
                ctx.event(
                    "recovery_chat",
                    member.messages()
                        .toString());
                ctx.pass(
                    "Unsafe topology rejected without state mutation; explicit native owner recovered legacy isolation; BUILD/SEC-only cannot issue keys; current-grid BUILD plus native manager explicit repair restores positive access.");
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
        require(
            ctx,
            hasChat(member, "gtswn.chat.quantum.pairing_mismatch"),
            "Real bound-terminal callback explains the pairing mismatch");
        ctx.event("topology_denial_chat", reason + "; bound=" + member.messages());
        ItemStack fresh = handoff.copy();
        fresh.getTagCompound()
            .removeTag(ItemNetworkQuantumTerminal.NBT_BOUND);
        member.clearMessages();
        click(ctx, member, fresh);
        require(
            ctx,
            !ItemNetworkQuantumTerminal.isBound(fresh) && hasChat(member, "gtswn.chat.quantum.pairing_mismatch"),
            "Same key on an unbound control is refused without writing a controller binding");
        ctx.event("topology_denial_chat", reason + "; fresh=" + member.messages());
    }

    private boolean hasChat(NativeSecurityStationSupport.RecordingActor actor, String key) {
        String expected = StatCollector.translateToLocal(key);
        for (String message : actor.messages()) if (message.contains(expected)) return true;
        return false;
    }

    private void click(TestContext ctx, NativeSecurityStationSupport.RecordingActor actor, ItemStack held) {
        click(ctx, actor, held, false);
    }

    private void click(TestContext ctx, NativeSecurityStationSupport.RecordingActor actor, ItemStack held,
        boolean sneak) {
        actor.inventory.currentItem = 0;
        actor.inventory.setInventorySlotContents(0, held);
        actor.setSneaking(sneak);
        try {
            actor.theItemInWorldManager.activateBlockOrUseItem(actor, ctx.world(), held, X, Y, Z, 1, .5f, .5f, .5f);
        } finally {
            actor.setSneaking(false);
        }
    }

    private boolean openCancellationCompatibility(TestContext ctx) {
        NativeSecurityMatrix.assertOrdinaryActors(ctx, "OFF/ON cancellation compatibility", outsider);
        ISecurityGrid security = grid(station).getCache(ISecurityGrid.class);
        require(
            ctx,
            !security.hasPermission(outsider, SecurityPermissions.BUILD)
                && !security.hasPermission(outsider, SecurityPermissions.SECURITY),
            "Cancellation compatibility actor has no native BUILD or SECURITY card");
        if (!openCancellationRestored) {
            ItemStack offControl = ownerTerminal.copy();
            require(
                ctx,
                !QuantumTerminalAccess.isNetworkSafetyEnabled(ctx.world(), X, Y, Z)
                    && QuantumTerminalAccess.canManageAnchor(outsider, offControl, ctx.world(), X, Y, Z)
                    && !QuantumTerminalAccess.canRecoverController(outsider, offControl, ctx.world(), X, Y, Z),
                "Local OFF retains the old bearer cancellation path without granting native recovery authority");
            click(ctx, outsider, offControl, true);
            require(
                ctx,
                !QuantumControllerRegistry.get(ctx.world())
                    .isQuantized(X, Y, Z) && !ItemNetworkQuantumTerminal.isBound(offControl),
                "Actual OFF Shift callback still cancels quantization for the non-owner no-card actor");
            click(ctx, owner, ownerTerminal);
            require(
                ctx,
                QuantumControllerRegistry.get(ctx.world())
                    .isQuantized(X, Y, Z) && QuantumTerminalAccess.setNetworkSafety(owner, ownerTerminal, true),
                "Owner resumes the supported topology and restores safety ON");
            openCancellationRestored = true;
            // Observe native availability on a later END tick before claiming an ON authority denial.
            return false;
        }
        ItemStack onControl = ownerTerminal.copy();
        require(
            ctx,
            !QuantumTerminalAccess.canManageAnchor(outsider, onControl, ctx.world(), X, Y, Z)
                && !QuantumTerminalAccess.canRecoverController(outsider, onControl, ctx.world(), X, Y, Z),
            "The same ON actor fails both ordinary and keyless recovery authority");
        NBTTagCompound before = (NBTTagCompound) onControl.writeToNBT(new NBTTagCompound())
            .copy();
        outsider.clearMessages();
        click(ctx, outsider, onControl, true);
        require(
            ctx,
            QuantumControllerRegistry.get(ctx.world())
                .isQuantized(X, Y, Z) && before.equals(onControl.writeToNBT(new NBTTagCompound()))
                && hasChat(outsider, "gtswn.chat.quantum.manage_permission_required"),
            "Actual ON Shift callback rejects the same no-card actor without changing the held binding");
        ctx.event(
            "open_cancel_compatibility",
            "localOFF=no-card non-owner callback accepted; localON=same actor callback denied; native recovery predicate stayed false");
        return true;
    }

    private void assertUnsafeQuantizationRejected(TestContext ctx) {
        owner.inventory.setInventorySlotContents(0, ownerTerminal);
        NBTTagCompound terminalBefore = (NBTTagCompound) ownerTerminal.writeToNBT(new NBTTagCompound())
            .copy();
        NBTTagList inventoryBefore = (NBTTagList) owner.inventory.writeToNBT(new NBTTagList())
            .copy();
        NBTTagCompound registryBefore = new NBTTagCompound(), safetyBefore = new NBTTagCompound();
        QuantumControllerRegistry.get(ctx.world())
            .writeToNBT(registryBefore);
        QuantumNetworkSecurityRegistry.get(ctx.world())
            .writeToNBT(safetyBefore);
        long journalBefore = QuantumMigrationJournal.get(ctx.world())
            .currentSequence();
        EnumSet<ForgeDirection> sidesBefore = EnumSet.copyOf(proxySides());
        IGrid gridBefore = grid(controller);
        List<IGridConnection> edgesBefore = new ArrayList<>();
        for (IGridConnection edge : NativeSecurityStationSupport.node(controller)
            .getConnections()) edgesBefore.add(edge);
        require(
            ctx,
            !edgesBefore.isEmpty() && !QuantumSecurityPreflight
                .preservesNativeSecurity(ctx.world(), QuantumControllerRegistry.floodControllers(ctx.world(), X, Y, Z)),
            "Real existing glass-cable path is rejected by preflight");
        owner.clearMessages();
        click(ctx, owner, ownerTerminal);
        NBTTagCompound registryAfter = new NBTTagCompound(), safetyAfter = new NBTTagCompound();
        QuantumControllerRegistry.get(ctx.world())
            .writeToNBT(registryAfter);
        QuantumNetworkSecurityRegistry.get(ctx.world())
            .writeToNBT(safetyAfter);
        require(
            ctx,
            !QuantumControllerRegistry.get(ctx.world())
                .isQuantized(X, Y, Z) && !ItemNetworkQuantumTerminal.isBound(ownerTerminal),
            "Unsafe production gesture writes neither controller registration nor terminal anchor");
        require(
            ctx,
            terminalBefore.equals(ownerTerminal.writeToNBT(new NBTTagCompound()))
                && inventoryBefore.equals(owner.inventory.writeToNBT(new NBTTagList()))
                && registryBefore.equals(registryAfter)
                && safetyBefore.equals(safetyAfter)
                && journalBefore == QuantumMigrationJournal.get(ctx.world())
                    .currentSequence(),
            "Unsafe gesture leaves full terminal/inventory/registry/safety/journal state unchanged");
        require(
            ctx,
            grid(controller) == gridBefore && grid(station) == gridBefore && sidesBefore.equals(proxySides()),
            "Unsafe gesture leaves actual native grid membership and validSides unchanged");
        for (IGridConnection edge : edgesBefore) require(
            ctx,
            edge.a()
                .getConnections()
                .contains(edge)
                && edge.b()
                    .getConnections()
                    .contains(edge),
            "Unsafe gesture keeps both actual endpoints of the original native edge");
        require(
            ctx,
            hasChat(owner, "gtswn.chat.quantum.security_topology_unsafe"),
            "Unsafe gesture emits the specific topology explanation");
        ctx.event("unsafe_topology_rejected", "No mutation; native original edges retained; chat=" + owner.messages());
    }

    private boolean hasGrant(SecurityPermissions permission) {
        ISecurityGrid security = grid(station).getCache(ISecurityGrid.class);
        return security.hasPermission(member, permission);
    }

    private void replaceCard(TestContext ctx, SecurityPermissions permission) {
        require(
            ctx,
            NativeSecurityStationSupport.extractCard(ctx, station, owner, currentCard) != null,
            "Owner removes the previous exact native permission card");
        currentCard = NativeSecurityStationSupport.card(member, permission);
        require(
            ctx,
            NativeSecurityStationSupport.insertCard(ctx, station, owner, currentCard) == null,
            "Owner inserts the next exact native permission card");
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
            if (outsider != null) outsider.inventory.clearInventory(null, -1);
            QuantumNetworkSecurityRegistry providers = QuantumNetworkSecurityRegistry.providerRegistry(ctx.world());
            for (long key : introducedKeys) TestFixtureAccess.forgetProvider(providers, ctx.world(), key);
            introducedKeys.clear();
            started = false;
        }
    }
}
