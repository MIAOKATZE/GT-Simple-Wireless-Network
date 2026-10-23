package com.miaokatze.gtswn.client.engineering;

import com.miaokatze.gtswn.client.DevicePowerFilter;
import com.miaokatze.gtswn.common.device.DeviceTerminalRecognitionMode;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

/** Filters only facts carried by DIT: state, sample evidence and snapshot freshness. */
public enum FactoryMachineFilter {

    ALL,
    RUNNING,
    IDLE,
    STOPPED,
    IGNORED,
    UNKNOWN,
    ATTENTION,
    STALE;

    public boolean matches(Entry entry, boolean fresh, int powerMode) {
        if (!DevicePowerFilter.matches(entry, powerMode)) return false;
        switch (this) {
            case ALL:
                return true;
            case IGNORED:
                return ignored(entry);
            case STALE:
                return !fresh;
            case RUNNING:
                return fresh && !ignored(entry) && entry.state == 1;
            case IDLE:
                return fresh && !ignored(entry) && entry.state == 0;
            case STOPPED:
                return fresh && !ignored(entry) && entry.state == 2;
            case UNKNOWN:
                return fresh && !ignored(entry) && entry.evidenceKind == 3;
            case ATTENTION:
                return fresh && !ignored(entry) && (entry.state == 2 || entry.evidenceKind == 3);
            default:
                return false;
        }
    }

    public FactoryMachineFilter next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public String key() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static boolean ignored(Entry entry) {
        return entry.state == 3 || entry.recognitionMode == DeviceTerminalRecognitionMode.IGNORE;
    }

    public static String status(Entry entry, boolean fresh) {
        if (ignored(entry)) return "ignored";
        if (!fresh) return "stale";
        return entry.state == 1 ? "running" : entry.state == 0 ? "idle" : entry.state == 2 ? "stopped" : "unknown";
    }
}
