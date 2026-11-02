package com.miaokatze.gtswn.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;

import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.miaokatze.gtswn.common.gui.GtswnMuiTheme;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** A native client-only MUI2 screen. Subclasses populate the panel after assigning their own state. */
@SideOnly(Side.CLIENT)
public abstract class GtswnMuiScreen extends ModularScreen {

    protected final Minecraft mc = Minecraft.getMinecraft();
    protected final FontRenderer fontRendererObj = mc.fontRenderer;

    protected GtswnMuiScreen(String name, int width, int height) {
        this(GtswnMuiTheme.panel(name, width, height));
    }

    protected GtswnMuiScreen(ModularPanel panel) {
        super("gtswn", panel);
        pausesGame(false);
        useTheme(GtswnMuiTheme.THEME_ID);
    }

    protected final int panelLeft() {
        return getMainPanel().getArea()
            .x();
    }

    protected final int panelTop() {
        return getMainPanel().getArea()
            .y();
    }
}
