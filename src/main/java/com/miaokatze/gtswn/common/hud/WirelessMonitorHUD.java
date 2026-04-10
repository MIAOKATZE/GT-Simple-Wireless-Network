package com.miaokatze.gtswn.common.hud;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import gregtech.common.misc.WirelessNetworkManager;

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

    /** 历史测量记录列表（只记录发生变化的点） */
    private static List<Measurement> measurementHistory = new ArrayList<>();

    /** 缓存的 EU/t 文本 */
    private static String cachedEUTText = "";

    /** 上次计算的 EU/t 值（用于保持稳定状态显示） */
    private static double lastCalculatedEUT = 0.0;

    /** 连续无变化的检测次数 */
    private static int unchangedCount = 0;

    /** 最大无变化检测次数（超过此值显示“暂无变化”） */
    private static final int MAX_UNCHANGED_COUNT = 60;

    /** HUD 更新间隔（ticks），每 200 ticks（10 秒）更新一次 */
    private static final int UPDATE_INTERVAL = 200;

    /** 背包遍历间隔（ticks），每 20 ticks（1 秒）检查一次 */
    private static final int INVENTORY_CHECK_INTERVAL = 20;

    /** 上次更新的时间戳（游戏 tick） */
    private static long lastUpdateTick = 0;

    /** 上次背包检查的时间戳（游戏 tick） */
    private static long lastInventoryCheckTick = 0;

    /** 缓存的无线电网能量值 */
    private static String cachedEUText = "无线电网: 0 EU";

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

        // 检查 HUD 是否启用
        if (!hudEnabled) {
            return;
        }

        // 确保游戏正常运行且有玩家
        if (mc.theWorld == null || mc.thePlayer == null) {
            return;
        }

        EntityPlayer player = mc.thePlayer;

        // 获取世界时间
        long currentTick = mc.theWorld.getTotalWorldTime();

        // 每 INVENTORY_CHECK_INTERVAL ticks 检查一次背包
        if (currentTick - lastInventoryCheckTick >= INVENTORY_CHECK_INTERVAL) {
            cachedOwnerUUID = findMonitorInInventory(player);
            lastInventoryCheckTick = currentTick;
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

        // 每 UPDATE_INTERVAL ticks 更新一次缓存
        if (currentTick - lastUpdateTick >= UPDATE_INTERVAL) {
            // 调用 GT5U 的 WirelessNetworkManager 获取无线电网能量
            BigInteger wirelessEU = WirelessNetworkManager.getUserEU(uuid);

            // 根据显示模式格式化能量值
            String euFormatted;
            if (displayMode == 2) {
                // 科学计数法
                euFormatted = formatScientific(wirelessEU);
            } else {
                // 常规计数（带逗号分隔）
                euFormatted = formatNormal(wirelessEU);
            }

            cachedEUText = "§b" + StatCollector.translateToLocal("gtswn.hud.wireless.network")
                + ": §f"
                + euFormatted
                + " §b"
                + StatCollector.translateToLocal("gtswn.hud.eu.unit");

            // 记录测量历史并计算 EU/t
            recordMeasurement(currentTick, wirelessEU);
            cachedEUTText = calculateEUT(currentTick);

            lastUpdateTick = currentTick;
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
     * 记录测量历史（只记录发生变化的点）
     */
    private static void recordMeasurement(long tick, BigInteger value) {
        // 如果历史记录为空，直接添加
        if (measurementHistory.isEmpty()) {
            measurementHistory.add(new Measurement(tick, value));
            unchangedCount = 0;
            return;
        }

        // 获取最新的测量值
        Measurement latest = measurementHistory.get(measurementHistory.size() - 1);

        // 只有当值发生变化时才记录
        if (!latest.value.equals(value)) {
            measurementHistory.add(new Measurement(tick, value));
            unchangedCount = 0; // 重置计数器

            // 保留最近 10 次变化记录（足够计算）
            if (measurementHistory.size() > 10) {
                measurementHistory.remove(0);
            }
        } else {
            // 值没有变化，增加计数器
            unchangedCount++;
        }
    }

    /**
     * 计算 EU/t（每秒能量变化率，dEU/dt）
     */
    private static String calculateEUT(long currentTick) {
        // 如果连续无变化次数超过阈值，显示“暂无变化”
        if (unchangedCount >= MAX_UNCHANGED_COUNT) {
            return "§b" + StatCollector.translateToLocal("gtswn.hud.grid.status")
                + ": §f"
                + StatCollector.translateToLocal("gtswn.hud.grid.no.change");
        }

        if (measurementHistory.size() < 2) {
            return "§b" + StatCollector.translateToLocal("gtswn.hud.grid.status")
                + ": §f"
                + StatCollector.translateToLocal("gtswn.hud.grid.no.change");
        }

        // 获取最新的两个变化点
        Measurement latest = measurementHistory.get(measurementHistory.size() - 1);
        Measurement previous = measurementHistory.get(measurementHistory.size() - 2);

        // 计算差值和时间间隔
        BigInteger diff = latest.value.subtract(previous.value);
        long tickDiff = latest.tick - previous.tick;

        // 计算 EU/t（diff / tickDiff）
        double euPerTick = diff.doubleValue() / tickDiff;

        // 更新上次计算的 EU/t 值
        lastCalculatedEUT = euPerTick;

        // 格式化 EU/t（根据显示模式）
        String euPerTickStr;
        if (displayMode == 2) {
            // 科学计数法（10^幂格式）
            int exponent = (int) Math.floor(Math.log10(Math.abs(euPerTick)));
            double coefficient = euPerTick / Math.pow(10, exponent);
            euPerTickStr = String.format("%.2f×10^%d", coefficient, exponent);
        } else {
            // 常规计数
            if (Math.abs(euPerTick) < 0.01) {
                euPerTickStr = "0.00";
            } else if (Math.abs(euPerTick) < 1000) {
                euPerTickStr = String.format("%.2f", euPerTick);
            } else {
                // 大数值使用逗号分隔
                euPerTickStr = formatNormalDouble(euPerTick);
            }
        }

        // 转换为 GT 的电流+电压等级格式
        String gtPowerText = formatGTPower(euPerTick);

        // 判断是增加还是减少
        String status;
        if (euPerTick > 0) {
            status = "§a↑ +" + euPerTickStr
                + " "
                + StatCollector.translateToLocal("gtswn.hud.eut.unit")
                + " ("
                + gtPowerText
                + ")";
        } else if (euPerTick < 0) {
            status = "§c↓ " + euPerTickStr
                + " "
                + StatCollector.translateToLocal("gtswn.hud.eut.unit")
                + " ("
                + gtPowerText
                + ")";
        } else {
            status = "§f= 0.00 " + StatCollector.translateToLocal("gtswn.hud.eut.unit");
        }

        return "§b" + StatCollector.translateToLocal("gtswn.hud.grid.status") + ": " + status;
    }

    /**
     * 测量记录类
     */
    private static class Measurement {

        long tick;
        BigInteger value;

        Measurement(long tick, BigInteger value) {
            this.tick = tick;
            this.value = value;
        }
    }

    /**
     * GT 电压等级定义（每安培的 EU/t）
     */
    private static final long[] VOLTAGES = { 8L, // ULV
        32L, // LV
        128L, // MV
        512L, // HV
        2048L, // EV
        8192L, // IV
        32768L, // LuV
        131072L, // ZPM
        524288L, // UV
        2097152L, // UHV
        8388608L, // UEV
        33554432L, // UIV
        134217728L, // UMV
        536870912L, // UXV
        2147483647L // MAX
    };

    /**
     * GT 电压等级名称
     */
    private static final String[] TIER_NAMES = { "ULV", "LV", "MV", "HV", "EV", "IV", "LuV", "ZPM", "UV", "UHV", "UEV",
        "UIV", "UMV", "UXV", "MAX" };

    /**
     * GT 电压等级颜色代码（使用 Minecraft 原生 16 色）
     */
    private static final String[] TIER_COLORS = { "§7", // ULV - 灰色
        "§7", // LV - 灰色
        "§b", // MV - 亮蓝色
        "§9", // HV - 蓝色
        "§3", // EV - 青色
        "§3", // IV - 青色
        "§a", // LuV - 绿色
        "§e", // ZPM - 黄色
        "§e", // UV - 黄色
        "§6", // UHV - 金色
        "§6", // UEV - 金色
        "§c", // UIV - 红色
        "§c", // UMV - 红色
        "§4", // UXV - 深红色
        "§4" // MAX - 深红色
    };

    /**
     * 将 EU/t 转换为 GT 的电流+电压等级格式
     * 
     * @param euPerTick 每秒能量变化率
     * @return 格式化后的字符串（例如：2A HV）
     */
    private static String formatGTPower(double euPerTick) {
        double absEU = Math.abs(euPerTick);

        // 找到对应的电压等级
        int tier = 0;
        for (int i = 0; i < VOLTAGES.length; i++) {
            if (absEU < VOLTAGES[i] * 5) { // 最多 4A，所以用 5 倍作为上限
                tier = i;
                break;
            }
            if (i == VOLTAGES.length - 1) {
                tier = i; // MAX
            }
        }

        // 计算电流
        long voltage = VOLTAGES[tier];
        int amperage = (int) Math.ceil(absEU / voltage);

        // 如果电流超过 4A 且不是 MAX 等级，自动升级到下一等级
        boolean isOverloaded = false;
        if (amperage > 4 && tier < VOLTAGES.length - 1) {
            // 升级到下一等级
            tier++;
            voltage = VOLTAGES[tier];
            amperage = (int) Math.ceil(absEU / voltage);

            // 如果升级后还是超过 4A，继续检查是否需要再次升级
            while (amperage > 4 && tier < VOLTAGES.length - 1) {
                tier++;
                voltage = VOLTAGES[tier];
                amperage = (int) Math.ceil(absEU / voltage);
            }

            // 如果已经是 MAX 等级且超过 4A，标记为过载
            if (tier == VOLTAGES.length - 1 && amperage > 4) {
                isOverloaded = true;
                amperage = 4;
            }
        } else if (amperage > 4) {
            // MAX 等级超过 4A
            isOverloaded = true;
            amperage = 4;
        }

        // 格式化输出
        String color = TIER_COLORS[tier];
        String tierName = TIER_NAMES[tier];

        if (isOverloaded) {
            return color + amperage + "A " + tierName + "+";
        } else {
            return color + amperage + "A " + tierName;
        }
    }

    /**
     * 格式化为常规计数（带逗号分隔）
     * 
     * @param value 要格式化的 BigInteger 值
     * @return 格式化后的字符串（例如：269,835,880）
     */
    private String formatNormal(BigInteger value) {
        if (value == null) {
            return "0";
        }

        String str = value.toString();
        StringBuilder result = new StringBuilder();
        int length = str.length();

        for (int i = 0; i < length; i++) {
            if (i > 0 && (length - i) % 3 == 0) {
                result.append(",");
            }
            result.append(str.charAt(i));
        }

        return result.toString();
    }

    /**
     * 格式化 double 为常规计数（带逗号分隔，保留两位小数）
     * 
     * @param value 要格式化的 double 值
     * @return 格式化后的字符串（例如：1,234.56）
     */
    private static String formatNormalDouble(double value) {
        // 先格式化为两位小数
        String formatted = String.format("%.2f", Math.abs(value));

        // 分离整数部分和小数部分
        String[] parts = formatted.split("\\.");
        String integerPart = parts[0];
        String decimalPart = parts.length > 1 ? parts[1] : "00";

        // 为整数部分添加逗号分隔
        StringBuilder result = new StringBuilder();
        int length = integerPart.length();

        for (int i = 0; i < length; i++) {
            if (i > 0 && (length - i) % 3 == 0) {
                result.append(",");
            }
            result.append(integerPart.charAt(i));
        }

        // 组合结果
        String finalResult = result.toString() + "." + decimalPart;

        // 如果是负数，添加负号
        if (value < 0) {
            finalResult = "-" + finalResult;
        }

        return finalResult;
    }

    /**
     * 格式化为科学计数法字符串
     * 保留三位有效数字，使用 10^幂 格式
     * 
     * @param value 要格式化的 BigInteger 值
     * @return 格式化后的字符串（例如：2.70×10^8）
     */
    private String formatScientific(BigInteger value) {
        if (value == null || value.equals(BigInteger.ZERO)) {
            return "0";
        }

        // 转换为 double 进行科学计数法格式化
        double doubleValue = value.doubleValue();

        // 获取指数部分
        int exponent = (int) Math.floor(Math.log10(Math.abs(doubleValue)));

        // 计算系数（保留两位小数，即三位有效数字）
        double coefficient = doubleValue / Math.pow(10, exponent);

        // 格式化为 "系数×10^指数" 的形式
        return String.format("%.2f×10^%d", coefficient, exponent);
    }
}
