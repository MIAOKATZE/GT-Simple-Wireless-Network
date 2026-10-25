package com.miaokatze.gtswn.client.engineering.desktop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Sparse desktop: 1024 is the initial arrangement, never a canvas boundary. */
public final class DesktopLayout {

    public static final int DEFAULT_SIZE = 1024, CELL = 64, MAX_COORD = 1000000, MAX_SIZE = 64, MAX_WIDGETS = 32;
    private final List<WidgetSpec> widgets = new ArrayList<>();
    private DashboardTheme theme = DashboardTheme.STANDARD;

    public DashboardTheme theme() {
        return theme;
    }

    public void setTheme(DashboardTheme theme) {
        this.theme = java.util.Objects.requireNonNull(theme, "theme");
    }

    public DesktopLayout withTheme(DashboardTheme theme) {
        setTheme(theme);
        return this;
    }

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

    /** Append an independent, unbound template beside the current desktop. Empty means no change. */
    public List<WidgetSpec> appendPreset(DesktopLayout template) {
        Bounds occupied = bounds();
        return appendPreset(
            template,
            widgets.isEmpty() ? 0 : occupied.right / CELL + 1,
            widgets.isEmpty() ? 0 : occupied.top / CELL);
    }

    /**
     * Place a whole template near its requested top-left corner, preserving every relative position. Search only
     * obstacle edges, never the million-cell canvas. Original widgets and terminal references remain untouched.
     */
    public List<WidgetSpec> appendPreset(DesktopLayout template, int anchorColumn, int anchorRow) {
        if (template == null || template.widgets.isEmpty() || widgets.size() + template.widgets.size() > MAX_WIDGETS)
            return Collections.emptyList();
        List<WidgetSpec> incoming = new ArrayList<>(template.widgets);
        int left = Integer.MAX_VALUE, top = left, right = Integer.MIN_VALUE, bottom = right;
        for (WidgetSpec widget : incoming) {
            left = Math.min(left, widget.column);
            top = Math.min(top, widget.row);
            right = Math.max(right, widget.column + widget.columns);
            bottom = Math.max(bottom, widget.row + widget.rows);
        }
        long minX = -MAX_COORD - (long) left, maxX = MAX_COORD - (long) right;
        long minY = -MAX_COORD - (long) top, maxY = MAX_COORD - (long) bottom;
        if (minX > maxX || minY > maxY) return Collections.emptyList();
        long wantedX = clamp((long) anchorColumn - left, minX, maxX);
        long wantedY = clamp((long) anchorRow - top, minY, maxY);
        Set<Long> rows = new TreeSet<>();
        rows.add(wantedY);
        rows.add(minY);
        rows.add(maxY);
        for (WidgetSpec old : widgets) for (WidgetSpec next : incoming) {
            rows.add(clamp((long) old.row + old.rows - next.row, minY, maxY));
            rows.add(clamp((long) old.row - next.row - next.rows, minY, maxY));
        }
        long bestX = 0, bestY = 0, bestDistance = Long.MAX_VALUE;
        for (long dy : rows) {
            List<long[]> forbidden = new ArrayList<>();
            for (WidgetSpec old : widgets) for (WidgetSpec next : incoming) {
                if (next.row + dy >= (long) old.row + old.rows || next.row + dy + next.rows <= old.row) continue;
                forbidden.add(
                    new long[] { (long) old.column - next.column - next.columns + 1,
                        (long) old.column + old.columns - next.column - 1 });
            }
            forbidden.sort(Comparator.comparingLong(interval -> interval[0]));
            long availableFrom = minX;
            for (int index = 0; index <= forbidden.size() && availableFrom <= maxX; index++) {
                long blockedFrom = index == forbidden.size() ? maxX + 1 : forbidden.get(index)[0];
                long availableTo = Math.min(maxX, blockedFrom - 1);
                if (availableFrom <= availableTo) {
                    long dx = clamp(wantedX, availableFrom, availableTo);
                    long distance = Math.abs(dx - wantedX) + Math.abs(dy - wantedY);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestX = dx;
                        bestY = dy;
                    }
                }
                if (index < forbidden.size()) availableFrom = Math.max(availableFrom, forbidden.get(index)[1] + 1);
            }
        }
        if (bestDistance == Long.MAX_VALUE) return Collections.emptyList();
        DesktopLayout staged = copy();
        List<WidgetSpec> added = new ArrayList<>();
        for (WidgetSpec widget : incoming) {
            WidgetSpec candidate = widget.asTemplate()
                .withAutoResource(widget.kind == WidgetKind.RESOURCES)
                .withId(
                    UUID.randomUUID()
                        .toString())
                .withBounds((int) (widget.column + bestX), (int) (widget.row + bestY), widget.columns, widget.rows);
            if (!staged.add(candidate)) return Collections.emptyList();
            added.add(candidate);
        }
        widgets.addAll(added);
        return Collections.unmodifiableList(added);
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
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
        copy.theme = theme;
        copy.widgets.addAll(widgets);
        return copy;
    }
}
