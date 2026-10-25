package com.miaokatze.gtswn.client.engineering.desktop;

import java.util.Objects;

/** Immutable decorative preferences. Animation never changes the measured reading or its denominator. */
public final class WidgetEffects {

    public enum Style {
        ORBITAL,
        SIGNAL,
        SEGMENTED,
        PRISM,
        WAVE
    }

    public static final WidgetEffects DEFAULT = new WidgetEffects(Style.ORBITAL, true, 0xFF67DEE5, 1, .75);
    public final Style style;
    public final boolean animated;
    public final int accentColor;
    public final double speed, intensity, glow;

    public WidgetEffects(Style style, boolean animated, int accentColor, double speed, double intensity) {
        this(style, animated, accentColor, speed, intensity, .85);
    }

    public WidgetEffects(Style style, boolean animated, int accentColor, double speed, double intensity, double glow) {
        this.style = Objects.requireNonNull(style, "style");
        if (!Double.isFinite(speed) || speed < 0 || speed > 3) throw new IllegalArgumentException("speed");
        if (!Double.isFinite(intensity) || intensity < 0 || intensity > 1)
            throw new IllegalArgumentException("intensity");
        if (!Double.isFinite(glow) || glow < 0 || glow > 2) throw new IllegalArgumentException("glow");
        this.animated = animated;
        this.accentColor = 0xFF000000 | accentColor;
        this.speed = speed;
        this.intensity = intensity;
        this.glow = glow;
    }

    public WidgetEffects withStyle(Style value) {
        return new WidgetEffects(value, animated, accentColor, speed, intensity, glow);
    }

    public WidgetEffects withAnimated(boolean value) {
        return new WidgetEffects(style, value, accentColor, speed, intensity, glow);
    }

    public WidgetEffects withAccentColor(int value) {
        return new WidgetEffects(style, animated, value, speed, intensity, glow);
    }

    public WidgetEffects withSpeed(double value) {
        return new WidgetEffects(style, animated, accentColor, value, intensity, glow);
    }

    public WidgetEffects withIntensity(double value) {
        return new WidgetEffects(style, animated, accentColor, speed, value, glow);
    }

    public WidgetEffects withGlow(double value) {
        return new WidgetEffects(style, animated, accentColor, speed, intensity, value);
    }
}
