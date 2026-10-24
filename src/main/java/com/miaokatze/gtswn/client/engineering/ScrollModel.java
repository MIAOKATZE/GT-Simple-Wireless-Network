package com.miaokatze.gtswn.client.engineering;

/** One clamped offset shared by input and drawing. Units may be pixels or rows. */
public final class ScrollModel {

    private int offset, maximum, viewport;

    public int measure(int content, int viewport, int requested) {
        this.viewport = Math.max(0, viewport);
        maximum = Math.max(0, content - Math.max(0, viewport));
        return set(requested);
    }

    public int set(int requested) {
        offset = Math.max(0, Math.min(maximum, requested));
        return offset;
    }

    public int move(int delta) {
        return set(offset + delta);
    }

    public int offset() {
        return offset;
    }

    public int maximum() {
        return maximum;
    }

    public int viewport() {
        return viewport;
    }
}
