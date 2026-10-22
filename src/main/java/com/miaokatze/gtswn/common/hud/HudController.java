package com.miaokatze.gtswn.common.hud;

import java.math.BigInteger;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import com.miaokatze.gtswn.common.charging.MonitorBattery;
import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;
import com.miaokatze.gtswn.common.util.FormatUtil;
import com.miaokatze.gtswn.common.util.GTTierUtil;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketRequestWirelessEU;

import baubles.api.BaublesApi;

/**
 * 便携监测终端 HUD 业务控制器（O2-B10 / O2-B03-6：原 {@code WirelessMonitorHUD.onRenderOverlay}
 * 的 ①世界切换状态机 / ②背包复合扫描 / ③开关与 gap 检测 / ④数据集更新四段自渲染路径迁出，
 * 渲染方法只留 GL 绘制）。
 *
 * <p>
 * 持有 {@link HudState}（原 11 个 static 态实例化）；唯一实例持有点 = ClientProxy 注册处
 * （渲染器经构造注入共享同一实例）。对监视器 NBT 的读取经
 * {@link PortableWirelessNetworkMonitor} 语义门面，不再直读键名布局。
 * </p>
 */
public final class HudController {

    /** 背包遍历间隔（ticks），每 20 ticks（1 秒）检查一次 */
    private static final int INVENTORY_CHECK_INTERVAL = 20;

    private final HudState state = new HudState();
    private final HudSession session = new HudSession();
    private boolean engineeringMode;

    public HudSession.Snapshot captureSession(Object world, Object player, Object connection) {
        return session.capture(world, player, connection);
    }

    /** Main-thread lifecycle boundary; connection changes clear history, world changes revalidate its owner. */
    public boolean synchronizeSession(Object world, Object player, Object connection) {
        boolean preserveHistory = session.sameConnection(connection);
        if (!session.change(world, player, connection)) return false;
        state.resetForSession(preserveHistory);
        return true;
    }

    public boolean unloadWorld(Object world) {
        return session.ownsWorld(world) && synchronizeSession(null, null, session.connection());
    }

    public boolean disconnect(Object connection) {
        return session.sameConnection(connection) && synchronizeSession(null, null, null);
    }

    /**
     * 每渲染帧驱动（原 onRenderOverlay ①-④ 段逐字搬迁，tick 口径不变：
     * 背包扫描 20t、数据集更新按配置（默认 50t）、gap 检测 10s 真实时间）。
     *
     * @param player 客户端玩家实体
     * @return true = 继续 GL 绘制；false = 本帧不渲染
     */
    public boolean updateForFrame(EntityPlayer player) {
        Minecraft mc = Minecraft.getMinecraft();
        Object connection = mc.getNetHandler() == null ? null
            : mc.getNetHandler()
                .getNetworkManager();
        synchronizeSession(mc.theWorld, player, connection);
        if (mc.theWorld == null || player == null || player.worldObj != mc.theWorld || connection == null) return false;
        if (state.samplingInterval != Config.hudUpdateInterval) {
            state.samplingInterval = Config.hudUpdateInterval;
            state.dataSet = new com.miaokatze.gtswn.common.util.EUDataSet(
                com.miaokatze.gtswn.common.util.EUDataSet.hudCapacityFor(Config.hudUpdateInterval));
            state.syncedEuStr = null;
            state.lastUpdateTick = Long.MIN_VALUE / 2;
        }

        // 获取世界时间
        long currentTick = mc.theWorld.getTotalWorldTime();
        boolean engineering = com.miaokatze.gtswn.client.engineering.EngineeringModeController.isActive();

        // ② 每 INVENTORY_CHECK_INTERVAL ticks 检查一次背包（在 hudEnabled 检查之前执行）
        if (engineering != engineeringMode || currentTick < state.lastInventoryCheckTick
            || currentTick - state.lastInventoryCheckTick >= INVENTORY_CHECK_INTERVAL) {
            engineeringMode = engineering;
            // 工程模式优先饰品，其次主背包槽序；常规模式保留手持优先。
            MonitorScanResult scan = scanMonitorInInventory(player);
            String newOwnerUUID = scan.ownerUUID;
            boolean selectionChanged = newOwnerUUID == null || !newOwnerUUID.equals(state.cachedOwnerUUID)
                || state.displayMode != scan.hudMode;
            if (selectionChanged) {
                session.invalidateRequest();
                state.lastUpdateTick = Long.MIN_VALUE / 2;
            }
            state.selectMonitor(scan.stack, newOwnerUUID, scan.hudMode);
            state.lastInventoryCheckTick = currentTick;
        }

        // ③ 检查 HUD 是否启用（在背包检查之后）
        if (!state.hudEnabled) {
            return false;
        }
        com.miaokatze.gtswn.client.PortableMonitorClientCache.poll(state.monitorStack);

        // 如果找不到监测终端，不显示 HUD
        if (state.cachedOwnerUUID == null || state.cachedOwnerUUID.isEmpty()) {
            return false;
        }

        // 拥有者 UUID（O2-27：读缓存解析结果，非法/未解析时与原 try-catch return 语义一致）
        UUID uuid = state.parsedOwnerUuid;
        if (uuid == null) {
            return false;
        }

        // 真实时间 gap 检测（登出期间 tick 不推进，用真实时间检测）
        // 说明：getTotalWorldTime() 登出期间不推进，无法检测登出时长；
        // System.currentTimeMillis() 真实时间，登出期间持续推进；
        // 阈值 10000ms = 10秒，与 MTE 的 200L ticks = 10s 对齐；
        // 短时卡顿（<10s）豁免，保留数据集
        long currentRealTimeMs = System.currentTimeMillis();
        if (state.lastUpdateRealTimeMs > 0 && currentRealTimeMs - state.lastUpdateRealTimeMs > 10000L) {
            // 长时重载/退出重进（>10秒真实时间）：清空数据集，强制首次检测
            state.dataSet.clear();
            state.lastUpdateTick = Long.MIN_VALUE / 2;
        }
        state.lastUpdateRealTimeMs = currentRealTimeMs;

        // ④ 每 Config.hudUpdateInterval ticks 更新一次缓存（默认 50t=2.5s，可配置 1-600t），
        // 仅便携 HUD，MTE/信息屏不受影响（固定 100t）
        if (currentTick - state.lastUpdateTick >= Config.hudUpdateInterval) {
            updateCache(currentTick, uuid);
        }
        return true;
    }

    /**
     * 应用物品侧的 HUD 模式切换意图（O2-B10：原 {@code WirelessMonitorHUD.setEnabled(mode > 0, owner)}
     * + {@code setDisplayMode(mode)} 两步静态调用的单入口替代，经 @SidedProxy 由 ClientProxy 转发；
     * 同 tick 执行，模式切换即时生效语义不变）。
     *
     * @param newMode   新 HUD 显示模式（0=关闭，1=常规计数，2=科学计数）
     * @param ownerUUID 拥有者 UUID；无有效拥有者时撤销 HUD 显示资格
     */
    public void applyHudToggle(int newMode, String ownerUUID) {
        session.invalidateRequest();
        state.selectMonitor(state.monitorStack, ownerUUID, newMode);
        state.lastInventoryCheckTick = Long.MIN_VALUE / 2;
        state.lastUpdateTick = Long.MIN_VALUE / 2;
    }

    /** 渲染只读：EU 总量文本（原 cachedEUText 静态字段读点收口为门面） */
    public String euText() {
        return Config.hudCompact ? compact(state.cachedEUText) : state.cachedEUText;
    }

    /** 渲染只读：EU/t 状态文本 */
    public String eutText() {
        return displayPower(state.cachedEUTText);
    }

    /** 渲染只读：实时 EU/t 状态文本 */
    public String realtimeEutText() {
        return displayPower(state.cachedRealtimeEUTText);
    }

    private static String compact(String value) {
        return value.replace(StatCollector.translateToLocal("gtswn.hud.wireless.network"), "EU")
            .replace(StatCollector.translateToLocal("gtswn.hud.network.status"), "\u0394")
            .replace(StatCollector.translateToLocal("gtswn.hud.network.realtime_status"), "\u0394t");
    }

    private static String displayPower(String value) {
        if (!Config.hudTierEnabled) {
            int bracket = value.lastIndexOf('(');
            if (bracket >= 0 && !value.contains(StatCollector.translateToLocal("gtswn.hud.network.status.silent"))
                && !value.contains(StatCollector.translateToLocal("gtswn.hud.network.status.long_silent"))
                && !value.contains(StatCollector.translateToLocal("gtswn.hud.network.status.lt1"))) {
                value = value.substring(0, bracket);
            }
        }
        return Config.hudCompact ? compact(value) : value;
    }

    public ItemStack monitorStack() {
        return state.monitorStack;
    }

    public boolean hasBattery() {
        return state.monitorStack != null && MonitorBattery.hasBattery(state.monitorStack);
    }

    public String chargeText() {
        String status;
        switch (MonitorBattery.getStatus(state.monitorStack)) {
            case 2:
                status = "charging";
                break;
            case 3:
                status = "complete";
                break;
            case 1:
                status = "idle";
                break;
            default:
                status = "empty";
                break;
        }
        return "\u00A7b" + StatCollector.translateToLocal("gtswn.hud.charge.label")
            + ": \u00A7f"
            + StatCollector.translateToLocal("gtswn.hud.charge." + status);
    }

    /**
     * 单次背包扫描的复合结果（合并原三重同构扫描，见《全局调查-优化建议》OPT-7）。
     * <p>
     * 主手 → Baubles 饰品栏 → 主背包一次遍历，返回第一个「已绑定」便携监测终端的
     * 拥有者 UUID 与 HUD 模式；未找到已绑定终端时 ownerUUID 为 null、hudMode 为 0。
     * <p>
     * 保留终端栈供充电状态读取，扫描时随背包同步更新引用。
     */
    private static final class MonitorScanResult {

        /** 未找到已绑定监测终端时的空结果（hudMode=0 与既有默认语义一致） */
        static final MonitorScanResult NONE = new MonitorScanResult(null, 0, null);

        final ItemStack stack;

        /** 拥有者 UUID 字符串（仅已绑定时非 null） */
        final String ownerUUID;

        /** 监测终端 NBT 中的 HUD 显示模式（0=关闭，1=常规计数，2=科学计数） */
        final int hudMode;

        MonitorScanResult(String ownerUUID, int hudMode, ItemStack stack) {
            this.ownerUUID = ownerUUID;
            this.hudMode = hudMode;
            this.stack = stack;
        }
    }

    /**
     * 单次遍历背包查找「已绑定」的便携监测终端（常规模式手持优先；工程模式饰品 → 主背包槽序）。
     * <p>
     * 合并原 findMonitorInInventory / findMonitorStackInInventory / getHUDModeFromInventory
     * 三重同构扫描（每 20t 最多三遍背包遍历 → 一次遍历返回复合结果，调用方各取所需）；
     * HUD 模式与拥有者提取统一先过物品侧语义门面，与 owner 判定口径一致（BUG-8）。
     *
     * @param player 玩家实体
     * @return 第一个已绑定监测终端的复合结果；未找到返回 {@link MonitorScanResult#NONE}
     */
    private MonitorScanResult scanMonitorInInventory(EntityPlayer player) {
        MonitorScanResult result;
        if (!engineeringMode) {
            result = inspectMonitorStack(player.getHeldItem());
            if (result != null) return result;
        }

        // --- 饰品栏扫描（Baubles 不存在时安全降级） ---
        try {
            IInventory baubles = BaublesApi.getBaubles(player);
            if (baubles != null) {
                for (int i = 0; i < baubles.getSizeInventory(); i++) {
                    result = inspectMonitorStack(baubles.getStackInSlot(i));
                    if (result != null) {
                        return result;
                    }
                }
            }
        } catch (NoClassDefFoundError ignored) {
            // Baubles 未安装，跳过饰品栏扫描
        }

        // 遍历背包槽位（0-35）
        for (int i = 0; i < player.inventory.mainInventory.length; i++) {
            result = inspectMonitorStack(player.inventory.mainInventory[i]);
            if (result != null) {
                return result;
            }
        }

        return MonitorScanResult.NONE;
    }

    /**
     * 检查单个槽位：是「已绑定」的便携监测终端则打包复合结果（拥有者 UUID + HUD 模式），
     * 否则返回 null 继续扫描后续槽位（未绑定监视器不参与 HUD 模式判定，BUG-8）。
     * <p>
     * O2-B10：绑定判定与 NBT 读取改经 {@link PortableWirelessNetworkMonitor} 物品侧语义门面，
     * HUD 侧不再直读监视器 NBT 键名布局。
     */
    private static MonitorScanResult inspectMonitorStack(ItemStack stack) {
        if (!PortableWirelessNetworkMonitor.isMonitorBound(stack)) {
            return null;
        }
        return new MonitorScanResult(
            PortableWirelessNetworkMonitor.getOwnerUUID(stack),
            PortableWirelessNetworkMonitor.getHudMode(stack),
            stack);
    }

    /**
     * 接收服务端同步过来的 EU 字符串（由 {@code PacketResponseWirelessEU.Handler} 通过
     * {@code ClientProxy.handleResponseEU} 以 {@code Minecraft.func_152344_a} 调度到客户端
     * 主线程后调用）。
     * <p>
     * 承担原 {@code updateCache} 的格式化、记录测量、计算 EU/t 职责；运行在客户端主线程，可安全操作状态。
     *
     * @param euStr 服务端传来的 {@code BigInteger.toString()} 字符串
     */
    public void receiveSyncedEU(String euStr, long requestToken, HudSession.Snapshot snapshot, Object world,
        EntityPlayer player, Object connection) {
        if (euStr == null || euStr.isEmpty()) {
            return;
        }
        if (!session.consume(snapshot, world, player, connection, requestToken) || !state.hudEnabled) return;
        MonitorScanResult scan = scanMonitorInInventory(player);
        if (scan.hudMode <= 0 || !java.util.Objects.equals(scan.ownerUUID, state.cachedOwnerUUID)) {
            session.invalidateRequest();
            state.selectMonitor(scan.stack, scan.ownerUUID, scan.hudMode);
            state.lastUpdateTick = Long.MIN_VALUE / 2;
            return;
        }
        state.monitorStack = scan.stack;

        // 解析服务端传来的 EU 字符串（异常时设为 ZERO，避免渲染崩溃）
        BigInteger wirelessEU;
        try {
            wirelessEU = new BigInteger(euStr);
        } catch (NumberFormatException e) {
            wirelessEU = BigInteger.ZERO;
        }

        // 更新同步缓存
        state.syncedEuStr = euStr;

        // 获取当前世界 tick（receiveSyncedEU 无 currentTick 入参，自行从客户端世界读取）
        Minecraft mc = Minecraft.getMinecraft();
        long currentTick = (mc.theWorld != null) ? mc.theWorld.getTotalWorldTime() : 0L;

        // 根据显示模式格式化能量值
        String euFormatted;
        if (state.displayMode == 2) {
            // 科学计数法
            euFormatted = FormatUtil.formatScientific(wirelessEU);
        } else {
            // 常规计数（带逗号分隔）
            euFormatted = FormatUtil.formatNormal(wirelessEU);
        }

        state.cachedEUText = "§b" + StatCollector.translateToLocal("gtswn.hud.wireless.network")
            + ": §f"
            + euFormatted
            + " §b"
            + StatCollector.translateToLocal("gtswn.hud.eu.unit");

        // 记录到数据集（替代 FormatUtil.recordMeasurement，EUDataSet 内部自动 FIFO 老化）
        state.dataSet.add(wirelessEU, currentTick);
        // 格式化 HUD 电网状态文本
        state.cachedEUTText = formatHUDStatus();
        state.cachedRealtimeEUTText = formatHUDRealtimeStatus();
    }

    /**
     * 更新 HUD 缓存数据。
     * <p>
     * [Bugfix] 不再在客户端直接调用 {@code WirelessNetworkManager.getUserEU}（GlobalEnergy 数据仅在服务端，
     * 客户端恒返 0）。改为向服务端发送 {@link PacketRequestWirelessEU} 请求包，由服务端查询后回包，
     * 实际的格式化与 EU/t 计算在 {@link #receiveSyncedEU} 中完成。
     *
     * @param currentTick 当前游戏 tick
     * @param uuid        保留以兼容现有调用点；EU 数据已改为服务端同步，本方法不再直接使用此参数
     */
    private void updateCache(long currentTick, UUID uuid) {
        // 向服务端发送 EU 请求包（仅当拥有者 UUID 有效时）
        if (state.cachedOwnerUUID != null && !state.cachedOwnerUUID.isEmpty()) {
            GTSWNPacketHandler.NETWORK
                .sendToServer(new PacketRequestWirelessEU(state.cachedOwnerUUID, session.request()));
        }

        // 首次进入（尚未收到服务端响应）时显示占位符，避免闪烁；
        // 已有同步数据时 cachedEUText 由 receiveSyncedEU 维护，此处不覆盖
        if (state.syncedEuStr == null) {
            state.cachedEUText = "§b" + StatCollector.translateToLocal("gtswn.hud.wireless.network")
                + ": §f..."
                + " §b"
                + StatCollector.translateToLocal("gtswn.hud.eu.unit");
        }

        state.lastUpdateTick = currentTick;
    }

    // 记录/清理方法已迁移至 EUDataSet（T4 公共工具类提取）

    /**
     * 根据数据集格式化 HUD 电网状态文本。
     * <p>
     * 显示逻辑（与 MTE 统一）：
     * <ul>
     * <li>size &lt; 2：网络状态：计算中...（标题青色 + 计算中橙黄）—— 首检未完成</li>
     * <li>eut == 0：0 (静默) —— 绝对无变化</li>
     * <li>0 &lt; |eut| &lt; 1：0 (&lt;1EU) —— 近似无变化</li>
     * <li>|eut| &gt;= 1：正常显示（数值 + 电压等级）</li>
     * </ul>
     *
     * @return 格式化后的 HUD 电网状态文本（带 § 颜色代码）
     */
    private String formatHUDStatus() {
        // 便携式冷启动：size < 2 时无法计算斜率，显示"网络状态：计算中..."
        // v1.3.2 修正：原阈值 size < 6 为 v1.3.0 前 600t 间隔的过时逻辑，
        // 现检测间隔可配置（默认 50t）且静默压缩后 size 恒为 2，故与 MTE formatEUTStatus 统一为 size < 2
        // 颜色：标题青色 §b（与其他状态行一致），"计算中"橙黄 §6 警示
        if (state.dataSet.size() < 2) {
            return "§b" + StatCollector.translateToLocal("gtswn.hud.network.status")
                + ": §6"
                + StatCollector.translateToLocal("gtswn.hud.network.status.calculating");
        }

        // 由 EUDataSet 计算 EU/t 斜率（BigDecimal 精确除法）
        double eut = state.dataSet.calculateEUT();

        // 绝对无变化（首末两点 EU 完全相等）
        if (eut == 0.0) {
            // 长期静默：静默模式持续 ≥ 300s（数据集压缩为 2 个数据点）
            if (state.dataSet.isLongTermSilent()) {
                return "§b" + StatCollector.translateToLocal("gtswn.hud.network.status")
                    + ": §f0 §bEU/t (§7"
                    + StatCollector.translateToLocal("gtswn.hud.network.status.long_silent")
                    + "§b)";
            }
            return "§b" + StatCollector.translateToLocal("gtswn.hud.network.status")
                + ": §f0 §bEU/t (§7"
                + StatCollector.translateToLocal("gtswn.hud.network.status.silent")
                + "§b)";
        }

        double absEut = Math.abs(eut);

        // 小于 1 EU/t：变化过小，近似无变化
        if (absEut < 1.0) {
            return "§b" + StatCollector.translateToLocal("gtswn.hud.network.status")
                + ": §f0 §bEU/t (§7"
                + StatCollector.translateToLocal("gtswn.hud.network.status.lt1")
                + "§b)";
        }

        // 正常显示：数值 + GT 电压等级
        // displayMode==2 科学计数（与 EU 总量判断一致），否则常规计数
        String euPerTickStr = (state.displayMode == 2) ? FormatUtil.formatScientificDouble(absEut)
            : FormatUtil.formatNormalDouble(absEut);
        String gtPowerText = GTTierUtil.formatGTPower(eut);
        int gtTier = GTTierUtil.getGTTier(eut);
        String bracketColor = GTTierUtil.TIER_COLORS[gtTier];
        String statusLabel = StatCollector.translateToLocal("gtswn.hud.network.status");
        String eutUnit = StatCollector.translateToLocal("gtswn.hud.eut.unit");

        // v1.4.14 修正：去除 ↑↓ 箭头，增加用 +，减少用 -（与实时状态行格式统一）
        // 注意：euPerTickStr 基于 absEut 计算，减少时通过 "-" 前缀补负号
        if (eut > 0) {
            return "§b" + statusLabel
                + ": §a+"
                + euPerTickStr
                + " §b"
                + eutUnit
                + " "
                + bracketColor
                + "("
                + gtPowerText
                + ")";
        } else {
            return "§b" + statusLabel
                + ": §c-"
                + euPerTickStr
                + " §b"
                + eutUnit
                + " "
                + bracketColor
                + "("
                + gtPowerText
                + ")";
        }
    }

    // 测量记录类、电压等级数组、格式化方法已迁移至 FormatUtil 与 GTTierUtil（T4 公共工具类提取）
    private String formatHUDRealtimeStatus() {
        String statusLabel = StatCollector.translateToLocal("gtswn.hud.network.realtime_status");
        String eutUnit = StatCollector.translateToLocal("gtswn.hud.eut.unit");
        if (state.dataSet.size() < 2) {
            return "\u00A7b" + statusLabel
                + ": \u00A76"
                + StatCollector.translateToLocal("gtswn.hud.network.status.calculating");
        }

        double eut = state.dataSet.calculateRecentEUT();
        if (eut == 0.0) {
            return "\u00A7b" + statusLabel
                + ": \u00A7f0 \u00A7b"
                + eutUnit
                + " (\u00A77"
                + StatCollector.translateToLocal("gtswn.hud.network.status.silent")
                + "\u00A7b)";
        }

        double absEut = Math.abs(eut);
        if (absEut < 1.0) {
            return "\u00A7b" + statusLabel
                + ": \u00A7f0 \u00A7b"
                + eutUnit
                + " (\u00A77"
                + StatCollector.translateToLocal("gtswn.hud.network.status.lt1")
                + "\u00A7b)";
        }

        // displayMode==2 科学计数（与 EU 总量判断一致），否则常规计数
        String euPerTickStr = (state.displayMode == 2) ? FormatUtil.formatScientificDouble(absEut)
            : FormatUtil.formatNormalDouble(absEut);
        String gtPowerText = GTTierUtil.formatGTPower(eut);
        int gtTier = GTTierUtil.getGTTier(eut);
        String bracketColor = GTTierUtil.TIER_COLORS[gtTier];

        if (eut > 0) {
            return "\u00A7b" + statusLabel
                + ": \u00A7a+"
                + euPerTickStr
                + " \u00A7b"
                + eutUnit
                + " "
                + bracketColor
                + "("
                + gtPowerText
                + ")";
        }
        return "\u00A7b" + statusLabel
            + ": \u00A7c-"
            + euPerTickStr
            + " \u00A7b"
            + eutUnit
            + " "
            + bracketColor
            + "("
            + gtPowerText
            + ")";
    }
}
