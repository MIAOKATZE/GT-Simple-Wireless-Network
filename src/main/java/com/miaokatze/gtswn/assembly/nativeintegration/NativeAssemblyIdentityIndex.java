package com.miaokatze.gtswn.assembly.nativeintegration;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import net.minecraft.world.World;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchInput;
import gregtech.api.metatileentity.implementations.MTEHatchInputBus;

/** Loaded-instance identity evidence for this feature only. It never owns or reconstructs inventories. */
final class NativeAssemblyIdentityIndex {

    private static final Map<UUID, List<ControllerClaim>> CONTROLLERS = new WeakHashMap<>();
    private static final Map<UUID, List<MemberClaim>> MEMBERS = new WeakHashMap<>();

    private NativeAssemblyIdentityIndex() {}

    static synchronized boolean verify(NativeAssemblySession session) {
        session.identityConflict("");
        claimController(session);
        for (MTEHatchInputBus bus : session.controller().mInputBusses) {
            if (bus instanceof OrderedAssemblyMember) claimMember(session, (OrderedAssemblyMember) bus, bus);
        }
        for (MTEHatchInput hatch : session.controller().mInputHatches) {
            if (hatch instanceof OrderedAssemblyMember) claimMember(session, (OrderedAssemblyMember) hatch, hatch);
        }
        return session.identityConflict()
            .isEmpty();
    }

    private static void claimController(NativeAssemblySession session) {
        List<ControllerClaim> claims = CONTROLLERS.computeIfAbsent(session.controllerId(), key -> new ArrayList<>());
        boolean present = false;
        for (Iterator<ControllerClaim> it = claims.iterator(); it.hasNext();) {
            ControllerClaim claim = it.next();
            NativeAssemblySession owner = claim.session.get();
            if (owner == null) {
                it.remove();
            } else if (owner == session) {
                present = true;
                if (!claim.position.matches(session.controller()))
                    session.identityConflict("controller_identity_moved");
            } else
                if (!NativeAssemblyAdapter.isLive(owner.controller()) && claim.position.matches(session.controller())) {
                    // A loaded replacement at the same saved controller identity and position is a normal reload.
                    it.remove();
                } else {
                    conflict(owner, session, "duplicate_controller_identity");
                }
        }
        if (!present) claims.add(new ControllerClaim(session));
    }

    private static void claimMember(NativeAssemblySession session, OrderedAssemblyMember member, IMetaTileEntity meta) {
        UUID id = member.orderedMemberId();
        if (id == null || !NativeAssemblyAdapter.isLive(meta)) {
            session.identityConflict("member_identity_unavailable");
            return;
        }
        List<MemberClaim> claims = MEMBERS.computeIfAbsent(id, key -> new ArrayList<>());
        boolean present = false;
        for (Iterator<MemberClaim> it = claims.iterator(); it.hasNext();) {
            MemberClaim claim = it.next();
            NativeAssemblySession owner = claim.session.get();
            IMetaTileEntity oldMember = claim.member.get();
            if (owner == null && oldMember == null) {
                it.remove();
                continue;
            }
            if (owner == session && oldMember == meta) {
                present = true;
                if (!claim.position.matches(meta) || !claim.controllerPosition.matches(session.controller()))
                    session.identityConflict("member_identity_moved");
                continue;
            }
            boolean sameOwner = claim.controllerId.equals(session.controllerId())
                && claim.controllerPosition.matches(session.controller());
            boolean oldOwnerLive = owner != null && NativeAssemblyAdapter.isLive(owner.controller());
            boolean oldMemberLive = oldMember != null && NativeAssemblyAdapter.isLive(oldMember);
            boolean reloadedMember = claim.position.matches(meta) && (oldMember == meta || !oldMemberLive)
                && (owner == session || !oldOwnerLive);
            if (sameOwner && reloadedMember) {
                it.remove();
            } else {
                conflict(owner, session, "duplicate_member_identity_or_owner");
            }
        }
        if (!present) claims.add(new MemberClaim(session, meta));
    }

    private static void conflict(NativeAssemblySession first, NativeAssemblySession second, String reason) {
        if (first != null) first.identityConflict(reason);
        second.identityConflict(reason);
    }

    /** Explicit removal releases claims. An unload retains evidence until a same-position reload replaces it. */
    static synchronized void removed(NativeAssemblySession session) {
        for (List<ControllerClaim> claims : CONTROLLERS.values())
            claims.removeIf(claim -> claim.session.get() == null || claim.session.get() == session);
        for (List<MemberClaim> claims : MEMBERS.values()) claims.removeIf(
            claim -> claim.session.get() == session || claim.session.get() == null && claim.member.get() == null);
    }

    private static final class ControllerClaim {

        final WeakReference<NativeAssemblySession> session;
        final Position position;

        ControllerClaim(NativeAssemblySession owner) {
            session = new WeakReference<>(owner);
            position = new Position(owner.controller());
        }
    }

    private static final class MemberClaim {

        final WeakReference<NativeAssemblySession> session;
        final WeakReference<IMetaTileEntity> member;
        final UUID controllerId;
        final Position controllerPosition;
        final Position position;

        MemberClaim(NativeAssemblySession owner, IMetaTileEntity meta) {
            session = new WeakReference<>(owner);
            member = new WeakReference<>(meta);
            controllerId = owner.controllerId();
            controllerPosition = new Position(owner.controller());
            position = new Position(meta);
        }
    }

    private static final class Position {

        final WeakReference<World> world;
        final int x, y, z;

        Position(IMetaTileEntity meta) {
            IGregTechTileEntity tile = meta.getBaseMetaTileEntity();
            world = new WeakReference<>(tile.getWorld());
            x = tile.getXCoord();
            y = tile.getYCoord();
            z = tile.getZCoord();
        }

        boolean matches(IMetaTileEntity meta) {
            IGregTechTileEntity tile = meta.getBaseMetaTileEntity();
            return tile != null && world.get() == tile.getWorld()
                && x == tile.getXCoord()
                && y == tile.getYCoord()
                && z == tile.getZCoord();
        }
    }
}
