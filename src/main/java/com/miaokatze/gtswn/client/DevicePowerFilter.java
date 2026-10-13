package com.miaokatze.gtswn.client;

import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

/** Device list filters use independent channel capabilities, including idle devices. */
public final class DevicePowerFilter {

    public static final int ALL = 0;
    public static final int ELECTRIC = 1;
    public static final int STEAM = 2;
    public static final int GENERATE_EU = 3;
    public static final int CONSUME_EU = 4;
    public static final int GENERATE_STEAM = 5;
    public static final int CONSUME_STEAM = 6;
    public static final int MODE_COUNT = 7;

    private static final int EU_IN = 1;
    private static final int EU_OUT = 2;
    private static final int STEAM_IN = 4;
    private static final int STEAM_OUT = 8;

    private DevicePowerFilter() {}

    /** The server combines idle capabilities and supported observed flows; net power is not a category. */
    public static boolean matches(Entry entry, int mode) {
        return matches(entry.state == 3 ? 0 : entry.capabilityMask, mode);
    }

    public static boolean matches(int capabilityMask, int mode) {
        switch (mode) {
            case ELECTRIC:
                return (capabilityMask & (EU_IN | EU_OUT)) != 0;
            case STEAM:
                return (capabilityMask & (STEAM_IN | STEAM_OUT)) != 0;
            case GENERATE_EU:
                return (capabilityMask & EU_OUT) != 0;
            case CONSUME_EU:
                return (capabilityMask & EU_IN) != 0;
            case GENERATE_STEAM:
                return (capabilityMask & STEAM_OUT) != 0;
            case CONSUME_STEAM:
                return (capabilityMask & STEAM_IN) != 0;
            case ALL:
            default:
                return true;
        }
    }
}
