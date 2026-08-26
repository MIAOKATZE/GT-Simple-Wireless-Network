package com.miaokatze.gtswn.crossmod.bq;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miaokatze.gtswn.Tags;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import betterquesting.api.questing.IQuest;
import betterquesting.api.questing.IQuestLine;
import betterquesting.api.storage.BQ_Settings;
import betterquesting.api.utils.JsonHelper;
import betterquesting.api.utils.NBTConverter;
import betterquesting.handlers.SaveLoadHandler;
import betterquesting.network.handlers.NetChapterSync;
import betterquesting.network.handlers.NetQuestSync;
import betterquesting.network.handlers.NetSettingSync;
import betterquesting.questing.QuestDatabase;
import betterquesting.questing.QuestInstance;
import betterquesting.questing.QuestLine;
import betterquesting.questing.QuestLineDatabase;
import betterquesting.questing.QuestLineEntry;
import cpw.mods.fml.common.Loader;

/**
 * BetterQuesting 运行时任务注入器（任务注入 PoC）。
 * <p>
 * 本 mod 内全部 betterquesting 类型引用都收敛在这一个类中，
 * 且只在 {@link BqCompat#isBqLoaded()} 为 true 时才会被类加载
 * （BQ 缺席环境下本类永不加载，无需 @Optional 字节码剥离）。
 * <p>
 * 挂载点：CommonProxy.serverStarting。@Mod 声明 after:betterquesting
 * 保证 BQ 的 default load（clear 后从 config 重载）已同步完成，
 * 本注入器在其后做幂等追加，不会被清库。
 * <p>
 * 流程（照抄 BQ 官方装载路径 QuestCommandDefaults.load 的同构形状）：
 * <ol>
 * <li>读 assets/gtswn/bqquests/index.json 清单（规避 1.7.10 jar 目录枚举）</li>
 * <li>逐文件 Gson 解析 → NBTConverter.JSONtoNBT_Object(format=true)</li>
 * <li>任务与任务线幂等 get-or-create 装载（get(id)==null 才 new + readFromNBT）</li>
 * <li>QuestLineEntry 挂线（未挂才 put）与 QuestLineDatabase.setOrderIndex</li>
 * <li>版本戳对账（世界目录 gtswn-injected.json vs Tags.VERSION）：不一致时执行
 * 「定义刷新」——快照进度→重读定义→merge 回填，完成/领取状态保留，
 * 挂线坐标同步替换；老世界无版本戳视为待升级刷一次。
 * 由此实现「新任务覆盖老任务」：发新版只需替换 jar，玩家免手动迁移</li>
 * <li>剪枝（无条件执行）：线上存在但 index.json 清单中已删除的任务，摘线并移出
 * 任务数据库——删除操作同样自动传播到所有世界</li>
 * <li>进度回填：QuestProgress 目录逐玩家 merge=true 重放（对抗 default load
 * 对"库内不存在任务"进度的静默丢弃，见设计文档第 2 节）</li>
 * <li>同步四连（NetSettingSync / NetQuestSync.quickSync / NetChapterSync / markDirty）</li>
 * </ol>
 */
public final class BqQuestInjector {

    /** jar 内任务清单资源路径（index 声明文件树，规避 1.7.10 jar 目录枚举坑） */
    private static final String INDEX_RESOURCE = "assets/gtswn/bqquests/index.json";

    /** 世界侧版本戳文件名（位于 BQ_Settings.curWorldDir 下），记录最近一次注入所用定义版本 */
    private static final String STAMP_FILE_NAME = "gtswn-injected.json";

    private BqQuestInjector() {}

    /**
     * 任务注入入口（serverStarting，BQ default load 之后）。
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
            int refreshedCount = 0;
            int prunedCount = 0;
            // 版本戳对账：版本变化（含老世界无戳）→ 刷新全部已存在任务的定义与挂线坐标
            boolean refresh = isDefinitionRefreshNeeded();
            for (int i = 0; i < lines.size(); i++) {
                int[] r = loadQuestLine(
                    lines.get(i)
                        .getAsJsonObject(),
                    refresh);
                questCount += r[0];
                refreshedCount += r[1];
                prunedCount += r[2];
            }
            restoreProgress();
            if (refresh) {
                writeStamp(Tags.VERSION);
            }
            // 同步四连（与 QuestCommandDefaults.load 尾部同款）
            NetSettingSync.sendSync(null);
            NetQuestSync.quickSync(null, true, true);
            NetChapterSync.sendSync(null, null);
            SaveLoadHandler.INSTANCE.markDirty();
            GTSimpleWirelessNetwork.LOG.info(
                "[BQ] 任务注入完成：{} 条任务线，{} 个新任务，{} 个定义刷新{}，{} 个已删除任务清理",
                lines.size(),
                questCount,
                refreshedCount,
                refresh ? "（对齐版本 " + Tags.VERSION + "）" : "",
                prunedCount);
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
     * @param lineSpec index.json 中该线的声明对象
     * @param refresh  是否对已存在任务执行定义刷新
     * @return int[]{本次新建任务数, 本次刷新定义任务数, 本次剪枝删除任务数}
     */
    private static int[] loadQuestLine(JsonObject lineSpec, boolean refresh) {
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
                return new int[] { 0, 0, 0 };
            }
            line = new QuestLine();
            line.readFromNBT(lineTag);
            QuestLineDatabase.INSTANCE.put(lineId, line);
            lineCreated = true;
        }
        // setOrderIndex 幂等（仅重排 lineOrder 列表），无条件执行
        QuestLineDatabase.INSTANCE.setOrderIndex(lineId, orderIndex);

        int created = 0;
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
                created++;
            } else if (refresh) {
                // 版本升级：重读定义（名称/描述/前置/奖励/任务），玩家进度保留
                refreshDefinition(existing, questTag);
                refreshed++;
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
        return new int[] { created, refreshed, pruned };
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
        JsonObject o = new JsonObject();
        o.addProperty("version", version);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            new Gson().toJson(o, w);
        } catch (Exception e) {
            GTSimpleWirelessNetwork.LOG.warn("[BQ] 版本戳写入失败（下次启动可能重复刷新一次定义）", e);
        }
    }

    /**
     * 进度回填（设计文档第 2 节缓解措施）。
     * <p>
     * default load 的进度快照恢复只对"库内存在"的任务生效，本 mod 任务
     * 在其 clear→重载窗口内不在库中，进度被静默丢弃；此处注册完成后
     * （早于任何玩家登录）从 QuestProgress 目录逐玩家 merge=true 重放。
     * merge 幂等：config 任务内存进度与磁盘同源同值，注入任务刚注册无内存进度。
     * 不调用 savePlayerProgress/markDirtyPlayers，也不再 quickSync（无在线玩家）。
     */
    private static void restoreProgress() {
        File dir = new File(BQ_Settings.curWorldDir, "QuestProgress");
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (!f.getName()
                .endsWith(".json")) {
                continue;
            }
            try {
                NBTTagCompound nbt = NBTConverter
                    .JSONtoNBT_Object(JsonHelper.ReadFromFile(f), new NBTTagCompound(), true);
                QuestDatabase.INSTANCE
                    .readProgressFromNBT(nbt.getTagList("questProgress", Constants.NBT.TAG_COMPOUND), true);
            } catch (Throwable t) {
                GTSimpleWirelessNetwork.LOG.warn("[BQ] 进度回填跳过无法解析的文件: {}", f.getName(), t);
            }
        }
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
            GTSimpleWirelessNetwork.LOG.warn("[BQ] 资源不存在: {}", path);
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
