package com.miaokatze.gtswn.client.gui;

import java.util.List;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketRequestQuantumTerminalData;

/**
 * ME 网络量子终端客户端 GUI（v1.6.2 仿 AE2 Network Status 界面重做）。
 * <p>
 * 纯 {@link GuiScreen}（无槽位界面）：顶部网络信息 + 现存/最大能源、中部 5×4 设备图标网格、
 * 底部耗能/产能/物品/流体/源质统计。
 * <p>
 * 数据流：
 * <ol>
 * <li>{@link #updateScreen()} 每 {@value #POLL_INTERVAL_TICKS} tick 经包 5 向服务端轮询；</li>
 * <li>服务端回包 6 → ClientProxy 切主线程 → {@link #receiveData(QuantumNetworkData)} 写静态缓存；</li>
 * <li>绘制时读取 {@link #latestData}；GUI 打开/关闭即清空缓存，防止串上一次的旧数据。</li>
 * </ol>
 */
public class GuiQuantumTerminal extends GuiScreen {

    /** AE2 网络状态界面纹理 */
    private static final ResourceLocation TEXTURE = new ResourceLocation(
        "appliedenergistics2",
        "textures/guis/networkstatus.png");

    /** 最新网络快照（包 6 经 ClientProxy 切主线程写入；GUI 打开/关闭时清空） */
    private static QuantumNetworkData latestData = null;

    /** 轮询间隔（tick）：每 10 tick 发一次请求包 5 */
    private static final int POLL_INTERVAL_TICKS = 10;

    /** GUI 尺寸（与 AE2 NetworkStatus 一致） */
    private final int xSize = 195;
    private final int ySize = 183;

    /** 设备图标网格：5 列 × 4 行 */
    private static final int GRID_COLUMNS = 5;
    private static final int GRID_ROWS = 4;
    private static final int CELL_WIDTH = 28;
    private static final int CELL_HEIGHT = 20;
    private static final int ICON_SIZE = 16;

    /** GUI 左上角屏幕坐标 */
    private int guiLeft;
    private int guiTop;

    /** 轮询计时器（初值 0 → 打开后首个 updateScreen 立即发首包） */
    private int pollTimer = 0;

    /** 设备网格滚动偏移（按行） */
    private int scrollRow = 0;

    public GuiQuantumTerminal() {
        // 打开 GUI 时清空缓存，防止串上一次会话的旧数据
        latestData = null;
    }

    /**
     * 包 6 回包写入入口（ClientProxy 已切至客户端主线程）。
     */
    public static void receiveData(QuantumNetworkData data) {
        latestData = data;
    }

    @Override
    public void initGui() {
        super.initGui();
        this.guiLeft = (this.width - this.xSize) / 2;
        this.guiTop = (this.height - this.ySize) / 2;
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        // 每 10 tick 轮询一次服务端数据；pollTimer 初值 0 → 打开后立即发首包
        if (this.pollTimer++ % POLL_INTERVAL_TICKS == 0) {
            GTSWNPacketHandler.NETWORK.sendToServer(new PacketRequestQuantumTerminalData());
        }
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        // Esc/关闭即弃缓存
        latestData = null;
    }

    @Override
    public void handleMouseInput() {
        // 滚轮按行滚动设备网格
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && latestData != null) {
            int maxRow = Math.max(0, (latestData.entries.size() + GRID_COLUMNS - 1) / GRID_COLUMNS - GRID_ROWS);
            this.scrollRow = MathHelper.clamp_int(this.scrollRow - Integer.signum(wheel), 0, maxRow);
        }
        super.handleMouseInput();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // 绘制 AE2 网络状态背景
        this.mc.getTextureManager()
            .bindTexture(TEXTURE);
        this.drawTexturedModalRect(this.guiLeft, this.guiTop, 0, 0, this.xSize, this.ySize);

        // 标题
        this.fontRendererObj
            .drawString(tr("gtswn.gui.quantum.network_details"), this.guiLeft + 8, this.guiTop + 6, 0x404040);

        QuantumNetworkData data = latestData;
        if (data == null) {
            // 首个回包未到达：显示等待占位
            this.fontRendererObj
                .drawString(EnumChatFormatting.GRAY + "...", this.guiLeft + 13, this.guiTop + 40, 0x404040);
        } else if (!data.online) {
            drawOffline(data);
        } else {
            drawOnline(data);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    /** 绘制在线数据：能源 / 设备网格 / 底部统计 */
    private void drawOnline(QuantumNetworkData data) {
        // 现存 / 最大能源
        String storedStr = data.powerInfinite ? "∞" : formatAE(data.storedPower);
        String maxStr = data.powerInfinite ? "∞" : formatAE(data.maxStoredPower);
        this.fontRendererObj.drawString(
            tr("gtswn.gui.quantum.stored_power") + ": " + storedStr,
            this.guiLeft + 13,
            this.guiTop + 16,
            0x404040);
        this.fontRendererObj.drawString(
            tr("gtswn.gui.quantum.max_power") + ": " + maxStr,
            this.guiLeft + 13,
            this.guiTop + 26,
            0x404040);

        // 设备图标网格
        drawDeviceGrid(data);

        // 底部统计行：耗能、产能、物品、流体、源质
        int y = this.guiTop + 123;
        this.fontRendererObj.drawString(
            tr("gtswn.gui.quantum.power_usage") + ": " + formatAE(data.avgPowerUsage) + " AE/t",
            this.guiLeft + 13,
            y,
            0x404040);
        y += 10;
        this.fontRendererObj.drawString(
            tr("gtswn.gui.quantum.power_input") + ": " + formatAE(data.avgPowerInjection) + " AE/t",
            this.guiLeft + 13,
            y,
            0x404040);
        y += 10;
        this.fontRendererObj.drawString(
            tr("gtswn.gui.quantum.items") + ": " + formatByteLine(data.itemBytesUsed, data.itemBytesTotal),
            this.guiLeft + 13,
            y,
            0x404040);
        y += 10;
        this.fontRendererObj.drawString(
            tr("gtswn.gui.quantum.fluids") + ": " + formatByteLine(data.fluidBytesUsed, data.fluidBytesTotal),
            this.guiLeft + 13,
            y,
            0x404040);
        y += 10;
        this.fontRendererObj.drawString(
            tr("gtswn.gui.quantum.essentia") + ": " + formatByteLine(data.essentiaBytesUsed, data.essentiaBytesTotal),
            this.guiLeft + 13,
            y,
            0x404040);
    }

    /** 绘制离线状态：标题 + 锚点信息 + 离线提示 */
    private void drawOffline(QuantumNetworkData data) {
        // 锚点坐标行
        this.fontRendererObj.drawString(
            StatCollector.translateToLocalFormatted(
                "gtswn.tooltip.quantum_terminal.bound",
                "DIM " + data.anchorDim,
                data.anchorX,
                data.anchorY,
                data.anchorZ),
            this.guiLeft + 13,
            this.guiTop + 40,
            0x404040);
        // 离线提示
        this.fontRendererObj.drawString(
            EnumChatFormatting.RED + tr("gtswn.gui.quantum.offline"),
            this.guiLeft + 13,
            this.guiTop + 55,
            0x404040);
    }

    /** 绘制设备图标网格：5 列 × 4 行，按 ItemStack 聚合计数 */
    private void drawDeviceGrid(QuantumNetworkData data) {
        List<QuantumNetworkData.DeviceEntry> entries = data.entries;
        int maxRow = Math.max(0, (entries.size() + GRID_COLUMNS - 1) / GRID_COLUMNS - GRID_ROWS);
        this.scrollRow = MathHelper.clamp_int(this.scrollRow, 0, maxRow);

        int startIndex = this.scrollRow * GRID_COLUMNS;
        int viewEnd = Math.min(startIndex + GRID_COLUMNS * GRID_ROWS, entries.size());

        for (int i = startIndex; i < viewEnd; i++) {
            int gridIndex = i - startIndex;
            int col = gridIndex % GRID_COLUMNS;
            int row = gridIndex / GRID_COLUMNS;

            int x = this.guiLeft + 14 + col * CELL_WIDTH;
            int y = this.guiTop + 41 + row * CELL_HEIGHT;

            QuantumNetworkData.DeviceEntry entry = entries.get(i);
            if (entry.icon != null) {
                drawItemIcon(entry.icon, x, y);

                // 数量文本以 0.5 倍缩放画在图标右下
                String countStr = formatCount(entry.count);
                GL11.glPushMatrix();
                GL11.glScalef(0.5F, 0.5F, 0.5F);
                int w = this.fontRendererObj.getStringWidth(countStr);
                this.fontRendererObj
                    .drawString(countStr, (x + ICON_SIZE - w + 1) * 2, (y + ICON_SIZE - 6) * 2, 0xFFFFFF);
                GL11.glPopMatrix();
            }
        }
    }

    /** 绘制 16×16 物品图标（启用标准 GUI 物品光照） */
    private void drawItemIcon(ItemStack stack, int x, int y) {
        RenderHelper.enableGUIStandardItemLighting();
        RenderItem.getInstance()
            .renderItemAndEffectIntoGUI(this.fontRendererObj, this.mc.getTextureManager(), stack, x, y);
        RenderHelper.disableStandardItemLighting();
    }

    /** 格式化字节行：used / total，total>0 时追加百分比 */
    private String formatByteLine(double used, double total) {
        String line = formatBytes(used) + " / " + formatBytes(total);
        if (total > 0.0D) {
            line += " (" + String.format("%.1f", used * 100.0D / total) + "%)";
        }
        return line;
    }

    /** 格式化 AE 能量值：k / M / G / T / P */
    private static String formatAE(double value) {
        if (value == 0.0D) {
            return "0";
        }
        String[] suffixes = { "", "k", "M", "G", "T", "P" };
        int idx = 0;
        double v = value;
        while (Math.abs(v) >= 1000.0D && idx < suffixes.length - 1) {
            v /= 1000.0D;
            idx++;
        }
        return String.format("%.2f", v) + suffixes[idx];
    }

    /** 格式化字节：B / kB / MB / GB / TB / PB */
    private static String formatBytes(double value) {
        if (value == 0.0D) {
            return "0 B";
        }
        String[] suffixes = { "B", "kB", "MB", "GB", "TB", "PB" };
        int idx = 0;
        double v = value;
        while (Math.abs(v) >= 1024.0D && idx < suffixes.length - 1) {
            v /= 1024.0D;
            idx++;
        }
        return String.format("%.2f", v) + " " + suffixes[idx];
    }

    /** 格式化设备数量：≥10k 显示为 Xk */
    private static String formatCount(int count) {
        if (count >= 10000) {
            return (count / 1000) + "k";
        }
        return String.valueOf(count);
    }

    /** 本地化工具 */
    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }
}
