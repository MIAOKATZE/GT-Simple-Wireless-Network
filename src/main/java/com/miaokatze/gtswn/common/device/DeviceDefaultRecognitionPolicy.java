package com.miaokatze.gtswn.common.device;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;

/** Verified storage and electric distribution families; processing machines are never inferred from their names. */
public final class DeviceDefaultRecognitionPolicy {

    private static final Set<String> FACILITIES = new HashSet<>(
        Arrays.asList(
            "MTEBasicBatteryBuffer",
            "MTEEnergyBuffer",
            "MTETransformer",
            "MTEBasicHull",
            "MTEActiveTransformer",
            "MTELapotronicSuperCapacitor",
            "MTEPowerSubStation",
            "MTETeslaTower",
            "MTEWirelessCharger"));
    private static final ClassValue<Boolean> PASSIVE = new ClassValue<Boolean>() {

        @Override
        protected Boolean computeValue(Class<?> type) {
            return isPassiveClass(type);
        }
    };

    private DeviceDefaultRecognitionPolicy() {}

    static boolean isPassiveClass(Class<?> type) {
        boolean facility = false;
        for (int depth = 0; type != null && depth < 32; type = type.getSuperclass(), depth++) {
            String name = type.getSimpleName();
            // This hull branch includes steam casings and item/fluid pipelines, with no electric net I/O.
            if ("MTEBasicHullNonElectric".equals(name)) return false;
            if (FACILITIES.contains(name)) facility = true;
        }
        return facility;
    }

    public static boolean isPassiveFacility(IMetaTileEntity machine) {
        return machine != null && PASSIVE.get(machine.getClass());
    }

    public static byte defaultMode(IMetaTileEntity machine) {
        return isPassiveFacility(machine) ? DeviceTerminalRecognitionMode.IGNORE : DeviceTerminalRecognitionMode.AUTO;
    }

    public static IMetaTileEntity prototype(int metaTileId) {
        return metaTileId >= 0 && metaTileId < GregTechAPI.METATILEENTITIES.length
            ? GregTechAPI.METATILEENTITIES[metaTileId]
            : null;
    }
}
