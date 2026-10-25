package com.miaokatze.gtswn.client.engineering;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.drawable.GuiDraw;
import com.miaokatze.gtswn.client.engineering.WorkbenchLayout.Rect;
import com.miaokatze.gtswn.client.engineering.desktop.DashboardTheme;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetEffects;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetEffects.Style;
import com.miaokatze.miaokatzecore.client.render.GlScope;

/** Scoped drawing primitives; the desktop owns transform, clipping, data and input. */
final class FactoryDashboardArt {

    private static DashboardTheme theme = DashboardTheme.STANDARD;

    static void theme(DashboardTheme value) {
        theme = value;
    }

    private static int effectAccent(WidgetEffects effects) {
        return readableAccent(
            effects.accentColor == WidgetEffects.DEFAULT.accentColor ? theme.accent : effects.accentColor);
    }

    /** A custom pastel remains readable when placed on either paper theme. */
    static int readableAccent(int color) {
        if (theme != DashboardTheme.MINIMAL && theme != DashboardTheme.RETRO) return color;
        double luminance = .2126 * (color >> 16 & 255) + .7152 * (color >> 8 & 255) + .0722 * (color & 255);
        double scale = luminance <= 115 ? 1 : 115 / luminance;
        return 0xFF000000 | (int) ((color >> 16 & 255) * scale) << 16
            | (int) ((color >> 8 & 255) * scale) << 8
            | (int) ((color & 255) * scale);
    }

    private FactoryDashboardArt() {}

    static void card(Rect r, WidgetEffects effects, double phase) {
        try (GlScope ignored = new GlScope()) {
            GuiDraw.drawVerticalGradientRect(r.x, r.y, r.w, r.h, theme.cardTop, theme.cardBottom);
            prepare();
            int accent = effectAccent(effects);
            int grid = alpha(
                theme.grid,
                theme == DashboardTheme.STANDARD ? .23 : theme == DashboardTheme.MINIMAL ? .4 : .7);
            int step = theme == DashboardTheme.RETRO ? 16 : effects.style == Style.SEGMENTED ? 16 : 24;
            for (int x = r.x + step; x < r.x + r.w; x += step) quad(x, r.y + 23, x + .6, r.y + r.h - 2, grid);
            for (int y = r.y + 24; y < r.y + r.h; y += step) quad(r.x + 2, y, r.x + r.w - 2, y + .6, grid);
            if (theme == DashboardTheme.STANDARD) {
                // Quiet glass grain under content; bevels stay within the existing card bounds.
                for (int y = r.y + 26; y < r.y + r.h - 2; y += 6)
                    quad(r.x + 2, y, r.x + r.w - 2, y + .5, alpha(theme.text, .018));
                quad(r.x + 1, r.y + 1, r.x + r.w - 1, r.y + 2, alpha(theme.accent, .26));
                quad(r.x + 1, r.y + 2, r.x + 2, r.y + r.h - 2, alpha(theme.text, .07));
                quad(r.x + 1, r.y + r.h - 2, r.x + r.w - 1, r.y + r.h - 1, alpha(theme.background, .75));
            } else if (theme == DashboardTheme.RETRO) {
                for (int x = r.x + 8; x < r.x + r.w - 6; x += 64) for (int y = r.y + 32; y < r.y + r.h - 7; y += 64) {
                    quad(x - 2, y, x + 3, y + .6, alpha(theme.muted, .26));
                    quad(x, y - 2, x + .6, y + 3, alpha(theme.muted, .26));
                }
            } else if (theme == DashboardTheme.INDUSTRIAL) {
                for (int y = r.y + 25; y < r.y + r.h - 2; y += 4)
                    quad(r.x + 2, y, r.x + r.w - 2, y + .5, alpha(theme.text, .035));
                for (int horizontal = 0; horizontal < 2; horizontal++)
                    for (int vertical = 0; vertical < 2; vertical++) {
                        int x = horizontal == 0 ? r.x + 5 : r.x + r.w - 6;
                        int y = vertical == 0 ? r.y + 26 : r.y + r.h - 6;
                        quad(x - 1, y - 1, x + 2, y + 2, theme.track);
                        quad(x - .5, y + .3, x + 1.5, y + .8, theme.edge);
                    }
            }
            if (effects.style == Style.SIGNAL) {
                double y = r.y + 25 + phase * Math.max(0, r.h - 29);
                for (int tail = 0; tail < 7; tail++) quad(
                    r.x + 2,
                    y + tail,
                    r.x + r.w - 2,
                    Math.min(r.y + r.h - 2, y + tail + 1),
                    alpha(accent, .18 * effects.intensity * cycleEnvelope(phase) * (1 - tail / 7.0)));
            } else if (effects.style == Style.SEGMENTED) {
                for (int x = 5; x < r.w - 8; x += 12) {
                    double glow = .12 + .43 * circularPulse(x / (double) r.w - phase, .14);
                    quad(r.x + x, r.y + r.h - 4, r.x + x + 7, r.y + r.h - 2, alpha(accent, glow * effects.intensity));
                }
            } else if (effects.style == Style.PRISM) {
                for (int i = 0; i < 5; i++) {
                    double x = r.x + r.w * (i + .5) / 5;
                    double y = r.y + 25 + (r.h - 28) * (i % 2 == 0 ? .25 : .7);
                    double span = Math.min(36, r.w / 7.0);
                    triangle(
                        x - span,
                        y,
                        x,
                        Math.min(r.y + r.h - 2, y + 15),
                        x + span,
                        y,
                        alpha(accent, (.03 + .025 * Math.cos((phase + i / 5.0) * Math.PI * 2)) * effects.intensity));
                }
            } else if (effects.style == Style.WAVE) {
                for (int x = 3; x < r.w - 3; x += 3) {
                    double y = r.y + r.h - 7 + Math.sin((x / 60.0 - phase) * Math.PI * 2) * 2;
                    quad(r.x + x, y, r.x + x + 2, y + .7, alpha(accent, .32 * effects.intensity));
                }
            }
        }
    }

    static void header(Rect r, WidgetEffects effects, double phase) {
        try (GlScope ignored = new GlScope()) {
            prepare();
            double x = r.x + 1 + phase * Math.max(1, r.w - 2);
            for (int tail = 0; tail < 16; tail++) quad(
                Math.max(r.x + 1, x - tail - 1),
                r.y + 1,
                Math.min(r.x + r.w - 1, x - tail),
                r.y + 2,
                alpha(effectAccent(effects), .85 * effects.intensity * cycleEnvelope(phase) * (1 - tail / 16.0)));
        }
    }

    /** Symmetric ornamental brackets have no start/end fill or percentage semantics. */
    static void stockDial(double x, double y, double radius, boolean known, double phase, WidgetEffects effects,
        int color) {
        try (GlScope ignored = new GlScope()) {
            prepare();
            for (int i = 0; i < 60; i++)
                arc(x, y, radius + 5, i % 5 == 0 ? 3 : 1.4, i / 60.0, (i + .16) / 60.0, theme.edge);
            for (int i = 0; i < 3; i++) {
                double from = i / 3.0 + .04 + phase;
                if (known) luminousArc(x, y, radius, Math.max(3, radius * .045), from, from + .23, color, .86, effects);
                else arc(x, y, radius, Math.max(3, radius * .045), from, from + .23, theme.track);
                double innerFrom = innerOrbitStart(i, phase);
                arc(x, y, radius - 6, .9, innerFrom, innerFrom + .23, theme.edge);
            }
            arc(x, y, radius - 13, .7, 0, 1, theme.edge);
            if (known) ornament(x, y, radius + 1, phase, effects, color);
        }
    }

    /** A wrapped turn may only be multiplied by an integer to preserve its circular seam. */
    static double innerOrbitStart(int bracket, double phase) {
        return bracket / 3.0 + .04 - phase;
    }

    /** Independent decorative movement outside the actual data ring. */
    static void halo(double x, double y, double radius, double phase, WidgetEffects effects, int color) {
        try (GlScope ignored = new GlScope()) {
            prepare();
            ornament(x, y, radius, phase, effects, color);
        }
    }

    private static void ornament(double x, double y, double radius, double phase, WidgetEffects effects, int color) {
        double strength = effects.intensity;
        if (effects.style == Style.ORBITAL) {
            for (int i = 0; i < 15; i++) {
                double at = phase - i * .009;
                luminousArc(
                    x,
                    y,
                    radius,
                    i == 0 ? 2 : 1,
                    at,
                    at + .010,
                    color,
                    strength * Math.pow(1 - i / 15.0, 2),
                    effects);
                luminousArc(
                    x,
                    y,
                    radius - 7,
                    1,
                    .5 - at,
                    .5 - at + .007,
                    theme.warning,
                    strength * .65 * Math.pow(1 - i / 15.0, 2),
                    effects);
            }
        } else if (effects.style == Style.SIGNAL) {
            for (int i = 0; i < 3; i++) {
                double p = (phase + i / 3.0) % 1;
                luminousArc(
                    x,
                    y,
                    radius - 6 + smooth(p) * 11,
                    1,
                    .04,
                    .46,
                    color,
                    cycleEnvelope(p) * strength,
                    effects);
                luminousArc(
                    x,
                    y,
                    radius - 6 + smooth(p) * 11,
                    1,
                    .54,
                    .96,
                    color,
                    cycleEnvelope(p) * strength,
                    effects);
            }
        } else if (effects.style == Style.SEGMENTED) {
            for (int i = 0; i < 32; i++) {
                double pulse = circularPulse(i / 32.0 - phase, .17);
                luminousArc(
                    x,
                    y,
                    radius,
                    1.3 + pulse * 1.7,
                    i / 32.0,
                    (i + .5) / 32,
                    color,
                    strength * (.12 + .78 * pulse),
                    effects);
            }
        } else if (effects.style == Style.PRISM) {
            for (int i = 0; i < 6; i++) {
                double angle = (i / 6.0 + phase) * Math.PI * 2;
                double dx = Math.cos(angle), dy = Math.sin(angle), tx = -dy, ty = dx;
                double px = x + dx * radius, py = y + dy * radius;
                triangle(
                    px + tx * 5,
                    py + ty * 5,
                    px - tx * 5,
                    py - ty * 5,
                    px + dx * 7,
                    py + dy * 7,
                    alpha(color, strength * .65));
                luminousArc(
                    x,
                    y,
                    radius - 3,
                    .8,
                    i / 6.0 + phase - .025,
                    i / 6.0 + phase + .025,
                    color,
                    strength,
                    effects);
            }
        } else if (effects.style == Style.WAVE) {
            for (int i = 0; i < 96; i++) {
                double at = i / 96.0;
                double wave = Math.sin((at * 6 - phase) * Math.PI * 2);
                luminousArc(
                    x,
                    y,
                    radius + wave * 3,
                    .9,
                    at,
                    at + 1 / 96.0,
                    color,
                    strength * (.35 + .35 * (wave + 1) / 2),
                    effects);
            }
        }
    }

    /** Activity marks express a nonzero observed rate, never stock fullness or a made-up target. */
    static void flow(Rect r, boolean active, int direction, double phase, WidgetEffects effects, int color) {
        if (!active || r.w < 10 || effects.intensity == 0) return;
        try (GlScope ignored = new GlScope()) {
            prepare();
            double cursor = direction < 0 ? 1 - phase : phase;
            if (effects.style == Style.SIGNAL) {
                for (int i = 0; i < 3; i++) {
                    double p = (cursor + i / 3.0) % 1;
                    double x = r.x + p * (r.w - 3);
                    quad(x, r.y, x + 2, r.y + r.h, alpha(color, effects.intensity * .8 * edgeEnvelope(p)));
                }
            } else {
                for (int i = 0; i < 7; i++) {
                    double p = (cursor - i * .035 + 1) % 1;
                    double x = r.x + p * (r.w - 5);
                    quad(
                        x,
                        r.y,
                        x + (effects.style == Style.SEGMENTED ? 3 : 5),
                        r.y + r.h,
                        alpha(color, effects.intensity * Math.pow(1 - i / 7.0, 2) * .8 * edgeEnvelope(p)));
                }
            }
        }
    }

    static void status(double x, double y, int state, boolean active, double phase, WidgetEffects effects, int color) {
        try (GlScope ignored = new GlScope()) {
            prepare();
            arc(x, y, 7, 1, 0, 1, theme.edge);
            if (state == 0) {
                arc(x, y, 4, 2, 0, 1, alpha(color, .65));
                if (active) {
                    double radius = 4 + smooth(phase) * 5;
                    luminousArc(x, y, radius, .8, 0, 1, color, cycleEnvelope(phase) * effects.intensity, effects);
                }
            } else if (state == 1) {
                quad(x - 3, y - 1, x + 3, y + 1, color);
                if (active) arc(x, y, 5, 1, phase, phase + .25, alpha(color, effects.intensity));
            } else if (state == 2) {
                quad(
                    x - 3,
                    y - 3,
                    x + 3,
                    y + 3,
                    alpha(color, active ? .7 + .25 * effects.intensity * Math.sin(phase * Math.PI * 2) : .7));
            } else {
                arc(x, y, 4, 2, .06, .32, color);
                arc(x, y, 4, 2, .56, .82, color);
            }
        }
    }

    private static void prepare() {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
    }

    static double smooth(double value) {
        double p = Math.max(0, Math.min(1, value));
        return p * p * (3 - 2 * p);
    }

    /** Zero opacity and zero first derivative at both wrap endpoints. */
    static double cycleEnvelope(double phase) {
        double sine = Math.sin(phase * Math.PI);
        return sine * sine;
    }

    static double edgeEnvelope(double position) {
        return smooth(Math.min(position, 1 - position) / .12);
    }

    private static double circularPulse(double turn, double radius) {
        double distance = Math.abs(turn - Math.floor(turn + .5));
        return smooth(1 - distance / radius);
    }

    private static void triangle(double ax, double ay, double bx, double by, double cx, double cy, int color) {
        color(color);
        GL11.glBegin(GL11.GL_TRIANGLES);
        GL11.glVertex2d(ax, ay);
        GL11.glVertex2d(bx, by);
        GL11.glVertex2d(cx, cy);
        GL11.glEnd();
    }

    /** Bounded alpha layers give a soft halo and a narrow luminous core without framebuffer effects. */
    private static void luminousArc(double x, double y, double radius, double thickness, double from, double to,
        int color, double opacity, WidgetEffects effects) {
        double glow = effects.glow * theme.glowMultiplier;
        if (glow > 0) {
            arc(x, y, radius + 2.5, thickness + 5, from, to, alpha(color, opacity * .055 * glow));
            arc(x, y, radius + 1.2, thickness + 2.4, from, to, alpha(color, opacity * .15 * glow));
        }
        arc(x, y, radius, thickness, from, to, alpha(color, opacity));
        if (glow > 0) arc(
            x,
            y,
            radius - thickness * .3,
            Math.min(.7, thickness * .45),
            from,
            to,
            alpha(theme.text, opacity * .48 * Math.min(1, glow)));
    }

    static void bar(Rect r, Double fraction, int color) {
        try (GlScope ignored = new GlScope()) {
            prepare();
            quad(r.x, r.y, r.x + r.w, r.y + r.h, theme.track);
            if (fraction != null && Double.isFinite(fraction))
                quad(r.x, r.y, r.x + Math.max(0, Math.min(1, fraction)) * r.w, r.y + r.h, color);
        }
    }

    private static int alpha(int color, double opacity) {
        return (color & 0xFFFFFF) | (int) Math.round(255 * Math.max(0, Math.min(1, opacity))) << 24;
    }

    private static void quad(double left, double top, double right, double bottom, int color) {
        if (right <= left || bottom <= top) return;
        color(color);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2d(left, top);
        GL11.glVertex2d(right, top);
        GL11.glVertex2d(right, bottom);
        GL11.glVertex2d(left, bottom);
        GL11.glEnd();
    }

    private static void color(int color) {
        GL11.glColor4f(
            (color >> 16 & 255) / 255f,
            (color >> 8 & 255) / 255f,
            (color & 255) / 255f,
            (color >>> 24) / 255f);
    }

    /** The outer ring is a real fraction, or an unlit track when no denominator is known. */
    static void dial(double x, double y, double radius, Double fraction, int color, WidgetEffects effects) {
        if (radius < 8) return;
        try (GlScope ignored = new GlScope()) {
            prepare();
            double filled = fraction == null || !Double.isFinite(fraction) ? 0 : Math.max(0, Math.min(1, fraction));
            if (filled > 0) luminousArc(x, y, radius, Math.max(3, radius * .065), 0, filled, color, .5, effects);
            for (int i = 0; i < 48; i++) {
                arc(
                    x,
                    y,
                    radius,
                    Math.max(3, radius * .065),
                    i / 48.0,
                    (i + .74) / 48.0,
                    i + .5 <= filled * 48 ? color : theme.track);
                if (i % 4 == 0) arc(x, y, radius + 5, 2, i / 48.0, (i + .18) / 48.0, theme.edge);
            }
            arc(x, y, radius - 9, .8, 0, 1, theme.edge);
            arc(x, y, radius - 13, .6, .10, .40, theme.track);
            arc(x, y, radius - 13, .6, .60, .90, theme.track);
        }
    }

    private static void arc(double x, double y, double radius, double thickness, double from, double to, int color) {
        GL11.glColor4f(
            (color >> 16 & 255) / 255f,
            (color >> 8 & 255) / 255f,
            (color & 255) / 255f,
            (color >>> 24) / 255f);
        int segments = Math.max(2, (int) Math.ceil((to - from) * 128));
        GL11.glBegin(GL11.GL_QUAD_STRIP);
        for (int i = 0; i <= segments; i++) {
            double angle = -Math.PI / 2 + Math.PI * 2 * (from + (to - from) * i / segments);
            double dx = Math.cos(angle), dy = Math.sin(angle);
            GL11.glVertex2d(x + dx * radius, y + dy * radius);
            GL11.glVertex2d(x + dx * (radius - thickness), y + dy * (radius - thickness));
        }
        GL11.glEnd();
    }

    static void bars(Rect r, double[] values, long[] ticks, double zero, int color, boolean quiet, double phase,
        WidgetEffects effects) {
        bars(r, new FactoryChartGeometry.Bars(values, ticks, r.w), zero, color, quiet, phase, effects);
    }

    static void bars(Rect r, FactoryChartGeometry.Bars bars, double zero, int color, boolean quiet, double phase,
        WidgetEffects effects) {
        try (GlScope ignored = new GlScope()) {
            prepare();
            double baseline = r.y + (1 - Math.max(0, Math.min(1, zero))) * r.h;
            for (int i = 0; i < bars.x.length; i++) {
                if (!Double.isFinite(bars.low[i])) continue;
                double x = r.x + bars.x[i], half = bars.halfWidth;
                double top = r.y + (1 - Math.max(0, Math.min(1, Math.max(zero, bars.high[i])))) * r.h;
                double bottom = r.y + (1 - Math.max(0, Math.min(1, Math.min(zero, bars.low[i])))) * r.h;
                double opacity = quiet ? .23 : .68;
                quad(x - half, top, x + half, Math.min(r.y + r.h, Math.max(top + .7, bottom)), alpha(color, opacity));
                // A fixed edge marks the observed extrema; it does not animate the measured height.
                double glow = effects.glow * theme.glowMultiplier;
                for (int edgeIndex = 0; edgeIndex < 2; edgeIndex++) {
                    double edge = edgeIndex == 0 ? top : bottom;
                    if (edge == baseline && top != bottom) continue;
                    quad(
                        x - half,
                        Math.max(r.y, edge - 1.5),
                        x + half,
                        Math.min(r.y + r.h, edge + 2),
                        alpha(color, .10 * glow));
                    quad(
                        x - half,
                        Math.max(r.y, edge - .4),
                        x + half,
                        Math.min(r.y + r.h, edge + .5),
                        alpha(color, quiet ? .58 : .95));
                }
                if (effects.animated && effects.intensity > 0 && Math.abs(top - bottom) >= 2) {
                    double p = (phase + i * .07) % 1;
                    double cursor = top + (bottom - top) * p;
                    quad(
                        x - half,
                        cursor,
                        x + half,
                        Math.min(bottom, cursor + .8),
                        alpha(effectAccent(effects), effects.intensity * .7 * cycleEnvelope(p)));
                }
            }
        }
    }

    /** Refined line layers use precisely the same timestamps and missing-sample breaks. */
    static void trend(Rect r, double[] values, long[] ticks, int color, WidgetEffects effects) {
        if (values == null || values.length == 0) return;
        try (GlScope ignored = new GlScope()) {
            prepare();
            GL11.glEnable(GL11.GL_LINE_SMOOTH);
            double glow = effects.glow * theme.glowMultiplier;
            for (int layer = 2; layer >= 0; layer--) {
                if (layer > 0 && glow == 0) continue;
                GL11.glLineWidth(layer == 2 ? 4 : layer == 1 ? 2.3f : 1.15f);
                color(alpha(color, layer == 2 ? .065 * glow : layer == 1 ? .21 * glow : .95));
                GL11.glBegin(GL11.GL_LINES);
                for (int i = 1; i < values.length; i++) {
                    if (!Double.isFinite(values[i - 1]) || !Double.isFinite(values[i])) continue;
                    for (int j = i - 1; j <= i; j++) GL11.glVertex2d(
                        r.x + FactoryChartGeometry.timeX(FactoryTrend.timePosition(j, values.length, ticks), r.w),
                        r.y + (1 - values[j]) * r.h);
                }
                GL11.glEnd();
            }
            GL11.glPointSize(3);
            color(color);
            GL11.glBegin(GL11.GL_POINTS);
            for (int i = 0; i < values.length; i++) {
                if (!Double.isFinite(values[i])) continue;
                boolean isolated = (i == 0 || !Double.isFinite(values[i - 1]))
                    && (i == values.length - 1 || !Double.isFinite(values[i + 1]));
                if (isolated) GL11.glVertex2d(
                    r.x + FactoryChartGeometry.timeX(FactoryTrend.timePosition(i, values.length, ticks), r.w),
                    r.y + (1 - values[i]) * r.h);
            }
            GL11.glEnd();
        }
    }
}
