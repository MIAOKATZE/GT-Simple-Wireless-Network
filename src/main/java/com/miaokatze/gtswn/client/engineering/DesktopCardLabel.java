package com.miaokatze.gtswn.client.engineering;

import com.miaokatze.gtswn.client.engineering.desktop.WidgetKind;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetPresentation;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetSpec;

/** Current source data is supplied for one projection only; no sampled names are retained. */
final class DesktopCardLabel {

    static String informationTitleKey(WidgetSpec widget) {
        if (widget.kind != WidgetKind.CLOCK) return null;
        String source = widget.clockSourceField();
        if ("gameTime".equals(source)) return "gtswn.factory.desktop.info.gameClock";
        if ("localTime".equals(source)) return "gtswn.factory.desktop.info."
            + (widget.presentation == WidgetPresentation.CLOCK_FACE ? "analogClock" : "localClock");
        return null;
    }

    static String title(WidgetSpec widget, String fallback, String currentDisplayName, String currentName) {
        if (!widget.title.isEmpty()) return widget.title;
        if (widget.resource != null && !widget.resource.name.isEmpty()) return widget.resource.name;
        if (widget.kind != WidgetKind.DEVICE || widget.device == null) return fallback;
        String name = currentDisplayName == null || currentDisplayName.isEmpty() ? currentName : currentDisplayName;
        if (name == null || name.isEmpty()) name = fallback;
        return name + " · DIM "
            + widget.device.dimension
            + " · "
            + widget.device.x
            + "/"
            + widget.device.y
            + "/"
            + widget.device.z;
    }
}
