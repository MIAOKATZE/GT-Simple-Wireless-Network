package com.miaokatze.gtswn.client.engineering.desktop;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Selection is local editor state; coordinates remain in desktop pixels at every zoom level. */
public final class DesktopSelection {

    private final Set<String> ids = new LinkedHashSet<>();
    private Set<String> before;
    private double startX, startY, x, y;

    public Set<String> ids() {
        return Collections.unmodifiableSet(ids);
    }

    public void only(String id) {
        ids.clear();
        if (id != null && !id.isEmpty()) ids.add(id);
    }

    public boolean toggle(String id) {
        if (!ids.remove(id)) return ids.add(id);
        return false;
    }

    public void retain(Collection<String> valid) {
        ids.retainAll(valid);
    }

    public void begin(double x, double y) {
        before = new LinkedHashSet<>(ids);
        startX = this.x = x;
        startY = this.y = y;
    }

    public boolean active() {
        return before != null;
    }

    public void update(DesktopLayout layout, double x, double y) {
        if (!active() || !Double.isFinite(x) || !Double.isFinite(y)) return;
        this.x = x;
        this.y = y;
        ids.clear();
        ids.addAll(before);
        for (WidgetSpec widget : layout.widgets()) if (widget.x() < right() && widget.x() + widget.width() > left()
            && widget.y() < bottom()
            && widget.y() + widget.height() > top()) ids.add(widget.id);
    }

    public double left() {
        return Math.min(startX, x);
    }

    public double top() {
        return Math.min(startY, y);
    }

    public double right() {
        return Math.max(startX, x);
    }

    public double bottom() {
        return Math.max(startY, y);
    }

    public void finish() {
        before = null;
    }

    public void cancel() {
        if (before != null) {
            ids.clear();
            ids.addAll(before);
            before = null;
        }
    }
}
