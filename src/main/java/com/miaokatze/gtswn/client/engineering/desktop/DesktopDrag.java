package com.miaokatze.gtswn.client.engineering.desktop;

/** Pointer capture uses the press geometry, so panning/zooming cannot accumulate rounding drift. */
public final class DesktopDrag {

    public enum Edge {
        MOVE,
        LEFT,
        RIGHT,
        TOP,
        BOTTOM,
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    private DesktopLayout layout;
    private WidgetSpec original;
    private Edge edge;
    private double startX, startY;
    private WidgetSpec preview;
    private java.util.List<WidgetSpec> group;

    public boolean begin(DesktopLayout layout, String id, Edge edge, double x, double y) {
        cancel();
        if (layout == null || edge == null || !Double.isFinite(x) || !Double.isFinite(y)) return false;
        WidgetSpec found = layout.find(id);
        if (found == null) return false;
        this.layout = layout;
        original = found;
        this.edge = edge;
        startX = x;
        startY = y;
        preview = found;
        return true;
    }

    public boolean beginGroup(DesktopLayout layout, java.util.Collection<String> ids, String anchor, double x,
        double y) {
        if (!begin(layout, anchor, Edge.MOVE, x, y)) return false;
        group = new java.util.ArrayList<>();
        for (WidgetSpec widget : layout.widgets()) if (ids.contains(widget.id)) group.add(widget);
        if (!ids.contains(anchor) || group.isEmpty()) {
            cancel();
            return false;
        }
        return true;
    }

    public boolean active() {
        return original != null;
    }

    public WidgetSpec preview() {
        return preview;
    }

    public boolean update(double x, double y) {
        if (!active() || !Double.isFinite(x) || !Double.isFinite(y)) return false;
        long dx = Math.round((x - startX) / DesktopLayout.CELL), dy = Math.round((y - startY) / DesktopLayout.CELL);
        long limit = DesktopLayout.MAX_COORD * 2L;
        if (dx < -limit || dx > limit || dy < -limit || dy > limit) return false;
        if (group != null) {
            java.util.List<WidgetSpec> moved = new java.util.ArrayList<>();
            for (WidgetSpec widget : group) moved
                .add(widget.withBounds(widget.column + (int) dx, widget.row + (int) dy, widget.columns, widget.rows));
            return layout.replaceAll(moved);
        }
        int c = original.column, r = original.row, w = original.columns, h = original.rows;
        if (edge == Edge.MOVE) {
            c += (int) dx;
            r += (int) dy;
        } else {
            String name = edge.name();
            if (name.contains("LEFT")) {
                c += (int) dx;
                w -= (int) dx;
            }
            if (name.contains("RIGHT")) w += (int) dx;
            if (name.contains("TOP")) {
                r += (int) dy;
                h -= (int) dy;
            }
            if (name.contains("BOTTOM")) h += (int) dy;
            int clampedW = Math.max(original.minColumns(), Math.min(DesktopLayout.MAX_SIZE, w));
            int clampedH = Math.max(original.minRows(), Math.min(DesktopLayout.MAX_SIZE, h));
            // Dragging beyond the minimum stops at it, preserving the opposite edge instead of freezing the preview.
            if (name.contains("LEFT")) c = original.column + original.columns - clampedW;
            if (name.contains("TOP")) r = original.row + original.rows - clampedH;
            w = clampedW;
            h = clampedH;
        }
        preview = original.withBounds(c, r, w, h);
        return layout.replace(preview);
    }

    /** Release commits the last accepted geometry; losing capture must call cancel instead. */
    public boolean finish() {
        boolean active = active();
        clear();
        return active;
    }

    public void cancel() {
        if (active()) {
            if (group != null) layout.replaceAll(group);
            else layout.replace(original);
        }
        clear();
    }

    private void clear() {
        group = null;
        layout = null;
        original = null;
        edge = null;
        preview = null;
    }
}
