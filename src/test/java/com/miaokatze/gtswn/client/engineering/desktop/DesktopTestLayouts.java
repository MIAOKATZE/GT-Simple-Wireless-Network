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
}
