package com.miaokatze.gtswn.client.engineering.desktop;

/** Independent compact arrangements; applying a preset is an explicit editor action. */
public enum DesktopPreset {

    OVERVIEW,
    POWER,
    PRODUCTION,
    LOGISTICS,
    DIAGNOSTICS,
    REGIONS;

    public DesktopLayout createLayout() {
        if (this == OVERVIEW) return overviewLayout();
        DesktopLayout layout = new DesktopLayout();
        switch (this) {
            case POWER:
                add(layout, "wireless", WidgetKind.WIRELESS, WidgetPresentation.KPI, "stored", 0, 0, 6, 2);
                add(layout, "netRate", WidgetKind.WIRELESS, WidgetPresentation.KPI, "netRate", 6, 0, 6, 2);
                add(layout, "region", WidgetKind.REGION, WidgetPresentation.DEFAULT, "", 12, 0, 4, 2);
                add(layout, "history", WidgetKind.HISTORY, WidgetPresentation.TREND, "", 0, 2, 10, 4);
                add(layout, "attention", WidgetKind.ATTENTION, WidgetPresentation.STATUS_LIST, "", 10, 2, 6, 4);
                break;
            case PRODUCTION:
                machineCounts(layout);
                add(layout, "machines", WidgetKind.MACHINES, WidgetPresentation.STATUS_LIST, "", 0, 2, 8, 3);
                add(layout, "attention", WidgetKind.ATTENTION, WidgetPresentation.STATUS_LIST, "", 8, 2, 8, 3);
                add(layout, "resources", WidgetKind.RESOURCES, WidgetPresentation.STATUS_LIST, "", 0, 5, 10, 3);
                add(layout, "netRate", WidgetKind.WIRELESS, WidgetPresentation.KPI, "netRate", 10, 5, 6, 3);
                break;
            case LOGISTICS:
                add(layout, "resources", WidgetKind.RESOURCES, WidgetPresentation.STATUS_LIST, "", 0, 0, 10, 4);
                add(layout, "wireless", WidgetKind.WIRELESS, WidgetPresentation.KPI, "stored", 10, 0, 6, 2);
                add(layout, "netRate", WidgetKind.WIRELESS, WidgetPresentation.KPI, "netRate", 10, 2, 6, 2);
                add(layout, "history", WidgetKind.HISTORY, WidgetPresentation.TREND, "", 0, 4, 10, 3);
                add(layout, "attention", WidgetKind.ATTENTION, WidgetPresentation.STATUS_LIST, "", 10, 4, 6, 3);
                break;
            case DIAGNOSTICS:
                machineCounts(layout);
                add(layout, "attention", WidgetKind.ATTENTION, WidgetPresentation.STATUS_LIST, "", 0, 2, 10, 4);
                add(layout, "region", WidgetKind.REGION, WidgetPresentation.DEFAULT, "", 10, 2, 6, 2);
                add(layout, "unknown", WidgetKind.MACHINES, WidgetPresentation.KPI, "unknown", 10, 4, 6, 2);
                break;
            case REGIONS:
                add(layout, "monitored", WidgetKind.REGION, WidgetPresentation.KPI, "monitored", 0, 0, 4, 2);
                add(layout, "unknown", WidgetKind.REGION, WidgetPresentation.KPI, "unknown", 4, 0, 4, 2);
                add(layout, "loaded", WidgetKind.REGION, WidgetPresentation.KPI, "loadedChunks", 8, 0, 4, 2);
                add(layout, "chunks", WidgetKind.REGION, WidgetPresentation.KPI, "totalChunks", 12, 0, 4, 2);
                add(layout, "region", WidgetKind.REGION, WidgetPresentation.STATUS_LIST, "", 0, 2, 10, 3);
                add(layout, "attention", WidgetKind.ATTENTION, WidgetPresentation.STATUS_LIST, "", 10, 2, 6, 3);
                break;
            default:
                throw new IllegalStateException(name());
        }
        return layout;
    }

    /** New profiles and explicit preset application only; persisted arrangements are left intact. */
    public static DesktopLayout overviewLayout() {
        DesktopLayout layout = new DesktopLayout();
        add(layout, "machines", WidgetKind.MACHINES, WidgetPresentation.KPI, "count", 8, 0, 4, 2);
        add(layout, "wireless", WidgetKind.WIRELESS, WidgetPresentation.KPI, "stored", 0, 0, 4, 2);
        add(layout, "netRate", WidgetKind.WIRELESS, WidgetPresentation.KPI, "netRate", 4, 0, 4, 2);
        add(layout, "region", WidgetKind.REGION, WidgetPresentation.DEFAULT, "", 12, 0, 4, 2);
        add(layout, "history", WidgetKind.HISTORY, WidgetPresentation.TREND, "", 0, 2, 10, 3);
        add(layout, "resources", WidgetKind.RESOURCES, WidgetPresentation.STATUS_LIST, "", 0, 5, 10, 3);
        add(layout, "attention", WidgetKind.ATTENTION, WidgetPresentation.STATUS_LIST, "", 10, 2, 6, 3);
        add(layout, "device", WidgetKind.DEVICE, WidgetPresentation.DEFAULT, "", 10, 5, 6, 3);
        return layout;
    }

    private static void machineCounts(DesktopLayout layout) {
        String[] metrics = { "count", "running", "idle", "stopped" };
        for (int i = 0; i < metrics.length; i++)
            add(layout, metrics[i], WidgetKind.MACHINES, WidgetPresentation.KPI, metrics[i], i * 4, 0, 4, 2);
    }

    private static void add(DesktopLayout layout, String id, WidgetKind kind, WidgetPresentation presentation,
        String metric, int column, int row, int columns, int rows) {
        WidgetSpec widget = new WidgetSpec(id, kind, column, row, columns, rows).presented(presentation, metric, null);
        if (!layout.add(widget)) throw new IllegalStateException("Invalid preset widget: " + id);
    }
}
