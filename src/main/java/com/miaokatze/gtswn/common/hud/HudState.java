package com.miaokatze.gtswn.common.hud;

import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import com.miaokatze.gtswn.common.util.EUDataSet;
import com.miaokatze.gtswn.config.Config;

/**
 * 便携监测终端 HUD 状态值对象（O2-B10：原 {@code WirelessMonitorHUD} 的 11 个 static 可变态
 * 收编为实例态，由 {@link HudController} 唯一持有，消除跨类 static 写入面）。
 *
 * <p>
 * 显示资格、采样节拍与文本均属于当前客户端会话；历史只允许同连接同拥有者跨维度沿用。
 * </p>
 */
final class HudState {

    /** HUD 全局开关状态（默认关闭） */
    boolean hudEnabled = false;

    /** HUD 显示模式（0=关闭，1=常规计数，2=科学计数） */
    int displayMode = 0;

    ItemStack monitorStack = null;

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
     * 容量按 Config.hudUpdateInterval 动态（hudCapacityFor：6000t/间隔+1，默认 50t → 121 点，
     * 满载窗口恒 300s），FIFO 老化，
     * 内部使用 BigDecimal 精确计算 EU/t 斜率。原 static 单例 → HudState 实例单例：HUD 全局唯一。
     * </p>
     */
    EUDataSet dataSet = new EUDataSet(EUDataSet.hudCapacityFor(Config.hudUpdateInterval));
    int samplingInterval = Config.hudUpdateInterval;

    /** 缓存的 EU/t 文本 */
    String cachedEUTText = "";

    String cachedRealtimeEUTText = "";

    /** 上次更新的真实时间戳（毫秒），用于登出/重进期间的 gap 检测（getTotalWorldTime 登出不推进） */
    long lastUpdateRealTimeMs = 0L;

    /** 上次更新的时间戳（游戏 tick） */
    long lastUpdateTick = Long.MIN_VALUE / 2;

    /** 上次背包检查的时间戳（游戏 tick） */
    long lastInventoryCheckTick = Long.MIN_VALUE / 2;

    /** 缓存的无线电网能量值 */
    String cachedEUText = "§b" + StatCollector.translateToLocal("gtswn.hud.wireless.network")
        + ": §f0 §b"
        + StatCollector.translateToLocal("gtswn.hud.eu.unit");

    /** Only used while revalidating inventory after a world/player change on the same connection. */
    String historyOwnerUUID;

    /**
     * 清空所有缓存数据（用于玩家失去监视器、HUD 关闭等场景）。
     * <p>
     * 断开连接、切换账户或失去终端时清空；不修改任何物品 NBT。
     * </p>
     */
    void clearCache() {
        this.monitorStack = null;
        setCachedOwnerUUID(null);
        // 重置服务端同步缓存，避免跨存档/世界切换时残留旧值
        this.syncedEuStr = null;
        this.cachedEUText = "§b" + StatCollector.translateToLocal("gtswn.hud.wireless.network")
            + ": §f0 §b"
            + StatCollector.translateToLocal("gtswn.hud.eu.unit");
        this.cachedEUTText = "";
        this.cachedRealtimeEUTText = "";
        this.dataSet.clear(); // 清空数据集
        this.lastUpdateTick = Long.MIN_VALUE / 2;
        this.lastUpdateRealTimeMs = 0; // 重置真实时间戳
        this.lastInventoryCheckTick = Long.MIN_VALUE / 2;
        this.hudEnabled = false;
        this.displayMode = 0;
        this.historyOwnerUUID = null;
    }

    /**
     * Always revoke display eligibility and sampling deadlines. Only a still-open connection may
     * retain history while inventory is rechecked for the same owner in the next world.
     */
    void resetForSession(boolean preserveHistory) {
        EUDataSet history = this.dataSet;
        String historyOwner = cachedOwnerUUID != null ? cachedOwnerUUID : historyOwnerUUID;
        if (preserveHistory) this.dataSet = new EUDataSet(EUDataSet.hudCapacityFor(samplingInterval));
        clearCache();
        if (preserveHistory) {
            this.dataSet = history;
            this.historyOwnerUUID = historyOwner;
        }
    }

    void selectMonitor(ItemStack stack, String owner, int mode) {
        if (owner == null || owner.isEmpty() || mode <= 0) {
            clearCache();
            return;
        }
        String previous = cachedOwnerUUID != null ? cachedOwnerUUID : historyOwnerUUID;
        if (!owner.equals(previous)) clearCache();
        monitorStack = stack;
        setCachedOwnerUUID(owner);
        displayMode = mode;
        hudEnabled = parsedOwnerUuid != null;
        historyOwnerUUID = null;
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
