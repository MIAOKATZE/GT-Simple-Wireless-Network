package com.miaokatze.gtswn.client.engineering;

/** Scaled HUD coordinates; reserve the top-right square without querying a minimap implementation. */
public final class FactoryEntryHintLayout {

    private FactoryEntryHintLayout() {}

    public static Bounds place(int screenWidth, int screenHeight, int desiredWidth, Bounds terminal) {
        int margin = 8;
        int mapReserve = Math.min(144, screenWidth / 2);
        int mapLeft = screenWidth - mapReserve - margin;
        int width = Math.max(1, Math.min(desiredWidth, screenWidth - margin * 2));
        int height = Math.min(34, Math.max(1, screenHeight - margin * 2));
        int x = terminal == null ? screenWidth / 2 + margin : terminal.right() + margin;
        int y = 8;
        if (mapLeft - x >= Math.min(112, width)) {
            width = Math.min(width, mapLeft - x);
        } else {
            // Stay below the actual terminal card and left of the reserved map where possible.
            y = terminal == null ? 38 : terminal.bottom() + 6;
            if (mapLeft - margin >= Math.min(112, width)) {
                width = Math.min(width, mapLeft - margin);
                x = margin + Math.max(0, (mapLeft - margin - width) / 2);
            } else {
                x = margin;
                y = Math.max(y, mapReserve + margin * 2);
            }
        }
        x = Math.max(0, Math.min(x, screenWidth - width));
        y = Math.max(0, Math.min(y, screenHeight - height));
        return new Bounds(x, y, width, height);
    }

    public static final class Bounds {

        public final int x, y, width, height;

        public Bounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }
    }
}
