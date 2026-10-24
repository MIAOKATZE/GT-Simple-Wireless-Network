package com.miaokatze.gtswn.client.engineering;

import java.util.UUID;

import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

/** An authorized record identity, independent of a target in the currently rendered world. */
final class SelectedMachineRecord {

    final UUID source;
    final int dimension, x, y, z;

    SelectedMachineRecord(UUID source, Entry entry) {
        this.source = source;
        dimension = entry.dim;
        x = entry.x;
        y = entry.y;
        z = entry.z;
    }

    boolean matches(UUID currentSource, Entry entry) {
        return source != null && source.equals(currentSource)
            && entry.dim == dimension
            && entry.x == x
            && entry.y == y
            && entry.z == z;
    }

    boolean locatable(int currentDimension) {
        return currentDimension == dimension;
    }
}
