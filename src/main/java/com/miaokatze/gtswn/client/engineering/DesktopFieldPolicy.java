package com.miaokatze.gtswn.client.engineering;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.miaokatze.gtswn.client.engineering.desktop.WidgetKind;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetSpec;

/** Display semantics independent of render code: explicit empty never means default. */
final class DesktopFieldPolicy {

    static List<String> fields(WidgetSpec widget) {
        if (widget.fieldsConfigured) return widget.fields;
        switch (widget.kind) {
            case CLOCK:
                return Arrays.asList("localTime", "localDate", "timezone");
            case SESSION:
                return Arrays.asList("sessionDuration", "connection");
            case TEAM:
                return Arrays.asList("teamName", "teamRole");
            case PLAYER:
                return Arrays.asList("playerName", "health", "food", "level");
            case WORLD:
                return Arrays.asList("dimension", "coordinates", "weather");
            case MACHINES:
            case ATTENTION:
                return Collections.singletonList("status");
            case INPUT:
                return Arrays.asList("input", "status");
            case OUTPUT:
                return Arrays.asList("output", "status");
            case WIRELESS:
                return Arrays.asList("stored", "netRate", "status", "source");
            case HISTORY:
            case RESOURCES:
                return Arrays.asList("source", "updated");
            case REGION:
                return Arrays.asList("status", "source", "updated");
            case DEVICE:
                return Arrays.asList("status", "instantInput", "instantOutput", "recipeInput", "recipeOutput");
            default:
                return Collections.emptyList();
        }
    }

    static boolean show(WidgetSpec widget, String field) {
        return fields(widget).contains(field);
    }

    static boolean keepsBody(WidgetKind kind) {
        return kind == WidgetKind.HISTORY || kind == WidgetKind.RESOURCES;
    }
}
