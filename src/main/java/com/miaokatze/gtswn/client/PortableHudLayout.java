package com.miaokatze.gtswn.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.miaokatze.gtswn.client.PortableMonitorClientCache.PinnedLine;

/** Each subscription keeps its own box; plots never widen or raise text rows. */
public final class PortableHudLayout {

    /** Subscription families are explicit; translated text and screen numbers never determine grouping. */
    public enum Group {

        NONE(null),
        EU_TREND("gtswn.hud.group.eu_trend"),
        AE_TREND("gtswn.hud.group.ae_trend"),
        AE_STOCK("gtswn.hud.group.ae_stock");

        public final String labelKey;

        Group(String labelKey) {
            this.labelKey = labelKey;
        }

        public boolean heading() {
            return this == EU_TREND || this == AE_TREND;
        }
    }

    private PortableHudLayout() {}

    public static int height(PinnedLine line, int renderMode) {
        return line.graph ? 48 : renderMode == 1 ? 16 : 12;
    }

    public static int width(PinnedLine line, int textWidth, int maximum) {
        return line.graph ? maximum : Math.min(maximum, Math.max(1, textWidth));
    }

    public static int iconColumn(PinnedLine line, int renderMode) {
        return line.icon == null ? 0 : height(line, renderMode) + 2;
    }

    public static int width(PinnedLine line, int textWidth, int maximum, int renderMode) {
        return width(line, textWidth + iconColumn(line, renderMode), maximum);
    }

    public static List<List<PinnedLine>> pages(List<PinnedLine> lines, int rows, int availableHeight, int renderMode) {
        return pages(lines, rows, availableHeight, renderMode, "", "");
    }

    public static List<List<PinnedLine>> pages(List<PinnedLine> lines, int rows, int availableHeight, int renderMode,
        String euHeading, String aeHeading) {
        List<List<PinnedLine>> pages = new ArrayList<>();
        List<PinnedLine> page = new ArrayList<>();
        List<List<PinnedLine>> units = units(lines);
        int used = 0, subscriptions = 0;
        Group heading = Group.NONE;
        for (List<PinnedLine> unit : units) {
            Group group = unit.get(0).group;
            int height = 0;
            for (PinnedLine line : unit) height += height(line, renderMode);
            boolean addHeading = group.heading() && heading != group;
            int headingHeight = addHeading ? textHeight(renderMode) : 0;
            if (!page.isEmpty()
                && (subscriptions >= Math.max(1, rows) || used + height + headingHeight > availableHeight)) {
                pages.add(page);
                page = new ArrayList<>();
                used = 0;
                subscriptions = 0;
                heading = Group.NONE;
                addHeading = group.heading();
                headingHeight = addHeading ? textHeight(renderMode) : 0;
            }
            if (addHeading) {
                page.add(
                    new PinnedLine(
                        group == Group.EU_TREND ? euHeading : aeHeading,
                        (com.miaokatze.gtswn.network.PortableMonitorIcon) null));
                used += headingHeight;
                heading = group;
            }
            // An oversized unit remains intact; the HUD scales to requiredHeight before pagination.
            page.addAll(unit);
            used += height;
            subscriptions++;
        }
        if (!page.isEmpty()) pages.add(page);
        if (pages.isEmpty()) pages.add(new ArrayList<>());
        return pages;
    }

    /** Minimum space required for one indivisible source, including its family heading. */
    public static int requiredHeight(List<PinnedLine> lines, int renderMode) {
        int largest = 0;
        for (List<PinnedLine> unit : units(lines)) {
            int height = unit.get(0).group.heading() ? textHeight(renderMode) : 0;
            for (PinnedLine line : unit) height += height(line, renderMode);
            largest = Math.max(largest, height);
        }
        return largest;
    }

    private static int textHeight(int renderMode) {
        return renderMode == 1 ? 16 : 12;
    }

    private static List<List<PinnedLine>> units(List<PinnedLine> lines) {
        List<List<PinnedLine>> units = new ArrayList<>();
        for (Group group : Group.values()) {
            Map<String, List<PinnedLine>> sources = new LinkedHashMap<>();
            for (PinnedLine line : lines) {
                if (line.group != group) continue;
                if (group.heading()) sources.computeIfAbsent(line.source, ignored -> new ArrayList<>())
                    .add(line);
                else {
                    List<PinnedLine> single = new ArrayList<>();
                    single.add(line);
                    units.add(single);
                }
            }
            units.addAll(sources.values());
        }
        return units;
    }
}
