package com.miaokatze.gtswn.client.engineering;

import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.client.engineering.desktop.DashboardTheme;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetEffects;
import com.miaokatze.miaokatzecore.client.render.GlScope;

/** Uses the dashboard's existing arc geometry and Core GL state guard. */
final class DashboardClockDrawing {

    private DashboardClockDrawing() {}

    static void draw(ClockFaceLayout face, DashboardClock clock, DashboardTheme theme, WidgetEffects effects) {
        double radius = face.radius, x = face.centerX, y = face.centerY;
        if (radius < 12) return;
        try (GlScope ignored = new GlScope()) {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            FactoryDashboardArt.arc(x, y, radius, 1.5, 0, 1, theme.edge);
            for (int mark = 0; mark < 60; mark++) {
                boolean hour = mark % 5 == 0;
                stroke(
                    x,
                    y,
                    mark / 60.0,
                    radius - (hour ? 9 : 5),
                    radius - 3,
                    hour ? 2 : 1,
                    hour ? theme.text : theme.muted);
            }
            if (clock == null) return;
            stroke(x, y, clock.hour, -3, radius * .48, 3.4, theme.text);
            int accent = FactoryDashboardArt.readableAccent(
                effects.accentColor == WidgetEffects.DEFAULT.accentColor ? theme.accent : effects.accentColor);
            stroke(x, y, clock.minute, -4, radius * .72, 2.3, accent);
            stroke(x, y, clock.second, -radius * .16, radius * .80, 1.1, theme.warning);
            FactoryDashboardArt.arc(x, y, 3, 3, 0, 1, theme.text);
        }
    }

    private static void stroke(double x, double y, double turn, double from, double to, double width, int color) {
        double angle = turn * Math.PI * 2 - Math.PI / 2;
        double dx = Math.cos(angle), dy = Math.sin(angle);
        double px = -dy * width / 2, py = dx * width / 2;
        GL11.glColor4f((color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f, (color & 255) / 255f, 1);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2d(x + dx * from + px, y + dy * from + py);
        GL11.glVertex2d(x + dx * to + px, y + dy * to + py);
        GL11.glVertex2d(x + dx * to - px, y + dy * to - py);
        GL11.glVertex2d(x + dx * from - px, y + dy * from - py);
        GL11.glEnd();
    }
}
