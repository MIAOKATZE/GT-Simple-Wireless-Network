package com.miaokatze.gtswn.client.engineering.desktop;

/** Data source categories, never cached data or an authority to operate a device. */
public enum WidgetKind {

    MACHINES(4, 2),
    INPUT(3, 2),
    OUTPUT(3, 2),
    WIRELESS(4, 2),
    HISTORY(8, 3),
    RESOURCES(6, 3),
    ATTENTION(4, 3),
    REGION(4, 2),
    DEVICE(3, 3),
    CLOCK(3, 2),
    SESSION(3, 2),
    TEAM(3, 2),
    PLAYER(3, 2),
    WORLD(3, 2);

    public final int minColumns, minRows;

    WidgetKind(int columns, int rows) {
        minColumns = columns;
        minRows = rows;
    }

    public boolean isInformation() {
        return this == CLOCK || this == SESSION || this == TEAM || this == PLAYER || this == WORLD;
    }

    public boolean supportsDeviceBinding() {
        return this == DEVICE || this == INPUT || this == OUTPUT;
    }

    public boolean supportsScreenBinding() {
        return this == WIRELESS || this == HISTORY || this == RESOURCES;
    }
}
