package com.miaokatze.gtswn.client.engineering;

import com.miaokatze.gtswn.client.engineering.WorkbenchLayout.Rect;

/** Responsive geometry, independent of Minecraft and shared with dashboard hit testing. */
final class FactoryDashboardLayout {

    final Rect[] kpis = new Rect[4];
    final Rect trend, region, resources, attention;
    final int contentHeight;

    FactoryDashboardLayout(int width, int preset, boolean wide, boolean reverse) {
        int gap = 8, columns = width >= 540 && wide ? 4 : 2;
        int cardWidth = (width - gap * (columns - 1)) / columns;
        for (int i = 0; i < kpis.length; i++)
            kpis[i] = new Rect((i % columns) * (cardWidth + gap), (i / columns) * 60, cardWidth, 52);
        int y = (4 / columns) * 60;
        boolean split = width >= 540 && wide;
        if (preset == 1 && split) {
            int resourceWidth = (width - gap) * 3 / 5;
            Rect major = new Rect(0, y, resourceWidth, 260);
            Rect minor = new Rect(resourceWidth + gap, y + 176, width - resourceWidth - gap, 84);
            resources = reverse ? minor : major;
            attention = reverse ? major : minor;
            trend = new Rect(resourceWidth + gap, y, width - resourceWidth - gap, 100);
            region = new Rect(resourceWidth + gap, y + 108, width - resourceWidth - gap, 60);
            contentHeight = y + 260;
            return;
        }
        int left = split ? (width - gap) * (preset == 1 ? 3 : 2) / (preset == 1 ? 5 : 3) : width;
        int trendHeight = preset == 2 ? 124 : 100;
        trend = new Rect(0, y, left, trendHeight);
        region = split ? new Rect(left + gap, y, width - left - gap, trendHeight)
            : new Rect(0, y + trendHeight + gap, width, 60);
        y += split ? trendHeight + gap : trendHeight + 76;
        int bottomLeft = split ? (width - gap) * 2 / 3 : width;
        int listHeight = preset == 1 ? 170 : 84;
        Rect first = new Rect(0, y, bottomLeft, listHeight);
        Rect second = split ? new Rect(bottomLeft + gap, y, width - bottomLeft - gap, listHeight)
            : new Rect(0, y + listHeight + gap, width, 106);
        resources = reverse ? second : first;
        attention = reverse ? first : second;
        contentHeight = Math.max(resources.y + resources.h, attention.y + attention.h);
    }
}
