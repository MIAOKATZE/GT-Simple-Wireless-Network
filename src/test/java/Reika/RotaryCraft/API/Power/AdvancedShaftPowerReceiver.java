package Reika.RotaryCraft.API.Power;

import net.minecraftforge.common.util.ForgeDirection;

/** Test-only optional API method shape required by AE2's untransformed RotaryCraft ancestor. */
public interface AdvancedShaftPowerReceiver {

    boolean addPower(int torque, int omega, long power, ForgeDirection side);

    int getOmega();

    int getTorque();

    long getPower();

    String getName();

    int getIORenderAlpha();

    void setIORenderAlpha(int alpha);

    boolean canReadFrom(ForgeDirection side);

    boolean isReceiving();

    int getMinTorque(int available);
}
