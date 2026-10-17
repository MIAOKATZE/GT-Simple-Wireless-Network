package com.miaokatze.gtswn.common.gui;

import com.cleanroommc.modularui.api.IThemeApi;
import com.cleanroommc.modularui.drawable.UITexture;
import com.cleanroommc.modularui.screen.ModularPanel;

/** Shared, side-safe MUI2 resources for the device terminal's industrial theme. */
public final class GtswnMuiTheme {

    public static final String THEME_ID = "gtswn:terminal";

    public static final int TEXT_TITLE = 0xFFF0E8D8;
    public static final int TEXT_BODY = 0xFFD8D4C8;
    public static final int TEXT_LABEL = 0xFFB0AA9A;
    public static final int TEXT_MUTED = 0xFF9AA0A8;
    public static final int TEXT_ACCENT = 0xFFF0A028;
    public static final int STATE_ONLINE = 0xFF4CE08A;
    public static final int STATE_OFFLINE = 0xFFF05A5A;

    public static final UITexture PANEL = texture("panel_device_info", 450, 252, 8);
    public static final UITexture BUTTON = texture("button_normal", 64, 20, 4);
    public static final UITexture BUTTON_HOVER = texture("button_hover", 64, 20, 4);
    public static final UITexture BUTTON_DISABLED = texture("button_disabled", 64, 20, 4);
    public static final UITexture TAB_ACTIVE = texture("tab_active", 64, 20, 4);
    public static final UITexture TAB_INACTIVE = texture("tab_inactive", 64, 20, 4);
    public static final UITexture LIST_PANEL = texture("list_panel", 32, 32, 4);
    public static final UITexture ROW_HOVER = texture("row_hover", 32, 20, 4);
    public static final UITexture SCROLLBAR_TRACK = texture("scrollbar_track", 6, 16, 2);
    public static final UITexture SCROLLBAR_THUMB = texture("scrollbar_thumb", 6, 12, 2);

    private GtswnMuiTheme() {}

    private static UITexture texture(String name, int width, int height, int border) {
        return UITexture.builder()
            .location("gtswn", "textures/gui/" + name + ".png")
            .imageSize(width, height)
            .adaptable(border)
            .build();
    }

    /** Theme definitions themselves are loaded and reloaded by MUI2 from assets/gtswn/themes.json. */
    public static void register() {
        IThemeApi.get()
            .registerThemeForScreen("gtswn", THEME_ID);
    }

    public static ModularPanel panel(String name, int width, int height) {
        return fit(
            new ModularPanel(name).size(width, height)
                .background(PANEL)
                .themeOverride(THEME_ID));
    }

    /** Also usable for native modal dialogs, which have their own viewport and input transform. */
    public static <P extends ModularPanel> P fit(P panel) {
        return fit(panel, 0, 0);
    }

    /** Reserve space for controls such as GregTech's cover tabs outside the panel bounds. */
    public static <P extends ModularPanel> P fit(P panel, int horizontalOutset, int verticalOutset) {
        panel.transform((widget, stack) -> {
            float scale = fitScale(
                widget.getParentArea()
                    .w(),
                widget.getParentArea()
                    .h(),
                widget.getArea()
                    .w() + 2 * horizontalOutset,
                widget.getArea()
                    .h() + 2 * verticalOutset);
            if (scale < 1f) {
                float centerX = widget.getArea()
                    .w() / 2f;
                float centerY = widget.getArea()
                    .h() / 2f;
                stack.translate(centerX, centerY);
                stack.scale(scale, scale);
                stack.translate(-centerX, -centerY);
            }
        });
        return panel;
    }

    /** The viewport matrix applies this same scale to rendering, clipping and input coordinates. */
    public static float fitScale(int screenWidth, int screenHeight, int panelWidth, int panelHeight) {
        return Math.max(
            0.01f,
            Math.min(
                1f,
                Math.min(
                    Math.max(1, screenWidth - 16) / (float) Math.max(1, panelWidth),
                    Math.max(1, screenHeight - 16) / (float) Math.max(1, panelHeight))));
    }
}
