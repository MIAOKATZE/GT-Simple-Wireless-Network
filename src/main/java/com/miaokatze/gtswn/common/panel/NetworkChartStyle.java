package com.miaokatze.gtswn.common.panel;

/** Stable persisted IDs; old or invalid configuration keeps the original line chart. */
public enum NetworkChartStyle {

    LINE,
    BAR,
    COMBINED;

    public static NetworkChartStyle fromId(int id) {
        return id >= 0 && id < values().length ? values()[id] : LINE;
    }

    public static NetworkChartStyle fromText(String text) {
        try {
            return fromId(Integer.parseInt(text));
        } catch (NumberFormatException ignored) {
            return LINE;
        }
    }
}
