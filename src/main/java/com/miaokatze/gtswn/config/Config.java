package com.miaokatze.gtswn.config;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

/**
 * 模组配置管理类
 * 负责读取和保存模组的配置文件 (config/gtswn.cfg)
 */
public class Config {

    // GregTech 元机器实体 (MTE) ID 分配的偏移量。
    // 注意：基准值 (BASE) 已在 MetaTileEntityID.java 中硬编码为 14600，以便按类型分段管理 ID。
    // 此配置仅用于在基准值基础上进行微调。
    public static int metaIdOffset = 0;

    // 无线网络监测开关，默认开启
    public static boolean wirelessNetworkMonitor = true;

    /**
     * 同步配置文件
     * 从磁盘读取配置并更新静态变量，如果配置有变动则自动保存
     * 
     * @param configFile 配置文件对象
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

        wirelessNetworkMonitor = configuration.getBoolean(
            "wirelessNetworkMonitor",
            Configuration.CATEGORY_GENERAL,
            wirelessNetworkMonitor,
            "是否开启无线网络监测，开启后会在聊天窗口输出电网能量变化 (默认 true)");

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }
}
