package com.miaokatze.gtswn.crossmod.bq;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miaokatze.gtswn.Tags;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import betterquesting.api.questing.IQuest;
import betterquesting.api.questing.IQuestLine;
import betterquesting.api.questing.tasks.ITask;
import betterquesting.api.storage.BQ_Settings;
import betterquesting.api.utils.NBTConverter;
import betterquesting.api.utils.UuidConverter;
import betterquesting.api2.storage.DBEntry;
import betterquesting.handlers.SaveLoadHandler;
import betterquesting.network.handlers.NetChapterSync;
import betterquesting.network.handlers.NetQuestSync;
import betterquesting.network.handlers.NetSettingSync;
import betterquesting.questing.QuestDatabase;
import betterquesting.questing.QuestInstance;
import betterquesting.questing.QuestLine;
import betterquesting.questing.QuestLineDatabase;
import betterquesting.questing.QuestLineEntry;
import bq_standard.tasks.TaskCheckbox;
import bq_standard.tasks.TaskCrafting;
import bq_standard.tasks.TaskRetrieval;
import cpw.mods.fml.common.Loader;

/**
 * BetterQuesting 运行时任务注入器（任务注入 PoC）。
 * <p>
 * 本 mod 内全部 betterquesting 类型引用都收敛在这一个类中，
 * 且只在 {@link BqCompat#isBqLoaded()} 为 true 时才会被类加载
 * （BQ 缺席环境下本类永不加载，无需 @Optional 字节码剥离）。
 * <p>
 * 挂载点：CommonProxy.serverStarted（FMLServerStartedEvent）。专用服场景
 * （GTNH 2.9.0-beta-3：BQ 3.8.84 + dreamcraft）中，dreamcraft 会在同波次更晚的
 * FMLServerStartingEvent 因 "Modpack has been updated" 整库重载默认任务库，
 * serverStarting 波次内的注入会被整体覆盖；FMLServerStartedEvent 时整波
 * ServerStarting（含 BQ default load 与任何第三方整库重载）已结束、tick 未启动、
 * 无玩家登录，且 BQ 全源码零 FMLServerStartedEvent 订阅，该时点无清库者。
 * 
 * @Mod 的 after:betterquesting 保留为纯排序纵深防御。
 *      <p>
 *      流程（照抄 BQ 官方装载路径 QuestCommandDefaults.load 的同构形状）：
 *      <ol>
 *      <li>读 assets/gtswn/bqquests/index.json 清单（规避 1.7.10 jar 目录枚举）</li>
 *      <li>逐文件 Gson 解析 → NBTConverter.JSONtoNBT_Object(format=true)</li>
 *      <li>任务与任务线幂等 get-or-create 装载（get(id)==null 才 new + readFromNBT）</li>
 *      <li>QuestLineEntry 挂线（未挂才 put）与 QuestLineDatabase.setOrderIndex（初排序，
 *      最终序由下方前排有序收尾统一覆盖）</li>
 *      <li>版本戳对账（世界目录 gtswn-injected.json vs Tags.VERSION）：不一致时执行
 *      「定义刷新」——快照进度→重读定义→merge 回填，完成/领取状态保留，
 *      挂线坐标同步替换；老世界无版本戳视为待升级刷一次。
 *      由此实现「新任务覆盖老任务」：发新版只需替换 jar，玩家免手动迁移</li>
 *      <li>剪枝（无条件执行）：线上存在但 index.json 清单中已删除的任务，摘线并移出
 *      任务数据库——删除操作同样自动传播到所有世界</li>
 *      <li>进度回填：仅恢复本次重建的本包任务，先旧版 QuestProgress.json，后逐玩家
 *      QuestProgress 目录；已有任务的内存进度保持权威，不用磁盘旧值覆盖</li>
 *      <li>前排有序收尾（条款10）：MIAO 常量表 gtsr→gtswn→gtit 对库内存在线依序
 *      setOrderIndex(i) 覆盖为最终序，必须先于同步四连执行</li>
 *      <li>同步四连（NetSettingSync / NetQuestSync.quickSync / NetChapterSync / markDirty）</li>
 *      </ol>
 */
public final class BqQuestInjector {

    /** jar 内任务清单资源路径（index 声明文件树，规避 1.7.10 jar 目录枚举坑） */
    private static final String INDEX_RESOURCE = "assets/gtswn/bqquests/index.json";

    /** 世界侧版本戳文件名（位于 BQ_Settings.curWorldDir 下），记录最近一次注入所用定义版本 */
    private static final String STAMP_FILE_NAME = "gtswn-injected.json";

    /** 本包任务 UUID 高 32 位（ASCII "GTWN"）；不回放其他包的进度。 */
    private static final long OWNED_QUEST_HIGH_PREFIX = 0x4754574EL;

    /**
     * MIAO 任务包前排有序表（BQ任务整合规范 §6 条款10，2026-09-07 拍板）：
     * 线 UUID 高 32 位 pack 标识按展示顺序 gtsr→gtswn→gtit。注入收尾对表内
     * 库内存在的线依序 {@code setOrderIndex(i)}（i=存在线序位，缺线无空洞），
     * 幂等且与跨 mod 注入次序无关（每个注入器收尾都跑全表，最后执行者定最终序）；
     * 未登记 orderIndex 的线沉底（QuestLineDatabase 缺省 -1）。禁用
     * {@code setOrderedEntries}（其首行整库 clear）。
     */
    private static final long[] FRONT_ROW_HIGH_PREFIXES = { 0x47545352L, // "GTSR"
        0x4754574EL, // "GTWN"（本包）
        0x47544954L // "GTIT"
    };

    private BqQuestInjector() {}

    /**
     * 任务注入入口（serverStarted，整波 ServerStarting 含 BQ default load 与任何
     * 第三方整库重载已结束之后）。
     * <p>
     * 双哨兵加固：BqCompat 探测标志 + Loader.isModLoaded 二次确认；
     * {@code BQ_Settings.curWorldDir != null} 表示 BQ 已完成世界装载流程，
     * 任何一项不满足即静默返回（BQ 缺席环境零副作用）。
     * 整体 try-catch 兜底：注入失败不影响 GTSWN 主功能。
     */
    public static void inject() {
        if (!BqCompat.isBqLoaded() || !Loader.isModLoaded("betterquesting")) {
            return;
        }
        if (BQ_Settings.curWorldDir == null) {
            GTSimpleWirelessNetwork.LOG.warn("[BQ] curWorldDir 未就绪（BQ 世界装载未完成），跳过本次任务注入");
            return;
        }
        try {
            JsonObject index = readJsonResource(INDEX_RESOURCE);
            if (index == null) {
                GTSimpleWirelessNetwork.LOG.warn("[BQ] 未找到任务清单 {}，跳过任务注入", INDEX_RESOURCE);
                return;
            }
            JsonArray lines = index.getAsJsonArray("questLines");
            if (lines == null) {
                GTSimpleWirelessNetwork.LOG.warn("[BQ] 任务清单缺少 questLines 数组，跳过任务注入");
                return;
            }
            int questCount = 0;
            int skippedCount = 0;
            int refreshedCount = 0;
            int prunedCount = 0;
            // 版本戳对账：版本变化（含老世界无戳）→ 刷新全部已存在任务的定义与挂线坐标
            boolean refresh = isDefinitionRefreshNeeded();
            Set<UUID> recreatedOwnedQuests = new HashSet<>();
            for (int i = 0; i < lines.size(); i++) {
                int[] r = loadQuestLine(
                    lines.get(i)
                        .getAsJsonObject(),
                    refresh,
                    recreatedOwnedQuests);
                questCount += r[0];
                skippedCount += r[1];
                refreshedCount += r[2];
                prunedCount += r[3];
            }
            restoreProgress(recreatedOwnedQuests);
            // 前排有序收尾（规范 §6 条款10）：覆盖装载阶段的初排序为 MIAO 前排最终序；
            // 必须在同步四连之前执行，客户端顺序经 NetChapterSync order 列表自动镜像
            int frontRowCount = applyFrontRowOrder();
            if (refresh) {
                writeStamp(Tags.VERSION);
            }
            // 同步四连（与 QuestCommandDefaults.load 尾部同款）
            NetSettingSync.sendSync(null);
            NetQuestSync.quickSync(null, true, true);
            NetChapterSync.sendSync(null, null);
            SaveLoadHandler.INSTANCE.markDirty();
            // 注入计数日志（条款9）：正常热重启应全为跳过；新建>0 即疑似被第三方
            // 整库重载覆盖后由本注入器自愈重建（专用服 dreamcraft 重载场景的判定锚点）
            GTSimpleWirelessNetwork.LOG.info(
                "[BQ] 任务注入完成：{} 条任务线，任务 新建 {}/跳过 {}/刷新 {}/删除 {}，前排有序 {} 条{}{}",
                lines.size(),
                questCount,
                skippedCount,
                refreshedCount,
                prunedCount,
                frontRowCount,
                refresh ? "，定义对齐版本 " + Tags.VERSION : "",
                questCount > 0 ? "（新建>0：疑似被第三方整库重载覆盖后自愈重建）" : "");
        } catch (Throwable t) {
            GTSimpleWirelessNetwork.LOG.error("[BQ] 任务注入失败（不影响 GTSWN 主功能）", t);
        }
    }

    /**
     * 装载单条任务线及其下所有任务（全幂等 + 版本化定义刷新）。
     * <p>
     * 装载规则：线 get(id)==null 才 new QuestLine + readFromNBT + put；
     * 任务 get(id)==null 才 new QuestInstance + readFromNBT + put；
     * 线内条目 line.get(questId)==null 才 put(new QuestLineEntry)。
     * <p>
     * 刷新规则（{@code refresh==true}，版本戳对账失败时）：已存在任务重读定义
     * （进度快照→readFromNBT→merge 回填，完成/领取保留），挂线坐标用
     * {@code line.put} 直接替换（UuidDatabase map 语义）。
     * <p>
     * 剪枝规则（无条件执行）：线上存在、但 index.json 清单中已不存在的任务视为
     * 已删除——摘线并从任务数据库移除（进度文件残留无害），使删除操作同样
     * 自动传播到老世界。
     *
     * @param lineSpec             index.json 中该线的声明对象
     * @param refresh              是否对已存在任务执行定义刷新
     * @param recreatedOwnedQuests 本次实际新建的本包任务，供进度回填使用
     * @return int[]{本次新建任务数, 本次跳过任务数, 本次刷新定义任务数, 本次剪枝删除任务数}
     */
    private static int[] loadQuestLine(JsonObject lineSpec, boolean refresh, Set<UUID> recreatedOwnedQuests) {
        UUID lineId = new UUID(
            lineSpec.get("idHigh")
                .getAsLong(),
            lineSpec.get("idLow")
                .getAsLong());
        int orderIndex = lineSpec.get("orderIndex")
            .getAsInt();

        IQuestLine line = QuestLineDatabase.INSTANCE.get(lineId);
        boolean lineCreated = false;
        if (line == null) {
            NBTTagCompound lineTag = readNbtResource(
                lineSpec.get("lineFile")
                    .getAsString());
            if (lineTag == null) {
                GTSimpleWirelessNetwork.LOG.warn("[BQ] 任务线定义文件缺失，跳过该线: {}", lineSpec.get("lineFile"));
                return new int[] { 0, 0, 0, 0 };
            }
            line = new QuestLine();
            line.readFromNBT(lineTag);
            QuestLineDatabase.INSTANCE.put(lineId, line);
            lineCreated = true;
        }
        // setOrderIndex 幂等（仅重排 lineOrder 列表），无条件执行
        QuestLineDatabase.INSTANCE.setOrderIndex(lineId, orderIndex);

        int created = 0;
        int skipped = 0;
        int refreshed = 0;
        Set<UUID> expected = new HashSet<>();
        JsonArray entries = lineSpec.getAsJsonArray("entries");
        for (int i = 0; i < entries.size(); i++) {
            JsonObject entry = entries.get(i)
                .getAsJsonObject();
            NBTTagCompound questTag = readNbtResource(
                entry.get("questFile")
                    .getAsString());
            if (questTag == null) {
                GTSimpleWirelessNetwork.LOG.warn("[BQ] 任务定义文件缺失，跳过: {}", entry.get("questFile"));
                continue;
            }
            UUID questId = NBTConverter.UuidValueType.QUEST.readId(questTag);
            expected.add(questId);
            IQuest existing = QuestDatabase.INSTANCE.get(questId);
            if (existing == null) {
                IQuest quest = new QuestInstance();
                quest.readFromNBT(questTag);
                QuestDatabase.INSTANCE.put(questId, quest);
                if ((questId.getMostSignificantBits() >>> 32) == OWNED_QUEST_HIGH_PREFIX) {
                    recreatedOwnedQuests.add(questId);
                }
                created++;
            } else if (refresh) {
                // 版本升级：重读定义（名称/描述/前置/奖励/任务），玩家进度保留
                refreshDefinition(existing, questTag);
                refreshed++;
            } else {
                // 幂等命中：定义已存在且无需刷新，跳过不重读（重读会 reset 任务级进度）
                skipped++;
            }
            // 任务必须挂线（/bq_admin purge_hidden_quests 会清未挂线任务）：未挂才 put；
            // 已挂线且刷新中则替换条目以同步编辑器坐标
            if (line.get(questId) == null) {
                NBTTagCompound entryTag = readNbtResource(
                    entry.get("entryFile")
                        .getAsString());
                if (entryTag == null) {
                    GTSimpleWirelessNetwork.LOG.warn("[BQ] 线内条目文件缺失，任务暂未挂线: {}", entry.get("entryFile"));
                    continue;
                }
                line.put(questId, new QuestLineEntry(entryTag));
            } else if (refresh) {
                NBTTagCompound entryTag = readNbtResource(
                    entry.get("entryFile")
                        .getAsString());
                if (entryTag == null) {
                    GTSimpleWirelessNetwork.LOG.warn("[BQ] 线内条目文件缺失，坐标保持原样: {}", entry.get("entryFile"));
                } else {
                    line.put(questId, new QuestLineEntry(entryTag));
                }
            }
        }
        // 剪枝：清单中已删除的任务从线上摘除并移出任务数据库（无条件执行，删除自动传播到老世界）
        int pruned = 0;
        List<UUID> stale = line.orderedEntries()
            .map(Map.Entry::getKey)
            .filter(id -> !expected.contains(id))
            .collect(Collectors.toList());
        for (UUID id : stale) {
            line.remove(id);
            QuestDatabase.INSTANCE.remove(id);
            pruned++;
        }
        if (pruned > 0) {
            GTSimpleWirelessNetwork.LOG.info("[BQ] 任务线 {} 清理已删除任务 {} 个: {}", lineId, pruned, stale);
        }
        if (lineCreated || created > 0 || refreshed > 0) {
            GTSimpleWirelessNetwork.LOG
                .info("[BQ] 任务线 {} 装载：线新建={}，新任务={}，刷新定义={}", lineId, lineCreated, created, refreshed);
        }
        return new int[] { created, skipped, refreshed, pruned };
    }

    /**
     * 定义刷新（新任务覆盖老任务的核心步骤）：先快照进度，再重读定义，最后 merge 回填。
     * <p>
     * 完成状态与奖励领取标记整体保留在 completeUsers；子任务进度随快照回填，
     * 若新版定义的任务数量/顺序变化，超出部分的子任务细粒度进度被丢弃
     * （任务级完成与奖励领取不受影响）。
     */
    private static void refreshDefinition(IQuest quest, NBTTagCompound defTag) {
        NBTTagCompound progress = quest.writeProgressToNBT(new NBTTagCompound(), null);
        quest.readFromNBT(defTag);
        quest.readProgressFromNBT(progress, true);
    }

    /**
     * 版本戳判定：世界目录缺少版本戳文件（v1.7.5 及之前注入的老世界）或版本号
     * 与当前构建 {@link Tags#VERSION} 不同时返回 true。
     */
    private static boolean isDefinitionRefreshNeeded() {
        String stamped = readStamp();
        return stamped == null || !stamped.equals(Tags.VERSION);
    }

    /**
     * 读取世界侧版本戳（{@value #STAMP_FILE_NAME}），缺失或解析失败返回 null。
     */
    private static String readStamp() {
        File f = new File(BQ_Settings.curWorldDir, STAMP_FILE_NAME);
        if (!f.isFile()) {
            return null;
        }
        try (BufferedReader br = new BufferedReader(
            new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8),
            1024)) {
            JsonObject o = new JsonParser().parse(br)
                .getAsJsonObject();
            return o.has("version") && o.get("version")
                .isJsonPrimitive() ? o.get("version")
                    .getAsString() : null;
        } catch (Exception e) {
            GTSimpleWirelessNetwork.LOG.warn("[BQ] 版本戳读取失败，本次按需要刷新处理", e);
            return null;
        }
    }

    /**
     * 写入世界侧版本戳。失败仅告警不阻断：下次启动会多刷一次定义（幂等无害）。
     */
    private static void writeStamp(String version) {
        File f = new File(BQ_Settings.curWorldDir, STAMP_FILE_NAME);
        try {
            BqStampFile.write(f, version);
        } catch (Exception e) {
            GTSimpleWirelessNetwork.LOG.warn("[BQ] 版本戳写入失败（下次启动可能重复刷新一次定义）", e);
        }
    }

    /**
     * 只恢复本次重建的本包任务在 default load 缺失定义窗口内丢弃的进度。
     * <p>
     * 已有任务（包括定义刷新过的任务）持有 BQ 恢复后的权威内存进度；merge=true
     * 仍会替换完成/领取记录，不能用磁盘旧值覆盖。旧版汇总文件先读，逐玩家文件后读，
     * 与 BQ SaveLoadHandler.loadProgress 的格式及优先级一致。只通过原生 dirty-player
     * API 安排恢复后的用户在正常存盘时持久化；本读取过程不修改进度文件。
     */
    private static void restoreProgress(Set<UUID> recreatedOwnedQuests) {
        if (recreatedOwnedQuests.isEmpty()) {
            return;
        }
        File legacy = new File(BQ_Settings.curWorldDir, "QuestProgress.json");
        if (legacy.isFile()) {
            restoreProgressFile(legacy, recreatedOwnedQuests, false);
        }
        File dir = new File(BQ_Settings.curWorldDir, "QuestProgress");
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isFile() && f.getName()
                .endsWith(".json")) {
                restoreProgressFile(f, recreatedOwnedQuests, true);
            }
        }
    }

    /** 仅识别本包使用的三个原生任务类的已知用户字段，不遍历自定义任务或任务数据。 */
    private static void collectRestoredUsers(QuestInstance quest, Set<UUID> users) {
        quest.getUsersWithCompletionData(users);
        for (DBEntry<ITask> entry : quest.getTasks()
            .getEntries()) {
            ITask task = entry.getValue();
            Class<?> type = task.getClass();
            if (type != TaskCheckbox.class && type != TaskRetrieval.class && type != TaskCrafting.class) {
                continue;
            }
            // 原生 TaskBase / TaskProgressableBase 输出的顶层用户列表；不读取 data 载荷。
            NBTTagCompound progress = task.writeProgressToNBT(new NBTTagCompound(), null);
            NBTTagList completed = progress.getTagList("completeUsers", Constants.NBT.TAG_STRING);
            for (int i = 0; i < completed.tagCount(); i++) {
                users.add(UUID.fromString(completed.getStringTagAt(i)));
            }
            NBTTagList partial = progress.getTagList("userProgress", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < partial.tagCount(); i++) {
                users.add(
                    UUID.fromString(
                        partial.getCompoundTagAt(i)
                            .getString("uuid")));
            }
        }
    }

    private static void restoreProgressFile(File file, Set<UUID> recreatedOwnedQuests, boolean merge) {
        // JsonHelper.ReadFromFile 会备份损坏文件；这里只读，不改写任何进度文件。
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8),
            32768)) {
            JsonObject json = new JsonParser().parse(reader)
                .getAsJsonObject();
            NBTTagCompound nbt = NBTConverter.JSONtoNBT_Object(json, new NBTTagCompound(), true);
            NBTTagList progress = nbt.getTagList("questProgress", Constants.NBT.TAG_COMPOUND);
            NBTTagList selected = new NBTTagList();
            Set<UUID> selectedIds = new HashSet<>();
            for (int i = 0; i < progress.tagCount(); i++) {
                NBTTagCompound entry = progress.getCompoundTagAt(i);
                // 与原生 QuestDatabase.readProgressFromNBT 一样支持 UUID 和旧整数 ID。
                UUID questId = NBTConverter.UuidValueType.QUEST.tryReadId(entry)
                    .orElse(null);
                if (questId == null && entry.hasKey("questID", Constants.NBT.TAG_ANY_NUMERIC)) {
                    questId = UuidConverter.convertLegacyId(entry.getInteger("questID"));
                }
                if (recreatedOwnedQuests.contains(questId)) {
                    selected.appendTag(entry);
                    selectedIds.add(questId);
                }
            }
            QuestDatabase.INSTANCE.readProgressFromNBT(selected, merge);
            // 与 BQ 旧版进度迁移相同，通过原生 API 合并 dirty 用户，不清空已有集合。
            Set<UUID> restoredUsers = new HashSet<>();
            for (UUID id : selectedIds) {
                IQuest quest = QuestDatabase.INSTANCE.get(id);
                if (quest instanceof QuestInstance) {
                    collectRestoredUsers((QuestInstance) quest, restoredUsers);
                }
            }
            SaveLoadHandler.INSTANCE.addDirtyPlayers(restoredUsers);
        } catch (Throwable t) {
            GTSimpleWirelessNetwork.LOG.warn("[BQ] 进度回填跳过无法解析的文件: {}", file.getName(), t);
        }
    }

    /**
     * 前排有序规则收尾（BQ任务整合规范 §6 条款10）：按 {@link #FRONT_ROW_HIGH_PREFIXES}
     * 顺序对任务线库中实际存在的线依序 {@code setOrderIndex(i)}（i=存在线序位），
     * 覆盖装载阶段 index.json 的初排序，收敛为 MIAO 前排最终序。
     *
     * @return 本次实际重排的线数
     */
    private static int applyFrontRowOrder() {
        int applied = 0;
        for (long prefix : FRONT_ROW_HIGH_PREFIXES) {
            UUID lineId = findLineIdByHighPrefix(prefix);
            if (lineId != null) {
                QuestLineDatabase.INSTANCE.setOrderIndex(lineId, applied);
                applied++;
            }
        }
        return applied;
    }

    /**
     * 在任务线库中查找高 32 位等于指定 pack 标识的线
     * （线 UUID 高位约定为 ASCII4&lt;&lt;32，如 gtswn=0x4754574E00000000）。
     *
     * @return 命中的线 UUID，库内不存在返回 null
     */
    private static UUID findLineIdByHighPrefix(long highPrefix) {
        for (UUID id : QuestLineDatabase.INSTANCE.keySet()) {
            if ((id.getMostSignificantBits() >>> 32) == highPrefix) {
                return id;
            }
        }
        return null;
    }

    /**
     * 从 classloader 资源读 JSON 并转为 NBTTagCompound。
     * <p>
     * 与 BQ 官方装载同构：{@code NBTConverter.JSONtoNBT_Object(json, tag, format=true)}
     * （"键:类型ID" 后缀在此剥离）。dev（classes 目录）与 prod（jar）行为一致。
     *
     * @param path 资源路径（相对 assets/gtswn/bqquests/ 的完整 classpath 路径）
     * @return NBT 化的定义，资源缺失或解析失败返回 null
     */
    private static NBTTagCompound readNbtResource(String path) {
        JsonObject json = readJsonResource(path);
        if (json == null) {
            return null;
        }
        return NBTConverter.JSONtoNBT_Object(json, new NBTTagCompound(), true);
    }

    /**
     * 从 classloader 资源读 JsonObject（Gson 用 MC 1.7.10 自带 com.google.gson，
     * 与 BQ JsonHelper.ReadFromFile 的解析方式对齐：UTF-8 + 32KB 缓冲）。
     *
     * @param path 资源路径
     * @return 解析结果，资源缺失或解析失败返回 null
     */
    private static JsonObject readJsonResource(String path) {
        InputStream is = BqQuestInjector.class.getClassLoader()
            .getResourceAsStream(path);
        if (is == null) {
            return null;
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8), 32768)) {
            return new JsonParser().parse(br)
                .getAsJsonObject();
        } catch (Exception e) {
            GTSimpleWirelessNetwork.LOG.error("[BQ] 资源 JSON 解析失败: {}", path, e);
            return null;
        }
    }
}
