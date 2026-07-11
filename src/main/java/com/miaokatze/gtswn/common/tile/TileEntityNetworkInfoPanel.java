package com.miaokatze.gtswn.common.tile;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;

import com.miaokatze.gtswn.common.panel.NetworkInfoDataSet;
import com.miaokatze.gtswn.common.panel.NetworkInfoDataStore;
import com.miaokatze.gtswn.common.panel.NetworkInfoSample;
import com.miaokatze.gtswn.common.panel.NetworkScreen;
import com.miaokatze.gtswn.common.util.EUDataSet;
import com.miaokatze.gtswn.common.util.FormatUtil;
import com.miaokatze.gtswn.common.util.GTTierUtil;

import appeng.api.networking.GridFlags;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.storage.IStackWatcher;
import appeng.api.networking.storage.IStackWatcherHost;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.StorageChannel;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IItemList;
import appeng.api.util.AECableType;
import appeng.api.util.DimensionalCoord;
import appeng.me.GridAccessException;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEItemStack;
import gregtech.common.misc.WirelessNetworkManager;

public class TileEntityNetworkInfoPanel extends TileEntity implements IGridProxyable, IStackWatcherHost {

    private static final long SAMPLE_INTERVAL_TICKS = 100L;
    private static final long MAX_CONTINUOUS_GAP_TICKS = 200L;

    private UUID ownerUUID;
    private String ownerName = "";

    private boolean showBriefEnergy = true;
    private boolean showBriefStatus = true;
    private boolean showChartEnergy = true;
    private boolean showChartStatus = true;
    private int trackingWindow = NetworkInfoDataSet.WINDOW_5_MIN;
    private int briefRatio = 28;
    // 显示模式：0=常规计数，1=科学计数（EU 与 EU/t 均跟随此模式）
    private int displayMode = 0;
    private String energyAxisMin = "";
    private String energyAxisMax = "";
    private String eutAxisMin = "";
    private String eutAxisMax = "";
    private int chartBorderThickness = 3;
    private String chartBackgroundColor = "";
    private int trendLineThickness = 3;
    private int trendLineSmoothing = 0;
    private String screenBackgroundColor = ""; // 默认无背景色，TESR 不绘制背景填充

    private long lastSampleTick = -1L;
    private BigInteger cachedEu = BigInteger.ZERO;
    private double cachedEut = 0.0D;
    private String cachedStatus = "No data";
    private final EUDataSet eutDataSet = new EUDataSet();
    private final List<NetworkInfoSample> cachedSamples = new ArrayList<>();
    private NetworkScreen screen;
    private boolean screenInitialized = false;

    /** AE2 网络代理，懒加载，首次调用 getProxy() 时初始化 */
    private AENetworkProxy gridProxy = null;

    /** 标记 proxy 是否已就绪（onReady 已调用） */
    private boolean aeProxyReady = false;

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

    /** 监视列表上限（v1.5.3 改为 Config 配置） */
    private static final int MAX_MONITORED = 64;

    // === IStackWatcher 框架（v1.5.1 引入，v1.5.2 填充采样）===

    /** AE2 注入的栈监听器，grid 就绪后由 AE2 主动调用 updateWatcher 注入 */
    private IStackWatcher stackWatcher;

    /** 标记 AE 网络栈发生变化（onStackChange 回调设置，updateEntity 检测后清零），volatile 保证跨线程可见性 */
    private volatile boolean aeStackDirty = false;

    @Override
    public void updateEntity() {
        if (worldObj == null) {
            return;
        }
        if (!worldObj.isRemote) {
            if (!aeProxyReady) {
                getProxy().onReady();
                aeProxyReady = true;
            }
            if (!screenInitialized) {
                rebuildScreen();
                screenInitialized = true;
            }
            long tick = worldObj.getTotalWorldTime();
            if (ownerUUID != null && (lastSampleTick < 0L || tick - lastSampleTick >= SAMPLE_INTERVAL_TICKS)) {
                sampleNetwork(tick);
                lastSampleTick = tick;
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
            chartItem = null;
            markDirty();
            configureStackWatcher();
            worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
            return false;
        }
        chartItem = stack != null ? stack.copy() : null;
        chartFluid = null; // 物品与流体互斥
        markDirty();
        configureStackWatcher();
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
        return true;
    }

    /** 绑定走势图流体 */
    public boolean setChartFluid(FluidStack fluid) {
        if (fluid != null && chartFluid != null
            && fluid.getFluid()
                .equals(chartFluid.getFluid())) {
            chartFluid = null;
            markDirty();
            configureStackWatcher();
            worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
            return false;
        }
        chartFluid = fluid != null ? fluid.copy() : null;
        chartItem = null;
        markDirty();
        configureStackWatcher();
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
        chartItem = null;
        chartFluid = null;
        markDirty();
        configureStackWatcher();
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
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
                monitoredItems.remove(i);
                markDirty();
                configureStackWatcher();
                worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
                return false;
            }
        }
        if (monitoredItems.size() < MAX_MONITORED) {
            monitoredItems.add(stack.copy());
            markDirty();
            configureStackWatcher();
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
                monitoredFluids.remove(i);
                markDirty();
                configureStackWatcher();
                worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
                return false;
            }
        }
        if (monitoredFluids.size() < MAX_MONITORED) {
            monitoredFluids.add(fluid.copy());
            markDirty();
            configureStackWatcher();
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

    // ==================== IStackWatcherHost 实现（v1.5.1 框架，v1.5.2 填充采样）====================

    @Override
    public void updateWatcher(IStackWatcher newWatcher) {
        this.stackWatcher = newWatcher;
        configureStackWatcher();
    }

    /**
     * 重新配置栈监听器关注的物品/流体列表。
     * 在 chartItem/chartFluid/monitoredItems/monitoredFluids 变化后调用。
     * stackWatcher 为 null 时（AE 网络未就绪）安全跳过。
     */
    private void configureStackWatcher() {
        if (this.stackWatcher == null) return;
        this.stackWatcher.clear();
        // 走势图绑定的物品/流体
        if (chartItem != null) {
            stackWatcher.add(AEItemStack.create(chartItem));
        }
        if (chartFluid != null) {
            stackWatcher.add(AEFluidStack.create(chartFluid));
        }
        // 实时监控列表
        for (ItemStack s : monitoredItems) {
            stackWatcher.add(AEItemStack.create(s));
        }
        for (FluidStack f : monitoredFluids) {
            stackWatcher.add(AEFluidStack.create(f));
        }
    }

    /**
     * AE2 网络栈变化回调。可能在 AE 网络线程调用。
     * v1.5.1 仅设置 dirty 标记，v1.5.2 在 updateEntity 中检测后执行采样。
     */
    @Override
    public void onStackChange(IItemList o, IAEStack fullStack, IAEStack diffStack, BaseActionSource src,
        StorageChannel chan) {
        this.aeStackDirty = true;
    }

    /** 检查并消费 AE 栈变化标记（v1.5.2 在 updateEntity 中调用） */
    public boolean consumeAEStackDirty() {
        if (aeStackDirty) {
            aeStackDirty = false;
            return true;
        }
        return false;
    }

    public void bindOwner(UUID uuid, String name) {
        if (uuid != null && ownerUUID == null) {
            ownerUUID = uuid;
            ownerName = name == null ? "" : name;
            markDirty();
        }
    }

    public UUID getOwnerUUID() {
        return ownerUUID;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public BigInteger getCachedEu() {
        return cachedEu;
    }

    public double getCachedEut() {
        return cachedEut;
    }

    public String getCachedStatus() {
        return cachedStatus;
    }

    public List<NetworkInfoSample> getCachedSamples() {
        return cachedSamples;
    }

    public NetworkScreen getScreen() {
        return screen;
    }

    public boolean isShowBriefEnergy() {
        return showBriefEnergy;
    }

    public boolean isShowBriefStatus() {
        return showBriefStatus;
    }

    public boolean isShowChartEnergy() {
        return showChartEnergy;
    }

    public boolean isShowChartStatus() {
        return showChartStatus;
    }

    public int getTrackingWindow() {
        return trackingWindow;
    }

    public int getBriefRatio() {
        return briefRatio;
    }

    public float getBriefFontScale() {
        return Math.max(10, Math.min(80, briefRatio)) / 20.0F;
    }

    public int getDisplayMode() {
        return displayMode;
    }

    public void setDisplayMode(int mode) {
        this.displayMode = (mode == 1) ? 1 : 0;
    }

    public String getEnergyAxisMinText() {
        return energyAxisMin;
    }

    public String getEnergyAxisMaxText() {
        return energyAxisMax;
    }

    public String getEutAxisMinText() {
        return eutAxisMin;
    }

    public String getEutAxisMaxText() {
        return eutAxisMax;
    }

    public int getChartBorderThickness() {
        return chartBorderThickness;
    }

    public String getChartBackgroundColorText() {
        return chartBackgroundColor;
    }

    public int getTrendLineThickness() {
        return trendLineThickness;
    }

    public int getTrendLineSmoothing() {
        return trendLineSmoothing;
    }

    public String getScreenBackgroundColorText() {
        return screenBackgroundColor;
    }

    public Double getEnergyAxisMin() {
        return parseOptionalDouble(energyAxisMin);
    }

    public Double getEnergyAxisMax() {
        return parseOptionalDouble(energyAxisMax);
    }

    public Double getEutAxisMin() {
        return parseOptionalDouble(eutAxisMin);
    }

    public Double getEutAxisMax() {
        return parseOptionalDouble(eutAxisMax);
    }

    public Integer getChartBackgroundColor() {
        return parseOptionalColor(chartBackgroundColor);
    }

    public boolean hasScreenBackgroundColor() {
        return parseOptionalColor(screenBackgroundColor) != null;
    }

    public int getScreenBackgroundColor() {
        Integer color = parseOptionalColor(screenBackgroundColor);
        return color == null ? 0xDDE1E4 : color.intValue(); // 防御性兜底：hasScreenBackgroundColor() 为 false 时渲染路径不调用此方法
    }

    public void applyConfigAction(int action) {
        switch (action) {
            case 0:
                showBriefEnergy = !showBriefEnergy;
                break;
            case 1:
                showBriefStatus = !showBriefStatus;
                break;
            case 2:
                showChartEnergy = !showChartEnergy;
                break;
            case 3:
                showChartStatus = !showChartStatus;
                break;
            case 4:
                trackingWindow = nextTrackingWindow(trackingWindow);
                refreshCachedSamples();
                break;
            case 5:
                briefRatio = Math.max(10, briefRatio - 5);
                break;
            case 6:
                briefRatio = Math.min(80, briefRatio + 5);
                break;
            case 7:
                // 切换显示模式：常规计数(0) ↔ 科学计数(1)，影响 EU 与 EU/t 的格式化
                displayMode = (displayMode == 0) ? 1 : 0;
                // 立即重算 cachedStatus，使 GUI/TESR 即时反映新格式（无需等下次采样）
                cachedStatus = formatStatus(cachedEut, eutDataSet.size() < 2, false);
                break;
            default:
                return;
        }
        markDirty();
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
    }

    public void applyChartConfig(String payload) {
        if (payload == null) {
            return;
        }
        String[] lines = payload.split("\n", -1);
        for (String line : lines) {
            int index = line.indexOf('=');
            if (index <= 0) {
                continue;
            }
            String key = line.substring(0, index);
            String value = cleanText(line.substring(index + 1));
            if ("energyMin".equals(key)) {
                energyAxisMin = value;
            } else if ("energyMax".equals(key)) {
                energyAxisMax = value;
            } else if ("eutMin".equals(key)) {
                eutAxisMin = value;
            } else if ("eutMax".equals(key)) {
                eutAxisMax = value;
            } else if ("border".equals(key)) {
                chartBorderThickness = clampInt(value, chartBorderThickness, 1, 8);
            } else if ("chartBg".equals(key)) {
                chartBackgroundColor = cleanColorText(value);
            } else if ("line".equals(key)) {
                trendLineThickness = clampInt(value, trendLineThickness, 1, 8);
            } else if ("smoothing".equals(key)) {
                trendLineSmoothing = clampInt(value, trendLineSmoothing, 0, 12);
            } else if ("screenColor".equals(key)) {
                screenBackgroundColor = cleanColorText(value);
            }
        }
        markDirty();
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
    }

    public void readPlacementData(NBTTagCompound tag) {
        // 旧版本曾有 "DatasetId" 键（每屏独立数据集），现改为按 ownerUUID 共享数据集，
        // 旧数据无法适配新机制 → 直接丢弃，不再读取
        if (tag.hasKey("OwnerUUID")) {
            try {
                ownerUUID = UUID.fromString(tag.getString("OwnerUUID"));
            } catch (IllegalArgumentException e) {
                ownerUUID = null;
            }
        }
        if (tag.hasKey("OwnerName")) {
            ownerName = tag.getString("OwnerName");
        }
        if (tag.hasKey("lastSampleTick")) {
            lastSampleTick = tag.getLong("lastSampleTick");
        }
        eutDataSet.loadFromNBT(tag, "eutMeasurementHistory");
        readChartConfig(tag);
    }

    public void writePlacementData(NBTTagCompound tag) {
        if (ownerUUID != null) {
            tag.setString("OwnerUUID", ownerUUID.toString());
        }
        tag.setString("OwnerName", ownerName == null ? "" : ownerName);
        tag.setLong("lastSampleTick", lastSampleTick);
        eutDataSet.saveToNBT(tag, "eutMeasurementHistory");
        writeChartConfig(tag);
    }

    private void sampleNetwork(long tick) {
        if (ownerUUID == null) {
            return;
        }
        NetworkInfoDataStore store = NetworkInfoDataStore.get(worldObj);
        // 数据集 key 从 datasetId 改为 ownerUUID.toString()，同一玩家的所有信息屏共享同一份数据
        NetworkInfoDataSet dataSet = store.getOrCreate(ownerUUID.toString());

        // 全局采样锁：多屏共享时，距离上次采样 < 100 ticks 则跳过本屏采样
        if (!dataSet.tryAcquireSampleLock(tick)) {
            // 即使跳过采样，也要用数据集最新点反写 cachedEu/cachedEut，保证多屏显示一致
            NetworkInfoSample newest = dataSet.newest();
            if (newest != null) {
                cachedEu = newest.eu;
                cachedEut = newest.eut;
            }
            return;
        }

        BigInteger eu = WirelessNetworkManager.getUserEU(ownerUUID);
        cachedEu = eu == null ? BigInteger.ZERO : eu;

        // eutDataSet 仍每屏独立（实时 EU/t 显示用，不变）
        boolean coldStarting = eutDataSet.isEmpty();
        if (lastSampleTick > 0L && tick - lastSampleTick > MAX_CONTINUOUS_GAP_TICKS) {
            eutDataSet.clear();
            coldStarting = true;
        }
        eutDataSet.add(cachedEu, tick);
        cachedEut = eutDataSet.calculateRecentEUT();

        long nowMs = System.currentTimeMillis();
        dataSet.addSample(cachedEu, tick, nowMs, cachedEut);
        store.markDirty();

        // 反写 newest() 确保多屏一致（用数据集实际存储的值，而非本屏局部计算值）
        NetworkInfoSample newest = dataSet.newest();
        if (newest != null) {
            cachedEu = newest.eu;
            cachedEut = newest.eut;
        }

        cachedStatus = formatStatus(cachedEut, coldStarting || eutDataSet.size() < 2, false);
        lastSampleTick = tick; // 保留：用于本 TE 的 gap 检测
        refreshCachedSamples(dataSet);
        markDirty();
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
    }

    private void refreshCachedSamples() {
        if (worldObj == null || worldObj.isRemote || ownerUUID == null) {
            return;
        }
        refreshCachedSamples(
            NetworkInfoDataStore.get(worldObj)
                .getOrCreate(ownerUUID.toString()));
    }

    private void refreshCachedSamples(NetworkInfoDataSet dataSet) {
        cachedSamples.clear();
        cachedSamples.addAll(dataSet.query(trackingWindow));
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
        // EU/t 数值根据 displayMode 切换常规/科学计数
        String eutText = displayMode == 0 ? FormatUtil.formatNormalDouble(Math.abs(eut))
            : FormatUtil.formatScientificDouble(Math.abs(eut));
        return StatCollector.translateToLocalFormatted(key, eutText, GTTierUtil.formatGTPower(eut));
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }

    public String getWindowName() {
        switch (trackingWindow) {
            case NetworkInfoDataSet.WINDOW_1_HOUR:
                return "1h";
            case NetworkInfoDataSet.WINDOW_8_HOUR:
                return "8h";
            case NetworkInfoDataSet.WINDOW_24_HOUR:
                return "24h";
            case NetworkInfoDataSet.WINDOW_5_MIN:
            default:
                return "5m";
        }
    }

    private static int nextTrackingWindow(int window) {
        switch (window) {
            case NetworkInfoDataSet.WINDOW_5_MIN:
                return NetworkInfoDataSet.WINDOW_1_HOUR;
            case NetworkInfoDataSet.WINDOW_1_HOUR:
                return NetworkInfoDataSet.WINDOW_8_HOUR;
            case NetworkInfoDataSet.WINDOW_8_HOUR:
                return NetworkInfoDataSet.WINDOW_24_HOUR;
            case NetworkInfoDataSet.WINDOW_24_HOUR:
            default:
                return NetworkInfoDataSet.WINDOW_5_MIN;
        }
    }

    public void rebuildScreen() {
        int facing = getBlockMetadata();
        if (facing < 2 || facing > 5) {
            facing = 3;
        }

        Set<String> visited = new HashSet<>();
        // v1.4.6：新增 screenParts 只记录兼容的屏幕方块（主屏+Extender），用于后续矩形识别与 Extender 遍历
        // 修复 bug：原 BFS 把空气方块也加入 visited，污染 findLargestFilledRect 的 occupied 集合，
        // 导致算法返回包含空气行的更大矩形（如 3x3 错误扩展为 5x3）
        Set<String> screenParts = new HashSet<>();
        Queue<int[]> queue = new ArrayDeque<>();
        queue.add(new int[] { xCoord, yCoord, zCoord });
        int minX = xCoord;
        int maxX = xCoord;
        int minY = yCoord;
        int maxY = yCoord;
        int minZ = zCoord;
        int maxZ = zCoord;

        while (!queue.isEmpty()) {
            int[] pos = queue.remove();
            String key = key(pos[0], pos[1], pos[2]);
            if (!visited.add(key)) {
                continue;
            }
            TileEntity tile = worldObj.getTileEntity(pos[0], pos[1], pos[2]);
            if (!isCompatibleScreenPart(tile, facing)) {
                continue;
            }
            screenParts.add(key); // v1.4.6：仅兼容方块才记录到 screenParts，避免空气方块污染矩形识别
            minX = Math.min(minX, pos[0]);
            maxX = Math.max(maxX, pos[0]);
            minY = Math.min(minY, pos[1]);
            maxY = Math.max(maxY, pos[1]);
            minZ = Math.min(minZ, pos[2]);
            maxZ = Math.max(maxZ, pos[2]);
            addPlaneNeighbors(queue, pos[0], pos[1], pos[2], facing);
        }

        // v1.4.5：改为识别"完全填满的子矩形"，而非整个包围盒
        // v1.4.6：在 screenParts（仅兼容方块）中找出包含 core 位置的最大填满子矩形
        int[] rect = findLargestFilledRect(screenParts, facing, xCoord, yCoord, zCoord); // v1.4.6：传 screenParts 而非
                                                                                         // visited，确保只识别真实屏幕方块
        NetworkScreen next = new NetworkScreen();
        next.minX = rect[0];
        next.minY = rect[1];
        next.minZ = rect[2];
        next.maxX = rect[3];
        next.maxY = rect[4];
        next.maxZ = rect[5];
        next.coreX = xCoord;
        next.coreY = yCoord;
        next.coreZ = zCoord;
        next.facing = facing;
        screen = next;

        // 遍历所有兼容的屏幕方块：子矩形内的 Extender 附着到 core，子矩形外的 Extender 解除附着
        // v1.4.6：用 screenParts 替代 visited，避免遍历到空气等不兼容方块
        for (String key : screenParts) {
            int[] pos = parseKey(key);
            TileEntity tile = worldObj.getTileEntity(pos[0], pos[1], pos[2]);
            if (tile instanceof TileEntityNetworkInfoPanelExtender) {
                TileEntityNetworkInfoPanelExtender extender = (TileEntityNetworkInfoPanelExtender) tile;
                // 判断该 Extender 是否落在最终子矩形内
                boolean inRect = pos[0] >= next.minX && pos[0] <= next.maxX
                    && pos[1] >= next.minY
                    && pos[1] <= next.maxY
                    && pos[2] >= next.minZ
                    && pos[2] <= next.maxZ;
                if (inRect) {
                    extender.attachToCore(this, next);
                } else {
                    // 连通但在子矩形外的 Extender，解除附着避免残留 partOfScreen 状态
                    extender.detachFromCore();
                }
            }
            worldObj.markBlockForUpdate(pos[0], pos[1], pos[2]);
        }
        markDirty();
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
    }

    /**
     * 在已连通的屏幕方块集合中，找出包含 core 位置的、完全被填满的最大子矩形。
     * <p>
     * 算法思路：
     * <ol>
     * <li>将 3D 方块坐标投影到 2D 平面（facing 2/3 时水平轴=X，facing 4/5 时水平轴=Z，垂直轴=Y）</li>
     * <li>枚举垂直行区间 [top, bottom]，增量维护每列是否在该行区间内全部被占用</li>
     * <li>约束：core 的垂直坐标必须在 [top, bottom] 内，且 core 的水平列必须被占用</li>
     * <li>从 core 水平坐标向左右扩展连续被占用的最远边界，计算面积</li>
     * <li>取面积最大的子矩形作为结果</li>
     * </ol>
     * <p>
     * 复杂度 O(rows² × cols)，屏幕规模小（通常 ≤16×16）完全可行。
     *
     * @param screenParts 已连通且兼容的屏幕方块坐标集合（不含空气等不兼容方块，key 格式 "x,y,z"）
     * @param facing      朝向（2/3 为 X 方向展开，4/5 为 Z 方向展开）
     * @param coreX       主屏 X 坐标
     * @param coreY       主屏 Y 坐标
     * @param coreZ       主屏 Z 坐标
     * @return int[6] = {minX, minY, minZ, maxX, maxY, maxZ} 最大填满子矩形的 3D 边界
     */
    private static int[] findLargestFilledRect(Set<String> screenParts, int facing, int coreX, int coreY, int coreZ) {
        // 确定投影轴：facing 2/3 时水平轴=X，facing 4/5 时水平轴=Z；垂直轴始终=Y
        boolean xAxis = (facing == 2 || facing == 3);
        int coreH = xAxis ? coreX : coreZ;
        int coreV = coreY;

        // 收集所有已占用方块的 2D 坐标，并求包围范围
        java.util.Set<Long> occupied = new java.util.HashSet<>();
        int hMin = coreH, hMax = coreH, vMin = coreV, vMax = coreV;
        for (String key : screenParts) {
            int[] pos = parseKey(key);
            int h = xAxis ? pos[0] : pos[2];
            int v = pos[1];
            // 用 (long)h << 32 | (v & 0xFFFFFFFFL) 编码 2D 坐标，避免 Long.signum 问题
            occupied.add(((long) h << 32) | (v & 0xFFFFFFFFL));
            hMin = Math.min(hMin, h);
            hMax = Math.max(hMax, h);
            vMin = Math.min(vMin, v);
            vMax = Math.max(vMax, v);
        }

        int cols = hMax - hMin + 1;
        boolean[] colOk = new boolean[cols];

        int bestArea = 1;
        int bestLeft = coreH, bestRight = coreH, bestTop = coreV, bestBottom = coreV;

        // 枚举行(垂直)区间 [top, bottom]
        for (int top = vMin; top <= vMax; top++) {
            // 每个 top 起始重置列占用状态
            java.util.Arrays.fill(colOk, true);
            for (int bottom = top; bottom <= vMax; bottom++) {
                // 增量更新：bottom 行加入后，列 c 仍为 true 当且仅当 (c, bottom) 被占用
                for (int c = hMin; c <= hMax; c++) {
                    int idx = c - hMin;
                    if (colOk[idx]) {
                        long code = ((long) c << 32) | (bottom & 0xFFFFFFFFL);
                        if (!occupied.contains(code)) {
                            colOk[idx] = false;
                        }
                    }
                }
                // 约束：core 的垂直坐标必须在 [top, bottom] 内
                if (coreV < top || coreV > bottom) {
                    continue;
                }
                // 约束：core 的水平列必须被占用
                if (!colOk[coreH - hMin]) {
                    continue;
                }
                // 从 coreH 向左右扩展连续 true 的最远边界
                int left = coreH;
                while (left - 1 >= hMin && colOk[left - 1 - hMin]) {
                    left--;
                }
                int right = coreH;
                while (right + 1 <= hMax && colOk[right + 1 - hMin]) {
                    right++;
                }
                int area = (bottom - top + 1) * (right - left + 1);
                if (area > bestArea) {
                    bestArea = area;
                    bestLeft = left;
                    bestRight = right;
                    bestTop = top;
                    bestBottom = bottom;
                }
            }
        }

        // 映射回 3D 边界
        if (xAxis) {
            return new int[] { bestLeft, bestTop, coreZ, bestRight, bestBottom, coreZ };
        } else {
            return new int[] { coreX, bestTop, bestLeft, coreX, bestBottom, bestRight };
        }
    }

    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        if (screen == null) {
            return AxisAlignedBB.getBoundingBox(xCoord, yCoord, zCoord, xCoord + 1.0D, yCoord + 1.0D, zCoord + 1.0D);
        }
        return AxisAlignedBB
            .getBoundingBox(
                screen.minX,
                screen.minY,
                screen.minZ,
                screen.maxX + 1.0D,
                screen.maxY + 1.0D,
                screen.maxZ + 1.0D)
            .expand(0.25D, 0.25D, 0.25D);
    }

    public void detachScreen() {
        if (screen == null || worldObj == null) {
            return;
        }
        for (int x = screen.minX; x <= screen.maxX; x++) {
            for (int y = screen.minY; y <= screen.maxY; y++) {
                for (int z = screen.minZ; z <= screen.maxZ; z++) {
                    TileEntity tile = worldObj.getTileEntity(x, y, z);
                    if (tile instanceof TileEntityNetworkInfoPanelExtender) {
                        ((TileEntityNetworkInfoPanelExtender) tile).detachFromCore();
                    }
                }
            }
        }
    }

    private boolean isCompatibleScreenPart(TileEntity tile, int facing) {
        if (tile instanceof TileEntityNetworkInfoPanel) {
            return tile == this && tile.getBlockMetadata() == facing;
        }
        return tile instanceof TileEntityNetworkInfoPanelExtender && tile.getBlockMetadata() == facing;
    }

    private void addPlaneNeighbors(Queue<int[]> queue, int x, int y, int z, int facing) {
        queue.add(new int[] { x, y + 1, z });
        queue.add(new int[] { x, y - 1, z });
        if (facing == 2 || facing == 3) {
            queue.add(new int[] { x + 1, y, z });
            queue.add(new int[] { x - 1, y, z });
        } else {
            queue.add(new int[] { x, y, z + 1 });
            queue.add(new int[] { x, y, z - 1 });
        }
    }

    public static void rebuildNearbyScreens(World world, int x, int y, int z) {
        for (int dx = -16; dx <= 16; dx++) {
            for (int dy = -16; dy <= 16; dy++) {
                for (int dz = -16; dz <= 16; dz++) {
                    TileEntity tile = world.getTileEntity(x + dx, y + dy, z + dz);
                    if (tile instanceof TileEntityNetworkInfoPanel) {
                        ((TileEntityNetworkInfoPanel) tile).rebuildScreen();
                    }
                }
            }
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        readPlacementData(tag);
        showBriefEnergy = !tag.hasKey("showBriefEnergy") || tag.getBoolean("showBriefEnergy");
        showBriefStatus = !tag.hasKey("showBriefStatus") || tag.getBoolean("showBriefStatus");
        showChartEnergy = !tag.hasKey("showChartEnergy") || tag.getBoolean("showChartEnergy");
        showChartStatus = !tag.hasKey("showChartStatus") || tag.getBoolean("showChartStatus");
        trackingWindow = tag.getInteger("trackingWindow");
        briefRatio = tag.hasKey("briefRatio") ? tag.getInteger("briefRatio") : 28;
        displayMode = tag.hasKey("displayMode") ? tag.getInteger("displayMode") : 0;
        readChartConfig(tag);
        lastSampleTick = tag.getLong("lastSampleTick");
        eutDataSet.loadFromNBT(tag, "eutMeasurementHistory");
        if (tag.hasKey("screen")) {
            screen = NetworkScreen.fromNBT(tag.getCompoundTag("screen"));
        }
        readSyncData(tag);
        // === AE 标签页字段读取 ===
        currentTab = tag.hasKey("currentTab") ? tag.getInteger("currentTab") : 0;
        if (tag.hasKey("chartItem")) {
            chartItem = ItemStack.loadItemStackFromNBT(tag.getCompoundTag("chartItem"));
        }
        if (tag.hasKey("chartFluid")) {
            chartFluid = FluidStack.loadFluidStackFromNBT(tag.getCompoundTag("chartFluid"));
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
        if (tag.hasKey("proxy") && !worldObj.isRemote) {
            getProxy().readFromNBT(tag);
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        writePlacementData(tag);
        tag.setBoolean("showBriefEnergy", showBriefEnergy);
        tag.setBoolean("showBriefStatus", showBriefStatus);
        tag.setBoolean("showChartEnergy", showChartEnergy);
        tag.setBoolean("showChartStatus", showChartStatus);
        tag.setInteger("trackingWindow", trackingWindow);
        tag.setInteger("briefRatio", briefRatio);
        tag.setInteger("displayMode", displayMode);
        writeChartConfig(tag);
        tag.setLong("lastSampleTick", lastSampleTick);
        eutDataSet.saveToNBT(tag, "eutMeasurementHistory");
        if (screen != null) {
            tag.setTag("screen", screen.toNBT());
        }
        writeSyncData(tag);
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
        tag.setBoolean("showBriefEnergy", showBriefEnergy);
        tag.setBoolean("showBriefStatus", showBriefStatus);
        tag.setBoolean("showChartEnergy", showChartEnergy);
        tag.setBoolean("showChartStatus", showChartStatus);
        tag.setInteger("trackingWindow", trackingWindow);
        tag.setInteger("briefRatio", briefRatio);
        tag.setInteger("displayMode", displayMode);
        writeChartConfig(tag);
        if (screen != null) {
            tag.setTag("screen", screen.toNBT());
        }
        NBTTagList list = new NBTTagList();
        for (NetworkInfoSample sample : cachedSamples) {
            list.appendTag(sample.toNBT());
        }
        tag.setTag("samples", list);
        // === AE 标签页状态同步（monitoredItems/Fluids 通过 PacketSyncAEMonitorData 同步，v1.5.2 实现）===
        tag.setInteger("currentTab", currentTab);
        if (chartItem != null) {
            tag.setTag("chartItem", chartItem.writeToNBT(new NBTTagCompound()));
        }
        if (chartFluid != null) {
            tag.setTag("chartFluid", chartFluid.writeToNBT(new NBTTagCompound()));
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
        showBriefEnergy = !tag.hasKey("showBriefEnergy") || tag.getBoolean("showBriefEnergy");
        showBriefStatus = !tag.hasKey("showBriefStatus") || tag.getBoolean("showBriefStatus");
        showChartEnergy = !tag.hasKey("showChartEnergy") || tag.getBoolean("showChartEnergy");
        showChartStatus = !tag.hasKey("showChartStatus") || tag.getBoolean("showChartStatus");
        trackingWindow = tag.getInteger("trackingWindow");
        briefRatio = tag.hasKey("briefRatio") ? tag.getInteger("briefRatio") : 28;
        displayMode = tag.hasKey("displayMode") ? tag.getInteger("displayMode") : 0;
        readChartConfig(tag);
        if (tag.hasKey("screen")) {
            screen = NetworkScreen.fromNBT(tag.getCompoundTag("screen"));
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
        }
        if (tag.hasKey("chartFluid")) {
            chartFluid = FluidStack.loadFluidStackFromNBT(tag.getCompoundTag("chartFluid"));
        }
    }

    private void writeChartConfig(NBTTagCompound tag) {
        tag.setString("energyAxisMin", energyAxisMin);
        tag.setString("energyAxisMax", energyAxisMax);
        tag.setString("eutAxisMin", eutAxisMin);
        tag.setString("eutAxisMax", eutAxisMax);
        tag.setInteger("chartBorderThickness", chartBorderThickness);
        tag.setString("chartBackgroundColor", chartBackgroundColor);
        tag.setInteger("trendLineThickness", trendLineThickness);
        tag.setInteger("trendLineSmoothing", trendLineSmoothing);
        tag.setString("screenBackgroundColor", screenBackgroundColor);
    }

    private void readChartConfig(NBTTagCompound tag) {
        energyAxisMin = tag.hasKey("energyAxisMin") ? tag.getString("energyAxisMin") : "";
        energyAxisMax = tag.hasKey("energyAxisMax") ? tag.getString("energyAxisMax") : "";
        eutAxisMin = tag.hasKey("eutAxisMin") ? tag.getString("eutAxisMin") : "";
        eutAxisMax = tag.hasKey("eutAxisMax") ? tag.getString("eutAxisMax") : "";
        chartBorderThickness = tag.hasKey("chartBorderThickness")
            ? clampInt(tag.getInteger("chartBorderThickness"), 1, 8)
            : 3;
        chartBackgroundColor = tag.hasKey("chartBackgroundColor")
            ? cleanColorText(tag.getString("chartBackgroundColor"))
            : "";
        trendLineThickness = tag.hasKey("trendLineThickness") ? clampInt(tag.getInteger("trendLineThickness"), 1, 8)
            : 3;
        trendLineSmoothing = tag.hasKey("trendLineSmoothing") ? clampInt(tag.getInteger("trendLineSmoothing"), 0, 12)
            : 0;
        screenBackgroundColor = tag.hasKey("screenBackgroundColor")
            ? cleanColorText(tag.getString("screenBackgroundColor"))
            : ""; // 旧存档无此字段时默认空（不绘制背景）
    }

    private static Double parseOptionalDouble(String value) {
        if (value == null || value.trim()
            .isEmpty()) {
            return null;
        }
        try {
            return Double.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseOptionalColor(String value) {
        String clean = cleanColorText(value);
        if (clean.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf((int) Long.parseLong(clean, 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String cleanText(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
            .replace('\r', ' ')
            .replace('\n', ' ');
    }

    private static String cleanColorText(String value) {
        String clean = cleanText(value).replace("#", "");
        if (clean.length() > 6) {
            clean = clean.substring(0, 6);
        }
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return "";
            }
        }
        return clean.toUpperCase();
    }

    private static int clampInt(String value, int fallback, int min, int max) {
        try {
            return clampInt(Integer.parseInt(value), min, max);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    private static int[] parseKey(String key) {
        String[] parts = key.split(",");
        return new int[] { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]) };
    }
}
