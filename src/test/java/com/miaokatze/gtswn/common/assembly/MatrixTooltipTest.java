// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.assembly;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import net.minecraft.util.StringTranslate;

import org.junit.Test;

import com.miaokatze.gtswn.common.util.DeviceTooltip;
import com.miaokatze.gtswn.common.util.DeviceTooltipOrder;

public class MatrixTooltipTest {

    @Test
    @SuppressWarnings("unchecked")
    public void everyBilingualVariantKeepsTheStandardHeaderAndHighlightedAssemblyLineHint() throws Exception {
        Field instance = StringTranslate.class.getDeclaredField("instance");
        instance.setAccessible(true);
        Field languageList = StringTranslate.class.getDeclaredField("languageList");
        languageList.setAccessible(true);
        Map<String, String> original = new HashMap<>((Map<String, String>) languageList.get(instance.get(null)));
        try {
            for (String locale : new String[] { "zh_CN", "en_US" }) {
                Properties messages = new Properties();
                try (InputStreamReader reader = new InputStreamReader(
                    getClass().getResourceAsStream("/assets/gtswn/lang/" + locale + ".lang"),
                    StandardCharsets.UTF_8)) {
                    messages.load(reader);
                }
                Map<String, String> translations = new HashMap<>();
                for (String key : messages.stringPropertyNames()) translations.put(key, messages.getProperty(key));
                StringTranslate.replaceWith(translations);
                for (boolean fluid : new boolean[] { false, true })
                    for (boolean mirror : new boolean[] { false, true }) {
                        String[] lines = MatrixAssemblyUi.description(fluid, mirror);
                        String name = messages.getProperty(
                            "gtswn.matrix." + (fluid ? "fluid" : "item") + (mirror ? ".mirror" : "") + ".name");
                        assertTrue(lines[0].contains(name));
                        assertTrue(lines[0].startsWith("\u00a79"));
                        assertTrue(lines[0].contains("\u00a7e"));
                        assertTrue(lines[1].startsWith("\u00a7f"));
                        assertTrue(lines[1].contains("\u00a7b"));
                        assertEquals(
                            messages.getProperty(
                                "gtswn.matrix.description.storage." + (mirror ? "mirror" : fluid ? "fluid" : "item")),
                            lines[2]);
                        assertTrue(lines[2].startsWith("\u00a7f"));
                        assertTrue(lines[2].contains(locale.equals("zh_CN") ? "仓室缓存" : "Compartment Buffer"));
                        assertTrue(lines[2].contains("\u00a7b"));
                        assertTrue(lines[2].contains("\u00a77"));
                        if (mirror) {
                            assertTrue(
                                lines[2].contains(locale.equals("zh_CN") ? "共享源仓室" : "Shared source compartment"));
                            assertTrue(lines[2].contains(locale.equals("zh_CN") ? "无独立缓存" : "no independent buffer"));
                            assertFalse(lines[2].contains("2,147,483,647"));
                        } else {
                            assertTrue(lines[2].contains("2,147,483,647"));
                            assertTrue(
                                lines[2].contains(fluid ? "\u00a77 L" : locale.equals("zh_CN") ? "个物品" : "items"));
                        }
                        assertTrue(lines[3].contains(locale.equals("zh_CN") ? "仓室等级" : "Hatch Tier"));
                        assertTrue(lines[3].contains("LuV"));
                        assertEquals(messages.getProperty("gtswn.matrix.description.assembly_line"), lines[4]);
                        assertTrue(lines[4].startsWith("\u00a76\u00a7l["));
                        assertTrue(lines[4].endsWith("]\u00a7r"));
                        assertEquals("", lines[5]);
                        assertTrue(lines[6].contains("\u00a7b"));
                        assertEquals(DeviceTooltip.addedByLine(), lines[lines.length - 1]);
                        List<String> compact = new ArrayList<>(Arrays.asList(lines));
                        assertTrue(
                            DeviceTooltipOrder
                                .matrixPage(compact, Arrays.asList(lines), DeviceTooltip.addedByLine(), false));
                        assertEquals(
                            Arrays
                                .asList(lines[0], lines[1], lines[2], lines[3], lines[4], DeviceTooltip.addedByLine()),
                            compact);
                    }
            }
        } finally {
            StringTranslate.replaceWith(original);
        }
    }
}
