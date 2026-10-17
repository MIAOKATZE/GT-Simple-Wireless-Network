package com.miaokatze.gtswn.crossmod.mm;

import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.quantum.QuantumNodeCablePart;

import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;

/** MM transfers item-backed AE parts; the quantum block owns its virtual cable slot. */
public final class AEPartCopy {

    private AEPartCopy() {}

    public static IPart transferablePart(IPartHost host, ForgeDirection side) {
        IPart part = host.getPart(side);
        return side == ForgeDirection.UNKNOWN && part instanceof QuantumNodeCablePart ? null : part;
    }
}
