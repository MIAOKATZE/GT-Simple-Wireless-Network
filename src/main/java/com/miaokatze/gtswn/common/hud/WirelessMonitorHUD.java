package com.miaokatze.gtswn.common.hud;

import java.math.BigInteger;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;
import com.miaokatze.gtswn.common.util.EUDataSet;
import com.miaokatze.gtswn.common.util.FormatUtil;
import com.miaokatze.gtswn.common.util.GTTierUtil;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketRequestWirelessEU;

import baubles.api.BaublesApi;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * 便携式无线网络监测终端 HUD 渲染器
 * <p>
 * 当监测终端在玩家背包内时，在饱食度上方显示无线电网能量值。
 * 使用 Forge 事件系统监听游戏渲染事件，在适当的位置绘制 HUD 文本。
 */
public class WirelessMonitorHUD extends Gui {

    /** HUD 全局开关状态（默认关闭） */
    private static boolean hudEnabled = false;

    /** HUD 显示模式（0=关闭，1=常规计数，2=科学计数） */
    private static int displayMode = 0;

    /** 缓存的拥有者 UUID（用于 HUD 显示） */
    private static String cachedOwnerUUID = null;

    /** 服务端同步过来的 EU 字符串（BigInteger.toString()），未收到响应前为 null（用于判断首次进入） */
    private static String syncedEuStr = null;

    /**
     * EU 测量数据集（替代原 measurementHistory 列表）。
     * <p>
     * 容量 61（0s 首检 + 60 次 100t 检测 = 300s），FIFO 老化，
     * 内部使用 BigDecimal 精确计算 EU/t 斜率。static 单例：HUD 全局唯一。
     */
    private static final EUDataSet dataSet = new EUDataSet();

    /** 缓存的 EU/t 文本 */
    private static String cachedEUTText = "";
    private static String cachedRealtimeEUTText = "";

    /** HUD 更新间隔（ticks），每 100 ticks（5 秒）更新一次（与 MTE 统一） */
    private static final int UPDATE_INTERVAL = 100;

    /** 上次更新的真实时间戳（毫秒），用于登出/重进期间的 gap 检测（getTotalWorldTime 登出不推进） */
    private static long lastUpdateRealTimeMs = 0L;

    /** 背包遍历间隔（ticks），每 20 ticks（1 秒）检查一次 */
    private static final int INVENTORY_CHECK_INTERVAL = 20;

    /** 上次更新的时间戳（游戏 tick） */
    private static long lastUpdateTick = 0;

    /** 上次背包检查的时间戳（游戏 tick） */
    private static long lastInventoryCheckTick = 0;

    /** 缓存的无线电网能量值 */
    private static String cachedEUText = "§b" + StatCollector.translateToLocal("gtswn.hud.wireless.network")
        + ": §f0 §b"
        + StatCollector.translateToLocal("gtswn.hud.eu.unit");

    /** 当前世界 ID（用于检测世界切换） */
    private static int currentWorldId = -1;

    /**
     * 设置 HUD 显示状态
     *
     * @param enabled   是否启用 HUD
     * @param ownerUUID 拥有者 UUID（可选）
     */
    public static void setEnabled(boolean enabled, String ownerUUID) {
        hudEnabled = enabled;
        if (ownerUUID != null && !ownerUUID.isEmpty()) {
            cachedOwnerUUID = ownerUUID;
        }
    }

    /**
     * 设置 HUD 显示模式
     *
     * @param mode 显示模式（0=关闭，1=常规计数，2=科学计数）
     */
    public static void setDisplayMode(int mode) {
        displayMode = mode;
        // 重置更新时间，强制下次渲染时立即更新
        lastUpdateTick = 0;
    }

    /**
     * 获取 HUD 全局开关状态
     *
     * @return 当前 HUD 是否启用
     */
    public static boolean isEnabled() {
        return hudEnabled;
    }

    /**
     * 清空所有缓存数据（用于玩家失去监视器、HUD 关闭等场景）。
     * <p>
     * 注意：世界切换不再调用此方法（见 {@link #onRenderOverlay} 中的世界切换处理），
     * 用户确认世界切换时保留数据集以维持 EU/t 连续性。
     */
    private static void clearCache() {
        cachedOwnerUUID = null;
        // 重置服务端同步缓存，避免跨存档/世界切换时残留旧值
        syncedEuStr = null;
        cachedEUText = "§b" + StatCollector.translateToLocal("gtswn.hud.wireless.network")
            + ": §f0 §b"
            + StatCollector.translateToLocal("gtswn.hud.eu.unit");
        cachedEUTText = "";
        cachedRealtimeEUTText = "";
        dataSet.clear(); // 清空数据集
        lastUpdateTick = 0; // 强制首次检测
        lastUpdateRealTimeMs = 0; // 重置真实时间戳
        lastInventoryCheckTick = 0;
        hudEnabled = false;
        displayMode = 0;
    }

    /**
     * 渲染游戏覆盖层事件处理器
     * 在饱食度上方绘制无线电网能量信息
     */
    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        // 仅在绘制所有元素后执行
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();

        // 确保游戏正常运行且有玩家
        if (mc.theWorld == null || mc.thePlayer == null) {
            return;
        }

        EntityPlayer player = mc.thePlayer;

        // 检测世界切换：用户确认世界切换时保留数据集（维持 EU/t 连续性）
        // 仅重置 UI 状态（syncedEuStr、cachedEUText、cachedEUTText、lastUpdateTick），不清空 dataSet
        int worldId = mc.theWorld.provider.dimensionId;
        if (worldId != currentWorldId) {
            currentWorldId = worldId;
            // 保留 dataSet（用户确认），只重置 UI 状态
            syncedEuStr = null;
            cachedEUText = "§b" + StatCollector.translateToLocal("gtswn.hud.wireless.network")
                + ": §f... §b"
                + StatCollector.translateToLocal("gtswn.hud.eu.unit");
            cachedEUTText = "";
            cachedRealtimeEUTText = "";
            lastUpdateTick = 0; // 强制下次更新
            // 不清空 dataSet
        }

        // 获取世界时间
        long currentTick = mc.theWorld.getTotalWorldTime();

        // 每 INVENTORY_CHECK_INTERVAL ticks 检查一次背包（在 hudEnabled 检查之前执行）
        if (currentTick - lastInventoryCheckTick >= INVENTORY_CHECK_INTERVAL) {
            String newOwnerUUID = findMonitorInInventory(player);

            // 如果找到了监测终端，从 NBT 读取 HUD 模式并初始化
            if (newOwnerUUID != null && !newOwnerUUID.isEmpty()) {
                // 获取物品的 HUD 模式
                int hudMode = getHUDModeFromInventory(player);

                // 如果 HUD 模式或拥有者发生变化，更新缓存
                if (!newOwnerUUID.equals(cachedOwnerUUID) || displayMode != hudMode) {
                    cachedOwnerUUID = newOwnerUUID;
                    displayMode = hudMode;
                    hudEnabled = hudMode > 0;

                    // 如果 HUD 开启，重置更新时间强制立即更新
                    // 注：便携式随退出登录重置（用户确认），不再从物品 NBT 加载历史，
                    // 靠 gap 检测和首次检测重建数据集
                    if (hudEnabled) {
                        lastUpdateTick = 0;

                        // 立即更新一次缓存
                        try {
                            updateCache(currentTick, UUID.fromString(newOwnerUUID));
                        } catch (Exception e) {
                            // UUID 解析失败，忽略
                        }
                    }
                }
            } else {
                // 没找到监测终端，关闭 HUD
                if (hudEnabled) {
                    // 失去监视器：清空所有缓存（含 dataSet），避免跨存档污染
                    hudEnabled = false;
                    cachedOwnerUUID = null;
                    displayMode = 0;
                    clearCache();
                }
            }

            lastInventoryCheckTick = currentTick;
        }

        // 检查 HUD 是否启用（在背包检查之后）
        if (!hudEnabled) {
            return;
        }

        // 如果找不到监测终端，不显示 HUD
        if (cachedOwnerUUID == null || cachedOwnerUUID.isEmpty()) {
            return;
        }

        // 解析拥有者 UUID
        UUID uuid;
        try {
            uuid = UUID.fromString(cachedOwnerUUID);
        } catch (Exception e) {
            return;
        }

        // 真实时间 gap 检测（登出期间 tick 不推进，用真实时间检测）
        // 说明：getTotalWorldTime() 登出期间不推进，无法检测登出时长；
        // System.currentTimeMillis() 真实时间，登出期间持续推进；
        // 阈值 10000ms = 10秒，与 MTE 的 200L ticks = 10s 对齐；
        // 短时卡顿（<10s）豁免，保留数据集
        long currentRealTimeMs = System.currentTimeMillis();
        if (lastUpdateRealTimeMs > 0 && currentRealTimeMs - lastUpdateRealTimeMs > 10000L) {
            // 长时重载/退出重进（>10秒真实时间）：清空数据集，强制首次检测
            dataSet.clear();
            lastUpdateTick = 0; // 强制首次检测
        }
        lastUpdateRealTimeMs = currentRealTimeMs;

        // 每 UPDATE_INTERVAL ticks 更新一次缓存
        if (currentTick - lastUpdateTick >= UPDATE_INTERVAL) {
            updateCache(currentTick, uuid);
        }

        // 计算 HUD 位置（饱食度上方）
        ScaledResolution resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int screenWidth = resolution.getScaledWidth();
        int screenHeight = resolution.getScaledHeight();

        // 饱食度图标位置：x = screenWidth / 2 + 91, y = screenHeight - 39
        // HUD 显示在饱食度上方 15 像素处
        int hudX = screenWidth / 2 + 91;
        int hudY = screenHeight - 54;

        // 保存 OpenGL 状态
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();

        // 禁用深度测试和光照，确保 HUD 始终在最上层
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);

        // 获取文本宽度
        int textWidth = mc.fontRenderer.getStringWidth(cachedEUText);

        // 绘制半透明背景
        drawRect(hudX - 2, hudY - 2, hudX + textWidth + 2, hudY + 10, 0x80000000);

        // 绘制文本（使用格式化字符串，带颜色代码）
        mc.fontRenderer.drawStringWithShadow(cachedEUText, hudX, hudY, 0xFFFFFF);

        // 绘制 EU/t 信息（在上方一行）
        int eutY = hudY - 12;
        int eutTextWidth = mc.fontRenderer.getStringWidth(cachedEUTText);
        drawRect(hudX - 2, eutY - 2, hudX + Math.max(textWidth, eutTextWidth) + 2, eutY + 10, 0x80000000);
        mc.fontRenderer.drawStringWithShadow(cachedEUTText, hudX, eutY, 0xFFFFFF);

        int realtimeY = hudY - 24;
        int realtimeTextWidth = mc.fontRenderer.getStringWidth(cachedRealtimeEUTText);
        drawRect(
            hudX - 2,
            realtimeY - 2,
            hudX + Math.max(Math.max(textWidth, eutTextWidth), realtimeTextWidth) + 2,
            realtimeY + 10,
            0x80000000);
        mc.fontRenderer.drawStringWithShadow(cachedRealtimeEUTText, hudX, realtimeY, 0xFFFFFF);

        // 恢复 OpenGL 状态
        GL11.glPopMatrix();
        GL11.glPopAttrib();
    }

    /**
     * 遍历玩家背包查找便携监测终端
     *
     * @param player 玩家实体
     * @return 拥有者 UUID，如果未找到则返回 null
     */
    private String findMonitorInInventory(EntityPlayer player) {
        // 检查主手
        ItemStack heldItem = player.getHeldItem();
        if (heldItem != null) {
            if (heldItem.getItem() instanceof PortableWirelessNetworkMonitor) {
                if (isMonitorBound(heldItem)) {
                    String uuid = heldItem.stackTagCompound.getString("OwnerUUID");
                    return uuid;
                }
            }
        }

        // --- 饰品栏扫描（Baubles 不存在时安全降级） ---
        try {
            IInventory baubles = BaublesApi.getBaubles(player);
            if (baubles != null) {
                for (int i = 0; i < baubles.getSizeInventory(); i++) {
                    ItemStack baubleStack = baubles.getStackInSlot(i);
                    if (baubleStack != null && baubleStack.getItem() instanceof PortableWirelessNetworkMonitor) {
                        if (isMonitorBound(baubleStack)) {
                            return baubleStack.stackTagCompound.getString("OwnerUUID");
                        }
                    }
                }
            }
        } catch (NoClassDefFoundError ignored) {
            // Baubles 未安装，跳过饰品栏扫描
        }

        // 遍历背包槽位（0-35）
        for (int i = 0; i < player.inventory.mainInventory.length; i++) {
            ItemStack stack = player.inventory.mainInventory[i];
            if (stack != null) {
                if (stack.getItem() instanceof PortableWirelessNetworkMonitor) {
                    if (isMonitorBound(stack)) {
                        String uuid = stack.stackTagCompound.getString("OwnerUUID");
                        return uuid;
                    }
                }
            }
        }

        return null;
    }

    /**
     * 遍历玩家背包查找便携监测终端（返回物品栈版本）
     * <p>
     * 与 {@link #findMonitorInInventory(EntityPlayer)} 扫描顺序一致：
     * 主手 → Baubles 饰品栏 → 背包槽位（0-35）。
     * 用于 NBT 历史读写时需要操作具体物品栈的场景。
     *
     * @param player 玩家实体
     * @return 已绑定的监视器物品栈，未找到返回 null
     */
    private ItemStack findMonitorStackInInventory(EntityPlayer player) {
        // 检查主手
        ItemStack heldItem = player.getHeldItem();
        if (heldItem != null && heldItem.getItem() instanceof PortableWirelessNetworkMonitor) {
            if (isMonitorBound(heldItem)) {
                return heldItem;
            }
        }

        // --- 饰品栏扫描（Baubles 不存在时安全降级） ---
        try {
            IInventory baubles = BaublesApi.getBaubles(player);
            if (baubles != null) {
                for (int i = 0; i < baubles.getSizeInventory(); i++) {
                    ItemStack baubleStack = baubles.getStackInSlot(i);
                    if (baubleStack != null && baubleStack.getItem() instanceof PortableWirelessNetworkMonitor) {
                        if (isMonitorBound(baubleStack)) {
                            return baubleStack;
                        }
                    }
                }
            }
        } catch (NoClassDefFoundError ignored) {
            // Baubles 未安装，跳过饰品栏扫描
        }

        // 遍历背包槽位（0-35）
        for (int i = 0; i < player.inventory.mainInventory.length; i++) {
            ItemStack stack = player.inventory.mainInventory[i];
            if (stack != null && stack.getItem() instanceof PortableWirelessNetworkMonitor) {
                if (isMonitorBound(stack)) {
                    return stack;
                }
            }
        }

        return null;
    }

    /**
     * 检查监测终端是否已绑定
     *
     * @param stack 物品堆栈
     * @return 是否已绑定
     */
    private boolean isMonitorBound(ItemStack stack) {
        if (stack.stackTagCompound == null) {
            return false;
        }
        return stack.stackTagCompound.getBoolean("Initialized") && stack.stackTagCompound.hasKey("OwnerUUID");
    }

    /**
     * 从背包中获取监测终端的 HUD 模式
     */
    private int getHUDModeFromInventory(EntityPlayer player) {
        // 检查主手
        ItemStack heldItem = player.getHeldItem();
        if (heldItem != null && heldItem.getItem() instanceof PortableWirelessNetworkMonitor) {
            if (heldItem.stackTagCompound != null) {
                return heldItem.stackTagCompound.getInteger("HUDMode");
            }
        }

        // --- 饰品栏扫描（Baubles 不存在时安全降级） ---
        try {
            IInventory baublesInv = BaublesApi.getBaubles(player);
            if (baublesInv != null) {
                for (int i = 0; i < baublesInv.getSizeInventory(); i++) {
                    ItemStack baubleStack = baublesInv.getStackInSlot(i);
                    if (baubleStack != null && baubleStack.getItem() instanceof PortableWirelessNetworkMonitor) {
                        if (baubleStack.stackTagCompound != null) {
                            return baubleStack.stackTagCompound.getInteger("HUDMode");
                        }
                    }
                }
            }
        } catch (NoClassDefFoundError ignored) {
            // Baubles 未安装，跳过饰品栏扫描
        }

        // 遍历背包槽位
        for (int i = 0; i < player.inventory.mainInventory.length; i++) {
            ItemStack stack = player.inventory.mainInventory[i];
            if (stack != null && stack.getItem() instanceof PortableWirelessNetworkMonitor) {
                if (stack.stackTagCompound != null) {
                    return stack.stackTagCompound.getInteger("HUDMode");
                }
            }
        }

        return 0;
    }

    /**
     * 接收服务端同步过来的 EU 字符串（由 {@code PacketResponseWirelessEU.Handler} 通过
     * {@code Minecraft.addScheduledTask} 调度到客户端主线程后调用）。
     * <p>
     * 承担原 {@code updateCache} 的格式化、记录测量、计算 EU/t 职责；运行在客户端主线程，可安全操作 static 字段。
     *
     * @param euStr 服务端传来的 {@code BigInteger.toString()} 字符串
     */
    public static void receiveSyncedEU(String euStr) {
        if (euStr == null || euStr.isEmpty()) {
            return;
        }

        // 解析服务端传来的 EU 字符串（异常时设为 ZERO，避免渲染崩溃）
        BigInteger wirelessEU;
        try {
            wirelessEU = new BigInteger(euStr);
        } catch (NumberFormatException e) {
            wirelessEU = BigInteger.ZERO;
        }

        // 更新同步缓存
        syncedEuStr = euStr;

        // 获取当前世界 tick（receiveSyncedEU 无 currentTick 入参，自行从客户端世界读取）
        Minecraft mc = Minecraft.getMinecraft();
        long currentTick = (mc.theWorld != null) ? mc.theWorld.getTotalWorldTime() : 0L;

        // 根据显示模式格式化能量值
        String euFormatted;
        if (displayMode == 2) {
            // 科学计数法
            euFormatted = FormatUtil.formatScientific(wirelessEU);
        } else {
            // 常规计数（带逗号分隔）
            euFormatted = FormatUtil.formatNormal(wirelessEU);
        }

        cachedEUText = "§b" + StatCollector.translateToLocal("gtswn.hud.wireless.network")
            + ": §f"
            + euFormatted
            + " §b"
            + StatCollector.translateToLocal("gtswn.hud.eu.unit");

        // 记录到数据集（替代 FormatUtil.recordMeasurement，EUDataSet 内部自动 FIFO 老化）
        dataSet.add(wirelessEU, currentTick);
        // 格式化 HUD 电网状态文本
        cachedEUTText = formatHUDStatus();
        cachedRealtimeEUTText = formatHUDRealtimeStatus();
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
        if (cachedOwnerUUID != null && !cachedOwnerUUID.isEmpty()) {
            GTSWNPacketHandler.NETWORK.sendToServer(new PacketRequestWirelessEU(cachedOwnerUUID));
        }

        // 首次进入（尚未收到服务端响应）时显示占位符，避免闪烁；
        // 已有同步数据时 cachedEUText 由 receiveSyncedEU 维护，此处不覆盖
        if (syncedEuStr == null) {
            cachedEUText = "§b" + StatCollector.translateToLocal("gtswn.hud.wireless.network")
                + ": §f..."
                + " §b"
                + StatCollector.translateToLocal("gtswn.hud.eu.unit");
        }

        lastUpdateTick = currentTick;
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
    private static String formatHUDStatus() {
        // 便携式冷启动：size < 2 时无法计算斜率，显示"网络状态：计算中..."
        // v1.3.2 修正：原阈值 size < 6 为 v1.3.0 前 600t 间隔的过时逻辑，
        // 现检测间隔 100t 且静默压缩后 size 恒为 2，故与 MTE formatEUTStatus 统一为 size < 2
        // 颜色：标题青色 §b（与其他状态行一致），"计算中"橙黄 §6 警示
        if (dataSet.size() < 2) {
            return "§b" + StatCollector.translateToLocal("gtswn.hud.network.status")
                + ": §6"
                + StatCollector.translateToLocal("gtswn.hud.network.status.calculating");
        }

        // 由 EUDataSet 计算 EU/t 斜率（BigDecimal 精确除法）
        double eut = dataSet.calculateEUT();

        // 绝对无变化（首末两点 EU 完全相等）
        if (eut == 0.0) {
            // 长期静默：静默模式持续 ≥ 300s（数据集压缩为 2 个数据点）
            if (dataSet.isLongTermSilent()) {
                return "§b" + StatCollector.translateToLocal("gtswn.hud.network.status") + ": §f0 §bEU/t (§7长期静默§b)";
            }
            return "§b" + StatCollector.translateToLocal("gtswn.hud.network.status") + ": §f0 §bEU/t (§7静默§b)";
        }

        double absEut = Math.abs(eut);

        // 小于 1 EU/t：变化过小，近似无变化
        if (absEut < 1.0) {
            return "§b" + StatCollector.translateToLocal("gtswn.hud.network.status") + ": §f0 §bEU/t (§7<1EU§b)";
        }

        // 正常显示：数值 + GT 电压等级
        String euPerTickStr = FormatUtil.formatNormalDouble(absEut);
        String gtPowerText = GTTierUtil.formatGTPower(eut);
        int gtTier = GTTierUtil.getGTTier(eut);
        String bracketColor = GTTierUtil.TIER_COLORS[gtTier];
        String statusLabel = StatCollector.translateToLocal("gtswn.hud.network.status");
        String eutUnit = StatCollector.translateToLocal("gtswn.hud.eut.unit");

        if (eut > 0) {
            return "§b" + statusLabel
                + ": §a↑ +"
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
                + ": §c↓ "
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
    private static String formatHUDRealtimeStatus() {
        String statusLabel = StatCollector.translateToLocal("gtswn.hud.network.realtime_status");
        String eutUnit = StatCollector.translateToLocal("gtswn.hud.eut.unit");
        if (dataSet.size() < 2) {
            return "\u00A7b" + statusLabel
                + ": \u00A76"
                + StatCollector.translateToLocal("gtswn.hud.network.status.calculating");
        }

        double eut = dataSet.calculateRecentEUT();
        if (eut == 0.0) {
            return "\u00A7b" + statusLabel + ": \u00A7f0 \u00A7b" + eutUnit + " (\u00A77Silent\u00A7b)";
        }

        double absEut = Math.abs(eut);
        if (absEut < 1.0) {
            return "\u00A7b" + statusLabel + ": \u00A7f0 \u00A7b" + eutUnit + " (\u00A77<1EU\u00A7b)";
        }

        String euPerTickStr = FormatUtil.formatNormalDouble(absEut);
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
