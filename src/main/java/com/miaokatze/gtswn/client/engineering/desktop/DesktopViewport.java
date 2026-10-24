package com.miaokatze.gtswn.client.engineering.desktop;

import com.miaokatze.miaokatzecore.api.ui.CanvasTransform;

/** A local view over a sparse desktop; translation is in desktop pixels. */
public final class DesktopViewport {

    public static final double MIN_SCALE = .025, MAX_SCALE = 4;
    public double panX, panY, scale = 1;

    public CanvasTransform transform(double viewportX, double viewportY) {
        sanitize();
        return new CanvasTransform(panX, panY, viewportX, viewportY, scale);
    }

    public double toLocalX(double screenX, double viewportX) {
        return transform(viewportX, 0).toLocalX(screenX);
    }

    public double toLocalY(double screenY, double viewportY) {
        return transform(0, viewportY).toLocalY(screenY);
    }

    public void pan(double screenDx, double screenDy) {
        if (!Double.isFinite(screenDx) || !Double.isFinite(screenDy)) return;
        sanitize();
        panX -= screenDx / scale;
        panY -= screenDy / scale;
        sanitize();
    }

    /** Keeps the desktop point underneath the cursor fixed through a zoom. */
    public void zoomAt(double factor, double mouseX, double mouseY, double viewportX, double viewportY) {
        if (!Double.isFinite(factor) || factor <= 0
            || !Double.isFinite(mouseX)
            || !Double.isFinite(mouseY)
            || !Double.isFinite(viewportX)
            || !Double.isFinite(viewportY)) return;
        double x = toLocalX(mouseX, viewportX), y = toLocalY(mouseY, viewportY);
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale * factor));
        panX = x - (mouseX - viewportX) / scale;
        panY = y - (mouseY - viewportY) / scale;
        sanitize();
    }

    public void fit(DesktopLayout layout, double width, double height) {
        if (layout == null || width <= 32 || height <= 32 || !Double.isFinite(width) || !Double.isFinite(height))
            return;
        if (layout.widgets()
            .isEmpty()) {
            panX = panY = 0;
            scale = 1;
            return;
        }
        double minX = Double.POSITIVE_INFINITY, minY = minX, maxX = Double.NEGATIVE_INFINITY, maxY = maxX;
        for (WidgetSpec widget : layout.widgets()) {
            minX = Math.min(minX, widget.x());
            minY = Math.min(minY, widget.y());
            maxX = Math.max(maxX, widget.x() + widget.width());
            maxY = Math.max(maxY, widget.y() + widget.height());
        }
        scale = Math.max(MIN_SCALE, Math.min(1, Math.min((width - 32) / (maxX - minX), (height - 32) / (maxY - minY))));
        panX = (minX + maxX - width / scale) / 2;
        panY = (minY + maxY - height / scale) / 2;
        sanitize();
    }

    public DesktopViewport copy() {
        DesktopViewport copy = new DesktopViewport();
        copy.panX = panX;
        copy.panY = panY;
        copy.scale = scale;
        copy.sanitize();
        return copy;
    }

    public void sanitize() {
        double limit = (double) DesktopLayout.MAX_COORD * DesktopLayout.CELL;
        panX = Double.isFinite(panX) ? Math.max(-limit, Math.min(limit, panX)) : 0;
        panY = Double.isFinite(panY) ? Math.max(-limit, Math.min(limit, panY)) : 0;
        scale = Double.isFinite(scale) ? Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale)) : 1;
    }
}
