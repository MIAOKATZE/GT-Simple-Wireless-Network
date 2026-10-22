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
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fluids.FluidStack;

import com.miaokatze.gtswn.config.Config;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.interfaces.tileentity.IMachineProgress;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;

/**
 * Bounded server sampling of active terminal bindings. Actual energy is attributed to controller calls,
 * registered model providers carry a distinct evidence flag.
 * Instantaneous readings use a 20-tick window; the 60-point averages use all changes since each previous observation.
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

        final int[] nonzero = new int[2];

        /** 流入通道（fifoIn）有效样本之和 */
        double sumIn;

        /** 流出通道（fifoOut）有效样本之和 */
        double sumOut;

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
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return;
        }
        // 主线程排空包 8 请求队列（版本未变则跳过重发，防轮询压力）
        if (event.phase == TickEvent.Phase.START) {
            World world = server.worldServerForDimension(0);
            if (world != null) DeviceFlowMonitor.tick(server, world.getTotalWorldTime());
            return;
        }
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
            DeviceTerminalSettings.onLogout(event.player.getUniqueID());
            com.miaokatze.gtswn.network.DeviceTerminalActionQueue.onLogout(event.player.getUniqueID());
        }
    }

    // ==================== 工作队列填充 / 排水 ====================

    /**
     * 到点填充：全部活跃终端的绑定键去重入队（同键多终端共享只采一次，结果分发到各终端）。
     */
    private void refillWorkQueue(World overworld) {
        DeviceTerminalDataStore store = DeviceTerminalDataStore.get(overworld);
        for (Map.Entry<UUID, DeviceTerminalDataStore.TerminalData> entry : store.getActiveTerminals()) {
            if (!DeviceTerminalDataStore.isTerminalActive(entry.getKey())) {
                continue;
            }
            this.workQueue.addAll(entry.getValue().boundKeys);
        }
        this.fifoStates.keySet()
            .retainAll(this.workQueue);
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
            World world = DimensionManager.getWorld(pos[0]);
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
            int liveMetaTileId = gtTE.getMetaTileID();
            if (activeTerminals == null) {
                store = DeviceTerminalDataStore.get(overworld);
                activeTerminals = new ArrayList<>();
                for (Map.Entry<UUID, DeviceTerminalDataStore.TerminalData> entry : store.getActiveTerminals())
                    if (DeviceTerminalDataStore.isTerminalActive(entry.getKey())) activeTerminals.add(entry);
            }
            boolean observable = false;
            for (Map.Entry<UUID, DeviceTerminalDataStore.TerminalData> entry : activeTerminals) {
                if (entry.getValue().boundKeys.contains(key)
                    && store.recognitionMode(entry.getKey(), key, liveMetaTileId, mte)
                        != DeviceTerminalRecognitionMode.IGNORE)
                    observable = true;
            }
            if (!observable) {
                DeviceFlowMonitor.forget(mte);
                this.fifoStates.remove(key);
                continue;
            }
            IMachineProgress progress = (IMachineProgress) gtTE;
            // 三态统一口径：!isAllowedToWork→停机 / isActive→运行 / 其余待机
            int state = !progress.isAllowedToWork() ? DeviceTerminalDataStore.STATE_STOPPED
                : progress.isActive() ? DeviceTerminalDataStore.STATE_RUNNING : DeviceTerminalDataStore.STATE_IDLE;
            // 静态家族只提供待机时的设备角色；实际流量方向来自控制器交互。
            boolean isGenerator = DeviceMachineTypes.isGeneratorMachine(mte);
            // 瞬时值取实际交互的 20 tick 窗口，平均值取采样间隔内累计量。
            // 实测不依赖采样这一刻的 active 状态，避免丢失间歇运行的产出。
            DeviceFlowMonitor.Reading observed = DeviceFlowMonitor
                .watch(mte, Math.max(200L, (long) Config.deviceSampleIntervalSeconds * 60L));
            byte evidenceKind = observed == null ? (byte) 3 : observed.warmedUp() ? (byte) 0 : (byte) 2;
            double[] interval = observed == null ? new double[2] : observed.consumeInterval();
            long euIn = observed == null ? 0L : observed.average(0);
            long euOut = observed == null ? 0L : observed.average(1);
            byte originalCapabilities = DevicePowerCapabilities.capabilityMask(mte);
            // Provider values are explicitly modeled; no recipe-demand or hatch-network fallback is used.
            if (state == DeviceTerminalDataStore.STATE_RUNNING && euIn == 0
                && euOut == 0
                && interval[0] == 0
                && interval[1] == 0) {
                long modeled = DeviceSpecialPowerProvider.readAuthoritativeEut(mte);
                if (modeled != 0) {
                    evidenceKind = 1;
                    if (modeled > 0) {
                        euOut = modeled;
                        interval[1] = modeled;
                    } else {
                        euIn = absEut(modeled);
                        interval[0] = euIn;
                    }
                }
            }
            byte capabilities = DevicePowerCapabilities
                .observedCapabilities(originalCapabilities, euIn, euOut, interval);
            byte powerType = isGenerator ? DeviceTerminalDataStore.POWER_TYPE_GENERATE
                : DeviceTerminalDataStore.POWER_TYPE_CONSUME;
            int metaTileId = gtTE.getMetaTileID();
            byte linkNodeType = DeviceMachineIdentity.linkNodeType(mte);
            String localName = mte.getLocalName();
            String recipeIn = state == DeviceTerminalDataStore.STATE_RUNNING ? buildRecipeInputSnapshot(mte) : "";
            String recipeOut = state == DeviceTerminalDataStore.STATE_RUNNING ? buildRecipeOutputSnapshot(mte) : "";
            // 分发到含该键的所有活跃终端
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
                byte recognitionMode = store.recognitionMode(entry.getKey(), key, metaTileId, mte);
                if (recognitionMode == DeviceTerminalRecognitionMode.IGNORE) {
                    if (record.recognitionMode != recognitionMode
                        || record.state != DeviceTerminalDataStore.STATE_IGNORED) {
                        record.resetRecognition(recognitionMode, DevicePowerCapabilities.capabilityMask(mte));
                        data.version++;
                    }
                    Map<UUID, FifoState> ignoredStates = this.fifoStates.get(key);
                    if (ignoredStates != null) ignoredStates.remove(entry.getKey());
                    continue;
                }
                byte effectiveCapabilities = capabilities;
                long sampleEuIn = euIn, sampleEuOut = euOut;
                double[] sampleInterval = interval.clone();
                byte sampleEvidence = evidenceKind;
                if (record.recognitionWarmup) {
                    record.recognitionWarmup = false;
                    Map<UUID, FifoState> warmStates = this.fifoStates.get(key);
                    if (warmStates != null) warmStates.remove(entry.getKey());
                    sampleEuIn = sampleEuOut = 0;
                    java.util.Arrays.fill(sampleInterval, 0);
                    sampleEvidence = 2;
                }
                record.recognitionMode = recognitionMode;
                record.recordSampleSource(te, world.getTotalWorldTime());
                double nextInAvg = record.inAvg, nextOutAvg = record.outAvg;
                if (sampleEvidence <= 1) {
                    FifoState window = fifoState(key, entry.getKey(), record);
                    double runningSumIn = FifoMath.pushScaled(
                        record.fifoIn,
                        record.idx,
                        record.count,
                        window.sumIn,
                        sampleInterval[0],
                        window.nonzero,
                        0);
                    double runningSumOut = FifoMath.pushScaled(
                        record.fifoOut,
                        record.idx,
                        record.count,
                        window.sumOut,
                        sampleInterval[1],
                        window.nonzero,
                        1);
                    record.fifoIn[record.idx] = sampleInterval[0];
                    record.fifoOut[record.idx] = sampleInterval[1];
                    record.idx = (record.idx + 1) % DeviceTerminalDataStore.FIFO_SIZE;
                    if (record.count < DeviceTerminalDataStore.FIFO_SIZE) {
                        record.count++;
                    }
                    window.sumIn = runningSumIn;
                    window.sumOut = runningSumOut;
                    window.idx = record.idx;
                    window.count = record.count;
                    nextInAvg = FifoMath.scaledAvg(runningSumIn, record.count, DeviceTerminalDataStore.FIFO_SIZE);
                    nextOutAvg = FifoMath.scaledAvg(runningSumOut, record.count, DeviceTerminalDataStore.FIFO_SIZE);
                }
                byte nextCapabilities = record.metaTileId == metaTileId
                    ? (byte) (effectiveCapabilities | (record.capabilityMask & 3))
                    : effectiveCapabilities;
                if (record.applySample(
                    state,
                    powerType,
                    metaTileId,
                    sampleEvidence,
                    linkNodeType,
                    sampleEuIn,
                    sampleEuOut,
                    nextInAvg,
                    nextOutAvg,
                    pos[0],
                    pos[1],
                    pos[2],
                    pos[3],
                    localName,
                    recipeIn,
                    recipeOut,
                    nextCapabilities)) data.version++;
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
            window.sumIn = FifoMath.rebuildScaledSum(record.fifoIn, record.count, window.nonzero, 0);
            window.sumOut = FifoMath.rebuildScaledSum(record.fifoOut, record.count, window.nonzero, 1);
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

    private static long absEut(long signed) {
        return signed == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(signed);
    }

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
