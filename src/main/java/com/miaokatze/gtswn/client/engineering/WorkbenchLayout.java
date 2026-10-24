package com.miaokatze.gtswn.client.engineering;

/** One geometry contract for world HUD drawing, hit testing, hover and Core navigation exclusion. */
public final class WorkbenchLayout {

    static final int DEVICE_ROWS_TOP = 131, NODE_ROWS_TOP = 49, RAIL_ROW_HEIGHT = 23, RAIL_BOTTOM = 8;
    private static final int MIN_RAIL_HEIGHT = DEVICE_ROWS_TOP + RAIL_ROW_HEIGHT + RAIL_BOTTOM;

    static int railHeight(boolean nodes, int count) {
        return Math.max(
            MIN_RAIL_HEIGHT,
            (nodes ? NODE_ROWS_TOP : DEVICE_ROWS_TOP) + Math.max(1, Math.min(8, count)) * RAIL_ROW_HEIGHT
                + RAIL_BOTTOM);
    }

    public final Rect[] terminals = new Rect[8];
    public final Rect[] modes = new Rect[5];
    public final Rect[] tools = new Rect[5];
    public final Rect[] tasks = new Rect[4];
    public final Rect sources;
    public final Rect sidebar, rail, power, dashboard, alerts, shortcuts, cameraHints, inspector;
    public final Rect worldViewport;

    public WorkbenchLayout(int width, int height, boolean sidebarOpen, boolean railOpen, boolean powerOpen,
        boolean dashboardOpen) {
        this(width, height, sidebarOpen, railOpen, powerOpen, dashboardOpen, height - 180, 108);
    }

    public WorkbenchLayout(int width, int height, boolean sidebarOpen, boolean railOpen, boolean powerOpen,
        boolean dashboardOpen, int railHeight, int powerHeight) {
        this(width, height, sidebarOpen, railOpen, powerOpen, dashboardOpen, railHeight, powerHeight, false);
    }

    public WorkbenchLayout(int width, int height, boolean sidebarOpen, boolean railOpen, boolean powerOpen,
        boolean dashboardOpen, int railHeight, int powerHeight, boolean alertsOpen) {
        this(
            width,
            height,
            sidebarOpen,
            railOpen,
            powerOpen,
            dashboardOpen,
            railHeight,
            powerHeight,
            alertsOpen,
            false);
    }

    public WorkbenchLayout(int width, int height, boolean sidebarOpen, boolean railOpen, boolean powerOpen,
        boolean dashboardOpen, int railHeight, int powerHeight, boolean alertsOpen, boolean taskNavigation) {
        this(
            width,
            height,
            sidebarOpen,
            railOpen,
            powerOpen,
            dashboardOpen,
            railHeight,
            powerHeight,
            alertsOpen,
            taskNavigation,
            false);
    }

    public WorkbenchLayout(int width, int height, boolean sidebarOpen, boolean railOpen, boolean powerOpen,
        boolean dashboardOpen, int railHeight, int powerHeight, boolean alertsOpen, boolean taskNavigation,
        boolean objectsPage) {
        this(
            width,
            height,
            sidebarOpen,
            railOpen,
            powerOpen,
            dashboardOpen,
            railHeight,
            powerHeight,
            alertsOpen,
            taskNavigation,
            objectsPage,
            true);
    }

    public WorkbenchLayout(int width, int height, boolean sidebarOpen, boolean railOpen, boolean powerOpen,
        boolean dashboardOpen, int railHeight, int powerHeight, boolean alertsOpen, boolean taskNavigation,
        boolean objectsPage, boolean inspectorOpen) {
        // Floating controls own only their painted bounds; the space between them remains world input.
        cameraHints = new Rect(0, 0, 0, 0);
        shortcuts = dashboardOpen ? new Rect(0, 0, 0, 0) : new Rect(width - 64, 9, 56, 24);
        int terminalStep = Math.min(33, Math.max(28, (height - 48) / 8));
        for (int i = 0; i < terminals.length; i++) terminals[i] = new Rect(8, 42 + i * terminalStep, 28, 28);
        modes[0] = new Rect(8, 9, 92, 24);
        for (int i = 1; i < modes.length; i++) modes[i] = new Rect(104 + (i - 1) * 31, 9, 27, 24);
        for (int i = 0; i < tools.length; i++) tools[i] = new Rect(width - 34, 56 + i * 33, 26, 27);
        sidebar = sidebarOpen && !dashboardOpen ? new Rect(43, 42, 214, Math.min(336, Math.max(0, height - 50))) : null;
        boolean splitObjects = objectsPage && !sidebarOpen && !dashboardOpen && railOpen;
        int contentHeight = Math.max(0, height - 50);
        // An explicitly opened list must retain a selectable row. Secondary power content yields on short canvases.
        boolean showPower = !dashboardOpen && powerOpen
            && !splitObjects
            && (!railOpen || contentHeight - powerHeight - 8 >= MIN_RAIL_HEIGHT);
        int railAvailable = Math.max(0, contentHeight - (showPower ? powerHeight + 8 : 0));
        int objectWidth = width >= 600 ? (width - 95) * 3 / 5 : width - 95;
        rail = splitObjects ? new Rect(43, 42, objectWidth, height - 50)
            : !dashboardOpen && railOpen
                ? new Rect(width - 252, 42, 210, Math.min(railAvailable, Math.max(MIN_RAIL_HEIGHT, railHeight)))
                : null;
        inspector = !splitObjects ? null
            : width >= 600 ? new Rect(51 + objectWidth, 42, width - objectWidth - 93, height - 50)
                : inspectorOpen ? new Rect(rail.x, rail.y, rail.w, rail.h) : null;
        power = showPower ? new Rect(width - 252, height - powerHeight - 8, 210, powerHeight) : null;
        dashboard = dashboardOpen ? new Rect(0, 0, width, height) : null;
        int alertLeft = sidebarOpen ? 264 : 43;
        int alertRight = railOpen || powerOpen ? width - 260 : width - 42;
        int alertWidth = Math.min(176, Math.max(0, alertRight - alertLeft));
        alerts = !dashboardOpen && alertsOpen && !splitObjects
            ? new Rect(alertLeft + (alertRight - alertLeft - alertWidth) / 2, height - 47, alertWidth, 30)
            : null;
        for (int i = 0; i < tasks.length; i++) tasks[i] = new Rect(0, 0, 0, 0);
        sources = new Rect(0, 0, 0, 0);
        int worldLeft = sidebar == null ? 43 : sidebar.x + sidebar.w + 8;
        int worldRight = rail != null ? rail.x - 8 : power != null ? power.x - 8 : width - 42;
        int worldBottom = alerts == null ? height - 8 : alerts.y - 8;
        worldViewport = dashboardOpen ? new Rect(0, 0, 0, 0)
            : new Rect(worldLeft, 42, worldRight - worldLeft, worldBottom - 42);
        if (dashboardOpen) {
            for (int i = 0; i < terminals.length; i++) terminals[i] = new Rect(0, 0, 0, 0);
            for (int i = 0; i < modes.length; i++) modes[i] = new Rect(0, 0, 0, 0);
            for (int i = 0; i < tools.length; i++) tools[i] = new Rect(0, 0, 0, 0);
        }
    }

    public boolean contains(int x, int y) {
        for (Rect r : terminals) if (r.contains(x, y)) return true;
        for (Rect r : modes) if (r.contains(x, y)) return true;
        for (Rect r : tools) if (r.contains(x, y)) return true;
        for (Rect r : tasks) if (r.contains(x, y)) return true;
        if (sources.contains(x, y)) return true;
        return cameraHints.contains(x, y) || shortcuts.contains(x, y)
            || inside(sidebar, x, y)
            || inside(rail, x, y)
            || inside(inspector, x, y)
            || inside(power, x, y)
            || inside(dashboard, x, y)
            || inside(alerts, x, y);
    }

    public static boolean inside(Rect rect, int x, int y) {
        return rect != null && rect.contains(x, y);
    }

    public static final class Rect {

        public final int x, y, w, h;

        public Rect(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.w = Math.max(0, width);
            this.h = Math.max(0, height);
        }

        public boolean contains(int px, int py) {
            return px >= x && py >= y && px < x + w && py < y + h;
        }
    }
}
