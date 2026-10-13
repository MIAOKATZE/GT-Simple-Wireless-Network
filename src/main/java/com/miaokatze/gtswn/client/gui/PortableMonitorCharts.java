package com.miaokatze.gtswn.client.gui;

import net.minecraft.client.gui.FontRenderer;

import com.miaokatze.gtswn.client.PortableMonitorClientCache.PinnedLine;

/** Simple HUD chart deliberately differs from the full information-screen canvas. */
public final class PortableMonitorCharts {

    private PortableMonitorCharts() {}

    public static void drawCompact(PinnedLine line, int x, int y, int width, int height, FontRenderer font) {
        font.drawStringWithShadow(font.trimStringToWidth(line.text, width), x, y, 0xffffff);
        if (!line.currentText.isEmpty())
            font.drawStringWithShadow(font.trimStringToWidth(line.currentText, width), x, y + 10, 0xffffff);
        int color = line.change > 0 ? 0x60dc83 : line.change < 0 ? 0xf16f68 : 0x57d8ca;
        int plotTop = line.currentText.isEmpty() ? 14 : 24;
        GuiPortableMonitor.drawChart(
            line.chart,
            x + 2,
            y + plotTop,
            Math.max(1, width - 4),
            Math.max(1, height - plotTop - 6),
            color);
        GuiPortableMonitor.drawChart(
            line.rateChart,
            x + 2,
            y + plotTop,
            Math.max(1, width - 4),
            Math.max(1, height - plotTop - 6),
            0xffa551);
    }
}
