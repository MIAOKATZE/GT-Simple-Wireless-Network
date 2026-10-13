package com.miaokatze.gtswn.common.device;

import net.minecraftforge.fluids.FluidStack;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.util.GTModHandler;

/** Steam volumes remain liters; recipe EU fields are never used to derive steam consumption. */
public final class DeviceSteamAdapter {

    public static final byte EU = 0;
    public static final byte STEAM = 1;
    public static final byte MIXED = 2;

    private DeviceSteamAdapter() {}

    public static boolean isSteam(FluidStack fluid) {
        if (fluid == null) return false;
        String name = fluid.getFluid()
            .getName();
        return GTModHandler.isAnySteam(fluid) || GTModHandler.isSuperHeatedSteam(fluid)
            || "supercriticalsteam".equals(name);
    }

    public static byte energyKind(IMetaTileEntity machine) {
        if (machine instanceof MetaTileEntity && ((MetaTileEntity) machine).isSteampowered()) return STEAM;
        for (Class<?> type = machine.getClass(); type != null; type = type.getSuperclass()) {
            String name = type.getSimpleName();
            if (name.equals("MTESteamMultiBlockBase") || name.equals("MTELargeBoiler")
                || name.equals("MTELargeBoilerBase")
                || name.equals("MTEThermalBoiler")
                || name.equals("MTEThermalBoilerLegacy")
                || name.equals("MTEAdvHeatExchanger")) return STEAM;
            if (name.contains("Turbine") && name.contains("Steam")) return MIXED;
        }
        return EU;
    }
}
