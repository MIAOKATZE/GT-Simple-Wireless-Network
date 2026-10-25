package com.miaokatze.gtswn.client.engineering.desktop;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Two explicit basic clock elements sharing the same source kind and visual-only fields. */
public enum DesktopInformationTemplate {

    LOCAL_CLOCK("localClock", "localTime", "localDate", "timezone"),
    GAME_CLOCK("gameClock", "gameTime", "gameDay");

    private final String key;
    private final List<String> fields;

    DesktopInformationTemplate(String key, String... fields) {
        this.key = key;
        this.fields = Collections.unmodifiableList(Arrays.asList(fields));
    }

    public String titleKey() {
        return "gtswn.factory.desktop.info." + key;
    }

    public List<String> fields() {
        return fields;
    }

    public WidgetSpec create(String id) {
        return new WidgetSpec(id, WidgetKind.CLOCK, 0, 0, 3, 3).configured("", fields, 1);
    }
}
