package com.miaokatze.gtswn.client.engineering;

import com.miaokatze.miaokatzecore.api.ui.guidance.GuidanceStyle;

/** Shared factory discovery colors; drawing and animation remain in Core. */
final class FactoryGuidanceStyle {

    static final GuidanceStyle PALETTE = GuidanceStyle.builder()
        .background(0xF0102538)
        .titleText(DashboardVisuals.TEXT)
        .detailText(DashboardVisuals.MUTED)
        .highlight(DashboardVisuals.CYAN)
        .pointerFill(DashboardVisuals.TEXT)
        .pointerOutline(DashboardVisuals.BACK)
        .pressedHint(0x7068E1E8)
        .clickCorner(DashboardVisuals.CYAN)
        .fadedClickCorner(0x8868E1E8)
        .build();

    private FactoryGuidanceStyle() {}
}
