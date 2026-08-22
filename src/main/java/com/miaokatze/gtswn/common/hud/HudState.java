package com.miaokatze.gtswn.common.hud;

import java.util.UUID;

import net.minecraft.util.StatCollector;

import com.miaokatze.gtswn.common.util.EUDataSet;

/**
 * 便携监测终端 HUD 状态值对象（O2-B10：原 {@code WirelessMonitorHUD} 的 11 个 static 可变态
 * 收编为实例态，由 {@link HudController} 唯一持有，消除跨类 static 写入面）。
 *
 * <p>
 * 字段与字段级操作自 {@code WirelessMonitorHUD} 逐字搬迁，语义不变；
 * {@code clearCache} 的「世界切换不再调用、失去监视器/HUD 关闭时调用」口径随迁（见调用方注释）。
 * </p>
 */
final class HudState {

    /** HUD 全局开关状态（默认关闭） */
    boolean hudEnabled = false;

    /** HUD 显示模式（0=关闭，1=常规计数，2=科学计数） */
    int displayMode = 0;

    /** 缓存的拥有者 UUID（用于 HUD 显示） */
    String cachedOwnerUUID = null;

    /**
     * 缓存的拥有者 UUID 解析结果（O2-27：随 {@link #cachedOwnerUUID} 变更点同步更新，
     * 渲染路径读缓存，免每帧 UUID.fromString 字符串解析）。
     */
    UUID parsedOwnerUuid = null;

    /** 服务端同步过来的 EU 字符串（BigInteger.toString()），未收到响应前为 null（用于判断首次进入） */
    String syncedEuStr = null;

    /**
     * EU 测量数据集（替代原 measurementHistory 列表）。
     * <p>
     * 容量 61（0s 首检 + 60 次 100t 检测 = 300s），FIFO 老化，
     * 内部使用 BigDecimal 精确计算 EU/t 斜率。原 static 单例 → HudState 实例单例：HUD 全局唯一。
     * </p>
     */
    final EUDataSet dataSet = new EUDataSet();

    /** 缓存的 EU/t 文本 */
    String cachedEUTText = "";

    String cachedRealtimeEUTText = "";

    /** 上次更新的真实时间戳（毫秒），用于登出/重进期间的 gap 检测（getTotalWorldTime 登出不推进） */
    long lastUpdateRealTimeMs = 0L;

    /** 上次更新的时间戳（游戏 tick） */
    long lastUpdateTick = 0;

    /** 上次背包检查的时间戳（游戏 tick） */
    long lastInventoryCheckTick = 0;

    /** 缓存的无线电网能量值 */
    String cachedEUText = "§b" + StatCollector.translateToLocal("gtswn.hud.wireless.network")
        + ": §f0 §b"
        + StatCollector.translateToLocal("gtswn.hud.eu.unit");

    /** 当前世界 ID（用于检测世界切换） */
    int currentWorldId = -1;

    /**
     * 设置 HUD 显示状态。
     *
     * @param enabled   是否启用 HUD
     * @param ownerUUID 拥有者 UUID（可选）
     */
    void setEnabled(boolean enabled, String ownerUUID) {
        this.hudEnabled = enabled;
        if (ownerUUID != null && !ownerUUID.isEmpty()) {
            setCachedOwnerUUID(ownerUUID);
        }
    }

    /**
     * 设置 HUD 显示模式。
     *
     * @param mode 显示模式（0=关闭，1=常规计数，2=科学计数）
     */
    void setDisplayMode(int mode) {
        this.displayMode = mode;
        // 重置更新时间，强制下次渲染时立即更新
        this.lastUpdateTick = 0;
    }

    /**
     * 清空所有缓存数据（用于玩家失去监视器、HUD 关闭等场景）。
     * <p>
     * 注意：世界切换不再调用此方法（见 {@link #resetUiForWorldSwitch()}），
     * 用户确认世界切换时保留数据集以维持 EU/t 连续性。
     * </p>
     */
    void clearCache() {
        setCachedOwnerUUID(null);
        // 重置服务端同步缓存，避免跨存档/世界切换时残留旧值
        this.syncedEuStr = null;
        this.cachedEUText = "§b" + StatCollector.translateToLocal("gtswn.hud.wireless.network")
            + ": §f0 §b"
            + StatCollector.translateToLocal("gtswn.hud.eu.unit");
        this.cachedEUTText = "";
        this.cachedRealtimeEUTText = "";
        this.dataSet.clear(); // 清空数据集
        this.lastUpdateTick = 0; // 强制首次检测
        this.lastUpdateRealTimeMs = 0; // 重置真实时间戳
        this.lastInventoryCheckTick = 0;
        this.hudEnabled = false;
        this.displayMode = 0;
    }

    /**
     * 世界切换 UI 态重置（保留 {@link #dataSet}，用户确认维持 EU/t 连续性）：
     * 仅重置 syncedEuStr、cachedEUText、cachedEUTText、lastUpdateTick 等 UI 状态。
     */
    void resetUiForWorldSwitch() {
        // 保留 dataSet（用户确认），只重置 UI 状态
        this.syncedEuStr = null;
        this.cachedEUText = "§b" + StatCollector.translateToLocal("gtswn.hud.wireless.network")
            + ": §f... §b"
            + StatCollector.translateToLocal("gtswn.hud.eu.unit");
        this.cachedEUTText = "";
        this.cachedRealtimeEUTText = "";
        this.lastUpdateTick = 0; // 强制下次更新
    }

    /**
     * 更新缓存的拥有者 UUID 字符串及其解析结果（O2-27）。
     * <p>
     * {@link #cachedOwnerUUID} 的所有赋值点统一走本方法，保证
     * {@link #parsedOwnerUuid} 同步失效/重建；解析失败时解析结果置 null，
     * 渲染路径据此跳过（与原先每帧 try-catch UUID.fromString 的语义一致）。
     *
     * @param ownerUUID 新的拥有者 UUID 字符串（null/空时一并清空解析结果）
     */
    void setCachedOwnerUUID(String ownerUUID) {
        this.cachedOwnerUUID = ownerUUID;
        if (ownerUUID == null || ownerUUID.isEmpty()) {
            this.parsedOwnerUuid = null;
            return;
        }
        try {
            this.parsedOwnerUuid = UUID.fromString(ownerUUID);
        } catch (IllegalArgumentException e) {
            this.parsedOwnerUuid = null;
        }
    }
}
