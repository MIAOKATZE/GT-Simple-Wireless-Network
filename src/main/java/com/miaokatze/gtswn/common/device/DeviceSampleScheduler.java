package com.miaokatze.gtswn.common.device;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.fluids.FluidStack;

import com.miaokatze.gtswn.config.Config;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IBasicEnergyContainer;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.interfaces.tileentity.IMachineProgress;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.metatileentity.implementations.MTEExtendedPowerMultiBlockBase;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;

/**
 * 设备信息终端采样调度器（实施计划 C1，注册于 CommonProxy.init 的 FML 总线）。
 * <p>
 * ServerTickEvent（END 相）两件事：
 * <ol>
 * <li>每 tick：排空设备终端数据请求队列（包 8 → {@link DeviceTerminalRequestQueue}，
 * Netty 线程入队 → 主线程装配分页回包，仿量子 {@code QuantumTerminalRequestQueue} 模式）</li>
 * <li>每 {@link Config#deviceSampleIntervalSeconds}×20t（读时钳 ≥20t）：把全部<b>活跃终端</b>
 * （{@code DeviceTerminalDataStore.isTerminalActive}，在线玩家持有）的绑定键去重后
 * 入工作队列；每 tick 预算 ≤100 台排水逐键采样</li>
 * </ol>
 * 单键采样流程：
 * <ul>
 * <li>解析 {@code dim:x:y:z}（{@link KeyFormat#parse} 带结果缓存，坏键同样缓存不重复 split）
 * → 维度不存在 / 区块未加载（{@code chunkExists} 为 false）
 * → 跳过保留旧值（不强加载区块）</li>
 * <li>区块已加载但 TE 不满足 D1 判别式（{@link DeviceMachineTypes#isWorkingMachine}）
 * → <b>自愈</b>：登记表出册 + {@link DeviceTerminalDataStore#removeKeyFromAll} 级联解绑
 * （version++ 由 store 内部保证），下一台继续</li>
 * <li>有效机器 → 读三态（停机/运行/待机统一口径，isAllowedToWork/isActive 经基座
 * BaseMetaTileEntity 委托）、EU 方向统一采集（{@link #readEuFlow}：基座 getter 读双 5-tick
 * 网络流量均值，多方块叠加双路 hatch 聚合并按引用去重，仅多方块且枚举失败且控制器双均值为 0
 * 时按 mEUt/lEUt 符号兜底；in/out=实际网络流量，非运行态三通道全写 0）、
 * 功率分类（发电谓词刷新 powerType 0/1）、配方双侧快照
 * （v1.7.2：输入侧因 GT5U 无公开 lastRecipe 入口暂置空，输出侧经公有 mOutputItems/
 * mOutputFluids 采集；仅运行中，非运行置空串）→ 对含该键的所有活跃终端各自 MachineRecord
 * 追加 net/in/out 三通道 FIFO 环形采样点、经 {@link FifoMath} 以三个 runningSum 各自增量推进
 * 60 点均值（O(1) 入加挤出减；会话缓存 + 旧档首载一次性重建；fifo/avg 语义为 net = out − in，
 * 新增 fifoIn/fifoOut/inAvg/outAvg）、
 * 刷新 name/localName → version++</li>
 * </ul>
 * 每轮排水（{@link #drainSamples}）任一终端记录成功写入后，轮末统一 markDirty（每轮至多一次）。
 */
public class DeviceSampleScheduler {

    /** 每 tick 采样排水预算（台） */
    private static final int PER_TICK_BUDGET = 100;

    /** 单侧配方快照字符串总长封顶（字符，输入/输出各自封顶） */
    private static final int RECIPE_STR_CAP = 120;

    /** 单侧配方快照条目数上限（超过 8 项服务端截断，GUI 侧另有 ellipsis 兜底） */
    private static final int RECIPE_SIDE_MAX_ITEMS = 8;

    /** 单项显示名长度封顶（字符，截断后再拼 xN/nL 后缀） */
    private static final int RECIPE_PART_NAME_CAP = 24;

    /** 采样工作队列（去重；仅服务端主线程访问） */
    private final LinkedHashSet<String> workQueue = new LinkedHashSet<>();

    /** 采样间隔计数器（仅主线程） */
    private int intervalCounter = 0;

    /**
     * FIFO 增量均值会话缓存：机器键 →（终端 UUID → 上次推进后的 runningSum 状态）。
     * <p>
     * 仅服务端主线程访问；不落盘（net/in/out 三通道共用一份 (idx, count) 滚动状态，
     * 旧档缺新键按 0 兼容）。首次遇到某 (键, 终端) 或缓存状态与记录当前 (idx, count)
     * 不一致（解绑重绑 / 存档重载
     * 重建了记录）时，用 {@link FifoMath#rebuildSum} 从三条存量 fifo 各一次性重建（最多 60 点）；
     * 自愈解绑时整键清除。正确性不依赖登录 / 登出清理（状态对不上即自愈重建）。
     */
    private final Map<String, Map<UUID, FifoState>> fifoStates = new HashMap<>();

    /** runningSum 会话缓存条目：上次推进后的三通道 (sum, sumIn, sumOut) 与 (idx, count)，须与记录状态一致才可增量推进 */
    private static final class FifoState {

        /** 流入通道（fifoIn）有效样本之和 */
        long sumIn;

        /** 流出通道（fifoOut）有效样本之和 */
        long sumOut;

        /** 上次推进后的写指针 */
        int idx;

        /** 上次推进后的有效样本数 */
        int count;
    }

    /**
     * ServerTickEvent（END 相）：先排空请求队列（每 tick 响应 GUI 轮询），再推进采样。
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return;
        }
        // 主线程排空包 8 请求队列（版本未变则跳过重发，防轮询压力）
        DeviceTerminalRequestQueue.drain();
        World overworld = server.worldServerForDimension(0);
        if (overworld == null) {
            return;
        }
        // 采样间隔（读时钳 ≥20t，防 Config 调 0 引发每 tick 全量采样）
        int intervalTicks = Math.max(20, Config.deviceSampleIntervalSeconds * 20);
        this.intervalCounter++;
        if (this.intervalCounter >= intervalTicks) {
            this.intervalCounter = 0;
            refillWorkQueue(overworld);
        }
        if (!this.workQueue.isEmpty()) {
            drainSamples(server, overworld);
        }
    }

    /** 玩家登出：清理请求队列的已收版本缓存（防跨玩家 UUID 残留累积） */
    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!event.player.worldObj.isRemote) {
            DeviceTerminalRequestQueue.onPlayerLoggedOut(event.player.getUniqueID());
        }
    }

    // ==================== 工作队列填充 / 排水 ====================

    /**
     * 到点填充：全部活跃终端的绑定键去重入队（同键多终端共享只采一次，结果分发到各终端）。
     */
    private void refillWorkQueue(World overworld) {
        DeviceTerminalDataStore store = DeviceTerminalDataStore.get(overworld);
        for (Map.Entry<UUID, DeviceTerminalDataStore.TerminalData> entry : store.getAllTerminals()) {
            if (!DeviceTerminalDataStore.isTerminalActive(entry.getKey())) {
                continue;
            }
            this.workQueue.addAll(entry.getValue().boundKeys);
        }
    }

    /**
     * 每 tick 采样排水（≤{@value #PER_TICK_BUDGET} 台）：逐键解析-判别-采样/自愈。
     */
    private void drainSamples(MinecraftServer server, World overworld) {
        int processed = 0;
        boolean anyRecordWritten = false;
        DeviceTerminalDataStore store = null;
        DeviceRegistryData registry = null;
        // 活跃终端快照（本 tick 内复用：同键分发到含它的所有活跃终端）
        List<Map.Entry<UUID, DeviceTerminalDataStore.TerminalData>> activeTerminals = null;
        java.util.Iterator<String> it = this.workQueue.iterator();
        while (it.hasNext() && processed < PER_TICK_BUDGET) {
            String key = it.next();
            it.remove();
            processed++;
            int[] pos = KeyFormat.parse(key);
            if (pos == null) {
                // 键格式损坏（正常路径不可达，仅存档手改/损坏时）：自愈出册级联解绑
                if (registry == null) {
                    registry = DeviceRegistryData.get(overworld);
                    store = DeviceTerminalDataStore.get(overworld);
                }
                registry.unregister(key);
                store.removeKeyFromAll(key);
                // 各终端该键记录已级联销毁：增量均值会话缓存整键清除
                this.fifoStates.remove(key);
                continue;
            }
            World world = server.worldServerForDimension(pos[0]);
            if (world == null || world.getChunkProvider() == null
                || !world.getChunkProvider()
                    .chunkExists(pos[1] >> 4, pos[3] >> 4)) {
                // 维度未注册 / 区块未加载：跳过保留旧值（不强加载区块）
                continue;
            }
            TileEntity te = world.getTileEntity(pos[1], pos[2], pos[3]);
            IGregTechTileEntity gtTE = te instanceof IGregTechTileEntity ? (IGregTechTileEntity) te : null;
            IMetaTileEntity mte = gtTE != null ? gtTE.getMetaTileEntity() : null;
            if (!DeviceMachineTypes.isWorkingMachine(mte)) {
                // 区块已加载但 TE 不再是可监控机器 → 自愈：出册 + 级联解绑（version++），下一台继续
                if (registry == null) {
                    registry = DeviceRegistryData.get(overworld);
                    store = DeviceTerminalDataStore.get(overworld);
                }
                registry.unregister(key);
                store.removeKeyFromAll(key);
                // 各终端该键记录已级联销毁：增量均值会话缓存整键清除
                this.fifoStates.remove(key);
                continue;
            }
            // 三态经基座委托（BaseMetaTileEntity 实现 IMachineProgress），cast 基座而非 mte
            IMachineProgress progress = (IMachineProgress) gtTE;
            // 三态统一口径：!isAllowedToWork→停机 / isActive→运行 / 其余待机
            int state = !progress.isAllowedToWork() ? DeviceTerminalDataStore.STATE_STOPPED
                : progress.isActive() ? DeviceTerminalDataStore.STATE_RUNNING : DeviceTerminalDataStore.STATE_IDLE;
            // EU 方向统一采集：in/out = 实际网络流量（5-tick 双均值，均 ≥0），net = out − in（可为负）；
            // 非运行态（停机/待机）三通道全写 0（读数源会保留旧配方值，如多方块 mEUt 仅 stopMachine 清零），非 RUNNING 跳过读取
            long euIn = 0L;
            long euOut = 0L;
            if (state == DeviceTerminalDataStore.STATE_RUNNING) {
                long[] flow = readEuFlow(mte, gtTE);
                euIn = flow[0];
                euOut = flow[1];
            }
            long eut = euOut - euIn;
            // 功率分类（0=耗电 / 1=发电），随采样持续刷新（含旧档补齐）
            byte powerType = DeviceMachineTypes.isGeneratorMachine(mte) ? DeviceTerminalDataStore.POWER_TYPE_GENERATE
                : DeviceTerminalDataStore.POWER_TYPE_CONSUME;
            String localName = mte.getLocalName();
            String recipeIn = state == DeviceTerminalDataStore.STATE_RUNNING ? buildRecipeInputSnapshot(mte) : "";
            String recipeOut = state == DeviceTerminalDataStore.STATE_RUNNING ? buildRecipeOutputSnapshot(mte) : "";
            // 分发到含该键的所有活跃终端
            if (activeTerminals == null) {
                DeviceTerminalDataStore dataStore = DeviceTerminalDataStore.get(overworld);
                activeTerminals = new ArrayList<>();
                for (Map.Entry<UUID, DeviceTerminalDataStore.TerminalData> entry : dataStore.getAllTerminals()) {
                    if (DeviceTerminalDataStore.isTerminalActive(entry.getKey())) {
                        activeTerminals.add(entry);
                    }
                }
                store = DeviceTerminalDataStore.get(overworld);
            }
            for (Map.Entry<UUID, DeviceTerminalDataStore.TerminalData> entry : activeTerminals) {
                DeviceTerminalDataStore.TerminalData data = entry.getValue();
                if (!data.boundKeys.contains(key)) {
                    continue;
                }
                DeviceTerminalDataStore.MachineRecord record = data.records.get(key);
                if (record == null) {
                    // 绑定在而记录缺（旧存档）：补建记录
                    record = new DeviceTerminalDataStore.MachineRecord();
                    data.records.put(key, record);
                }
                // 增量均值：输入/输出双通道共用 (idx, count) 滚动；净值由下游派生。
                FifoState window = fifoState(key, entry.getKey(), record);
                long runningSumIn = FifoMath.push(record.fifoIn, record.idx, record.count, window.sumIn, euIn);
                long runningSumOut = FifoMath.push(record.fifoOut, record.idx, record.count, window.sumOut, euOut);
                record.fifoIn[record.idx] = euIn;
                record.fifoOut[record.idx] = euOut;
                record.idx = (record.idx + 1) % DeviceTerminalDataStore.FIFO_SIZE;
                if (record.count < DeviceTerminalDataStore.FIFO_SIZE) {
                    record.count++;
                }
                window.sumIn = runningSumIn;
                window.sumOut = runningSumOut;
                window.idx = record.idx;
                window.count = record.count;
                record.inAvg = FifoMath.avg(runningSumIn, record.count);
                record.outAvg = FifoMath.avg(runningSumOut, record.count);
                record.state = state;
                record.powerType = powerType;
                record.name = localName;
                record.dim = pos[0];
                record.x = pos[1];
                record.y = pos[2];
                record.z = pos[3];
                record.recipeIn = recipeIn;
                record.recipeOut = recipeOut;
                data.version++;
                anyRecordWritten = true;
            }
        }
        if (anyRecordWritten && store != null) {
            // 轮末统一标脏：本轮有任何终端记录成功写入才标（每轮至多一次；version++ 在上方逐终端完成）
            store.markDirty();
        }
    }

    /**
     * 取该 (机器键, 终端) 的 FIFO 增量状态：缓存缺失或与记录当前 (idx, count) 不一致时，
     * 用 {@link FifoMath#rebuildSum} 从三条存量 fifo（net/in/out）各一次性重建
     * （旧档首载最多 60 点求和；旧档缺 fifoIn/fifoOut 时数组全 0，重建和为 0）。
     */
    private FifoState fifoState(String key, UUID terminalId, DeviceTerminalDataStore.MachineRecord record) {
        Map<UUID, FifoState> perKey = this.fifoStates.get(key);
        FifoState window = perKey != null ? perKey.get(terminalId) : null;
        if (window == null || window.idx != record.idx || window.count != record.count) {
            window = new FifoState();
            window.sumIn = FifoMath.rebuildSum(record.fifoIn, record.count);
            window.sumOut = FifoMath.rebuildSum(record.fifoOut, record.count);
            window.idx = record.idx;
            window.count = record.count;
            if (perKey == null) {
                perKey = new HashMap<>();
                this.fifoStates.put(key, perKey);
            }
            perKey.put(terminalId, window);
        }
        return window;
    }

    // ==================== 采样读取工具 ====================

    // ==================== EU 方向统一采集（纯算法核 + 采样接线） ====================

    /**
     * 单个 hatch 采样点：{@code identity} 为 hatch 基座 tile 引用（跨 super 字段与 TT 反射两路
     * 按引用去重，保留首次出现），{@code value} 为该 hatch 在对应方向上的贡献（能量仓 = 平均输入 /
     * 动态仓 = 平均输出，直接取 5-tick 网络流量均值，≥0）。
     */
    public static final class EuFlowSample {

        /** hatch 基座 tile 引用（identity 比较去重） */
        public final Object identity;

        /** 该 hatch 对应方向贡献（EU/t） */
        public final long value;

        public EuFlowSample(Object identity, long value) {
            this.identity = identity;
            this.value = value;
        }
    }

    /**
     * EU 方向统一采集纯算法（零 Minecraft 类加载，单测直测）。方向 = 读哪个 getter，
     * 不按数值符号、不按基类白名单：{@code getAverageElectricInput()} /
     * {@code getAverageElectricOutput()} 仅在 GT5U BaseMetaTileEntity 网络路径
     * （injectEnergyUnits→Input / drainEnergyUnits、handleEUOutput→Output）累加，
     * 语义为<b>实际网络流量</b>——无人取电的发电机 out 可为 0（预期语义）。
     * <ol>
     * <li>基座实现 IBasicEnergyContainer → 控制器双均值即采集起点（单机 / 发电机 / 太阳能 /
     * 避雷针天然走此路，无类别特判，abs 与旧类别分支全部删除）</li>
     * <li>多方块 → in += Σ 能量仓平均输入、out += Σ 动态仓平均输出（两路样本入参，
     * 同一 hatch tile 按引用去重）</li>
     * <li>兜底（三条件缺一不可）：多方块 && 两路均拿不到 hatch 列表（enumerated=false）&&
     * 控制器两均值均为 0 → 按带符号 mEUt/lEUt：&gt;0 记 output、&lt;0 取反为正记 input、=0 双 0</li>
     * </ol>
     *
     * @param hasContainer   tile 是否实现 IBasicEnergyContainer（false 时两控制器读数不参与）
     * @param controllerIn   控制器基座 getAverageElectricInput()
     * @param controllerOut  控制器基座 getAverageElectricOutput()
     * @param isMultiBlock   MTE 是否 MTEMultiBlockBase
     * @param hatchIn        输入方向 hatch 样本（可含两路重复；isMultiBlock=false 时可 null）
     * @param hatchOut       输出方向 hatch 样本（同上）
     * @param enumerated     是否至少一路成功拿到 hatch 列表（true 时禁用符号兜底）
     * @param signedFallback 带符号 mEUt/lEUt（MTEExtendedPowerMultiBlockBase 取 lEUt，否则 mEUt）
     * @return {@code {in, out}}，均 ≥0（in = 网络流入、out = 网络流出，net = out − in 由调用方计算）
     */
    public static long[] collectEuFlow(boolean hasContainer, long controllerIn, long controllerOut,
        boolean isMultiBlock, List<EuFlowSample> hatchIn, List<EuFlowSample> hatchOut, boolean enumerated,
        long signedFallback) {
        long in = hasContainer ? controllerIn : 0L;
        long out = hasContainer ? controllerOut : 0L;
        if (!isMultiBlock) {
            return new long[] { in, out };
        }
        in += sumDedupByIdentity(hatchIn);
        out += sumDedupByIdentity(hatchOut);
        if (!enumerated && controllerIn == 0L && controllerOut == 0L) {
            if (signedFallback > 0L) {
                out += signedFallback;
            } else if (signedFallback < 0L) {
                in += -signedFallback;
            }
        }
        return new long[] { in, out };
    }

    /** hatch 样本按引用去重（保留首次出现）后累加；null / 空列表为 0 */
    private static long sumDedupByIdentity(List<EuFlowSample> samples) {
        if (samples == null || samples.isEmpty()) {
            return 0L;
        }
        long sum = 0L;
        List<Object> seen = new ArrayList<>(samples.size());
        for (EuFlowSample sample : samples) {
            boolean duplicate = false;
            for (Object identity : seen) {
                if (identity == sample.identity) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                seen.add(sample.identity);
                sum += sample.value;
            }
        }
        return sum;
    }

    /**
     * 采样接线（仅 RUNNING 态由调用方进入）：控制器基座读双均值，MTE 为多方块时叠加双路 hatch
     * 聚合，全部交给 {@link #collectEuFlow} 判定。两路 = GT5U MTEMultiBlockBase public 字段
     * {@code mEnergyHatches}/{@code mDynamoHatches} 与 Tectech TTMultiblockBase public 方法
     * {@code getExoticAndNormalEnergyHatchList()}/{@code getExoticDynamoHatches()} 反射
     * （TT 列表含 mEnergyHatches 子集，故跨路同一 hatch 基座按引用去重）。枚举失败判定：
     * public 字段为 null 且两反射方法均拿不到 List（TT 类 / 方法不存在、调用抛错、
     * 返回非 List——一律吞掉，绝不抛穿主采样线程）；空列表一律视为枚举成功，不得触发符号兜底。
     * 禁止编译期 import GoodGenerator/Tectech 类，TT 侧只走反射。
     */
    private static long[] readEuFlow(IMetaTileEntity mte, IGregTechTileEntity gtTE) {
        boolean hasContainer = gtTE instanceof IBasicEnergyContainer;
        long controllerIn = 0L;
        long controllerOut = 0L;
        if (hasContainer) {
            IBasicEnergyContainer container = (IBasicEnergyContainer) gtTE;
            controllerIn = container.getAverageElectricInput();
            controllerOut = container.getAverageElectricOutput();
        }
        if (!(mte instanceof MTEMultiBlockBase)) {
            // 单机 / 发电机 / 太阳能 / 避雷针：getter 即采集，无兜底
            return collectEuFlow(hasContainer, controllerIn, controllerOut, false, null, null, false, 0L);
        }
        MTEMultiBlockBase multi = (MTEMultiBlockBase) mte;
        List<EuFlowSample> hatchIn = new ArrayList<>();
        List<EuFlowSample> hatchOut = new ArrayList<>();
        boolean enumerated = false;
        // 路 A：super public 字段（字段存在即枚举成功；空列表 = 该结构确无此类仓，不是枚举失败）
        if (multi.mEnergyHatches != null) {
            collectHatchSamples(hatchIn, multi.mEnergyHatches, true);
            enumerated = true;
        }
        if (multi.mDynamoHatches != null) {
            collectHatchSamples(hatchOut, multi.mDynamoHatches, false);
            enumerated = true;
        }
        // 路 B：TT 反射（`|` 非短路与：两侧都必须尝试）
        boolean viaTectech = collectExoticHatchSamples(multi, hatchIn, "getExoticAndNormalEnergyHatchList", true)
            | collectExoticHatchSamples(multi, hatchOut, "getExoticDynamoHatches", false);
        // 兜底信号：多方块代际功率字段（扩展电力多方块记 lEUt，其余记 mEUt，均带符号）
        long signed = mte instanceof MTEExtendedPowerMultiBlockBase ? ((MTEExtendedPowerMultiBlockBase) mte).lEUt
            : multi.mEUt;
        return collectEuFlow(
            hasContainer,
            controllerIn,
            controllerOut,
            true,
            hatchIn,
            hatchOut,
            enumerated || viaTectech,
            signed);
    }

    /**
     * 逐个 hatch MTE 经 {@code getBaseMetaTileEntity()} 取基座读对应均值成样本；
     * 基座已失效 / 非能量容器跳过。跨路重复不在此处去重（交给 {@link #collectEuFlow}）。
     */
    private static void collectHatchSamples(List<EuFlowSample> target, List<?> hatches, boolean readInput) {
        for (Object hatch : hatches) {
            if (!(hatch instanceof IMetaTileEntity)) {
                continue;
            }
            IGregTechTileEntity base = ((IMetaTileEntity) hatch).getBaseMetaTileEntity();
            if (!(base instanceof IBasicEnergyContainer)) {
                continue;
            }
            IBasicEnergyContainer container = (IBasicEnergyContainer) base;
            target.add(
                new EuFlowSample(
                    base,
                    readInput ? container.getAverageElectricInput() : container.getAverageElectricOutput()));
        }
    }

    /**
     * 反射调用 TT 多方块 public hatch 列表方法并采集；返回是否拿到 List。
     * 返回空 List 同样算「拿到了列表」（该结构确无此类仓，不是枚举失败）。
     */
    private static boolean collectExoticHatchSamples(MTEMultiBlockBase multi, List<EuFlowSample> target,
        String methodName, boolean readInput) {
        try {
            Object listed = multi.getClass()
                .getMethod(methodName)
                .invoke(multi);
            if (!(listed instanceof List)) {
                return false;
            }
            collectHatchSamples(target, (List<?>) listed, readInput);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // TT 未安装 / 方法不存在 / 调用抛错：本路不可用，按兜底处理，不抛穿
            return false;
        }
    }

    /**
     * 输入侧配方快照（v1.7.2 双侧采集）。
     * <p>
     * 【GT5U 5.09.54.20 公开 API 核实结论】MTEBasicMachine.mLastRecipe 为 protected 字段
     * （MTEBasicMachine.java:139），全 gregtech.api 无公开取 lastRecipe 的入口；
     * MTEMultiBlockBase 在该版本无 lastRecipe 字段；配方类 gregtech.api.util.GTRecipe 亦无
     * getRepresentativeInputs/Outputs 数组方法（仅 per-index getRepresentativeInput(int)）。
     * 输入侧无可用的公开入口，按计划回退：置空串返回（禁止反射私有成员），
     * 待 GT5U 后续开放公开 API 再补双侧完整采样。
     */
    private static String buildRecipeInputSnapshot(IMetaTileEntity mte) {
        return "";
    }

    /**
     * 输出侧快照（近似）：mOutputItems / mOutputFluids 拼「显示名 xN|显示名 nL|...」式描述，
     * display name 取值，单项名封 {@value #RECIPE_PART_NAME_CAP} 字符、单侧条目数封
     * {@value #RECIPE_SIDE_MAX_ITEMS} 项、总长封顶 {@value #RECIPE_STR_CAP} 字符（超限截断）；
     * 空输出返回空串。
     */
    private static String buildRecipeOutputSnapshot(IMetaTileEntity mte) {
        StringBuilder sb = new StringBuilder();
        ItemStack[] outputItems = null;
        FluidStack[] outputFluids = null;
        if (mte instanceof MTEBasicMachine) {
            outputItems = ((MTEBasicMachine) mte).mOutputItems;
        } else if (mte instanceof MTEMultiBlockBase) {
            outputItems = ((MTEMultiBlockBase) mte).mOutputItems;
            outputFluids = ((MTEMultiBlockBase) mte).mOutputFluids;
        }
        int count = 0;
        if (outputItems != null) {
            for (ItemStack stack : outputItems) {
                if (stack == null || stack.getItem() == null || stack.stackSize <= 0) {
                    continue;
                }
                appendPart(sb, capName(stack.getDisplayName()) + " x" + stack.stackSize);
                if (++count >= RECIPE_SIDE_MAX_ITEMS) {
                    return capTotal(sb);
                }
            }
        }
        if (outputFluids != null) {
            for (FluidStack fluid : outputFluids) {
                if (fluid == null || fluid.getFluid() == null || fluid.amount <= 0) {
                    continue;
                }
                appendPart(sb, capName(fluid.getLocalizedName()) + " " + fluid.amount + "L");
                if (++count >= RECIPE_SIDE_MAX_ITEMS) {
                    return capTotal(sb);
                }
            }
        }
        return capTotal(sb);
    }

    /** 追加片段（"|" 分隔，v1.7.2 双侧序列化格式），超条目数上限后由调用方截断停止 */
    private static void appendPart(StringBuilder sb, String part) {
        if (sb.length() > 0) {
            sb.append('|');
        }
        sb.append(part);
    }

    /** 单项显示名截断（null 安全） */
    private static String capName(String name) {
        if (name == null) {
            return "";
        }
        return name.length() > RECIPE_PART_NAME_CAP ? name.substring(0, RECIPE_PART_NAME_CAP) : name;
    }

    /** 单侧总长封顶截断 */
    private static String capTotal(StringBuilder sb) {
        return sb.length() > RECIPE_STR_CAP ? sb.substring(0, RECIPE_STR_CAP) : sb.toString();
    }
}
