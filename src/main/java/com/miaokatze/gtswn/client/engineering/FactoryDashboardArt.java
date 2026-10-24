package com.miaokatze.gtswn.client.engineering;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.drawable.GuiDraw;
import com.miaokatze.gtswn.client.engineering.WorkbenchLayout.Rect;
import com.miaokatze.miaokatzecore.client.render.GlScope;

/** Scoped drawing primitives; the desktop owns transform, clipping, data and input. */
final class FactoryDashboardArt {

    private FactoryDashboardArt() {}

    static void card(Rect r) {
        try (GlScope ignored = new GlScope()) {
            GuiDraw.drawVerticalGradientRect(r.x, r.y, r.w, r.h, 0xFF102B43, 0xFF091C30);
        }
    }

    /** Symmetric ornamental brackets have no start/end fill or percentage semantics. */
    static void stockDial(double x, double y, double radius, boolean known, int color) {
        try (GlScope ignored = new GlScope()) {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            for (int i = 0; i < 60; i++)
                arc(x, y, radius + 5, i % 5 == 0 ? 3 : 1.4, i / 60.0, (i + .16) / 60.0, DashboardVisuals.EDGE);
            for (int i = 0; i < 3; i++) {
                double from = i / 3.0 + .04;
                arc(x, y, radius, Math.max(3, radius * .045), from, from + .23, known ? color : DashboardVisuals.TRACK);
                arc(x, y, radius - 5, .7, from, from + .23, DashboardVisuals.EDGE);
            }
            arc(x, y, radius - 13, .7, 0, 1, DashboardVisuals.EDGE);
            arc(x, y, radius - 17, .7, .08, .42, DashboardVisuals.TRACK);
            arc(x, y, radius - 17, .7, .58, .92, DashboardVisuals.TRACK);
        }
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

    static void bars(Rect r, double[] values, long[] ticks, double zero, int color, boolean quiet) {
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
        }
    }
}
