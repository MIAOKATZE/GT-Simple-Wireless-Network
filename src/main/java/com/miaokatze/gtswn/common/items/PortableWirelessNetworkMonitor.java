package com.miaokatze.gtswn.common.items;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.hud.WirelessMonitorHUD;

import gregtech.common.misc.WirelessNetworkManager;

/**
 * 便携式无线网络监测终端
 * <p>
 * 一个便携式的无线电网能量监控设备，允许玩家随时查看所属团队的无线电网能量储备。
 * 功能特性：
 * - 首次右击时自动绑定拥有者（玩家 UUID）
 * - 已绑定状态下右击显示拥有者的无线电网能量值
 * - Shift + 右击覆盖绑定为当前玩家（无论是否已绑定）
 */
public class PortableWirelessNetworkMonitor extends Item {

    /** NBT 键名：存储拥有者的 UUID 字符串 */
    private static final String NBT_OWNER_UUID = "OwnerUUID";

    /** NBT 键名：标记是否已初始化拥有者 */
    private static final String NBT_INITIALIZED = "Initialized";

    /** NBT 键名：存储拥有者的玩家名称（用于 tooltip 显示） */
    private static final String NBT_OWNER_NAME = "OwnerName";

    /** NBT 键名：HUD 显示模式（0=关闭，1=常规计数，2=科学计数） */
    private static final String NBT_HUD_MODE = "HUDMode";

    /**
     * 构造函数：初始化便携式无线网络监测终端的基础属性
     */
    public PortableWirelessNetworkMonitor() {
        super();
        // 设置未本地化名称 (Unlocalized Name)，用于关联语言文件
        setUnlocalizedName("PortableWirelessNetworkMonitor_GTswn");
        // 设置材质路径 (Texture Name)，指向 assets/gtswn/textures/items/Portable_Wireless_Network_Monitor.png
        setTextureName("gtswn:Portable_Wireless_Network_Monitor");
        // 设置创造模式标签页，使其能在游戏中被玩家获取
        setCreativeTab(CreativeTabs.tabMisc);
        // 设置最大堆叠数量为 1（便携式设备通常不可堆叠）
        setMaxStackSize(1);
        // 允许显示 tooltip
        setHasSubtypes(true);
    }

    /**
     * 处理右击事件
     * - 未绑定时普通右击：绑定当前玩家为拥有者
     * - 已绑定时普通右击：切换 HUD 显示模式（关闭/常规/科学）
     * - Shift + 右击：覆盖绑定为当前玩家
     */
    @Override
    public ItemStack onItemRightClick(ItemStack aStack, World aWorld, EntityPlayer aPlayer) {
        // 确保 NBT 已初始化
        ensureNBT(aStack);

        // 检测是否按住 Shift 键
        if (aPlayer.isSneaking()) {
            // Shift + 右击：覆盖绑定为当前玩家
            updateOwner(aStack, aPlayer);
        } else {
            // 普通右击：检查是否已绑定
            if (isBound(aStack)) {
                // 已绑定：切换 HUD 显示模式
                toggleHUDMode(aStack, aPlayer);
            } else {
                // 未绑定：绑定当前玩家为拥有者
                bindOwner(aStack, aPlayer);
            }
        }

        return aStack;
    }

    /**
     * 确保 ItemStack 的 NBT 标签已创建
     */
    private void ensureNBT(ItemStack aStack) {
        if (aStack.stackTagCompound == null) {
            aStack.stackTagCompound = new NBTTagCompound();
        }
    }

    /**
     * 检查物品是否已绑定拥有者
     */
    private boolean isBound(ItemStack aStack) {
        ensureNBT(aStack);
        return aStack.stackTagCompound.getBoolean(NBT_INITIALIZED) && aStack.stackTagCompound.hasKey(NBT_OWNER_UUID);
    }

    /**
     * 绑定当前玩家为拥有者（仅用于未绑定状态）
     */
    private void bindOwner(ItemStack aStack, EntityPlayer aPlayer) {
        UUID playerUUID = aPlayer.getUniqueID();
        aStack.stackTagCompound.setString(NBT_OWNER_UUID, playerUUID.toString());
        aStack.stackTagCompound.setBoolean(NBT_INITIALIZED, true);
        // 存储玩家名称用于 tooltip 显示
        aStack.stackTagCompound.setString(NBT_OWNER_NAME, aPlayer.getCommandSenderName());
        // 默认 HUD 模式为关闭
        aStack.stackTagCompound.setInteger(NBT_HUD_MODE, 0);

        // 向玩家发送提示信息
        String boundMsg = StatCollector
            .translateToLocalFormatted("gtswn.chat.monitor.bound", aPlayer.getCommandSenderName());
        String hintMsg = StatCollector.translateToLocal("gtswn.chat.monitor.bound.hint");
        aPlayer.addChatMessage(new ChatComponentText(boundMsg));
        aPlayer.addChatMessage(new ChatComponentText(hintMsg));
    }

    /**
     * 更新物品的拥有者为当前玩家（覆盖绑定）
     */
    private void updateOwner(ItemStack aStack, EntityPlayer aPlayer) {
        // 设置新拥有者（覆盖旧数据）
        UUID newOwnerUUID = aPlayer.getUniqueID();
        aStack.stackTagCompound.setString(NBT_OWNER_UUID, newOwnerUUID.toString());
        aStack.stackTagCompound.setBoolean(NBT_INITIALIZED, true);
        // 更新玩家名称用于 tooltip 显示
        aStack.stackTagCompound.setString(NBT_OWNER_NAME, aPlayer.getCommandSenderName());
        // 重置 HUD 模式为关闭
        aStack.stackTagCompound.setInteger(NBT_HUD_MODE, 0);

        // 发送提示信息
        String reboundMsg = StatCollector
            .translateToLocalFormatted("gtswn.chat.monitor.rebound", aPlayer.getCommandSenderName());
        aPlayer.addChatMessage(new ChatComponentText(reboundMsg));
    }

    /**
     * 切换 HUD 显示模式（关闭/常规/科学）
     */
    private void toggleHUDMode(ItemStack aStack, EntityPlayer aPlayer) {
        // 获取当前 HUD 模式
        int currentMode = aStack.stackTagCompound.getInteger(NBT_HUD_MODE);
        int newMode = (currentMode + 1) % 3; // 0 -> 1 -> 2 -> 0

        // 更新 HUD 模式（两端都执行）
        aStack.stackTagCompound.setInteger(NBT_HUD_MODE, newMode);

        // 仅在客户端同步到 HUD 管理器
        if (aPlayer.worldObj.isRemote) {
            String ownerUUID = aStack.stackTagCompound.getString(NBT_OWNER_UUID);
            WirelessMonitorHUD.setEnabled(newMode > 0, ownerUUID);
            WirelessMonitorHUD.setDisplayMode(newMode);
        }

        // 仅在客户端发送提示信息
        if (aPlayer.worldObj.isRemote) {
            String modeKey;
            switch (newMode) {
                case 0:
                    modeKey = "gtswn.chat.monitor.mode.off";
                    break;
                case 1:
                    modeKey = "gtswn.chat.monitor.mode.normal";
                    break;
                case 2:
                    modeKey = "gtswn.chat.monitor.mode.scientific";
                    break;
                default:
                    modeKey = "gtswn.chat.monitor.mode.off";
            }
            String modeText = StatCollector.translateToLocal(modeKey);
            String switchMsg = StatCollector.translateToLocalFormatted("gtswn.chat.monitor.mode.switch", modeText);
            aPlayer.addChatMessage(new ChatComponentText(switchMsg));
        }
    }

    /**
     * 显示拥有者的无线电网能量值（仅在已绑定状态下调用）
     */
    private void displayWirelessEnergy(ItemStack aStack, EntityPlayer aPlayer) {
        // 读取拥有者 UUID（此时必定存在）
        UUID ownerUUID;
        try {
            String uuidString = aStack.stackTagCompound.getString(NBT_OWNER_UUID);
            ownerUUID = UUID.fromString(uuidString);
        } catch (Exception e) {
            // 理论上不会到达这里，因为 isBound() 已检查
            aPlayer.addChatMessage(new ChatComponentText("§c[便携监测终端] 数据异常！请 Shift + 右击重新绑定。"));
            return;
        }

        // 调用 GT5U 的 WirelessNetworkManager 获取无线电网能量
        BigInteger wirelessEU = WirelessNetworkManager.getUserEU(ownerUUID);

        // 格式化能量值（使用标准数字格式）
        String euFormatted = formatBigInteger(wirelessEU);

        // 发送聊天消息
        String energyMsg = StatCollector.translateToLocalFormatted("gtswn.chat.monitor.energy", euFormatted);
        aPlayer.addChatMessage(new ChatComponentText(energyMsg));

        // 额外提示：如何更新拥有者
        String hintMsg = StatCollector.translateToLocal("gtswn.chat.monitor.rebind.hint");
        aPlayer.addChatMessage(new ChatComponentText(hintMsg));
    }

    /**
     * 格式化 BigInteger 为易读字符串
     * 对于超大数值使用逗号分隔
     */
    private String formatBigInteger(BigInteger value) {
        if (value == null) {
            return "0";
        }

        // 如果数值较小，直接显示
        if (value.compareTo(BigInteger.valueOf(1_000_000L)) < 0) {
            return value.toString();
        }

        // 对于大数值，使用逗号分隔
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
     * 添加物品的额外信息（Tooltip）
     * 显示拥有者信息和操作提示
     */
    @Override
    public void addInformation(ItemStack aStack, EntityPlayer aPlayer, List<String> aList, boolean aF3_H) {
        // 确保 NBT 已初始化
        if (aStack.stackTagCompound == null) {
            aList.add("§7未绑定拥有者");
        } else {
            // 检查是否已绑定
            boolean isInitialized = aStack.stackTagCompound.getBoolean(NBT_INITIALIZED);
            String ownerName = aStack.stackTagCompound.getString(NBT_OWNER_NAME);

            if (isInitialized && ownerName != null && !ownerName.isEmpty()) {
                // 已绑定：显示拥有者名称（亮蓝色）
                aList.add("§b§l拥有者: " + ownerName);

                // 显示当前 HUD 模式
                int hudMode = aStack.stackTagCompound.getInteger(NBT_HUD_MODE);
                String modeKey;
                switch (hudMode) {
                    case 0:
                        modeKey = "gtswn.tooltip.monitor.hud.mode.off";
                        break;
                    case 1:
                        modeKey = "gtswn.tooltip.monitor.hud.mode.normal";
                        break;
                    case 2:
                        modeKey = "gtswn.tooltip.monitor.hud.mode.scientific";
                        break;
                    default:
                        modeKey = "gtswn.tooltip.monitor.hud.mode.off";
                }
                aList.add(StatCollector.translateToLocal(modeKey));
            } else {
                // 未绑定
                aList.add("§7未绑定拥有者");
            }
        }

        // 添加空行
        aList.add("");
        // 操作提示
        aList.add(StatCollector.translateToLocal("gtswn.tooltip.monitor.usage"));
    }
}
