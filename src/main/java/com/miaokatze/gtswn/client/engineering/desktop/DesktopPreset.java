package com.miaokatze.gtswn.client.engineering.desktop;

/** Candidate arrangements only. Applying one is a separate, explicitly confirmed editor action. */
public enum DesktopPreset {

    OVERVIEW,
    POWER,
    PRODUCTION,
    LOGISTICS,
    DIAGNOSTICS,
    REGIONS;

    /** Always creates an independent layout; never edits a profile or invents a mounted device. */
    public DesktopLayout createLayout() {
        if (this == OVERVIEW) return DesktopLayout.defaultLayout();
        DesktopLayout layout = new DesktopLayout();
        switch (this) {
            case POWER:
                add(layout, "input", WidgetKind.INPUT, WidgetPresentation.BAR, "input", 0, 0, 5, 3);
                add(layout, "output", WidgetKind.OUTPUT, WidgetPresentation.BAR, "output", 5, 0, 5, 3);
                add(layout, "wireless", WidgetKind.WIRELESS, WidgetPresentation.GAUGE, "stored", 10, 0, 6, 3);
                add(layout, "history", WidgetKind.HISTORY, WidgetPresentation.TREND, "", 0, 3, 10, 8);
                add(layout, "region", WidgetKind.REGION, WidgetPresentation.STATUS_LIST, "", 10, 3, 6, 5);
                add(layout, "attention", WidgetKind.ATTENTION, WidgetPresentation.STATUS_LIST, "", 10, 8, 6, 6);
                add(layout, "machines", WidgetKind.MACHINES, WidgetPresentation.KPI, "count", 0, 11, 10, 3);
                break;
            case PRODUCTION:
                machineCounts(layout);
                add(layout, "machines", WidgetKind.MACHINES, WidgetPresentation.STATUS_LIST, "", 0, 2, 8, 6);
                add(layout, "attention", WidgetKind.ATTENTION, WidgetPresentation.STATUS_LIST, "", 8, 2, 8, 6);
                add(layout, "input", WidgetKind.INPUT, WidgetPresentation.KPI, "input", 0, 8, 8, 3);
                add(layout, "output", WidgetKind.OUTPUT, WidgetPresentation.KPI, "output", 8, 8, 8, 3);
                add(layout, "resources", WidgetKind.RESOURCES, WidgetPresentation.STATUS_LIST, "", 0, 11, 16, 5);
                break;
            case LOGISTICS:
                add(layout, "resources", WidgetKind.RESOURCES, WidgetPresentation.STATUS_LIST, "", 0, 0, 10, 9);
                add(layout, "wireless", WidgetKind.WIRELESS, WidgetPresentation.KPI, "stored", 10, 0, 6, 3);
                add(layout, "attention", WidgetKind.ATTENTION, WidgetPresentation.STATUS_LIST, "", 10, 3, 6, 6);
                add(layout, "history", WidgetKind.HISTORY, WidgetPresentation.TREND, "", 0, 9, 16, 7);
                break;
            case DIAGNOSTICS:
                machineCounts(layout);
                add(layout, "attention", WidgetKind.ATTENTION, WidgetPresentation.STATUS_LIST, "", 0, 2, 10, 9);
                add(layout, "region", WidgetKind.REGION, WidgetPresentation.STATUS_LIST, "", 10, 2, 6, 6);
                add(layout, "unknown", WidgetKind.MACHINES, WidgetPresentation.KPI, "unknown", 10, 8, 6, 3);
                add(layout, "input", WidgetKind.INPUT, WidgetPresentation.STATUS_LIST, "", 0, 11, 8, 4);
                add(layout, "output", WidgetKind.OUTPUT, WidgetPresentation.STATUS_LIST, "", 8, 11, 8, 4);
                break;
            case REGIONS:
                add(layout, "monitored", WidgetKind.REGION, WidgetPresentation.KPI, "monitored", 0, 0, 4, 2);
                add(layout, "unknown", WidgetKind.REGION, WidgetPresentation.KPI, "unknown", 4, 0, 4, 2);
                add(layout, "loaded", WidgetKind.REGION, WidgetPresentation.KPI, "loadedChunks", 8, 0, 4, 2);
                add(layout, "chunks", WidgetKind.REGION, WidgetPresentation.KPI, "totalChunks", 12, 0, 4, 2);
                add(layout, "region", WidgetKind.REGION, WidgetPresentation.STATUS_LIST, "", 0, 2, 10, 7);
                add(layout, "attention", WidgetKind.ATTENTION, WidgetPresentation.STATUS_LIST, "", 10, 2, 6, 7);
                add(layout, "input", WidgetKind.INPUT, WidgetPresentation.KPI, "input", 0, 9, 8, 3);
                add(layout, "output", WidgetKind.OUTPUT, WidgetPresentation.KPI, "output", 8, 9, 8, 3);
                break;
            default:
                throw new IllegalStateException(name());
        }
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
