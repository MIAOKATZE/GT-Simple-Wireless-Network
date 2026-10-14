package com.miaokatze.gtswn.client.gui;

/** One coordinate space for native GuiScreen input, rendering and framebuffer clipping. */
public final class GuiCanvas {

    public final float scale;
    public final int width, height;

    public GuiCanvas(int screenWidth, int screenHeight, int panelWidth, int panelHeight) {
        scale = Math.max(
            .01f,
            Math.min(
                1f,
                Math.min(
                    Math.max(1, screenWidth - 16) / (float) panelWidth,
                    Math.max(1, screenHeight - 16) / (float) panelHeight)));
        width = (int) Math.floor(screenWidth / (double) scale);
        height = (int) Math.floor(screenHeight / (double) scale);
    }

    public int mouse(int screenCoordinate) {
        return (int) Math.floor(screenCoordinate / (double) scale);
    }

    /** Pixel edges round outward, then clamp to the actual framebuffer. */
    public int[] scissor(int x, int y, int w, int h, int guiFactor, int displayWidth, int displayHeight) {
        double factor = guiFactor * (double) scale;
        int x0 = Math.max(0, (int) Math.floor(x * factor));
        int x1 = Math.min(displayWidth, (int) Math.ceil((x + w) * factor));
        int y0 = Math.max(0, displayHeight - (int) Math.ceil((y + h) * factor));
        int y1 = Math.min(displayHeight, displayHeight - (int) Math.floor(y * factor));
        return new int[] { x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0) };
    }
}
