package com.miaokatze.gtswn.crossmod.bq;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.event.world.WorldEvent;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miaokatze.gtswn.Tags;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;
import com.miaokatze.miaokatzecore.api.bq.QuestDefinitionPreflight;
import com.miaokatze.miaokatzecore.api.bq.QuestPackPreflight;
import com.miaokatze.miaokatzecore.api.bq.QuestProgressRecovery;

import betterquesting.api.questing.IQuest;
import betterquesting.api.questing.IQuestLine;
import betterquesting.api.storage.BQ_Settings;
import betterquesting.api.utils.NBTConverter;
import betterquesting.handlers.SaveLoadHandler;
import betterquesting.questing.QuestDatabase;
import betterquesting.questing.QuestInstance;
import betterquesting.questing.QuestLine;
import betterquesting.questing.QuestLineDatabase;
import betterquesting.questing.QuestLineEntry;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

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
 *      <li>整包预检资源、UUID 与原生定义解析；失败不写库、不剪枝、不写成功戳</li>
 *      <li>任务与任务线幂等 get-or-create 装载（get(id)==null 才 new + readFromNBT）</li>
 *      <li>QuestLineEntry 挂线（未挂才 put）与 QuestLineDatabase.setOrderIndex（初排序，
 *      最终序由下方前排有序收尾统一覆盖）</li>
 *      <li>版本戳对账（世界目录 gtswn-injected.json vs Tags.VERSION）：不一致时执行
 *      「定义刷新」——快照进度→重读定义→merge 回填，完成/领取状态保留，
 *      挂线坐标同步替换；老世界无版本戳视为待升级刷一次。
 *      由此实现「新任务覆盖老任务」：发新版只需替换 jar，玩家免手动迁移</li>
 *      <li>仅摘除本包线上的过期本包任务；全部挂线后，无其他线引用才移出全局库</li>
 *      <li>进度回填：仅恢复本次重建的本包任务，先旧版 QuestProgress.json，后逐玩家
 *      QuestProgress 目录；已有任务的内存进度保持权威，不用磁盘旧值覆盖</li>
 *      <li>前排有序收尾（条款10）：MIAO 常量表 gtsr→gtswn→gtit 对库内存在线依序
 *      setOrderIndex(i) 覆盖为最终序，必须先于保存及原生登录同步执行</li>
 *      <li>标记定义需要保存；远程玩家由 BQ 原生登录同步接收定义与进度</li>
 *      </ol>
 */
public final class BqQuestInjector {

    /** jar 内任务清单资源路径（index 声明文件树，规避 1.7.10 jar 目录枚举坑） */
    private static final String INDEX_RESOURCE = "assets/gtswn/bqquests/index.json";

    /** 世界侧版本戳文件名（位于 BQ_Settings.curWorldDir 下），记录最近一次注入所用定义版本 */
    private static final String STAMP_FILE_NAME = "gtswn-injected.json";

    /** 本包任务 UUID 高 64 位（ASCII "GTWN" 后补零）；不回放或剪枝其他包。 */
    private static final long MANAGED_QUEST_HIGH = 0x4754574e00000000L;

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

    private static BqDefinitionJournal pendingCommit;
    private static File pendingWorld;
    private static boolean verifierRegistered;

    private BqQuestInjector() {}

    /** Runs after BQ's synchronous NORMAL World Save handler and Future waits, then reads actual bytes. */
    public static final class CommitVerifier {

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void saved(WorldEvent.Save event) {
            if (!event.world.isRemote && event.world.provider.dimensionId == 0) verifyCommit();
        }
    }

    private static void verifyCommit() {
        if (pendingCommit == null || pendingWorld == null || !pendingWorld.equals(BQ_Settings.curWorldDir)) return;
        try {
            pendingCommit.acceptLiveChanges(liveDefinitions());
            File database = new File(pendingWorld, "QuestDatabase.json");
            if (!database.isFile()) return;
            if (database.length() > 64L * 1024 * 1024) throw new java.io.IOException("Oversized BQ database");
            try (Reader reader = Files.newBufferedReader(database.toPath(), StandardCharsets.UTF_8)) {
                NBTTagCompound disk = NBTConverter.JSONtoNBT_Object(
                    new JsonParser().parse(reader)
                        .getAsJsonObject(),
                    new NBTTagCompound(),
                    true);
                if (!pendingCommit.matches(snapshot(disk))) {
                    GTSimpleWirelessNetwork.LOG
                        .warn("[BQ] Definition commit PENDING: database bytes differ; retry on save/startup");
                    return;
                }
            }
            BqStampFile.write(new File(pendingWorld, STAMP_FILE_NAME), pendingCommit.version());
            pendingCommit.committed();
            GTSimpleWirelessNetwork.LOG
                .info("[BQ] Verified definition commit; protected editor conflicts={}", pendingCommit.conflicts());
            pendingCommit = null;
            pendingWorld = null;
        } catch (Exception failure) {
            GTSimpleWirelessNetwork.LOG
                .warn("[BQ] Definition commit PENDING; retain journal for restart repair", failure);
        }
    }

    private static String questKey(UUID id) {
        return "q:" + id;
    }

    private static String entryKey(UUID line, UUID quest) {
        return "e:" + line + ":" + quest;
    }

    private static JsonObject definition(NBTTagCompound tag) {
        NBTTagCompound copy = (NBTTagCompound) tag.copy();
        copy.removeTag("questIDHigh");
        copy.removeTag("questIDLow");
        return NBTConverter.NBTtoJSON_Compound(copy, new JsonObject(), true);
    }

    private static JsonObject snapshot(NBTTagCompound database) {
        JsonObject result = new JsonObject();
        NBTTagList quests = database.getTagList("questDatabase", 10);
        for (int i = 0; i < quests.tagCount(); i++) {
            NBTTagCompound quest = quests.getCompoundTagAt(i);
            UUID id = requireId(quest, "questID");
            if (id.getMostSignificantBits() == MANAGED_QUEST_HIGH) result.add(questKey(id), definition(quest));
        }
        NBTTagList lines = database.getTagList("questLines", 10);
        for (int i = 0; i < lines.tagCount(); i++) {
            NBTTagCompound line = lines.getCompoundTagAt(i);
            UUID lineId = requireId(line, "questLineID");
            if (lineId.getMostSignificantBits() != MANAGED_QUEST_HIGH) continue;
            NBTTagList entries = line.getTagList("quests", 10);
            for (int j = 0; j < entries.tagCount(); j++) {
                NBTTagCompound entry = entries.getCompoundTagAt(j);
                UUID id = requireId(entry, "questID");
                if (id.getMostSignificantBits() == MANAGED_QUEST_HIGH)
                    result.add(entryKey(lineId, id), definition(entry));
            }
        }
        return result;
    }

    private static JsonObject liveDefinitions() {
        NBTTagCompound database = new NBTTagCompound();
        database.setTag("questDatabase", QuestDatabase.INSTANCE.writeToNBT(new NBTTagList(), null));
        database.setTag("questLines", QuestLineDatabase.INSTANCE.writeToNBT(new NBTTagList(), null));
        return snapshot(database);
    }

    private static JsonObject desiredDefinitions(JsonArray lines, Map<String, NBTTagCompound> resources,
        JsonObject current) {
        JsonObject desired = new JsonObject();
        for (Map.Entry<String, com.google.gson.JsonElement> entry : current.entrySet())
            desired.add(entry.getKey(), JsonNull.INSTANCE);
        for (int i = 0; i < lines.size(); i++) {
            JsonObject spec = lines.get(i)
                .getAsJsonObject();
            UUID lineId = new UUID(
                spec.get("idHigh")
                    .getAsLong(),
                spec.get("idLow")
                    .getAsLong());
            JsonArray entries = spec.getAsJsonArray("entries");
            for (int j = 0; j < entries.size(); j++) {
                JsonObject entry = entries.get(j)
                    .getAsJsonObject();
                NBTTagCompound tag = resources.get(
                    entry.get("questFile")
                        .getAsString());
                UUID id = requireId(tag, "questID");
                if (id.getMostSignificantBits() != MANAGED_QUEST_HIGH) continue;
                QuestInstance detached = new QuestInstance();
                detached.readFromNBT(tag);
                desired.add(questKey(id), definition(detached.writeToNBT(new NBTTagCompound())));
                if (lineId.getMostSignificantBits() == MANAGED_QUEST_HIGH) desired.add(
                    entryKey(lineId, id),
                    definition(
                        new QuestLineEntry(
                            resources.get(
                                entry.get("entryFile")
                                    .getAsString())).writeToNBT(new NBTTagCompound())));
            }
        }
        return desired;
    }

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
            Map<String, NBTTagCompound> resources = prepareResources(lines, BqQuestInjector::readNbtResource);
            Set<UUID> pruneCandidates = new HashSet<>();
            int questCount = 0;
            int skippedCount = 0;
            int refreshedCount = 0;
            int prunedCount = 0;
            // 版本戳对账：版本变化（含老世界无戳）→ 刷新全部已存在任务的定义与挂线坐标
            boolean refresh = isDefinitionRefreshNeeded();
            JsonObject current = liveDefinitions();
            BqDefinitionJournal journal = BqDefinitionJournal.prepare(
                new File(BQ_Settings.curWorldDir, "gtswn-definition-commit.json").toPath(),
                Tags.VERSION,
                refresh,
                current,
                desiredDefinitions(lines, resources, current));
            Set<UUID> recreatedOwnedQuests = new HashSet<>();
            for (int i = 0; i < lines.size(); i++) {
                int[] r = loadQuestLine(
                    lines.get(i)
                        .getAsJsonObject(),
                    journal,
                    resources,
                    pruneCandidates,
                    recreatedOwnedQuests);
                questCount += r[0];
                skippedCount += r[4];
                refreshedCount += r[1];
                prunedCount += r[2];
            }
            for (UUID id : pruneCandidates) {
                boolean referenced = QuestLineDatabase.INSTANCE.values()
                    .stream()
                    .anyMatch(line -> line.get(id) != null);
                if (!referenced && (journal == null || journal.shouldApply(questKey(id))))
                    QuestDatabase.INSTANCE.remove(id);
            }
            QuestProgressRecovery.Summary recovery = QuestProgressRecovery
                .restore(BQ_Settings.curWorldDir, recreatedOwnedQuests);
            GTSimpleWirelessNetwork.LOG.info("[BQ] Progress recovery: {}", recovery);
            // 前排有序收尾（规范 §6 条款10）：覆盖装载阶段的初排序为 MIAO 前排最终序；
            // 登录前确定顺序，远程玩家通过 BQ 原生登录同步接收 order 列表。
            int frontRowCount = applyFrontRowOrder();
            if (journal != null) {
                journal.expect(liveDefinitions());
                pendingCommit = journal;
                pendingWorld = BQ_Settings.curWorldDir;
                if (!verifierRegistered) {
                    MinecraftForge.EVENT_BUS.register(new CommitVerifier());
                    verifierRegistered = true;
                }
                GTSimpleWirelessNetwork.LOG
                    .info("[BQ] Definition commit PENDING; protected editor conflicts={}", journal.conflicts());
            }
            // 保持原生登录同步；定义保存仍由 BQ World Save 生命周期执行。
            // 启动时不排队广播：迟到的全书定义包会清空单机共享任务库的进度。
            // 本机直接共享数据库，远程玩家由 BQ 原生登录同步接收定义与进度。
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

    /** Preflight custom packs as well as the bundled pack; an explicitly empty entries list is valid. */
    static Map<String, NBTTagCompound> prepareResources(JsonArray lines, Function<String, NBTTagCompound> reader) {
        return QuestPackPreflight.prepareResources(lines, reader);
    }

    private static UUID requireId(NBTTagCompound tag, String prefix) {
        // Native BQ readId defaults absent or wrongly typed halves to zero.
        if (!tag.hasKey(prefix + "High", Constants.NBT.TAG_LONG)
            || !tag.hasKey(prefix + "Low", Constants.NBT.TAG_LONG)) {
            throw new IllegalStateException("Missing/invalid BQ identity: " + prefix);
        }
        return new UUID(tag.getLong(prefix + "High"), tag.getLong(prefix + "Low"));
    }

    /**
     * Load a preflighted line. Only owned lines/quests can be refreshed or pruned; defer global
     * deletion until all declared lines are loaded, then preserve every surviving line reference.
     *
     * @return created quests, refreshed quests, detached entries, created lines, skipped quests
     */
    private static int[] loadQuestLine(JsonObject lineSpec, BqDefinitionJournal journal,
        Map<String, NBTTagCompound> resources, Set<UUID> pruneCandidates, Set<UUID> progressTargets) {
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
            NBTTagCompound lineTag = resources.get(
                lineSpec.get("lineFile")
                    .getAsString());
            line = new QuestLine();
            line.readFromNBT(lineTag);
            QuestLineDatabase.INSTANCE.put(lineId, line);
            lineCreated = true;
        }
        // Existing foreign/custom lines are get-or-create only; do not reorder another pack.
        boolean managedLine = lineId.getMostSignificantBits() == MANAGED_QUEST_HIGH;
        if (lineCreated || managedLine) QuestLineDatabase.INSTANCE.setOrderIndex(lineId, orderIndex);

        int created = 0;
        int refreshed = 0;
        int skipped = 0;
        Set<UUID> expected = new HashSet<>();
        JsonArray entries = lineSpec.getAsJsonArray("entries");
        for (int i = 0; i < entries.size(); i++) {
            JsonObject entry = entries.get(i)
                .getAsJsonObject();
            NBTTagCompound questTag = resources.get(
                entry.get("questFile")
                    .getAsString());
            UUID questId = requireId(questTag, "questID");
            expected.add(questId);
            IQuest existing = QuestDatabase.INSTANCE.get(questId);
            if (existing == null) {
                // Only missing definitions lose their progress during BQ's default reload.
                // Existing quests already have authoritative in-memory progress (including
                // resets/claims made by earlier startup handlers). Never replay older disk
                // records over that state, even when the UUID belongs to this pack.
                if (questId.getMostSignificantBits() == MANAGED_QUEST_HIGH) progressTargets.add(questId);
                IQuest quest = new QuestInstance();
                quest.readFromNBT(questTag);
                QuestDatabase.INSTANCE.put(questId, quest);
                created++;
            } else if (journal != null && journal.shouldApply(questKey(questId))
                && questId.getMostSignificantBits() == MANAGED_QUEST_HIGH) {
                    // 版本升级：重读定义（名称/描述/前置/奖励/任务），玩家进度保留
                    refreshDefinition(existing, questTag);
                    refreshed++;
                } else {
                    // 已存在且无需刷新：幂等跳过（用于对账日志判读库是否被第三方整库重载）
                    skipped++;
                }
            // 任务必须挂线（/bq_admin purge_hidden_quests 会清未挂线任务）：未挂才 put；
            // 已挂线且刷新中则替换条目以同步编辑器坐标
            if (line.get(questId) == null || (journal != null && journal.shouldApply(entryKey(lineId, questId))
                && managedLine
                && questId.getMostSignificantBits() == MANAGED_QUEST_HIGH)) {
                NBTTagCompound entryTag = resources.get(
                    entry.get("entryFile")
                        .getAsString());
                line.put(questId, new QuestLineEntry(entryTag));
            }
        }
        // Only remove owned stale entries. Foreign editor-added quests and quests shared with
        // another line must survive; global deletion waits until all declared lines are loaded.
        int pruned = 0;
        List<UUID> stale = line.orderedEntries()
            .map(Map.Entry::getKey)
            .filter(
                id -> managedLine && id.getMostSignificantBits() == MANAGED_QUEST_HIGH
                    && !expected.contains(id)
                    && (journal == null || journal.shouldApply(entryKey(lineId, id))))
            .collect(Collectors.toList());
        for (UUID id : stale) {
            line.remove(id);
            pruneCandidates.add(id);
            pruned++;
        }
        if (pruned > 0) {
            GTSimpleWirelessNetwork.LOG.info("[BQ] 任务线 {} 清理已删除任务 {} 个: {}", lineId, pruned, stale);
        }
        if (lineCreated || created > 0 || refreshed > 0) {
            GTSimpleWirelessNetwork.LOG
                .info("[BQ] 任务线 {} 装载：线新建={}，新任务={}，刷新定义={}", lineId, lineCreated, created, refreshed);
        }
        return new int[] { created, refreshed, pruned, lineCreated ? 1 : 0, skipped };
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
     * 完成戳由 CommitVerifier 在真实数据库落盘校验后发布；写入失败保留 PENDING。
     */

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
        QuestDefinitionPreflight.validateJsonIds(json);
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
