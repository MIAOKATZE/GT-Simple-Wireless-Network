package com.miaokatze.gtswn.client.engineering.desktop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Sparse desktop: 1024 is the initial arrangement, never a canvas boundary. */
public final class DesktopLayout {

    public static final int DEFAULT_SIZE = 1024, CELL = 64, MAX_COORD = 1000000, MAX_SIZE = 64, MAX_WIDGETS = 32;
    private final List<WidgetSpec> widgets = new ArrayList<>();

    public List<WidgetSpec> widgets() {
        return Collections.unmodifiableList(widgets);
    }

    /** Occupied desktop pixels; an empty layout retains its initial 1024-square reference area. */
    public Bounds bounds() {
        if (widgets.isEmpty()) return new Bounds(0, 0, DEFAULT_SIZE, DEFAULT_SIZE);
        int left = Integer.MAX_VALUE, top = left, right = Integer.MIN_VALUE, bottom = right;
        for (WidgetSpec widget : widgets) {
            left = Math.min(left, widget.x());
            top = Math.min(top, widget.y());
            right = Math.max(right, widget.x() + widget.width());
            bottom = Math.max(bottom, widget.y() + widget.height());
        }
        return new Bounds(left, top, right, bottom);
    }

    public static final class Bounds {

        public final int left, top, right, bottom;

        private Bounds(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public int width() {
            return right - left;
        }

        public int height() {
            return bottom - top;
        }
    }

    public WidgetSpec find(String id) {
        for (WidgetSpec widget : widgets) if (widget.id.equals(id)) return widget;
        return null;
    }

    public static DesktopLayout defaultLayout() {
        return DesktopPreset.overviewLayout();
    }

    public boolean add(WidgetSpec widget) {
        if (widget == null || widgets.size() >= MAX_WIDGETS || find(widget.id) != null || !fits(widget, null))
            return false;
        widgets.add(widget);
        return true;
    }

    public WidgetSpec add(WidgetKind kind) {
        return add(kind, null);
    }

    public WidgetSpec add(WidgetKind kind, int column, int row) {
        if (kind == null) return null;
        WidgetSpec widget = new WidgetSpec(
            UUID.randomUUID()
                .toString(),
            kind,
            column,
            row,
            kind.minColumns,
            kind.minRows);
        return add(widget) ? widget : null;
    }

    public WidgetSpec addDevice(DeviceBinding binding) {
        return add(WidgetKind.DEVICE, binding);
    }

    /** Bind before placement so a precise resource can use a compact free space atomically. */
    public WidgetSpec addResource(ResourceBinding binding) {
        if (binding == null || widgets.size() >= MAX_WIDGETS) return null;
        WidgetSpec candidate = new WidgetSpec(
            UUID.randomUUID()
                .toString(),
            WidgetKind.RESOURCES,
            0,
            0,
            4,
            2).boundResource(binding);
        return place(candidate);
    }

    private WidgetSpec add(WidgetKind kind, DeviceBinding binding) {
        if (kind == null || widgets.size() >= MAX_WIDGETS) return null;
        WidgetSpec candidate = new WidgetSpec(
            UUID.randomUUID()
                .toString(),
            kind,
            0,
            0,
            kind.minColumns,
            kind.minRows,
            "",
            binding,
            kind == WidgetKind.DEVICE ? java.util.Arrays.asList("status", "instantInput", "instantOutput")
                : Collections.<String>emptyList(),
            1);
        candidate = candidate.withBounds(0, 0, candidate.minColumns(), candidate.minRows());
        return place(candidate);
    }

    private WidgetSpec place(WidgetSpec candidate) {
        // Bounded search in the compact area; then append beneath the occupied area.
        for (int row = 0; row < 64; row++) for (int col = 0; col <= 16 - candidate.columns; col++) {
            WidgetSpec at = candidate.withBounds(col, row, candidate.columns, candidate.rows);
            if (add(at)) return at;
        }
        int bottom = 0;
        for (WidgetSpec widget : widgets) bottom = Math.max(bottom, widget.row + widget.rows);
        candidate = candidate.withBounds(0, bottom, candidate.columns, candidate.rows);
        return add(candidate) ? candidate : null;
    }

    public boolean remove(String id) {
        WidgetSpec widget = find(id);
        return widget != null && widgets.remove(widget);
    }

    public boolean replace(WidgetSpec candidate) {
        if (candidate == null) return false;
        WidgetSpec original = find(candidate.id);
        if (original == null || !fits(candidate, original.id)) return false;
        widgets.set(widgets.indexOf(original), candidate);
        return true;
    }

    public boolean move(String id, int column, int row) {
        WidgetSpec widget = find(id);
        return widget != null && replace(widget.withBounds(column, row, widget.columns, widget.rows));
    }

    public boolean resize(String id, int column, int row, int columns, int rows) {
        WidgetSpec widget = find(id);
        return widget != null && replace(widget.withBounds(column, row, columns, rows));
    }

    public boolean fits(WidgetSpec candidate, String ignoredId) {
        if (candidate.columns < candidate.minColumns() || candidate.rows < candidate.minRows()
            || candidate.columns > MAX_SIZE
            || candidate.rows > MAX_SIZE
            || candidate.column < -MAX_COORD
            || candidate.row < -MAX_COORD
            || (long) candidate.column + candidate.columns > MAX_COORD
            || (long) candidate.row + candidate.rows > MAX_COORD) return false;
        for (WidgetSpec other : widgets) {
            if (other.id.equals(ignoredId)) continue;
            if (candidate.column < other.column + other.columns && candidate.column + candidate.columns > other.column
                && candidate.row < other.row + other.rows
                && candidate.row + candidate.rows > other.row) return false;
        }
        return true;
    }

    public void reset() {
        widgets.clear();
        widgets.addAll(defaultLayout().widgets);
    }

    public DesktopLayout copy() {
        DesktopLayout copy = new DesktopLayout();
        copy.widgets.addAll(widgets);
        return copy;
    }
}
