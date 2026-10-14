package cofh.api.energy;

import net.minecraftforge.common.util.ForgeDirection;

/** Test-only optional API method shape required by AE2's untransformed RedstoneFlux ancestor. */
public interface IEnergyReceiver {

    int receiveEnergy(ForgeDirection from, int maxReceive, boolean simulate);

    int getEnergyStored(ForgeDirection from);

    int getMaxEnergyStored(ForgeDirection from);

    boolean canConnectEnergy(ForgeDirection from);
}
