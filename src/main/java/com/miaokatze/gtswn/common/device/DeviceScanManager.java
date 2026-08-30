package com.miaokatze.gtswn.common.device;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import com.gtnewhorizon.gtnhlib.teams.Team;
import com.gtnewhorizon.gtnhlib.teams.TeamManager;
import com.miaokatze.gtswn.common.device.DeviceRegistryData.DeviceEntry;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/**
 * 设备信息终端异步扫描管理器（实施计划 C2 / 用户修正 M2 / v1.8.0 世界源扩展，
 * 注册于 CommonProxy.init 的 FML 总线）。
 * <p>
 * Shift+右击（{@code ItemDeviceInfoTerminal} onItemRightClick / onItemUse 服务端分支）调
 * {@link #toggleScan}：进行中 = 取消（聊天 scan.cancelled + 清理）；否则启动 5 秒逐秒倒计时
 * （5/4/3/2/1 五条 scan.countdown 提示，保留跨档补发；scan.start 即开始提示）。
 * <p>
 * 0 档执行（用户修正 M2 三段式 + v1.8.0 世界化）：
 * <ol>
 * <li><b>主线程快照</b>：团队成员 UUID 并集（GTNHLib TeamManager owners/officers/members，
 * NoClassDefFoundError/异常回退仅本人）+ 登记表条目副本（A 源）+ 该终端现绑定副本 +
 * 玩家当前维度 {@code world.loadedTileEntityList.toArray()} 快照（B 源）——B 源在主线程
 * 仅提取不可变描述（key/坐标/ownerUuid/getOwnerUuid/是否 D1 工作机器/是否发电），
 * <b>禁止把 TE/World/玩家实体交给后台线程</b>；跨维度边界：以 0 档触发时玩家所在维度为准</li>
 * <li><b>后台单线程 daemon executor</b>（静态懒建，命名 gtswn-device-scan）纯内存过滤合并：
 * A∪B 按 key 去重 − D1 不符 − owner∉团队 − 现绑定，截断至 {@link Config#deviceTerminalMaxMachines}
 * 上限；<b>零世界访问</b>（只做集合运算与字符串解析，回放不重查世界）</li>
 * <li><b>主线程排水</b>（ServerTickEvent END）：世界源新键现查 TE 补登记
 * {@link DeviceRegistryData}（owner+本地名，区块未加载跳过）后批量 addBinding 应用 +
 * 聊天 scan.complete（已录入 N 台（团队 M 人）），version++ 由 addBinding 内部保证</li>
 * </ol>
 * 清理路径三保险：取消（toggle 再按）/ 登出（PlayerLoggedOutEvent）/ 异常（快照与排水各自
 * try-catch 后移除状态，后台任务异常仅丢弃结果不落状态）。
 */
public final class DeviceScanManager {

    /**
     * 倒计时六档剩余 tick（v1.8.0：{5,4,3,2,1,0} 秒逐秒提示 → tick {100,80,60,40,20,0}；
     * 0 档=执行，开始提示由 scan.start 承担）
     */
    private static final int[] COUNTDOWN_TICKS = { 5 * 20, 4 * 20, 3 * 20, 2 * 20, 1 * 20, 0 };

    /** 玩家 UUID → 扫描状态（仅服务端主线程访问） */
    private static final Map<UUID, ScanState> STATES = new HashMap<>();

    /** 后台合并结果队列（后台线程生产，主线程排水消费） */
    private static final ConcurrentLinkedQueue<ScanResult> RESULTS = new ConcurrentLinkedQueue<>();

    /** 后台单线程 executor（静态懒建，daemon：不阻塞 JVM 退出） */
    private static volatile ExecutorService scanExecutor;

    private DeviceScanManager() {}

    /**
     * 注册自宿主 tick / 登出监听（CommonProxy.init 调用一次）。
     */
    public static void register() {
        FMLCommonHandler.instance()
            .bus()
            .register(new DeviceScanManager());
    }

    // ==================== 扫描开关（物品手势入口） ====================

    /**
     * 扫描开关：该玩家扫描进行中 → 取消；否则启动 5 秒逐秒倒计时扫描。
     *
     * @param player     服务端玩家（仅主线程调用）
     * @param terminalId 手持终端实例 UUID（绑定数据锚点）
     */
    public static void toggleScan(EntityPlayerMP player, UUID terminalId) {
        UUID playerId = player.getUniqueID();
        ScanState state = STATES.get(playerId);
        if (state != null) {
            // 取消路径：标记作废（后台结果到达时丢弃）+ 移除状态 + 聊天确认
            state.cancelled = true;
            STATES.remove(playerId);
            sendChat(player, "gtswn.device.chat.scan.cancelled");
            return;
        }
        MinecraftServer server = MinecraftServer.getServer();
        World overworld = server == null ? null : server.worldServerForDimension(0);
        if (overworld == null) {
            return;
        }
        ScanState fresh = new ScanState(player, terminalId, overworld.getTotalWorldTime() + COUNTDOWN_TICKS[0]);
        STATES.put(playerId, fresh);
        // 开始提示（性能口径文案：后台执行不卡服务器；后续 5/4/3/2/1 逐秒提示）
        sendChat(player, "gtswn.device.chat.scan.start");
    }

    // ==================== tick：倒计时推进 + 结果排水 ====================

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return;
        }
        World overworld = server.worldServerForDimension(0);
        if (overworld == null) {
            return;
        }
        if (!STATES.isEmpty()) {
            advanceCountdowns(overworld.getTotalWorldTime());
        }
        drainResults(overworld);
    }

    /** 玩家登出：清理扫描状态（后台结果到达时因状态缺失被丢弃） */
    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!event.player.worldObj.isRemote) {
            ScanState state = STATES.remove(event.player.getUniqueID());
            if (state != null) {
                state.cancelled = true;
            }
        }
    }

    /**
     * 倒计时推进：跨过未提示档位则各发一条聊天（逐秒 5/4/3/2/1，保留跨档补发）；
     * 跨过 0 档执行扫描（每状态仅一次）。
     * <p>
     * 迭代快照副本：executeScan 的异常路径会从 STATES 移除条目，避免并发修改。
     */
    private static void advanceCountdowns(long now) {
        for (ScanState state : new ArrayList<>(STATES.values())) {
            if (state.running) {
                continue;
            }
            long remaining = state.deadlineTick - now;
            while (state.thresholdIndex < COUNTDOWN_TICKS.length
                && remaining <= COUNTDOWN_TICKS[state.thresholdIndex]) {
                int threshold = COUNTDOWN_TICKS[state.thresholdIndex];
                state.thresholdIndex++;
                if (threshold == 0) {
                    executeScan(state);
                    break;
                }
                sendChat(state.player, "gtswn.device.chat.scan.countdown", threshold / 20);
            }
        }
    }

    // ==================== 0 档执行：主线程快照 → 后台过滤 ====================

    /**
     * 主线程快照（团队 UUID 并集 + 登记表副本 + 现绑定副本 + 玩家当前维度世界源快照）后提交
     * 后台过滤。跨维度边界：以 0 档触发时玩家所在维度为准（dimension 快照后回放不重查世界）。
     * 快照异常（世界 / 团队 API）→ 移除状态并记日志（异常清理路径）。
     */
    private static void executeScan(ScanState state) {
        state.running = true;
        try {
            EntityPlayerMP player = state.player;
            if (player.playerNetServerHandler == null) {
                STATES.remove(player.getUniqueID());
                return;
            }
            MinecraftServer server = MinecraftServer.getServer();
            World overworld = server.worldServerForDimension(0);
            // 0 档触发时玩家所在维度（A 源过滤与 B 源世界快照共用基准）
            final int dimension = player.dimension;
            // 团队成员并集（含本人；GTNHLib 缺失/异常回退仅本人）
            Set<UUID> team = resolveTeam(player.getUniqueID());
            // 登记表副本 / 现绑定副本（后台线程只读副本，零世界访问）
            Map<String, DeviceEntry> registry = DeviceRegistryData.get(overworld)
                .snapshotEntries();
            Set<String> bound = new LinkedHashSet<>(
                DeviceTerminalDataStore.get(overworld)
                    .getOrCreateTerminal(state.terminalId).boundKeys);
            // B 源世界快照（主线程，玩家当前维度）：仅提取不可变描述
            List<Candidate> worldCandidates = snapshotWorldCandidates(server, dimension);
            final Set<UUID> teamCopy = team;
            final Map<String, DeviceEntry> registryCopy = registry;
            final Set<String> boundCopy = bound;
            final List<Candidate> worldCopy = worldCandidates;
            final int capacity = Math.max(0, Config.deviceTerminalMaxMachines - boundCopy.size());
            executor().execute(() -> {
                try {
                    RESULTS.add(mergeScan(state, dimension, teamCopy, registryCopy, boundCopy, worldCopy, capacity));
                } catch (Throwable t) {
                    // 后台纯内存运算异常：仅丢弃结果（状态由排水侧超时无果自然残留至登出/取消清理，
                    // 此处无法安全触碰主线程 Map）
                    GTSimpleWirelessNetwork.LOG.error("[设备终端] 后台扫描合并异常（结果丢弃）", t);
                }
            });
        } catch (Throwable t) {
            GTSimpleWirelessNetwork.LOG.error("[设备终端] 扫描快照异常（扫描终止）", t);
            STATES.remove(state.player.getUniqueID());
        }
    }

    /**
     * 主线程世界源快照（B 源，玩家当前维度）：遍历 {@code loadedTileEntityList.toArray()}
     * 快照数组，对每个 GT 基座仅提取<b>不可变描述</b>（key/坐标/ownerUuid、是否 D1 工作机器、
     * 是否发电）为 Candidate（纯 String/int/UUID/boolean 载荷）。
     * <b>TE / World / 玩家实体一律不出本方法</b>（后台线程零 World/TE 访问）。
     */
    private static List<Candidate> snapshotWorldCandidates(MinecraftServer server, int dimension) {
        List<Candidate> candidates = new ArrayList<>();
        World world = server.worldServerForDimension(dimension);
        if (world == null) {
            return candidates;
        }
        Object[] tiles = world.loadedTileEntityList.toArray();
        for (Object object : tiles) {
            if (!(object instanceof TileEntity)) {
                continue;
            }
            TileEntity te = (TileEntity) object;
            if (te.isInvalid()) {
                continue;
            }
            IGregTechTileEntity gtTE = te instanceof IGregTechTileEntity ? (IGregTechTileEntity) te : null;
            if (gtTE == null) {
                // 非 GT 基座：必然不是 D1 工作机器（mergeScan 侧亦会过滤），提前跳过省分配
                continue;
            }
            IMetaTileEntity mte = gtTE.getMetaTileEntity();
            int x = te.xCoord;
            int y = te.yCoord;
            int z = te.zCoord;
            candidates.add(
                new Candidate(
                    DeviceRegistryData.makeKey(dimension, x, y, z),
                    "",
                    dimension,
                    x,
                    y,
                    z,
                    gtTE.getOwnerUuid(),
                    DeviceMachineTypes.isWorkingMachine(mte),
                    DeviceMachineTypes.isGeneratorMachine(mte),
                    true));
        }
        return candidates;
    }

    /**
     * 后台线程过滤合并（纯内存）：A)登记表源（owner∈团队 + 触发维度）∪ B)世界源快照
     * （按 key 去重，A 优先）− D1 不符 − owner∉团队 − 现绑定 → 按键排序 → 截断至上限。
     * <b>本方法不得访问任何世界 / WorldSavedData / 实体对象</b>（仅集合运算与字符串解析，
     * 回放不重查世界；维度判定用 executeScan 快照的 dimension，不读 player）。
     *
     * @param dimension 0 档触发时玩家所在维度（主线程快照值）
     */
    private static ScanResult mergeScan(ScanState state, int dimension, Set<UUID> team,
        Map<String, DeviceEntry> registry, Set<String> bound, List<Candidate> worldCandidates, int capacity) {
        List<Candidate> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // A) 登记表源：登记表仅收录 D1 机器（放置/绑定即校验），这里按 owner∈团队 + 维度过滤
        for (Map.Entry<String, DeviceEntry> entry : registry.entrySet()) {
            String key = entry.getKey();
            if (bound.contains(key) || seen.contains(key) || !team.contains(entry.getValue().owner)) {
                continue;
            }
            int[] pos = parseKey(key);
            if (pos == null || pos[0] != dimension) {
                continue;
            }
            seen.add(key);
            String localName = entry.getValue().localName;
            candidates.add(
                new Candidate(
                    key,
                    localName == null ? "" : localName,
                    pos[0],
                    pos[1],
                    pos[2],
                    pos[3],
                    null,
                    true,
                    false,
                    false));
        }
        // B) 世界源：D1 判别在快照时完成（workingMachine 标志），此处仅查标志（纯内存）
        for (Candidate candidate : worldCandidates) {
            String key = candidate.key;
            if (!candidate.workingMachine || bound.contains(key)
                || seen.contains(key)
                || !team.contains(candidate.ownerUuid)) {
                continue;
            }
            seen.add(key);
            candidates.add(candidate);
        }
        // 截断必须在 A∪B 去重与排序之后统一执行：A 优先仅作用于 key 冲突去重，
        // 不得在收集阶段按容量提前 break（否则会按遍历序漏掉可绑定机器）
        candidates.sort((a, b) -> a.key.compareTo(b.key));
        if (candidates.size() > capacity) {
            candidates = new ArrayList<>(candidates.subList(0, capacity));
        }
        return new ScanResult(state, team.size(), candidates);
    }

    // ==================== 结果排水（主线程） ====================

    /**
     * 主线程排空后台合并结果：状态仍有效（未取消/未登出/未被新扫描顶替）才批量应用。
     * <p>
     * 世界源（fromWorld）新键：主线程现查 TE 补登记 {@link DeviceRegistryData}
     * （owner+本地名，名称主线程现查 TE；区块未加载 / TE 已非 D1 机器 → 跳过该键），
     * 之后 addBinding（powerType 用快照时的发电分类）。
     */
    private static void drainResults(World overworld) {
        ScanResult result;
        while ((result = RESULTS.poll()) != null) {
            ScanState state = STATES.get(result.state.player.getUniqueID());
            if (state == null || state != result.state || state.cancelled) {
                // 取消 / 登出 / 新扫描顶替：丢弃结果
                continue;
            }
            EntityPlayerMP player = state.player;
            STATES.remove(player.getUniqueID());
            if (player.playerNetServerHandler == null) {
                continue;
            }
            try {
                MinecraftServer server = MinecraftServer.getServer();
                DeviceTerminalDataStore store = DeviceTerminalDataStore.get(overworld);
                DeviceRegistryData registry = DeviceRegistryData.get(overworld);
                int added = 0;
                for (Candidate candidate : result.candidates) {
                    String localName = candidate.localName;
                    byte powerType = candidate.generator ? DeviceTerminalDataStore.POWER_TYPE_GENERATE
                        : DeviceTerminalDataStore.POWER_TYPE_CONSUME;
                    if (candidate.fromWorld) {
                        // 世界源新键：主线程现查 TE（登记表此前未收录）→ 补登记 + 取实时名
                        World world = server.worldServerForDimension(candidate.dim);
                        if (world == null || world.getChunkProvider() == null
                            || !world.getChunkProvider()
                                .chunkExists(candidate.x >> 4, candidate.z >> 4)) {
                            // 区块未加载：跳过（不强加载区块）
                            continue;
                        }
                        TileEntity te = world.getTileEntity(candidate.x, candidate.y, candidate.z);
                        IGregTechTileEntity gtTE = te instanceof IGregTechTileEntity ? (IGregTechTileEntity) te : null;
                        IMetaTileEntity mte = gtTE != null ? gtTE.getMetaTileEntity() : null;
                        if (!DeviceMachineTypes.isWorkingMachine(mte)) {
                            // 5 秒倒计时期间机器被替换 / 移除：跳过
                            continue;
                        }
                        localName = mte.getLocalName();
                        registry.register(candidate.key, candidate.ownerUuid, localName);
                    }
                    if (store.addBinding(
                        state.terminalId,
                        candidate.key,
                        localName,
                        candidate.dim,
                        candidate.x,
                        candidate.y,
                        candidate.z,
                        powerType) == DeviceTerminalDataStore.AddResult.SUCCESS) {
                        added++;
                    }
                }
                sendChat(player, "gtswn.device.chat.scan.complete", added, result.teamSize);
            } catch (Throwable t) {
                GTSimpleWirelessNetwork.LOG.error("[设备终端] 扫描结果应用异常（结果丢弃）", t);
            }
        }
    }

    // ==================== 工具 ====================

    /** 后台单线程 executor（懒建；daemon 线程 gtswn-device-scan） */
    private static ExecutorService executor() {
        ExecutorService executor = scanExecutor;
        if (executor == null) {
            synchronized (DeviceScanManager.class) {
                executor = scanExecutor;
                if (executor == null) {
                    executor = Executors.newSingleThreadExecutor(runnable -> {
                        Thread thread = new Thread(runnable, "gtswn-device-scan");
                        thread.setDaemon(true);
                        return thread;
                    });
                    scanExecutor = executor;
                }
            }
        }
        return executor;
    }

    /**
     * 团队成员 UUID 并集（含本人）：GTNHLib {@code TeamManager.getTeamByPlayer} →
     * owners + officers + members；无队伍 / GTNHLib 缺失 / 任何异常 → 仅本人。
     */
    private static Set<UUID> resolveTeam(UUID playerId) {
        Set<UUID> team = new HashSet<>();
        team.add(playerId);
        try {
            Team playerTeam = TeamManager.getTeamByPlayer(playerId);
            if (playerTeam != null) {
                team.addAll(playerTeam.getOwners());
                team.addAll(playerTeam.getOfficers());
                team.addAll(playerTeam.getMembers());
            }
        } catch (Throwable t) {
            // NoClassDefFoundError（GTNHLib 缺失）/ 运行时异常：回退仅本人
            GTSimpleWirelessNetwork.LOG.warn("[设备终端] 团队解析失败，回退仅本人扫描", t);
        }
        return team;
    }

    /** 解析机器键 {@code dim:x:y:z} → [dim,x,y,z]；格式坏返回 null（纯字符串运算，任意线程安全） */
    private static int[] parseKey(String key) {
        if (key == null) {
            return null;
        }
        String[] parts = key.split(":");
        if (parts.length != 4) {
            return null;
        }
        try {
            return new int[] { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                Integer.parseInt(parts[3]) };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 服务端本地化聊天 */
    private static void sendChat(EntityPlayerMP player, String key, Object... args) {
        if (player != null && player.playerNetServerHandler != null) {
            player.addChatMessage(new ChatComponentText(StatCollector.translateToLocalFormatted(key, args)));
        }
    }

    // ==================== 数据结构 ====================

    /** 单玩家扫描状态（仅主线程访问；cancelled 供结果排水作废判定） */
    private static final class ScanState {

        /** 发起玩家（主线程引用；应用前排水复验在线） */
        final EntityPlayerMP player;

        /** 目标终端实例 UUID */
        final UUID terminalId;

        /** 0 档执行时刻（overworld 总 tick） */
        final long deadlineTick;

        /** 下一个未提示档位下标（0 起：5/4/3/2/1 秒各发一条 scan.countdown，0 档=执行） */
        int thresholdIndex = 0;

        /** 已提交后台（倒计时结束） */
        boolean running;

        /** 已取消（结果到达即丢弃） */
        boolean cancelled;

        ScanState(EntityPlayerMP player, UUID terminalId, long deadlineTick) {
            this.player = player;
            this.terminalId = terminalId;
            this.deadlineTick = deadlineTick;
        }
    }

    /** 后台合并产出（进入主线程结果队列的载荷） */
    private static final class ScanResult {

        /** 关联扫描状态（排水侧校验未取消/未顶替） */
        final ScanState state;

        /** 团队人数（聊天报告参数） */
        final int teamSize;

        /** 候选机器（已过滤排序截断，纯内存副本） */
        final List<Candidate> candidates;

        ScanResult(ScanState state, int teamSize, List<Candidate> candidates) {
            this.state = state;
            this.teamSize = teamSize;
            this.candidates = candidates;
        }
    }

    /**
     * 候选机器（不可变描述载荷：键 + 显示名 + 坐标 + owner + 判别标志 + 来源标记；
     * 纯 String/int/UUID/boolean，世界源在主线程快照时构造，后台线程只读安全）。
     */
    private static final class Candidate {

        final String key;

        /**
         * 显示名：登记表源 = 登记表快照名；世界源 = ""（排水主线程现查 TE 后补，
         * 名称为不可变 String 快照外的运行时数据，不进后台线程）
         */
        final String localName;

        final int dim;
        final int x;
        final int y;
        final int z;

        /** owner（世界源 = getOwnerUuid() 快照；登记表源 = null，owner 过滤走登记表条目） */
        final UUID ownerUuid;

        /** 是否 D1 工作机器（世界源快照时判定；登记表源恒 true——登记表仅收录 D1 机器） */
        final boolean workingMachine;

        /** 是否发电分类（世界源快照时判定，用于 addBinding 的 powerType 种子值） */
        final boolean generator;

        /** 是否世界源（true = 排水主线程需补登记 + 现查名） */
        final boolean fromWorld;

        Candidate(String key, String localName, int dim, int x, int y, int z, UUID ownerUuid, boolean workingMachine,
            boolean generator, boolean fromWorld) {
            this.key = key;
            this.localName = localName;
            this.dim = dim;
            this.x = x;
            this.y = y;
            this.z = z;
            this.ownerUuid = ownerUuid;
            this.workingMachine = workingMachine;
            this.generator = generator;
            this.fromWorld = fromWorld;
        }
    }
}
