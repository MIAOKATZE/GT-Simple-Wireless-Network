package com.miaokatze.gtswn.client.engineering;

import java.util.Locale;

/** Local, bounded text filter. Never changes server sampling or authorization. */
final class FactoryTextSearch {

    String value = "";
    boolean focused;

    boolean matches(String text) {
        return value.isEmpty() || text.toLowerCase(Locale.ROOT)
            .contains(value.toLowerCase(Locale.ROOT));
    }

    void character(char character) {
        if (character >= 32 && character != 127 && value.length() < 96) value += character;
    }

    void backspace() {
        if (!value.isEmpty()) value = value.substring(0, value.length() - 1);
    }
}
