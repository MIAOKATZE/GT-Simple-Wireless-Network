package com.miaokatze.gtswn.main;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;

import com.miaokatze.gtswn.client.DeviceTerminalClientCache;
import com.miaokatze.gtswn.client.QuantumNodeHighlightRenderer;
import com.miaokatze.gtswn.client.RevealTriggerHandler;
import com.miaokatze.gtswn.client.WirelessNodeRevealRenderer;
import com.miaokatze.gtswn.client.WirelessTapHighlightRenderer;
import com.miaokatze.gtswn.client.gui.GuiDeviceInfoTerminal;
import com.miaokatze.gtswn.client.gui.GuiNetworkInfoPanel;
import com.miaokatze.gtswn.client.gui.GuiQuantumTerminal;
import com.miaokatze.gtswn.client.render.RenderNetworkInfoPanel;
import com.miaokatze.gtswn.client.render.RenderNetworkQuantumNode;
import com.miaokatze.gtswn.common.block.BlockNetworkQuantumNode;
import com.miaokatze.gtswn.common.hud.HudController;
import com.miaokatze.gtswn.common.hud.WirelessMonitorHUD;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.network.PacketSyncAEMonitorData;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData;
import com.miaokatze.gtswn.network.PacketSyncNodeReveal;
import com.miaokatze.gtswn.network.PacketSyncQuantumTerminalData;
import com.miaokatze.gtswn.network.PacketSyncQuantumTerminalDataLite;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.event.FMLInitializationEvent;

/**
 * 客户端代理类
 * 继承自 CommonProxy，用于处理仅在客户端（Client Side）执行的逻辑。
 * 例如：渲染注册、按键绑定、GUI 打开等。
 */
public class ClientProxy extends CommonProxy {

    /**
     * HUD 业务控制器唯一实例（O2-B10：原 WirelessMonitorHUD 的 static 可变态收编为
     * HudState 实例，本字段即「唯一 static 持有点 = ClientProxy 注册处」；
     * 渲染器与 EU 回包路径经构造/本字段共享同一实例）。
     */
    private final HudController hudController = new HudController();

    /**
     * 初始化阶段 (Init)
     * 在此阶段注册客户端特定的事件处理器，如 HUD 渲染器。
     */
    @Override
    public void init(FMLInitializationEvent event) {
        // 调用父类的 init 方法，确保通用逻辑正常执行
        super.init(event);

        // 注册 HUD 渲染器到 Forge 事件总线（仅在客户端）
        // 注意：RenderGameOverlayEvent 是 Forge 事件，必须注册到 MinecraftForge.EVENT_BUS
        GTSimpleWirelessNetwork.LOG.info("[2/2] 注册客户端 HUD 渲染器...");
        MinecraftForge.EVENT_BUS.register(new WirelessMonitorHUD(this.hudController));
        // 注册无线链路终端辅助线渲染器（DrawBlockHighlightEvent，与 GT 扳手/覆盖板工具相同机制）
        MinecraftForge.EVENT_BUS.register(new WirelessTapHighlightRenderer());
        // v1.6.1 问题 2：注册量子节点放置预览框渲染器（手持已绑定量子终端瞄准可放置位置时画青色预览盒）
        MinecraftForge.EVENT_BUS.register(new QuantumNodeHighlightRenderer());
        // 节点显形渲染器（RenderWorldLastEvent 穿墙线框 + WorldEvent.Unload 清缓存）：
        // 包 12 显形回包经 handleSyncNodeReveal 切主线程写缓存后由此绘制
        MinecraftForge.EVENT_BUS.register(new WirelessNodeRevealRenderer());
        // v1.7.23：Alt+右键即时显形触发器（MouseEvent 按下沿 + Alt 按住 + 手持链路终端 →
        // 发 disc 11 并取消原版右键；替代 v1.7.20~v1.7.22 的右击空气蓄力路径）
        MinecraftForge.EVENT_BUS.register(new RevealTriggerHandler());
        ClientRegistry.bindTileEntitySpecialRenderer(TileEntityNetworkInfoPanel.class, new RenderNetworkInfoPanel());

        // v1.6.1 问题 1：注册量子节点 ISBRH（线缆形态：小核心 + 朝 AE 网格宿主的连接臂）。
        // 【双端安全】renderId 先由 register() 申请并注册渲染器，再回写到 Block 类的静态 int 字段，
        // Block.getRenderType 只读该 int，Block 类不引用任何 client 包类，服务端加载安全。
        RenderNetworkQuantumNode.register();
        BlockNetworkQuantumNode.renderId = RenderNetworkQuantumNode.INSTANCE.getRenderId();

        // 注：原 PlayerLoggedOutEvent 监听器用于保存便携式 HUD 历史到物品 NBT，
        // 已随 WirelessMonitorHUD.saveHistoryToItemStack 删除而移除（用户确认便携式随退出登录重置）。
        // HUD 状态会在下次背包周期扫描（scanMonitorInInventory，每 20t）时自动重置：
        // - 无监视器 → clearCache() 清空所有缓存
        // - 有监视器 → 重新初始化，靠 gap 检测和首次检测重建数据集
    }

    /**
     * 【hotfix v1.5.14】客户端处理 EU 响应包：调度到主线程后写入 HUD 缓存。
     * <p>
     * 【线程安全】1.7.10 的 SimpleChannelHandlerWrapper.channelRead0 直接在 Netty 网络线程
     * 调用 onMessage，而 HUD 渲染在客户端主线程读取 static 缓存，两者并发会竞争 static 字段。
     * 故用 {@link Minecraft#func_152344_a(Runnable)}（1.7.10 中 addScheduledTask 的 SRG 名）
     * 把写操作调度到主线程。
     * <p>
     * 【类加载安全】本方法在 ClientProxy 中，ClientProxy 只在客户端被 @SidedProxy 机制加载，
     * 服务端不会加载本类，故可安全引用 {@code Minecraft} 等客户端类。
     */
    @Override
    public void handleResponseEU(String euStr) {
        // 1.7.10 API：func_152344_a 等价于 1.8+ 的 addScheduledTask，调度到客户端主线程
        // O2-B10：EU 回包写入经控制器实例（原 WirelessMonitorHUD.receiveSyncedEU 静态入口随 B10 实例化）
        Minecraft.getMinecraft()
            .func_152344_a(() -> this.hudController.receiveSyncedEU(euStr));
    }

    /**
     * 【hotfix v1.5.14】客户端处理 AE 监控数据同步包：定位信息屏 TileEntity，切回主线程后写入 AE 缓存。
     * <p>
     * 【线程安全】SimpleChannelHandlerWrapper 在 Netty 网络线程调用 onMessage，而 GUI/TESR
     * 在客户端主线程读取 TileEntity 字段，故用 {@link Minecraft#func_152344_a(Runnable)}
     * 把写操作调度到主线程，避免并发读到半更新状态。
     * <p>
     * 【类加载安全】本方法在 ClientProxy 中，ClientProxy 只在客户端被 @SidedProxy 机制加载，
     * 服务端不会加载本类，故可安全引用 {@code Minecraft.getMinecraft().theWorld}
     * （theWorld 字段类型为 WorldClient，@SideOnly(Side.CLIENT)）。
     */
    @Override
    public void handleSyncAEMonitorData(PacketSyncAEMonitorData msg) {
        // 获取客户端世界（WorldClient，仅在客户端可访问）
        final World world = Minecraft.getMinecraft().theWorld;
        if (world == null) {
            return;
        }

        // 先在网络线程定位 TileEntity（避免主线程 world 快照不一致）
        final TileEntity te = world.getTileEntity(msg.getX(), msg.getY(), msg.getZ());
        if (!(te instanceof TileEntityNetworkInfoPanel)) {
            return;
        }

        // 切到主线程再修改 TileEntity 字段
        Minecraft.getMinecraft()
            .func_152344_a(() -> {
                TileEntityNetworkInfoPanel panel = (TileEntityNetworkInfoPanel) world
                    .getTileEntity(msg.getX(), msg.getY(), msg.getZ());
                if (panel != null) {
                    panel.receiveAEMonitorData(msg.getChartSamples(), msg.getMonitorLatest(), msg.getMonitorAvg300s());
                }
            });
    }

    /**
     * 客户端处理量子终端数据同步包：切主线程后写入 GUI 静态缓存。
     * <p>
     * 【线程安全】与 {@link #handleSyncAEMonitorData} 同理：onMessage 运行在 Netty 网络线程，
     * 而 GuiQuantumTerminal 在客户端主线程读取静态缓存，故用
     * {@link Minecraft#func_152344_a(Runnable)} 把写操作调度到主线程。
     * <p>
     * 【类加载安全】本方法在 ClientProxy 中，仅客户端加载，可安全引用 Minecraft 与 GUI 类。
     */
    @Override
    public void handleSyncQuantumTerminalData(PacketSyncQuantumTerminalData msg) {
        final QuantumNetworkData data = msg.getData();
        if (data == null) {
            return;
        }
        // 1.7.10 API：func_152344_a 等价于 1.8+ 的 addScheduledTask，调度到客户端主线程
        Minecraft.getMinecraft()
            .func_152344_a(() -> GuiQuantumTerminal.receiveData(data));
    }

    /**
     * 客户端处理量子终端短回包（disc 7，O2-17）：切主线程后写入 GUI 静态缓存。
     * <p>
     * 线程安全与类加载安全模式同 {@link #handleSyncQuantumTerminalData}；
     * 短包仅 9 字段有效，receiveData 接口不变（GUI 零改动）。
     */
    @Override
    public void handleSyncQuantumTerminalDataLite(PacketSyncQuantumTerminalDataLite msg) {
        final QuantumNetworkData data = msg.getData();
        if (data == null) {
            return;
        }
        Minecraft.getMinecraft()
            .func_152344_a(() -> GuiQuantumTerminal.receiveData(data));
    }

    /**
     * 客户端处理设备信息终端数据分页同步包（disc 9，阶段 D2）：切主线程后写
     * {@link DeviceTerminalClientCache}（分页到齐整体替换防撕裂，缓存锚点=终端 UUID
     * 不随 GUI 关闭清空）。
     * <p>
     * 线程安全与类加载安全模式同 {@link #handleSyncQuantumTerminalData}：onMessage 运行
     * 在 Netty 网络线程，用 {@link Minecraft#func_152344_a(Runnable)} 切主线程；本方法
     * 仅客户端加载，可安全引用客户端缓存类。
     */
    @Override
    public void handleSyncDeviceTerminalData(PacketSyncDeviceTerminalData msg) {
        final java.util.UUID terminalId = msg.getTerminalId();
        if (terminalId == null) {
            // 坏包退化的惰性消息：丢弃
            return;
        }
        final long version = msg.getVersion();
        final int pageIndex = msg.getPageIndex();
        final int pageTotal = msg.getPageTotal();
        final java.util.List<com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry> page = new java.util.ArrayList<>(
            msg.getEntries());
        // 1.7.10 API：func_152344_a 等价于 1.8+ 的 addScheduledTask，调度到客户端主线程
        Minecraft.getMinecraft()
            .func_152344_a(
                () -> DeviceTerminalClientCache.receivePage(terminalId, version, pageIndex, pageTotal, page));
    }

    /**
     * 客户端处理节点显形同步包（disc 12）：切主线程后写
     * {@link WirelessNodeRevealRenderer} 显形缓存（空列表 = 清缓存语义）。
     * <p>
     * 线程安全与类加载安全模式同 {@link #handleSyncDeviceTerminalData}：onMessage 运行
     * 在 Netty 网络线程，用 {@link Minecraft#func_152344_a(Runnable)} 切主线程；
     * 维度在主线程取客户端当前世界（服务端围绕请求玩家当前位置查询，玩家必在同维），
     * 过期锚点由 {@code acceptReveal} 用客户端墙钟（收包时刻 + durationTicks×50ms）计算。
     */
    @Override
    public void handleSyncNodeReveal(PacketSyncNodeReveal msg) {
        final long serverTotalWorldTime = msg.getServerTotalWorldTime();
        final int durationTicks = msg.getDurationTicks();
        final java.util.List<PacketSyncNodeReveal.RevealedNode> nodes = new java.util.ArrayList<>(msg.getNodes());
        // 1.7.10 API：func_152344_a 等价于 1.8+ 的 addScheduledTask，调度到客户端主线程
        Minecraft.getMinecraft()
            .func_152344_a(() -> {
                final World world = Minecraft.getMinecraft().theWorld;
                if (world == null) {
                    return;
                }
                WirelessNodeRevealRenderer
                    .acceptReveal(world.provider.dimensionId, serverTotalWorldTime, durationTicks, nodes);
                // [GTSWN-REVEAL-PROBE] C4
                GTSimpleWirelessNetwork.LOG
                    .info("[GTSWN-REVEAL] C4 accept dim=" + world.provider.dimensionId + " n=" + nodes.size());
            });
    }

    @Override
    public void openQuantumTerminalGui() {
        Minecraft.getMinecraft()
            .displayGuiScreen(new GuiQuantumTerminal());
    }

    /**
     * 客户端打开设备信息终端 GUI（阶段 E3：{@code ItemDeviceInfoTerminal.onItemRightClick}
     * 非 Shift 右击空气经 @SidedProxy 委托至此）。
     * <p>
     * 解析手持优先 → 主背包首台的设备信息终端（与服务端
     * {@code DeviceTerminalRequestQueue.findTerminalStack} 同序），<b>只读</b>其 DIT_UUID 与
     * UI 偏好（不生成 UUID——服务端 onItemRightClick 同拍生成并显式 S2FPacketSetSlot 同步；
     * 未同步到时 GUI 先显示「...」占位，逐 tick 只读重解析自动锚定）后本地 displayGuiScreen。
     * 未持有终端（防御）静默不打开。
     */
    @Override
    public void openDeviceInfoTerminalGui() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.thePlayer;
        if (player == null) {
            return;
        }
        ItemStack stack = player.getHeldItem();
        if (!(stack != null && stack.getItem() instanceof ItemDeviceInfoTerminal)) {
            stack = null;
            for (ItemStack candidate : player.inventory.mainInventory) {
                if (candidate != null && candidate.getItem() instanceof ItemDeviceInfoTerminal) {
                    stack = candidate;
                    break;
                }
            }
        }
        if (stack == null) {
            return;
        }
        mc.displayGuiScreen(new GuiDeviceInfoTerminal(ItemDeviceInfoTerminal.readTerminalId(stack), stack));
    }

    /**
     * HUD 模式切换意图的客户端落地（O2-B10：物品侧经 @SidedProxy 发意图，本方法转
     * {@link HudController#applyHudToggle}；同 tick 执行，与原静态 setter 直调语义一致）。
     */
    @Override
    public void toggleHudMode(int mode, String ownerUUID) {
        this.hudController.applyHudToggle(mode, ownerUUID);
    }

    @Override
    public void openNetworkInfoPanelGui(TileEntityNetworkInfoPanel panel) {
        Minecraft.getMinecraft()
            .displayGuiScreen(new GuiNetworkInfoPanel(panel));
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        return null;
    }
}
