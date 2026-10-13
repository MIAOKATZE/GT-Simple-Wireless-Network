package com.miaokatze.gtswn.common.device;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraftforge.common.util.Constants;

import com.miaokatze.gtswn.common.util.SavedDataUtil;
import com.miaokatze.gtswn.config.Config;

/**
 * 设备信息终端数据仓（每存档一份，阶段 B）。
 * <p>
 * 终端实例 UUID（物品 NBT 的 DIT_UUID）→ {@link TerminalData}（绑定键序 + 机器记录 + 版本号）。
 * 机器键与 {@link DeviceRegistryData} 共用（{@code "dim:x:y:z"}）。挂载点同为 overworld
 * perWorldStorage（终端绑定跟玩家 / 物品走，跨维度右击不得分裂数据）。
 * <p>
 * 版本号语义（防轮询重发）：任一终端的绑定集或记录变化即该终端 {@code version++} 并 markDirty；
 * 请求排水时玩家已收版本 == 当前版本则跳过重发（阶段 D5）。
 * <p>
 * 活跃索引（运行时态，不落盘）：登录扫背包、登出移除，供阶段 C 采样调度只采在线玩家持有的终端。
 */
public class DeviceTerminalDataStore extends WorldSavedData {

    /** WorldSavedData 注册名（全仓唯一，落盘文件名） */
    public static final String DATA_NAME = "gtswn_device_terminal_data";

    /** 每台机器 EU/t FIFO 深度（输入/输出双通道共用写指针与计数，60 采样点） */
    public static final int FIFO_SIZE = 60;

    /** 机器状态：待机（非运行且允许工作） */
    public static final int STATE_IDLE = 0;

    /** 机器状态：运行（isActive） */
    public static final int STATE_RUNNING = 1;

    /** 机器状态：停机（!isAllowedToWork） */
    public static final int STATE_STOPPED = 2;
    public static final int STATE_IGNORED = 3;

    /** 功率分类：耗电（旧档缺省值） */
    public static final byte POWER_TYPE_CONSUME = 0;

    /** 功率分类：发电（{@link DeviceMachineTypes#isGeneratorMachine} 判定） */
    public static final byte POWER_TYPE_GENERATE = 1;

    /** 终端 UUID → 终端数据 */
    private final Map<UUID, TerminalData> terminals = new HashMap<>();

    public DeviceTerminalDataStore(String name) {
        super(name);
    }

    /**
     * 取或创建数据仓（任意维度 world 均解析到 overworld 挂载）。
     */
    public static DeviceTerminalDataStore get(World world) {
        return SavedDataUtil.loadOrCreate(
            resolveGlobalWorld(world),
            DATA_NAME,
            DeviceTerminalDataStore.class,
            DeviceTerminalDataStore::new);
    }

    // ==================== 绑定操作 ====================

    /** 加绑定结果（上限拒绝路径供聊天提示区分） */
    public enum AddResult {

        /** 新增绑定成功 */
        SUCCESS,

        /** 该机器已在绑定列表（幂等，不 dirty） */
        ALREADY_BOUND,

        /** 绑定数已达 Config.deviceTerminalMaxMachines（拒绝，不 dirty） */
        LIMIT_REACHED
    }

    /**
     * 取或创建终端数据（不存在则新建空终端并 markDirty）。
     */
    public TerminalData getOrCreateTerminal(UUID terminalId) {
        TerminalData data = terminals.get(terminalId);
        if (data == null) {
            data = new TerminalData();
            terminals.put(terminalId, data);
            markDirty();
        }
        return data;
    }

    /** 查询终端数据（只读，不存在返回 null，不创建） */
    public TerminalData getTerminal(UUID terminalId) {
        return terminals.get(terminalId);
    }

    /**
     * 全部终端数据快照（阶段 C 采样 / 自愈遍历用，修改不影响内部映射）。
     */
    public List<Map.Entry<UUID, TerminalData>> getAllTerminals() {
        return new ArrayList<>(terminals.entrySet());
    }

    /** O(actual held terminals), independent of historical save size. */
    public List<Map.Entry<UUID, TerminalData>> getActiveTerminals() {
        List<Map.Entry<UUID, TerminalData>> active = new ArrayList<>();
        for (UUID id : ACTIVE_TERMINALS) {
            TerminalData data = terminals.get(id);
            if (data != null) active.add(new java.util.AbstractMap.SimpleImmutableEntry<>(id, data));
        }
        return active;
    }

    /**
     * 加绑定（右击机器 / 放置自动绑定 / 扫描录入统一入口）。
     * <p>
     * 尊重 {@link Config#deviceTerminalMaxMachines} 上限；成功时以 localName 与坐标
     * 创建初始 MachineRecord（FIFO 空、状态待机、功率分类按调用方传入，采样由阶段 C
     * 调度持续刷新）。
     */
    public AddResult addBinding(UUID terminalId, String key, String localName, int dim, int x, int y, int z,
        byte powerType) {
        return addBinding(terminalId, key, localName, dim, x, y, z, powerType, -1, (byte) 0, (byte) 0);
    }

    public AddResult addBinding(UUID terminalId, String key, String localName, int dim, int x, int y, int z,
        byte powerType, int metaTileId, byte energyKind, byte linkNodeType) {
        return addBinding(
            terminalId,
            key,
            localName,
            dim,
            x,
            y,
            z,
            powerType,
            metaTileId,
            energyKind,
            linkNodeType,
            DeviceSteamAdapter.legacyCapabilityMask(energyKind, powerType));
    }

    public AddResult addBinding(UUID terminalId, String key, String localName, int dim, int x, int y, int z,
        byte powerType, int metaTileId, byte energyKind, byte linkNodeType, byte capabilityMask) {
        TerminalData data = getOrCreateTerminal(terminalId);
        if (data.boundKeys.contains(key)) {
            return AddResult.ALREADY_BOUND;
        }
        if (data.boundKeys.size() >= Config.deviceTerminalMaxMachines) {
            return AddResult.LIMIT_REACHED;
        }
        data.boundKeys.add(key);
        MachineRecord record = new MachineRecord();
        record.name = localName;
        record.metaTileId = metaTileId;
        record.energyKind = energyKind;
        record.capabilityMask = (byte) (capabilityMask & 15);
        record.linkNodeType = linkNodeType;
        record.powerType = powerType == POWER_TYPE_GENERATE ? POWER_TYPE_GENERATE : POWER_TYPE_CONSUME;
        record.dim = dim;
        record.x = x;
        record.y = y;
        record.z = z;
        record.resetRecognition(recognitionMode(terminalId, key, metaTileId), capabilityMask);
        data.records.put(key, record);
        data.version++;
        markDirty();
        return AddResult.SUCCESS;
    }

    /**
     * 解除单台终端的单个绑定（阶段 E GUI Ctrl+点击 → 阶段 D 动作包调用）。
     *
     * @return true=已解除（version++ + markDirty）；false=绑定不存在（不 dirty）
     */
    public byte recognitionMode(UUID terminalId, String key, int metaTileId) {
        return recognitionMode(terminalId, key, metaTileId, DeviceDefaultRecognitionPolicy.prototype(metaTileId));
    }

    public byte recognitionMode(UUID terminalId, String key, int metaTileId,
        gregtech.api.interfaces.metatileentity.IMetaTileEntity machine) {
        TerminalData data = getOrCreateTerminal(terminalId);
        MachineRecord record = data.records.get(key);
        RecognitionOverride override = data.recognitionOverrides.get(key);
        boolean changedIdentity = override != null && metaTileId >= 0 && override.metaTileId != metaTileId;
        if (changedIdentity) {
            data.recognitionOverrides.remove(key);
            override = null;
        }
        byte mode = override != null ? override.mode
            : machine != null ? DeviceDefaultRecognitionPolicy.defaultMode(machine)
                : !changedIdentity && record != null ? record.recognitionMode : DeviceTerminalRecognitionMode.AUTO;
        boolean changedRecordIdentity = record != null && metaTileId >= 0 && record.metaTileId != metaTileId;
        if (record != null && (record.recognitionMode != mode || changedIdentity
            || changedRecordIdentity
            || (mode == DeviceTerminalRecognitionMode.IGNORE && record.hasIgnoredResidual()))) {
            byte originalCapabilities = machine != null ? DeviceSteamAdapter.capabilityMask(machine)
                : record.capabilityMask;
            record.resetRecognition(mode, originalCapabilities);
            if (metaTileId >= 0) record.metaTileId = metaTileId;
            if (machine != null && changedRecordIdentity) {
                record.name = machine.getLocalName();
                record.linkNodeType = DeviceMachineIdentity.linkNodeType(machine);
                gregtech.api.interfaces.tileentity.IGregTechTileEntity liveTile = machine.getBaseMetaTileEntity();
                if (liveTile != null && liveTile.getWorld() != null) {
                    DeviceRegistryData registry = DeviceRegistryData.get(liveTile.getWorld());
                    DeviceRegistryData.DeviceEntry known = registry.getEntry(key);
                    if (known != null) registry.register(key, known.owner, record.name, metaTileId);
                }
                record.powerType = DeviceMachineTypes.isGeneratorMachine(machine)
                    || (DeviceSteamAdapter.energyKind(originalCapabilities) == DeviceSteamAdapter.STEAM
                        && (originalCapabilities & DeviceSteamAdapter.STEAM_OUT) != 0) ? POWER_TYPE_GENERATE
                            : POWER_TYPE_CONSUME;
            }
            data.version++;
            markDirty();
        } else if (changedIdentity) {
            data.version++;
            markDirty();
        }
        return mode;
    }

    /** Used before snapshots and after NBT loading; prototypes not yet registered are deferred. */
    public void refreshRecognitionDefaults(UUID terminalId) {
        TerminalData data = getTerminal(terminalId);
        if (data == null) return;
        for (Map.Entry<String, MachineRecord> entry : data.records.entrySet()) {
            gregtech.api.interfaces.metatileentity.IMetaTileEntity machine = DeviceDefaultRecognitionPolicy
                .prototype(entry.getValue().metaTileId);
            if (machine != null) recognitionMode(terminalId, entry.getKey(), entry.getValue().metaTileId, machine);
        }
    }

    public byte cycleRecognition(UUID terminalId, String key, int metaTileId, byte originalCapabilities) {
        return cycleRecognition(
            terminalId,
            key,
            metaTileId,
            originalCapabilities,
            DeviceDefaultRecognitionPolicy.prototype(metaTileId));
    }

    public byte cycleRecognition(UUID terminalId, String key, int metaTileId, byte originalCapabilities,
        gregtech.api.interfaces.metatileentity.IMetaTileEntity machine) {
        if (KeyFormat.parse(key) == null || metaTileId < 0) return -1;
        TerminalData data = getOrCreateTerminal(terminalId);
        byte baseMode = DeviceDefaultRecognitionPolicy.defaultMode(machine);
        byte mode = DeviceTerminalRecognitionMode.next(recognitionMode(terminalId, key, metaTileId, machine));
        if (mode != baseMode && !data.recognitionOverrides.containsKey(key)
            && data.recognitionOverrides.size() >= DeviceTerminalRecognitionMode.MAX_OVERRIDES) return -1;
        if (mode == baseMode) data.recognitionOverrides.remove(key);
        else data.recognitionOverrides.put(key, new RecognitionOverride(metaTileId, mode));
        MachineRecord record = data.records.get(key);
        if (record != null) record.resetRecognition(mode, originalCapabilities);
        data.version++;
        markDirty();
        return mode;
    }

    public static final class RecognitionOverride {

        public final int metaTileId;
        public final byte mode;

        public RecognitionOverride(int metaTileId, byte mode) {
            this.metaTileId = metaTileId;
            this.mode = mode;
        }
    }

    public boolean removeBinding(UUID terminalId, String key) {
        TerminalData data = terminals.get(terminalId);
        if (data == null) {
            return false;
        }
        boolean removed = data.boundKeys.remove(key);
        removed |= data.records.remove(key) != null;
        if (removed) {
            data.version++;
            markDirty();
        }
        return removed;
    }

    /** Alias belongs to this terminal binding; the original machine name continues to be sampled independently. */
    public boolean renameBinding(UUID terminalId, String key, String name, int color, boolean bold) {
        String normalized = DeviceTerminalNameRules.normalize(name);
        if (normalized == null || !DeviceTerminalNameRules.validColor(color)) return false;
        TerminalData data = terminals.get(terminalId);
        if (data == null || !data.boundKeys.contains(key)) return false;
        MachineRecord record = data.records.get(key);
        if (record == null) return false;
        byte chosenColor = (byte) (normalized.isEmpty() ? DeviceTerminalNameRules.DEFAULT_COLOR : color);
        boolean chosenBold = !normalized.isEmpty() && bold;
        if (normalized.equals(record.displayName) && record.nameColor == chosenColor && record.nameBold == chosenBold)
            return true;
        record.displayName = normalized;
        record.nameColor = chosenColor;
        record.nameBold = chosenBold;
        data.version++;
        markDirty();
        return true;
    }

    /**
     * 从所有终端移除指定机器键（破坏事件级联 / 阶段 C 采样自愈统一入口）。
     * <p>
     * 只遍历终端 Map 本身（无世界 / 区块 / 实体遍历）；每个受影响终端 version++。
     *
     * @return 受影响终端数（0 则不 dirty）
     */
    public int removeKeyFromAll(String key) {
        int affected = 0;
        for (TerminalData data : terminals.values()) {
            boolean removed = data.boundKeys.remove(key);
            removed |= data.records.remove(key) != null;
            if (removed) {
                data.version++;
                affected++;
            }
        }
        if (affected > 0) {
            markDirty();
        }
        return affected;
    }

    // ==================== 活跃终端索引（运行时态，不落盘） ====================

    /** 玩家 UUID → 其背包内持有（登录时扫描）的终端 UUID 集 */
    private static final Map<UUID, Set<UUID>> PLAYER_TERMINALS = new HashMap<>();

    /** 在线玩家持有的终端 UUID 并集（阶段 C 采样调度判定用） */
    private static final Set<UUID> ACTIVE_TERMINALS = new HashSet<>();
    private static final Map<UUID, Integer> HOLDER_COUNTS = new HashMap<>();

    /**
     * 玩家登录：登记其背包内全部终端 UUID（重复登录先清旧映射）。
     */
    public static void onPlayerLoggedIn(UUID playerId, Set<UUID> terminalIds) {
        synchronizeHeldTerminals(playerId, terminalIds);
    }

    public static void onPlayerLoggedOut(UUID playerId) {
        Set<UUID> removed = PLAYER_TERMINALS.remove(playerId);
        if (removed != null) for (UUID id : removed) removeHolder(id);
    }

    /** Inventory reconciliation drops terminals put in chests or on the ground, rather than sampling until logout. */
    public static void synchronizeHeldTerminals(UUID playerId, Set<UUID> actual) {
        Set<UUID> held = PLAYER_TERMINALS.computeIfAbsent(playerId, ignored -> new HashSet<>());
        java.util.Iterator<UUID> iterator = held.iterator();
        while (iterator.hasNext()) {
            UUID id = iterator.next();
            if (!actual.contains(id)) {
                iterator.remove();
                removeHolder(id);
            }
        }
        for (UUID id : actual) if (held.add(id)) addHolder(id);
        if (held.isEmpty()) PLAYER_TERMINALS.remove(playerId);
    }

    private static void addHolder(UUID id) {
        HOLDER_COUNTS.put(id, HOLDER_COUNTS.getOrDefault(id, 0) + 1);
        ACTIVE_TERMINALS.add(id);
    }

    private static void removeHolder(UUID id) {
        int count = HOLDER_COUNTS.getOrDefault(id, 1) - 1;
        if (count <= 0) {
            HOLDER_COUNTS.remove(id);
            ACTIVE_TERMINALS.remove(id);
        } else HOLDER_COUNTS.put(id, count);
    }

    public static void ensureActiveTerminal(UUID playerId, UUID terminalId) {
        Set<UUID> held = PLAYER_TERMINALS.computeIfAbsent(playerId, ignored -> new HashSet<>());
        if (held.add(terminalId)) addHolder(terminalId);
    }

    /** 终端是否活跃（在线玩家持有；阶段 C 采样调度入口判定） */
    public static void clearActiveTerminals() {
        PLAYER_TERMINALS.clear();
        ACTIVE_TERMINALS.clear();
        HOLDER_COUNTS.clear();
    }

    public static boolean isTerminalActive(UUID terminalId) {
        return ACTIVE_TERMINALS.contains(terminalId);
    }

    // ==================== 数据结构 ====================

    /**
     * 单台终端数据：绑定键序（LinkedHashSet 保插入序，GUI 稳定展示）+ 机器记录 + 版本号。
     */
    public static final class TerminalData {

        /** 绑定机器键（dim:x:y:z，插入序） */
        public final LinkedHashSet<String> boundKeys = new LinkedHashSet<>();

        /** 机器键 → 采样记录 */
        public final Map<String, MachineRecord> records = new HashMap<>();
        public final Map<String, RecognitionOverride> recognitionOverrides = new java.util.LinkedHashMap<>();

        /** 数据版本号（任一变更 +1，阶段 D5 请求排水防重发） */
        public long version;

        void readFromNBT(NBTTagCompound tag) {
            boundKeys.clear();
            NBTTagList bound = tag.getTagList("bound", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < bound.tagCount(); i++) {
                String key = bound.getCompoundTagAt(i)
                    .getString("k");
                if (key != null && !key.isEmpty()) {
                    boundKeys.add(key);
                }
            }
            records.clear();
            NBTTagList recs = tag.getTagList("records", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < recs.tagCount(); i++) {
                NBTTagCompound entry = recs.getCompoundTagAt(i);
                String key = entry.getString("key");
                if (key == null || key.isEmpty()) {
                    continue;
                }
                MachineRecord record = new MachineRecord();
                record.readFromNBT(entry.getCompoundTag("data"));
                records.put(key, record);
            }
            recognitionOverrides.clear();
            NBTTagList overrides = tag.getTagList("recognitionOverrides", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < Math.min(overrides.tagCount(), DeviceTerminalRecognitionMode.MAX_OVERRIDES); i++) {
                NBTTagCompound entry = overrides.getCompoundTagAt(i);
                String key = entry.getString("key");
                byte mode = entry.getByte("mode");
                if (KeyFormat.parse(key) != null && mode >= DeviceTerminalRecognitionMode.AUTO
                    && mode <= DeviceTerminalRecognitionMode.IGNORE)
                    recognitionOverrides.put(key, new RecognitionOverride(entry.getInteger("metaTileId"), mode));
            }
            version = tag.getLong("version");
        }

        void writeToNBT(NBTTagCompound tag) {
            NBTTagList bound = new NBTTagList();
            for (String key : boundKeys) {
                NBTTagCompound entry = new NBTTagCompound();
                entry.setString("k", key);
                bound.appendTag(entry);
            }
            tag.setTag("bound", bound);
            NBTTagList recs = new NBTTagList();
            for (Map.Entry<String, MachineRecord> entry : records.entrySet()) {
                NBTTagCompound entryTag = new NBTTagCompound();
                entryTag.setString("key", entry.getKey());
                entryTag.setTag(
                    "data",
                    entry.getValue()
                        .toNBT());
                recs.appendTag(entryTag);
            }
            tag.setTag("records", recs);
            NBTTagList overrides = new NBTTagList();
            for (Map.Entry<String, RecognitionOverride> entry : recognitionOverrides.entrySet()) {
                NBTTagCompound item = new NBTTagCompound();
                item.setString("key", entry.getKey());
                item.setInteger("metaTileId", entry.getValue().metaTileId);
                item.setByte("mode", entry.getValue().mode);
                overrides.appendTag(item);
            }
            tag.setTag("recognitionOverrides", overrides);
            tag.setLong("version", version);
        }
    }

    /**
     * 单台机器采样记录：EU/t 环形 FIFO + 均值 + 三态 + 名字 / 坐标 / 配方快照。
     * <p>
     * 三态统一口径：{@code !isAllowedToWork()}→停机 / {@code isActive()}→运行 / 其余待机。
     * <p>
     * v1.7.18 起仅存储输入/输出双通道；净值始终由下游按 {@code out - in} 派生。
     * 旧档的存储型 {@code fifo}/{@code avg} 净通道键会被忽略，缺少新双通道键时按 0
     * 加载，窗口在新采样后自然回填。
     */
    public static final class MachineRecord {

        /** 流入方向 EU/t 环形缓冲；旧档缺键时保持全 0。 */
        public final double[] fifoIn = new double[FIFO_SIZE];

        /** 流出方向 EU/t 环形缓冲；旧档缺键时保持全 0。 */
        public final double[] fifoOut = new double[FIFO_SIZE];
        public final double[] fifoSteamIn = new double[FIFO_SIZE];
        public final double[] fifoSteamOut = new double[FIFO_SIZE];
        public int metaTileId = -1;
        public byte energyKind;
        public byte capabilityMask;
        public byte recognitionMode;
        /** Actual directions learned for a manual channel, independent of its initial idle fallback. */
        public byte observedOverrideDirections;
        public boolean recognitionWarmup;
        /** 0 observed, 1 provider model, 2 warming up, 3 unavailable. */
        public byte evidenceKind = 2;
        public byte linkNodeType;
        public long instIn;
        public long instOut;
        public long steamInstIn;
        public long steamInstOut;
        public double steamAvgIn;
        public double steamAvgOut;

        /** 环形写指针（下一个写入位，输入/输出双通道共用） */
        public int idx;

        /** 有效样本数（≤ FIFO_SIZE，三通道均值共用分母） */
        public int count;

        /** FIFO 流入均值（EU/t，实际网络流入；v1.7.18 新增，旧档缺键为 0） */
        public double inAvg;

        /** FIFO 流出均值（EU/t，实际网络流出；v1.7.18 新增，旧档缺键为 0） */
        public double outAvg;

        /** 三态：STATE_IDLE / STATE_RUNNING / STATE_STOPPED */
        public int state = STATE_IDLE;

        /** 功率分类：0=耗电 / 1=发电（旧档缺省 0；采样与绑定时由 isGeneratorMachine 刷新） */
        public byte powerType = POWER_TYPE_CONSUME;

        /** 机器本地显示名（绑定 / 采样时刷新） */
        public String name = "";
        public String displayName = "";
        public byte nameColor = DeviceTerminalNameRules.DEFAULT_COLOR;
        public boolean nameBold;

        /** 机器维度 */
        public int dim;

        /** 机器坐标 */
        public int x;

        public int y;

        public int z;

        /** 当前执行配方输入侧描述（v1.7.2 双侧快照；GT5U 无公开 lastRecipe 入口暂置空；阶段 C 采样填充） */
        public String recipeIn = "";

        /** 当前执行配方输出侧描述（输出快照，近似；阶段 C 采样填充） */
        public String recipeOut = "";

        boolean hasIgnoredResidual() {
            return state != STATE_IGNORED || capabilityMask != 0
                || evidenceKind != 0
                || idx != 0
                || count != 0
                || instIn != 0
                || instOut != 0
                || steamInstIn != 0
                || steamInstOut != 0
                || inAvg != 0
                || outAvg != 0
                || steamAvgIn != 0
                || steamAvgOut != 0
                || !recipeIn.isEmpty()
                || !recipeOut.isEmpty();
        }

        public void resetRecognition(byte mode, byte originalCapabilities) {
            recognitionMode = mode;
            observedOverrideDirections = 0;
            java.util.Arrays.fill(fifoIn, 0);
            java.util.Arrays.fill(fifoOut, 0);
            java.util.Arrays.fill(fifoSteamIn, 0);
            java.util.Arrays.fill(fifoSteamOut, 0);
            idx = count = 0;
            instIn = instOut = steamInstIn = steamInstOut = 0;
            inAvg = outAvg = steamAvgIn = steamAvgOut = 0;
            recipeIn = recipeOut = "";
            capabilityMask = DeviceTerminalRecognitionMode.effectiveCapabilities(mode, originalCapabilities, powerType);
            energyKind = mode == DeviceTerminalRecognitionMode.IGNORE
                ? DeviceSteamAdapter.energyKind(originalCapabilities)
                : DeviceSteamAdapter.energyKind(capabilityMask);
            state = mode == DeviceTerminalRecognitionMode.IGNORE ? STATE_IGNORED : STATE_IDLE;
            evidenceKind = mode == DeviceTerminalRecognitionMode.IGNORE ? (byte) 0 : (byte) 2;
            recognitionWarmup = mode != DeviceTerminalRecognitionMode.IGNORE;
        }

        public byte learnOverrideDirections(byte originalCapabilities, byte fallbackCapabilities, long electricIn,
            long electricOut, long steamIn, long steamOut, double[] interval) {
            int channel = DeviceSteamAdapter.energyKind(fallbackCapabilities) == DeviceSteamAdapter.STEAM ? 12 : 3;
            int actual = 0;
            if ((channel & DeviceSteamAdapter.EU_IN) != 0 && (electricIn > 0 || interval[0] > 0))
                actual |= DeviceSteamAdapter.EU_IN;
            if ((channel & DeviceSteamAdapter.EU_OUT) != 0 && (electricOut > 0 || interval[1] > 0))
                actual |= DeviceSteamAdapter.EU_OUT;
            if ((channel & DeviceSteamAdapter.STEAM_IN) != 0 && (steamIn > 0 || interval[2] > 0))
                actual |= DeviceSteamAdapter.STEAM_IN;
            if ((channel & DeviceSteamAdapter.STEAM_OUT) != 0 && (steamOut > 0 || interval[3] > 0))
                actual |= DeviceSteamAdapter.STEAM_OUT;
            observedOverrideDirections = (byte) ((observedOverrideDirections | actual) & channel);
            int nativeRoles = originalCapabilities & channel;
            if (nativeRoles != 0) return (byte) (nativeRoles | observedOverrideDirections);
            return observedOverrideDirections != 0 ? observedOverrideDirections : fallbackCapabilities;
        }

        /** Exact comparison of serialized fields; rolling FIFO bookkeeping alone does not invalidate network pages. */
        public boolean applySample(int nextState, byte nextPower, int meta, byte energy, byte evidence, byte nodes,
            long electricIn, long electricOut, long steamIn, long steamOut, double avgElectricIn, double avgElectricOut,
            double avgSteamIn, double avgSteamOut, int dimension, int px, int py, int pz, String localName,
            String inputRecipe, String outputRecipe) {
            return applySample(
                nextState,
                nextPower,
                meta,
                energy,
                evidence,
                nodes,
                electricIn,
                electricOut,
                steamIn,
                steamOut,
                avgElectricIn,
                avgElectricOut,
                avgSteamIn,
                avgSteamOut,
                dimension,
                px,
                py,
                pz,
                localName,
                inputRecipe,
                outputRecipe,
                DeviceSteamAdapter.legacyCapabilityMask(energy, nextPower));
        }

        public boolean applySample(int nextState, byte nextPower, int meta, byte energy, byte evidence, byte nodes,
            long electricIn, long electricOut, long steamIn, long steamOut, double avgElectricIn, double avgElectricOut,
            double avgSteamIn, double avgSteamOut, int dimension, int px, int py, int pz, String localName,
            String inputRecipe, String outputRecipe, byte capabilities) {
            capabilities = (byte) (capabilities & 15);
            boolean changed = capabilityMask != capabilities || state != nextState
                || powerType != nextPower
                || metaTileId != meta
                || energyKind != energy
                || evidenceKind != evidence
                || linkNodeType != nodes
                || instIn != electricIn
                || instOut != electricOut
                || steamInstIn != steamIn
                || steamInstOut != steamOut
                || Double.compare(inAvg, avgElectricIn) != 0
                || Double.compare(outAvg, avgElectricOut) != 0
                || Double.compare(steamAvgIn, avgSteamIn) != 0
                || Double.compare(steamAvgOut, avgSteamOut) != 0
                || dim != dimension
                || x != px
                || y != py
                || z != pz
                || !java.util.Objects.equals(name, localName)
                || !java.util.Objects.equals(recipeIn, inputRecipe)
                || !java.util.Objects.equals(recipeOut, outputRecipe);
            capabilityMask = capabilities;
            state = nextState;
            powerType = nextPower;
            metaTileId = meta;
            energyKind = energy;
            evidenceKind = evidence;
            linkNodeType = nodes;
            instIn = electricIn;
            instOut = electricOut;
            steamInstIn = steamIn;
            steamInstOut = steamOut;
            inAvg = avgElectricIn;
            outAvg = avgElectricOut;
            steamAvgIn = avgSteamIn;
            steamAvgOut = avgSteamOut;
            dim = dimension;
            x = px;
            y = py;
            z = pz;
            name = localName;
            recipeIn = inputRecipe;
            recipeOut = outputRecipe;
            return changed;
        }

        void readFromNBT(NBTTagCompound tag) {
            // 迁移语义：旧存储型净通道 fifo/avg 及其 NBT 键故意忽略；缺双通道键按 0，窗口自然回填。
            readFifo(tag, "fifoIn", fifoIn);
            readFifo(tag, "fifoOut", fifoOut);
            readFifo(tag, "fifoSteamIn", fifoSteamIn);
            readFifo(tag, "fifoSteamOut", fifoSteamOut);
            metaTileId = tag.hasKey("metaTileId") ? tag.getInteger("metaTileId") : -1;
            energyKind = tag.getByte("energyKind");
            recognitionMode = tag.getByte("recognitionMode");
            if (recognitionMode < 0 || recognitionMode > 2) recognitionMode = 0;
            recognitionWarmup = recognitionMode != DeviceTerminalRecognitionMode.IGNORE;
            observedOverrideDirections = recognitionMode == DeviceTerminalRecognitionMode.OTHER
                ? (byte) (tag.getByte("observedOverrideDirections") & 15)
                : 0;
            evidenceKind = 2;
            linkNodeType = tag.getByte("linkNodeType");
            instIn = tag.getLong("instIn");
            instOut = tag.getLong("instOut");
            steamInstIn = tag.getLong("steamInstIn");
            steamInstOut = tag.getLong("steamInstOut");
            steamAvgIn = tag.getDouble("steamAvgIn");
            steamAvgOut = tag.getDouble("steamAvgOut");
            // 坏存档防御：idx 落到 [0, FIFO_SIZE)，count 落到 [0, FIFO_SIZE]，防采样越界 / 均值分母异常
            idx = ((tag.getInteger("idx") % FIFO_SIZE) + FIFO_SIZE) % FIFO_SIZE;
            count = Math.min(Math.max(tag.getInteger("count"), 0), FIFO_SIZE);
            // 旧 avg 净值键故意不读取；新均值键缺失时 getDouble 返回 0.0
            inAvg = tag.getDouble("inAvg");
            outAvg = tag.getDouble("outAvg");
            state = tag.getInteger("state");
            // v1.8.0 旧档兼容：缺 powerType 键时 getByte 返回 0（=耗电），非 1 值一律钳回耗电
            powerType = tag.getByte("powerType") == POWER_TYPE_GENERATE ? POWER_TYPE_GENERATE : POWER_TYPE_CONSUME;
            if (recognitionMode == DeviceTerminalRecognitionMode.IGNORE)
                resetRecognition(recognitionMode, DeviceSteamAdapter.legacyCapabilityMask(energyKind, powerType));
            capabilityMask = tag.hasKey("capabilityMask") ? (byte) (tag.getByte("capabilityMask") & 15)
                : DeviceSteamAdapter.legacyCapabilityMask(energyKind, powerType);
            name = tag.getString("name");
            String alias = DeviceTerminalNameRules.normalize(tag.getString("displayName"));
            displayName = alias == null ? "" : alias;
            int color = tag.hasKey("nameColor") ? tag.getByte("nameColor") : DeviceTerminalNameRules.DEFAULT_COLOR;
            nameColor = (byte) (!displayName.isEmpty() && DeviceTerminalNameRules.validColor(color) ? color
                : DeviceTerminalNameRules.DEFAULT_COLOR);
            nameBold = !displayName.isEmpty() && tag.getBoolean("nameBold");
            dim = tag.getInteger("dim");
            x = tag.getInteger("x");
            y = tag.getInteger("y");
            z = tag.getInteger("z");
            // v1.7.2 旧档兼容：旧版本仅存单侧 "recipe" 键，新档缺 recipeIn/recipeOut 时
            // getString 返回空串（不迁移旧值，下一采样周期由调度器刷新为双侧新值）
            recipeIn = tag.getString("recipeIn");
            recipeOut = tag.getString("recipeOut");
        }

        NBTTagCompound toNBT() {
            NBTTagCompound tag = new NBTTagCompound();
            // 不再写入存储型净通道 fifo/avg；净值由双通道派生。
            writeFifo(tag, "fifoIn", fifoIn);
            writeFifo(tag, "fifoOut", fifoOut);
            writeFifo(tag, "fifoSteamIn", fifoSteamIn);
            writeFifo(tag, "fifoSteamOut", fifoSteamOut);
            tag.setInteger("metaTileId", metaTileId);
            tag.setByte("energyKind", energyKind);
            tag.setByte("capabilityMask", capabilityMask);
            tag.setByte("recognitionMode", recognitionMode);
            tag.setByte("observedOverrideDirections", observedOverrideDirections);
            tag.setByte("linkNodeType", linkNodeType);
            tag.setLong("instIn", instIn);
            tag.setLong("instOut", instOut);
            tag.setLong("steamInstIn", steamInstIn);
            tag.setLong("steamInstOut", steamInstOut);
            tag.setDouble("steamAvgIn", steamAvgIn);
            tag.setDouble("steamAvgOut", steamAvgOut);
            tag.setInteger("idx", idx);
            tag.setInteger("count", count);
            tag.setDouble("inAvg", inAvg);
            tag.setDouble("outAvg", outAvg);
            tag.setInteger("state", state);
            tag.setByte("powerType", powerType);
            tag.setString("name", name == null ? "" : name);
            tag.setString("displayName", displayName);
            tag.setByte("nameColor", nameColor);
            tag.setBoolean("nameBold", nameBold);
            tag.setInteger("dim", dim);
            tag.setInteger("x", x);
            tag.setInteger("y", y);
            tag.setInteger("z", z);
            tag.setString("recipeIn", recipeIn == null ? "" : recipeIn);
            tag.setString("recipeOut", recipeOut == null ? "" : recipeOut);
            return tag;
        }

        /** 读一条环形缓冲（键缺 / 旧档缺新键时 getTagList 返回空表 → 目标数组保持全 0；条目子键 "v"，三通道同构） */
        private static void readFifo(NBTTagCompound tag, String key, double[] target) {
            NBTTagList list = tag.getTagList(key, Constants.NBT.TAG_COMPOUND);
            int n = Math.min(list.tagCount(), target.length);
            for (int i = 0; i < n; i++) {
                target[i] = list.getCompoundTagAt(i)
                    .getDouble("v");
            }
        }

        /** 写一条环形缓冲（条目子键 "v"，三通道同构） */
        private static void writeFifo(NBTTagCompound tag, String key, double[] source) {
            NBTTagList list = new NBTTagList();
            for (double value : source) {
                NBTTagCompound entry = new NBTTagCompound();
                entry.setDouble("v", value);
                list.appendTag(entry);
            }
            tag.setTag(key, list);
        }
    }

    // ==================== NBT 对称读写 ====================

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        terminals.clear();
        NBTTagList list = tag.getTagList("terminals", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);
            String id = entry.getString("id");
            if (id == null || id.isEmpty()) {
                continue;
            }
            try {
                TerminalData data = new TerminalData();
                data.readFromNBT(entry.getCompoundTag("data"));
                UUID terminalId = UUID.fromString(id);
                terminals.put(terminalId, data);
                refreshRecognitionDefaults(terminalId);
            } catch (IllegalArgumentException e) {
                // 坏 UUID 数据跳过（不阻断其余终端加载）
            }
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        NBTTagList list = new NBTTagList();
        for (Map.Entry<UUID, TerminalData> entry : terminals.entrySet()) {
            NBTTagCompound entryTag = new NBTTagCompound();
            entryTag.setString(
                "id",
                entry.getKey()
                    .toString());
            NBTTagCompound data = new NBTTagCompound();
            entry.getValue()
                .writeToNBT(data);
            entryTag.setTag("data", data);
            list.appendTag(entryTag);
        }
        tag.setTag("terminals", list);
    }

    /** 解析全局挂载世界（overworld；服务端外 / 异常时回退传入 world） */
    private static World resolveGlobalWorld(World world) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server != null) {
            World overworld = server.worldServerForDimension(0);
            if (overworld != null) {
                return overworld;
            }
        }
        return world;
    }
}
