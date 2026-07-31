package com.miaokatze.gtswn.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketRequestQuantumTerminalData;

/**
 * ME 网络量子终端客户端 GUI（v1.6.9 紧凑布局精简版）。
 * <p>
 * 纯 {@link GuiScreen}（无槽位界面）：紧凑自绘面板，仅显示四项核心信息——
 * 控制器坐标 / 维度 / 量子节点数 / 频道（used/total/百分比）。
 * <p>
 * 数据流（不变）：
 * <ol>
 * <li>{@link #updateScreen()} 每 {@value #POLL_INTERVAL_TICKS} tick 经包 5 向服务端轮询；</li>
 * <li>服务端回包 6 → ClientProxy 切主线程 → {@link #receiveData(QuantumNetworkData)} 写静态缓存；</li>
 * <li>绘制时读取 {@link #latestData}；v1.6.5 起缓存不随 GUI 开关清空——
 * 仅当缓存锚点与手持终端绑定目标不一致时才清空。</li>
 * </ol>
 * <p>
 * v1.6.9 移除：AE2 networkstatus.png 背景、5×4 设备图标网格、滚动条、设备图标 tooltip、
 * 能量/存储/耗能/产能统计行、formatAE/formatBytes/formatCount 工具方法。
 */
public class GuiQuantumTerminal extends GuiScreen {

    /** 最新网络快照（包 6 经 ClientProxy 切主线程写入；GUI 打开/关闭时清空） */
    private static QuantumNetworkData latestData = null;

    /** 轮询间隔（tick）：每 10 tick 发一次请求包 5 */
    private static final int POLL_INTERVAL_TICKS = 10;

    /** GUI 尺寸（v1.6.9：紧凑布局，从 195×183 缩为 120×92） */
    private final int xSize = 120;
    private final int ySize = 92;

    /** GUI 左上角屏幕坐标 */
    private int guiLeft;
    private int guiTop;

    /** 轮询计时器（初值 0 → 打开后首个 updateScreen 立即发首包） */
    private int pollTimer = 0;

    public GuiQuantumTerminal() {
        // v1.6.5：不再无条件清空缓存——仅当缓存锚点与当前手持终端绑定目标不一致时清空。
        // 保留缓存时重开 GUI 立即显示上次快照，≤10 tick 内由轮询自动刷新。
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        ItemStack held = player != null ? player.getHeldItem() : null;
        int[] anchor = ItemNetworkQuantumTerminal.getAnchor(held);
        if (!matchesCachedAnchor(anchor)) {
            latestData = null;
        }
    }

    /**
     * v1.6.7 核心修复：覆写默认行为，打开 GUI 时不暂停游戏 tick。
     * <p>
     * 1.7.10 中 {@link GuiScreen#doesGuiPauseGame()} 默认返回 true，会导致单人 integrated server
     * 模式下打开 GUI 暂停整个游戏 tick——服务端不 tick 即无法处理包 5 请求，客户端永远收不到
     * 包 6 回包，GUI 卡在「...」占位符（v1.6.5 之前的 GUI 空白现象根因）。
     * <p>
     * dedicated server 中 GUI 是客户端概念本来就不影响服务端 tick，故此修改对服务端无副作用。
     */
    @Override
    public boolean doesGuiPauseGame() {
        return false;
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
        // v1.6.5：关闭不再清缓存（保留最近快照供下次打开即显；改绑其他控制器时由构造函数锚点匹配清空）
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // v1.6.9：自绘紧凑面板背景（不再绑定 AE2 networkstatus.png）
        drawPanelBackground();

        // 标题
        this.fontRendererObj
            .drawString(tr("gtswn.gui.quantum.network_details"), this.guiLeft + 8, this.guiTop + 6, 0x404040);

        QuantumNetworkData data = latestData;
        if (data == null) {
            // 首个回包未到达：显示等待占位
            this.fontRendererObj
                .drawString(EnumChatFormatting.GRAY + "...", this.guiLeft + 13, this.guiTop + 20, 0x404040);
        } else if (!data.online) {
            drawOffline(data);
        } else {
            drawOnline(data);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    /**
     * 自绘紧凑面板背景（仿 GuiNetworkInfoPanel.drawPanelBackground，v1.6.9 起不再绑定 AE2 纹理）。
     * <p>
     * 配色与项目内 GuiNetworkInfoPanel 一致：浅灰背景 + 深蓝灰边框 + 浅蓝灰标题分隔线。
     */
    private void drawPanelBackground() {
        drawRect(this.guiLeft, this.guiTop, this.guiLeft + this.xSize, this.guiTop + this.ySize, 0xFFE8EAEC);
        drawRect(this.guiLeft, this.guiTop, this.guiLeft + this.xSize, this.guiTop + 1, 0xFF607080);
        drawRect(
            this.guiLeft,
            this.guiTop + this.ySize - 1,
            this.guiLeft + this.xSize,
            this.guiTop + this.ySize,
            0xFF607080);
        drawRect(this.guiLeft, this.guiTop, this.guiLeft + 1, this.guiTop + this.ySize, 0xFF607080);
        drawRect(
            this.guiLeft + this.xSize - 1,
            this.guiTop,
            this.guiLeft + this.xSize,
            this.guiTop + this.ySize,
            0xFF607080);
        // 标题分隔线
        drawRect(this.guiLeft + 8, this.guiTop + 14, this.guiLeft + this.xSize - 8, this.guiTop + 15, 0xFFB8C0C8);
    }

    /** 绘制在线数据：控制器坐标 / 维度 / 量子节点数 / 频道（used/total/百分比，过载红色） */
    private void drawOnline(QuantumNetworkData data) {
        // 控制器坐标行
        this.fontRendererObj.drawString(
            tr("gtswn.gui.quantum.controller_pos") + ": " + data.anchorX + ", " + data.anchorY + ", " + data.anchorZ,
            this.guiLeft + 13,
            this.guiTop + 20,
            0x404040);
        // 维度行
        this.fontRendererObj.drawString(
            tr("gtswn.gui.quantum.dimension") + ": " + data.anchorDim,
            this.guiLeft + 13,
            this.guiTop + 32,
            0x404040);
        // 量子节点数行（v1.6.9 新增字段）
        this.fontRendererObj.drawString(
            tr("gtswn.gui.quantum.node_count") + ": " + data.quantumNodeCount,
            this.guiLeft + 13,
            this.guiTop + 44,
            0x404040);
        // 频道行（保留 v1.6.8 三件套 used/total/(pct%)，过载态红色高亮）
        if (data.channelsInfinite) {
            String channelLine = tr("gtswn.gui.quantum.channels") + ": " + data.usedChannels + " / \u221e";
            this.fontRendererObj.drawString(channelLine, this.guiLeft + 13, this.guiTop + 56, 0x404040);
            return;
        }
        int channelPct = data.totalChannels > 0 ? (int) (data.usedChannels * 100L / data.totalChannels) : 0;
        int channelColor = data.usedChannels > data.totalChannels ? 0xFF0000 : 0x404040;
        String channelLine = tr("gtswn.gui.quantum.channels") + ": "
            + data.usedChannels
            + " / "
            + data.totalChannels
            + " ("
            + channelPct
            + "%)";
        this.fontRendererObj.drawString(channelLine, this.guiLeft + 13, this.guiTop + 56, channelColor);
    }

    /**
     * 绘制离线状态：与在线态布局统一，四项核心信息始终显示（节点数/频道数硬编码 0），末行追加离线红字提示。
     * <p>
     * 离线时 totalChannels/usedChannels/quantumNodeCount 在服务端均默认 0，但显式硬编码字符串
     * "0 / 0 (0%)" 避免任何计算（含 totalChannels=0 时的除零分支），离线态显示稳定。
     */
    private void drawOffline(QuantumNetworkData data) {
        // 控制器坐标行（离线快照仍有锚点信息）
        this.fontRendererObj.drawString(
            tr("gtswn.gui.quantum.controller_pos") + ": " + data.anchorX + ", " + data.anchorY + ", " + data.anchorZ,
            this.guiLeft + 13,
            this.guiTop + 20,
            0x404040);
        // 维度行
        this.fontRendererObj.drawString(
            tr("gtswn.gui.quantum.dimension") + ": " + data.anchorDim,
            this.guiLeft + 13,
            this.guiTop + 32,
            0x404040);
        // 量子节点数行（离线时硬编码 0）
        this.fontRendererObj
            .drawString(tr("gtswn.gui.quantum.node_count") + ": 0", this.guiLeft + 13, this.guiTop + 44, 0x404040);
        // 频道行（离线时硬编码 "0 / 0 (0%)"）
        this.fontRendererObj.drawString(
            tr("gtswn.gui.quantum.channels") + ": 0 / 0 (0%)",
            this.guiLeft + 13,
            this.guiTop + 56,
            0x404040);
        // 离线提示
        this.fontRendererObj.drawString(
            EnumChatFormatting.RED + tr("gtswn.gui.quantum.offline"),
            this.guiLeft + 13,
            this.guiTop + 72,
            0x404040);
    }

    /** 本地化工具 */
    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }
}
