package mekanism.api.energy;

import net.minecraftforge.common.util.ForgeDirection;

/** Test-only optional API method shape required by AE2's untransformed MekJoules ancestor. */
public interface IStrictEnergyAcceptor {

    double getEnergy();

    void setEnergy(double energy);

    double getMaxEnergy();

    double transferEnergyToAcceptor(ForgeDirection side, double amount);

    boolean canReceiveEnergy(ForgeDirection side);
}
