package com.miaokatze.gtswn.client.engineering.desktop;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Basic clock elements sharing the same source kind and visual-only fields. */
public enum DesktopInformationTemplate {

    LOCAL_CLOCK("localClock", "localTime", "localDate", "timezone"),
    GAME_CLOCK("gameClock", "gameTime", "gameDay"),
    ANALOG_CLOCK("analogClock", "localTime");

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
        WidgetSpec widget = new WidgetSpec(id, WidgetKind.CLOCK, 0, 0, 2, 2).configured("", fields, 1);
        if (this == ANALOG_CLOCK) widget = widget.presented(WidgetPresentation.CLOCK_FACE, "", null);
        return widget.withBounds(0, 0, widget.minColumns(), widget.minRows());
    }
}
