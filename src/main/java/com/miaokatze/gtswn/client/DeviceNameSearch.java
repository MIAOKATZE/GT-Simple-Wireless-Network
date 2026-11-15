// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.client;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import me.majiajie.tinypinyin.Pinyin;

/** Local name aliases only; never reads machines, polls a terminal or changes displayed names. */
public final class DeviceNameSearch {

    private static final int CACHE_LIMIT = 4096;
    private static final Map<String, String[]> CACHE = new LinkedHashMap<String, String[]>(128, .75f, true) {

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String[]> eldest) {
            return size() > CACHE_LIMIT;
        }
    };

    private DeviceNameSearch() {}

    public static boolean matches(String query, String... names) {
        String needle = plain(query).trim();
        if (needle.isEmpty()) return true;
        for (String name : names) {
            String value = plain(name);
            if (value.contains(needle)) return true;
            String[] aliases = aliases(value);
            if (aliases[0].contains(needle) || aliases[1].contains(needle)) return true;
        }
        return false;
    }

    private static String plain(String text) {
        if (text == null) return "";
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00a7' && i + 1 < text.length()) i++;
            else result.append(c);
        }
        return result.toString()
            .toLowerCase(Locale.ROOT);
    }

    private static String[] aliases(String name) {
        synchronized (CACHE) {
            String[] cached = CACHE.get(name);
            if (cached != null) return cached;
        }
        StringBuilder full = new StringBuilder(), initials = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Pinyin.isChinese(c)) {
                String syllable = Pinyin.toPinyin(c)
                    .toLowerCase(Locale.ROOT);
                full.append(syllable);
                initials.append(syllable.charAt(0));
            } else {
                full.append(c);
                initials.append(c);
            }
        }
        String[] result = { full.toString(), initials.toString() };
        // Do not retain arbitrary large names from remote/custom metadata.
        if (name.length() <= 512) synchronized (CACHE) {
            CACHE.put(name, result);
        }
        return result;
    }
}
