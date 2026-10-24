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

    public WidgetSpec find(String id) {
        for (WidgetSpec widget : widgets) if (widget.id.equals(id)) return widget;
        return null;
    }

    public static DesktopLayout defaultLayout() {
        DesktopLayout layout = new DesktopLayout();
        layout.add(new WidgetSpec("machines", WidgetKind.MACHINES, 0, 0, 4, 2));
        layout.add(new WidgetSpec("input", WidgetKind.INPUT, 4, 0, 4, 2));
        layout.add(new WidgetSpec("output", WidgetKind.OUTPUT, 8, 0, 4, 2));
        layout.add(new WidgetSpec("wireless", WidgetKind.WIRELESS, 12, 0, 4, 2));
        layout.add(new WidgetSpec("history", WidgetKind.HISTORY, 0, 2, 10, 5));
        layout.add(new WidgetSpec("region", WidgetKind.REGION, 10, 2, 6, 5));
        layout.add(new WidgetSpec("resources", WidgetKind.RESOURCES, 0, 7, 10, 7));
        layout.add(new WidgetSpec("attention", WidgetKind.ATTENTION, 10, 7, 6, 7));
        return layout;
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
            || (long) candidate.row + candidate.rows > MAX_COORD
            || candidate.kind == WidgetKind.DEVICE && candidate.device == null) return false;
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
