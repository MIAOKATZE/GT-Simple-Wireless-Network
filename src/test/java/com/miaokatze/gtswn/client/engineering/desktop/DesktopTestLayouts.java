package com.miaokatze.gtswn.client.engineering.desktop;

final class DesktopTestLayouts {

    static DesktopLayout dragFixture() {
        DesktopLayout layout = new DesktopLayout();
        layout.add(new WidgetSpec("machines", WidgetKind.MACHINES, 0, 0, 4, 3));
        layout.add(new WidgetSpec("other", WidgetKind.MACHINES, 4, 0, 4, 3));
        return layout;
    }

    static DesktopLayout legacyDefault(boolean tall) {
        String[] ids = { "machines", "input", "output", "wireless", "history", "region", "resources", "attention" };
        WidgetKind[] kinds = { WidgetKind.MACHINES, WidgetKind.INPUT, WidgetKind.OUTPUT, WidgetKind.WIRELESS,
            WidgetKind.HISTORY, WidgetKind.REGION, WidgetKind.RESOURCES, WidgetKind.ATTENTION };
        int[][] geometry = tall
            ? new int[][] { { 0, 0, 4, 2 }, { 4, 0, 4, 2 }, { 8, 0, 4, 2 }, { 12, 0, 4, 2 }, { 0, 2, 10, 5 },
                { 10, 2, 6, 5 }, { 0, 7, 10, 7 }, { 10, 7, 6, 7 } }
            : new int[][] { { 0, 0, 4, 3 }, { 0, 3, 4, 2 }, { 4, 3, 4, 2 }, { 4, 0, 4, 3 }, { 8, 0, 8, 4 },
                { 0, 5, 4, 3 }, { 8, 4, 8, 4 }, { 4, 5, 4, 3 } };
        DesktopLayout layout = new DesktopLayout();
        for (int index = 0; index < ids.length; index++) {
            int[] bounds = geometry[index];
            if (!layout.add(new WidgetSpec(ids[index], kinds[index], bounds[0], bounds[1], bounds[2], bounds[3])))
                throw new AssertionError(ids[index]);
        }
        return layout;
    }

    static DesktopLayout v217Overview() {
        String[] ids = { "machines", "wireless", "netRate", "region", "history", "resources", "attention", "device" };
        WidgetKind[] kinds = { WidgetKind.MACHINES, WidgetKind.WIRELESS, WidgetKind.WIRELESS, WidgetKind.REGION,
            WidgetKind.HISTORY, WidgetKind.RESOURCES, WidgetKind.ATTENTION, WidgetKind.DEVICE };
        WidgetPresentation[] forms = { WidgetPresentation.KPI, WidgetPresentation.KPI, WidgetPresentation.KPI,
            WidgetPresentation.DEFAULT, WidgetPresentation.TREND, WidgetPresentation.STATUS_LIST,
            WidgetPresentation.STATUS_LIST, WidgetPresentation.DEFAULT };
        String[] metrics = { "count", "stored", "netRate", "", "", "", "", "" };
        int[][] geometry = { { 8, 0, 4, 2 }, { 0, 0, 4, 2 }, { 4, 0, 4, 2 }, { 12, 0, 4, 2 }, { 0, 2, 10, 3 },
            { 0, 5, 10, 3 }, { 10, 2, 6, 3 }, { 10, 5, 6, 3 } };
        DesktopLayout layout = new DesktopLayout();
        for (int i = 0; i < ids.length; i++) {
            int[] b = geometry[i];
            if (!layout
                .add(new WidgetSpec(ids[i], kinds[i], b[0], b[1], b[2], b[3]).presented(forms[i], metrics[i], null)))
                throw new AssertionError(ids[i]);
        }
        return layout;
    }

    static DesktopLayout v218Overview() {
        DesktopLayout layout = new DesktopLayout();
        layout.add(
            new WidgetSpec("machines", WidgetKind.MACHINES, 0, 0, 5, 2)
                .presented(WidgetPresentation.KPI, "count", null));
        layout.add(
            new WidgetSpec("wireless", WidgetKind.WIRELESS, 10, 0, 6, 5)
                .presented(WidgetPresentation.GAUGE, "stored", null));
        layout.add(
            new WidgetSpec("netRate", WidgetKind.WIRELESS, 5, 0, 5, 2)
                .presented(WidgetPresentation.KPI, "netRate", null));
        layout.add(
            new WidgetSpec("history", WidgetKind.HISTORY, 0, 2, 10, 3).presented(WidgetPresentation.COMBO, "", null));
        layout.add(
            new WidgetSpec("resources", WidgetKind.RESOURCES, 0, 5, 10, 3)
                .presented(WidgetPresentation.STATUS_LIST, "", null));
        layout.add(new WidgetSpec("device", WidgetKind.DEVICE, 10, 5, 6, 3));
        return layout;
    }

    /** Frozen shipped geometry; do not derive this fixture from the evolving presets. */
    static DesktopLayout v219Overview() {
        DesktopLayout layout = v218Overview();
        layout.replace(
            layout.find("resources")
                .withAutoResource(true));
        for (int index = 0; index < 4; index++) {
            if (!layout.add(
                new WidgetSpec("resource" + (index + 1), WidgetKind.RESOURCES, index * 4, 8, 4, 2)
                    .presented(WidgetPresentation.STATUS_LIST, "", null)
                    .withSingleResource(true)
                    .withAutoResource(true)))
                throw new AssertionError(index);
        }
        return layout;
    }
}
