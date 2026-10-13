package com.miaokatze.gtswn.common.device;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import net.minecraftforge.fluids.FluidStack;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.util.GTModHandler;

/** Steam power capabilities are independent of recipe fluids; all observed steam remains in liters. */
public final class DeviceSteamAdapter {

    public static final byte EU = 0;
    public static final byte STEAM = 1;
    public static final byte MIXED = 2;
    public static final byte EU_IN = 1;
    public static final byte EU_OUT = 2;
    public static final byte STEAM_IN = 4;
    public static final byte STEAM_OUT = 8;

    private static final Set<String> PRODUCERS = new HashSet<>(
        Arrays.asList(
            "MTEBoiler",
            "MTELargeBoiler",
            "MTELargeBoilerBase",
            "MTEThermalBoiler",
            "MTEThermalBoilerLegacy",
            "MTEHeatExchanger",
            "MTEAdvHeatExchanger",
            "MTEExtremeHeatExchanger"));
    private static final Set<String> TURBINES = new HashSet<>(
        Arrays.asList(
            "MTESteamTurbine",
            "MTELargeTurbineSteam",
            "MTELargeTurbineHPSteam",
            "MTELargeTurbineSCSteam",
            "MTEXLTurbineSteam",
            "MTEXLTurbineHPSteam",
            "MTEXLTurbineSCSteam",
            "MTELargeTurbineSteamLegacy",
            "MTELargeTurbineHPSteamLegacy",
            "MTESupercriticalFluidTurbineLegacy",
            "MTELargerTurbineSteamLegacy",
            "MTELargerTurbineHPSteamLegacy",
            "MTELargerTurbineSCSteamLegacy",
            "MTEMegaSteamTurbineArray"));
    private static final Set<String> CASCADING_TURBINES = new HashSet<>(
        Arrays.asList(
            "MTELargeTurbineHPSteam",
            "MTELargeTurbineSCSteam",
            "MTEXLTurbineHPSteam",
            "MTEXLTurbineSCSteam",
            "MTELargeTurbineHPSteamLegacy",
            "MTESupercriticalFluidTurbineLegacy",
            "MTELargerTurbineHPSteamLegacy",
            "MTELargerTurbineSCSteamLegacy"));

    /** One bounded inheritance walk per class; no optional class loading or reflection over machine fields. */
    private static final ClassValue<Byte> STEAM_ROLES = new ClassValue<Byte>() {

        @Override
        protected Byte computeValue(Class<?> type) {
            return classSteamRoles(type);
        }
    };

    private DeviceSteamAdapter() {}

    public static boolean isSteam(FluidStack fluid) {
        if (fluid == null || fluid.getFluid() == null) return false;
        String name = fluid.getFluid()
            .getName();
        return GTModHandler.isAnySteam(fluid) || GTModHandler.isSuperHeatedSteam(fluid)
            || "supercriticalsteam".equals(name)
            || "densesupercriticalsteam".equals(name)
            || "densesteam".equals(name)
            || "densesuperheatedsteam".equals(name);
    }

    static byte classSteamRoles(Class<?> type) {
        int roles = 0;
        for (int depth = 0; type != null && depth < 32; type = type.getSuperclass(), depth++) {
            String name = type.getSimpleName();
            if (PRODUCERS.contains(name)) roles |= STEAM_OUT;
            if (TURBINES.contains(name)) roles |= EU_OUT | STEAM_IN;
            if (CASCADING_TURBINES.contains(name)) roles |= STEAM_OUT;
            if ("MTESteamMultiBlockBase".equals(name)) roles |= STEAM_IN;
        }
        return (byte) roles;
    }

    public static byte capabilityMask(IMetaTileEntity machine) {
        if (machine == null) return 0;
        int roles = STEAM_ROLES.get(machine.getClass());
        if (machine instanceof MetaTileEntity && ((MetaTileEntity) machine).isSteampowered()) roles |= STEAM_IN;
        // Boilers and exchangers reuse EU recipe/display fields without doing electric work.
        if ((roles & (EU_IN | EU_OUT)) == 0 && (roles & (STEAM_IN | STEAM_OUT)) == 0) {
            roles |= DeviceMachineTypes.isGeneratorMachine(machine) ? EU_OUT : EU_IN;
        }
        return (byte) roles;
    }

    public static byte energyKind(IMetaTileEntity machine) {
        return energyKind(capabilityMask(machine));
    }

    public static byte energyKind(byte capabilities) {
        if ((capabilities & (STEAM_IN | STEAM_OUT)) == 0) return EU;
        return (capabilities & (EU_IN | EU_OUT)) == 0 ? STEAM : MIXED;
    }

    /** Capability rather than sampled fluid activity: an EU recipe using steam is not a steam power channel. */
    public static boolean supportsSteamChannel(IMetaTileEntity machine) {
        return (capabilityMask(machine) & (STEAM_IN | STEAM_OUT)) != 0;
    }

    public static boolean isSteamProducer(IMetaTileEntity machine) {
        return (capabilityMask(machine) & STEAM_OUT) != 0;
    }

    public static boolean isSteamConsumer(IMetaTileEntity machine) {
        return (capabilityMask(machine) & STEAM_IN) != 0;
    }

    /** Compatibility for old records/constructors until the next live sample refreshes exact roles. */
    public static byte legacyCapabilityMask(byte energyKind, byte powerType) {
        if (energyKind == STEAM) return powerType == 1 ? STEAM_OUT : STEAM_IN;
        if (energyKind == MIXED) return (byte) (powerType == 1 ? EU_OUT | STEAM_IN : EU_IN | STEAM_OUT);
        return powerType == 1 ? EU_OUT : EU_IN;
    }

    static byte observedCapabilities(byte capabilities, long euIn, long euOut, long steamIn, long steamOut,
        double[] interval) {
        int roles = capabilities;
        if (euIn > 0 || interval[0] > 0) roles |= EU_IN;
        if (euOut > 0 || interval[1] > 0) roles |= EU_OUT;
        if ((capabilities & (STEAM_IN | STEAM_OUT)) != 0) {
            if (steamIn > 0 || interval[2] > 0) roles |= STEAM_IN;
            if (steamOut > 0 || interval[3] > 0) roles |= STEAM_OUT;
        }
        return (byte) roles;
    }
}
