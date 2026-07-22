package com.miaokatze.gtswn.client.gui;

import java.util.List;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MathHelper;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Mouse;

import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketRequestQuantumTerminalData;

/**
 * ME 网络量子终端客户端 GUI（T6，规划 plan_20260722152445.md §3/§6/§7）。
 * <p>
 * 纯 {@link GuiScreen}（无槽位界面，风格仿 {@link GuiNetworkInfoPanel} 但简化）：
 * 显示锚点坐标、频道用量、能量消耗/注入、网络储能与可滚动设备列表。
 * <p>
 * 数据流（规划 §6 客户端刷新模式）：
 * <ol>
 * <li>{@link #updateScreen()} 每 {@value #POLL_INTERVAL_TICKS} tick 经包 5 向服务端轮询；</li>
 * <li>服务端回包 6 → ClientProxy 切主线程 → {@link #receiveData(QuantumNetworkData)} 写静态缓存；</li>
 * <li>绘制时读取 {@link #latestData}；GUI 打开/关闭即清空缓存，防止串上一次的旧数据。</li>
 * </ol>
 */
public class GuiQuantumTerminal extends GuiScreen {

    /** 最新网络快照（包 6 经 ClientProxy 切主线程写入；GUI 打开/关闭时清空） */
    private static QuantumNetworkData latestData = null;

    /** 轮询间隔（tick）：每 10 tick 发一次请求包 5 */
    private static final int POLL_INTERVAL_TICKS = 10;

    /** 设备列表可见行数 */
    private static final int VISIBLE_ROWS = 8;

    /** 设备列表行高（px，与 16x16 图标同高） */
    private static final int ROW_HEIGHT = 16;

    /** 面板尺寸（xSize≈256 / ySize≈200 自定，规划任务书） */
    private final int xSize = 256;
    private final int ySize = 224;

    /** 面板左上角屏幕坐标 */
    private int left;
    private int top;

    /** 轮询计时器（初值 0 → 打开后首个 updateScreen 立即发首包） */
    private int pollTimer = 0;

    /** 设备列表滚动偏移（滚轮调整，绘制时按条目数 clamp） */
    private int scrollOffset = 0;

    public GuiQuantumTerminal() {
        // 打开 GUI 时清空缓存，防止串上一次会话的旧数据（规划 §6：GUI 关闭即弃）
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
        this.left = (this.width - this.xSize) / 2;
        this.top = (this.height - this.ySize) / 2;
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
        // Esc/关闭即弃缓存（规划 §6）
        latestData = null;
    }

    @Override
    public void handleMouseInput() {
        // 滚轮调整设备列表滚动偏移（无其他可滚控件，直接消费）
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && latestData != null) {
            int maxOffset = Math.max(0, latestData.entries.size() - VISIBLE_ROWS);
            this.scrollOffset = MathHelper.clamp_int(this.scrollOffset - Integer.signum(wheel), 0, maxOffset);
        }
        super.handleMouseInput();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // 面板背景与标题栏（浅色风格仿信息屏，不画 drawDefaultBackground 的 dirt 底）
        drawPanelBackground();
        drawTitleBar();
        QuantumNetworkData data = latestData;
        if (data == null) {
            // 首个回包未到达：显示等待占位（数据区留空，下轮轮询到达后自动填充）
            fontRendererObj.drawString(EnumChatFormatting.GRAY + "...", left + 12, top + 40, 0x6B7680);
        } else {
            // 锚点坐标行：复用 tooltip.bound 键（§7 无独立 GUI 锚点键）；
            // name 参数客户端无法解析锚点维度的显示名，以「DIM + id」代替
            fontRendererObj.drawString(
                StatCollector.translateToLocalFormatted(
                    "gtswn.tooltip.quantum_terminal.bound",
                    "DIM " + data.anchorDim,
                    data.anchorX,
                    data.anchorY,
                    data.anchorZ),
                left + 12,
                top + 24,
                0x2F3640);
            if (!data.online) {
                // 离线（维度不存在/锚点区块未加载/锚点已非控制器/网格未就绪）
                fontRendererObj.drawString(tr("gtswn.gui.quantum.offline"), left + 12, top + 40, 0xF44336);
            } else {
                drawOnlineData(data);
            }
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    /** 绘制在线数据：频道 / 能量消耗 / 储能 / 设备列表 */
    private void drawOnlineData(QuantumNetworkData data) {
        fontRendererObj.drawString(
            StatCollector
                .translateToLocalFormatted("gtswn.gui.quantum.channels", data.usedChannels, data.totalChannels),
            left + 12,
            top + 36,
            0x2F3640);
        fontRendererObj.drawString(
            StatCollector
                .translateToLocalFormatted("gtswn.gui.quantum.power.usage", data.avgPowerUsage, data.avgPowerInjection),
            left + 12,
            top + 48,
            0x2F3640);
        fontRendererObj.drawString(
            StatCollector
                .translateToLocalFormatted("gtswn.gui.quantum.power.stored", data.storedPower, data.maxStoredPower),
            left + 12,
            top + 60,
            0x2F3640);
        // 分隔线（与信息屏同一浅灰）
        drawRect(left + 8, top + 70, left + xSize - 8, top + 71, 0xFFB8C0C8);
        // 设备列表标题（含设备总数）
        fontRendererObj.drawString(
            StatCollector.translateToLocalFormatted("gtswn.gui.quantum.devices", data.totalMachines),
            left + 12,
            top + 74,
            0x2F3640);
        drawDeviceList(data);
    }

    /** 绘制设备列表：每行 16x16 图标 + 本地化名 + 右对齐数量；超出可见行画简易滚动条 */
    private void drawDeviceList(QuantumNetworkData data) {
        List<QuantumNetworkData.DeviceEntry> entries = data.entries;
        int listX = left + 12;
        int listY = top + 86;
        // 右侧预留 6px 滚动条位
        int listRight = left + xSize - 18;
        // 新数据条目数变化时 clamp 偏移防越界
        int maxOffset = Math.max(0, entries.size() - VISIBLE_ROWS);
        this.scrollOffset = MathHelper.clamp_int(this.scrollOffset, 0, maxOffset);
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            int index = this.scrollOffset + row;
            if (index >= entries.size()) {
                break;
            }
            QuantumNetworkData.DeviceEntry entry = entries.get(index);
            int rowY = listY + row * ROW_HEIGHT;
            // 设备图标
            if (entry.icon != null) {
                drawItemIcon(entry.icon, listX, rowY);
            }
            // 设备名称（超长按可用宽度截断）
            String name = entry.icon != null ? entry.icon.getDisplayName() : "?";
            String countText = "×" + entry.count;
            int countWidth = fontRendererObj.getStringWidth(countText);
            int nameMaxWidth = listRight - (listX + 20) - countWidth - 8;
            fontRendererObj.drawString(
                fontRendererObj.trimStringToWidth(name, Math.max(nameMaxWidth, 20)),
                listX + 20,
                rowY + 4,
                0x2F3640);
            // 数量（右对齐）
            fontRendererObj.drawString(countText, listRight - countWidth, rowY + 4, 0x4C5660);
        }
        // 简易滚动条：轨道 + 按偏移比例定位的滑块（条目数超过可见行才显示）
        if (entries.size() > VISIBLE_ROWS) {
            int trackX = left + xSize - 12;
            int trackHeight = VISIBLE_ROWS * ROW_HEIGHT;
            drawRect(trackX, listY, trackX + 4, listY + trackHeight, 0xFFB8C0C8);
            int thumbHeight = Math.max(10, trackHeight * VISIBLE_ROWS / entries.size());
            // maxOffset 在此分支恒 > 0，无除零风险
            int thumbY = listY + (trackHeight - thumbHeight) * this.scrollOffset / maxOffset;
            drawRect(trackX, thumbY, trackX + 4, thumbY + thumbHeight, 0xFF6A7680);
        }
    }

    /** 面板背景（浅色填充 + 深色 1px 边框，仿信息屏 drawPanelBackground） */
    private void drawPanelBackground() {
        drawRect(left, top, left + xSize, top + ySize, 0xFFE8EAEC);
        drawRect(left, top, left + xSize, top + 1, 0xFF607080);
        drawRect(left, top + ySize - 1, left + xSize, top + ySize, 0xFF607080);
        drawRect(left, top, left + 1, top + ySize, 0xFF607080);
        drawRect(left + xSize - 1, top, left + xSize, top + ySize, 0xFF607080);
    }

    /** 标题栏（深色底 + 1px 高光/阴影边框 + 白色粗体标题，仿信息屏 drawTitleBar） */
    private void drawTitleBar() {
        String title = EnumChatFormatting.BOLD + tr("gtswn.gui.quantum.title");
        int titleWidth = fontRendererObj.getStringWidth(title);
        int boxPadding = 6;
        int boxX = left + 8;
        int boxY = top + 6;
        int boxW = titleWidth + boxPadding * 2;
        int boxH = 14;
        drawRect(boxX, boxY, boxX + boxW, boxY + boxH, 0xFF607080);
        drawRect(boxX, boxY, boxX + boxW, boxY + 1, 0xFFA0A8B0);
        drawRect(boxX, boxY + boxH - 1, boxX + boxW, boxY + boxH, 0xFF405060);
        drawRect(boxX, boxY, boxX + 1, boxY + boxH, 0xFFA0A8B0);
        drawRect(boxX + boxW - 1, boxY, boxX + boxW, boxY + boxH, 0xFF405060);
        fontRendererObj.drawString(title, boxX + boxPadding, boxY + 3, 0xFFFFFF);
    }

    /** 本地化工具（与信息屏 tr 同款） */
    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }

    /** 绘制 16x16 物品图标（启用标准 GUI 物品光照，仿信息屏 drawItemIcon） */
    private void drawItemIcon(ItemStack stack, int x, int y) {
        RenderHelper.enableGUIStandardItemLighting();
        RenderItem.getInstance()
            .renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), stack, x, y);
        RenderHelper.disableStandardItemLighting();
    }
}
