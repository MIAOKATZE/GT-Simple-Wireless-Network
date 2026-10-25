package com.miaokatze.gtswn.client.engineering.desktop;

import com.miaokatze.miaokatzecore.api.ui.CanvasTransform;

/** A local view over a sparse desktop; translation is in desktop pixels. */
public final class DesktopViewport {

    public static final double MIN_SCALE = .025, MAX_SCALE = 4, DEFAULT_SCALE = .6;
    public double panX, panY, scale = DEFAULT_SCALE;

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
        DesktopLayout.Bounds bounds = layout.bounds();
        scale = Math
            .max(MIN_SCALE, Math.min(1, Math.min((width - 32) / bounds.width(), (height - 32) / bounds.height())));
        center(layout, width, height);
    }

    /** Initial view, reset and explicit preset application share the same readable default zoom. */
    public void reset(DesktopLayout layout, double width, double height) {
        scale = DEFAULT_SCALE;
        panX = panY = 0;
        center(layout, width, height);
    }

    /** Centers occupied geometry without changing zoom; grid cell boundaries remain the seam centerlines. */
    public void center(DesktopLayout layout, double width, double height) {
        if (layout == null || width <= 0 || height <= 0 || !Double.isFinite(width) || !Double.isFinite(height)) return;
        sanitize();
        DesktopLayout.Bounds bounds = layout.bounds();
        panX = (bounds.left + bounds.right - width / scale) / 2;
        panY = (bounds.top + bounds.bottom - height / scale) / 2;
        sanitize();
    }

    /** Centers the initial arrangement area at the current zoom, without clamping future navigation to it. */
    public void centerInitial(double width, double height) {
        if (width <= 0 || height <= 0 || !Double.isFinite(width) || !Double.isFinite(height)) return;
        sanitize();
        panX = (DesktopLayout.DEFAULT_SIZE - width / scale) / 2;
        panY = (DesktopLayout.DEFAULT_SIZE - height / scale) / 2;
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
        scale = Double.isFinite(scale) ? Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale)) : DEFAULT_SCALE;
    }
}
