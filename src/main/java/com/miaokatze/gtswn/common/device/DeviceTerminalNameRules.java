package com.miaokatze.gtswn.common.device;

/** Plain alias text and separately validated Minecraft color/bold styles. */
public final class DeviceTerminalNameRules {

    public static final int MAX_NAME_LENGTH = 48;
    public static final int DEFAULT_COLOR = 15;

    private DeviceTerminalNameRules() {}

    public static String normalize(String name) {
        if (name == null || name.length() > MAX_NAME_LENGTH) return null;
        for (int i = 0; i < name.length();) {
            char c = name.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= name.length() || !Character.isLowSurrogate(name.charAt(i + 1))) return null;
            } else if (Character.isLowSurrogate(c)) return null;
            int point = name.codePointAt(i);
            int type = Character.getType(point);
            if (point == 0x00a7 || Character.isISOControl(point)
                || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR) return null;
            i += Character.charCount(point);
        }
        return name.trim();
    }

    public static boolean validColor(int color) {
        return color >= 0 && color <= 15;
    }
}
