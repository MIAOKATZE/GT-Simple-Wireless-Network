package com.miaokatze.gtswn.common.gui;

import net.minecraft.util.ChatAllowedCharacters;

/** Vanilla anvil name limits and allowed characters, shared by the editor and the server. */
public final class TerminalNameRules {

    public static final int MAX_LENGTH = 40;

    private TerminalNameRules() {}

    public static String normalize(String name) {
        if (name == null || name.length() > MAX_LENGTH) return null;
        String filtered = ChatAllowedCharacters.filerAllowedCharacters(name);
        for (int i = 0; i < filtered.length(); i++) {
            if (!Character.isWhitespace(filtered.charAt(i))) return filtered;
        }
        return "";
    }
}
