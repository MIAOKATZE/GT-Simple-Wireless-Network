package com.miaokatze.gtswn.client;

import com.miaokatze.gtswn.common.device.DeviceTerminalRecognitionMode;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

/** Device list filters use observed electrical input and output, independent of net power. */
public final class DevicePowerFilter {

    public static final int ALL = 0;
    public static final int GENERATE_EU = 1;
    public static final int CONSUME_EU = 2;
    public static final int MODE_COUNT = 3;

    private DevicePowerFilter() {}

    public static boolean matches(Entry entry, int mode) {
        boolean ignored = entry.state == 3 || entry.recognitionMode == DeviceTerminalRecognitionMode.IGNORE;
        switch (mode) {
            case GENERATE_EU:
                return !ignored && (entry.instOut > 0L || entry.avgOut > 0D);
            case CONSUME_EU:
                return !ignored && (entry.instIn > 0L || entry.avgIn > 0D);
            case ALL:
            default:
                return true;
        }
    }
}
