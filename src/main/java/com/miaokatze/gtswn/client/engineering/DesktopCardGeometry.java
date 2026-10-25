package com.miaokatze.gtswn.client.engineering;

import com.miaokatze.gtswn.client.engineering.WorkbenchLayout.Rect;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopDrag;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopViewport;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetKind;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetSpec;
import com.miaokatze.miaokatzecore.api.ui.CanvasTransform;

/** Screen geometry shared by card drawing, picking and visible resize handles. */
final class DesktopCardGeometry {

    static final int GAP = 8;
    static final double BORDER_LOD_SCALE = .18;

    static Rect canonical(WidgetSpec widget) {
        return new Rect(0, 0, widget.width() - GAP, widget.height() - GAP);
    }

    static CanvasTransform contentTransform(WidgetSpec widget, CanvasTransform viewport) {
        return new CanvasTransform(
            0,
            0,
            viewport.toScreenX(widget.x() + GAP / 2.0),
            viewport.toScreenY(widget.y() + GAP / 2.0),
            viewport.scale());
    }

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
        return widget.kind == WidgetKind.HISTORY ? 132
            : widget.kind == WidgetKind.RESOURCES ? 112 : widget.kind == WidgetKind.WIRELESS ? 30 : 0;
    }

    static int gap(double scale) {
        return Math.max(0, (int) Math.round(GAP * scale));
    }

    static double gridOffset(double scale) {
        return 0;
    }

    static Rect bounds(WidgetSpec widget, CanvasTransform transform) {
        CanvasTransform content = contentTransform(widget, transform);
        Rect local = canonical(widget);
        int left = (int) Math.floor(content.toScreenX(0)), top = (int) Math.floor(content.toScreenY(0));
        return new Rect(
            left,
            top,
            Math.max(1, (int) Math.ceil(content.toScreenX(local.w)) - left),
            Math.max(1, (int) Math.ceil(content.toScreenY(local.h)) - top));
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

    static boolean titleHandle(Rect rect, double scale, double x, double y) {
        return x >= rect.x + 10 && x < rect.x + rect.w - 10 && y >= rect.y && y < rect.y + 24 * scale;
    }

    static int overviewFill(WidgetSpec widget) {
        return 0xB0000000 | widget.borderColor & 0xFFFFFF;
    }

    static boolean hit(Rect rect, double x, double y, boolean handles) {
        int pad = handles ? 7 : 0;
        return x >= rect.x - pad && x < rect.x + rect.w + pad && y >= rect.y - pad && y < rect.y + rect.h + pad;
    }
}
