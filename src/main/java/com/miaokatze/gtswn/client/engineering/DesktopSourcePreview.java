package com.miaokatze.gtswn.client.engineering;

import net.minecraft.client.gui.FontRenderer;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.drawable.FluidDrawable;
import com.cleanroommc.modularui.drawable.ItemDrawable;
import com.cleanroommc.modularui.screen.viewport.GuiContext;
import com.cleanroommc.modularui.theme.WidgetTheme;
import com.miaokatze.gtswn.client.engineering.desktop.ResourceBinding;
import com.miaokatze.gtswn.client.engineering.desktop.ScreenBinding;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetResources;
import com.miaokatze.gtswn.common.util.FormatUtil;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor.Panel;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor.Row;
import com.miaokatze.miaokatzecore.api.ui.component.UiRect;
import com.miaokatze.miaokatzecore.api.ui.component.UiRenderer;
import com.miaokatze.miaokatzecore.client.render.GlScope;

/** Selector previews read the existing authorized cache, without guessing item identities. */
final class DesktopSourcePreview {

    private static final ItemDrawable ITEM = new ItemDrawable();
    private static final FluidDrawable FLUID = new FluidDrawable();
    private static final WidgetTheme ICON_THEME = WidgetTheme.whiteTextShadow(16, 16, null);

    private DesktopSourcePreview() {}

    static String screenTitle(ScreenBinding binding) {
        int index = WidgetResources.screenChoices()
            .indexOf(binding);
        return FactoryDesktop.tr("binding.screen.entry") + " " + (index < 0 ? "?" : index + 1);
    }

    static boolean category(ScreenBinding binding, int category) {
        if (category == 0) return true;
        Panel panel = WidgetResources.readScreen(binding).panel;
        return panel != null && (category == 1 ? panel.aeConnected : !panel.currentEnergy.isEmpty());
    }

    static String screenSummary(ScreenBinding binding) {
        WidgetResources.ScreenReading reading = WidgetResources.readScreen(binding);
        Panel panel = reading.panel;
        if (panel == null) return FactoryDesktop.tr("binding.screen.state." + reading.reason);
        int items = 0, fluids = 0;
        for (Row row : panel.rows) {
            if (row.kind == 1) items++;
            if (row.kind == 2) fluids++;
        }
        return (panel.currentEnergy.isEmpty() ? "" : "EU · ") + (panel.aeConnected
            ? "AE · " + FactoryDesktop
                .tr("resource.items") + " " + items + " · " + FactoryDesktop.tr("resource.fluids") + " " + fluids
            : FactoryDesktop.tr("binding.screen.ae.offline"));
    }

    static void screen(FontRenderer font, UiRenderer renderer, UiRect bounds, ScreenBinding binding) {
        line(
            font,
            renderer,
            screenTitle(binding) + " · " + screenSummary(binding),
            bounds,
            5,
            4,
            DashboardVisuals.TEXT);
        Panel panel = WidgetResources.readScreen(binding).panel;
        StringBuilder preview = new StringBuilder();
        if (panel != null) for (Row row : panel.rows) {
            if (row.kind != 1 && row.kind != 2) continue;
            if (preview.length() > 0) preview.append(" · ");
            preview.append(row.name)
                .append(" × ")
                .append(row.amount);
            if (preview.length() > 100) break;
        }
        if (preview.length() == 0) preview.append(FactoryDesktop.tr("binding.screen.content.empty"));
        line(font, renderer, preview.toString(), bounds, 5, 17, DashboardVisuals.CYAN);
        line(font, renderer, WidgetResources.screenLabel(binding), bounds, 5, 30, DashboardVisuals.MUTED);
    }

    static void resource(FontRenderer font, UiRenderer renderer, UiRect bounds, ResourceBinding binding) {
        WidgetResources.Reading reading = WidgetResources.read(binding);
        Row row = reading.row;
        int x = (int) bounds.x + 4, y = (int) bounds.y + 8;
        try (GlScope ignored = new GlScope()) {
            GL11.glColor4f(1, 1, 1, 1);
            if (row != null && row.icon != null && row.kind == 1 && row.icon.item != null) ITEM.setItem(row.icon.item)
                .draw(GuiContext.getDefault(), x, y, 16, 16, ICON_THEME);
            else if (row != null && row.icon != null && row.kind == 2 && row.icon.fluid != null)
                FLUID.setFluid(row.icon.fluid)
                    .draw(GuiContext.getDefault(), x, y, 16, 16, ICON_THEME);
            else renderer.text("?", x + 5, y + 4, DashboardVisuals.MUTED);
        }
        line(font, renderer, binding.name, bounds, 24, 4, DashboardVisuals.TEXT);
        String amount = row == null ? "—" : row.amount + (row.kind == 2 ? " mB" : "");
        String rate = WidgetResources.rateKnown(reading.panel, row)
            ? FormatUtil.formatMetricDouble(row.rate) + (row.kind == 2 ? " mB/min" : "/min")
            : "—";
        line(font, renderer, amount + " · " + rate, bounds, 24, 17, DashboardVisuals.CYAN);
    }

    static void line(FontRenderer font, UiRenderer renderer, String text, UiRect bounds, int x, int y, int color) {
        int width = Math.max(0, (int) bounds.width - x - 5);
        String clipped = font.getStringWidth(text) <= width ? text
            : font.trimStringToWidth(text, Math.max(0, width - font.getStringWidth("…"))) + "…";
        renderer.text(clipped, bounds.x + x, bounds.y + y, color);
    }
}
