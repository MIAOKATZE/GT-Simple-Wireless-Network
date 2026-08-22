package com.miaokatze.gtswn.common.tile;

import java.math.BigInteger;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;

import com.miaokatze.gtswn.common.panel.AEMonitorController;
import com.miaokatze.gtswn.common.panel.AEMonitorSample;
import com.miaokatze.gtswn.common.panel.EUCacheBridge;
import com.miaokatze.gtswn.common.panel.NetworkInfoSample;
import com.miaokatze.gtswn.common.panel.NetworkScreen;
import com.miaokatze.gtswn.common.panel.PanelBroadcastPort;
import com.miaokatze.gtswn.common.panel.PanelConfigStore;
import com.miaokatze.gtswn.common.panel.WindowLabel;
import com.miaokatze.gtswn.common.tile.screen.ScreenStructure;

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

    /**
     * E4（O2-03）：EU 缓存桥域——updateRequestTick/冷启动/轮询三段 + EU/EU-t/状态文本/走势样本缓存
     * + O2-28 owner 数据集解析缓存，方法体逐字搬迁至 {@link EUCacheBridge}；三段顺序不变，
     * S35 四键（cachedEu/cachedEut/cachedStatus/samples）经 bridge.readSync/writeSync 搬运。
     */
    private final EUCacheBridge bridge = new EUCacheBridge(this, store);

    /** AE2 网络代理，懒加载，首次调用 getProxy() 时初始化 */
    private AENetworkProxy gridProxy = null;

    /** 标记 proxy 是否已就绪（onReady 已调用） */
    private boolean aeProxyReady = false;

    /** 区块加载时 worldObj 可能尚未设置，暂存 AE proxy NBT，待世界可用后再恢复 */
    private NBTTagCompound pendingProxyNBT = null;

    /**
     * E5（O2-04）：AE2 gridProxy 只读查询口——proxy 调用权留本类（深查 §三-2 硬约束：
     * 域对象不得持有 getProxy() 调用权），域对象经接口查询。
     */
    private final AEMonitorController.AEQuery aeQuery = new AEMonitorController.AEQuery() {

        @Override
        public boolean isConnected() {
            return isAEConnected();
        }

        @Override
        public long itemAmount(ItemStack stack) {
            return getAEItemAmount(stack);
        }

        @Override
        public long fluidAmount(FluidStack fluid) {
            return getAEFluidAmount(fluid);
        }
    };

    /**
     * E5（O2-04）：AE 监控网络语义域——采样门控与推送、标签页状态与绑定操作、客户端镜像缓存，
     * 方法体逐字搬迁至 {@link AEMonitorController}；本类保留操作族门面单行委托（网络包/
     * 双 Block/ClientProxy/Render/Gui 调用面零改动），区块 NBT 与 S35 的 AE 段键名逐字不动。
     */
    private final AEMonitorController controller = new AEMonitorController(
        this,
        aeQuery,
        store,
        this::markDirtyAndSync);

    /**
     * E5：C 域推送出口——广播端口静态单例转发（broadcastPort/setBroadcastPort 留本类，
     * CommonProxy.init 注入面不变；null 守卫在此收口，域对象不感知端口单例）。
     */
    public void broadcastAEMonitorData(World world, int x, int y, int z, String chartKey,
        List<AEMonitorSample> chartSamples, Map<String, AEMonitorSample> monitorLatest,
        Map<String, Double> monitorAvg300s) {
        if (broadcastPort != null) {
            broadcastPort.broadcastAEMonitorData(world, x, y, z, chartKey, chartSamples, monitorLatest, monitorAvg300s);
        }
    }

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

            // ===== EU 缓存域（E4 迁入 EUCacheBridge）：请求 tick 更新 → 冷启动刷新 → 新数据轮询，三段顺序不变 =====
            bridge.tickServer(overworldTick);

            // ===== AE 监控域（E5 迁入 AEMonitorController）：采样门控 + 采样 + 推送，五段顺序末段不变 =====
            controller.tickSampling(tick);
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

    /** E5 门面：AE 坐标主键（方法体迁入 {@link AEMonitorController#dataKey}；BlockNetworkInfoPanel 调用面） */
    public String getAEMonitorDataKey() {
        return AEMonitorController.dataKey(this);
    }

    /** E5 门面：AE 物品 key（方法体迁入 {@link AEMonitorController#aeKey}；Render/Gui 调用面） */
    public static String getAEKey(ItemStack stack) {
        return AEMonitorController.aeKey(stack);
    }

    /** E5 门面：AE 流体 key（方法体迁入 {@link AEMonitorController#aeKey}；Render/Gui 调用面） */
    public static String getAEKey(FluidStack fluid) {
        return AEMonitorController.aeKey(fluid);
    }

    // ==================== AE 标签页与监视列表操作（E5：方法体迁入 AEMonitorController，门面单行委托）====================

    /** 切换当前标签页 */
    public void setCurrentTab(int tab) {
        controller.setCurrentTab(tab);
    }

    public int getCurrentTab() {
        return controller.getCurrentTab();
    }

    /**
     * 绑定走势图物品。传入与当前绑定相同物品则清除（再次右键清除）。
     *
     * @return true=新绑定, false=清除绑定
     */
    public boolean setChartItem(ItemStack stack) {
        return controller.setChartItem(stack);
    }

    /** 绑定走势图流体 */
    public boolean setChartFluid(FluidStack fluid) {
        return controller.setChartFluid(fluid);
    }

    public ItemStack getChartItem() {
        return controller.getChartItem();
    }

    public FluidStack getChartFluid() {
        return controller.getChartFluid();
    }

    /** 清除走势图所有绑定 */
    public void clearAEBinding() {
        controller.clearAEBinding();
    }

    /** 一键清除 AE 实时监控列表中的所有物品与流体监控 */
    public void clearAllAEMonitors() {
        controller.clearAllAEMonitors();
    }

    /**
     * 切换物品监视（添加/移除）。
     *
     * @return true=已添加, false=已移除
     */
    public boolean toggleItemMonitor(ItemStack stack) {
        return controller.toggleItemMonitor(stack);
    }

    /** 切换流体监视（添加/移除） */
    public boolean toggleFluidMonitor(FluidStack fluid) {
        return controller.toggleFluidMonitor(fluid);
    }

    public List<ItemStack> getMonitoredItems() {
        return controller.getMonitoredItems();
    }

    public List<FluidStack> getMonitoredFluids() {
        return controller.getMonitoredFluids();
    }

    /**
     * 获取客户端 AE 走势图样本缓存（只读）。
     *
     * @return 样本列表的不可读修改视图
     */
    public List<AEMonitorSample> getAEChartSamples() {
        return controller.getAEChartSamples();
    }

    /**
     * 获取客户端 AE 实时监控列表最新值缓存（只读）。
     *
     * @return key → 最新样本的不可修改映射
     */
    public Map<String, AEMonitorSample> getAEMonitorLatest() {
        return controller.getAEMonitorLatest();
    }

    /**
     * 客户端接收服务端推送的 AE 监控数据，深拷贝后写入本地缓存（ClientProxy 调用面）。
     *
     * @param chartSamples   走势图样本列表
     * @param monitorLatest  实时监控列表最新值
     * @param monitorAvg300s 实时监控 300s 平均变化率
     */
    public void receiveAEMonitorData(List<AEMonitorSample> chartSamples, Map<String, AEMonitorSample> monitorLatest,
        Map<String, Double> monitorAvg300s) {
        controller.receiveAEMonitorData(chartSamples, monitorLatest, monitorAvg300s);
    }

    /**
     * 获取客户端 AE 实时监控 300s 平均变化率缓存（只读）。
     *
     * @return key → 平均变化率的不可修改映射
     */
    public Map<String, Double> getAEMonitorAvg300s() {
        return controller.getAEMonitorAvg300s();
    }

    public void bindOwner(UUID uuid, String name) {
        if (uuid != null && ownerUUID == null) {
            ownerUUID = uuid;
            ownerName = name == null ? "" : name;
            bridge.onOwnerBound();
            bridge.markDataRefreshNeeded();
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
     * E4 门面：owner→全局数据集解析与 O2-28 缓存已迁入 {@link EUCacheBridge}（getOwnerDataSet 私有化）。
     */

    /** E4 门面：EU 缓存桥数据源（外部 12 文件调用面零改动） */
    public BigInteger getCachedEu() {
        return bridge.getCachedEu();
    }

    public String getCachedStatus() {
        return bridge.getCachedStatus();
    }

    public List<NetworkInfoSample> getCachedSamples() {
        return bridge.getCachedSamples();
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
            bridge.refreshChartCache();
        } else if (action == 7) {
            // 立即重算 cachedStatus，使 GUI/TESR 即时反映新格式（无需等下次采样）
            bridge.refreshStatusText();
        } else if (action == 24) {
            // 立即推送新窗口数据给客户端，避免等待下次采样才刷新
            controller.pushNow();
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
            bridge.onOwnerBound();
        }
        ownerName = tag.getString("OwnerName");
        if (tag.hasKey("lastAESampleTick")) {
            controller.setLastAESampleTick(tag.getLong("lastAESampleTick"));
        }
        store.readPlacement(tag);
        bridge.markDataRefreshNeeded();
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
        tag.setLong("lastAESampleTick", controller.getLastAESampleTick());
        store.writePlacement(tag);
    }

    /**
     * E4 窗口枚举化：短名映射迁入 {@link WindowLabel}（原 8 分支 switch 语义逐字等价，
     * 越界值回落 "5m" 与原 default 分支一致）。
     */
    public String getWindowName() {
        return WindowLabel.of(store.getTrackingWindow())
            .shortName();
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
        // === AE 标签页字段读取（E5 迁入 AEMonitorController.readTabState）===
        controller.readTabState(tag);
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
        // === AE 标签页字段写入（E5 迁入 AEMonitorController.writeTabState）===
        controller.writeTabState(tag);
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
        bridge.writeSync(tag);
        store.writeSync(tag);
        if (structure.getScreen() != null) {
            tag.setTag(
                "screen",
                structure.getScreen()
                    .toNBT());
        }
        // === AE 标签页状态同步（E5 迁入 AEMonitorController.writeSync）===
        controller.writeSync(tag);
    }

    private void readSyncData(NBTTagCompound tag) {
        if (tag.hasKey("OwnerName")) {
            ownerName = tag.getString("OwnerName");
        }
        bridge.readSync(tag);
        store.readSync(tag);
        if (tag.hasKey("screen")) {
            structure.setScreen(NetworkScreen.fromNBT(tag.getCompoundTag("screen")));
        }
        // === AE 标签页状态读取（E5 迁入 AEMonitorController.readSync）===
        controller.readSync(tag);
    }

}
