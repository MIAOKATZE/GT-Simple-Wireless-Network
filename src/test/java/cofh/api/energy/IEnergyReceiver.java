// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package cofh.api.energy;

import net.minecraftforge.common.util.ForgeDirection;

/** Test-only optional API method shape required by AE2's untransformed RedstoneFlux ancestor. */
public interface IEnergyReceiver {

    int receiveEnergy(ForgeDirection from, int maxReceive, boolean simulate);

    int getEnergyStored(ForgeDirection from);

    int getMaxEnergyStored(ForgeDirection from);

    boolean canConnectEnergy(ForgeDirection from);
}
