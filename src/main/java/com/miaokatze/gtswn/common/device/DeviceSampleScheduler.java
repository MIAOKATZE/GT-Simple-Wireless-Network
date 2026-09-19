package com.miaokatze.gtswn.common.device;

import java.lang.reflect.Field;
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
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IBasicEnergyContainer;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.interfaces.tileentity.IMachineProgress;
import gregtech.api.metatileentity.MetaTileEntity;
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
 * 网络流量均值，多方块叠加双路 hatch 聚合并按引用去重；getter 与 hatch 聚合合计仍双零时进入
 * <b>预选词条/数值链</b>（读到非零即停）：发电词条幅值（方向由 isGeneratorMachine 词条决定，
 * 不由数值符号）→ 多方块长功率符号权威层（{@code MTEExtendedPowerMultiBlockBase.lEUt} 原符号，
 * 正=发电→out / 负=耗电→in；仅「多方块 + 长功率」生效，单机与普通多方块不介入，幅值经
 * {@code tEff} 万分度效率修正）→ 特殊机器权威 provider（{@link DeviceSpecialPowerProvider#readAuthoritativeEut}
 * 符号值，正=发电→out / 负=消耗→in，词条幅值缺失如 LNR 时接管）→ 耗电词条幅值（仅非发电，
 * 无线喂电常规机 |mEUt| 消费腿）；in/out=实际网络流量，非运行态三通道全写 0）、
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

    /** RUNNING 真双零诊断日志限频间隔（tick/键，600t = 30s 每键至多 1 条） */
    private static final long ZERO_FLOW_LOG_INTERVAL_TICKS = 600L;

    /**
     * 万分度效率字段名（GT5U UCFE 家族自持字段，非基类契约：
     * MTEUniversalChemicalFuelEngine.java:76 {@code private long tEff}，字段名在
     * MTEUniversalChemicalFuelEngineLegacy.java:62 同形，故按名反射而非 import）
     */
    private static final String EFFICIENCY_FIELD_NAME = "tEff";

    /** 效率字段沿 {@code getClass()→getSuperclass()} 向上查找的最大层数（防御超深链与畸形继承） */
    private static final int EFFICIENCY_FIELD_MAX_DEPTH = 8;

    /** 万分度基数：GT5U 效率以 10000 = 100% 记账（UCFE {@code addAutoEnergy} :299 即 powerFlow*tEff/10000） */
    private static final long EFFICIENCY_SCALE_BASE = 10000L;

    /** {@link #readEfficiencyScale} 的「无字段/不可读」哨兵；{@link #applyEfficiency} 见此值原样返回不缩放 */
    private static final long NO_EFFICIENCY_SCALE = -1L;

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

    /**
     * RUNNING 真双零诊断日志的按键限频表：机器键 → 上次输出 tick（仅服务端主线程访问，不落盘）。
     * 机器自愈解绑时随 {@link #fifoStates} 一并清除。
     */
    private final Map<String, Long> zeroFlowLogTicks = new HashMap<>();

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
                this.zeroFlowLogTicks.remove(key);
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
                this.zeroFlowLogTicks.remove(key);
                continue;
            }
            // 三态经基座委托（BaseMetaTileEntity 实现 IMachineProgress），cast 基座而非 mte
            IMachineProgress progress = (IMachineProgress) gtTE;
            // 三态统一口径：!isAllowedToWork→停机 / isActive→运行 / 其余待机
            int state = !progress.isAllowedToWork() ? DeviceTerminalDataStore.STATE_STOPPED
                : progress.isActive() ? DeviceTerminalDataStore.STATE_RUNNING : DeviceTerminalDataStore.STATE_IDLE;
            // 发电白名单只推断一次：EU 方向兜底（readEuFlow/collectEuFlow）与功率分类共用同一结果
            boolean isGenerator = DeviceMachineTypes.isGeneratorMachine(mte);
            // EU 方向统一采集：in/out = 实际网络流量（5-tick 双均值，均 ≥0），net = out − in（可为负）；
            // 非运行态（停机/待机）三通道全写 0（读数源会保留旧配方值，如多方块 mEUt 仅 stopMachine 清零），非 RUNNING 跳过读取
            long euIn = 0L;
            long euOut = 0L;
            if (state == DeviceTerminalDataStore.STATE_RUNNING) {
                EuFlowReading reading = readEuFlow(mte, gtTE, isGenerator);
                euIn = reading.in;
                euOut = reading.out;
                // 限频诊断（不改采样结果与控制流）：RUNNING 且含兜底后仍真双零，每键 600t 至多 1 条
                if (euIn == 0L && euOut == 0L) {
                    logZeroFlowDiagnostic(key, mte, reading, overworld.getTotalWorldTime());
                }
            }
            // 功率分类（0=耗电 / 1=发电），随采样持续刷新（含旧档补齐）
            byte powerType = isGenerator ? DeviceTerminalDataStore.POWER_TYPE_GENERATE
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
     * 一次 EU 采集的完整读数：in/out 为最终采集结果（均 ≥0，net = out − in 由调用方计算）；
     * controllerIn/controllerOut 为控制器基座双均值原值，hatchInSamples/hatchOutSamples 为
     * 两路 hatch 样本条数（含跨路重复）——后四者仅供 {@link #logZeroFlowDiagnostic} 诊断输出。
     */
    public static final class EuFlowReading {

        /** 最终采集结果：流入（EU/t，≥0） */
        public final long in;

        /** 最终采集结果：流出（EU/t，≥0） */
        public final long out;

        /** 控制器基座 getAverageElectricInput() 原值（诊断） */
        public final long controllerIn;

        /** 控制器基座 getAverageElectricOutput() 原值（诊断） */
        public final long controllerOut;

        /** 输入方向 hatch 样本条数（含跨路重复，诊断） */
        public final int hatchInSamples;

        /** 输出方向 hatch 样本条数（含跨路重复，诊断） */
        public final int hatchOutSamples;

        public EuFlowReading(long in, long out, long controllerIn, long controllerOut, int hatchInSamples,
            int hatchOutSamples) {
            this.in = in;
            this.out = out;
            this.controllerIn = controllerIn;
            this.controllerOut = controllerOut;
            this.hatchInSamples = hatchInSamples;
            this.hatchOutSamples = hatchOutSamples;
        }
    }

    /**
     * EU 方向统一采集纯算法（零 Minecraft 类加载，单测直测）。语义 = <b>实际网络流量</b>：
     * {@code getAverageElectricInput()} / {@code getAverageElectricOutput()} 仅在 GT5U
     * BaseMetaTileEntity 网络路径（injectEnergyUnits→Input / drainEnergyUnits、handleEUOutput→Output）
     * 累加，采集优先序 = 控制器双均值 → 多方块双路 hatch 聚合（同一 hatch 基座按引用去重）→
     * 真双零时进入<b>预选词条/数值链</b>。getter 与 hatch 聚合合计仍双零（RUNNING 真双零，典型如
     * 记账绕过型无线馈电）时才进入下方四层链（逐层读到非零即停，全链零则保持双零）。
     * <p>
     * <b>四层链序 + 每层方向来源</b>：
     * <ol>
     * <li>① 发电词条幅值：{@code isGenerator} 且 {@code fallbackEut} 大于 0 → out。方向来源 =
     * {@code DeviceMachineTypes.isGeneratorMachine} 白名单<b>词条</b>（与 powerType 同源同值），
     * 不经数值符号、不经结构推断。词条必须居首：词条命中机器的方向已与 powerType 分类绑定，
     * 符号层 ② 只兜「未列名」的长功率多方块（防新引擎族漏录）；反之把符号不可靠的机型误列词条
     * 会直接倒退——GT5U goodgenerator {@code MTELargeFusionComputer} 即实测为<b>纯耗电</b>控制器
     * （:241-242 强制 {@code lEUt} 取负、:321 {@code decreaseStoredEnergyUnits(-lEUt)} 扣能、
     * :561 面板键 {@code gg.infodata.fusion.req} 显示为需求功率，全类无 Dynamo 仓与
     * {@code addEnergyOutput}），故它进排除集而非包含集</li>
     * <li>② 长功率符号权威层：仅 {@code isMultiBlock} 且 {@code signedLongPower} 非 0 时生效，
     * 正 → out、负 → in=幅值。方向来源 = GT5U 多方块<b>带符号</b> {@code lEUt} 的符号
     * （MTEExtendedPowerMultiBlockBase.java:28 字段声明；:53-57 正 {@code lEUt → addEnergyOutput}、
     * 负 {@code lEUt → drainEnergyInput}；:108-113 {@code setEnergyUsage} 把消耗强制写为负；
     * 耗电实证 MTEAssemblyLine.java:367,397；发电经 TTMultiblockBase.java:494-504
     * {@code setPowerFlow} 写入正 {@code lEUt}，UCFE 构造 {@code useLongPower=true}
     * MTEUniversalChemicalFuelEngine.java:85,90）</li>
     * <li>③ 特殊机器权威 provider：{@code providerEut} 正 → out、负 → in=幅值。方向来源 = 注册
     * provider 权威字段符号（{@link DeviceSpecialPowerProvider#readAuthoritativeEut}）——词条命中但
     * 幅值缺失（如 LNR 不维护 mEUt、权威值只在 trueOutput）时接管（v1.8.3 数值链）</li>
     * <li>④ 耗电词条幅值（耗电腿）：{@code !isGenerator} 且 {@code fallbackEut} 大于 0 → in。
     * 方向来源 = 白名单判定为<b>非发电</b>（无线喂电常规机 {@code |mEUt|}）</li>
     * </ol>
     * 符号层<b>只作用于「多方块 + 长功率 {@code lEUt}」</b>：普通多方块 {@code mEUt} 与单方块
     * {@code MTEBasicMachine.mEUt} 的正负语义不可靠（后者正数即<b>耗电</b>，
     * MTEBasicMachine.java:136,608,726-727,739,751 与 :751 处配方取负），故单机与普通多方块调用方
     * 恒传 {@code signedLongPower = 0}。幅值层面的 tEff 效率修正在 {@link #readEuFlow} 取数处完成，
     * 不进入本方法的方向语义。
     *
     * @param hasContainer    tile 是否实现 IBasicEnergyContainer（false 时两控制器读数不参与）
     * @param controllerIn    控制器基座 getAverageElectricInput()
     * @param controllerOut   控制器基座 getAverageElectricOutput()
     * @param isMultiBlock    MTE 是否 MTEMultiBlockBase（false 时两路 hatch 样本与符号层均不参与）
     * @param hatchIn         输入方向 hatch 样本（可含两路重复；isMultiBlock=false 时可 null）
     * @param hatchOut        输出方向 hatch 样本（同上）
     * @param isGenerator     调用方发电白名单判定结果（true → 白名单兜底记 output，false → 记 input）
     * @param fallbackEut     白名单兜底幅值 |mEUt|/|lEUt|（调用方保证 ≥0 且已按效率修正；0 = 该机无兜底幅值）
     * @param providerEut     特殊机器权威带符号 EU/t（{@link DeviceSpecialPowerProvider#readAuthoritativeEut}
     *                        数值链读取；0 = 未命中/失败/权威值全 0，数值链继续下一词条）
     * @param signedLongPower 多方块长功率<b>带符号</b>幅值（{@code MTEExtendedPowerMultiBlockBase.lEUt}
     *                        原符号、幅值已按效率修正；0 = 不适用，即单机 / 普通多方块 / 长功率字段为 0，
     *                        此时符号层整体跳过，链序与未引入符号层时逐位一致）
     * @return {@code {in, out}}，均 ≥0（in = 网络流入、out = 网络流出，net = out − in 由调用方计算）
     */
    public static long[] collectEuFlow(boolean hasContainer, long controllerIn, long controllerOut,
        boolean isMultiBlock, List<EuFlowSample> hatchIn, List<EuFlowSample> hatchOut, boolean isGenerator,
        long fallbackEut, long providerEut, long signedLongPower) {
        long in = hasContainer ? controllerIn : 0L;
        long out = hasContainer ? controllerOut : 0L;
        if (isMultiBlock) {
            // hatch 聚合先于兜底：任一路非零即压制兜底（记账已在 hatch 基座网络路径体现）
            in += sumDedupByIdentity(hatchIn);
            out += sumDedupByIdentity(hatchOut);
        }
        if (in == 0L && out == 0L) {
            // 真双零门 = 预选词条/数值链四层（读到非零即停）：
            // ① 发电词条幅值（方向由词条 isGenerator 决定，不经数值符号）；词条命中机器方向已与
            // powerType 分类绑定，故词条居首；② 长功率符号权威层（仅多方块带符号 lEUt：正→out /
            // 负→取幅→in）只兜未列名的长功率多方块；幅值缺失（signedLongPower=0，即单机/普通
            // 多方块/字段为 0）时 ② 整层跳过；
            // ③ provider 权威符号值（正→out / 负→取幅→in）——词条命中但幅值缺失（如 LNR |mEUt| 恒 0、
            // 权威值只在 trueOutput）时放行 provider，修复 v1.8.1 起 LNR 读不到输出的回归；
            // ④ 耗电词条幅值（仅未命中发电词条：无线喂电常规机的 |mEUt| 消费腿）。
            if (isGenerator && fallbackEut > 0L) {
                out = fallbackEut;
            } else if (isMultiBlock && signedLongPower > 0L) {
                out = signedLongPower;
            } else if (isMultiBlock && signedLongPower < 0L) {
                in = absEut(signedLongPower);
            } else if (providerEut > 0L) {
                out = providerEut;
            } else if (providerEut < 0L) {
                in = absEut(providerEut);
            } else if (!isGenerator && fallbackEut > 0L) {
                in = fallbackEut;
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
     * 聚合，兜底幅值按代际字段取绝对值（扩展电力多方块 |lEUt|、其余多方块 |mEUt|、单方块耗电
     * 常规机 MTEBasicMachine |mEUt|、单方块发电家族 maxEUOutput() 名义输出——MTEBasicGenerator
     * 不以 mEUt 记账发电、燃料直入基座缓冲，无线动力覆盖板 decreaseStoredEU 直扣缓冲绕过均值记账、
     * 其余机型 0 = 永不兜底），全部交给 {@link #collectEuFlow}
     * 判定（同时传入特殊机器权威 provider 读数 {@link DeviceSpecialPowerProvider#readAuthoritativeEut}
     * 与多方块带符号长功率 {@code signedLongPower}：真双零门内按四层预选词条/数值链定值——
     * 发电词条幅值 → <b>长功率符号权威层</b> → provider 符号值 → 耗电词条幅值，读到非零即停；
     * v1.8.3 起词条命中机器也读 provider，幅值缺失时由权威字段接管，修复 LNR 显示 0 的回归）；
     * 白名单兜底方向由调用方传入的发电白名单结果（isGenerator）决定，本方法不重复推断。
     * <p>
     * <b>符号与效率的取数位置</b>：{@code signedLongPower} 只在「MTE + MTEExtendedPowerMultiBlockBase」
     * 分支取（原样带符号的 {@code lEUt}），单机与普通多方块恒传 0——{@code mEUt} 系字段符号不可靠，
     * 见 {@link #collectEuFlow} 链序说明。效率修正（UCFE 家族 {@code tEff} 万分度，
     * {@link #readEfficiencyScale}/{@link #applyEfficiency}）<b>只发生在取幅值处</b>（兜底幅值与
     * 符号层幅值各自缩放，符号原样保留），不写回方向语义、不进 collectEuFlow。
     * 两路 = GT5U MTEMultiBlockBase public 字段 {@code mEnergyHatches}/{@code mDynamoHatches}
     * （字段声明即 new ArrayList 非 null，字段直读恒成功）与 Tectech TTMultiblockBase public 方法
     * {@code getExoticAndNormalEnergyHatchList()}/{@code getExoticDynamoHatches()} 反射
     * （TT 列表含 mEnergyHatches 子集，故跨路同一 hatch 基座按引用去重；空列表 = 该结构
     * 确无此类仓，不是枚举失败，枚举与否不再作兜底门）。禁止编译期 import
     * GoodGenerator/Tectech 类，TT 侧只走反射。
     */
    private static EuFlowReading readEuFlow(IMetaTileEntity mte, IGregTechTileEntity gtTE, boolean isGenerator) {
        // 特殊机器权威 provider（数值链，采集优先序第 3 层）：仅 RUNNING 态读一次；注册类
        // （LNR trueOutput / DysonSwarm euPerTick）按预选字段数值链读到非零即停，未注册/失败/全链
        // 零恒 0（内部全静默）。v1.8.3 起词条命中机器同样读取 provider：真双零门内发电词条幅值
        // 缺失（如 LNR |mEUt| 恒 0）时由 provider 符号值接管（见 collectEuFlow 四层数值链；
        // LNR 为单方块，signedLongPower 恒 0，符号层不抢先）。
        long providerEut = DeviceSpecialPowerProvider.readAuthoritativeEut(mte);
        boolean hasContainer = gtTE instanceof IBasicEnergyContainer;
        long controllerIn = 0L;
        long controllerOut = 0L;
        if (hasContainer) {
            IBasicEnergyContainer container = (IBasicEnergyContainer) gtTE;
            controllerIn = container.getAverageElectricInput();
            controllerOut = container.getAverageElectricOutput();
        }
        if (!(mte instanceof MTEMultiBlockBase)) {
            // 单机：耗电常规机按 |mEUt| 兜底；发电家族（柴油/燃气/蒸汽涡轮等发电常规机、太阳能、
            // 避雷针）按 maxEUOutput() 名义输出兜底——MTEBasicGenerator 不以 mEUt 记账发电（燃料经
            // increaseStoredEnergyUnits 直入基座缓冲），且无线动力覆盖板 decreaseStoredEU 直扣缓冲
            // 绕过记账，getter 双均值恒 0
            long fallbackEut;
            if (mte instanceof MTEBasicMachine) {
                fallbackEut = absEut(((MTEBasicMachine) mte).mEUt);
            } else if (isGenerator) {
                fallbackEut = ((MetaTileEntity) mte).maxEUOutput();
            } else {
                fallbackEut = 0L;
            }
            long[] flow = collectEuFlow(
                hasContainer,
                controllerIn,
                controllerOut,
                false,
                null,
                null,
                isGenerator,
                fallbackEut,
                providerEut,
                // 单机恒不启用符号层：mEUt/maxEUOutput 无「正=发电」符号约定（MTEBasicMachine 正数即耗电）
                0L);
            return new EuFlowReading(flow[0], flow[1], controllerIn, controllerOut, 0, 0);
        }
        MTEMultiBlockBase multi = (MTEMultiBlockBase) mte;
        List<EuFlowSample> hatchIn = new ArrayList<>();
        List<EuFlowSample> hatchOut = new ArrayList<>();
        // 路 A：super public 字段（字段存在即枚举成功；空列表 = 该结构确无此类仓）
        if (multi.mEnergyHatches != null) {
            collectHatchSamples(hatchIn, multi.mEnergyHatches, true);
        }
        if (multi.mDynamoHatches != null) {
            collectHatchSamples(hatchOut, multi.mDynamoHatches, false);
        }
        // 路 B：TT 反射（两路均无条件尝试；返回值只表本路是否拿到 List，不再作兜底门）
        collectExoticHatchSamples(multi, hatchIn, "getExoticAndNormalEnergyHatchList", true);
        collectExoticHatchSamples(multi, hatchOut, "getExoticDynamoHatches", false);
        // 兜底幅值与符号层入参：扩展电力多方块（MTEExtendedPowerMultiBlockBase，lEUt 为 long 且
        // GT5U 约定「正=发电 / 负=耗电」，MTEExtendedPowerMultiBlockBase.java:28,53-57,108-113）
        // 取带符号原值 rawLongPower，其余多方块无可靠符号约定（mEUt 正数可为耗电）⇒ rawLongPower=0，
        // 符号层整体跳过。tEff 万分度效率仅对扩展电力多方块探测（UCFE 家族），兜底幅值与符号层
        // 幅值都在此处缩放（方向语义不感知效率）。注：goodgenerator 大聚变 MTELargeFusionComputer
        // 是扩展电力多方块但产能时强制 lEUt 取负（:241-242）且本身纯耗电，已在 DeviceMachineTypes
        // 排除集 → isGenerator=false → 符号层 ② 据 lEUt<0 正确记入耗电腿。
        boolean longPower = mte instanceof MTEExtendedPowerMultiBlockBase;
        long rawLongPower = longPower ? ((MTEExtendedPowerMultiBlockBase) mte).lEUt : 0L;
        long efficiencyScale = longPower ? readEfficiencyScale(mte) : NO_EFFICIENCY_SCALE;
        long fallbackEut = applyEfficiency(absEut(longPower ? rawLongPower : multi.mEUt), efficiencyScale);
        // 符号层入参：符号原样保留，仅幅值按效率修正（-幅值不会出现负溢出：入参已过 absEut 钳位）
        long signedLongPower = 0L;
        if (rawLongPower > 0L) {
            signedLongPower = applyEfficiency(absEut(rawLongPower), efficiencyScale);
        } else if (rawLongPower < 0L) {
            signedLongPower = -applyEfficiency(absEut(rawLongPower), efficiencyScale);
        }
        long[] flow = collectEuFlow(
            hasContainer,
            controllerIn,
            controllerOut,
            true,
            hatchIn,
            hatchOut,
            isGenerator,
            fallbackEut,
            providerEut,
            signedLongPower);
        return new EuFlowReading(flow[0], flow[1], controllerIn, controllerOut, hatchIn.size(), hatchOut.size());
    }

    /**
     * 带符号功率字段的非负幅值（白名单兜底幅值与 provider 负值取幅恒 ≥0）：int 字段（mEUt）经
     * 调用处拓宽为 long，long 字段（lEUt）按 Long.MIN_VALUE 钳 Long.MAX_VALUE，杜绝 Math.abs
     * 溢出为负。
     */
    private static long absEut(long signed) {
        return signed == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(signed);
    }

    /**
     * 读取万分度效率标尺（GT5U UCFE 家族自持字段 {@code tEff}，
     * MTEUniversalChemicalFuelEngine.java:76 声明 {@code private long tEff}、:336-341
     * {@code calculateEfficiency} 无促进剂时 {@code tEff = 0}、上限
     * {@code EFFICIENCY_CEILING=1.5D}（:73）⇒ 最大 15000；:299 {@code addAutoEnergy} 的真实
     * EU/t = {@code getPowerFlow() * tEff / 10000}，故 tEff=0 即真实 0 输出）。
     * <p>
     * 沿 {@code getClass()→getSuperclass()} 向上逐层 {@code getDeclaredFields()} 找同名
     * <b>数值</b>字段（≤ {@value #EFFICIENCY_FIELD_MAX_DEPTH} 层，{@code null} 安全，
     * {@code setAccessible(true)} 读私有字段），命中负值视为「未初始化/不适用」。
     * <p>
     * 本方法只对传入对象做字段探测，<b>长功率机器门在调用处</b>（{@link #readEuFlow} 的
     * {@code mte instanceof MTEExtendedPowerMultiBlockBase} 三元）：效率缩放与符号层同样只允许
     * 作用于「多方块 + 长功率 {@code lEUt}」，普通多方块与单机的 {@code mEUt} 一律传
     * {@link #NO_EFFICIENCY_SCALE} 原样不缩放。门放在调用处而非本方法内，是为了让纯算法
     * （反射取标尺、按标尺缩放）可像 {@link #collectEuFlow} 一样被零 Minecraft 类加载的单测直测。
     * 未找到字段 / 字段非数值 / 反射不可读一律返回 {@link #NO_EFFICIENCY_SCALE}（-1）哨兵表示
     * 「不缩放」，且任何异常都静默降级、绝不抛穿采样（与
     * {@link #collectExoticHatchSamples} 同一容错口径）。
     *
     * @param mte 被测 MTE（仅 {@code MTEExtendedPowerMultiBlockBase} 实例由调用处传入）
     * @return 万分度效率标尺（≥0，10000 = 100%）；-1 = 不适用，调用方不缩放
     */
    static long readEfficiencyScale(Object mte) {
        if (mte == null) {
            return NO_EFFICIENCY_SCALE;
        }
        try {
            Class<?> type = mte.getClass();
            for (int depth = 0; type != null && depth < EFFICIENCY_FIELD_MAX_DEPTH; depth++) {
                for (Field field : type.getDeclaredFields()) {
                    if (!EFFICIENCY_FIELD_NAME.equals(field.getName())) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object value = field.get(mte);
                    // Boolean / 引用类型同名字段不算数值：继续向上层找（不中断整条链）
                    if (value instanceof Number) {
                        long perTenK = ((Number) value).longValue();
                        return perTenK >= 0L ? perTenK : NO_EFFICIENCY_SCALE;
                    }
                }
                type = type.getSuperclass();
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // 字段不可访问 / 强封装拒绝 / 类初始化异常：本机器按「不缩放」降级，不抛穿采样
            return NO_EFFICIENCY_SCALE;
        }
        return NO_EFFICIENCY_SCALE;
    }

    /**
     * 按万分度标尺缩放功率幅值（只在 {@link #readEuFlow} 取幅值处调用，不进方向语义）：
     * <ul>
     * <li>{@code scalePerTenK} 为负（{@link #NO_EFFICIENCY_SCALE} 哨兵）→ 原值返回（不缩放）</li>
     * <li>{@code scalePerTenK} 为 0 或幅值非正 → 0（UCFE 无促进剂时 {@code tEff=0} 即真实 0 输出，
     * MTEUniversalChemicalFuelEngine.java:338）</li>
     * <li>其余 → 乘标尺除 10000，按「高低位拆分」避免中间乘积溢出后<b>假性饱和</b>
     * （直接 {@code magnitude*scale} 在 1e15×15000 时就溢出，而真实商 1.5e15 完全放得下）；
     * 真实商确实溢出时饱和钳 {@code Long.MAX_VALUE}，不回绕为负</li>
     * </ul>
     *
     * @param magnitude    非负幅值（调用方已 {@link #absEut}）；负值按 0 防御
     * @param scalePerTenK 万分度标尺（{@link #readEfficiencyScale} 结果，-1 = 不缩放）
     * @return 缩放后幅值，恒 ≥0，最大 {@code Long.MAX_VALUE}
     */
    static long applyEfficiency(long magnitude, long scalePerTenK) {
        if (scalePerTenK < 0L) {
            return magnitude;
        }
        if (magnitude <= 0L || scalePerTenK == 0L) {
            return 0L;
        }
        long high = magnitude / EFFICIENCY_SCALE_BASE;
        long low = magnitude % EFFICIENCY_SCALE_BASE;
        // 真实商 ≥ high*scale：该乘积溢出即真实结果必溢出，饱和；否则乘积精确可用
        if (high > Long.MAX_VALUE / scalePerTenK) {
            return Long.MAX_VALUE;
        }
        long scaledHigh = high * scalePerTenK;
        long scaledLow = 0L;
        if (low > 0L) {
            if (low > Long.MAX_VALUE / scalePerTenK) {
                // 标尺畸形巨大（远超 15000 上限）：整条读数不可信，按饱和上报而非编造中间值
                return Long.MAX_VALUE;
            }
            scaledLow = low * scalePerTenK / EFFICIENCY_SCALE_BASE;
        }
        return scaledHigh > Long.MAX_VALUE - scaledLow ? Long.MAX_VALUE : scaledHigh + scaledLow;
    }

    /**
     * RUNNING 真双零限频诊断（不改采样结果与控制流）：每键 {@value #ZERO_FLOW_LOG_INTERVAL_TICKS}t
     * 至多 1 条，输出 mte 类名 / 控制器双均值原值 / 两路 hatch 样本数，辅助定位兜底后仍双零的
     * 残余机器（读数全 0 且无可用兜底幅值）。仅由 RUNNING 态且含兜底后仍 in==0 && out==0 时调用。
     */
    private void logZeroFlowDiagnostic(String key, IMetaTileEntity mte, EuFlowReading reading, long tick) {
        Long last = this.zeroFlowLogTicks.get(key);
        if (last != null && tick - last.longValue() < ZERO_FLOW_LOG_INTERVAL_TICKS) {
            return;
        }
        this.zeroFlowLogTicks.put(key, Long.valueOf(tick));
        GTSimpleWirelessNetwork.LOG.info(
            "[设备终端] RUNNING 双零流量诊断：key={} mte={} controllerIn={} controllerOut={} hatchIn样本数={} hatchOut样本数={}",
            key,
            mte.getClass()
                .getName(),
            reading.controllerIn,
            reading.controllerOut,
            reading.hatchInSamples,
            reading.hatchOutSamples);
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
            // TT 未安装 / 方法不存在 / 调用抛错：本路不可用即跳过（兜底改按幅值另行判定），不抛穿
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
