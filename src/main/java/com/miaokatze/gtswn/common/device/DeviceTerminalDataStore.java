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

    /** 每台机器 EU/t FIFO 深度（60 采样点，10s 间隔默认约 10 分钟窗口） */
    public static final int FIFO_SIZE = 60;

    /** 机器状态：待机（非运行且允许工作） */
    public static final int STATE_IDLE = 0;

    /** 机器状态：运行（isActive） */
    public static final int STATE_RUNNING = 1;

    /** 机器状态：停机（!isAllowedToWork） */
    public static final int STATE_STOPPED = 2;

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

    /**
     * 加绑定（右击机器 / 放置自动绑定 / 扫描录入统一入口）。
     * <p>
     * 尊重 {@link Config#deviceTerminalMaxMachines} 上限；成功时以 localName 与坐标
     * 创建初始 MachineRecord（FIFO 空、状态待机，采样由阶段 C 调度填充）。
     */
    public AddResult addBinding(UUID terminalId, String key, String localName, int dim, int x, int y, int z) {
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
        record.dim = dim;
        record.x = x;
        record.y = y;
        record.z = z;
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

    /**
     * 玩家登录：登记其背包内全部终端 UUID（重复登录先清旧映射）。
     */
    public static void onPlayerLoggedIn(UUID playerId, Set<UUID> terminalIds) {
        PLAYER_TERMINALS.put(playerId, new HashSet<>(terminalIds));
        ACTIVE_TERMINALS.addAll(terminalIds);
    }

    /**
     * 玩家登出：移除其终端映射；无人再持有的终端退出活跃集（克隆物品可能多人共享同 UUID）。
     */
    public static void onPlayerLoggedOut(UUID playerId) {
        Set<UUID> removed = PLAYER_TERMINALS.remove(playerId);
        if (removed == null) {
            return;
        }
        for (UUID terminalId : removed) {
            if (!isHeldByOthers(terminalId)) {
                ACTIVE_TERMINALS.remove(terminalId);
            }
        }
    }

    /** 终端是否仍被某个在线玩家持有 */
    private static boolean isHeldByOthers(UUID terminalId) {
        for (Set<UUID> held : PLAYER_TERMINALS.values()) {
            if (held.contains(terminalId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 主线程观测补登记：请求 / 动作排水发现玩家持有某终端时调用。
     * <p>
     * 会话内新获得的终端（创造拿取 / 合成 / PlaceEvent 新建 UUID）未走登录扫描，
     * 若不补入活跃索引，采样调度会一直跳过它直到重登。
     */
    public static void ensureActiveTerminal(UUID playerId, UUID terminalId) {
        Set<UUID> held = PLAYER_TERMINALS.get(playerId);
        if (held == null) {
            held = new HashSet<>();
            PLAYER_TERMINALS.put(playerId, held);
        }
        if (held.add(terminalId)) {
            ACTIVE_TERMINALS.add(terminalId);
        }
    }

    /** 终端是否活跃（在线玩家持有；阶段 C 采样调度入口判定） */
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
            tag.setLong("version", version);
        }
    }

    /**
     * 单台机器采样记录：EU/t 环形 FIFO + 均值 + 三态 + 名字 / 坐标 / 配方快照。
     * <p>
     * 三态统一口径：{@code !isAllowedToWork()}→停机 / {@code isActive()}→运行 / 其余待机。
     */
    public static final class MachineRecord {

        /** EU/t 环形缓冲（长度恒为 {@link #FIFO_SIZE}，未采样位为 0） */
        public final long[] fifo = new long[FIFO_SIZE];

        /** 环形写指针（下一个写入位） */
        public int idx;

        /** 有效样本数（≤ FIFO_SIZE，均值分母） */
        public int count;

        /** FIFO 均值（EU/t，采样时重算） */
        public double avg;

        /** 三态：STATE_IDLE / STATE_RUNNING / STATE_STOPPED */
        public int state = STATE_IDLE;

        /** 机器本地显示名（绑定 / 采样时刷新） */
        public String name = "";

        /** 机器维度 */
        public int dim;

        /** 机器坐标 */
        public int x;

        public int y;

        public int z;

        /** 当前执行配方描述（输出快照，近似；阶段 C 采样填充） */
        public String recipeStr = "";

        void readFromNBT(NBTTagCompound tag) {
            NBTTagList list = tag.getTagList("fifo", Constants.NBT.TAG_COMPOUND);
            int n = Math.min(list.tagCount(), FIFO_SIZE);
            for (int i = 0; i < n; i++) {
                fifo[i] = list.getCompoundTagAt(i)
                    .getLong("v");
            }
            // 坏存档防御：idx 落到 [0, FIFO_SIZE)，count 落到 [0, FIFO_SIZE]，防采样越界 / 均值分母异常
            idx = ((tag.getInteger("idx") % FIFO_SIZE) + FIFO_SIZE) % FIFO_SIZE;
            count = Math.min(Math.max(tag.getInteger("count"), 0), FIFO_SIZE);
            avg = tag.getDouble("avg");
            state = tag.getInteger("state");
            name = tag.getString("name");
            dim = tag.getInteger("dim");
            x = tag.getInteger("x");
            y = tag.getInteger("y");
            z = tag.getInteger("z");
            recipeStr = tag.getString("recipe");
        }

        NBTTagCompound toNBT() {
            NBTTagCompound tag = new NBTTagCompound();
            NBTTagList list = new NBTTagList();
            for (long value : fifo) {
                NBTTagCompound entry = new NBTTagCompound();
                entry.setLong("v", value);
                list.appendTag(entry);
            }
            tag.setTag("fifo", list);
            tag.setInteger("idx", idx);
            tag.setInteger("count", count);
            tag.setDouble("avg", avg);
            tag.setInteger("state", state);
            tag.setString("name", name == null ? "" : name);
            tag.setInteger("dim", dim);
            tag.setInteger("x", x);
            tag.setInteger("y", y);
            tag.setInteger("z", z);
            tag.setString("recipe", recipeStr == null ? "" : recipeStr);
            return tag;
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
                terminals.put(UUID.fromString(id), data);
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
