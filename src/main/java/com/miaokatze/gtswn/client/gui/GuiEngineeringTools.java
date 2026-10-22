package com.miaokatze.gtswn.client.gui;

import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import com.cleanroommc.modularui.drawable.ItemDrawable;
import com.cleanroommc.modularui.factory.ClientGUI;
import com.miaokatze.gtswn.client.engineering.EngineeringHud;
import com.miaokatze.gtswn.common.baubles.TerminalBaubles;

/** Small, on-demand engineering actions for the exact mounted terminal chosen on the C wheel. */
public final class GuiEngineeringTools extends GtswnMuiScreen {

    public GuiEngineeringTools(int slot) {
        super("engineering_tools", 310, toolHeight(slot));
        int controlsY = toolHeight(slot) - 35;
        ItemStack stack = TerminalBaubles.terminals(mc.thePlayer)[slot];
        if (stack == null) return;
        getMainPanel().child(
            new ItemDrawable(stack).asWidget()
                .pos(12, 12)
                .size(20, 20));
        getMainPanel()
            .child(GtswnMuiWidgets.canvas("terminal_identity", 40, 12, 258, 28, (context, mx, my, partial) -> {
                ItemStack current = TerminalBaubles.terminals(mc.thePlayer)[slot];
                if (current == null) return;
                fontRendererObj
                    .drawString(fontRendererObj.trimStringToWidth(current.getDisplayName(), 258), 0, 0, 0xFFF0E8D8);
                fontRendererObj.drawString(
                    fontRendererObj.trimStringToWidth(GuiTerminalWheel.identity(current, slot), 258),
                    0,
                    15,
                    0xFF9AA0A8);
            }));
        String[] labels = EngineeringHud.toolLabels(slot);
        for (int i = 0; i < labels.length; i++) {
            final int action = i;
            getMainPanel().child(
                GtswnMuiWidgets.button(
                    "engineering_action_" + i,
                    () -> EngineeringHud.toolLabels(slot)[action],
                    12 + (i % 2) * 146,
                    48 + (i / 2) * 23,
                    140,
                    20,
                    () -> {
                        mc.displayGuiScreen(null);
                        EngineeringHud.toolAction(slot, action);
                    }));
        }
        if (slot > 0) getMainPanel().child(
            GtswnMuiWidgets.button(
                "engineering_inspect",
                tr("tools.inspect"),
                12,
                controlsY,
                92,
                22,
                () -> EngineeringHud.inspect(slot)));
        getMainPanel().child(
            GtswnMuiWidgets.button(
                "engineering_wheel",
                tr("tools.wheel"),
                slot == 0 ? 12 : 109,
                controlsY,
                slot == 0 ? 140 : 92,
                22,
                () -> ClientGUI.open(new GuiTerminalWheel())));
        getMainPanel().child(
            GtswnMuiWidgets.button(
                "engineering_world",
                tr("tools.world"),
                slot == 0 ? 158 : 206,
                controlsY,
                slot == 0 ? 140 : 92,
                22,
                () -> mc.displayGuiScreen(null)));
    }

    private static int toolHeight(int slot) {
        return 94 + ((EngineeringHud.toolLabels(slot).length + 1) / 2) * 23;
    }

    static String tr(String key) {
        return StatCollector.translateToLocal("gtswn.engineering." + key);
    }
}
