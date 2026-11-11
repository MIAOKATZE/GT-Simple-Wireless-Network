// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.testworld;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.monitoring.MonitoringProfiles;
import com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumMigrationJournal;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkMigration;
import com.miaokatze.gtswn.common.quantum.QuantumTerminalAccess;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.register.BlockRegistrar;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.config.SecurityPermissions;
import appeng.api.implementations.items.IBiometricCard;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.pathing.ControllerState;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.networking.security.ISecurityProvider;
import appeng.api.storage.data.IAEItemStack;
import appeng.me.GridAccessException;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import appeng.tile.AEBaseTile;

/** Real station/card transitions, isolated from production and driven only by ordinary server ticks. */
final class NativeSecurityMatrix {

    private static final int NODE_X = 6, Y = TestWorldFixtures.Y, NODE_Z = 8, FOREIGN_X = 16, FOREIGN_Z = 16;
    private static final int REJECT_X = 5, REJECT_Z = 8;
    private final NativeSecurityScenario fixture;
    private final FakePlayer owner, member, outsider;
    private final TileEntity controller, station;
    private final ItemStack terminal;
    private ItemStack buildCard, memberTerminal;
    private TileEntityNetworkQuantumNode node;
    private TileEntity foreignController, foreignStation;
    private boolean nodePlaced, foreignControllerPlaced, foreignStationPlaced, rejectionFloorPlaced;
    private NBTTagCompound linkedBinding;
    private final List<IGridConnection> offEdges = new ArrayList<>();
    private String lastModeEnforcementObservation;
    private int phase;
    private long phaseTick;

    NativeSecurityMatrix(NativeSecurityScenario fixture, FakePlayer owner, FakePlayer member, FakePlayer outsider,
        TileEntity controller, TileEntity station, ItemStack terminal) {
        this.fixture = fixture;
        this.owner = owner;
        this.member = member;
        this.outsider = outsider;
        this.controller = controller;
        this.station = station;
        this.terminal = terminal;
    }

    void start(TestContext ctx) {
        TestWorldFixtures.requireTestWorld(ctx.world());
        require(ctx, Config.quantumSafetyEnabled && safety(ctx), "Native matrix starts with master ON and local ON");
        assertOrdinaryActors(ctx, "BUILD card provisioning", owner, member, outsider);
        buildCard = fixture.card(member, SecurityPermissions.BUILD);
        require(ctx, fixture.insertCard(buildCard) == null, "Owner inserts actual BUILD-only member card");
        pairMemberTerminal(ctx);
        next(ctx, 0);
    }

    void tick(TestContext ctx) {
        if (!Config.quantumSafetyEnabled) {
            require(ctx, false, "Global master disabled during native matrix; this cannot be security PASS");
            return;
        }
        power(foreignController);
        if (ctx.tick() - phaseTick > 300) {
            ctx.fail("Native security matrix timed out at phase=" + phase);
            return;
        }
        switch (phase) {
            case 0:
                if (!ready(controller, station) || !security().hasPermission(member, SecurityPermissions.BUILD)
                    || security().hasPermission(member, SecurityPermissions.SECURITY)) return;
                ordinaryMemberBinding(ctx);
                require(ctx, QuantumTerminalAccess.validate(member, memberTerminal), "Actual BUILD card permits use");
                require(ctx, !QuantumTerminalAccess.canManage(member, memberTerminal), "BUILD alone cannot manage");
                require(
                    ctx,
                    !QuantumTerminalAccess.canChangeNetworkSafety(member, memberTerminal),
                    "BUILD alone cannot control either local mode");
                require(ctx, QuantumNetworkData.assemble(owner, terminal) != null, "Warm actual owner data cache");
                require(
                    ctx,
                    ctx.world()
                        .isAirBlock(REJECT_X, Y, REJECT_Z)
                        && ctx.world()
                            .isAirBlock(REJECT_X, Y - 1, REJECT_Z),
                    "Rejection node and support positions start empty");
                rejectionFloorPlaced = true;
                TestWorldFixtures.place(ctx.world(), REJECT_X, Y - 1, REJECT_Z, Blocks.stone, 0);
                ItemStack noKey = memberTerminal.copy();
                noKey.getTagCompound()
                    .removeTag(QuantumTerminalAccess.NBT_ENCRYPTION_KEY);
                rejectedInteractions(ctx, "BUILD member without security pairing", member, noKey);
                rejectedInteractions(ctx, "outsider with valid paired terminal but no card", outsider, terminal.copy());
                deny(
                    ctx,
                    "BUILD-only OFF intent",
                    () -> require(
                        ctx,
                        !QuantumTerminalAccess.setNetworkSafety(member, memberTerminal, false),
                        "BUILD cannot turn OFF"));
                deny(
                    ctx,
                    "BUILD-only ON intent",
                    () -> require(
                        ctx,
                        !QuantumTerminalAccess.setNetworkSafety(member, memberTerminal, true),
                        "BUILD cannot turn ON"));
                deny(
                    ctx,
                    "BUILD-only migration",
                    () -> require(
                        ctx,
                        !QuantumNetworkMigration.migrate(member, memberTerminal, ctx.world(), 12, Y, 8),
                        "BUILD cannot enter the production migration transaction"));
                member.setSneaking(true);
                try {
                    deny(
                        ctx,
                        "BUILD-only controller cancellation",
                        () -> ((ItemNetworkQuantumTerminal) memberTerminal.getItem())
                            .onItemUseFirst(memberTerminal, member, ctx.world(), 8, Y, 8, 1, .5f, .5f, .5f));
                } finally {
                    member.setSneaking(false);
                }
                replaceFixtureNode(ctx, memberTerminal, member);
                next(ctx, 1);
                return;
            case 1:
                if (!node.isLinked()) return;
                requireMemberBridge(ctx);
                linkedBinding = binding(node);
                IAEItemStack removed = fixture.extractCard(buildCard);
                require(
                    ctx,
                    removed != null && removed.getStackSize() == 1,
                    "Owner revokes the exact real BUILD card while bridge is live");
                next(ctx, 2);
                return;
            case 2:
                if (!ready(controller, station) || security().hasPermission(member, SecurityPermissions.BUILD)
                    || node.isLinked()) return;
                require(
                    ctx,
                    node.getOfflineReason() == TileEntityNetworkQuantumNode.OfflineReason.NO_PERMISSION,
                    "Natural maintenance disconnects revoked bridge with NO_PERMISSION");
                require(
                    ctx,
                    linkedBinding.equals(binding(node)),
                    "Revocation retains anchor, generation and native actor");
                require(
                    ctx,
                    QuantumNetworkData.assemble(member, memberTerminal) == null,
                    "Revoked actor cannot obtain owner-warmed cached network data");
                deny(
                    ctx,
                    "revoked rebind",
                    () -> ItemNetworkQuantumTerminal.applyAnchorToNode(memberTerminal, node, member));
                deny(
                    ctx,
                    "revoked switch",
                    () -> require(
                        ctx,
                        !QuantumTerminalAccess.setNetworkSafety(member, memberTerminal, false),
                        "Revoked card cannot turn OFF"));
                require(ctx, fixture.insertCard(buildCard) == null, "Owner re-inserts same actual BUILD card");
                next(ctx, 3);
                return;
            case 3:
                if (!ready(controller, station) || !security().hasPermission(member, SecurityPermissions.BUILD)
                    || !node.isLinked()) return;
                requireMemberBridge(ctx);
                require(ctx, linkedBinding.equals(binding(node)), "Card regrant naturally reconnects same binding");
                node = (TileEntityNetworkQuantumNode) TestWorldFixtures.reload(ctx.world(), node);
                require(ctx, linkedBinding.equals(binding(node)), "Node NBT restores native actor and anchor metadata");
                next(ctx, 4);
                return;
            case 4:
                if (!node.isLinked()) return;
                requireMemberBridge(ctx);
                foreignControllerPlaced = true;
                foreignController = TestWorldFixtures.aeOwnedBlock(
                    ctx.world(),
                    FOREIGN_X,
                    Y,
                    FOREIGN_Z,
                    AEApi.instance()
                        .definitions()
                        .blocks()
                        .controller()
                        .maybeStack(1)
                        .get(),
                    outsider);
                foreignStationPlaced = true;
                foreignStation = TestWorldFixtures.aeOwnedBlock(
                    ctx.world(),
                    FOREIGN_X + 1,
                    Y,
                    FOREIGN_Z,
                    AEApi.instance()
                        .definitions()
                        .blocks()
                        .security()
                        .maybeStack(1)
                        .get(),
                    outsider);
                next(ctx, 5);
                return;
            case 5:
                if (!ready(foreignController, foreignStation)) return;
                require(
                    ctx,
                    gridNode(foreignController).getGrid() != gridNode(controller).getGrid(),
                    "Foreign native station remains on an independent real grid");
                ItemStack foreign = fixture.encodeAt(ctx, terminal, foreignStation, outsider);
                rejectedInteractions(ctx, "BUILD member with foreign-grid key", member, foreign.copy());
                require(
                    ctx,
                    ((ISecurityProvider) foreignStation).getOwner() == actorId(outsider),
                    "Foreign station has its actual outsider owner");
                require(
                    ctx,
                    !QuantumTerminalAccess.validate(owner, foreign) && !QuantumTerminalAccess.canManage(owner, foreign),
                    "Foreign encoded key cannot read/manage primary anchor");
                require(
                    ctx,
                    !QuantumTerminalAccess.validate(outsider, foreign)
                        && !QuantumTerminalAccess.canManage(outsider, foreign),
                    "Foreign native owner gains no primary-grid authority");
                assertManagementOnly(ctx, owner, foreign);
                deny(
                    ctx,
                    "foreign owner safety intent",
                    () -> require(
                        ctx,
                        !QuantumTerminalAccess.setNetworkSafety(outsider, foreign, false),
                        "Foreign owner cannot turn primary safety OFF"));
                require(
                    ctx,
                    QuantumTerminalAccess.setNetworkSafety(owner, terminal, false),
                    "Native owner turns local mode OFF");
                outsider.inventory.setInventorySlotContents(0, terminal.copy());
                require(
                    ctx,
                    QuantumNetworkData.assemble(outsider, outsider.getHeldItem()) != null,
                    "Local OFF permits ordinary response before ON revokes it");
                replaceFixtureNode(ctx, outsider.getHeldItem(), outsider);
                next(ctx, 6);
                return;
            case 6:
                if (!node.isLinked() || node.getNativeOwnerID() != actorId(outsider)) return;
                require(ctx, !safety(ctx), "Unprivileged bridge is admitted only while local OFF");
                linkedBinding = binding(node);
                captureOffEdges(ctx);
                deny(
                    ctx,
                    "outsider enabling safety",
                    () -> require(
                        ctx,
                        !QuantumTerminalAccess.setNetworkSafety(outsider, outsider.getHeldItem(), true),
                        "Local OFF does not let outsider control the switch"));
                require(
                    ctx,
                    QuantumTerminalAccess.setNetworkSafety(owner, terminal, true),
                    "Native owner restores local ON");
                observeModeEnforcement(ctx);
                require(ctx, !oldOffEdgeStillAttached(), "ON removes old OFF edges from both native endpoint lists");
                long responseBucket = ctx.world()
                    .getTotalWorldTime() / 100L;
                QuantumNetworkData onOwner = QuantumNetworkData.assemble(owner, terminal);
                require(
                    ctx,
                    onOwner != null && onOwner.online && !onOwner.managementOnly,
                    "Owner assembles full data after ON invalidated the previous cache");
                require(
                    ctx,
                    QuantumNetworkData.assemble(outsider, outsider.getHeldItem()) == null,
                    "Local ON rejects outsider after the owner full response in the same callback");
                require(
                    ctx,
                    ctx.world()
                        .getTotalWorldTime() / 100L == responseBucket,
                    "Owner and refused outsider observations share the same natural response bucket");
                ctx.event("native_data_bucket", "bucket=" + responseBucket + "; ownerFull=true; outsiderDenied=true");
                next(ctx, 7);
                return;
            case 7:
                observeModeEnforcement(ctx);
                require(ctx, !oldOffEdgeStillAttached(), "Old OFF edges cannot survive or reattach while ON");
                require(ctx, !node.isLinked(), "Unauthorized actor cannot retain or recreate a bridge while ON");
                // Edge teardown is synchronous; the reason follows the node's natural maintenance window.
                if (node.getOfflineReason() != TileEntityNetworkQuantumNode.OfflineReason.NO_PERMISSION) return;
                require(
                    ctx,
                    node.getOfflineReason() == TileEntityNetworkQuantumNode.OfflineReason.NO_PERMISSION,
                    "ON removes an existing OFF-exempt unauthorized edge");
                require(
                    ctx,
                    linkedBinding.equals(binding(node)),
                    "Mode enforcement does not rewrite unauthorized placer binding");
                replaceFixtureNode(ctx, memberTerminal, member);
                next(ctx, 8);
                return;
            case 8:
                if (!node.isLinked()) return;
                requireMemberBridge(ctx);
                IAEItemStack removedBuild = fixture.extractCard(buildCard);
                require(
                    ctx,
                    removedBuild != null && removedBuild.getStackSize() == 1,
                    "Owner removes BUILD-only card after its complete regression chain");
                ItemStack allGrants = fixture.card(member, SecurityPermissions.BUILD);
                IBiometricCard biometric = (IBiometricCard) allGrants.getItem();
                for (SecurityPermissions permission : SecurityPermissions.values())
                    biometric.addPermission(allGrants, permission);
                require(
                    ctx,
                    fixture.insertCard(allGrants) == null,
                    "Owner inserts actual all-permissions teammate card");
                next(ctx, 9);
                return;
            case 9:
                if (!ready(controller, station)) return;
                for (SecurityPermissions permission : SecurityPermissions.values())
                    if (!security().hasPermission(member, permission)) return;
                assertOrdinaryActors(ctx, "all-native-grants", owner, member, outsider);
                for (SecurityPermissions permission : SecurityPermissions.values())
                    ctx.event("all_native_grants", "actor=" + actorId(member) + "; " + permission.name() + "=true");
                pairMemberTerminal(ctx);
                ordinaryMemberBinding(ctx);
                require(
                    ctx,
                    QuantumTerminalAccess.canManage(member, memberTerminal)
                        && QuantumTerminalAccess.canChangeNetworkSafety(member, memberTerminal),
                    "Non-owner all-permissions teammate has native management authority as well as ordinary access");
                require(ctx, safety(ctx), "Final local safety remains ON");
                assertOrdinaryActors(ctx, "completed", owner, member, outsider);
                ctx.pass(
                    "Native non-OP BUILD-only/all-grants teammate binding and full data, denial side effects, cached data isolation, live revocation/regrant, node NBT, foreign key and OFF-to-ON edge enforcement passed");
                return;
            default:
                throw new IllegalStateException("Unknown native security matrix phase");
        }
    }

    private void pairMemberTerminal(TestContext ctx) {
        // Pair an otherwise fresh terminal through AE; do not inherit an already-bound controller anchor.
        memberTerminal = fixture.encodeAt(ctx, GTSWNItemList.ME_Network_Quantum_Terminal.get(1), station, owner);
        owner.inventory.setInventorySlotContents(0, terminal);
        QuantumTerminalAccess.synchronize(owner, memberTerminal);
        member.inventory.setInventorySlotContents(0, memberTerminal);
    }

    private void ordinaryMemberBinding(TestContext ctx) {
        require(
            ctx,
            !MonitoringProfiles.hasProfileReference(memberTerminal),
            "Positive teammate fixture is not Profile-managed");
        require(
            ctx,
            !ItemNetworkQuantumTerminal.isBound(memberTerminal),
            "Member receives encoded terminal without a controller anchor");
        require(
            ctx,
            ((ISecurityProvider) station).getOwner() == actorId(owner) && actorId(owner) != actorId(member),
            "Native station owner A is distinct from ordinary BUILD teammate B");
        String describedOwner = memberTerminal.getTagCompound()
            .getString(QuantumTerminalAccess.NBT_OWNER_UUID);
        require(
            ctx,
            owner.getUniqueID()
                .toString()
                .equals(describedOwner),
            "Handed-off terminal initially describes A, not B");
        require(
            ctx,
            Long.toString(((ISecurityProvider) station).getSecurityKey())
                .equals(((ItemNetworkQuantumTerminal) memberTerminal.getItem()).getEncryptionKey(memberTerminal)),
            "Member terminal was actually encoded by this native security station");
        require(
            ctx,
            ctx.world()
                .canMineBlock(member, 8, Y, 8),
            "Ordinary member passes independent world edit protection");
        member.setSneaking(false);
        ((ItemNetworkQuantumTerminal) memberTerminal.getItem())
            .onItemUseFirst(memberTerminal, member, ctx.world(), 8, Y, 8, 1, .5f, .5f, .5f);
        int[] anchor = ItemNetworkQuantumTerminal.getAnchor(memberTerminal);
        require(
            ctx,
            anchor != null && anchor[0] == ctx.world().provider.dimensionId
                && anchor[1] == 8
                && anchor[2] == Y
                && anchor[3] == 8,
            "Non-OP BUILD teammate actually binds protected controller through production item interaction");
        require(
            ctx,
            ((ISecurityProvider) station).getOwner() == actorId(owner)
                && gridNode(controller).getPlayerID() == actorId(owner),
            "Teammate binding does not transfer native network ownership");
        QuantumNetworkData full = QuantumNetworkData.assemble(member, memberTerminal);
        require(
            ctx,
            full != null && full.online
                && !full.managementOnly
                && full.networkSafetyEnabled
                && (full.channelsInfinite || full.totalChannels > 0),
            "Ordinary BUILD teammate receives real full network data while safety ON");
        ctx.event(
            "ordinary_member_access",
            "nativeOwner=" + actorId(owner)
                + "; actor="
                + actorId(member)
                + "; itemOwnerBefore="
                + describedOwner
                + "; actualControllerBind=true; fullResponse=true; BUILD=true; SECURITY="
                + security().hasPermission(member, SecurityPermissions.SECURITY)
                + "; OP2=false; OP4=false; topology=direct-adjacent-native-station");
    }

    private void rejectedInteractions(TestContext ctx, String label, FakePlayer actor, ItemStack held) {
        ItemStack previous = actor.inventory.mainInventory[0];
        ItemStack unbound = held.copy();
        unbound.getTagCompound()
            .removeTag(ItemNetworkQuantumTerminal.NBT_BOUND);
        actor.inventory.setInventorySlotContents(0, unbound);
        actor.setSneaking(false);
        try {
            require(
                ctx,
                !QuantumTerminalAccess.canAccessAnchor(actor, unbound, ctx.world(), 8, Y, 8),
                label + " is denied by clicked-network authority");
            deny(
                ctx,
                label + " controller interaction",
                () -> ((ItemNetworkQuantumTerminal) unbound.getItem())
                    .onItemUseFirst(unbound, actor, ctx.world(), 8, Y, 8, 1, .5f, .5f, .5f));
            require(ctx, !ItemNetworkQuantumTerminal.isBound(unbound), label + " creates no unauthorized binding");
            actor.inventory.setInventorySlotContents(0, held);
            require(
                ctx,
                QuantumNetworkData.assemble(actor, held) == null,
                label + " receives no full or management response");
            require(
                ctx,
                ctx.world()
                    .isAirBlock(REJECT_X, Y, REJECT_Z),
                "Denied node target is empty before actual item interaction");
            deny(
                ctx,
                label + " node interaction",
                () -> ((ItemNetworkQuantumTerminal) held.getItem())
                    .onItemUseFirst(held, actor, ctx.world(), REJECT_X, Y - 1, REJECT_Z, 1, .5f, .5f, .5f));
            require(
                ctx,
                ctx.world()
                    .isAirBlock(REJECT_X, Y, REJECT_Z),
                label + " creates no unauthorized node");
        } finally {
            actor.inventory.setInventorySlotContents(0, previous);
        }
    }

    private void captureOffEdges(TestContext ctx) {
        offEdges.clear();
        try {
            for (String name : new String[] { "connection", "anchorConnection" }) {
                Field field = TileEntityNetworkQuantumNode.class.getDeclaredField(name);
                field.setAccessible(true);
                IGridConnection edge = (IGridConnection) field.get(node);
                if (edge != null && !offEdges.contains(edge)) offEdges.add(edge);
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot inspect the owned fixture's native OFF edges", failure);
        }
        require(ctx, !offEdges.isEmpty() && oldOffEdgeStillAttached(), "Capture real live OFF edges before ON");
    }

    private boolean oldOffEdgeStillAttached() {
        for (IGridConnection edge : offEdges) {
            if (edge.a()
                .getConnections()
                .contains(edge)
                || edge.b()
                    .getConnections()
                    .contains(edge))
                return true;
        }
        return false;
    }

    private void observeModeEnforcement(TestContext ctx) {
        StringBuilder facts = new StringBuilder("linked=").append(node.isLinked())
            .append(";reason=")
            .append(node.getOfflineReason());
        for (int index = 0; index < offEdges.size(); index++) {
            IGridConnection edge = offEdges.get(index);
            facts.append(";edge")
                .append(index)
                .append(".a=")
                .append(
                    edge.a()
                        .getConnections()
                        .contains(edge))
                .append(";edge")
                .append(index)
                .append(".b=")
                .append(
                    edge.b()
                        .getConnections()
                        .contains(edge));
        }
        String observation = facts.toString();
        if (!observation.equals(lastModeEnforcementObservation)) {
            ctx.event("native_mode_enforcement", observation);
            lastModeEnforcementObservation = observation;
        }
    }

    private void requireMemberBridge(TestContext ctx) {
        require(
            ctx,
            node.getNativeOwnerID() == actorId(member),
            "Bridge carries ordinary member's actual native AE actor");
        require(
            ctx,
            node.getCoreGridNode()
                .getGrid() == gridNode(controller).getGrid(),
            "Authorized bridge joins the actual protected controller grid");
    }

    private void next(TestContext ctx, int value) {
        phase = value;
        phaseTick = ctx.tick();
        assertOrdinaryActors(ctx, "matrix phase=" + value, owner, member, outsider);
        for (FakePlayer actor : new FakePlayer[] { owner, member, outsider }) {
            ctx.event(
                "business_native_acl",
                "phase=" + value
                    + "; actor="
                    + actor.getCommandSenderName()
                    + "; BUILD="
                    + security().hasPermission(actor, SecurityPermissions.BUILD)
                    + "; SECURITY="
                    + security().hasPermission(actor, SecurityPermissions.SECURITY));
        }
        ctx.event("native_security_matrix", "phase=" + value + "; native card/cache/connection; no client/Netty claim");
    }

    private void replaceFixtureNode(TestContext ctx, ItemStack held, FakePlayer actor) {
        // AE's setOwner initializes a new node; changing the fixture actor is explicit replacement.
        if (nodePlaced) remove(ctx, NODE_X, NODE_Z);
        nodePlaced = true;
        TestWorldFixtures.place(ctx.world(), NODE_X, Y, NODE_Z, BlockRegistrar.networkQuantumNode, 0);
        node = (TileEntityNetworkQuantumNode) ctx.world()
            .getTileEntity(NODE_X, Y, NODE_Z);
        ItemNetworkQuantumTerminal.applyAnchorToNode(held, node, actor);
        ctx.event("security_fixture_node", "Fresh native node for ordinary actor=" + actor.getCommandSenderName());
    }

    static void assertOrdinaryActors(TestContext ctx, String phase, FakePlayer... actors) {
        for (FakePlayer actor : actors) {
            boolean op2 = actor.canCommandSenderUseCommand(2, "gtswn"),
                op4 = actor.canCommandSenderUseCommand(4, "gtswn");
            require(ctx, !op2 && !op4, "Business actor remains non-OP: " + actor.getCommandSenderName());
            ctx.event(
                "business_actor_authority",
                phase + "; actor="
                    + actor.getCommandSenderName()
                    + "; aeId="
                    + actorId(actor)
                    + "; actorLevel2="
                    + op2
                    + "; actorLevel4="
                    + op4);
        }
    }

    static void assertManagementOnly(TestContext ctx, EntityPlayer actor, ItemStack unpairedOrForeign) {
        QuantumNetworkData data = QuantumNetworkData.assemble(actor, unpairedOrForeign);
        require(
            ctx,
            data != null && data.managementOnly && data.online && !data.networkSafetyIdentity.isEmpty(),
            "Native safety manager receives a distinct management-only recovery response");
        require(
            ctx,
            data.totalChannels == 0 && data.usedChannels == 0
                && data.quantumNodeCount == 0
                && data.incorporationCount == 0
                && data.incorporationChannels == 0
                && data.totalMachines == 0
                && data.entries.isEmpty()
                && data.storedPower == 0
                && data.maxStoredPower == 0
                && data.avgPowerUsage == 0
                && data.avgPowerInjection == 0
                && data.itemBytesUsed == 0
                && data.itemBytesTotal == 0
                && data.fluidBytesUsed == 0
                && data.fluidBytesTotal == 0
                && data.essentiaBytesUsed == 0
                && data.essentiaBytesTotal == 0
                && data.networkSafetyRequestToken == 0
                && !data.canChangeNetworkSafety,
            "Management-only assembly publishes no metrics, entries or shared actor token");
    }

    private void deny(TestContext ctx, String label, Runnable action) {
        for (FakePlayer actor : new FakePlayer[] { owner, member, outsider }) {
            ItemStack held = actor.getHeldItem();
            if (held != null && held.getItem() instanceof ItemNetworkQuantumTerminal)
                QuantumTerminalAccess.synchronize(actor, held);
        }
        Snapshot before = snapshot(ctx);
        action.run();
        require(
            ctx,
            before.same(snapshot(ctx)),
            label + " leaves policy, binding, registry/journal, inventories and same-callback AE energy unchanged");
    }

    private Snapshot snapshot(TestContext ctx) {
        NBTTagCompound data = new NBTTagCompound(), controllers = new NBTTagCompound();
        QuantumControllerRegistry.get(ctx.world())
            .writeToNBT(controllers);
        data.setTag("controllers", controllers);
        data.setLong(
            "journal",
            QuantumMigrationJournal.get(ctx.world())
                .currentSequence());
        data.setBoolean("safety", safety(ctx));
        data.setTag("terminal", terminal.writeToNBT(new NBTTagCompound()));
        captureBlock(ctx, data, "controllerBlock", 8, Y, 8);
        captureBlock(ctx, data, "rejectedNodeBlock", REJECT_X, Y, REJECT_Z);
        captureBlock(ctx, data, "rejectedGroundBlock", REJECT_X, Y - 1, REJECT_Z);
        if (node != null) {
            data.setTag("nodeBinding", binding(node));
            data.setBoolean("nodeLinked", node.isLinked());
            data.setString(
                "nodeReason",
                node.getOfflineReason()
                    .name());
        }
        NBTTagList inventories = new NBTTagList();
        for (FakePlayer actor : new FakePlayer[] { owner, member, outsider }) {
            NBTTagCompound inventory = new NBTTagCompound();
            inventory.setTag("slots", actor.inventory.writeToNBT(new NBTTagList()));
            inventory.setInteger("selected", actor.inventory.currentItem);
            if (actor.inventory.getItemStack() != null) inventory.setTag(
                "cursor",
                actor.inventory.getItemStack()
                    .writeToNBT(new NBTTagCompound()));
            inventories.appendTag(inventory);
        }
        data.setTag("inventories", inventories);
        return new Snapshot(data, energy(controller).getStoredPower());
    }

    private static void captureBlock(TestContext ctx, NBTTagCompound into, String name, int x, int y, int z) {
        NBTTagCompound block = new NBTTagCompound();
        block.setInteger(
            "id",
            Block.getIdFromBlock(
                ctx.world()
                    .getBlock(x, y, z)));
        block.setInteger(
            "meta",
            ctx.world()
                .getBlockMetadata(x, y, z));
        TileEntity tile = ctx.world()
            .getTileEntity(x, y, z);
        if (tile != null) {
            NBTTagCompound state = new NBTTagCompound();
            tile.writeToNBT(state);
            block.setTag("tile", state);
        }
        into.setTag(name, block);
    }

    private static final class Snapshot {

        private final NBTTagCompound state;
        private final double energy;

        Snapshot(NBTTagCompound state, double energy) {
            this.state = (NBTTagCompound) state.copy();
            this.energy = energy;
        }

        boolean same(Snapshot other) {
            return state.equals(other.state) && Double.compare(energy, other.energy) == 0;
        }
    }

    private static NBTTagCompound binding(TileEntityNetworkQuantumNode node) {
        NBTTagCompound data = new NBTTagCompound();
        data.setBoolean("bound", node.hasAnchor());
        data.setInteger("dim", node.getAnchorDim());
        data.setInteger("x", node.getAnchorX());
        data.setInteger("y", node.getAnchorY());
        data.setInteger("z", node.getAnchorZ());
        data.setString("generation", node.getAnchorGeneration());
        data.setInteger("nativeActor", node.getNativeOwnerID());
        data.setString("placer", node.getPlacerUUID());
        return data;
    }

    private boolean safety(TestContext ctx) {
        return QuantumTerminalAccess.isNetworkSafetyEnabled(ctx.world(), 8, Y, 8);
    }

    private ISecurityGrid security() {
        return gridNode(controller).getGrid()
            .getCache(ISecurityGrid.class);
    }

    private static IGridNode gridNode(TileEntity tile) {
        return ((IGridHost) tile).getGridNode(ForgeDirection.UNKNOWN);
    }

    private static IEnergyGrid energy(TileEntity tile) {
        return gridNode(tile).getGrid()
            .getCache(IEnergyGrid.class);
    }

    private static int actorId(EntityPlayer actor) {
        return AEApi.instance()
            .registries()
            .players()
            .getID(actor);
    }

    private static boolean ready(TileEntity controller, TileEntity station) {
        if (controller == null || station == null || controller.isInvalid() || station.isInvalid()) return false;
        IGridNode a = gridNode(controller), b = gridNode(station);
        if (a == null || b == null || a.getGrid() != b.getGrid()) return false;
        try {
            AENetworkProxy proxy = ((IGridProxyable) controller).getProxy();
            return proxy.isReady() && proxy.getPath()
                .getControllerState() == ControllerState.CONTROLLER_ONLINE
                && proxy.getEnergy()
                    .isNetworkPowered()
                && ((ISecurityGrid) a.getGrid()
                    .getCache(ISecurityGrid.class)).isAvailable();
        } catch (GridAccessException booting) {
            return false;
        }
    }

    private static void power(TileEntity controller) {
        if (controller == null || controller.isInvalid()) return;
        AENetworkProxy proxy = ((IGridProxyable) controller).getProxy();
        if (!proxy.isReady()) return;
        try {
            proxy.getEnergy()
                .injectPower(200, Actionable.MODULATE);
        } catch (GridAccessException booting) { /* Wait for native startup. */ }
    }

    void cleanup(TestContext ctx) {
        TestWorldFixtures.requireTestWorld(ctx.world());
        try {
            if (nodePlaced) remove(ctx, NODE_X, NODE_Z);
        } finally {
            try {
                if (foreignStationPlaced) remove(ctx, FOREIGN_X + 1, FOREIGN_Z);
            } finally {
                try {
                    if (foreignControllerPlaced) remove(ctx, FOREIGN_X, FOREIGN_Z);
                } finally {
                    if (rejectionFloorPlaced) {
                        remove(ctx, REJECT_X, REJECT_Z);
                        ctx.world()
                            .setBlockToAir(REJECT_X, Y - 1, REJECT_Z);
                    }
                }
            }
        }
    }

    private static void remove(TestContext ctx, int x, int z) {
        TileEntity tile = ctx.world()
            .getTileEntity(x, Y, z);
        if (tile instanceof AEBaseTile) ((AEBaseTile) tile).disableDrops();
        ctx.world()
            .setBlockToAir(x, Y, z);
    }

    private static void require(TestContext ctx, boolean condition, String detail) {
        TestWorldFixtures.require(ctx, condition, detail);
    }
}
