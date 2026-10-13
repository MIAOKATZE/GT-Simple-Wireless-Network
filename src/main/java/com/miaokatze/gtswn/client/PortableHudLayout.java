package com.miaokatze.gtswn.client;

import java.util.ArrayList;
import java.util.List;

import com.miaokatze.gtswn.client.PortableMonitorClientCache.PinnedLine;

/** Each subscription keeps its own box; plots never widen or raise text rows. */
public final class PortableHudLayout {

    private PortableHudLayout() {}

    public static int height(PinnedLine line, int renderMode) {
        return line.graph ? 48 : renderMode == 1 ? 16 : 12;
    }

    public static int width(PinnedLine line, int textWidth, int maximum) {
        return line.graph ? maximum : Math.min(maximum, Math.max(1, textWidth));
    }

    public static List<List<PinnedLine>> pages(List<PinnedLine> lines, int rows, int availableHeight, int renderMode) {
        List<List<PinnedLine>> pages = new ArrayList<>();
        List<PinnedLine> page = new ArrayList<>();
        int used = 0;
        for (PinnedLine line : lines) {
            int height = height(line, renderMode);
            if (height > availableHeight) continue;
            if (!page.isEmpty() && (page.size() >= rows || used + height > availableHeight)) {
                pages.add(page);
                page = new ArrayList<>();
                used = 0;
            }
            page.add(line);
            used += height;
        }
        if (!page.isEmpty()) pages.add(page);
        if (pages.isEmpty()) pages.add(new ArrayList<>());
        return pages;
    }
}
