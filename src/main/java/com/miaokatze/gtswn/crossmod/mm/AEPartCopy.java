// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.crossmod.mm;

import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.quantum.QuantumNodeCablePart;

import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;

/** MM transfers item-backed AE parts; the quantum block owns its virtual cable slot. */
public final class AEPartCopy {

    private AEPartCopy() {}

    public static IPart transferablePart(IPartHost host, ForgeDirection side) {
        return filterTransferablePart(host.getPart(side), side);
    }

    public static IPart filterTransferablePart(IPart part, ForgeDirection side) {
        return side == ForgeDirection.UNKNOWN && part instanceof QuantumNodeCablePart ? null : part;
    }
}
