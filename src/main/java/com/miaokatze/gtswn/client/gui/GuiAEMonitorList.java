package com.miaokatze.gtswn.client.gui;

import java.math.BigInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;
import net.minecraftforge.fluids.FluidStack;

import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.miaokatze.gtswn.common.gui.GtswnMuiTheme;
import com.miaokatze.gtswn.common.panel.AEMonitorSample;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.common.util.FormatUtil;

/** Native MUI2 list: the viewport owns clipping, scrolling, drag capture and hit testing. */
class GuiAEMonitorList extends ListWidget<IWidget, GuiAEMonitorList> {

    private final GuiNetworkInfoPanel host;
    private final TileEntityNetworkInfoPanel panel;
    private final Supplier<Object[]> entries;
    private final Consumer<String> removeCallback;

    GuiAEMonitorList(GuiNetworkInfoPanel host, TileEntityNetworkInfoPanel panel, int left, int top, int width,
        Supplier<Object[]> entries, Consumer<String> removeCallback) {
        this.host = host;
        this.panel = panel;
        this.entries = entries;
        this.removeCallback = removeCallback;
        name("panel.monitor.entries");
        pos(left + 8, top + 163).size(width - 16, 107);
        background(GtswnMuiTheme.LIST_PANEL);
        rebuildRows();
    }

    /** Rebuild only after the server changes the monitored entry identities. */
    void clampScroll() {
        rebuildRows();
    }

    @Override
    public boolean layoutWidgets() {
        if (!hasChildren()) getScrollData().setScrollSize(0);
        boolean complete = super.layoutWidgets();
        if (complete) getScrollData().clamp(getScrollArea());
        return complete;
    }

    private void rebuildRows() {
        removeAll();
        Object[] current = entries.get();
        for (int index = 0; index < current.length; index++) {
            final Object entry = current[index];
            final String entryKey = GuiNetworkInfoPanel.entryKey(entry);
            ParentWidget<?> row = new ParentWidget<>().name("panel.monitor.row." + index)
                .widthRel(1f)
                .height(20)
                .background(IDrawable.EMPTY)
                .hoverBackground(GtswnMuiTheme.ROW_HOVER);
            row.child(
                GtswnMuiWidgets.canvas(
                    "panel.monitor.data." + index,
                    0,
                    0,
                    344,
                    20,
                    (context, mouseX, mouseY, partialTicks) -> drawEntry(entry, 0, 0)));
            row.child(
                GtswnMuiWidgets
                    .button(
                        "panel.monitor.remove." + index,
                        tr("gtswn.network_info.gui.ae.remove"),
                        0,
                        3,
                        48,
                        14,
                        () -> removeCallback.accept(entryKey))
                    .right(0));
            child(row);
        }
        if (isValid()) scheduleResize();
    }

    private void drawEntry(Object entry, int x, int y) {
        int displayMode = panel.getDisplayMode();
        int iconSize = 16;
        int iconY = y + (20 - iconSize) / 2;
        String name;
        String key;
        if (entry instanceof ItemStack) {
            ItemStack stack = (ItemStack) entry;
            host.drawItemIcon(stack, x, iconY);
            name = stack.getDisplayName();
            key = TileEntityNetworkInfoPanel.getAEKey(stack);
        } else if (entry instanceof FluidStack) {
            FluidStack fluid = (FluidStack) entry;
            host.drawFluidIcon(fluid, x, iconY);
            name = fluid.getLocalizedName();
            key = TileEntityNetworkInfoPanel.getAEKey(fluid);
        } else {
            return;
        }

        // 提前获取采样数据，供数量列与变化量列共用
        AEMonitorSample sample = key == null ? null
            : panel.getAEMonitorLatest()
                .get(key);

        // 名称列：限制宽度，避免与数量列重叠（数量列起始于 x+110）
        int nameMaxW = 84;
        host.font()
            .drawString(
                host.font()
                    .trimStringToWidth(name, nameMaxW),
                x + 22,
                y + 6,
                GtswnGuiPalette.TEXT_BODY);

        // 数量/存量列
        String amountText = sample == null ? "-" : formatAEMonitorAmount(sample.amount, displayMode);
        host.font()
            .drawString(amountText, x + 110, y + 6, GtswnGuiPalette.TEXT_BODY);

        // 实时变化量 / 平均变化量两列
        String realtimeText;
        int realtimeColor;
        String averageText;
        int averageColor;
        if (sample == null) {
            realtimeText = "-";
            realtimeColor = GtswnGuiPalette.TEXT_MUTED;
            averageText = "-";
            averageColor = GtswnGuiPalette.TEXT_MUTED;
        } else {
            realtimeText = formatAEMonitorRate(sample.rate, displayMode);
            realtimeColor = rateColor(sample.rate);
            Double avgRate = panel.getAEMonitorAvg300s()
                .get(key);
            if (avgRate == null) {
                averageText = "-";
                averageColor = GtswnGuiPalette.TEXT_MUTED;
            } else {
                averageText = formatAEMonitorRate(avgRate, displayMode);
                averageColor = rateColor(avgRate);
            }
        }
        host.font()
            .drawString(realtimeText, x + 170, y + 6, realtimeColor);
        host.font()
            .drawString(averageText, x + 260, y + 6, averageColor);

    }

    private static String formatAEMonitorAmount(long amount, int displayMode) {
        BigInteger value = BigInteger.valueOf(amount);
        switch (displayMode) {
            case 1:
                return FormatUtil.formatScientific(value);
            case 2:
                return FormatUtil.formatMetric(value, 2);
            case 0:
            default:
                return FormatUtil.formatNormal(value);
        }
    }

    /** 格式化 AE 监控变化速率（根据显示模式切换常规/科学/千位计数） */
    private static String formatAEMonitorRate(double rate, int displayMode) {
        switch (displayMode) {
            case 1:
                return FormatUtil.formatScientificDouble(rate);
            case 2:
                return FormatUtil.formatMetricDouble(rate, 2);
            case 0:
            default:
                return FormatUtil.formatNormalDouble(rate);
        }
    }

    /** 根据变化速率返回颜色：正绿、负红、零灰（契约 §5 色值映射 STATE_ONLINE/STATE_OFFLINE/TEXT_MUTED） */
    private static int rateColor(double rate) {
        if (rate > 0.0D) {
            return GtswnGuiPalette.STATE_ONLINE;
        }
        if (rate < 0.0D) {
            return GtswnGuiPalette.STATE_OFFLINE;
        }
        return GtswnGuiPalette.TEXT_MUTED;
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }
}
