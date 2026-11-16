// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.util;

import static org.junit.Assert.*;

import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StringTranslate;

import org.junit.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miaokatze.miaokatzecore.api.tooltip.TooltipPages;
import com.miaokatze.miaokatzecore.api.tooltip.component.NativeTooltipPage;

/** Exercise every authored page through the Core fallback bridge without a client renderer. */
public class AuthoredTooltipsTest {

    @Test
    @SuppressWarnings("unchecked")
    public void bilingualPagesCycleAllTiersAndKeepLiveStateAndForeignPrefix() throws Exception {
        Field instance = StringTranslate.class.getDeclaredField("instance");
        instance.setAccessible(true);
        Field languageList = StringTranslate.class.getDeclaredField("languageList");
        languageList.setAccessible(true);
        Map<String, String> original = new HashMap<>((Map<String, String>) languageList.get(instance.get(null)));
        Item item = new Item().setHasSubtypes(true);
        Method register = Item.itemRegistry.getClass()
            .getDeclaredMethod("addObjectRaw", int.class, String.class, Object.class);
        register.setAccessible(true);
        register.invoke(Item.itemRegistry, 31251, "gtswn_tooltip_test:authored", item);
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
                JsonObject catalog;
                try (InputStreamReader reader = new InputStreamReader(
                    getClass().getResourceAsStream("/assets/gtswn/tooltip/authored_" + locale + ".json"),
                    StandardCharsets.UTF_8)) {
                    catalog = new JsonParser().parse(reader)
                        .getAsJsonObject();
                }
                assertEquals(
                    18,
                    catalog.getAsJsonArray("items")
                        .size());
                int meta = 0;
                for (JsonElement element : catalog.getAsJsonArray("items")) {
                    JsonObject definition = element.getAsJsonObject();
                    String id = definition.get("id")
                        .getAsString();
                    ItemStack stack = new ItemStack(item, 1, meta++);
                    int count = definition.getAsJsonObject("tiers")
                        .has("detail") ? 3 : 2;
                    for (int tier = 0; tier < count; tier++) {
                        String owner = "Owner: " + locale + "_" + tier;
                        List<String> lines = new ArrayList<>(Arrays.asList("Native title", "Other mod: charge 321 EU"));
                        NativeTooltipPage page = AuthoredTooltips
                            .page(stack, id, Arrays.asList(owner, "Binding: dimension -1 / 12, 34, 56"));
                        assertNotNull(id, page);
                        assertTrue(id, page.appendTo(stack, lines));
                        assertEquals("Native title", lines.get(0));
                        assertEquals("Other mod: charge 321 EU", lines.get(1));
                        assertEquals(id, DeviceTooltip.addedByLine(), lines.get(lines.size() - 1));
                        if (id.equals("Portable_Wireless_Network_Monitor") || id.equals("Device_Info_Terminal")
                            || id.equals("ME_Network_Quantum_Terminal")) {
                            assertTrue(id + " " + page.tier(), lines.contains(owner));
                            assertTrue(lines.contains("Binding: dimension -1 / 12, 34, 56"));
                            for (String line : lines) assertFalse(line.contains("屏幕 0/16"));
                        }
                        for (String line : lines) {
                            assertFalse(id, line.contains("[right:"));
                            assertFalse(id, line.contains("[left:"));
                            assertFalse(id, line.contains("[key"));
                        }
                        assertTrue(NativeTooltipPage.act(TooltipPages.identity(stack), NativeTooltipPage.Action.TIER));
                    }
                }
            }
        } finally {
            StringTranslate.replaceWith(original);
        }
    }
}
