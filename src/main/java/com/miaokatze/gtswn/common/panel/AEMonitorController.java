package com.miaokatze.gtswn.common.panel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fluids.FluidStack;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.config.Config;

/**
 * AE 监控网络语义域（O2-04 = E 线 E5，四域拆分第四步）：采样门控与推送、标签页状态与绑定操作、
 * 客户端镜像缓存。
 * <p>
 * 方法体自 {@code TileEntityNetworkInfoPanel} 逐字搬迁（E5 表 C 行段整体平移），零行为变更；
 * core 引用仅用于 world/坐标访问与广播端口出口（E2/E4 同构模式），store 引用供推送读
 * AE 检测窗口（单向依赖 C→D），值变更副作用经构造注入的 DirtyListener（markDirty +
 * markBlockForUpdate）。
 * <p>
 * AE2 gridProxy 只读查询经 {@link AEQuery} 接口注入——proxy 调用权留在 TE（深查 §三-2 硬约束：
 * 域对象不得持有 {@code getProxy()} 调用权）。客户端镜像字段（aeChartSamples/aeMonitorLatest/
 * aeMonitorAvg300s）仅客户端写入（PacketSyncAEMonitorData → ClientProxy → TE 门面 → 此处），
 * 横跨双端为数据内聚优先的既定裁决。序列化键名逐字不动：区块 NBT 的 currentTab/chartItem/
 * chartFluid/monitoredItems/monitoredFluids 与 S35 同键 + aeChartSamples。
 */
public final class AEMonitorController {

    /** AE2 gridProxy 只读查询口——实现由 TE 提供（isAEConnected/getAEItemAmount/getAEFluidAmount） */
    public interface AEQuery {

        boolean isConnected();

        long itemAmount(ItemStack stack);

        long fluidAmount(FluidStack fluid);
    }

    private final TileEntityNetworkInfoPanel core;
    private final AEQuery query;
    private final PanelConfigStore store;
    private final PanelConfigStore.DirtyListener dirty;

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

    public AEMonitorController(TileEntityNetworkInfoPanel core, AEQuery query, PanelConfigStore store,
        PanelConfigStore.DirtyListener dirty) {
        this.core = core;
        this.query = query;
        this.store = store;
        this.dirty = dirty;
    }

    // ==================== 采样与推送（仅服务端）====================

    /**
     * 服务端每 {@code Config.aeSampleInterval} ticks 执行一次 AE 采样并推送给客户端
     * （原 updateEntity AE 门控段逐字搬迁）。
     *
     * @param worldTick 当前世界 tick
     */
    public void tickSampling(long worldTick) {
        if (Config.aeChartEnabled
            && (lastAESampleTick < 0L || worldTick - lastAESampleTick >= Config.aeSampleInterval)) {
            sampleAENetwork(worldTick);
            lastAESampleTick = worldTick;
        }
    }

    /** 服务端将当前 AE 走势图样本与实时监控最新值推送给周围客户端（原 sendAEMonitorDataToClients） */
    public void pushNow() {
        if (core.getWorldObj() == null || core.getWorldObj().isRemote) {
            return;
        }

        // O2-21：无接收者预检——64 格（与 TargetPoint 半径一致）内无玩家时连包体都不组装，
        // 无人区常载屏每采样间隔免一次走势 61 点 + 双 Map 三段集合 build；
        // 视锥级过滤不实施（失步无刷新问题，接收端现存 TE 缺席丢弃已够）
        if (!hasNearbyReceiver()) {
            return;
        }

        String dataKey = dataKey(core);
        if (dataKey == null) {
            return;
        }

        AEMonitorDataSet dataSet = AEMonitorDataStore.get(core.getWorldObj())
            .getIfPresent(dataKey);
        // v1.5.15：无数据可推送时直接返回，避免 getOrCreate 创建空集导致内存泄漏
        if (dataSet == null) {
            return;
        }

        String chartKey = null;
        List<AEMonitorSample> chartSamples = new ArrayList<>();
        if (chartItem != null) {
            chartKey = aeKey(chartItem);
            if (chartKey != null) {
                chartSamples = dataSet.query(chartKey, store.getAETrackingWindow());
            }
        } else if (chartFluid != null) {
            chartKey = aeKey(chartFluid);
            if (chartKey != null) {
                chartSamples = dataSet.query(chartKey, store.getAETrackingWindow());
            }
        }

        Map<String, AEMonitorSample> monitorLatest = new HashMap<>();
        Map<String, Double> monitorAvg300s = new HashMap<>();
        for (ItemStack stack : monitoredItems) {
            String key = aeKey(stack);
            if (key == null) continue;
            AEMonitorSample sample = dataSet.newest(key);
            if (sample != null) {
                monitorLatest.put(key, sample);
            }
            monitorAvg300s.put(key, dataSet.averageRate300s(key));
        }
        for (FluidStack fluid : monitoredFluids) {
            String key = aeKey(fluid);
            if (key == null) continue;
            AEMonitorSample sample = dataSet.newest(key);
            if (sample != null) {
                monitorLatest.put(key, sample);
            }
            monitorAvg300s.put(key, dataSet.averageRate300s(key));
        }

        // B07：经广播端口推送（包体构造与 TargetPoint 由 network 侧端口实现承载，逐字搬迁；
        // null 守卫在 TE 的出口转发内）
        core.broadcastAEMonitorData(
            core.getWorldObj(),
            core.xCoord,
            core.yCoord,
            core.zCoord,
            chartKey,
            chartSamples,
            monitorLatest,
            monitorAvg300s);
    }

    /**
     * O2-21：64 格（与推送 TargetPoint 半径一致）内是否存在玩家接收者。
     * <p>
     * 只查本维度 {@code worldObj.playerEntities}（sendToAllAround 的 TargetPoint 也限定本维度）；
     * 平方距离比较避免开方。
     */
    private boolean hasNearbyReceiver() {
        double sq = 64.0D * 64.0D;
        for (EntityPlayer player : core.getWorldObj().playerEntities) {
            double dx = player.posX - (core.xCoord + 0.5D);
            double dy = player.posY - (core.yCoord + 0.5D);
            double dz = player.posZ - (core.zCoord + 0.5D);
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
     * 不复用 {@link #pushNow()}：该方法在 dataSet == null 时直接返回，
     * 且 dataSet 存在时推送的是 WSD 旧值而非空态，均达不到清显示的目的。
     * 服务端 WSD 历史不动，重连后由 {@link #sampleAENetwork(long)} 重新采样推送恢复；
     * 节律 = 断网期间每 {@code Config.aeSampleInterval} 一次空包，与在线推送同频。
     */
    private void pushAEMonitorOffline() {
        if (core.getWorldObj() == null || core.getWorldObj().isRemote) {
            return;
        }
        // B07：空数据集推送同经广播端口（B2-02 语义不变，客户端 clear+addAll 即清显示）
        core.broadcastAEMonitorData(
            core.getWorldObj(),
            core.xCoord,
            core.yCoord,
            core.zCoord,
            null,
            Collections.<AEMonitorSample>emptyList(),
            Collections.<String, AEMonitorSample>emptyMap(),
            Collections.<String, Double>emptyMap());
    }

    /**
     * 服务端执行 AE 网络采样：对走势图绑定与实时监控列表中的每个物品/流体查询 AE 存量并写入数据集。
     *
     * @param tick 当前世界 tick
     */
    private void sampleAENetwork(long tick) {
        if (!query.isConnected()) {
            // B2-02：断网时改为空推送——清掉客户端走势图/监控缓存，避免 GUI 无限期显示陈旧值；
            // 服务端 WSD 历史不动，重连后由下方采样逻辑恢复
            pushAEMonitorOffline();
            return;
        }

        String dataKey = dataKey(core);
        if (dataKey == null) {
            return;
        }

        AEMonitorDataStore store = AEMonitorDataStore.get(core.getWorldObj());
        AEMonitorDataSet dataSet = store.getOrCreate(dataKey);
        boolean wroteAny = false;
        long timeMs = System.currentTimeMillis();

        // 走势图物品
        if (chartItem != null) {
            String key = aeKey(chartItem);
            if (key != null && dataSet.tryAcquireSampleLock(key, tick, Config.aeSampleInterval)) {
                long amount = query.itemAmount(chartItem);
                dataSet.addSample(key, amount, tick, timeMs);
                wroteAny = true;
            }
        }

        // 走势图流体
        if (chartFluid != null) {
            String key = aeKey(chartFluid);
            if (key != null && dataSet.tryAcquireSampleLock(key, tick, Config.aeSampleInterval)) {
                long amount = query.fluidAmount(chartFluid);
                dataSet.addSample(key, amount, tick, timeMs);
                wroteAny = true;
            }
        }

        // 实时监控物品列表
        for (ItemStack stack : monitoredItems) {
            String key = aeKey(stack);
            if (key != null && dataSet.tryAcquireSampleLock(key, tick, Config.aeSampleInterval)) {
                long amount = query.itemAmount(stack);
                dataSet.addSample(key, amount, tick, timeMs);
                wroteAny = true;
            }
        }

        // 实时监控流体列表
        for (FluidStack fluid : monitoredFluids) {
            String key = aeKey(fluid);
            if (key != null && dataSet.tryAcquireSampleLock(key, tick, Config.aeSampleInterval)) {
                long amount = query.fluidAmount(fluid);
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
            pushNow();
        }
    }

    /**
     * 清空指定 key 在本屏坐标主键下的 AE 采样数据。
     * <p>
     * 仅服务端执行；坐标 key 未初始化或 world 不可用时安全跳过。
     */
    private void clearAEData(String key) {
        if (key == null || core.getWorldObj() == null || core.getWorldObj().isRemote) {
            return;
        }
        String dataKey = dataKey(core);
        if (dataKey == null) {
            return;
        }
        // v1.5.15：改用 getIfPresent 避免为已破坏/卸载的方块创建空数据集导致内存泄漏
        AEMonitorDataStore store = AEMonitorDataStore.get(core.getWorldObj());
        AEMonitorDataSet dataSet = store.getIfPresent(dataKey);
        if (dataSet != null) {
            dataSet.clear(key);
            store.markDirty();
        }
    }

    // ==================== 静态 key 工具（Render/Gui 经 TE 静态门面消费，签名不变）====================

    /**
     * 生成本方块在 AEMonitorDataStore 中使用的坐标主键。
     * <p>
     * 格式：{@code dimensionId:xCoord:yCoord:zCoord}。
     * 该 key 随方块位置唯一确定，避免使用 panelUUID 带来的存档迁移与数据残留问题。
     *
     * @return 坐标字符串；world 不可用时返回 null
     */
    public static String dataKey(TileEntity te) {
        if (te.getWorldObj() == null) {
            return null;
        }
        return te.getWorldObj().provider.dimensionId + ":" + te.xCoord + ":" + te.yCoord + ":" + te.zCoord;
    }

    /**
     * 生成 AE 走势图/实时监控的物品 key。
     *
     * @param stack 物品堆（null 或空返回 null）
     * @return item:&lt;registryName&gt;:&lt;meta&gt;
     */
    public static String aeKey(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return null;
        return "item:" + Item.itemRegistry.getNameForObject(stack.getItem()) + ":" + stack.getItemDamage();
    }

    /**
     * 生成 AE 走势图/实时监控的流体 key。
     *
     * @param fluid 流体堆（null 或空返回 null）
     * @return fluid:&lt;fluidName&gt;
     */
    public static String aeKey(FluidStack fluid) {
        if (fluid == null || fluid.getFluid() == null) return null;
        return "fluid:" + fluid.getFluid()
            .getName();
    }

    // ==================== 标签页与绑定操作（PacketUpdateAETabState 七 actionType 的服务端入口）====================

    /** 切换当前标签页 */
    public void setCurrentTab(int tab) {
        this.currentTab = (tab >= 0 && tab <= 2) ? tab : 0;
        dirty.markChanged();
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
            clearAEData(aeKey(chartItem));
            chartItem = null;
            dirty.markChanged();
            return false;
        }
        if (chartItem != null) {
            clearAEData(aeKey(chartItem));
        }
        if (chartFluid != null) {
            clearAEData(aeKey(chartFluid));
        }
        chartItem = stack != null ? stack.copy() : null;
        chartFluid = null; // 物品与流体互斥
        dirty.markChanged();
        return true;
    }

    /** 绑定走势图流体 */
    public boolean setChartFluid(FluidStack fluid) {
        if (fluid != null && chartFluid != null
            && fluid.getFluid()
                .equals(chartFluid.getFluid())) {
            clearAEData(aeKey(chartFluid));
            chartFluid = null;
            dirty.markChanged();
            return false;
        }
        if (chartFluid != null) {
            clearAEData(aeKey(chartFluid));
        }
        if (chartItem != null) {
            clearAEData(aeKey(chartItem));
        }
        chartFluid = fluid != null ? fluid.copy() : null;
        chartItem = null;
        dirty.markChanged();
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
            clearAEData(aeKey(chartItem));
        }
        if (chartFluid != null) {
            clearAEData(aeKey(chartFluid));
        }
        chartItem = null;
        chartFluid = null;
        dirty.markChanged();
        // O2-20：解绑后立即推送空走势段清客户端显示（周期推送已被空屏门控跳过）
        pushNow();
    }

    /** 一键清除 AE 实时监控列表中的所有物品与流体监控 */
    public void clearAllAEMonitors() {
        for (ItemStack stack : new ArrayList<>(monitoredItems)) {
            clearAEData(aeKey(stack));
        }
        for (FluidStack fluid : new ArrayList<>(monitoredFluids)) {
            clearAEData(aeKey(fluid));
        }
        monitoredItems.clear();
        monitoredFluids.clear();
        dirty.markChanged();
        // O2-20：清空后立即推送空监控段清客户端显示（周期推送已被空屏门控跳过）
        pushNow();
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
                clearAEData(aeKey(monitoredItems.get(i)));
                monitoredItems.remove(i);
                dirty.markChanged();
                // O2-20：移除后立即推送缩减 key 集清客户端对应显示（周期推送已被空屏门控跳过）
                pushNow();
                return false;
            }
        }
        if (monitoredItems.size() < Config.aeMaxMonitoredItems) {
            monitoredItems.add(stack.copy());
            dirty.markChanged();
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
                clearAEData(aeKey(monitoredFluids.get(i)));
                monitoredFluids.remove(i);
                dirty.markChanged();
                // O2-20：移除后立即推送缩减 key 集清客户端对应显示（周期推送已被空屏门控跳过）
                pushNow();
                return false;
            }
        }
        if (monitoredFluids.size() < Config.aeMaxMonitoredItems) {
            monitoredFluids.add(fluid.copy());
            dirty.markChanged();
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

    // ==================== 客户端镜像（字段仅客户端写入）====================

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

    // ==================== 序列化通道（键名逐字不动；装配顺序由 TE 编排）====================

    /** 区块 NBT 标签页状态段写入（原 TE.writeToNBT 的 AE 标签页字段段） */
    public void writeTabState(NBTTagCompound tag) {
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
    }

    /** 区块 NBT 标签页状态段读取（原 TE.readFromNBT 的 AE 标签页字段段） */
    public void readTabState(NBTTagCompound tag) {
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
    }

    /** S35 描述包 AE 状态段写入（原 TE.writeSyncData 的 AE 段：同键 + aeChartSamples） */
    public void writeSync(NBTTagCompound tag) {
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

    /** S35 描述包 AE 状态段读取（原 TE.readSyncData 的 AE 段，与 writeSync 对应） */
    public void readSync(NBTTagCompound tag) {
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
        // v1.5.15：读取 aeChartSamples，与 writeSync 对应
        aeChartSamples.clear();
        if (tag.hasKey("aeChartSamples")) {
            NBTTagList aeChartList = tag.getTagList("aeChartSamples", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < aeChartList.tagCount(); i++) {
                aeChartSamples.add(AEMonitorSample.fromNBT(aeChartList.getCompoundTagAt(i)));
            }
        }
    }

    /** 放置数据 lastAESampleTick 读取委托（readPlacementData 用） */
    public long getLastAESampleTick() {
        return lastAESampleTick;
    }

    /** 放置数据 lastAESampleTick 写入委托（writePlacementData 用） */
    public void setLastAESampleTick(long tick) {
        lastAESampleTick = tick;
    }
}
