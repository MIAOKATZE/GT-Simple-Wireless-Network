package com.miaokatze.gtswn.client.render;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IIcon;
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
            : formatAEChartAmount(newest.amount, displayMode);
        font.drawString(
            StatCollector.translateToLocalFormatted("gtswn.network_info.screen.ae_chart_current", amountText),
            textX,
            textY,
            0x34404A);

        textY += 12;
        String rateText = newest == null ? "-" : formatAEChartRate(newest.rate, displayMode);
        String rateLabel = StatCollector.translateToLocalFormatted("gtswn.network_info.screen.ae_chart_rate", rateText);
        int rateColor = newest == null ? 0x6B7680 : rateColor(newest.rate);
        font.drawString(rateLabel, textX, textY, rateColor);

        // 平均变化速率（基于当前窗口61点首尾差值法，与AE实时监控的averageRate300s算法一致）
        // 数字保持默认深色，不按正负着色
        double avgRate = 0.0D;
        if (samples.size() >= 2) {
            AEMonitorSample first = samples.get(0);
            AEMonitorSample last = samples.get(samples.size() - 1);
            long tickDiff = last.tick - first.tick;
            if (tickDiff > 0L) {
                avgRate = (last.amount - first.amount) / (double) tickDiff * 1200.0D;
            }
        }
        String avgRateText = (newest == null || samples.size() < 2) ? "-" : formatAEChartRate(avgRate, displayMode);
        String avgRateLabel = StatCollector
            .translateToLocalFormatted("gtswn.network_info.screen.ae_chart_avg_rate", avgRateText);
        textY += 12;
        font.drawString(avgRateLabel, textX, textY, 0x34404A);

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
                    drawAEAcademicAxes(panel, font, plotX, plotY, plotW, plotH, null, null, displayMode);
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
                        drawAEAcademicAxes(panel, font, plotX, plotY, plotW, plotH, null, null, displayMode);
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
                        drawAEAcademicAxes(
                            panel,
                            font,
                            plotX,
                            plotY,
                            plotW,
                            plotH,
                            amountRange,
                            rateRange,
                            displayMode);

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
                            // 变化率曲线使用橙色（与无线EU网络EU/t线一致），线宽与样条密度与存量线一致
                            drawSeries(
                                rates,
                                rateRange,
                                plotX,
                                plotY,
                                plotW,
                                plotH,
                                0xFF7A18,
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
        double[] amountRange, double[] rateRange, int displayMode) {
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
                String label = formatAEChartAxis(value, displayMode);
                font.drawString(label, x - 8 - font.getStringWidth(label), ty - 4, AXIS_COLOR);
            }
            if (rateRange != null) {
                double value = rateRange[0] + (rateRange[1] - rateRange[0]) * i / 4.0D;
                String label = formatAEChartAxis(value, displayMode);
                font.drawString(label, x + w + 8, ty - 4, 0xFF7A18);
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
            drawRotated(font, tr("gtswn.network_info.screen.ae_rate_axis"), x + w + 48, y + h / 2 + 38, 0xFF7A18);
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
        // 在线状态文字使用深绿 0x2E7D32，与 AE 实时监控主题色一致
        int statusColor = online ? 0x2E7D32 : 0xF44336;
        String status = tr(statusKey);
        font.drawString(status, width - safe - font.getStringWidth(status), titleY, statusColor);

        int panelWidth = width - safe * 2;
        int listTop = titleY + 16;
        int availableHeight = height - safe - listTop;

        // 布局保护：面板可用空间过小时给出明确提示，避免后续除零或越界
        if (panelWidth < 32 || availableHeight < 24) {
            drawCentered(
                font,
                tr("gtswn.network_info.screen.ae_monitor_too_small"),
                safe,
                panelWidth,
                height / 2 - 4,
                0xF44336);
        } else if (items.isEmpty() && fluids.isEmpty()) {
            drawCentered(
                font,
                tr("gtswn.network_info.screen.ae_monitor_empty"),
                safe,
                panelWidth,
                height / 2 - 4,
                0x777777);
        } else {
            // 读取 AE 实时监控显示配置
            int renderMode = panel.getAEMonitorRenderMode();
            int iconSize = Math.min(panel.getAEMonitorIconSize(), 32);
            int fontSize = Math.max(8, Math.min(16, panel.getAEMonitorFontSize()));
            boolean bold = panel.isAEMonitorBold();

            if (renderMode == 1) {
                drawAEMonitorGrid(
                    font,
                    items,
                    fluids,
                    latest,
                    avg300s,
                    displayMode,
                    safe,
                    listTop,
                    panelWidth,
                    availableHeight,
                    iconSize,
                    fontSize,
                    bold);
            } else {
                drawAEMonitorList(
                    font,
                    items,
                    fluids,
                    latest,
                    avg300s,
                    displayMode,
                    safe,
                    listTop,
                    panelWidth,
                    availableHeight,
                    iconSize,
                    fontSize,
                    bold);
            }
        }

        // 离线遮罩提示
        if (!online) {
            drawCentered(
                font,
                tr("gtswn.network_info.screen.ae_offline_overlay"),
                safe,
                panelWidth,
                height / 2 + 10,
                0xF44336);
        }
    }

    /** 绘制 AE 实时监控列表中的一行 */
    private void drawAEMonitorRow(FontRenderer font, ItemStack item, FluidStack fluid, AEMonitorSample sample,
        Double avg, int rowY, int displayMode, int iconX, int nameX, int amountX, int rateX, int avgX, int iconSize,
        int fontSize, boolean bold, int rowHeight) {
        String name;
        // 图标尺寸由面板配置决定，上限已在调用处保护为 32；在行内垂直居中
        int actualIconSize = Math.min(iconSize, 32);
        int iconY = rowY + (rowHeight - actualIconSize) / 2;
        if (item != null) {
            drawItemIconTESR(item, iconX, iconY, actualIconSize);
            name = item.getDisplayName();
        } else {
            drawFluidIconTESR(fluid, iconX, iconY, actualIconSize);
            name = fluid.getLocalizedName();
        }

        // 根据配置字号计算缩放比例（基准为 12）
        float fontScale = fontSize / 12.0F;
        // 名称加粗时追加格式码
        String displayName = bold ? EnumChatFormatting.BOLD + name : name;
        // getStringWidth 返回未缩放宽度，裁剪宽度需除以缩放比例
        int nameMaxW = (int) ((amountX - nameX - 4) / fontScale);
        drawScaledString(font, font.trimStringToWidth(displayName, nameMaxW), nameX, rowY + 4, 0x2F3640, fontScale);

        String amountText = sample == null ? "-" : formatAEMonitorAmount(sample.amount, displayMode);
        drawScaledString(font, amountText, amountX, rowY + 4, 0x2F3640, fontScale);

        double rate = sample == null ? 0.0D : sample.rate;
        String rateText = sample == null ? "-" : formatAEMonitorRate(rate, displayMode);
        int rateCol = sample == null ? 0x6B7680 : rateColor(rate);
        drawScaledString(font, rateText, rateX, rowY + 4, rateCol, fontScale);

        String avgText = avg == null ? "-" : formatAEMonitorRate(avg.doubleValue(), displayMode);
        int avgCol = avg == null ? 0x6B7680 : rateColor(avg.doubleValue());
        drawScaledString(font, avgText, avgX, rowY + 4, avgCol, fontScale);
    }

    /** 绘制 AE 实时监控列表模式（renderMode = 0） */
    private void drawAEMonitorList(FontRenderer font, List<ItemStack> items, List<FluidStack> fluids,
        Map<String, AEMonitorSample> latest, Map<String, Double> avg300s, int displayMode, int safe, int listTop,
        int panelWidth, int availableHeight, int iconSize, int fontSize, boolean bold) {
        float fontScale = fontSize / 12.0F;
        // 行高由图标大小和字号共同决定，保证视觉协调
        int rowHeight = Math.max(iconSize + 4, fontSize + 8);
        int bottomLimit = listTop + availableHeight;
        int iconX = safe;
        int nameX = iconX + iconSize + 4;
        int amountX = safe + panelWidth * 50 / 100;
        int rateX = safe + panelWidth * 72 / 100;
        int avgX = safe + panelWidth * 86 / 100;

        int rowY = listTop;
        // 表头使用相同字号缩放，保持与数据行视觉一致
        drawScaledString(
            font,
            tr("gtswn.network_info.screen.ae_monitor_header_name"),
            nameX,
            rowY,
            0x4C5660,
            fontScale);
        drawScaledString(
            font,
            tr("gtswn.network_info.screen.ae_monitor_header_amount"),
            amountX,
            rowY,
            0x4C5660,
            fontScale);
        drawScaledString(
            font,
            tr("gtswn.network_info.screen.ae_monitor_header_rate"),
            rateX,
            rowY,
            0x4C5660,
            fontScale);
        drawScaledString(font, tr("gtswn.network_info.screen.ae_monitor_header_avg"), avgX, rowY, 0x4C5660, fontScale);
        // 表头占用高度随字号等比缩放（原设计为 12 像素）
        rowY += Math.round(12 * fontScale);

        // 物品行
        for (ItemStack stack : items) {
            if (rowY + rowHeight > bottomLimit) break;
            String key = TileEntityNetworkInfoPanel.getAEKey(stack);
            AEMonitorSample sample = key == null ? null : latest.get(key);
            Double avg = key == null ? null : avg300s.get(key);
            drawAEMonitorRow(
                font,
                stack,
                null,
                sample,
                avg,
                rowY,
                displayMode,
                iconX,
                nameX,
                amountX,
                rateX,
                avgX,
                iconSize,
                fontSize,
                bold,
                rowHeight);
            rowY += rowHeight;
        }
        // 流体行
        for (FluidStack fluid : fluids) {
            if (rowY + rowHeight > bottomLimit) break;
            String key = TileEntityNetworkInfoPanel.getAEKey(fluid);
            AEMonitorSample sample = key == null ? null : latest.get(key);
            Double avg = key == null ? null : avg300s.get(key);
            drawAEMonitorRow(
                font,
                null,
                fluid,
                sample,
                avg,
                rowY,
                displayMode,
                iconX,
                nameX,
                amountX,
                rateX,
                avgX,
                iconSize,
                fontSize,
                bold,
                rowHeight);
            rowY += rowHeight;
        }
    }

    /** 绘制 AE 实时监控网格模式（renderMode = 1） */
    private void drawAEMonitorGrid(FontRenderer font, List<ItemStack> items, List<FluidStack> fluids,
        Map<String, AEMonitorSample> latest, Map<String, Double> avg300s, int displayMode, int safe, int listTop,
        int panelWidth, int availableHeight, int iconSize, int fontSize, boolean bold) {
        float fontScale = fontSize / 12.0F;

        // 预计算所有监控项名称的最大未缩放宽度，作为格子宽度的依据
        int nameMaxWidth = 0;
        for (ItemStack stack : items) {
            nameMaxWidth = Math.max(nameMaxWidth, getAEMonitorNameWidth(font, stack, null, bold));
        }
        for (FluidStack fluid : fluids) {
            nameMaxWidth = Math.max(nameMaxWidth, getAEMonitorNameWidth(font, null, fluid, bold));
        }
        nameMaxWidth = Math.max(nameMaxWidth, font.getStringWidth("..."));

        int actualIconSize = Math.min(iconSize, 32);
        // 格子宽度 = 图标宽度 + 名称显示宽度 + 两侧边距
        int cellWidth = actualIconSize + (int) (nameMaxWidth * fontScale) + 16;
        int columns = Math.max(1, panelWidth / cellWidth);
        // 列宽均分，避免右侧留白过多
        int columnWidth = panelWidth / columns;

        // 每个格子高度：图标 + 四行文字（名称、存量、实时变化量、平均变化量）+ 间距
        int cellHeight = actualIconSize + fontSize * 4 + 14;
        int totalItems = items.size() + fluids.size();
        int rows = (totalItems + columns - 1) / columns;
        int visibleRows = Math.max(0, availableHeight / cellHeight);
        int endRow = Math.min(rows, visibleRows);

        // 按行优先顺序绘制可见格子
        for (int index = 0; index < totalItems; index++) {
            int row = index / columns;
            if (row >= endRow) break;
            int col = index % columns;
            int cellX = safe + col * columnWidth;
            int cellY = listTop + row * cellHeight;

            if (index < items.size()) {
                ItemStack stack = items.get(index);
                String key = TileEntityNetworkInfoPanel.getAEKey(stack);
                AEMonitorSample sample = key == null ? null : latest.get(key);
                Double avg = key == null ? null : avg300s.get(key);
                drawAEMonitorCell(
                    font,
                    stack,
                    null,
                    sample,
                    avg,
                    displayMode,
                    cellX,
                    cellY,
                    columnWidth,
                    iconSize,
                    fontSize,
                    bold);
            } else {
                FluidStack fluid = fluids.get(index - items.size());
                String key = TileEntityNetworkInfoPanel.getAEKey(fluid);
                AEMonitorSample sample = key == null ? null : latest.get(key);
                Double avg = key == null ? null : avg300s.get(key);
                drawAEMonitorCell(
                    font,
                    null,
                    fluid,
                    sample,
                    avg,
                    displayMode,
                    cellX,
                    cellY,
                    columnWidth,
                    iconSize,
                    fontSize,
                    bold);
            }
        }

        // 若内容被截断，在可用区域底部给出提示
        if (rows > visibleRows && visibleRows > 0) {
            int hintY = listTop + visibleRows * cellHeight + 2;
            if (hintY + fontSize <= listTop + availableHeight) {
                drawScaledString(
                    font,
                    tr("gtswn.network_info.screen.ae_monitor_grid_truncated"),
                    safe,
                    hintY,
                    0x6B7680,
                    fontScale);
            }
        }
    }

    /** 绘制 AE 实时监控网格中的一个格子 */
    private void drawAEMonitorCell(FontRenderer font, ItemStack item, FluidStack fluid, AEMonitorSample sample,
        Double avg, int displayMode, int cellX, int cellY, int cellWidth, int iconSize, int fontSize, boolean bold) {
        String name;
        int actualIconSize = Math.min(iconSize, 32);
        int iconX = cellX + (cellWidth - actualIconSize) / 2;
        int iconY = cellY + 4;
        if (item != null) {
            drawItemIconTESR(item, iconX, iconY, actualIconSize);
            name = item.getDisplayName();
        } else {
            drawFluidIconTESR(fluid, iconX, iconY, actualIconSize);
            name = fluid.getLocalizedName();
        }

        float fontScale = fontSize / 12.0F;
        String displayName = bold ? EnumChatFormatting.BOLD + name : name;
        int nameMaxW = (int) ((cellWidth - 8) / fontScale);
        String trimmedName = font.trimStringToWidth(displayName, nameMaxW);
        int nameW = font.getStringWidth(trimmedName);
        int nameX = cellX + (cellWidth - (int) (nameW * fontScale)) / 2;
        int nameY = iconY + actualIconSize + 2;
        drawScaledString(font, trimmedName, nameX, nameY, 0x2F3640, fontScale);

        // 存量：科学计数模式下小值回退为常规显示
        int amountMode = displayMode;
        if (sample != null && displayMode == 1 && Math.abs((double) sample.amount) < 10000.0D) {
            amountMode = 0;
        }
        String amountText = sample == null ? "-" : formatAEMonitorAmount(sample.amount, amountMode);
        int amountW = font.getStringWidth(amountText);
        int amountX = cellX + (cellWidth - (int) (amountW * fontScale)) / 2;
        int amountY = nameY + fontSize + 2;
        drawScaledString(font, amountText, amountX, amountY, 0x2F3640, fontScale);

        // 实时变化量：科学计数模式下小值回退为常规显示
        int rateMode = displayMode;
        if (sample != null && displayMode == 1 && Math.abs(sample.rate) < 10000.0D) {
            rateMode = 0;
        }
        int rateY = amountY + fontSize + 2;
        String rateText = sample == null ? "-" : formatAEMonitorRate(sample.rate, rateMode);
        int rateW = font.getStringWidth(rateText);
        int rateX = cellX + (cellWidth - (int) (rateW * fontScale)) / 2;
        int rateColor = sample == null ? 0x6B7680 : rateColor(sample.rate);
        drawScaledString(font, rateText, rateX, rateY, rateColor, fontScale);

        // 平均变化量（300秒均值）：科学计数模式下小值回退为常规显示
        int avgMode = displayMode;
        if (avg != null && displayMode == 1 && Math.abs(avg.doubleValue()) < 10000.0D) {
            avgMode = 0;
        }
        int avgY = rateY + fontSize + 2;
        String avgText = avg == null ? "-" : formatAEMonitorRate(avg.doubleValue(), avgMode);
        int avgW = font.getStringWidth(avgText);
        int avgX = cellX + (cellWidth - (int) (avgW * fontScale)) / 2;
        int avgColor = avg == null ? 0x6B7680 : rateColor(avg.doubleValue());
        drawScaledString(font, avgText, avgX, avgY, avgColor, fontScale);
    }

    /** 获取 AE 监控项名称的未缩放宽度，用于网格布局计算 */
    private static int getAEMonitorNameWidth(FontRenderer font, ItemStack item, FluidStack fluid, boolean bold) {
        String name;
        if (item != null) {
            name = item.getDisplayName();
        } else if (fluid != null) {
            name = fluid.getLocalizedName();
        } else {
            return 0;
        }
        if (bold) {
            name = EnumChatFormatting.BOLD + name;
        }
        return font.getStringWidth(name);
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
        if (stack == null) return;
        int iconSize = Math.min(size, 32);
        Minecraft mc = Minecraft.getMinecraft();
        GL11.glPushMatrix();
        // 极小的正向 Z 偏移
        GL11.glTranslatef(x, y, 0.001F);
        float localScale = iconSize / 16.0F;
        GL11.glScalef(localScale, localScale, localScale);
        // 关键：把 3D 物品沿 Z 轴压扁，使其紧贴面板
        GL11.glScalef(1.0F, 1.0F, 0.005F);
        // 保存 GL 属性：RenderItem 可能改变 blend/alpha test/cull/光照/深度等状态
        // v1.5.13：补充 GL_DEPTH_BUFFER_BIT，防止 Forge IItemRenderer 修改 glDepthFunc/glDepthMask 后
        // 状态泄漏到后续渲染，导致走势图/坐标轴等元素浮在最上层盖过其他材质
        GL11.glPushAttrib(
            GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        // 继续临时禁用深度测试，避免图标被面板表面裁剪
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        RenderHelper.enableGUIStandardItemLighting();
        // 使用 renderItemAndEffectIntoGUI 以触发 Forge IItemRenderer，从而渲染 GT 物品覆盖层
        RenderItem.getInstance()
            .renderItemAndEffectIntoGUI(mc.fontRenderer, mc.getTextureManager(), stack, 0, 0);
        RenderHelper.disableStandardItemLighting();
        GL11.glPopAttrib();
        // 保险：显式恢复深度测试状态，避免某些驱动下 glPopAttrib 对深度状态的恢复不可靠
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
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

        // 取流体对应的 IIcon；流体自身未注册图标时回退到对应方块纹理
        IIcon icon = fluid.getFluid()
            .getIcon(fluid);
        if (icon == null && fluid.getFluid()
            .getBlock() != null) {
            icon = fluid.getFluid()
                .getBlock()
                .getIcon(0, 0);
        }
        if (icon == null) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        mc.getTextureManager()
            .bindTexture(TextureMap.locationBlocksTexture);

        int color = 0xFF000000 | fluid.getFluid()
            .getColor(fluid);
        float r = ((color >> 16) & 255) / 255.0F;
        float g = ((color >> 8) & 255) / 255.0F;
        float b = (color & 255) / 255.0F;

        boolean blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean alphaEnabled = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        boolean cullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);

        GL11.glEnable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glColor4f(r, g, b, 1.0F);

        Tessellator tess = Tessellator.instance;
        tess.startDrawingQuads();
        tess.addVertexWithUV(x, y + iconSize, 0.0D, icon.getMinU(), icon.getMaxV());
        tess.addVertexWithUV(x + iconSize, y + iconSize, 0.0D, icon.getMaxU(), icon.getMaxV());
        tess.addVertexWithUV(x + iconSize, y, 0.0D, icon.getMaxU(), icon.getMinV());
        tess.addVertexWithUV(x, y, 0.0D, icon.getMinU(), icon.getMinV());
        tess.draw();

        if (!blendEnabled) {
            GL11.glDisable(GL11.GL_BLEND);
        }
        if (!alphaEnabled) {
            GL11.glDisable(GL11.GL_ALPHA_TEST);
        }
        if (cullEnabled) {
            GL11.glEnable(GL11.GL_CULL_FACE);
        } else {
            GL11.glDisable(GL11.GL_CULL_FACE);
        }
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
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
                        : panel.getDisplayMode() == 1 ? FormatUtil.formatScientific(panel.getCachedEu())
                            : FormatUtil.formatMetric(panel.getCachedEu(), 2)),
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
        int displayMode = panel.getDisplayMode();
        if (samples.size() < 2) {
            drawAcademicAxes(panel, font, plotX, plotY, plotW, plotH, null, null, displayMode);
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
        drawAcademicAxes(panel, font, plotX, plotY, plotW, plotH, energyRange, eutRange, displayMode);
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
        double[] energyRange, double[] eutRange, int displayMode) {
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
                String label = formatAxisValue(value, displayMode);
                font.drawString(label, x - 8 - font.getStringWidth(label), ty - 4, ENERGY_COLOR);
            }
            if (eutRange != null) {
                double value = eutRange[0] + (eutRange[1] - eutRange[0]) * i / 4.0D;
                font.drawString(formatAxisValue(value, displayMode), x + w + 8, ty - 4, EUT_COLOR);
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
        double[][] path = monotoneCubicPath(values, segments);

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glLineWidth(thickness);
        setColor(color);
        GL11.glBegin(GL11.GL_LINE_STRIP);
        double denom = values.length - 1;
        for (int i = 0; i < path.length; i++) {
            double normalized = (path[i][1] - min) / (max - min);
            // 配合 range() 自动模式预留的 10% 冗余，正常数据下曲线会落在绘图区内
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
     * 用 Fritsch-Carlson 单调三次 Hermite 样条在样品点之间生成密集顶点路径。
     * <p>
     * 该样条保证在相邻样品点之间保持单调性，避免 Catmull-Rom 样条常见的过冲（overshoot）
     * 问题，使走势线更贴合实际数据趋势。
     * X 等间距索引映射：path[i][0] = 索引坐标（浮点），path[i][1] = 值。
     *
     * @param values          样品值数组（已按时间索引化，X 等间距）
     * @param segmentsPerSpan 每相邻两点间的插值段数（≥1；1 = 线性）
     * @return 密集顶点路径，长度 = (values.length - 1) * segmentsPerSpan + 1
     */
    private static double[][] monotoneCubicPath(double[] values, int segmentsPerSpan) {
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

        // 1. 计算每段割线斜率 d[i] = values[i+1] - values[i]
        double[] d = new double[n - 1];
        for (int i = 0; i < n - 1; i++) {
            d[i] = values[i + 1] - values[i];
        }

        // 2. 初始化节点导数 m[i]
        // 内部点取相邻割线平均；端点取相邻割线；相邻割线符号相反或割线为 0 时导数置 0
        double[] m = new double[n];
        m[0] = d[0];
        m[n - 1] = d[n - 2];
        for (int i = 1; i < n - 1; i++) {
            if (d[i - 1] * d[i] <= 0.0D) {
                m[i] = 0.0D;
            } else {
                m[i] = (d[i - 1] + d[i]) / 2.0D;
            }
        }

        // 3. Fritsch-Carlson 单调性修正
        // 若某段割线为 0，则两端导数均为 0；否则根据 α、β 约束防止过冲
        for (int i = 0; i < n - 1; i++) {
            if (d[i] == 0.0D) {
                m[i] = 0.0D;
                m[i + 1] = 0.0D;
                continue;
            }
            double alpha = m[i] / d[i];
            double beta = m[i + 1] / d[i];
            double sumSq = alpha * alpha + beta * beta;
            if (sumSq > 9.0D) {
                double tau = 3.0D / Math.sqrt(sumSq);
                m[i] = tau * alpha * d[i];
                m[i + 1] = tau * beta * d[i];
            }
        }

        // 4. 分段三次 Hermite 插值求值
        int total = (n - 1) * segmentsPerSpan + 1;
        double[][] path = new double[total][2];
        path[0][0] = 0.0D;
        path[0][1] = values[0];

        int idx = 1;
        for (int i = 0; i < n - 1; i++) {
            double y0 = values[i];
            double y1 = values[i + 1];
            double m0 = m[i];
            double m1 = m[i + 1];
            for (int s = 1; s <= segmentsPerSpan; s++) {
                double t = (double) s / segmentsPerSpan;
                double t2 = t * t;
                double t3 = t2 * t;
                // 三次 Hermite 基函数
                double h00 = 2.0D * t3 - 3.0D * t2 + 1.0D;
                double h10 = t3 - 2.0D * t2 + t;
                double h01 = -2.0D * t3 + 3.0D * t2;
                double h11 = t3 - t2;
                double y = h00 * y0 + h10 * m0 + h01 * y1 + h11 * m1;
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

    /** 在指定位置以指定缩放绘制字符串，缩放操作被包裹在 glPushMatrix/glPopMatrix 中 */
    private void drawScaledString(FontRenderer font, String text, int x, int y, int color, float scale) {
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0.0F);
        GL11.glScalef(scale, scale, 1.0F);
        font.drawString(text, 0, 0, color);
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
        // 极小值视为 0，避免无意义的科学计数显示
        if (Math.abs(value) < 0.000001D) {
            return "0";
        }
        // 统一委托给 FormatUtil，使 HUD 渲染与 MTE 共用同一套 E 格式科学计数法，
        // 避免 ×10^ 与 E 两种风格混用导致显示不一致。
        return FormatUtil.formatScientificDouble(value);
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }

    /** 格式化 AE 走势图存量（支持常规/科学计数(0位)/千位模式） */
    private static String formatAEChartAmount(long amount, int displayMode) {
        BigInteger value = BigInteger.valueOf(amount);
        switch (displayMode) {
            case 1:
                return FormatUtil.formatScientific(value, 0);
            case 2:
                return FormatUtil.formatMetric(value, 2);
            case 0:
            default:
                return FormatUtil.formatNormal(value);
        }
    }

    /** 格式化 AE 走势图变化速率（支持常规/科学计数(0位)/千位模式，无小数） */
    private static String formatAEChartRate(double rate, int displayMode) {
        return formatAEChartAxis(rate, displayMode);
    }

    /** 格式化 AE 走势图坐标轴标签（支持常规/科学计数(0位)/千位模式，无小数） */
    private static String formatAEChartAxis(double value, int displayMode) {
        return formatAxisValue(value, displayMode);
    }

    /** 格式化坐标轴数值（支持常规/科学计数(0位)/千位模式，无小数） */
    private static String formatAxisValue(double value, int displayMode) {
        switch (displayMode) {
            case 1:
                return FormatUtil.formatScientificDouble(value, 0);
            case 2:
                return FormatUtil.formatMetricDouble(value);
            case 0:
            default:
                return FormatUtil.formatNormalDouble(value, 0);
        }
    }

    /** 格式化 AE 监控存量（根据显示模式切换常规/科学计数/千位模式） */
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

    /** 格式化 AE 监控变化速率（根据显示模式切换常规/科学计数/千位模式） */
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

    /** 根据变化速率返回颜色：正深绿、负红、零灰 */
    private static int rateColor(double rate) {
        if (rate > 0.0D) {
            return 0x2E7D32;
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
