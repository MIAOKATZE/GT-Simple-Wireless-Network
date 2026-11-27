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
    private TileEntity migrationSource;
    private TileEntity placementStation;
    private ItemStack ownerTerminal, handoff, currentCard;
    private int stage;
    private long stageTick, originalKey;
    private boolean started, openCancellationRestored, openCancellationResumed;

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
        migrationSource = null;
        placementStation = null;
        openCancellationRestored = false;
        openCancellationResumed = false;
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
        require(
            ctx,
            ctx.world()
                .isAirBlock(X + 8, Y, Z),
            "Open migration source position starts empty");
        migrationSource = TestWorldFixtures.aeOwnedBlock(
            ctx.world(),
            X + 8,
            Y,
            Z,
            AEApi.instance()
                .definitions()
                .blocks()
                .controller()
                .maybeStack(1)
                .get(),
            owner);
        ctx.event("transport", "Synthetic non-OP server actors and real native callbacks; no TCP or client claim");
    }

    @Override
    public void tick(TestContext ctx) {
        NativeSecurityStationSupport.power(controller);
        NativeSecurityStationSupport.power(station);
        NativeSecurityStationSupport.power(migrationSource);
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
                if (!ItemNetworkQuantumTerminal
                    .isControllerActive((appeng.tile.networking.TileController) migrationSource)) return;
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
                assertUnprivilegedConversionRejected(ctx);
                assertOpenMigrationRejected(ctx);
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
                NBTTagCompound beforeEnable = (NBTTagCompound) ownerTerminal.writeToNBT(new NBTTagCompound())
                    .copy();
                require(
                    ctx,
                    !QuantumTerminalAccess.setNetworkSafety(owner, ownerTerminal, true)
                        && !QuantumTerminalAccess.isNetworkSafetyEnabled(ctx.world(), X, Y, Z)
                        && beforeEnable.equals(ownerTerminal.writeToNBT(new NBTTagCompound())),
                    "Production safety ON rejects a missing station without changing OFF or the held key");
                assertSecurityStationPlacement(ctx);
                // Explicit old-save input: the new production API intentionally cannot create this broken ON state.
                QuantumNetworkSecurityRegistry.get(ctx.world())
                    .setSafety(
                        ctx.world(),
                        QuantumControllerRegistry.get(ctx.world())
                            .registeredStructure(QuantumControllerRegistry.pack(X, Y, Z)),
                        true);
                ctx.event(
                    "fixture",
                    "LEGACY_ON_WITH_MISSING_STATION: registry seed only, after real enabling rejection");
                assertGrants(ctx);
                require(
                    ctx,
                    encodedProvider(originalKey) == station && key(handoff) == originalKey,
                    "Legacy handed-off terminal key still identifies the original station");
                require(
                    ctx,
                    !MonitoringProfiles.hasProfileReference(handoff),
                    "Legacy fixture has no independent Profile ACL");
                rejectBoundAndFresh(ctx, "LEGACY_VALID_KEY_DIFFERENT_GRID", "gtswn.chat.quantum.security_missing");
                require(
                    ctx,
                    !QuantumTerminalAccess.canRecoverController(member, handoff, ctx.world(), X, Y, Z),
                    "SEC grants on a detached grid cannot authorize management of the current controller");
                member.clearMessages();
                click(ctx, member, handoff, true);
                require(
                    ctx,
                    QuantumControllerRegistry.get(ctx.world())
                        .isQuantized(X, Y, Z) && hasChat(member, "gtswn.chat.quantum.controller_owner_required"),
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
                assertUnpairedManagementBinding(ctx);
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
        rejectBoundAndFresh(ctx, reason, "gtswn.chat.quantum.pairing_mismatch");
    }

    private void rejectBoundAndFresh(TestContext ctx, String reason, String expectedFailure) {
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
            hasChat(member, expectedFailure),
            "Real bound-terminal callback reports the actual missing-station or pairing cause");
        ctx.event("topology_denial_chat", reason + "; bound=" + member.messages());
        ItemStack fresh = handoff.copy();
        fresh.getTagCompound()
            .removeTag(ItemNetworkQuantumTerminal.NBT_BOUND);
        member.clearMessages();
        click(ctx, member, fresh);
        require(
            ctx,
            !ItemNetworkQuantumTerminal.isBound(fresh) && hasChat(member, expectedFailure),
            "Same key on an unbound control is refused without writing a controller binding");
        ctx.event("topology_denial_chat", reason + "; fresh=" + member.messages());
        ItemStack neverEncoded = GTSWNItemList.ME_Network_Quantum_Terminal.get(1);
        member.clearMessages();
        click(ctx, member, neverEncoded);
        require(
            ctx,
            !ItemNetworkQuantumTerminal.isBound(neverEncoded) && hasChat(
                member,
                "gtswn.chat.quantum.security_missing".equals(expectedFailure) ? expectedFailure
                    : "gtswn.chat.quantum.pairing_required"),
            "Never-encoded terminal distinguishes missing station from missing pairing without granting a binding");
    }

    private void assertUnpairedManagementBinding(TestContext ctx) {
        ItemStack management = GTSWNItemList.ME_Network_Quantum_Terminal.get(1);
        member.clearMessages();
        click(ctx, member, management);
        require(
            ctx,
            ItemNetworkQuantumTerminal.isBound(management) && management.getTagCompound()
                .getString(QuantumTerminalAccess.NBT_ENCRYPTION_KEY)
                .isEmpty()
                && QuantumTerminalAccess.canChangeNetworkSafety(member, management)
                && QuantumTerminalAccess.canOpenTerminal(member, management)
                && !QuantumTerminalAccess.validate(member, management),
            "SECURITY-only actor binds management access without encoding a key or acquiring ordinary BUILD access");
        NativeSecurityMatrix.assertManagementOnly(ctx, member, management);
        String confirmation = StatCollector
            .translateToLocalFormatted("gtswn.chat.quantum.bound_management_only", X, Y, Z);
        require(
            ctx,
            member.messages()
                .stream()
                .anyMatch(message -> message.contains(confirmation)),
            "Management binding is explicitly explained by the real interaction reply");
        require(
            ctx,
            hasGrant(SecurityPermissions.SECURITY) && !hasGrant(SecurityPermissions.BUILD),
            "Management binding does not change the actual native ACL");
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
        if (!openCancellationRestored && openCancellationResumed) {
            if (!NativeSecurityStationSupport.ready(controller, station)) return false;
            require(
                ctx,
                QuantumTerminalAccess.setNetworkSafety(owner, ownerTerminal, true),
                "Owner restores safety ON after natural native station readiness");
            openCancellationRestored = true;
            return false;
        }
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
                    .isQuantized(X, Y, Z),
                "Owner resumes the supported topology; safety waits for native station readiness");
            openCancellationResumed = true;
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

    private void assertUnprivilegedConversionRejected(TestContext ctx) {
        ItemStack fresh = GTSWNItemList.ME_Network_Quantum_Terminal.get(1);
        NBTTagCompound before = fresh.writeToNBT(new NBTTagCompound());
        outsider.clearMessages();
        click(ctx, outsider, fresh);
        require(
            ctx,
            !QuantumControllerRegistry.get(ctx.world())
                .isQuantized(X, Y, Z) && before.equals(fresh.writeToNBT(new NBTTagCompound()))
                && hasChat(outsider, "gtswn.chat.quantum.manage_permission_required"),
            "Unprivileged actor cannot first-convert a native secured grid through default OFF");
    }

    private void assertSecurityStationPlacement(TestContext ctx) {
        require(
            ctx,
            ctx.world()
                .isAirBlock(X, Y + 1, Z),
            "Direct security-station placement target is empty");
        ItemStack placed = AEApi.instance()
            .definitions()
            .blocks()
            .security()
            .maybeStack(1)
            .get();
        owner.inventory.setInventorySlotContents(0, placed);
        net.minecraftforge.event.entity.player.PlayerInteractEvent event = new net.minecraftforge.event.entity.player.PlayerInteractEvent(
            owner,
            net.minecraftforge.event.entity.player.PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK,
            X,
            Y,
            Z,
            1,
            ctx.world());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(event);
        require(
            ctx,
            !event.isCanceled() && event.useItem != cpw.mods.fml.common.eventhandler.Event.Result.DENY,
            "Actual Forge right-click event allows a security station on a quantum controller");
        owner.theItemInWorldManager.activateBlockOrUseItem(owner, ctx.world(), placed, X, Y, Z, 1, .5f, 1f, .5f);
        TileEntity result = ctx.world()
            .getTileEntity(X, Y + 1, Z);
        if (result instanceof appeng.tile.misc.TileSecurity) placementStation = result;
        require(
            ctx,
            placed.stackSize == 0 && placementStation != null,
            "Native ItemBlock placement consumes one real station and creates it directly above the quantum controller");
        remove(ctx, X, Y + 1);
        placementStation = null;
        owner.inventory.setInventorySlotContents(0, ownerTerminal);
    }

    private void assertOpenMigrationRejected(TestContext ctx) {
        ItemStack open = GTSWNItemList.ME_Network_Quantum_Terminal.get(1);
        owner.inventory.setInventorySlotContents(0, open);
        owner.theItemInWorldManager.activateBlockOrUseItem(owner, ctx.world(), open, X + 8, Y, Z, 1, .5f, .5f, .5f);
        require(
            ctx,
            QuantumControllerRegistry.get(ctx.world())
                .isQuantized(X + 8, Y, Z) && ItemNetworkQuantumTerminal.isBound(open),
            "Real no-station first conversion remains usable and OFF");
        QuantumTerminalAccess.synchronize(owner, open);
        NBTTagCompound before = open.writeToNBT(new NBTTagCompound());
        NBTTagList inventory = (NBTTagList) owner.inventory.writeToNBT(new NBTTagList())
            .copy();
        IGrid nativeGrid = grid(controller);
        EnumSet<ForgeDirection> sides = EnumSet.copyOf(proxySides());
        List<IGridConnection> edges = new ArrayList<>();
        for (IGridConnection edge : NativeSecurityStationSupport.node(controller)
            .getConnections()) edges.add(edge);
        NBTTagCompound registry = new NBTTagCompound(), safety = new NBTTagCompound();
        QuantumControllerRegistry.get(ctx.world())
            .writeToNBT(registry);
        QuantumNetworkSecurityRegistry.get(ctx.world())
            .writeToNBT(safety);
        long journal = QuantumMigrationJournal.get(ctx.world())
            .currentSequence();
        boolean global = Config.quantumSafetyEnabled;
        try {
            for (boolean enabled : new boolean[] { true, false }) {
                Config.quantumSafetyEnabled = enabled;
                owner.clearMessages();
                require(
                    ctx,
                    !com.miaokatze.gtswn.common.quantum.QuantumNetworkMigration
                        .migrate(owner, open, ctx.world(), X, Y, Z),
                    "OFF-to-OFF unsafe migration rejected; globalSafety=" + enabled);
                NBTTagCompound afterRegistry = new NBTTagCompound(), afterSafety = new NBTTagCompound();
                QuantumControllerRegistry.get(ctx.world())
                    .writeToNBT(afterRegistry);
                QuantumNetworkSecurityRegistry.get(ctx.world())
                    .writeToNBT(afterSafety);
                require(
                    ctx,
                    before.equals(open.writeToNBT(new NBTTagCompound())) && registry.equals(afterRegistry)
                        && safety.equals(afterSafety)
                        && journal == QuantumMigrationJournal.get(ctx.world())
                            .currentSequence()
                        && hasChat(owner, "gtswn.chat.quantum.security_topology_unsafe"),
                    "Rejected open migration preserves held item, both registries and journal with specific reason");
                List<IGridConnection> actualEdges = new ArrayList<>();
                for (IGridConnection edge : NativeSecurityStationSupport.node(controller)
                    .getConnections()) actualEdges.add(edge);
                require(
                    ctx,
                    inventory.equals(owner.inventory.writeToNBT(new NBTTagList())) && nativeGrid == grid(controller)
                        && sides.equals(proxySides())
                        && edges.size() == actualEdges.size()
                        && edges.containsAll(actualEdges),
                    "Rejected open migration preserves actual inventory, controller sides, native grid and edge identities");
            }
        } finally {
            Config.quantumSafetyEnabled = global;
        }
        owner.setSneaking(true);
        try {
            owner.theItemInWorldManager.activateBlockOrUseItem(owner, ctx.world(), open, X + 8, Y, Z, 1, .5f, .5f, .5f);
        } finally {
            owner.setSneaking(false);
        }
        require(
            ctx,
            !QuantumControllerRegistry.get(ctx.world())
                .isQuantized(X + 8, Y, Z) && !ItemNetworkQuantumTerminal.isBound(open),
            "Open no-station cancellation remains usable");
        owner.inventory.setInventorySlotContents(0, ownerTerminal);
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
            if (placementStation != null) {
                require(
                    ctx,
                    ctx.world()
                        .getTileEntity(X, Y + 1, Z) == placementStation,
                    "Owned placement station was not replaced before cleanup");
                remove(ctx, X, Y + 1);
                placementStation = null;
            }
            QuantumControllerRegistry.get(ctx.world())
                .dequantize(X, Y, Z);
            if (migrationSource != null) {
                QuantumControllerRegistry.get(ctx.world())
                    .dequantize(X + 8, Y, Z);
                remove(ctx, X + 8, Y);
            }
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
