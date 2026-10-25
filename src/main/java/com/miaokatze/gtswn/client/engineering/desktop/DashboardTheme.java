package com.miaokatze.gtswn.client.engineering.desktop;

/** Desktop material palette, independent of widgets, bindings and the global MUI screen theme. */
public enum DashboardTheme {

    STANDARD(0xFF101F32, 0xFF102B43, 0xFF091C30, 0xFF234257, 0xFF345B72, 0xFFE1F2F5, 0xFF90AEBF, 0xFF68E1E8, 0xFFF3BF74,
        0xFF19384D, 0xFF25465D, 0xFF67DEE5, Pattern.SMOOTH, 1),
    RETRO(0xFFD3B684, 0xFFDFC697, 0xFFD9BE8B, 0xFFC5A471, 0xFF8C6B41, 0xFF33271B, 0xFF59422D, 0xFF254B2B, 0xFF7C261F,
        0xFFB89A67, 0xFFC4A675, 0xFF144D51, Pattern.BLUEPRINT, .65),
    INDUSTRIAL(0xFF282D33, 0xFF3F444B, 0xFF393E45, 0xFF30353C, 0xFF77756C, 0xFFF0E8D8, 0xFF9AA0A8, 0xFF4CE08A,
        0xFFF0A028, 0xFF4A5058, 0xFF23272D, 0xFFF0A028, Pattern.METAL, .75),
    MINIMAL(0xFFF0F1ED, 0xFFFFFFFF, 0xFFF8F9F6, 0xFFE6EAE5, 0xFF9DA9A1, 0xFF263C35, 0xFF63766C, 0xFF267854, 0xFF9B6220,
        0xFFDCE2DA, 0xFFDEE5DE, 0xFF35836A, Pattern.PAPER, .35);

    public enum Pattern {
        SMOOTH,
        BLUEPRINT,
        METAL,
        PAPER
    }

    public final int background, cardTop, cardBottom, header, edge, text, muted, positive, warning, grid, track, accent;
    public final Pattern pattern;
    public final double glowMultiplier;

    DashboardTheme(int background, int cardTop, int cardBottom, int header, int edge, int text, int muted, int positive,
        int warning, int grid, int track, int accent, Pattern pattern, double glowMultiplier) {
        this.background = background;
        this.cardTop = cardTop;
        this.cardBottom = cardBottom;
        this.header = header;
        this.edge = edge;
        this.text = text;
        this.muted = muted;
        this.positive = positive;
        this.warning = warning;
        this.grid = grid;
        this.track = track;
        this.accent = accent;
        this.pattern = pattern;
        this.glowMultiplier = glowMultiplier;
    }

    /** Material interpolation keeps shading within the selected palette. */
    public static int mix(int first, int second, double fraction) {
        double amount = Math.max(0, Math.min(1, fraction));
        int r = (int) Math.round((first >> 16 & 255) * (1 - amount) + (second >> 16 & 255) * amount);
        int g = (int) Math.round((first >> 8 & 255) * (1 - amount) + (second >> 8 & 255) * amount);
        int b = (int) Math.round((first & 255) * (1 - amount) + (second & 255) * amount);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    public String translationKey() {
        return "gtswn.desktop.theme." + name().toLowerCase(java.util.Locale.ROOT);
    }

    public DashboardTheme next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
