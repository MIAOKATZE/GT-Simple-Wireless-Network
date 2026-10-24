package com.miaokatze.gtswn.client.engineering;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;

import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.client.engineering.WorkbenchLayout.Rect;

/** Small, data-only instruments. Unknown values leave the instrument track unfilled. */
final class DashboardVisuals extends Gui {

    static final int BACK = 0xFF101F32, CARD = 0xD51B344B, HEADER = 0x962B4D63;
    static final int EDGE = 0xFF345B72, TEXT = 0xFFE1F2F5, MUTED = 0xFF90AEBF;
    static final int CYAN = 0xFF68E1E8, AMBER = 0xFFF3BF74, TRACK = 0xFF25465D;

    private DashboardVisuals() {}

    static void label(FontRenderer font, String text, int x, int y, int width, int color, float scale) {
        if (width < 2 || text == null || text.isEmpty()) return;
        GL11.glPushMatrix();
        try {
            GL11.glTranslatef(x, y, 0);
            GL11.glScalef(scale, scale, 1);
            font.drawString(font.trimStringToWidth(text, Math.max(1, (int) (width / scale))), 0, 0, color);
        } finally {
            GL11.glPopMatrix();
        }
    }

    static void bar(Rect r, Double fraction, int color) {
        if (r.w <= 0 || r.h <= 0) return;
        drawRect(r.x, r.y, r.x + r.w, r.y + r.h, TRACK);
        if (fraction == null || !Double.isFinite(fraction)) return;
        int pixels = (int) Math.round(Math.max(0, Math.min(1, fraction)) * r.w);
        if (pixels > 0) drawRect(r.x, r.y, r.x + pixels, r.y + r.h, color);
    }

    static void ring(double x, double y, double radius, Double fraction, int color) {
        if (radius < 3) return;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            arc(x, y, radius, Math.max(1.5, radius * .06), 1, TRACK);
            arc(x, y, radius + 3, .6, 1, EDGE);
            if (fraction != null && Double.isFinite(fraction))
                arc(x, y, radius, Math.max(1.5, radius * .06), Math.max(0, Math.min(1, fraction)), color);
        } finally {
            GL11.glPopAttrib();
        }
    }

    private static void arc(double x, double y, double radius, double thickness, double fraction, int color) {
        if (fraction <= 0) return;
        GL11.glColor4f((color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f, (color & 255) / 255f, 1);
        int segments = Math.max(1, (int) Math.ceil(96 * fraction));
        GL11.glBegin(GL11.GL_QUAD_STRIP);
        for (int i = 0; i <= segments; i++) {
            double angle = -Math.PI / 2 + Math.PI * 2 * fraction * i / segments;
            double dx = Math.cos(angle), dy = Math.sin(angle);
            GL11.glVertex2d(x + dx * radius, y + dy * radius);
            GL11.glVertex2d(x + dx * (radius - thickness), y + dy * (radius - thickness));
        }
        GL11.glEnd();
    }

    /** Join only adjacent finite samples; missing samples remain visible gaps. */
    static void trend(Rect r, double[] values, long[] ticks, int color) {
        if (values == null || values.length == 0 || r.w < 2 || r.h < 2) return;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_LINE_BIT | GL11.GL_POINT_BIT);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glColor4f((color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f, (color & 255) / 255f, 1);
            GL11.glLineWidth(1);
            GL11.glBegin(GL11.GL_LINES);
            for (int i = 1; i < values.length; i++) {
                if (!Double.isFinite(values[i - 1]) || !Double.isFinite(values[i])) continue;
                for (int j = i - 1; j <= i; j++) {
                    double position = FactoryTrend.timePosition(j, values.length, ticks);
                    GL11.glVertex2d(r.x + position * r.w, r.y + (1 - values[j]) * r.h);
                }
            }
            GL11.glEnd();
            // A single sample (including a point between missing neighbors) is evidence, not a line.
            GL11.glPointSize(3);
            GL11.glBegin(GL11.GL_POINTS);
            for (int i = 0; i < values.length; i++) {
                if (!Double.isFinite(values[i])) continue;
                boolean isolated = (i == 0 || !Double.isFinite(values[i - 1]))
                    && (i == values.length - 1 || !Double.isFinite(values[i + 1]));
                if (isolated) GL11.glVertex2d(
                    r.x + FactoryTrend.timePosition(i, values.length, ticks) * r.w,
                    r.y + (1 - values[i]) * r.h);
            }
            GL11.glEnd();
        } finally {
            GL11.glPopAttrib();
        }
    }
}
