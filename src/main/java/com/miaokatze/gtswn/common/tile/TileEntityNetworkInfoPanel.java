package com.miaokatze.gtswn.common.tile;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;

import com.miaokatze.gtswn.common.panel.AEMonitorDataSet;
import com.miaokatze.gtswn.common.panel.AEMonitorDataStore;
import com.miaokatze.gtswn.common.panel.AEMonitorSample;
import com.miaokatze.gtswn.common.panel.NetworkInfoDataSet;
import com.miaokatze.gtswn.common.panel.NetworkInfoDataStore;
import com.miaokatze.gtswn.common.panel.NetworkInfoSample;
import com.miaokatze.gtswn.common.panel.NetworkScreen;
import com.miaokatze.gtswn.common.panel.PanelBroadcastPort;
import com.miaokatze.gtswn.common.panel.PanelConfigStore;
import com.miaokatze.gtswn.common.tile.screen.ScreenStructure;
import com.miaokatze.gtswn.common.util.FormatUtil;
import com.miaokatze.gtswn.common.util.GTTierUtil;
import com.miaokatze.gtswn.config.Config;

import appeng.api.networking.GridFlags;
import appeng.api.networking.IGridNode;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.util.AECableType;
import appeng.api.util.DimensionalCoord;
import appeng.me.GridAccessException;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEItemStack;

public class TileEntityNetworkInfoPanel extends TileEntity implements IGridProxyable {

    /**
     * B07（O2-B07 拆环后半）：广播端口——tile→network 依赖经接口反转，
     * 由 CommonProxy.init 注入 network 侧实现（NetworkPanelBroadcastPort），本类不再 import 网络包类。
     */
    private static PanelBroadcastPort broadcastPort = null;

    /** 注册期注入广播端口（CommonProxy.init 调用一次；未注入时服务端推送静默跳过） */
    public static void setBroadcastPort(PanelBroadcastPort port) {
        broadcastPort = port;
    }

    private UUID ownerUUID;
    private String ownerName = "";

    private BigInteger cachedEu = BigInteger.ZERO;
    private double cachedEut = 0.0D;
    private String cachedStatus = "No data";
    private final List<NetworkInfoSample> cachedSamples = new ArrayList<>();

    /**
     * E2（O2-01b）：多方块结构域——BFS 连通重建/最大填满子矩形/Extender 附着与渲染包围盒，
     * 方法体逐字搬迁至 {@link ScreenStructure}，本类保留门面单行委托（外部调用面零改动）。
     */
    private final ScreenStructure structure = new ScreenStructure(this);

    /**
     * E3（O2-02）：面板配置域——全部显示/图表配置 + 行协议 + NBT/S35 序列化通道，
     * 方法体逐字搬迁至 {@link PanelConfigStore}；本类保留 getter 门面单行委托，
     * case 4/7/24 的跨域后置动作留 TE 编排（O2-A06），键名逐字不动。
     */
    private final PanelConfigStore store = new PanelConfigStore(this::markDirtyAndSync);

    /** AE2 网络代理，懒加载，首次调用 getProxy() 时初始化 */
    private AENetworkProxy gridProxy = null;

    /** 标记 proxy 是否已就绪（onReady 已调用） */
    private boolean aeProxyReady = false;

    /** 区块加载时 worldObj 可能尚未设置，暂存 AE proxy NBT，待世界可用后再恢复 */
    private NBTTagCompound pendingProxyNBT = null;
    /** 标记是否需要从 NetworkInfoDataStore 刷新一次缓存（重载/放置后） */
    private boolean needsDataRefresh = true;

    /** 上次已知采样 tick（轮询时检测新数据用） */
    private long lastKnownSampleTick = -1L;

    /**
     * O2-28：owner 数据集解析缓存（ownerUUID 绑定不变时引用稳定，
     * 免 updateEntity 每 tick 2 次 UUID.toString + mapStorage 查询 + getOrCreate HashMap）。
     * store 侧 remove/cleanupStale 推进 revision，缓存据此失效重解析。
     */
    private NetworkInfoDataStore cachedOwnerDataStore = null;
    private NetworkInfoDataSet cachedOwnerDataSet = null;
    private int cachedOwnerDataSetRevision = -1;

    // === AE 标签页相关字段 ===

    /** 当前标签页：0=EU网络, 1=AE走势图, 2=AE实时监控 */
    private int currentTab = 0;

    /** AE 走势图绑定的物品（null 表示未绑定） */
    private ItemStack chartItem = null;

    /** AE 走势图绑定的流体（null 表示未绑定） */
    private FluidStack chartFluid = null;

    /** AE 实时监控的物品列表 */
    private final List<ItemStack> monitoredItems = new ArrayList<>();

    /** AE 实时监控的流体列表 */
    private final List<FluidStack> monitoredFluids = new ArrayList<>();

    /** 上次 AE 采样 tick，-1 表示尚未采样 */
    private long lastAESampleTick = -1L;

    /** 客户端 AE 走势图样本缓存（由 PacketSyncAEMonitorData 推送） */
    private final List<AEMonitorSample> aeChartSamples = new ArrayList<>();

    /** 客户端 AE 实时监控列表最新值缓存（key → 最新样本） */
    private final Map<String, AEMonitorSample> aeMonitorLatest = new HashMap<>();

    /** 客户端 AE 实时监控 300s 平均变化率缓存（key → 每分钟数量变化） */
    private final Map<String, Double> aeMonitorAvg300s = new HashMap<>();

    @Override
    public void updateEntity() {
        super.updateEntity();
        if (worldObj == null) {
            return;
        }
        long tick = worldObj.getTotalWorldTime();
        if (!worldObj.isRemote) {
            if (pendingProxyNBT != null) {
                getProxy().readFromNBT(pendingProxyNBT);
                pendingProxyNBT = null;
            }
            if (!aeProxyReady) {
                getProxy().onReady();
                aeProxyReady = true;
            }
            if (!structure.isInitialized()) {
                structure.rebuild();
                structure.markInitialized();
            }

            // ===== 获取 overworld tick（供 lastRequestTick 与轮询逻辑共用） =====
            // 调度器使用 overworld tick 作为采样基准，panel 必须用同一基准更新 lastRequestTick
            MinecraftServer server = MinecraftServer.getServer();
            long overworldTick = -1L;
            if (server != null) {
                World overworld = server.worldServerForDimension(0);
                if (overworld != null) {
                    overworldTick = overworld.getTotalWorldTime();
                }
            }

            // ===== 更新请求 tick（调度器据此判断是否活跃采样） =====
            // 信息屏每次 updateEntity 都更新 lastRequestTick，调度器仅对 5 分钟内有请求的 dataSet 采样
            if (ownerUUID != null && overworldTick >= 0L) {
                NetworkInfoDataSet dataSet = getOwnerDataSet();
                if (dataSet != null) {
                    dataSet.updateRequestTick(overworldTick);
                }
            }

            // ===== 冷启动数据刷新（重进存档/放置方块后执行一次） =====
            if (needsDataRefresh && ownerUUID != null) {
                needsDataRefresh = false;
                // 从全局数据集拉取该玩家已有数据填入缓存
                refreshCachedSamples();
                NetworkInfoDataSet dataSet = getOwnerDataSet();
                if (dataSet != null) {
                    NetworkInfoSample newest = dataSet.newest();
                    if (newest != null) {
                        cachedEu = newest.eu;
                        cachedEut = newest.eut;
                        lastKnownSampleTick = newest.tick;
                    }
                    boolean cold = dataSet.isColdStarting();
                    boolean longSilent = dataSet.isLongTermSilent();
                    cachedStatus = formatStatus(cachedEut, cold, longSilent);
                }
                markDirty();
                worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
            }

            // ===== 轮询全局数据集，检测新采样数据 =====
            if (ownerUUID != null) {
                NetworkInfoDataSet dataSet = getOwnerDataSet();
                if (dataSet != null) {
                    NetworkInfoSample newest = dataSet.newest();
                    if (newest != null && newest.tick != lastKnownSampleTick) {
                        cachedEu = newest.eu;
                        cachedEut = newest.eut;
                        boolean cold = dataSet.isColdStarting();
                        boolean longSilent = dataSet.isLongTermSilent();
                        cachedStatus = formatStatus(cachedEut, cold, longSilent);
                        refreshCachedSamples(dataSet);
                        lastKnownSampleTick = newest.tick;
                        markDirty();
                        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
                    }
                }
            }

            // 服务端每 Config.aeSampleInterval ticks 执行一次 AE 采样并推送给客户端
            if (Config.aeChartEnabled
                && (lastAESampleTick < 0L || tick - lastAESampleTick >= Config.aeSampleInterval)) {
                sampleAENetwork(tick);
                lastAESampleTick = tick;
            }
        }
    }

    // ==================== AE2 网络节点生命周期 ====================

    @Override
    public void validate() {
        super.validate();
        if (gridProxy != null) {
            gridProxy.validate();
        }
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (gridProxy != null) {
            gridProxy.invalidate();
        }
    }

    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        if (gridProxy != null) {
            gridProxy.onChunkUnload();
        }
    }

    // ==================== IGridProxyable 接口实现 ====================

    @Override
    public AENetworkProxy getProxy() {
        if (gridProxy == null && !worldObj.isRemote) {
            gridProxy = new AENetworkProxy(this, "proxy", null, true);
            gridProxy.setFlags(GridFlags.REQUIRE_CHANNEL);
            gridProxy.setValidSides(EnumSet.allOf(ForgeDirection.class));
        }
        return gridProxy;
    }

    @Override
    public DimensionalCoord getLocation() {
        return new DimensionalCoord(worldObj, xCoord, yCoord, zCoord);
    }

    @Override
    public void gridChanged() {
        // AE2 网络连接变化回调，不做复杂操作
    }

    @Override
    public IGridNode getGridNode(ForgeDirection dir) {
        if (worldObj == null || worldObj.isRemote) return null;
        return getProxy().getNode();
    }

    @Override
    public AECableType getCableConnectionType(ForgeDirection dir) {
        return AECableType.SMART;
    }

    @Override
    public void securityBreak() {
        // AE2 安全系统回调，不做破坏
    }

    // ==================== AE2 存储读取辅助方法 ====================

    /**
     * 判断当前是否已连接到 AE2 网络且网络有电。
     *
     * @return true 表示已连接且通电
     */
    public boolean isAEConnected() {
        if (worldObj == null || worldObj.isRemote || gridProxy == null) return false;
        try {
            IGridNode node = gridProxy.getNode();
            if (node == null) return false;
            return gridProxy.isPowered();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 查询 AE2 网络中指定物品的存储数量。
     *
     * @param itemStack 要查询的物品（非 null）
     * @return 存储数量，未连接或未找到时返回 0
     */
    public long getAEItemAmount(net.minecraft.item.ItemStack itemStack) {
        if (worldObj == null || worldObj.isRemote || gridProxy == null || itemStack == null) return 0;
        try {
            IMEMonitor<IAEItemStack> itemInv = gridProxy.getStorage()
                .getItemInventory();
            IAEItemStack request = AEItemStack.create(itemStack);
            IAEItemStack stored = itemInv.getStorageList()
                .findPrecise(request);
            return stored != null ? stored.getStackSize() : 0;
        } catch (GridAccessException e) {
            return 0;
        }
    }

    /**
     * 查询 AE2 网络中指定流体的存储数量。
     *
     * @param fluidStack 要查询的流体（非 null）
     * @return 存储数量，未连接或未找到时返回 0
     */
    public long getAEFluidAmount(FluidStack fluidStack) {
        if (worldObj == null || worldObj.isRemote || gridProxy == null || fluidStack == null) return 0;
        try {
            IMEMonitor<IAEFluidStack> fluidInv = gridProxy.getStorage()
                .getFluidInventory();
            IAEFluidStack request = AEFluidStack.create(fluidStack);
            IAEFluidStack stored = fluidInv.getStorageList()
                .findPrecise(request);
            return stored != null ? stored.getStackSize() : 0;
        } catch (GridAccessException e) {
            return 0;
        }
    }

    /**
     * 生成本方块在 AEMonitorDataStore 中使用的坐标主键。
     * <p>
     * 格式：{@code dimensionId:xCoord:yCoord:zCoord}。
     * 该 key 随方块位置唯一确定，避免使用 panelUUID 带来的存档迁移与数据残留问题。
     *
     * @return 坐标字符串；world 不可用时返回 null
     */
    public String getAEMonitorDataKey() {
        if (worldObj == null) {
            return null;
        }
        return worldObj.provider.dimensionId + ":" + xCoord + ":" + yCoord + ":" + zCoord;
    }

    /**
     * 清空指定 key 在本屏坐标主键下的 AE 采样数据。
     * <p>
     * 仅服务端执行；坐标 key 未初始化或 world 不可用时安全跳过。
     */
    private void clearAEData(String key) {
        if (key == null || worldObj == null || worldObj.isRemote) {
            return;
        }
        String dataKey = getAEMonitorDataKey();
        if (dataKey == null) {
            return;
        }
        // v1.5.15：改用 getIfPresent 避免为已破坏/卸载的方块创建空数据集导致内存泄漏
        AEMonitorDataStore store = AEMonitorDataStore.get(worldObj);
        AEMonitorDataSet dataSet = store.getIfPresent(dataKey);
        if (dataSet != null) {
            dataSet.clear(key);
            store.markDirty();
        }
    }

    /**
     * 生成 AE 走势图/实时监控的物品 key。
     *
     * @param stack 物品堆（null 或空返回 null）
     * @return item:&lt;registryName&gt;:&lt;meta&gt;
     */
    public static String getAEKey(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return null;
        return "item:" + Item.itemRegistry.getNameForObject(stack.getItem()) + ":" + stack.getItemDamage();
    }

    /**
     * 生成 AE 走势图/实时监控的流体 key。
     *
     * @param fluid 流体堆（null 或空返回 null）
     * @return fluid:&lt;fluidName&gt;
     */
    public static String getAEKey(FluidStack fluid) {
        if (fluid == null || fluid.getFluid() == null) return null;
        return "fluid:" + fluid.getFluid()
            .getName();
    }

    /**
     * 服务端执行 AE 网络采样：对走势图绑定与实时监控列表中的每个物品/流体查询 AE 存量并写入数据集。
     *
     * @param tick 当前世界 tick
     */
    private void sampleAENetwork(long tick) {
        if (!isAEConnected()) {
            // B2-02：断网时改为空推送——清掉客户端走势图/监控缓存，避免 GUI 无限期显示陈旧值；
            // 服务端 WSD 历史不动，重连后由下方采样逻辑恢复
            pushAEMonitorOffline();
            return;
        }

        String dataKey = getAEMonitorDataKey();
        if (dataKey == null) {
            return;
        }

        AEMonitorDataStore store = AEMonitorDataStore.get(worldObj);
        AEMonitorDataSet dataSet = store.getOrCreate(dataKey);
        boolean wroteAny = false;
        long timeMs = System.currentTimeMillis();

        // 走势图物品
        if (chartItem != null) {
            String key = getAEKey(chartItem);
            if (key != null && dataSet.tryAcquireSampleLock(key, tick, Config.aeSampleInterval)) {
                long amount = getAEItemAmount(chartItem);
                dataSet.addSample(key, amount, tick, timeMs);
                wroteAny = true;
            }
        }

        // 走势图流体
        if (chartFluid != null) {
            String key = getAEKey(chartFluid);
            if (key != null && dataSet.tryAcquireSampleLock(key, tick, Config.aeSampleInterval)) {
                long amount = getAEFluidAmount(chartFluid);
                dataSet.addSample(key, amount, tick, timeMs);
                wroteAny = true;
            }
        }

        // 实时监控物品列表
        for (ItemStack stack : monitoredItems) {
            String key = getAEKey(stack);
            if (key != null && dataSet.tryAcquireSampleLock(key, tick, Config.aeSampleInterval)) {
                long amount = getAEItemAmount(stack);
                dataSet.addSample(key, amount, tick, timeMs);
                wroteAny = true;
            }
        }

        // 实时监控流体列表
        for (FluidStack fluid : monitoredFluids) {
            String key = getAEKey(fluid);
            if (key != null && dataSet.tryAcquireSampleLock(key, tick, Config.aeSampleInterval)) {
                long amount = getAEFluidAmount(fluid);
                dataSet.addSample(key, amount, tick, timeMs);
                wroteAny = true;
            }
        }

        if (wroteAny) {
            store.markDirty();
        }

        // 无论是否写入新采样，都推送一次最新数据给客户端，保证 GUI 状态及时
        // O2-20 空屏推送门控：走势图/监控列表全空的屏不再周期推送空包——
        // 解绑/清空后的客户端清显示由 toggle/clear 操作的即时推送负责（见各移除路径）
        if (chartItem != null || chartFluid != null || !monitoredItems.isEmpty() || !monitoredFluids.isEmpty()) {
            sendAEMonitorDataToClients();
        }
    }

    /**
     * 服务端将当前 AE 走势图样本与实时监控最新值推送给周围客户端。
     */
    private void sendAEMonitorDataToClients() {
        if (worldObj == null || worldObj.isRemote) {
            return;
        }

        // O2-21：无接收者预检——64 格（与 TargetPoint 半径一致）内无玩家时连包体都不组装，
        // 无人区常载屏每采样间隔免一次走势 61 点 + 双 Map 三段集合 build；
        // 视锥级过滤不实施（失步无刷新问题，接收端现存 TE 缺席丢弃已够）
        if (!hasNearbyReceiver()) {
            return;
        }

        String dataKey = getAEMonitorDataKey();
        if (dataKey == null) {
            return;
        }

        AEMonitorDataSet dataSet = AEMonitorDataStore.get(worldObj)
            .getIfPresent(dataKey);
        // v1.5.15：无数据可推送时直接返回，避免 getOrCreate 创建空集导致内存泄漏
        if (dataSet == null) {
            return;
        }

        String chartKey = null;
        List<AEMonitorSample> chartSamples = new ArrayList<>();
        if (chartItem != null) {
            chartKey = getAEKey(chartItem);
            if (chartKey != null) {
                chartSamples = dataSet.query(chartKey, getAETrackingWindow());
            }
        } else if (chartFluid != null) {
            chartKey = getAEKey(chartFluid);
            if (chartKey != null) {
                chartSamples = dataSet.query(chartKey, getAETrackingWindow());
            }
        }

        Map<String, AEMonitorSample> monitorLatest = new HashMap<>();
        Map<String, Double> monitorAvg300s = new HashMap<>();
        for (ItemStack stack : monitoredItems) {
            String key = getAEKey(stack);
            if (key == null) continue;
            AEMonitorSample sample = dataSet.newest(key);
            if (sample != null) {
                monitorLatest.put(key, sample);
            }
            monitorAvg300s.put(key, dataSet.averageRate300s(key));
        }
        for (FluidStack fluid : monitoredFluids) {
            String key = getAEKey(fluid);
            if (key == null) continue;
            AEMonitorSample sample = dataSet.newest(key);
            if (sample != null) {
                monitorLatest.put(key, sample);
            }
            monitorAvg300s.put(key, dataSet.averageRate300s(key));
        }

        // B07：经广播端口推送（包体构造与 TargetPoint 由 network 侧端口实现承载，逐字搬迁）
        if (broadcastPort != null) {
            broadcastPort.broadcastAEMonitorData(
                worldObj,
                xCoord,
                yCoord,
                zCoord,
                chartKey,
                chartSamples,
                monitorLatest,
                monitorAvg300s);
        }
    }

    /**
     * O2-21：64 格（与推送 TargetPoint 半径一致）内是否存在玩家接收者。
     * <p>
     * 只查本维度 {@code worldObj.playerEntities}（sendToAllAround 的 TargetPoint 也限定本维度）；
     * 平方距离比较避免开方。
     */
    private boolean hasNearbyReceiver() {
        double sq = 64.0D * 64.0D;
        for (EntityPlayer player : worldObj.playerEntities) {
            double dx = player.posX - (xCoord + 0.5D);
            double dy = player.posY - (yCoord + 0.5D);
            double dz = player.posZ - (zCoord + 0.5D);
            if (dx * dx + dy * dy + dz * dz <= sq) {
                return true;
            }
        }
        return false;
    }

    /**
     * AE 断网时的空推送（B2-02）：向周围客户端推送空数据集，客户端 {@code receiveAEMonitorData}
     * 的 clear+addAll 语义会把走势图/实时监控缓存清空，避免断网后 GUI/TESR 无限期显示陈旧值。
     * <p>
     * 不复用 {@link #sendAEMonitorDataToClients()}：该方法在 dataSet == null 时直接返回，
     * 且 dataSet 存在时推送的是 WSD 旧值而非空态，均达不到清显示的目的。
     * 服务端 WSD 历史不动，重连后由 {@link #sampleAENetwork(long)} 重新采样推送恢复；
     * 节律 = 断网期间每 {@code Config.aeSampleInterval} 一次空包，与在线推送同频。
     */
    private void pushAEMonitorOffline() {
        if (worldObj == null || worldObj.isRemote) {
            return;
        }
        // B07：空数据集推送同经广播端口（B2-02 语义不变，客户端 clear+addAll 即清显示）
        if (broadcastPort != null) {
            broadcastPort.broadcastAEMonitorData(
                worldObj,
                xCoord,
                yCoord,
                zCoord,
                null,
                Collections.<AEMonitorSample>emptyList(),
                Collections.<String, AEMonitorSample>emptyMap(),
                Collections.<String, Double>emptyMap());
        }
    }

    // ==================== AE 标签页与监视列表操作 ====================

    /** 切换当前标签页 */
    public void setCurrentTab(int tab) {
        this.currentTab = (tab >= 0 && tab <= 2) ? tab : 0;
        markDirty();
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
    }

    public int getCurrentTab() {
        return currentTab;
    }

    /**
     * 绑定走势图物品。传入与当前绑定相同物品则清除（再次右键清除）。
     *
     * @return true=新绑定, false=清除绑定
     */
    public boolean setChartItem(ItemStack stack) {
        if (stack != null && chartItem != null && ItemStack.areItemStacksEqual(chartItem, stack)) {
            clearAEData(getAEKey(chartItem));
            chartItem = null;
            markDirty();
            worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
            return false;
        }
        if (chartItem != null) {
            clearAEData(getAEKey(chartItem));
        }
        if (chartFluid != null) {
            clearAEData(getAEKey(chartFluid));
        }
        chartItem = stack != null ? stack.copy() : null;
        chartFluid = null; // 物品与流体互斥
        markDirty();
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
        return true;
    }

    /** 绑定走势图流体 */
    public boolean setChartFluid(FluidStack fluid) {
        if (fluid != null && chartFluid != null
            && fluid.getFluid()
                .equals(chartFluid.getFluid())) {
            clearAEData(getAEKey(chartFluid));
            chartFluid = null;
            markDirty();
            worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
            return false;
        }
        if (chartFluid != null) {
            clearAEData(getAEKey(chartFluid));
        }
        if (chartItem != null) {
            clearAEData(getAEKey(chartItem));
        }
        chartFluid = fluid != null ? fluid.copy() : null;
        chartItem = null;
        markDirty();
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
        return true;
    }

    public ItemStack getChartItem() {
        return chartItem;
    }

    public FluidStack getChartFluid() {
        return chartFluid;
    }

    /** 清除走势图所有绑定 */
    public void clearAEBinding() {
        if (chartItem != null) {
            clearAEData(getAEKey(chartItem));
        }
        if (chartFluid != null) {
            clearAEData(getAEKey(chartFluid));
        }
        chartItem = null;
        chartFluid = null;
        markDirty();
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
        // O2-20：解绑后立即推送空走势段清客户端显示（周期推送已被空屏门控跳过）
        sendAEMonitorDataToClients();
    }

    /** 一键清除 AE 实时监控列表中的所有物品与流体监控 */
    public void clearAllAEMonitors() {
        for (ItemStack stack : new ArrayList<>(monitoredItems)) {
            clearAEData(getAEKey(stack));
        }
        for (FluidStack fluid : new ArrayList<>(monitoredFluids)) {
            clearAEData(getAEKey(fluid));
        }
        monitoredItems.clear();
        monitoredFluids.clear();
        markDirty();
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
        // O2-20：清空后立即推送空监控段清客户端显示（周期推送已被空屏门控跳过）
        sendAEMonitorDataToClients();
    }

    /**
     * 切换物品监视（添加/移除）。
     *
     * @return true=已添加, false=已移除
     */
    public boolean toggleItemMonitor(ItemStack stack) {
        if (stack == null) return false;
        for (int i = 0; i < monitoredItems.size(); i++) {
            if (ItemStack.areItemStacksEqual(monitoredItems.get(i), stack)) {
                clearAEData(getAEKey(monitoredItems.get(i)));
                monitoredItems.remove(i);
                markDirty();
                worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
                // O2-20：移除后立即推送缩减 key 集清客户端对应显示（周期推送已被空屏门控跳过）
                sendAEMonitorDataToClients();
                return false;
            }
        }
        if (monitoredItems.size() < Config.aeMaxMonitoredItems) {
            monitoredItems.add(stack.copy());
            markDirty();
            worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
            return true;
        }
        return false;
    }

    /** 切换流体监视（添加/移除） */
    public boolean toggleFluidMonitor(FluidStack fluid) {
        if (fluid == null) return false;
        for (int i = 0; i < monitoredFluids.size(); i++) {
            if (monitoredFluids.get(i)
                .getFluid()
                .equals(fluid.getFluid())) {
                clearAEData(getAEKey(monitoredFluids.get(i)));
                monitoredFluids.remove(i);
                markDirty();
                worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
                // O2-20：移除后立即推送缩减 key 集清客户端对应显示（周期推送已被空屏门控跳过）
                sendAEMonitorDataToClients();
                return false;
            }
        }
        if (monitoredFluids.size() < Config.aeMaxMonitoredItems) {
            monitoredFluids.add(fluid.copy());
            markDirty();
            worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
            return true;
        }
        return false;
    }

    public List<ItemStack> getMonitoredItems() {
        return monitoredItems;
    }

    public List<FluidStack> getMonitoredFluids() {
        return monitoredFluids;
    }

    /**
     * 获取客户端 AE 走势图样本缓存（只读）。
     *
     * @return 样本列表的不可读修改视图
     */
    public List<AEMonitorSample> getAEChartSamples() {
        return Collections.unmodifiableList(aeChartSamples);
    }

    /**
     * 获取客户端 AE 实时监控列表最新值缓存（只读）。
     *
     * @return key → 最新样本的不可修改映射
     */
    public Map<String, AEMonitorSample> getAEMonitorLatest() {
        return Collections.unmodifiableMap(aeMonitorLatest);
    }

    /**
     * 客户端接收服务端推送的 AE 监控数据，深拷贝后写入本地缓存。
     *
     * @param chartSamples   走势图样本列表
     * @param monitorLatest  实时监控列表最新值
     * @param monitorAvg300s 实时监控 300s 平均变化率
     */
    public void receiveAEMonitorData(List<AEMonitorSample> chartSamples, Map<String, AEMonitorSample> monitorLatest,
        Map<String, Double> monitorAvg300s) {
        aeChartSamples.clear();
        if (chartSamples != null) {
            aeChartSamples.addAll(chartSamples);
        }
        aeMonitorLatest.clear();
        if (monitorLatest != null) {
            aeMonitorLatest.putAll(monitorLatest);
        }
        aeMonitorAvg300s.clear();
        if (monitorAvg300s != null) {
            aeMonitorAvg300s.putAll(monitorAvg300s);
        }
    }

    /**
     * 获取客户端 AE 实时监控 300s 平均变化率缓存（只读）。
     *
     * @return key → 平均变化率的不可修改映射
     */
    public Map<String, Double> getAEMonitorAvg300s() {
        return Collections.unmodifiableMap(aeMonitorAvg300s);
    }

    public void bindOwner(UUID uuid, String name) {
        if (uuid != null && ownerUUID == null) {
            ownerUUID = uuid;
            ownerName = name == null ? "" : name;
            needsDataRefresh = true;
            invalidateOwnerDataSetCache();
            markDirty();
        }
    }

    /**
     * 获取该信息屏绑定的玩家 UUID。
     *
     * @return ownerUUID，未绑定返回 null
     */
    public UUID getOwnerUUID() {
        return ownerUUID;
    }

    public String getOwnerName() {
        return ownerName;
    }

    /**
     * 获取该信息屏所属玩家的全局数据集（统一使用 overworld 数据存储）
     * 仅服务端调用，客户端返回 null
     * <p>
     * O2-28：ownerUUID 绑定不变时数据集引用稳定，缓存解析结果；
     * store 侧 remove/cleanupStale 推进 revision 时缓存失效，重新 getOrCreate
     * 取活引用（cleanup_info_data 命令清理后从空数据集重启的语义不变）。
     *
     * @return NetworkInfoDataSet 或 null
     */
    private NetworkInfoDataSet getOwnerDataSet() {
        if (worldObj == null || worldObj.isRemote || ownerUUID == null) return null;
        if (cachedOwnerDataSet != null && cachedOwnerDataStore != null
            && cachedOwnerDataSetRevision == cachedOwnerDataStore.getRevision()) {
            return cachedOwnerDataSet;
        }
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) return null;
        World overworld = server.worldServerForDimension(0);
        if (overworld == null) return null;
        cachedOwnerDataStore = NetworkInfoDataStore.get(overworld);
        cachedOwnerDataSet = cachedOwnerDataStore.getOrCreate(ownerUUID.toString());
        cachedOwnerDataSetRevision = cachedOwnerDataStore.getRevision();
        return cachedOwnerDataSet;
    }

    /** 失效 O2-28 的 owner 数据集解析缓存（ownerUUID 变化点调用）。 */
    private void invalidateOwnerDataSetCache() {
        cachedOwnerDataStore = null;
        cachedOwnerDataSet = null;
        cachedOwnerDataSetRevision = -1;
    }

    public BigInteger getCachedEu() {
        return cachedEu;
    }

    public String getCachedStatus() {
        return cachedStatus;
    }

    public List<NetworkInfoSample> getCachedSamples() {
        return cachedSamples;
    }

    public NetworkScreen getScreen() {
        return structure.getScreen();
    }

    public boolean isShowBriefEnergy() {
        return store.isShowBriefEnergy();
    }

    public boolean isShowBriefStatus() {
        return store.isShowBriefStatus();
    }

    public boolean isShowChartEnergy() {
        return store.isShowChartEnergy();
    }

    public boolean isShowChartStatus() {
        return store.isShowChartStatus();
    }

    public int getBriefRatio() {
        return store.getBriefRatio();
    }

    public float getBriefFontScale() {
        return store.getBriefFontScale();
    }

    public int getDisplayMode() {
        return store.getDisplayMode();
    }

    public void setDisplayMode(int mode) {
        store.setDisplayMode(mode);
    }

    public String getEnergyAxisMinText() {
        return store.getEnergyAxisMinText();
    }

    public String getEnergyAxisMaxText() {
        return store.getEnergyAxisMaxText();
    }

    public String getEutAxisMinText() {
        return store.getEutAxisMinText();
    }

    public String getEutAxisMaxText() {
        return store.getEutAxisMaxText();
    }

    public int getChartBorderThickness() {
        return store.getChartBorderThickness();
    }

    public String getChartBackgroundColorText() {
        return store.getChartBackgroundColorText();
    }

    public int getTrendLineThickness() {
        return store.getTrendLineThickness();
    }

    public int getTrendLineSmoothing() {
        return store.getTrendLineSmoothing();
    }

    public String getScreenBackgroundColorText() {
        return store.getScreenBackgroundColorText();
    }

    public Double getEnergyAxisMin() {
        return store.getEnergyAxisMin();
    }

    public Double getEnergyAxisMax() {
        return store.getEnergyAxisMax();
    }

    public Double getEutAxisMin() {
        return store.getEutAxisMin();
    }

    public Double getEutAxisMax() {
        return store.getEutAxisMax();
    }

    public Integer getChartBackgroundColor() {
        return store.getChartBackgroundColor();
    }

    public boolean hasScreenBackgroundColor() {
        return store.hasScreenBackgroundColor();
    }

    public int getScreenBackgroundColor() {
        return store.getScreenBackgroundColor();
    }

    // ==================== AE 图表配置 Getter / Setter（v1.5.4）====================

    public int getAETrackingWindow() {
        return store.getAETrackingWindow();
    }

    public int getAEChartBorderThickness() {
        return store.getAEChartBorderThickness();
    }

    public String getAEChartBackgroundColorText() {
        return store.getAEChartBackgroundColorText();
    }

    public Integer getAEChartBackgroundColor() {
        return store.getAEChartBackgroundColor();
    }

    public int getAETrendLineThickness() {
        return store.getAETrendLineThickness();
    }

    public int getAETrendLineSmoothing() {
        return store.getAETrendLineSmoothing();
    }

    public String getAEAxisMinText() {
        return store.getAEAxisMinText();
    }

    public String getAEAxisMaxText() {
        return store.getAEAxisMaxText();
    }

    public Double getAEAxisMin() {
        return store.getAEAxisMin();
    }

    public Double getAEAxisMax() {
        return store.getAEAxisMax();
    }

    public String getAELineColorText() {
        return store.getAELineColorText();
    }

    public Integer getAELineColor() {
        return store.getAELineColor();
    }

    // === AE 实时监控显示配置 Getter（v1.5.8）===
    public int getAEMonitorFontSize() {
        return store.getAEMonitorFontSize();
    }

    public boolean isAEMonitorBold() {
        return store.isAEMonitorBold();
    }

    public int getAEMonitorRenderMode() {
        return store.getAEMonitorRenderMode();
    }

    public int getAEMonitorIconSize() {
        return store.getAEMonitorIconSize();
    }

    public boolean isShowAEBrief() {
        return store.isShowAEBrief();
    }

    public boolean isShowAEChartAmount() {
        return store.isShowAEChartAmount();
    }

    public boolean isShowAEChartRate() {
        return store.isShowAEChartRate();
    }

    /**
     * E3 门面：AE 图表配置行协议（值变更体归 Store，含末尾 markChanged 回调）。
     */
    public void applyAEChartConfig(String payload) {
        store.applyAEChartConfig(payload);
    }

    /**
     * E3 门面：面板配置开关/步进行为编排——值变更体归 Store；case 4/7/24 的跨域后置动作
     * （刷新走势缓存 / displayMode 切换即时重算状态文本 / AE 立即推送）留 TE（O2-A06），
     * 执行顺序与搬迁前一致：值变更 → 后置动作 → markDirty + markBlockForUpdate。
     */
    public void applyConfigAction(int action) {
        if (!store.applyConfigAction(action)) {
            return;
        }
        if (action == 4) {
            // 刷新走势图缓存，使新窗口立即生效
            refreshCachedSamples();
        } else if (action == 7) {
            // 立即重算 cachedStatus，使 GUI/TESR 即时反映新格式（无需等下次采样）
            NetworkInfoDataSet dataSet = getOwnerDataSet();
            boolean cold = (dataSet == null) || dataSet.isColdStarting();
            cachedStatus = formatStatus(cachedEut, cold, dataSet != null && dataSet.isLongTermSilent());
        } else if (action == 24) {
            // 立即推送新窗口数据给客户端，避免等待下次采样才刷新
            sendAEMonitorDataToClients();
        }
        markDirtyAndSync();
    }

    /**
     * E3 门面：EU 图表配置行协议（值变更体归 Store，含末尾 markChanged 回调）。
     */
    public void applyChartConfig(String payload) {
        store.applyChartConfig(payload);
    }

    /** 值变更副作用出口：Store DirtyListener 与 applyConfigAction 编排共用 */
    private void markDirtyAndSync() {
        markDirty();
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
    }

    /**
     * 读取放置数据（从区块 NBT 或物品 NBT 恢复）
     *
     * <p>
     * 本版本简化：
     * <ul>
     * <li>保留：OwnerUUID、OwnerName、lastAESampleTick、chartConfig</li>
     * <li>删除：lastSampleTick、eutMeasurementHistory（已迁移到全局 NetworkInfoDataSet）</li>
     * <li>旧 NBT 中的 lastSampleTick/eutMeasurementHistory key 被自然忽略</li>
     * </ul>
     */
    public void readPlacementData(NBTTagCompound tag) {
        if (tag == null) return;
        if (tag.hasKey("OwnerUUID")) {
            try {
                ownerUUID = UUID.fromString(tag.getString("OwnerUUID"));
            } catch (IllegalArgumentException e) {
                ownerUUID = null;
            }
            invalidateOwnerDataSetCache();
        }
        ownerName = tag.getString("OwnerName");
        if (tag.hasKey("lastAESampleTick")) {
            lastAESampleTick = tag.getLong("lastAESampleTick");
        }
        store.readPlacement(tag);
        needsDataRefresh = true;
    }

    /**
     * 写入区块 NBT 持久化数据（注意：此方法同时被 writeToNBT 区块持久化和 getDrops 物品掉落使用）
     *
     * <p>
     * 本版本简化：
     * <ul>
     * <li>保留：OwnerUUID、OwnerName、lastAESampleTick、chartConfig（这些是区块 NBT 恢复必需的）</li>
     * <li>删除：lastSampleTick、eutMeasurementHistory（已迁移到全局 NetworkInfoDataSet）</li>
     * </ul>
     */
    public void writePlacementData(NBTTagCompound tag) {
        if (ownerUUID != null) {
            tag.setString("OwnerUUID", ownerUUID.toString());
        }
        tag.setString("OwnerName", ownerName == null ? "" : ownerName);
        tag.setLong("lastAESampleTick", lastAESampleTick);
        store.writePlacement(tag);
    }

    /**
     * 刷新本屏走势图缓存（从全局数据集查询当前 trackingWindow）
     */
    private void refreshCachedSamples() {
        if (worldObj == null || worldObj.isRemote || ownerUUID == null) {
            return;
        }
        NetworkInfoDataSet dataSet = getOwnerDataSet();
        if (dataSet != null) {
            refreshCachedSamples(dataSet);
        }
    }

    /**
     * 从指定数据集刷新本屏走势图缓存
     */
    private void refreshCachedSamples(NetworkInfoDataSet dataSet) {
        cachedSamples.clear();
        cachedSamples.addAll(dataSet.query(store.getTrackingWindow()));
    }

    private String formatStatus(double eut, boolean coldStarting, boolean longTermSilent) {
        if (coldStarting) {
            return tr("gtswn.network_info.status.cold");
        }
        if (longTermSilent) {
            return tr("gtswn.network_info.status.longtermsilent");
        }
        if (Math.abs(eut) < 0.000001D) {
            return tr("gtswn.network_info.status.silent");
        }
        if (Math.abs(eut) < 1.0D) {
            return tr("gtswn.network_info.status.lessthan1");
        }
        String key = eut > 0 ? "gtswn.network_info.status.up" : "gtswn.network_info.status.down";
        // EU/t 数值根据 displayMode 切换常规/科学/千位计数
        String eutText;
        switch (store.getDisplayMode()) {
            case 1:
                eutText = FormatUtil.formatScientificDouble(Math.abs(eut));
                break;
            case 2:
                eutText = FormatUtil.formatMetricDouble(Math.abs(eut), 2);
                break;
            case 0:
            default:
                eutText = FormatUtil.formatNormalDouble(Math.abs(eut));
                break;
        }
        return StatCollector.translateToLocalFormatted(key, eutText, GTTierUtil.formatGTPower(eut));
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }

    public String getWindowName() {
        switch (store.getTrackingWindow()) {
            case NetworkInfoDataSet.WINDOW_1_HOUR:
                return "1h";
            case NetworkInfoDataSet.WINDOW_8_HOUR:
                return "8h";
            case NetworkInfoDataSet.WINDOW_24_HOUR:
                return "24h";
            case NetworkInfoDataSet.WINDOW_7_DAY:
                return "7d";
            case NetworkInfoDataSet.WINDOW_1_MONTH:
                return "1M";
            case NetworkInfoDataSet.WINDOW_3_MONTH:
                return "3M";
            case NetworkInfoDataSet.WINDOW_1_YEAR:
                return "1Y";
            case NetworkInfoDataSet.WINDOW_5_MIN:
            default:
                return "5m";
        }
    }

    /**
     * E2 门面：多方块结构重建（外部调用面：双 Block 类放置/破坏回调；编排头经 structure.rebuild() 直达域对象）。
     */
    public void rebuildScreen() {
        structure.rebuild();
    }

    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        return structure.renderBounds(xCoord, yCoord, zCoord);
    }

    /**
     * E2 门面：破坏/卸载时遍历屏幕范围解除全部 Extender 附着（外部调用面：BlockNetworkInfoPanel）。
     */
    public void detachScreen() {
        structure.detach();
    }

    /**
     * E2 门面：扫描范围内全部信息屏重建（外部调用面：BlockNetworkInfoPanelExtender 放置/破坏回调）。
     */
    public static void rebuildNearbyScreens(World world, int x, int y, int z) {
        ScreenStructure.rebuildNearby(world, x, y, z);
    }

    public static void rebuildNearbyScreens(World world, int x, int y, int z, int range) {
        ScreenStructure.rebuildNearby(world, x, y, z, range);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        readPlacementData(tag);
        store.readFromNBT(tag);
        if (tag.hasKey("screen")) {
            structure.setScreen(NetworkScreen.fromNBT(tag.getCompoundTag("screen")));
        }
        readSyncData(tag);
        // === AE 标签页字段读取 ===
        currentTab = tag.hasKey("currentTab") ? tag.getInteger("currentTab") : 0;
        if (tag.hasKey("chartItem")) {
            chartItem = ItemStack.loadItemStackFromNBT(tag.getCompoundTag("chartItem"));
        } else {
            chartItem = null;
        }
        if (tag.hasKey("chartFluid")) {
            chartFluid = FluidStack.loadFluidStackFromNBT(tag.getCompoundTag("chartFluid"));
        } else {
            chartFluid = null;
        }
        monitoredItems.clear();
        if (tag.hasKey("monitoredItems")) {
            NBTTagList list = tag.getTagList("monitoredItems", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < list.tagCount(); i++) {
                ItemStack s = ItemStack.loadItemStackFromNBT(list.getCompoundTagAt(i));
                if (s != null) monitoredItems.add(s);
            }
        }
        monitoredFluids.clear();
        if (tag.hasKey("monitoredFluids")) {
            NBTTagList list = tag.getTagList("monitoredFluids", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < list.tagCount(); i++) {
                FluidStack f = FluidStack.loadFluidStackFromNBT(list.getCompoundTagAt(i));
                if (f != null) monitoredFluids.add(f);
            }
        }
        if (tag.hasKey("proxy")) {
            if (worldObj != null && !worldObj.isRemote) {
                getProxy().readFromNBT(tag);
            } else {
                pendingProxyNBT = tag.getCompoundTag("proxy");
            }
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        writePlacementData(tag);
        store.writeToNBT(tag);
        if (structure.getScreen() != null) {
            tag.setTag(
                "screen",
                structure.getScreen()
                    .toNBT());
        }
        // B2-09：不再调 writeSyncData(tag)——sync 数据（cachedEu/cachedStatus/samples/aeChartSamples 等）
        // 是 S35 描述包专用快照，readSyncData 读取全守卫，断档后由 needsDataRefresh 冷启动从 WSD 正本重建，
        // 区块 NBT 双写只增体积；writeSyncData/readSyncData 本体与 S35 路径（getDescriptionPacket/onDataPacket）不动
        // === AE 标签页字段写入 ===
        tag.setInteger("currentTab", currentTab);
        if (chartItem != null) {
            tag.setTag("chartItem", chartItem.writeToNBT(new NBTTagCompound()));
        }
        if (chartFluid != null) {
            tag.setTag("chartFluid", chartFluid.writeToNBT(new NBTTagCompound()));
        }
        NBTTagList itemList = new NBTTagList();
        for (ItemStack s : monitoredItems) {
            itemList.appendTag(s.writeToNBT(new NBTTagCompound()));
        }
        tag.setTag("monitoredItems", itemList);
        NBTTagList fluidList = new NBTTagList();
        for (FluidStack f : monitoredFluids) {
            fluidList.appendTag(f.writeToNBT(new NBTTagCompound()));
        }
        tag.setTag("monitoredFluids", fluidList);
        if (gridProxy != null) {
            gridProxy.writeToNBT(tag);
        }
    }

    @Override
    public Packet getDescriptionPacket() {
        NBTTagCompound tag = new NBTTagCompound();
        writeSyncData(tag);
        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 1, tag);
    }

    @Override
    public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
        readSyncData(pkt.func_148857_g());
    }

    private void writeSyncData(NBTTagCompound tag) {
        tag.setString("OwnerName", ownerName == null ? "" : ownerName);
        tag.setString("cachedEu", cachedEu == null ? "0" : cachedEu.toString());
        tag.setDouble("cachedEut", cachedEut);
        tag.setString("cachedStatus", cachedStatus == null ? "" : cachedStatus);
        store.writeSync(tag);
        if (structure.getScreen() != null) {
            tag.setTag(
                "screen",
                structure.getScreen()
                    .toNBT());
        }
        NBTTagList list = new NBTTagList();
        for (NetworkInfoSample sample : cachedSamples) {
            list.appendTag(sample.toNBT());
        }
        tag.setTag("samples", list);
        // === AE 标签页状态同步 ===
        tag.setInteger("currentTab", currentTab);
        if (chartItem != null) {
            tag.setTag("chartItem", chartItem.writeToNBT(new NBTTagCompound()));
        }
        if (chartFluid != null) {
            tag.setTag("chartFluid", chartFluid.writeToNBT(new NBTTagCompound()));
        }
        NBTTagList itemList = new NBTTagList();
        for (ItemStack s : monitoredItems) {
            itemList.appendTag(s.writeToNBT(new NBTTagCompound()));
        }
        tag.setTag("monitoredItems", itemList);
        NBTTagList fluidList = new NBTTagList();
        for (FluidStack f : monitoredFluids) {
            fluidList.appendTag(f.writeToNBT(new NBTTagCompound()));
        }
        tag.setTag("monitoredFluids", fluidList);
        // v1.5.15：同步 aeChartSamples，防止客户端跨维度往返后走势图短暂为空
        if (aeChartSamples != null && !aeChartSamples.isEmpty()) {
            NBTTagList aeChartList = new NBTTagList();
            for (AEMonitorSample s : aeChartSamples) {
                aeChartList.appendTag(s.toNBT());
            }
            tag.setTag("aeChartSamples", aeChartList);
        }
    }

    private void readSyncData(NBTTagCompound tag) {
        if (tag.hasKey("OwnerName")) {
            ownerName = tag.getString("OwnerName");
        }
        if (tag.hasKey("cachedEu")) {
            try {
                cachedEu = new BigInteger(tag.getString("cachedEu"));
            } catch (NumberFormatException e) {
                cachedEu = BigInteger.ZERO;
            }
        }
        cachedEut = tag.getDouble("cachedEut");
        if (tag.hasKey("cachedStatus")) {
            cachedStatus = tag.getString("cachedStatus");
        }
        store.readSync(tag);
        if (tag.hasKey("screen")) {
            structure.setScreen(NetworkScreen.fromNBT(tag.getCompoundTag("screen")));
        }
        cachedSamples.clear();
        NBTTagList list = tag.getTagList("samples", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < list.tagCount(); i++) {
            cachedSamples.add(NetworkInfoSample.fromNBT(list.getCompoundTagAt(i)));
        }
        // === AE 标签页状态读取 ===
        if (tag.hasKey("currentTab")) {
            currentTab = tag.getInteger("currentTab");
        }
        if (tag.hasKey("chartItem")) {
            chartItem = ItemStack.loadItemStackFromNBT(tag.getCompoundTag("chartItem"));
        } else {
            chartItem = null;
        }
        if (tag.hasKey("chartFluid")) {
            chartFluid = FluidStack.loadFluidStackFromNBT(tag.getCompoundTag("chartFluid"));
        } else {
            chartFluid = null;
        }
        monitoredItems.clear();
        if (tag.hasKey("monitoredItems")) {
            NBTTagList itemList = tag.getTagList("monitoredItems", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < itemList.tagCount(); i++) {
                ItemStack s = ItemStack.loadItemStackFromNBT(itemList.getCompoundTagAt(i));
                if (s != null) monitoredItems.add(s);
            }
        }
        monitoredFluids.clear();
        if (tag.hasKey("monitoredFluids")) {
            NBTTagList fluidList = tag.getTagList("monitoredFluids", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < fluidList.tagCount(); i++) {
                FluidStack f = FluidStack.loadFluidStackFromNBT(fluidList.getCompoundTagAt(i));
                if (f != null) monitoredFluids.add(f);
            }
        }
        // v1.5.15：读取 aeChartSamples，与 writeSyncData 对应
        aeChartSamples.clear();
        if (tag.hasKey("aeChartSamples")) {
            NBTTagList aeChartList = tag.getTagList("aeChartSamples", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < aeChartList.tagCount(); i++) {
                aeChartSamples.add(AEMonitorSample.fromNBT(aeChartList.getCompoundTagAt(i)));
            }
        }
    }

}
