package com.miaokatze.gtswn.client.render;

import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.StatCollector;
import net.minecraftforge.fluids.FluidStack;

import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.common.panel.AEMonitorDataSet;
import com.miaokatze.gtswn.common.panel.AEMonitorSample;
import com.miaokatze.gtswn.common.panel.NetworkInfoSample;
import com.miaokatze.gtswn.common.panel.NetworkScreen;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.common.util.FormatUtil;

public class RenderNetworkInfoPanel extends TileEntitySpecialRenderer {

    private static final int ENERGY_COLOR = 0x1F6FFF;
    private static final int EUT_COLOR = 0xFF7A18;
    private static final int AXIS_COLOR = 0x4E5964;
    private static final int TICK_COLOR = 0x6A7680;
    private static final int AE_LINE_DEFAULT_COLOR = 0x1F6FFF;

    @Override
    public void renderTileEntityAt(TileEntity tile, double x, double y, double z, float partialTicks) {
        if (!(tile instanceof TileEntityNetworkInfoPanel)) {
            return;
        }
        TileEntityNetworkInfoPanel panel = (TileEntityNetworkInfoPanel) tile;
        NetworkScreen screen = panel.getScreen();
        int widthBlocks = screen == null ? 1 : Math.max(1, screen.getWidth());
        int heightBlocks = screen == null ? 1 : Math.max(1, screen.getHeight());

        GL11.glPushMatrix();
        setupFaceTransform(panel.getBlockMetadata(), x, y, z);
        float scale = 1.0F / 128.0F;
        GL11.glScalef(scale, -scale, scale);
        GL11.glTranslatef(
            -64.0F - getCoreHorizontalOffset(panel, screen) * 128.0F,
            -64.0F - getCoreVerticalOffset(panel, screen) * 128.0F,
            0.0F);

        int pixelWidth = widthBlocks * 128;
        int pixelHeight = heightBlocks * 128;
        drawPanel(panel, pixelWidth, pixelHeight);

        GL11.glPopMatrix();
    }

    private int getCoreHorizontalOffset(TileEntityNetworkInfoPanel panel, NetworkScreen screen) {
        if (screen == null) {
            return 0;
        }
        int facing = screen.facing;
        if (facing == 2 || facing == 3) {
            return panel.xCoord - screen.minX;
        }
        return panel.zCoord - screen.minZ;
    }

    private int getCoreVerticalOffset(TileEntityNetworkInfoPanel panel, NetworkScreen screen) {
        if (screen == null) {
            return 0;
        }
        return screen.maxY - panel.yCoord;
    }

    private void setupFaceTransform(int facing, double x, double y, double z) {
        switch (facing) {
            case 2:
                GL11.glTranslated(x + 0.5D, y + 0.5D, z - 0.002D);
                GL11.glRotatef(180F, 0F, 1F, 0F);
                break;
            case 4:
                GL11.glTranslated(x - 0.002D, y + 0.5D, z + 0.5D);
                GL11.glRotatef(-90F, 0F, 1F, 0F);
                break;
            case 5:
                GL11.glTranslated(x + 1.002D, y + 0.5D, z + 0.5D);
                GL11.glRotatef(90F, 0F, 1F, 0F);
                break;
            case 3:
            default:
                GL11.glTranslated(x + 0.5D, y + 0.5D, z + 1.002D);
                break;
        }
    }

    private void drawPanel(TileEntityNetworkInfoPanel panel, int width, int height) {
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        int edge = Math.max(8, Math.min(16, Math.min(width, height) / 18));
        int safe = edge + 10;
        int briefHeight = Math.max(34, height * 15 / 100);
        briefHeight = Math.min(briefHeight, height - safe * 2 - 50);
        boolean cullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean depthEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean fogEnabled = GL11.glIsEnabled(GL11.GL_FOG);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_FOG);
        GL11.glDisable(GL11.GL_CULL_FACE);

        // v1.4.5：将光照贴图设为全亮(240,240)，避免环境光照导致 TESR 渲染颜色偏淡
        // 保存当前 lightmap 坐标以便渲染结束后恢复
        float prevLightmapX = OpenGlHelper.lastBrightnessX;
        float prevLightmapY = OpenGlHelper.lastBrightnessY;
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);

        if (panel.hasScreenBackgroundColor()) {
            fillRect(0, 0, width, height, panel.getScreenBackgroundColor());
        }
        // v1.4.5：移除额外外边框渲染，由方块本身承担边界

        int currentTab = panel.getCurrentTab();
        if (currentTab == 0) {
            drawEUPanel(panel, font, safe, width, height, briefHeight);
        } else if (currentTab == 1) {
            drawAEChartPanel(panel, font, safe, width, height, briefHeight);
        } else if (currentTab == 2) {
            drawAEMonitorPanel(panel, font, safe, width, height);
        }

        if (depthEnabled) {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
        } else {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
        }
        if (fogEnabled) {
            GL11.glEnable(GL11.GL_FOG);
        } else {
            GL11.glDisable(GL11.GL_FOG);
        }
        // 恢复光照贴图坐标，避免影响后续方块/实体渲染
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, prevLightmapX, prevLightmapY);
        GL11.glEnable(GL11.GL_LIGHTING);
        if (cullEnabled) {
            GL11.glEnable(GL11.GL_CULL_FACE);
        } else {
            GL11.glDisable(GL11.GL_CULL_FACE);
        }
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    /** 绘制 EU 标签页：简报 + 走势图表（原 drawPanel 主体逻辑，保持行为不变） */
    private void drawEUPanel(TileEntityNetworkInfoPanel panel, FontRenderer font, int safe, int width, int height,
        int briefHeight) {
        drawBrief(panel, font, safe, width - safe * 2, safe, briefHeight);

        int chartTop = safe + briefHeight + 12;
        int chartBottom = height - safe;
        if (chartBottom - chartTop > 42) {
            drawChart(panel, safe, chartTop, width - safe * 2, chartBottom - chartTop);
        }
    }

    /** 绘制 AE 走势图标签页（v1.5.5） */
    private void drawAEChartPanel(TileEntityNetworkInfoPanel panel, FontRenderer font, int safe, int width, int height,
        int briefHeight) {
        ItemStack item = panel.getChartItem();
        FluidStack fluid = panel.getChartFluid();

        // 未绑定：居中显示空提示
        if (item == null && fluid == null) {
            drawCentered(
                font,
                tr("gtswn.network_info.screen.ae_chart_empty"),
                safe,
                width - safe * 2,
                height / 2 - 4,
                0x777777);
            return;
        }

        // 顶部简报区：左侧图标，右侧名称/存量/速率
        // 图标尺寸做上限，避免凸出屏幕；垂直居中显示
        int iconSize = Math.min(briefHeight, 28);
        int iconX = safe;
        int iconY = safe + (briefHeight - iconSize) / 2;
        String name;
        if (item != null) {
            drawItemIconTESR(item, iconX, iconY, iconSize);
            name = item.getDisplayName();
        } else {
            drawFluidIconTESR(fluid, iconX, iconY, iconSize);
            name = fluid.getLocalizedName();
        }

        int textX = iconX + briefHeight + 8;
        int textY = iconY + 2;
        int textW = width - textX - safe;
        font.drawString(font.trimStringToWidth(name, textW), textX, textY, 0x26323D);

        List<AEMonitorSample> samples = panel.getAEChartSamples();
        AEMonitorSample newest = samples.isEmpty() ? null : samples.get(samples.size() - 1);
        int displayMode = panel.getDisplayMode();

        textY += 12;
        String amountText = newest == null ? tr("gtswn.network_info.screen.collecting_value")
            : formatAEMonitorAmount(newest.amount, displayMode);
        font.drawString(
            StatCollector.translateToLocalFormatted("gtswn.network_info.screen.ae_chart_current", amountText),
            textX,
            textY,
            0x34404A);

        textY += 12;
        String rateText = newest == null ? "-" : formatAEMonitorRate(newest.rate, displayMode);
        String rateLabel = StatCollector.translateToLocalFormatted("gtswn.network_info.screen.ae_chart_rate", rateText);
        int rateColor = newest == null ? 0x6B7680 : rateColor(newest.rate);
        font.drawString(rateLabel, textX, textY, rateColor);

        // 中部走势图区：与 EU 图表相同的边距、坐标轴、Catmull-Rom 样条
        int chartTop = safe + briefHeight + 12;
        int chartBottom = height - safe - 16;
        if (chartBottom - chartTop > 42) {
            int plotX = safe + 54;
            int plotY = chartTop + 22;
            int plotW = width - safe * 2 - 108;
            int plotH = chartBottom - chartTop - 52;
            if (plotW > 20 && plotH > 16) {
                Integer chartBg = panel.getAEChartBackgroundColor();
                if (chartBg != null) {
                    fillRect(plotX, plotY, plotW, plotH, chartBg.intValue());
                }

                if (samples.size() < 2) {
                    drawAEAcademicAxes(panel, font, plotX, plotY, plotW, plotH, null, null);
                    drawCentered(
                        font,
                        StatCollector.translateToLocalFormatted("gtswn.network_info.screen.collecting", samples.size()),
                        plotX,
                        plotW,
                        plotY + plotH / 2 - 4,
                        0x777777);
                } else {
                    // 分别提取存量与变化率序列
                    double[] amounts = new double[samples.size()];
                    double[] rates = new double[samples.size()];
                    for (int i = 0; i < samples.size(); i++) {
                        AEMonitorSample s = samples.get(i);
                        amounts[i] = s.amount;
                        rates[i] = s.rate;
                    }
                    boolean showAmount = panel.isShowAEChartAmount();
                    boolean showRate = panel.isShowAEChartRate();

                    if (!showAmount && !showRate) {
                        // 两条走势线都被关闭时给出明确提示
                        drawAEAcademicAxes(panel, font, plotX, plotY, plotW, plotH, null, null);
                        drawCentered(
                            font,
                            tr("gtswn.network_info.screen.ae_chart_no_line"),
                            plotX,
                            plotW,
                            plotY + plotH / 2 - 4,
                            0x777777);
                    } else {
                        // 复用同一 AE Y 轴字段同时约束存量和变化率（简化实现，后续可按需分离）
                        double[] amountRange = showAmount ? range(amounts, panel.getAEAxisMin(), panel.getAEAxisMax())
                            : null;
                        double[] rateRange = showRate ? range(rates, panel.getAEAxisMin(), panel.getAEAxisMax()) : null;
                        drawAEAcademicAxes(panel, font, plotX, plotY, plotW, plotH, amountRange, rateRange);

                        Integer lineColorObj = panel.getAELineColor();
                        int amountColor = lineColorObj == null ? AE_LINE_DEFAULT_COLOR : lineColorObj.intValue();
                        if (showAmount) {
                            drawSeries(
                                amounts,
                                amountRange,
                                plotX,
                                plotY,
                                plotW,
                                plotH,
                                amountColor,
                                panel.getAETrendLineThickness(),
                                panel.getAETrendLineSmoothing());
                        }
                        if (showRate) {
                            // 变化率曲线使用绿色，线宽与样条密度与存量线一致
                            drawSeries(
                                rates,
                                rateRange,
                                plotX,
                                plotY,
                                plotW,
                                plotH,
                                0x4CAF50,
                                panel.getAETrendLineThickness(),
                                panel.getAETrendLineSmoothing());
                        }
                    }
                }
            }
        }

        // 底部提示文字
        drawCentered(
            font,
            tr("gtswn.network_info.screen.ae_chart_hint"),
            safe,
            width - safe * 2,
            height - safe - 12,
            0x6B7680);
    }

    /** 绘制 AE 走势图坐标轴（v1.5.5 支持左右双 Y 轴：左存量、右变化率） */
    private void drawAEAcademicAxes(TileEntityNetworkInfoPanel panel, FontRenderer font, int x, int y, int w, int h,
        double[] amountRange, double[] rateRange) {
        int thickness = panel.getAEChartBorderThickness();
        fillRect(x, y, w + thickness, thickness, AXIS_COLOR);
        fillRect(x, y + h, w + thickness, thickness, AXIS_COLOR);
        fillRect(x, y, thickness, h + thickness, AXIS_COLOR);
        fillRect(x + w, y, thickness, h + thickness, AXIS_COLOR);

        for (int i = 0; i < 5; i++) {
            int ty = y + h - i * h / 4;
            fillRect(x - 4, ty, 4, Math.max(1, thickness), TICK_COLOR);
            fillRect(x + w, ty, 4, Math.max(1, thickness), TICK_COLOR);
            if (amountRange != null) {
                double value = amountRange[0] + (amountRange[1] - amountRange[0]) * i / 4.0D;
                String label = sci(value);
                font.drawString(label, x - 8 - font.getStringWidth(label), ty - 4, AXIS_COLOR);
            }
            if (rateRange != null) {
                double value = rateRange[0] + (rateRange[1] - rateRange[0]) * i / 4.0D;
                String label = sci(value);
                font.drawString(label, x + w + 8, ty - 4, 0x4CAF50);
            }
        }
        for (int i = 0; i < 5; i++) {
            int tx = x + i * w / 4;
            fillRect(tx, y + h, Math.max(1, thickness), 4, TICK_COLOR);
            String label = i == 0 ? "-" + aeWindowName(panel.getAETrackingWindow())
                : i == 4 ? tr("gtswn.network_info.screen.now") : "";
            if (!label.isEmpty()) {
                font.drawString(label, tx - font.getStringWidth(label) / 2, y + h + 8, 0x4E5964);
            }
        }
        drawCentered(font, tr("gtswn.network_info.screen.time_axis"), x, w, y + h + 20, 0x4E5964);
        if (amountRange != null) {
            drawRotated(font, tr("gtswn.network_info.screen.ae_axis"), x - 56, y + h / 2 + 42, AXIS_COLOR);
        }
        if (rateRange != null) {
            drawRotated(font, tr("gtswn.network_info.screen.ae_rate_axis"), x + w + 48, y + h / 2 + 38, 0x4CAF50);
        }
    }

    /** 绘制 AE 实时监控标签页（v1.5.4） */
    private void drawAEMonitorPanel(TileEntityNetworkInfoPanel panel, FontRenderer font, int safe, int width,
        int height) {
        List<ItemStack> items = panel.getMonitoredItems();
        List<FluidStack> fluids = panel.getMonitoredFluids();
        int displayMode = panel.getDisplayMode();
        Map<String, AEMonitorSample> latest = panel.getAEMonitorLatest();
        Map<String, Double> avg300s = panel.getAEMonitorAvg300s();

        // 顶部标题与 AE 在线状态（客户端无 gridProxy，通过是否收到监控数据推断）
        int titleY = safe;
        font.drawString(tr("gtswn.network_info.screen.ae_monitor_title"), safe, titleY, 0x26323D);
        boolean online = hasAEMonitorData(panel);
        String statusKey = online ? "gtswn.network_info.screen.ae_online" : "gtswn.network_info.screen.ae_offline";
        int statusColor = online ? 0x4CAF50 : 0xF44336;
        String status = tr(statusKey);
        font.drawString(status, width - safe - font.getStringWidth(status), titleY, statusColor);

        // 列表区域
        int rowY = titleY + 16;
        int rowHeight = 18;
        int bottomLimit = height - safe;
        int iconX = safe;
        int nameX = iconX + 20;
        int amountX = safe + (width - safe * 2) * 50 / 100;
        int rateX = safe + (width - safe * 2) * 72 / 100;
        int avgX = safe + (width - safe * 2) * 86 / 100;

        if (items.isEmpty() && fluids.isEmpty()) {
            drawCentered(
                font,
                tr("gtswn.network_info.screen.ae_monitor_empty"),
                safe,
                width - safe * 2,
                height / 2 - 4,
                0x777777);
        } else {
            // 表头
            font.drawString(tr("gtswn.network_info.screen.ae_monitor_header_name"), nameX, rowY, 0x4C5660);
            font.drawString(tr("gtswn.network_info.screen.ae_monitor_header_amount"), amountX, rowY, 0x4C5660);
            font.drawString(tr("gtswn.network_info.screen.ae_monitor_header_rate"), rateX, rowY, 0x4C5660);
            font.drawString(tr("gtswn.network_info.screen.ae_monitor_header_avg"), avgX, rowY, 0x4C5660);
            rowY += 12;

            // 物品行
            for (ItemStack stack : items) {
                if (rowY + rowHeight > bottomLimit) break;
                String key = TileEntityNetworkInfoPanel.getAEKey(stack);
                AEMonitorSample sample = key == null ? null : latest.get(key);
                Double avg = key == null ? null : avg300s.get(key);
                drawAEMonitorRow(font, stack, null, sample, avg, rowY, displayMode, iconX, nameX, amountX, rateX, avgX);
                rowY += rowHeight;
            }
            // 流体行
            for (FluidStack fluid : fluids) {
                if (rowY + rowHeight > bottomLimit) break;
                String key = TileEntityNetworkInfoPanel.getAEKey(fluid);
                AEMonitorSample sample = key == null ? null : latest.get(key);
                Double avg = key == null ? null : avg300s.get(key);
                drawAEMonitorRow(font, null, fluid, sample, avg, rowY, displayMode, iconX, nameX, amountX, rateX, avgX);
                rowY += rowHeight;
            }
        }

        // 离线遮罩提示
        if (!online) {
            drawCentered(
                font,
                tr("gtswn.network_info.screen.ae_offline_overlay"),
                safe,
                width - safe * 2,
                height / 2 + 10,
                0xF44336);
        }
    }

    /** 绘制 AE 实时监控列表中的一行 */
    private void drawAEMonitorRow(FontRenderer font, ItemStack item, FluidStack fluid, AEMonitorSample sample,
        Double avg, int rowY, int displayMode, int iconX, int nameX, int amountX, int rateX, int avgX) {
        String name;
        // 列表图标尺寸限制为行高 - 2，防止行高变化时图标凸出
        int iconSize = Math.min(16, 18 - 2);
        int iconY = rowY + (18 - iconSize) / 2;
        if (item != null) {
            drawItemIconTESR(item, iconX, iconY, iconSize);
            name = item.getDisplayName();
        } else {
            drawFluidIconTESR(fluid, iconX, iconY, iconSize);
            name = fluid.getLocalizedName();
        }

        int nameMaxW = amountX - nameX - 4;
        font.drawString(font.trimStringToWidth(name, nameMaxW), nameX, rowY + 4, 0x2F3640);

        String amountText = sample == null ? "-" : formatAEMonitorAmount(sample.amount, displayMode);
        font.drawString(amountText, amountX, rowY + 4, 0x2F3640);

        double rate = sample == null ? 0.0D : sample.rate;
        String rateText = sample == null ? "-" : formatAEMonitorRate(rate, displayMode);
        int rateCol = sample == null ? 0x6B7680 : rateColor(rate);
        font.drawString(rateText, rateX, rowY + 4, rateCol);

        String avgText = avg == null ? "-" : formatAEMonitorRate(avg.doubleValue(), displayMode);
        int avgCol = avg == null ? 0x6B7680 : rateColor(avg.doubleValue());
        font.drawString(avgText, avgX, rowY + 4, avgCol);
    }

    /**
     * 在 TESR 坐标系中渲染物品图标。
     * <p>
     * TESR 当前模型视图矩阵 Y 轴为负缩放（1 单位 = 1/128 方块，Y 向下增长），因此传入的 (x,y) 对应图标左上角。
     * 方法内部保存/恢复矩阵与光照状态，避免影响后续渲染。
     *
     * @param stack 物品堆
     * @param x     图标左上角 X（TESR 单位）
     * @param y     图标左上角 Y（TESR 单位，向下增长）
     * @param size  图标边长（TESR 单位）
     */
    private void drawItemIconTESR(ItemStack stack, int x, int y, int size) {
        if (stack == null) {
            return;
        }
        // 防御性限制图标尺寸，避免过大的图标凸出屏幕或产生深度冲突
        int iconSize = Math.min(size, 32);
        Minecraft mc = Minecraft.getMinecraft();
        GL11.glPushMatrix();
        // Z 偏移从 5.0F 降到 0.75F，降低图标凸出信息屏表面的视觉悬突
        GL11.glTranslatef(x, y, 0.75F);
        float localScale = iconSize / 16.0F;
        GL11.glScalef(localScale, localScale, localScale);
        boolean lightingEnabled = GL11.glIsEnabled(GL11.GL_LIGHTING);
        RenderHelper.enableGUIStandardItemLighting();
        RenderItem.getInstance()
            .renderItemAndEffectIntoGUI(mc.fontRenderer, mc.getTextureManager(), stack, 0, 0);
        RenderHelper.disableStandardItemLighting();
        if (lightingEnabled) {
            GL11.glEnable(GL11.GL_LIGHTING);
        } else {
            GL11.glDisable(GL11.GL_LIGHTING);
        }
        GL11.glPopMatrix();
    }

    /**
     * 在 TESR 坐标系中渲染流体颜色块图标。
     *
     * @param fluid 流体堆
     * @param x     图标左上角 X（TESR 单位）
     * @param y     图标左上角 Y（TESR 单位，向下增长）
     * @param size  图标边长（TESR 单位）
     */
    private void drawFluidIconTESR(FluidStack fluid, int x, int y, int size) {
        if (fluid == null || fluid.getFluid() == null) {
            return;
        }
        // 流体图标同样做尺寸上限，避免简报区大图标凸出
        int iconSize = Math.min(size, 32);
        int color = fluid.getFluid()
            .getColor(fluid);
        fillRect(x, y, iconSize, iconSize, 0xFF000000 | color);
    }

    /** 客户端没有 AE gridProxy，通过是否存在监控数据推断在线状态 */
    private static boolean hasAEMonitorData(TileEntityNetworkInfoPanel panel) {
        return !panel.getAEMonitorLatest()
            .isEmpty()
            || !panel.getAEChartSamples()
                .isEmpty();
    }

    private void drawBrief(TileEntityNetworkInfoPanel panel, FontRenderer font, int x, int w, int y, int h) {
        float scale = panel.getBriefFontScale();
        int lineCount = 1 + (panel.isShowBriefEnergy() ? 1 : 0) + (panel.isShowBriefStatus() ? 1 : 0);
        int lineStep = Math.max(10, Math.round(10.0F * scale));
        int lineY = y + Math.max(0, (h - lineCount * lineStep) / 2);
        drawScaledCentered(font, tr("gtswn.network_info.screen.title"), x, w, lineY, 0x26323D, scale);
        lineY += lineStep;
        if (panel.isShowBriefEnergy()) {
            drawScaledCentered(
                font,
                StatCollector.translateToLocalFormatted(
                    "gtswn.network_info.screen.energy",
                    // EU 根据 displayMode 切换常规/科学计数
                    panel.getDisplayMode() == 0 ? FormatUtil.formatBigInteger(panel.getCachedEu())
                        : FormatUtil.formatScientific(panel.getCachedEu())),
                x,
                w,
                lineY,
                ENERGY_COLOR,
                scale);
            lineY += lineStep;
        }
        if (panel.isShowBriefStatus()) {
            drawScaledCentered(
                font,
                StatCollector.translateToLocalFormatted("gtswn.network_info.screen.status", panel.getCachedStatus()),
                x,
                w,
                lineY,
                EUT_COLOR,
                scale);
        }
    }

    private void drawChart(TileEntityNetworkInfoPanel panel, int x, int y, int w, int h) {
        if (w <= 70 || h <= 55 || (!panel.isShowChartEnergy() && !panel.isShowChartStatus())) {
            return;
        }
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        String title = StatCollector
            .translateToLocalFormatted("gtswn.network_info.screen.history", panel.getWindowName());
        drawCentered(font, title, x, w, y, 0x34404A);

        int plotX = x + 54;
        int plotY = y + 22;
        int plotW = w - 108;
        int plotH = h - 52;
        if (plotW <= 20 || plotH <= 16) {
            return;
        }
        Integer chartBg = panel.getChartBackgroundColor();
        if (chartBg != null) {
            fillRect(plotX, plotY, plotW, plotH, chartBg.intValue());
        }

        List<NetworkInfoSample> samples = panel.getCachedSamples();
        if (samples.size() < 2) {
            drawAcademicAxes(panel, font, plotX, plotY, plotW, plotH, null, null);
            drawCentered(
                font,
                StatCollector.translateToLocalFormatted("gtswn.network_info.screen.collecting", samples.size()),
                plotX,
                plotW,
                plotY + plotH / 2 - 4,
                0x777777);
            return;
        }

        double[] energyValues = panel.isShowChartEnergy() ? energyValues(samples) : null;
        double[] eutValues = panel.isShowChartStatus() ? eutValues(samples) : null;
        double[] energyRange = energyValues == null ? null
            : range(energyValues, panel.getEnergyAxisMin(), panel.getEnergyAxisMax());
        double[] eutRange = eutValues == null ? null : range(eutValues, panel.getEutAxisMin(), panel.getEutAxisMax());
        drawAcademicAxes(panel, font, plotX, plotY, plotW, plotH, energyRange, eutRange);
        // chart 固定 overlay 布局：EU 与 EU/t 叠加在同一绘图区
        if (energyValues != null) {
            drawSeries(
                energyValues,
                energyRange,
                plotX,
                plotY,
                plotW,
                plotH,
                ENERGY_COLOR,
                panel.getTrendLineThickness(),
                panel.getTrendLineSmoothing());
        }
        if (eutValues != null) {
            drawSeries(
                eutValues,
                eutRange,
                plotX,
                plotY,
                plotW,
                plotH,
                EUT_COLOR,
                panel.getTrendLineThickness(),
                panel.getTrendLineSmoothing());
        }
    }

    private void drawAcademicAxes(TileEntityNetworkInfoPanel panel, FontRenderer font, int x, int y, int w, int h,
        double[] energyRange, double[] eutRange) {
        int thickness = panel.getChartBorderThickness();
        fillRect(x, y, w + thickness, thickness, AXIS_COLOR);
        fillRect(x, y + h, w + thickness, thickness, AXIS_COLOR);
        fillRect(x, y, thickness, h + thickness, AXIS_COLOR);
        fillRect(x + w, y, thickness, h + thickness, AXIS_COLOR);

        for (int i = 0; i < 5; i++) {
            int ty = y + h - i * h / 4;
            fillRect(x - 4, ty, 4, Math.max(1, thickness), TICK_COLOR);
            fillRect(x + w, ty, 4, Math.max(1, thickness), TICK_COLOR);
            if (energyRange != null) {
                double value = energyRange[0] + (energyRange[1] - energyRange[0]) * i / 4.0D;
                String label = sci(value);
                font.drawString(label, x - 8 - font.getStringWidth(label), ty - 4, ENERGY_COLOR);
            }
            if (eutRange != null) {
                double value = eutRange[0] + (eutRange[1] - eutRange[0]) * i / 4.0D;
                font.drawString(sci(value), x + w + 8, ty - 4, EUT_COLOR);
            }
        }
        for (int i = 0; i < 5; i++) {
            int tx = x + i * w / 4;
            fillRect(tx, y + h, Math.max(1, thickness), 4, TICK_COLOR);
            String label = i == 0 ? "-" + panel.getWindowName() : i == 4 ? tr("gtswn.network_info.screen.now") : "";
            if (!label.isEmpty()) {
                font.drawString(label, tx - font.getStringWidth(label) / 2, y + h + 8, 0x4E5964);
            }
        }
        drawCentered(font, tr("gtswn.network_info.screen.time_axis"), x, w, y + h + 20, 0x4E5964);
        if (energyRange != null) {
            drawRotated(font, tr("gtswn.network_info.screen.energy_axis"), x - 56, y + h / 2 + 42, ENERGY_COLOR);
        }
        if (eutRange != null) {
            drawRotated(font, tr("gtswn.network_info.screen.eut_axis"), x + w + 48, y + h / 2 + 38, EUT_COLOR);
        }
    }

    private void drawSeries(double[] values, double[] range, int x, int y, int w, int h, int color, int thickness,
        int smoothing) {
        if (values == null || values.length < 2 || h <= 2 || range == null) {
            return;
        }
        double min = range[0];
        double max = range[1];
        // smoothing 配置映射为样条分段数：0=线性(1段)，1..12 → 4..26 段
        int segments = smoothing <= 0 ? 1 : smoothing * 2 + 2;
        double[][] path = catmullRomPath(values, segments);

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glLineWidth(thickness);
        setColor(color);
        GL11.glBegin(GL11.GL_LINE_STRIP);
        double denom = values.length - 1;
        for (int i = 0; i < path.length; i++) {
            double normalized = (path[i][1] - min) / (max - min);
            // clamp 到 [0,1] 防止样条过冲溢出图表区
            normalized = Math.max(0.0D, Math.min(1.0D, normalized));
            double px = x + path[i][0] / denom * w;
            double py = y + h - normalized * h;
            GL11.glVertex3d(px, py, 0.0D);
        }
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    private static double[] energyValues(List<NetworkInfoSample> samples) {
        double[] values = new double[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            values[i] = samples.get(i).eu.doubleValue();
        }
        return values;
    }

    private static double[] eutValues(List<NetworkInfoSample> samples) {
        double[] values = new double[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            values[i] = samples.get(i).eut;
        }
        return values;
    }

    private static double[] range(double[] values, Double customMin, Double customMax) {
        double min = values[0];
        double max = values[0];
        // 扫描样品求真实数据范围
        for (double value : values) {
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        // 用户自定义覆盖对应边界（null 表示该端走自动模式）
        if (customMin != null) {
            min = customMin.doubleValue();
        }
        if (customMax != null) {
            max = customMax.doubleValue();
        }
        // 退化兜底：跨度近乎 0 或反转时，用 center ± 0.5 作为可视范围
        // 合成范围不再追加冗余，保持与旧版一致的退化行为
        if (Math.abs(max - min) < 0.000001D || max < min) {
            double center = (max + min) / 2.0D;
            min = center - 0.5D;
            max = center + 0.5D;
            return new double[] { min, max };
        }
        // 自动模式（两端均未自定义）应用上下 10% 冗余，避免曲线贴边
        // 冗余基于数据跨度计算：newMin = min - span*0.1，newMax = max + span*0.1
        if (customMin == null && customMax == null) {
            double span = max - min;
            min = min - span * 0.1D;
            max = max + span * 0.1D;
        }
        return new double[] { min, max };
    }

    /**
     * 用 Catmull-Rom 样条曲线在样品点之间生成密集顶点路径。
     * 样条经过每个样品点（p1、p2 为段端点）；两端用线性外推虚拟点处理边界。
     * X 等间距索引映射：path[i][0] = 索引坐标（浮点），path[i][1] = 值。
     *
     * @param values          样品值数组（已按时间索引化，X 等间距）
     * @param segmentsPerSpan 每相邻两点间的插值段数（≥1；1 = 线性）
     * @return 密集顶点路径，长度 = (values.length - 1) * segmentsPerSpan + 1
     */
    private static double[][] catmullRomPath(double[] values, int segmentsPerSpan) {
        int n = values.length;
        if (n < 2 || segmentsPerSpan < 1) {
            // 退化情况：直接返回原始点
            double[][] path = new double[n][2];
            for (int i = 0; i < n; i++) {
                path[i][0] = i;
                path[i][1] = values[i];
            }
            return path;
        }
        int total = (n - 1) * segmentsPerSpan + 1;
        double[][] path = new double[total][2];
        path[0][0] = 0D;
        path[0][1] = values[0];

        int idx = 1;
        for (int i = 0; i < n - 1; i++) {
            // 4 控制点：p1=段起点, p2=段终点, p0/p3=相邻点（首尾线性外推）
            double p0 = (i == 0) ? 2 * values[0] - values[1] : values[i - 1];
            double p1 = values[i];
            double p2 = values[i + 1];
            double p3 = (i + 2 >= n) ? 2 * values[n - 1] - values[n - 2] : values[i + 2];

            for (int s = 1; s <= segmentsPerSpan; s++) {
                double t = (double) s / segmentsPerSpan;
                double t2 = t * t;
                double t3 = t2 * t;
                // 标准 Catmull-Rom 基矩阵（张力 0.5）
                double y = 0.5D * (2 * p1 + (-p0 + p2) * t
                    + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2
                    + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
                path[idx][0] = i + t;
                path[idx][1] = y;
                idx++;
            }
        }
        return path;
    }

    private void fillRect(int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0) {
            return;
        }
        boolean cullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_BLEND);
        setColor(color);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex3d(x, y, 0.0D);
        GL11.glVertex3d(x + w, y, 0.0D);
        GL11.glVertex3d(x + w, y + h, 0.0D);
        GL11.glVertex3d(x, y + h, 0.0D);
        GL11.glEnd();
        if (blendEnabled) {
            GL11.glEnable(GL11.GL_BLEND);
        } else {
            GL11.glDisable(GL11.GL_BLEND);
        }
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        if (cullEnabled) {
            GL11.glEnable(GL11.GL_CULL_FACE);
        } else {
            GL11.glDisable(GL11.GL_CULL_FACE);
        }
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    private void drawCentered(FontRenderer font, String text, int x, int w, int y, int color) {
        font.drawString(text, x + (w - font.getStringWidth(text)) / 2, y, color);
    }

    private void drawScaledCentered(FontRenderer font, String text, int x, int w, int y, int color, float scale) {
        GL11.glPushMatrix();
        float centerX = x + w / 2.0F;
        GL11.glTranslatef(centerX, y, 0.0F);
        GL11.glScalef(scale, scale, 1.0F);
        font.drawString(text, -font.getStringWidth(text) / 2, 0, color);
        GL11.glPopMatrix();
    }

    private void drawRotated(FontRenderer font, String text, int x, int y, int color) {
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0.0F);
        GL11.glRotatef(-90.0F, 0.0F, 0.0F, 1.0F);
        font.drawString(text, 0, 0, color);
        GL11.glPopMatrix();
    }

    private void setColor(int color) {
        float r = ((color >> 16) & 255) / 255.0F;
        float g = ((color >> 8) & 255) / 255.0F;
        float b = (color & 255) / 255.0F;
        GL11.glColor4f(r, g, b, 1.0F);
    }

    private static String sci(double value) {
        if (Math.abs(value) < 0.000001D) {
            return "0";
        }
        double abs = Math.abs(value);
        int exp = (int) Math.floor(Math.log10(abs));
        double mantissa = abs / Math.pow(10.0D, exp);
        return (value < 0.0D ? "-" : "") + String.format(Locale.ROOT, "%.1fE%d", mantissa, exp);
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }

    /** 格式化 AE 监控存量（根据显示模式切换常规/科学计数） */
    private static String formatAEMonitorAmount(long amount, int displayMode) {
        BigInteger value = BigInteger.valueOf(amount);
        return displayMode == 0 ? FormatUtil.formatNormal(value) : FormatUtil.formatScientific(value);
    }

    /** 格式化 AE 监控变化速率（根据显示模式切换常规/科学计数） */
    private static String formatAEMonitorRate(double rate, int displayMode) {
        return displayMode == 0 ? FormatUtil.formatNormalDouble(rate) : FormatUtil.formatScientificDouble(rate);
    }

    /** 根据变化速率返回颜色：正绿、负红、零灰 */
    private static int rateColor(double rate) {
        if (rate > 0.0D) {
            return 0x4CAF50;
        }
        if (rate < 0.0D) {
            return 0xF44336;
        }
        return 0x6B7680;
    }

    /** 根据 AE 时间窗口常量返回显示名称 */
    private static String aeWindowName(int window) {
        switch (window) {
            case AEMonitorDataSet.WINDOW_1_HOUR:
                return "1h";
            case AEMonitorDataSet.WINDOW_8_HOUR:
                return "8h";
            case AEMonitorDataSet.WINDOW_24_HOUR:
                return "24h";
            case AEMonitorDataSet.WINDOW_5_MIN:
            default:
                return "5m";
        }
    }
}
