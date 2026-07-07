package com.miaokatze.gtswn.common.hud;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.StatCollector;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;
import com.miaokatze.gtswn.common.util.FormatUtil;
import com.miaokatze.gtswn.common.util.FormatUtil.Measurement;
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

    /** 历史测量记录列表（每次检测都记录，保证窗口内足够样本） */
    private static List<Measurement> measurementHistory = new ArrayList<>();

    /** 缓存的 EU/t 文本 */
    private static String cachedEUTText = "";

    /** HUD 更新间隔（ticks），每 600 ticks（30 秒）更新一次 */
    private static final int UPDATE_INTERVAL = 600;

    // 窗口常量已迁移至 FormatUtil（T4 公共工具类提取）

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
     * 清空所有缓存数据（用于世界切换或关闭 HUD 时）
     */
    private static void clearCache() {
        cachedOwnerUUID = null;
        // 重置服务端同步缓存，避免跨存档/世界切换时残留旧值
        syncedEuStr = null;
        cachedEUText = "§b" + StatCollector.translateToLocal("gtswn.hud.wireless.network")
            + ": §f0 §b"
            + StatCollector.translateToLocal("gtswn.hud.eu.unit");
        cachedEUTText = "";
        measurementHistory.clear();
        lastUpdateTick = 0;
        lastInventoryCheckTick = 0;
        hudEnabled = false;
        displayMode = 0;
    }

    /**
     * 将当前 measurementHistory 保存到物品 NBT
     * <p>
     * 在玩家退出世界、切维度或失去监视器时调用，确保历史不丢失。
     * 格式参考机器版 MTEWirelessEnergyMonitor.saveNBTData：count + m{i}(tick + value 字符串)
     *
     * @param stack 便携式监视器物品栈（可为 null）
     */
    public static void saveHistoryToItemStack(ItemStack stack) {
        // 物品栈为空或不是便携式监视器，直接返回
        if (stack == null || !(stack.getItem() instanceof PortableWirelessNetworkMonitor)) return;
        // 确保 NBT 已初始化
        if (stack.stackTagCompound == null) stack.stackTagCompound = new NBTTagCompound();
        // 构造历史记录 NBT
        NBTTagCompound historyTag = new NBTTagCompound();
        historyTag.setInteger("count", measurementHistory.size());
        for (int i = 0; i < measurementHistory.size(); i++) {
            Measurement m = measurementHistory.get(i);
            NBTTagCompound measTag = new NBTTagCompound();
            measTag.setLong("tick", m.tick);
            // BigInteger 以字符串形式存储（避免符号位/字节数组兼容性问题）
            measTag.setString("value", m.value.toString());
            historyTag.setTag("m" + i, measTag);
        }
        stack.stackTagCompound.setTag("measurementHistory", historyTag);
    }

    /**
     * 从物品 NBT 加载 measurementHistory（找到监视器后调用）
     * <p>
     * 加载后立即 purgeExpired 清理窗口外样本（参考机器版第1166-1170行）。
     * 如果 NBT 中无 measurementHistory 键，不做任何操作（首次使用）。
     *
     * @param stack       便携式监视器物品栈（可为 null）
     * @param currentTick 当前世界 tick（用于清理过期样本）
     */
    public static void loadHistoryFromItemStack(ItemStack stack, long currentTick) {
        // 物品栈为空或不是便携式监视器，直接返回
        if (stack == null || !(stack.getItem() instanceof PortableWirelessNetworkMonitor)) return;
        // NBT 不存在或无历史记录键，直接返回（首次使用）
        if (stack.stackTagCompound == null || !stack.stackTagCompound.hasKey("measurementHistory")) return;
        NBTTagCompound historyTag = stack.stackTagCompound.getCompoundTag("measurementHistory");
        int count = historyTag.getInteger("count");
        measurementHistory.clear();
        for (int i = 0; i < count; i++) {
            NBTTagCompound measTag = historyTag.getCompoundTag("m" + i);
            long tick = measTag.getLong("tick");
            BigInteger value = new BigInteger(measTag.getString("value"));
            measurementHistory.add(new Measurement(tick, value));
        }
        // 加载后清理过期样本（FormatUtil 静态方法）
        FormatUtil.purgeExpired(measurementHistory, currentTick, FormatUtil.WINDOW_TICKS);
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

        // 检测世界切换，清空缓存
        int worldId = mc.theWorld.provider.dimensionId;
        if (worldId != currentWorldId) {
            // 切维度前保存历史到物品栈（断点续传）
            ItemStack monitorStack = findMonitorStackInInventory(player);
            if (monitorStack != null) {
                saveHistoryToItemStack(monitorStack);
            }
            clearCache();
            currentWorldId = worldId;
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

                    // 如果 HUD 开启，从物品 NBT 加载历史（断点续传），重置更新时间强制立即更新
                    if (hudEnabled) {
                        lastUpdateTick = 0;
                        // 从物品栈加载历史测量数据（替代原来的 clear）
                        ItemStack monitorStack = findMonitorStackInInventory(player);
                        if (monitorStack != null) {
                            loadHistoryFromItemStack(monitorStack, currentTick);
                        } else {
                            measurementHistory.clear();
                        }

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
                    // 失去监视器前无法保存历史到物品栈（此时找不到栈），
                    // 历史会随 clearCache 丢失。这是可接受的——
                    // 玩家主动丢弃/放入箱子后历史无意义。
                    hudEnabled = false;
                    cachedOwnerUUID = null;
                    displayMode = 0;
                    // 清空缓存，避免跨存档污染
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

        // 记录测量历史并计算 EU/t（FormatUtil 静态方法）
        FormatUtil.recordMeasurement(measurementHistory, wirelessEU, currentTick, FormatUtil.WINDOW_TICKS);
        cachedEUTText = calculateEUT(currentTick);
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

    // 记录/清理方法已迁移至 FormatUtil（T4 公共工具类提取）

    /**
     * 计算 EU/t（300 秒窗口首末两点斜率法）
     * <p>
     * 算法：(lastValue - firstValue) / (lastTick - firstTick)
     * 边界情况：
     * <ul>
     * <li>历史为空或仅 1 个点：显示"无变化/计算中"</li>
     * <li>首末 tick 相同（tickDiff &lt;= 0）：返回 0.0</li>
     * <li>已记录但无变化：显示 "0.00" EU/t</li>
     * </ul>
     */
    private static String calculateEUT(long currentTick) {
        // 先清理窗口外样本（FormatUtil 静态方法）
        FormatUtil.purgeExpired(measurementHistory, currentTick, FormatUtil.WINDOW_TICKS);

        // 未记录前（窗口内样本不足）显示"无变化/计算中"
        if (measurementHistory.size() < 2) {
            return "§b" + StatCollector.translateToLocal("gtswn.hud.network.status")
                + ": §f"
                + StatCollector.translateToLocal("gtswn.hud.network.no.change");
        }

        // 计算 300 秒窗口首末两点斜率
        double euPerTick = 0.0;
        Measurement first = measurementHistory.get(0);
        Measurement last = measurementHistory.get(measurementHistory.size() - 1);
        long tickDiff = last.tick - first.tick;
        if (tickDiff > 0) {
            BigInteger diff = last.value.subtract(first.value);
            euPerTick = diff.doubleValue() / tickDiff;
        }

        // 格式化 EU/t（根据显示模式）
        String euPerTickStr;
        if (displayMode == 2) {
            // 科学计数法（10^幂格式）
            if (Math.abs(euPerTick) < 0.01) {
                euPerTickStr = "0.00";
            } else {
                int exponent = (int) Math.floor(Math.log10(Math.abs(euPerTick)));
                double coefficient = euPerTick / Math.pow(10, exponent);
                euPerTickStr = String.format("%.2f×10^%d", coefficient, exponent);
            }
        } else {
            // 常规计数
            if (Math.abs(euPerTick) < 0.01) {
                euPerTickStr = "0.00";
            } else if (Math.abs(euPerTick) < 1000) {
                euPerTickStr = String.format("%.2f", euPerTick);
            } else {
                // 大数值使用逗号分隔
                euPerTickStr = FormatUtil.formatNormalDouble(euPerTick);
            }
        }

        // 转换为 GT 的电流+电压等级格式
        String gtPowerText = GTTierUtil.formatGTPower(euPerTick);
        int gtTier = GTTierUtil.getGTTier(euPerTick); // 获取电压等级用于括号颜色

        // 判断是增加还是减少
        String status;
        String bracketColor = GTTierUtil.TIER_COLORS[gtTier]; // 使用电压等级颜色用于括号
        if (euPerTick > 0) {
            status = "§a↑ +" + euPerTickStr
                + " "
                + StatCollector.translateToLocal("gtswn.hud.eut.unit")
                + bracketColor
                + " ("
                + gtPowerText
                + ")";
        } else if (euPerTick < 0) {
            status = "§c↓ " + euPerTickStr
                + " "
                + StatCollector.translateToLocal("gtswn.hud.eut.unit")
                + bracketColor
                + " ("
                + gtPowerText
                + ")";
        } else {
            status = "§f= 0.00 " + StatCollector.translateToLocal("gtswn.hud.eut.unit");
        }

        return "§b" + StatCollector.translateToLocal("gtswn.hud.network.status") + ": " + status;
    }

    // 测量记录类、电压等级数组、格式化方法已迁移至 FormatUtil 与 GTTierUtil（T4 公共工具类提取）
}
