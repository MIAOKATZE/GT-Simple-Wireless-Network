package com.miaokatze.gtswn.network;

import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.fluids.FluidStack;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 面板操作待处理队列（B07 = O2-B07，吸收 B2-01 修复）：包 2/3 的「Netty 入队 → 主线程 drain」。
 * <p>
 * 线程事实（B2-01）：{@code SimpleChannelHandlerWrapper} 在 Netty 网络线程调用 onMessage，
 * 包 2/3 Handler 原先直改 {@link TileEntityNetworkInfoPanel} 世界态，与主线程
 * sampleAENetwork 的 for-each（AEMonitorDataSet 窗口 Map）并发。本队列把世界态修改全部
 * 归位服务端主线程，语义变化仅「配置应用延迟 ≤1 tick」（玩家无感）。
 * <p>
 * 设计取舍：
 * <ul>
 * <li><b>无同玩家去重门</b>（不继承 {@code PlayerRequestQueue}）：包 0/5 是幂等状态查询可去重，
 * 面板操作是异质变更序列——同 tick 内「清空全部 → 重新绑定」合法，去重会丢操作；
 * {@link ConcurrentLinkedQueue} FIFO 天然保到达序，满足同玩家连点操作的顺序要求。</li>
 * <li><b>自宿主 drain（swn-survey-a 方案 (c)）</b>：本类自带 @SubscribeEvent ServerTickEvent(END)
 * 监听，不挂 {@code QuantumControllerEventHandler}（避免与 B2-05 同文件串行、面板操作挂量子域
 * 语义不当）也不挂 {@code NetworkInfoMonitorScheduler}（panel→network 会成新环）——零新增
 * 跨包边，排空点即本类（network→tile 的唯一受控调用点）。</li>
 * <li><b>主线程复验</b>：入队后玩家可能掉线/远离，drain 逐条复验 playerNetServerHandler 非空
 * + 64D 距离 + TE instanceof 后才执行（逻辑自包 2/3 Handler 逐字搬迁）；单条异常仅记日志丢弃，
 * 不中断本 tick 后续操作。</li>
 * <li>载荷字段（含 {@code stackData} NBT）在 fromBytes 后线程封闭，可直接引用传递。</li>
 * </ul>
 */
public final class PanelActionQueue {

    /** 待处理操作队列（仅缓存载荷，主线程 drain 时再复验在线/距离/TE 类型） */
    private static final ConcurrentLinkedQueue<PanelAction> PENDING = new ConcurrentLinkedQueue<>();

    private PanelActionQueue() {}

    /**
     * 注册自宿主 tick 监听（CommonProxy.preInit 与 {@code GTSWNPacketHandler.register} 同址调用一次）。
     */
    public static void register() {
        FMLCommonHandler.instance()
            .bus()
            .register(new PanelActionQueue());
    }

    /** Netty 线程入队：包 2 信息屏配置操作（EU/AE 图表配置行协议与 action 按钮） */
    public static void enqueueConfig(EntityPlayerMP player, int x, int y, int z, int action, String chartConfig) {
        PENDING.add(new ConfigAction(player, x, y, z, action, chartConfig));
    }

    /** Netty 线程入队：包 3 AE 标签页切换与监视列表操作 */
    public static void enqueueAETab(EntityPlayerMP player, int x, int y, int z, byte actionType, int tabIndex,
        NBTTagCompound stackData) {
        PENDING.add(new AETabAction(player, x, y, z, actionType, tabIndex, stackData));
    }

    /**
     * 主线程逐条处理：复验在线/距离/TE 类型后应用（本 tick 内排空当前快照）。
     * <p>
     * 复验失败（掉线/远离/方块已破坏替换）静默丢弃，与旧 Netty 直改路径的 8 格拦截语义一致。
     */
    public static void drain() {
        PanelAction action;
        while ((action = PENDING.poll()) != null) {
            EntityPlayerMP player = action.player;
            if (player == null || player.playerNetServerHandler == null) {
                continue;
            }
            World world = player.worldObj;
            if (world == null || player.getDistanceSq(action.x + 0.5D, action.y + 0.5D, action.z + 0.5D) > 64D) {
                continue;
            }
            TileEntity tile = world.getTileEntity(action.x, action.y, action.z);
            if (!(tile instanceof TileEntityNetworkInfoPanel)) {
                continue;
            }
            try {
                action.apply((TileEntityNetworkInfoPanel) tile);
            } catch (Throwable t) {
                GTSimpleWirelessNetwork.LOG.error("[PanelActionQueue] 面板操作应用异常（丢弃该条，继续后续）", t);
            }
        }
    }

    /** ServerTickEvent END phase 自宿主排空（所有方块 updateEntity 已完成，主线程语义） */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        drain();
    }

    /** 面板操作载荷：发起玩家 + 目标信息屏坐标，apply 为逐字搬迁的操作逻辑 */
    private abstract static class PanelAction {

        /** 发起玩家（Netty 线程捕获引用，drain 时复验在线） */
        protected final EntityPlayerMP player;

        /** 目标信息屏坐标 */
        protected final int x;
        protected final int y;
        protected final int z;

        PanelAction(EntityPlayerMP player, int x, int y, int z) {
            this.player = player;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        /** 主线程复验通过后对目标信息屏应用操作（自包 2/3 Handler 逐字搬迁） */
        abstract void apply(TileEntityNetworkInfoPanel panel);
    }

    /** 包 2 载荷：{@link PacketUpdateNetworkInfoPanelConfig}（原 Handler.apply 逻辑） */
    private static final class ConfigAction extends PanelAction {

        private final int action;
        private final String chartConfig;

        ConfigAction(EntityPlayerMP player, int x, int y, int z, int action, String chartConfig) {
            super(player, x, y, z);
            this.action = action;
            this.chartConfig = chartConfig;
        }

        @Override
        void apply(TileEntityNetworkInfoPanel panel) {
            if (action == PacketUpdateNetworkInfoPanelConfig.ACTION_CHART_CONFIG) {
                panel.applyChartConfig(chartConfig);
            } else if (action == PacketUpdateNetworkInfoPanelConfig.ACTION_AE_CHART_CONFIG) {
                panel.applyAEChartConfig(chartConfig);
            } else if (action >= 0) {
                panel.applyConfigAction(action);
            }
        }
    }

    /** 包 3 载荷：{@link PacketUpdateAETabState}（原 Handler switch 逻辑） */
    private static final class AETabAction extends PanelAction {

        private final byte actionType;
        private final int tabIndex;
        private final NBTTagCompound stackData;

        AETabAction(EntityPlayerMP player, int x, int y, int z, byte actionType, int tabIndex,
            NBTTagCompound stackData) {
            super(player, x, y, z);
            this.actionType = actionType;
            this.tabIndex = tabIndex;
            this.stackData = stackData;
        }

        @Override
        void apply(TileEntityNetworkInfoPanel panel) {
            switch (actionType) {
                case 0: // 切换标签页
                    panel.setCurrentTab(tabIndex);
                    break;
                case 1: // 走势图绑定物品
                    if (stackData != null) {
                        ItemStack stack = ItemStack.loadItemStackFromNBT(stackData);
                        panel.setChartItem(stack);
                    }
                    break;
                case 2: // 走势图绑定流体
                    if (stackData != null) {
                        FluidStack fluid = FluidStack.loadFluidStackFromNBT(stackData);
                        panel.setChartFluid(fluid);
                    }
                    break;
                case 3: // 监控列表切换物品
                    if (stackData != null) {
                        ItemStack stack = ItemStack.loadItemStackFromNBT(stackData);
                        panel.toggleItemMonitor(stack);
                    }
                    break;
                case 4: // 监控列表切换流体
                    if (stackData != null) {
                        FluidStack fluid = FluidStack.loadFluidStackFromNBT(stackData);
                        panel.toggleFluidMonitor(fluid);
                    }
                    break;
                case 5: // 清除走势图绑定
                    panel.clearAEBinding();
                    break;
                case 6: // 清除 AE 实时监控全部物品与流体
                    panel.clearAllAEMonitors();
                    break;
                default:
                    break;
            }
        }
    }
}
