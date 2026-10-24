package com.miaokatze.gtswn.client.engineering;

import com.miaokatze.gtswn.client.engineering.WorkbenchLayout.Rect;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopDrag;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopViewport;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetKind;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetSpec;
import com.miaokatze.miaokatzecore.api.ui.CanvasTransform;

/** Screen geometry shared by card drawing, picking and visible resize handles. */
final class DesktopCardGeometry {

    static boolean showResizeHandles(double scale) {
        return scale >= .25;
    }

    static void focusAtOne(DesktopViewport view, WidgetSpec widget, Rect canvas) {
        view.scale = 1;
        view.panX = widget.x() + widget.width() / 2.0 - canvas.w / 2.0;
        view.panY = widget.y() + widget.height() / 2.0 - canvas.h / 2.0;
        view.sanitize();
    }

    static int headerReserved(WidgetSpec widget) {
        return widget.kind == WidgetKind.HISTORY ? 132 : widget.kind == WidgetKind.RESOURCES ? 112 : 0;
    }

    static int gap(double scale) {
        return Math.max(2, Math.min(8, (int) Math.round(8 * scale)));
    }

    static double gridOffset(double scale) {
        return 0;
    }

    static Rect bounds(WidgetSpec widget, CanvasTransform transform) {
        int gap = gap(transform.scale());
        return new Rect(
            (int) Math.round(transform.toScreenX(widget.x())) + gap / 2,
            (int) Math.round(transform.toScreenY(widget.y())) + gap / 2,
            Math.max(1, (int) Math.round(widget.width() * transform.scale()) - gap),
            Math.max(1, (int) Math.round(widget.height() * transform.scale()) - gap));
    }

    static DesktopDrag.Edge edge(Rect rect, double x, double y) {
        boolean left = Math.abs(x - rect.x) <= 7, right = Math.abs(x - rect.x - rect.w) <= 7,
            top = Math.abs(y - rect.y) <= 7, bottom = Math.abs(y - rect.y - rect.h) <= 7;
        if (top && left) return DesktopDrag.Edge.TOP_LEFT;
        if (top && right) return DesktopDrag.Edge.TOP_RIGHT;
        if (bottom && left) return DesktopDrag.Edge.BOTTOM_LEFT;
        if (bottom && right) return DesktopDrag.Edge.BOTTOM_RIGHT;
        boolean middleX = Math.abs(x - rect.x - rect.w / 2.0) <= 7, middleY = Math.abs(y - rect.y - rect.h / 2.0) <= 7;
        return left && middleY ? DesktopDrag.Edge.LEFT
            : right && middleY ? DesktopDrag.Edge.RIGHT
                : top && middleX ? DesktopDrag.Edge.TOP
                    : bottom && middleX ? DesktopDrag.Edge.BOTTOM : DesktopDrag.Edge.MOVE;
    }

    static boolean hit(Rect rect, double x, double y, boolean handles) {
        int pad = handles ? 7 : 0;
        return x >= rect.x - pad && x < rect.x + rect.w + pad && y >= rect.y - pad && y < rect.y + rect.h + pad;
    }
}
