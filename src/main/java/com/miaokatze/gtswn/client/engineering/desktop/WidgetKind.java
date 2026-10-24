package com.miaokatze.gtswn.client.engineering.desktop;

/** Data source categories, never cached data or an authority to operate a device. */
public enum WidgetKind {

    MACHINES(4, 2),
    INPUT(3, 2),
    OUTPUT(3, 2),
    WIRELESS(4, 2),
    HISTORY(8, 3),
    RESOURCES(8, 4),
    ATTENTION(4, 3),
    REGION(4, 3),
    DEVICE(4, 3);

    public final int minColumns, minRows;

    WidgetKind(int columns, int rows) {
        minColumns = columns;
        minRows = rows;
    }
}
