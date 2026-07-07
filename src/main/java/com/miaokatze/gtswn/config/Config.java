package com.miaokatze.gtswn.config;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

/**
 * 模组配置管理类
 * <p>
 * 负责读取和保存模组的配置文件。配置统一放在 {@code config/gtswn/} 目录下：
 * <ul>
 * <li>{@code gtswn.cfg}：仅含 MTE ID 偏移等基础配置</li>
 * <li>{@code gtswn_network.cfg}：无线网络上下行损耗系数</li>
 * </ul>
 */
public class Config {

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

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }
}
