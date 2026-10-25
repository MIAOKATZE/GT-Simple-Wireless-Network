package com.miaokatze.gtswn.client.engineering.desktop;

/** Visual forms over an existing data source; a form never adds telemetry or a capacity. */
public enum WidgetPresentation {

    DEFAULT(0, 0),
    KPI(3, 2),
    BAR(4, 2),
    GAUGE(3, 3),
    TREND(6, 3),
    BARS(6, 3),
    COMBO(6, 3),
    STATUS_LIST(3, 2),
    CLOCK_FACE(2, 3);

    public final int minColumns, minRows;

    WidgetPresentation(int columns, int rows) {
        minColumns = columns;
        minRows = rows;
    }
}
