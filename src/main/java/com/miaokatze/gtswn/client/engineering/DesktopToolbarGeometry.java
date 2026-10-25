package com.miaokatze.gtswn.client.engineering;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.miaokatze.miaokatzecore.api.ui.component.UiRect;

/** Responsive toolbar geometry, shared by real controls and headless boundary checks. */
final class DesktopToolbarGeometry {

    final Map<String, UiRect> controls;
    final UiRect title;
    final int height;

    DesktopToolbarGeometry(int width, boolean editing) {
        int available = Math.max(1, width - 12);
        String[] ids = editing ? new String[] { "back-world", "edit", "library-toggle", "zoom", "fit", "theme" }
            : new String[] { "back-world", "edit", "zoom", "fit", "theme" };
        int required = editing ? 184 : 156;
        boolean inlineTitle = available >= required + 100;
        title = new UiRect(6, inlineTitle ? 11 : 4, inlineTitle ? available - required - 8 : available, 12);
        int x = inlineTitle ? width - 6 - required : 6;
        int y = inlineTitle ? 5 : 20;
        Map<String, UiRect> positions = new LinkedHashMap<>();
        for (String id : ids) {
            int itemWidth = Math.min(available, id.equals("zoom") ? 44 : 24);
            if (x > 6 && x + itemWidth > width - 6) {
                x = 6;
                y += 28;
            }
            positions.put(id, new UiRect(x, y, itemWidth, 24));
            x += itemWidth + 4;
        }
        controls = Collections.unmodifiableMap(positions);
        height = y + 29;
    }
}
