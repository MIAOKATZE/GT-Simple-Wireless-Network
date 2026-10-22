package com.miaokatze.gtswn.client.engineering;

/** One geometry contract for world HUD drawing, hit testing, hover and Core navigation exclusion. */
public final class WorkbenchLayout {

    public final Rect[] terminals = new Rect[8];
    public final Rect[] modes = new Rect[5];
    public final Rect[] tools = new Rect[5];
    public final Rect sidebar, rail, power, dashboard, alerts;

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
        for (int i = 0; i < terminals.length; i++) terminals[i] = new Rect(8, 56 + i * 33, 28, 28);
        for (int i = 0; i < modes.length; i++) modes[i] = new Rect(8 + i * 31, 9, 27, 24);
        for (int i = 0; i < tools.length; i++) tools[i] = new Rect(width - 34, 56 + i * 33, 26, 27);
        sidebar = sidebarOpen ? new Rect(43, 56, 194, Math.min(300, Math.max(200, height - 64))) : null;
        rail = !dashboardOpen && railOpen ? new Rect(width - 252, 56, 210, Math.min(height - 180, railHeight)) : null;
        power = !dashboardOpen && powerOpen ? new Rect(width - 252, height - powerHeight - 8, 210, powerHeight) : null;
        int left = sidebarOpen ? 244 : 43;
        dashboard = dashboardOpen ? new Rect(left, 56, width - left - 42, height - 64) : null;
        int alertLeft = sidebarOpen ? 244 : 43;
        int alertRight = railOpen || powerOpen ? width - 260 : width - 42;
        int alertWidth = Math.min(176, Math.max(80, alertRight - alertLeft));
        alerts = !dashboardOpen && alertsOpen
            ? new Rect(alertLeft + (alertRight - alertLeft - alertWidth) / 2, height - 47, alertWidth, 30)
            : null;
    }

    public boolean contains(int x, int y) {
        for (Rect r : terminals) if (r.contains(x, y)) return true;
        for (Rect r : modes) if (r.contains(x, y)) return true;
        for (Rect r : tools) if (r.contains(x, y)) return true;
        return inside(sidebar, x, y) || inside(rail, x, y)
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
