package com.miaokatze.gtswn.config;

import java.io.File;
import java.util.Arrays;

import net.minecraftforge.common.config.Configuration;

/**
 * 模组配置管理类
 * <p>
 * 负责读取和保存模组的配置文件。配置统一放在 {@code config/gtswn/} 目录下：
 * <ul>
 * <li>{@code gtswn.cfg}：仅含 MTE ID 偏移等基础配置</li>
 * <li>{@code gtswn_network.cfg}：无线网络上下行损耗系数 + HUD 显示参数</li>
 * </ul>
 * <p>
 * gtswn_network.cfg 结构：
 * 
 * <pre>
 * general {
 *     DownlinkLossEU / UplinkLossEU   // 上下行损耗
 * }
 * hud {
 *     HudXOffset / HudYOffset / HudScale  // HUD 偏移与缩放
 * }
 * </pre>
 */
public class Config {

    /** gtswn_network.cfg 中 HUD 参数的独立类目名（与 general 平级） */
    private static final String CATEGORY_HUD = "hud";

    // GregTech 元机器实体 (MTE) ID 分配的偏移量。
    // 注意：基准值 (BASE) 已在 MetaTileEntityID.java 中硬编码为 14600，以便按类型分段管理 ID。
    // 此配置仅用于在基准值基础上进行微调。
    public static int metaIdOffset = 0;

    // 下行损耗系数 / Downlink loss ratio
    // 能源覆盖板从无线网络取电时，机器收到的 EU 不变，电网按 (1 + 此值) 倍率扣除。
    // When energy cover draws EU from wireless network, machine receives full amount;
    // network deducts (1 + this value) × EU.
    // 默认 0.15 = 电网扣 1.15 倍 / Default 0.15 = network deducts 1.15×
    public static float downlinkLossEU = 0.15f;

    // 上行损耗系数 / Uplink loss ratio
    // 动力覆盖板向无线网络送电时，机器扣减的 EU 不变，电网实际增加量按 (1 - 此值) 倍率计算。
    // When dynamo cover outputs EU to wireless network, machine deducts full amount;
    // network receives (1 - this value) × EU.
    // 默认 0.0 = 无损耗；1.0 = 电网净增加为 0（上传不了任何 EU）
    // Default 0.0 = no loss; 1.0 = network receives nothing (cannot upload any EU)
    public static float uplinkLossEU = 0.0f;

    // HUD 水平偏移 / HUD horizontal offset
    // 正值 = HUD 向右移动，负值 = HUD 向左移动 / positive = shift right, negative = shift left
    // 默认 0 = 无偏移 / Default 0 = no offset
    public static int hudXOffset = 0;

    // HUD 垂直偏移 / HUD vertical offset
    // 正值 = HUD 向上移动，负值 = HUD 向下移动 / positive = shift up, negative = shift down
    // 注意：屏幕坐标系 Y 向下为正，此处正值语义为"上移"，与屏幕坐标相反，
    // 渲染时将通过减去该值反转坐标（hudY = baseY - hudYOffset）。
    // Note: screen Y axis points downward; positive here means "up" (opposite to screen coords),
    // renderer inverts it by subtraction (hudY = baseY - hudYOffset).
    // 默认 0 = 无偏移 / Default 0 = no offset
    public static int hudYOffset = 0;

    // HUD 缩放比例 / HUD scale ratio
    // 1.0 = 默认大小，>1.0 放大，<1.0 缩小 / 1.0 = default size, >1.0 enlarge, <1.0 shrink
    // 渲染时以 HUD 基准点为缩放原点，避免缩放后位置漂移。
    // Renderer uses HUD base point as scale origin to prevent position drift.
    // 默认 1.0 = 默认大小 / Default 1.0 = default size
    public static float hudScale = 1.0f;

    /**
     * 同步主配置文件 (gtswn.cfg)
     * <p>
     * 仅处理 MTE ID 偏移等基础配置项。从磁盘读取配置并更新静态变量，如果配置有变动则自动保存。
     *
     * @param configFile 配置文件对象 (config/gtswn/gtswn.cfg)
     */
    public static void synchronizeConfiguration(File configFile) {
        Configuration configuration = new Configuration(configFile);

        metaIdOffset = configuration.getInt(
            "metaIdOffset",
            Configuration.CATEGORY_GENERAL,
            metaIdOffset,
            -5000,
            5000,
            "应用于 MTE ID 基准值的偏移量 (用于预留 ID 区间)");

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }

    /**
     * 同步无线网络配置文件 (gtswn_network.cfg)
     * <p>
     * 处理无线覆盖板的上下行损耗系数。从磁盘读取配置并更新静态变量，如果配置有变动则自动保存。
     *
     * @param configFile 配置文件对象 (config/gtswn/gtswn_network.cfg)
     */
    public static void synchronizeNetworkConfiguration(File configFile) {
        Configuration configuration = new Configuration(configFile);

        // 下行损耗系数 / Downlink loss ratio
        // 能源覆盖板从无线网络取电时，机器收到的 EU 不变，电网按 (1 + 此值) 倍率扣除。
        // When energy cover draws EU from wireless network, machine receives full amount;
        // network deducts (1 + this value) × EU.
        // 默认 0.15 = 电网扣 1.15 倍 / Default 0.15 = network deducts 1.15×
        downlinkLossEU = configuration.getFloat(
            "DownlinkLossEU",
            Configuration.CATEGORY_GENERAL,
            downlinkLossEU,
            0.0f,
            10.0f,
            "下行损耗系数 / Downlink loss ratio\n" + "能源覆盖板从无线网络取电时，机器收到的 EU 不变，电网按 (1 + 此值) 倍率扣除。\n"
                + "When energy cover draws EU from wireless network, machine receives full amount; network deducts (1 + this value) × EU.\n"
                + "范围 0.0-10.0，默认 0.15 = 电网扣 1.15 倍 / Range 0.0-10.0, Default 0.15 = network deducts 1.15×");

        // 上行损耗系数 / Uplink loss ratio
        // 动力覆盖板向无线网络送电时，机器扣减的 EU 不变，电网实际增加量按 (1 - 此值) 倍率计算。
        // When dynamo cover outputs EU to wireless network, machine deducts full amount;
        // network receives (1 - this value) × EU.
        // 默认 0.0 = 无损耗；1.0 = 电网净增加为 0（上传不了任何 EU）
        // Default 0.0 = no loss; 1.0 = network receives nothing (cannot upload any EU)
        uplinkLossEU = configuration.getFloat(
            "UplinkLossEU",
            Configuration.CATEGORY_GENERAL,
            uplinkLossEU,
            0.0f,
            1.0f,
            "上行损耗系数 / Uplink loss ratio\n" + "动力覆盖板向无线网络送电时，机器扣减的 EU 不变，电网实际增加量按 (1 - 此值) 倍率计算。\n"
                + "When dynamo cover outputs EU to wireless network, machine deducts full amount; network receives (1 - this value) × EU.\n"
                + "默认 0.0 = 无损耗；1.0 = 电网净增加为 0（上传不了任何 EU） / Default 0.0 = no loss; 1.0 = network receives nothing");

        // === HUD 显示参数类目（独立顶层 hud 类目，与 general 平级） ===
        // 配置项：HudXOffset / HudYOffset / HudScale
        // 设置类目注释，说明本类目用途
        configuration.setCategoryComment(CATEGORY_HUD, "HUD 显示参数（偏移与缩放）\nHUD display parameters (offset & scale)");

        // HUD 水平偏移 / HUD horizontal offset
        // 正值 = HUD 向右移动，负值 = HUD 向左移动 / positive = shift right, negative = shift left
        hudXOffset = configuration.getInt(
            "HudXOffset",
            CATEGORY_HUD,
            hudXOffset,
            -500,
            500,
            "HUD 水平偏移（像素）/ HUD horizontal offset (pixels)\n"
                + "正值 = HUD 向右移动，负值 = HUD 向左移动 / positive = shift right, negative = shift left\n"
                + "默认 0 = 无偏移 / Default 0 = no offset");

        // HUD 垂直偏移 / HUD vertical offset
        // 正值 = HUD 向上移动，负值 = HUD 向下移动 / positive = shift up, negative = shift down
        // 注意：屏幕坐标 Y 向下为正，此处正值语义为"上移"，渲染时以减法反转（hudY = baseY - hudYOffset）。
        hudYOffset = configuration.getInt(
            "HudYOffset",
            CATEGORY_HUD,
            hudYOffset,
            -500,
            500,
            "HUD 垂直偏移（像素）/ HUD vertical offset (pixels)\n"
                + "正值 = HUD 向上移动，负值 = HUD 向下移动 / positive = shift up, negative = shift down\n"
                + "注意：屏幕坐标 Y 向下为正，此处正值语义为上移（与屏幕坐标相反），渲染时以减法反转。\n"
                + "Note: screen Y points down; positive here means up (inverted at render via subtraction).\n"
                + "默认 0 = 无偏移 / Default 0 = no offset");

        // HUD 缩放比例 / HUD scale ratio
        // 1.0 = 默认大小，>1.0 放大，<1.0 缩小 / 1.0 = default, >1.0 enlarge, <1.0 shrink
        hudScale = configuration.getFloat(
            "HudScale",
            CATEGORY_HUD,
            hudScale,
            0.2f,
            5.0f,
            "HUD 缩放比例 / HUD scale ratio\n"
                + "1.0 = 默认大小，>1.0 放大，<1.0 缩小 / 1.0 = default size, >1.0 enlarge, <1.0 shrink\n"
                + "渲染时以 HUD 基准点为缩放原点，避免位置漂移 / Renderer uses HUD base point as scale origin to prevent drift\n"
                + "范围 0.2-5.0，默认 1.0 / Range 0.2-5.0, Default 1.0");

        // 控制 hud 类目内 key 的显示顺序：X偏移 → Y偏移 → 缩放
        configuration.setCategoryPropertyOrder(CATEGORY_HUD, Arrays.asList("HudXOffset", "HudYOffset", "HudScale"));

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }
}
