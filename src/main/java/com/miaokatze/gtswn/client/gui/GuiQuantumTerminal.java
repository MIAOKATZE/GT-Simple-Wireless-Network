package com.miaokatze.gtswn.client.gui;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;
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
 * <li>绘制时读取 {@link #latestData}；v1.6.5 起缓存<b>不随 GUI 开关清空</b>——
 * 仅当缓存锚点与手持终端绑定目标不一致时才清空（此前每开必清，重开必回「...」占位，
 * 遇 GTNH 存档停顿首包延迟数秒时用户反复快开快关将永远只见占位符）。</li>
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

    /** 设备图标网格：5 列 × 4 行，坐标对齐 AE2 GuiNetworkStatus（图标原点 24/42，列距 30，行距 18） */
    private static final int GRID_COLUMNS = 5;
    private static final int GRID_ROWS = 4;
    private static final int GRID_ORIGIN_X = 24;
    private static final int GRID_ORIGIN_Y = 42;
    private static final int CELL_WIDTH = 30;
    private static final int CELL_HEIGHT = 18;
    private static final int ICON_SIZE = 16;

    /** 滚动条轨道：与 AE2 networkstatus.png 烘焙轨道对齐（坐标取自 AE2 GuiNetworkStatus: left=175, top=39, height=78） */
    private static final int SCROLL_X = 175;
    private static final int SCROLL_Y = 39;
    private static final int SCROLL_W = 12;
    private static final int SCROLL_H = 78;
    /** 滑块高度（AE2 风格固定 15px，行程 = 轨道高 - 滑块高） */
    private static final int THUMB_H = 15;

    /** GUI 左上角屏幕坐标 */
    private int guiLeft;
    private int guiTop;

    /** 轮询计时器（初值 0 → 打开后首个 updateScreen 立即发首包） */
    private int pollTimer = 0;

    /** 设备网格滚动偏移（按行） */
    private int scrollRow = 0;

    /** 滚动条拖拽中（mouseClicked 命中轨道置位，松开左键复位） */
    private boolean draggingScroll = false;

    /** v1.6.5 一次性渲染日志：本次 GUI 会话首次绘制在线数据时记录，用于确定性验证渲染路径 */
    private boolean loggedFirstDataRender = false;

    /** v1.6.6 绘制帧计数器：约每 60 帧输出一次绘制状态，避免刷屏 */
    private int drawFrameCounter = 0;

    public GuiQuantumTerminal() {
        // v1.6.5：不再无条件清空缓存——仅当缓存锚点与当前手持终端绑定目标不一致时清空。
        // 保留缓存时重开 GUI 立即显示上次快照，≤10 tick 内由轮询自动刷新。
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        ItemStack held = player != null ? player.getHeldItem() : null;
        int[] anchor = ItemNetworkQuantumTerminal.getAnchor(held);
        if (!matchesCachedAnchor(anchor)) {
            latestData = null;
            GTSimpleWirelessNetwork.LOG.info("[量子终端][GUI 构造] 锚点不一致/无缓存，清空 latestData");
        } else {
            GTSimpleWirelessNetwork.LOG.info("[量子终端][GUI 构造] 锚点匹配，复用缓存条目=" + latestData.entries.size());
        }
    }

    /** 缓存快照的锚点是否与手持终端绑定目标一致（无缓存/未绑定视为不一致） */
    private static boolean matchesCachedAnchor(int[] anchor) {
        if (anchor == null || latestData == null) {
            return false;
        }
        return latestData.anchorDim == anchor[0] && latestData.anchorX == anchor[1]
            && latestData.anchorY == anchor[2]
            && latestData.anchorZ == anchor[3];
    }

    /**
     * 包 6 回包写入入口（ClientProxy 已切至客户端主线程）。
     */
    public static void receiveData(QuantumNetworkData data) {
        latestData = data;
        GTSimpleWirelessNetwork.LOG
            .info("[量子终端][GUI receiveData] 写入缓存 online=" + data.online + " 条目=" + data.entries.size());
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
            GTSimpleWirelessNetwork.LOG.info("[量子终端][GUI updateScreen] 发送请求包 #" + this.pollTimer);
            GTSWNPacketHandler.NETWORK.sendToServer(new PacketRequestQuantumTerminalData());
        }
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        // v1.6.5：关闭不再清缓存（保留最近快照供下次打开即显；改绑其他控制器时由构造函数锚点匹配清空）
    }

    @Override
    public void handleMouseInput() {
        // 滚轮按行滚动设备网格
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && latestData != null) {
            this.scrollRow = MathHelper.clamp_int(this.scrollRow - Integer.signum(wheel), 0, maxScrollRow(latestData));
        }
        super.handleMouseInput();
    }

    /** 设备网格的最大滚动行（条目 ≤ 一页时为 0，滚动条/滚轮均不生效） */
    private static int maxScrollRow(QuantumNetworkData data) {
        return Math.max(0, (data.entries.size() + GRID_COLUMNS - 1) / GRID_COLUMNS - GRID_ROWS);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        // 左键命中滚动条轨道 → 进入拖拽并把滑块跳到点击位置
        if (mouseButton == 0 && latestData != null && maxScrollRow(latestData) > 0) {
            int trackX = this.guiLeft + SCROLL_X;
            int trackY = this.guiTop + SCROLL_Y;
            if (mouseX >= trackX && mouseX < trackX + SCROLL_W && mouseY >= trackY && mouseY < trackY + SCROLL_H) {
                this.draggingScroll = true;
                updateScrollFromMouse(mouseY);
            }
        }
    }

    /** 拖拽中按鼠标 Y 反推滚动行（滑块中心对齐鼠标） */
    private void updateScrollFromMouse(int mouseY) {
        if (latestData == null) {
            return;
        }
        int maxRow = maxScrollRow(latestData);
        float frac = (float) (mouseY - this.guiTop - SCROLL_Y - THUMB_H / 2) / (float) (SCROLL_H - THUMB_H);
        this.scrollRow = MathHelper.clamp_int(Math.round(frac * maxRow), 0, maxRow);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // 滚动条拖拽跟踪（1.7.10 无鼠标移动回调，标准做法：按住左键期间每帧按鼠标 Y 更新，松开退出）
        if (this.draggingScroll) {
            if (!Mouse.isButtonDown(0)) {
                this.draggingScroll = false;
            } else {
                updateScrollFromMouse(mouseY);
            }
        }

        // 约每 60 帧输出一次绘制状态，用于定位卡死阶段（避免每帧刷屏）
        if (this.drawFrameCounter++ % 60 == 0) {
            QuantumNetworkData d = latestData;
            String state = d == null ? "null" : (d.online ? "online(" + d.entries.size() + ")" : "offline");
            GTSimpleWirelessNetwork.LOG.info("[量子终端][GUI drawScreen] 绘制中 data=" + state);
        }

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
            drawScrollbar(data);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    /** 绘制滚动条滑块（经典 MC 风格：灰主体 + 左上亮边 + 右下暗边）；仅条目超一页时显示 */
    private void drawScrollbar(QuantumNetworkData data) {
        int maxRow = maxScrollRow(data);
        if (maxRow <= 0) {
            return;
        }
        int trackX = this.guiLeft + SCROLL_X;
        int thumbY = this.guiTop + SCROLL_Y + (SCROLL_H - THUMB_H) * this.scrollRow / maxRow;
        drawRect(trackX, thumbY, trackX + SCROLL_W, thumbY + THUMB_H, 0xFF8B8B8B);
        drawRect(trackX, thumbY, trackX + SCROLL_W, thumbY + 1, 0xFFC6C6C6);
        drawRect(trackX, thumbY, trackX + 1, thumbY + THUMB_H, 0xFFC6C6C6);
        drawRect(trackX, thumbY + THUMB_H - 1, trackX + SCROLL_W, thumbY + THUMB_H, 0xFF555555);
        drawRect(trackX + SCROLL_W - 1, thumbY, trackX + SCROLL_W, thumbY + THUMB_H, 0xFF555555);
    }

    /** 绘制在线数据：能源 / 设备网格 / 底部统计 */
    private void drawOnline(QuantumNetworkData data) {
        // v1.6.5 一次性渲染日志：确定性验证「数据到达 → 实际绘制」闭环（排障后可移除）
        if (!this.loggedFirstDataRender) {
            this.loggedFirstDataRender = true;
            GTSimpleWirelessNetwork.LOG.info(
                "[量子终端] GUI 首次绘制在线数据：频道=" + data.usedChannels
                    + "/"
                    + data.totalChannels
                    + "，设备条目="
                    + data.entries.size());
        }
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

    /** 绘制设备图标网格：5 列 × 4 行，按 ItemStack 聚合计数（坐标对齐 AE2 纹理烘焙槽位） */
    private void drawDeviceGrid(QuantumNetworkData data) {
        List<QuantumNetworkData.DeviceEntry> entries = data.entries;
        int maxRow = maxScrollRow(data);
        this.scrollRow = MathHelper.clamp_int(this.scrollRow, 0, maxRow);

        int startIndex = this.scrollRow * GRID_COLUMNS;
        int viewEnd = Math.min(startIndex + GRID_COLUMNS * GRID_ROWS, entries.size());

        for (int i = startIndex; i < viewEnd; i++) {
            int gridIndex = i - startIndex;
            int col = gridIndex % GRID_COLUMNS;
            int row = gridIndex / GRID_COLUMNS;

            int x = this.guiLeft + GRID_ORIGIN_X + col * CELL_WIDTH;
            int y = this.guiTop + GRID_ORIGIN_Y + row * CELL_HEIGHT;

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

    /** 绘制 16×16 物品图标（启用标准 GUI 物品光照；v1.6.6 单个图标渲染异常时跳过，避免整 GUI 崩溃） */
    private void drawItemIcon(ItemStack stack, int x, int y) {
        try {
            RenderHelper.enableGUIStandardItemLighting();
            RenderItem.getInstance()
                .renderItemAndEffectIntoGUI(this.fontRendererObj, this.mc.getTextureManager(), stack, x, y);
            RenderHelper.disableStandardItemLighting();
        } catch (Throwable t) {
            GTSimpleWirelessNetwork.LOG.warn("[量子终端][GUI] 渲染设备图标异常，跳过：" + stack, t);
            RenderHelper.disableStandardItemLighting();
        }
    }

    /** 格式化字节行：used / total，total>0 时追加百分比 */
    private String formatByteLine(double used, double total) {
        String line = formatBytes(used) + " / " + formatBytes(total);
        if (total > 0.0D) {
            line += " (" + String.format("%.1f", used * 100.0D / total) + "%)";
        }
        return line;
    }

    /** 格式化 AE 能量值：k / M / G / T / P（v1.6.6 防御 Infinity/NaN） */
    private static String formatAE(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (Double.isInfinite(value)) {
            return "∞";
        }
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

    /** 格式化字节：B / kB / MB / GB / TB / PB（v1.6.6 防御 Infinity/NaN） */
    private static String formatBytes(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (Double.isInfinite(value)) {
            return "∞";
        }
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
