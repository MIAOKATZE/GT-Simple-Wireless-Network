// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.util;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miaokatze.miaokatzecore.api.tooltip.component.NativeTooltipPage;
import com.miaokatze.miaokatzecore.api.tooltip.component.TooltipTable;

/** Authored copy is independent of the live stack state collected by the native item tooltip. */
public final class AuthoredTooltips {

    private static final Map<String, Map<String, JsonObject>> CATALOGS = new HashMap<>();

    private AuthoredTooltips() {}

    private static synchronized Map<String, JsonObject> catalog() {
        String locale = StatCollector.translateToLocal("gtswn.tooltip.authored_locale");
        if (!"zh_CN".equals(locale)) locale = "en_US";
        if (CATALOGS.containsKey(locale)) return CATALOGS.get(locale);
        String resource = "/assets/gtswn/tooltip/authored_" + locale + ".json";
        InputStream stream = AuthoredTooltips.class.getResourceAsStream(resource);
        if (stream == null) return null;
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            Map<String, JsonObject> result = new HashMap<>();
            JsonObject root = new JsonParser().parse(reader)
                .getAsJsonObject();
            for (JsonElement value : root.getAsJsonArray("items")) {
                JsonObject item = value.getAsJsonObject();
                result.put(
                    item.get("id")
                        .getAsString(),
                    item.getAsJsonObject("tiers"));
            }
            CATALOGS.put(locale, result);
            return result;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read tooltip catalog: " + resource, e);
        }
    }

    public static NativeTooltipPage page(ItemStack stack, String id, List<String> liveState) {
        Map<String, JsonObject> catalog = catalog();
        if (catalog == null || !catalog.containsKey(id)) return null;
        JsonObject tiers = catalog.get(id);
        NativeTooltipPage page = NativeTooltipPage.forItem(stack);
        if (!tiers.has("detail")) page.twoTiers();
        String tier = page.tier()
            .name()
            .toLowerCase(java.util.Locale.ROOT);
        for (JsonElement value : tiers.getAsJsonArray(tier)) {
            JsonObject block = value.getAsJsonObject();
            if (block.has("role") && "credit".equals(
                block.get("role")
                    .getAsString())) {
                page.line(DeviceTooltip.addedByLine());
                continue;
            }
            switch (block.get("type")
                .getAsString()) {
                case "line":
                    page.input(
                        block.get("text")
                            .getAsString());
                    break;
                case "warning":
                    page.warning(
                        block.get("text")
                            .getAsString());
                    break;
                case "divider":
                    page.divider();
                    break;
                case "table":
                    List<List<String>> rows = new ArrayList<>();
                    for (JsonElement row : block.getAsJsonArray("rows")) rows.add(strings(row.getAsJsonArray()));
                    TooltipTable table = TooltipTable.of("", strings(block.getAsJsonArray("columns")), rows)
                        .withInputIcons();
                    for (int column = 0; column < table.headers()
                        .size(); column++) {
                        table = table.align(column, TooltipTable.Alignment.LEFT);
                    }
                    page.block(table);
                    break;
                case "dynamic":
                    if (!"live-state".equals(
                        block.get("binding")
                            .getAsString())) {
                        throw new IllegalStateException("Unknown tooltip binding: " + block);
                    }
                    for (String line : liveState) if (!line.isEmpty()) page.line(line);
                    break;
                default:
                    throw new IllegalStateException("Unknown tooltip block: " + block);
            }
        }
        return page;
    }

    private static List<String> strings(JsonArray array) {
        List<String> result = new ArrayList<>();
        for (JsonElement value : array) result.add(value.getAsString());
        return result;
    }
}
