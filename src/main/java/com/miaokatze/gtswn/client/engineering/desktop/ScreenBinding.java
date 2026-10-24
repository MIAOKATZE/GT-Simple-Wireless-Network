package com.miaokatze.gtswn.client.engineering.desktop;

import java.util.Objects;
import java.util.UUID;

/** Exact portable-terminal / information-screen preference; contains no data or authority. */
public final class ScreenBinding {

    public final UUID source, screen;

    public ScreenBinding(UUID source, UUID screen) {
        this.source = Objects.requireNonNull(source, "source");
        this.screen = Objects.requireNonNull(screen, "screen");
    }

    @Override
    public boolean equals(Object value) {
        if (!(value instanceof ScreenBinding)) return false;
        ScreenBinding other = (ScreenBinding) value;
        return source.equals(other.source) && screen.equals(other.screen);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, screen);
    }
}
