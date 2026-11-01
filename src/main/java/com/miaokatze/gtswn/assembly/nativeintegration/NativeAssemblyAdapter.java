package com.miaokatze.gtswn.assembly.nativeintegration;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.common.tileentities.machines.multi.MTEAssemblyLine;

/** Narrow access to the pinned native assembly line; unknown subclasses are unsupported. */
public final class NativeAssemblyAdapter {

    private static final ThreadLocal<NativeAssemblySession> ACCESS = new ThreadLocal<>();

    private NativeAssemblyAdapter() {}

    public static NativeAssemblySession session(MTEAssemblyLine controller) {
        if (!isSupportedController(controller) || !NativeAssemblyHooks.ready()) return null;
        return ((NativeAssemblyController) controller).orderedAssemblySession();
    }

    public static boolean isSupportedController(Object controller) {
        return controller != null && controller.getClass() == MTEAssemblyLine.class
            && controller instanceof NativeAssemblyController;
    }

    /** Also seals custom adders that bypass the normal GT registration methods. */
    public static boolean permitsNativeAccess(OrderedAssemblyMember member) {
        NativeAssemblySession session = ACCESS.get();
        return session != null && session.containsMember(member);
    }

    static NativeAssemblySession enter(NativeAssemblySession session) {
        NativeAssemblySession previous = ACCESS.get();
        ACCESS.set(session);
        return previous;
    }

    static void leave(NativeAssemblySession previous) {
        if (previous == null) ACCESS.remove();
        else ACCESS.set(previous);
    }

    public static boolean isLive(IMetaTileEntity meta) {
        IGregTechTileEntity tile = meta.getBaseMetaTileEntity();
        return tile != null && meta instanceof MetaTileEntity
            && ((MetaTileEntity) meta).isValid()
            && tile.getMetaTileEntity() == meta
            && tile.getWorld() != null
            && !tile.getWorld().isRemote
            && tile.getWorld()
                .blockExists(tile.getXCoord(), tile.getYCoord(), tile.getZCoord())
            && tile.getWorld()
                .getTileEntity(tile.getXCoord(), tile.getYCoord(), tile.getZCoord()) == tile;
    }
}
