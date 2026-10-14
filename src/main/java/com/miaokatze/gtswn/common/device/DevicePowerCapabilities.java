package com.miaokatze.gtswn.common.device;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;

/** Electric input/output roles; actual controller observations can add either direction. */
public final class DevicePowerCapabilities {

    public static final byte EU_IN = 1;
    public static final byte EU_OUT = 2;

    private DevicePowerCapabilities() {}

    public static byte capabilityMask(IMetaTileEntity machine) {
        if (machine == null) return 0;
        if (DeviceDefaultRecognitionPolicy.isPassiveFacility(machine)) return (byte) (EU_IN | EU_OUT);
        return defaultMask(DeviceMachineTypes.isGeneratorMachine(machine) ? (byte) 1 : (byte) 0);
    }

    public static byte defaultMask(byte powerType) {
        return powerType == 1 ? EU_OUT : EU_IN;
    }

    static byte observedCapabilities(byte capabilities, long euIn, long euOut, double[] interval) {
        int roles = capabilities & 3;
        if (euIn > 0 || interval[0] > 0) roles |= EU_IN;
        if (euOut > 0 || interval[1] > 0) roles |= EU_OUT;
        return (byte) roles;
    }
}
