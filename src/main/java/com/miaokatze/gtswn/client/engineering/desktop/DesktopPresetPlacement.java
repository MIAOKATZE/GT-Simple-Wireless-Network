package com.miaokatze.gtswn.client.engineering.desktop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Ephemeral, source-free preset geometry and a single complete canvas click. Never persisted. */
public final class DesktopPresetPlacement {

    private final DesktopLayout template = new DesktopLayout();
    private final boolean preserveBindings;
    private final int left, top;
    private List<WidgetSpec> positioned = Collections.emptyList();
    private int column, row;
    private boolean pointed, pressed, dragged;
    private double pressX, pressY;

    public DesktopPresetPlacement(DesktopLayout source) {
        this(source, false);
    }

    private DesktopPresetPlacement(DesktopLayout source, boolean preserveBindings) {
        if (source == null || source.widgets()
            .isEmpty()) throw new IllegalArgumentException("template");
        this.preserveBindings = preserveBindings;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        for (WidgetSpec widget : source.widgets()) {
            if (!template.add(preserveBindings ? widget : widget.asTemplate()))
                throw new IllegalArgumentException("template geometry");
            minX = Math.min(minX, widget.column);
            minY = Math.min(minY, widget.row);
        }
        left = minX;
        top = minY;
    }

    /** Trusted local choice retains its exact source; shared and built-in presets use the source-free constructor. */
    public static DesktopPresetPlacement single(WidgetSpec widget) {
        DesktopLayout source = new DesktopLayout();
        if (!source.add(widget)) throw new IllegalArgumentException("widget geometry");
        return new DesktopPresetPlacement(source, true);
    }

    public int count() {
        return template.widgets()
            .size();
    }

    public boolean single() {
        return preserveBindings;
    }

    public void point(double localX, double localY) {
        if (!Double.isFinite(localX) || !Double.isFinite(localY)) return;
        int limit = DesktopLayout.MAX_COORD + DesktopLayout.MAX_SIZE + 1;
        int nextColumn = (int) Math.max(-limit, Math.min(limit, Math.floor(localX / DesktopLayout.CELL)));
        int nextRow = (int) Math.max(-limit, Math.min(limit, Math.floor(localY / DesktopLayout.CELL)));
        if (pointed && column == nextColumn && row == nextRow) return;
        column = nextColumn;
        row = nextRow;
        pointed = true;
        List<WidgetSpec> result = new ArrayList<>();
        for (WidgetSpec widget : template.widgets()) result
            .add(widget.withBounds(column + widget.column - left, row + widget.row - top, widget.columns, widget.rows));
        positioned = Collections.unmodifiableList(result);
    }

    public List<WidgetSpec> widgets() {
        return positioned;
    }

    public boolean valid(DesktopLayout layout) {
        return status(layout) == DesktopLayout.PlacementStatus.VALID;
    }

    public DesktopLayout.PlacementStatus status(DesktopLayout layout) {
        return pointed ? layout.checkExact(template, column, row, preserveBindings)
            : DesktopLayout.PlacementStatus.EMPTY;
    }

    public void press(double screenX, double screenY) {
        pressed = true;
        dragged = false;
        pressX = screenX;
        pressY = screenY;
    }

    public void move(double screenX, double screenY) {
        if (pressed && Math.hypot(screenX - pressX, screenY - pressY) > 5) dragged = true;
    }

    /** A release without a preceding canvas press, or any drag, cannot commit. */
    public List<WidgetSpec> release(DesktopLayout layout, double screenX, double screenY, boolean inside) {
        move(screenX, screenY);
        boolean commit = pressed && !dragged && inside && valid(layout);
        pressed = false;
        return commit ? layout.appendExact(template, column, row, preserveBindings) : Collections.emptyList();
    }
}
