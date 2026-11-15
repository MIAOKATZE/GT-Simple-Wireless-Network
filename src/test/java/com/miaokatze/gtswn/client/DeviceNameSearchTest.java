// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.client;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.Map;

import org.junit.Test;

public class DeviceNameSearchTest {

    @Test
    public void chineseNamesMatchLiteralFullSpellingAndInitials() {
        for (String query : new String[] { "搅拌机", "JIAOBANJI", "jiaoban", "JBJ", "jb" })
            assertTrue(query, DeviceNameSearch.matches(query, "\u00a76\u00a7l大型搅拌机"));
        assertFalse(DeviceNameSearch.matches("dianjieji", "大型搅拌机"));
        assertFalse(DeviceNameSearch.matches("djj", "大型搅拌机"));
        assertTrue(DeviceNameSearch.matches("LVjbj", "LV搅拌机"));
        assertTrue(DeviceNameSearch.matches("mixer", "Mixer", "大型搅拌机"));
        assertTrue(DeviceNameSearch.matches("jiaobanji", "Primary", "大型搅拌机"));
        assertFalse(DeviceNameSearch.matches("null", (String) null));
        assertTrue(DeviceNameSearch.matches(" ", (String) null));
    }

    @Test
    public void nameChangesUseFreshAliasesAndTheCacheStaysBounded() throws Exception {
        assertTrue(DeviceNameSearch.matches("jbj", "搅拌机"));
        assertFalse(DeviceNameSearch.matches("jbj", "电解机"));
        assertTrue(DeviceNameSearch.matches("djj", "电解机"));
        for (int i = 0; i < 4200; i++) assertTrue(DeviceNameSearch.matches("jbj", "搅拌机" + i));
        Field field = DeviceNameSearch.class.getDeclaredField("CACHE");
        field.setAccessible(true);
        assertTrue(((Map<?, ?>) field.get(null)).size() <= 4096);
        assertTrue(DeviceNameSearch.matches("jbj", "搅拌机"));
    }
}
