package com.miaokatze.gtswn.client.gui;

import java.util.List;

import net.minecraft.client.gui.Gui;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.miaokatze.gtswn.client.engineering.EngineeringHud;
import com.miaokatze.gtswn.client.engineering.EngineeringModeController;
import com.miaokatze.gtswn.common.gui.GtswnMuiTheme;

/** Full read-only server evidence stays behind an explicit inspection action. */
public final class GuiEngineeringDetails extends GtswnMuiScreen {

    private int scroll;

    public GuiEngineeringDetails() {
        super("engineering_details", 310, 230);
        getMainPanel().child(
            IKey.str(GuiEngineeringTools.tr("dash.details"))
                .asWidget()
                .pos(12, 10)
                .size(240, 12));
        getMainPanel()
            .child(GtswnMuiWidgets.canvas("engineering_evidence", 12, 28, 286, 144, (context, mx, my, partial) -> {
                List<String> lines = EngineeringHud.inspectionLines();
                scroll = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - 10)));
                Gui.drawRect(0, 0, 286, 144, 0x90202A32);
                for (int i = 0; i < 10 && i + scroll < lines.size(); i++) fontRendererObj.drawString(
                    fontRendererObj.trimStringToWidth(lines.get(i + scroll), 274),
                    6,
                    5 + i * 14,
                    GtswnMuiTheme.TEXT_BODY);
            }));
        getMainPanel().child(GtswnMuiWidgets.button("details_previous", "↑", 252, 7, 20, 16, () -> scroll -= 8));
        getMainPanel().child(GtswnMuiWidgets.button("details_next", "↓", 278, 7, 20, 16, () -> scroll += 8));
        getMainPanel().child(
            GtswnMuiWidgets.button(
                "details_focus",
                () -> GuiEngineeringTools.tr("focus"),
                12,
                180,
                90,
                18,
                () -> EngineeringModeController.INSTANCE.selected() != null,
                () -> {
                    EngineeringModeController.Target t = EngineeringModeController.INSTANCE.selected();
                    mc.displayGuiScreen(null);
                    EngineeringModeController.focus(t.x, t.y, t.z);
                }));
        getMainPanel().child(
            GtswnMuiWidgets.button(
                "details_pin",
                () -> GuiEngineeringTools.tr(EngineeringModeController.INSTANCE.pinned() ? "unpin" : "pin"),
                110,
                180,
                90,
                18,
                () -> EngineeringModeController.INSTANCE.selected() != null,
                () -> EngineeringModeController.INSTANCE.togglePin()));
        getMainPanel()
            .child(GtswnMuiWidgets.button("details_clear", GuiEngineeringTools.tr("clear"), 208, 180, 90, 18, () -> {
                EngineeringHud.clearTarget();
                mc.displayGuiScreen(null);
            }));
        getMainPanel().child(
            GtswnMuiWidgets.button(
                "details_close",
                GuiEngineeringTools.tr("tools.world"),
                12,
                204,
                286,
                18,
                () -> mc.displayGuiScreen(null)));
    }

    @Override
    public boolean onMouseScroll(UpOrDown direction, int amount) {
        scroll += direction.isUp() ? -2 : 2;
        return true;
    }
}
