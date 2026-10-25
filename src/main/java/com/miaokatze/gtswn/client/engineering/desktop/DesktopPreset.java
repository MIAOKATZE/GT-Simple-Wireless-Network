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
                add(layout, "wireless", WidgetKind.WIRELESS, WidgetPresentation.KPI, "stored", 0, 0, 8, 2);
                add(layout, "netRate", WidgetKind.WIRELESS, WidgetPresentation.KPI, "netRate", 8, 0, 8, 2);
                add(layout, "history", WidgetKind.HISTORY, WidgetPresentation.COMBO, "", 0, 2, 10, 4);
                add(layout, "deviceInput", WidgetKind.DEVICE, WidgetPresentation.KPI, "instantInput", 10, 2, 6, 2);
                add(layout, "deviceOutput", WidgetKind.DEVICE, WidgetPresentation.KPI, "instantOutput", 10, 4, 6, 2);
                break;
            case PRODUCTION:
                machineCounts(layout);
                add(layout, "machines", WidgetKind.MACHINES, WidgetPresentation.GAUGE, "running", 10, 2, 6, 6);
                add(layout, "device", WidgetKind.DEVICE, WidgetPresentation.DEFAULT, "", 0, 2, 5, 3);
                add(layout, "deviceInput", WidgetKind.DEVICE, WidgetPresentation.KPI, "instantInput", 5, 2, 5, 3);
                add(layout, "deviceOutput", WidgetKind.DEVICE, WidgetPresentation.BAR, "averageOutput", 0, 5, 5, 3);
                add(layout, "attention", WidgetKind.ATTENTION, WidgetPresentation.STATUS_LIST, "", 5, 5, 5, 3);
                break;
            case LOGISTICS:
                add(layout, "resources", WidgetKind.RESOURCES, WidgetPresentation.STATUS_LIST, "", 0, 0, 10, 4);
                add(layout, "wireless", WidgetKind.WIRELESS, WidgetPresentation.KPI, "stored", 10, 0, 6, 2);
                add(layout, "netRate", WidgetKind.WIRELESS, WidgetPresentation.KPI, "netRate", 10, 2, 6, 2);
                add(layout, "history", WidgetKind.HISTORY, WidgetPresentation.BARS, "", 0, 4, 10, 3);
                add(layout, "attention", WidgetKind.ATTENTION, WidgetPresentation.STATUS_LIST, "", 10, 4, 6, 5);
                resourceStrip(layout, 7);
                layout.replace(
                    layout.find("resources")
                        .withAutoResource(true));
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
        informationStrip(layout, this == PRODUCTION || this == DIAGNOSTICS);
        return layout;
    }

    /** New profiles and explicit preset application only; persisted arrangements are left intact. */
    public static DesktopLayout overviewLayout() {
        DesktopLayout layout = new DesktopLayout();
        add(layout, "machines", WidgetKind.MACHINES, WidgetPresentation.KPI, "count", 0, 0, 5, 2);
        add(layout, "wireless", WidgetKind.WIRELESS, WidgetPresentation.GAUGE, "stored", 10, 0, 6, 5);
        add(layout, "netRate", WidgetKind.WIRELESS, WidgetPresentation.KPI, "netRate", 5, 0, 5, 2);
        add(layout, "history", WidgetKind.HISTORY, WidgetPresentation.COMBO, "", 0, 2, 10, 3);
        resourceStrip(layout, 5);
        add(layout, "status", WidgetKind.MACHINES, WidgetPresentation.STATUS_LIST, "running", 10, 5, 6, 2);
        layout.replace(
            layout.find("status")
                .styled(WidgetEffects.DEFAULT.withStyle(WidgetEffects.Style.SIGNAL)));
        informationStrip(layout, true);
        return layout;
    }

    /** Appended only when creating a fresh preset; persisted layouts have their own migration policy. */
    private static void informationStrip(DesktopLayout layout, boolean player) {
        int row = layout.bounds().bottom / DesktopLayout.CELL;
        WidgetKind[] kinds = { WidgetKind.CLOCK, WidgetKind.CLOCK, WidgetKind.SESSION, WidgetKind.TEAM,
            player ? WidgetKind.PLAYER : WidgetKind.WORLD };
        String[] ids = { "clock", "gameClock", "session", "team", player ? "player" : "world" };
        int column = 0;
        for (int i = 0; i < kinds.length; i++) {
            int width = 2;
            add(layout, ids[i], kinds[i], WidgetPresentation.DEFAULT, "", column, row, width, 2);
            if (i < 2) layout.replace(
                layout.find(ids[i])
                    .configured(
                        "",
                        (i == 0 ? DesktopInformationTemplate.LOCAL_CLOCK : DesktopInformationTemplate.GAME_CLOCK)
                            .fields(),
                        1));
            column += width;
        }
    }

    /** Five compact independent monitors, initialized once from authorized mounted inventory receipts. */
    private static void resourceStrip(DesktopLayout layout, int row) {
        for (int index = 0; index < 5; index++) {
            String id = "resource" + (index + 1);
            add(layout, id, WidgetKind.RESOURCES, WidgetPresentation.STATUS_LIST, "", index * 2, row, 2, 2);
            layout.replace(
                layout.find(id)
                    .withSingleResource(true)
                    .withAutoResource(true));
        }
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
