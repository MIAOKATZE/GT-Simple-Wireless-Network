package com.miaokatze.gtswn.client.engineering;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.drawable.GuiDraw;
import com.miaokatze.gtswn.client.engineering.WorkbenchLayout.Rect;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetEffects;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetEffects.Style;
import com.miaokatze.miaokatzecore.client.render.GlScope;

/** Scoped drawing primitives; the desktop owns transform, clipping, data and input. */
final class FactoryDashboardArt {

    private FactoryDashboardArt() {}

    static void card(Rect r, WidgetEffects effects, double phase) {
        try (GlScope ignored = new GlScope()) {
            int top = effects.style == Style.SEGMENTED ? 0xFF202E3A : 0xFF102B43;
            GuiDraw.drawVerticalGradientRect(r.x, r.y, r.w, r.h, top, 0xFF091C30);
            prepare();
            int grid = alpha(effects.accentColor, .055 * effects.intensity);
            int step = effects.style == Style.SEGMENTED ? 16 : 24;
            for (int x = r.x + step; x < r.x + r.w; x += step) quad(x, r.y + 23, x + .6, r.y + r.h - 2, grid);
            for (int y = r.y + 24; y < r.y + r.h; y += step) quad(r.x + 2, y, r.x + r.w - 2, y + .6, grid);
            if (effects.style == Style.SIGNAL) {
                double y = r.y + 25 + phase * Math.max(0, r.h - 29);
                quad(r.x + 2, y, r.x + r.w - 2, y + 1, alpha(effects.accentColor, .16 * effects.intensity));
                quad(r.x + 2, y + 1, r.x + r.w - 2, Math.min(r.y + r.h - 2, y + 7), grid);
            } else if (effects.style == Style.SEGMENTED) {
                for (int x = 5; x < r.w - 8; x += 12) {
                    double glow = Math.abs((x / (double) r.w) - phase) < .09 ? .55 : .12;
                    quad(
                        r.x + x,
                        r.y + r.h - 4,
                        r.x + x + 7,
                        r.y + r.h - 2,
                        alpha(effects.accentColor, glow * effects.intensity));
                }
            }
        }
    }

    static void header(Rect r, WidgetEffects effects, double phase) {
        try (GlScope ignored = new GlScope()) {
            prepare();
            double x = r.x + 1 + phase * Math.max(1, r.w - 26);
            quad(
                x,
                r.y + 1,
                Math.min(r.x + r.w - 1, x + 25),
                r.y + 2,
                alpha(effects.accentColor, .7 * effects.intensity));
        }
    }

    /** Symmetric ornamental brackets have no start/end fill or percentage semantics. */
    static void stockDial(double x, double y, double radius, boolean known, double phase, WidgetEffects effects,
        int color) {
        try (GlScope ignored = new GlScope()) {
            prepare();
            for (int i = 0; i < 60; i++)
                arc(x, y, radius + 5, i % 5 == 0 ? 3 : 1.4, i / 60.0, (i + .16) / 60.0, DashboardVisuals.EDGE);
            for (int i = 0; i < 3; i++) {
                double from = i / 3.0 + .04 + phase;
                arc(
                    x,
                    y,
                    radius,
                    Math.max(3, radius * .045),
                    from,
                    from + .23,
                    known ? alpha(color, .8) : DashboardVisuals.TRACK);
                double innerFrom = innerOrbitStart(i, phase);
                arc(x, y, radius - 6, .9, innerFrom, innerFrom + .23, DashboardVisuals.EDGE);
            }
            arc(x, y, radius - 13, .7, 0, 1, DashboardVisuals.EDGE);
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
                arc(x, y, radius, i == 0 ? 3 : 2, at, at + .010, alpha(color, strength * (1 - i / 15.0)));
                arc(
                    x,
                    y,
                    radius - 7,
                    1,
                    .5 - at,
                    .5 - at + .007,
                    alpha(DashboardVisuals.AMBER, strength * .65 * (1 - i / 15.0)));
            }
        } else if (effects.style == Style.SIGNAL) {
            for (int i = 0; i < 3; i++) {
                double p = (phase + i / 3.0) % 1;
                arc(x, y, radius - 6 + p * 11, 1.3, .04, .46, alpha(color, (1 - p) * strength));
                arc(x, y, radius - 6 + p * 11, 1.3, .54, .96, alpha(color, (1 - p) * strength));
            }
        } else {
            for (int i = 0; i < 32; i++) {
                double distance = (i / 32.0 - phase + 1) % 1;
                arc(
                    x,
                    y,
                    radius,
                    distance < .125 ? 4 : 2,
                    i / 32.0,
                    (i + .5) / 32,
                    alpha(color, strength * (distance < .125 ? .9 : .14)));
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
                    double x = r.x + ((cursor + i / 3.0) % 1) * (r.w - 3);
                    quad(x, r.y, x + 2, r.y + r.h, alpha(color, effects.intensity * .8));
                }
            } else {
                for (int i = 0; i < 7; i++) {
                    double x = r.x + ((cursor - i * .035 + 1) % 1) * (r.w - 5);
                    quad(
                        x,
                        r.y,
                        x + (effects.style == Style.SEGMENTED ? 3 : 5),
                        r.y + r.h,
                        alpha(color, effects.intensity * (1 - i / 7.0) * .8));
                }
            }
        }
    }

    static void status(double x, double y, int state, boolean active, double phase, WidgetEffects effects, int color) {
        try (GlScope ignored = new GlScope()) {
            prepare();
            arc(x, y, 7, 1, 0, 1, DashboardVisuals.EDGE);
            if (state == 0) {
                arc(x, y, 4, 2, 0, 1, alpha(color, .65));
                if (active) {
                    double radius = 4 + phase * 5;
                    arc(x, y, radius, 1, 0, 1, alpha(color, (1 - phase) * effects.intensity));
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
    static void dial(double x, double y, double radius, Double fraction, int color) {
        if (radius < 8) return;
        try (GlScope ignored = new GlScope()) {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            double filled = fraction == null || !Double.isFinite(fraction) ? 0 : Math.max(0, Math.min(1, fraction));
            for (int i = 0; i < 48; i++) {
                arc(
                    x,
                    y,
                    radius,
                    Math.max(3, radius * .065),
                    i / 48.0,
                    (i + .74) / 48.0,
                    i + .5 <= filled * 48 ? color : DashboardVisuals.TRACK);
                if (i % 4 == 0) arc(x, y, radius + 5, 2, i / 48.0, (i + .18) / 48.0, DashboardVisuals.EDGE);
            }
            arc(x, y, radius - 9, .8, 0, 1, DashboardVisuals.EDGE);
            arc(x, y, radius - 13, .6, .10, .40, DashboardVisuals.TRACK);
            arc(x, y, radius - 13, .6, .60, .90, DashboardVisuals.TRACK);
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
        try (GlScope ignored = new GlScope()) {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(
                (color >> 16 & 255) / 255f,
                (color >> 8 & 255) / 255f,
                (color & 255) / 255f,
                quiet ? .24f : .72f);
            double baseline = r.y + (1 - Math.max(0, Math.min(1, zero))) * r.h;
            GL11.glBegin(GL11.GL_QUADS);
            for (int i = 0; i < values.length; i++) {
                if (!Double.isFinite(values[i])) continue;
                double x = r.x + FactoryTrend.timePosition(i, values.length, ticks) * r.w;
                double half = FactoryChartGeometry.barHalfWidth(i, values.length, ticks, r.w);
                if (half <= 0) continue;
                double left = Math.max(r.x, x - half), right = Math.min(r.x + r.w, x + half);
                double top = r.y + (1 - Math.max(0, Math.min(1, values[i]))) * r.h;
                GL11.glVertex2d(left, baseline);
                GL11.glVertex2d(right, baseline);
                GL11.glVertex2d(right, top);
                GL11.glVertex2d(left, top);
            }
            GL11.glEnd();
            if (effects.animated && effects.intensity > 0) for (int i = 0; i < values.length; i++) {
                if (!Double.isFinite(values[i])) continue;
                double x = r.x + FactoryTrend.timePosition(i, values.length, ticks) * r.w;
                double half = FactoryChartGeometry.barHalfWidth(i, values.length, ticks, r.w);
                double top = r.y + (1 - Math.max(0, Math.min(1, values[i]))) * r.h;
                double cursor = baseline + (top - baseline) * ((phase + i * .07) % 1);
                if (half > 0 && Math.abs(top - baseline) >= 2) quad(
                    Math.max(r.x, x - half),
                    cursor,
                    Math.min(r.x + r.w, x + half),
                    Math.min(Math.max(top, baseline), cursor + 1),
                    alpha(effects.accentColor, effects.intensity * .9));
            }
        }
    }
}
