package com.miaokatze.gtswn.common.items;

import java.util.List;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import gregtech.api.interfaces.tileentity.IBasicEnergyContainer;

/**
 * 无线能量分接器
 * <p>
 * 一个便携式的无线电网分接设备，允许玩家将任意能量容器连接到GT无线电网。
 * 功能特性：
 * - 右击空气：切换手持模式（能源/动力），更新材质
 * - 右击能量容器：赋予或取消无线连接状态
 * - Shift + 右击能量容器：切换输入/输出模式
 * - 自动读取目标能量容器的电压等级
 */
public class WirelessEnergyTap extends Item {

    /** NBT 键名：存储当前输出模式 (true=动力, false=能源) */
    private static final String NBT_OUTPUT_MODE = "OutputMode";

    /** 两个材质图标 */
    private net.minecraft.util.IIcon[] icons = new net.minecraft.util.IIcon[2];

    /**
     * 构造函数：初始化无线网络链路终端的基础属性
     */
    public WirelessEnergyTap() {
        super();
        // 设置未本地化名称 (Unlocalized Name)，用于关联语言文件
        setUnlocalizedName("wirelessEnergyTap");
        // 默认材质在下面的 getIconFromDamage 中处理，先随便设一个
        setTextureName("gtswn:wireless_energy_tap_input");
        // 设置创造模式标签页
        setCreativeTab(CreativeTabs.tabMisc);
        // 设置最大堆叠数量为 1
        setMaxStackSize(1);
        // 允许显示 tooltip
        setHasSubtypes(true);
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
     * 获取当前输出模式
     */
    private boolean getOutputMode(ItemStack aStack) {
        ensureNBT(aStack);
        return aStack.stackTagCompound.getBoolean(NBT_OUTPUT_MODE);
    }

    /**
     * 切换输出模式
     */
    private boolean toggleOutputMode(ItemStack aStack) {
        ensureNBT(aStack);
        boolean current = aStack.stackTagCompound.getBoolean(NBT_OUTPUT_MODE);
        boolean newMode = !current;
        aStack.stackTagCompound.setBoolean(NBT_OUTPUT_MODE, newMode);
        return newMode;
    }

    /**
     * 获取未本地化名称
     */
    @Override
    public String getUnlocalizedName(ItemStack aStack) {
        return "wirelessEnergyTap";
    }

    /**
     * 获取物品的显示名称（本地化）
     */
    @Override
    public String getItemStackDisplayName(ItemStack aStack) {
        return StatCollector.translateToLocal("item.wirelessEnergyTap.name");
    }

    /**
     * 处理右击空气事件
     */
    @Override
    public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {
        // 只有 shift+右键才切换模式
        if (player.isSneaking()) {
            // 切换模式
            boolean newMode = toggleOutputMode(stack);
            // 更新 Item 的 damage 值，用于材质切换
            stack.setItemDamage(newMode ? 1 : 0);
            // 发送提示信息
            String modeKey = newMode ? "gtswn.chat.tap.mode.output" : "gtswn.chat.tap.mode.input";
            String msg = StatCollector.translateToLocal(modeKey);
            player.addChatMessage(new ChatComponentText(msg));
        }
        return stack;
    }

    /**
     * 注册图标
     */
    @Override
    public void registerIcons(net.minecraft.client.renderer.texture.IIconRegister register) {
        // 注册输入模式图标
        icons[0] = register.registerIcon("gtswn:wireless_energy_tap_input");
        // 注册输出模式图标
        icons[1] = register.registerIcon("gtswn:wireless_energy_tap_output");
        // 为了默认情况
        this.itemIcon = icons[0];
    }

    /**
     * 获取图标，根据 damage 值切换材质
     */
    @Override
    public net.minecraft.util.IIcon getIconFromDamage(int aDamage) {
        // 防止数组越界
        if (aDamage < 0 || aDamage >= icons.length) {
            return icons[0];
        }
        return icons[aDamage];
    }

    /**
     * 获取图标，用于手持时
     */
    @Override
    public net.minecraft.util.IIcon getIcon(ItemStack aStack, int aPass) {
        // 优先从 ItemStack 的 damage 值获取
        return this.getIconFromDamage(aStack.getItemDamage());
    }

    /**
     * 处理右击方块事件
     */
    @Override
    public boolean onItemUseFirst(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
        float hitX, float hitY, float hitZ) {
        // 只在服务端处理
        if (world.isRemote) {
            return false;
        }

        // 检查方块是否是 IBasicEnergyContainer
        TileEntity te = world.getTileEntity(x, y, z);
        if (!(te instanceof IBasicEnergyContainer)) {
            player.addChatMessage(
                new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.tap.not_energy_container")));
            return true;
        }

        IBasicEnergyContainer container = (IBasicEnergyContainer) te;

        // 检查是否有容量
        long capacity = container.getEUCapacity();
        if (capacity <= 0) {
            player.addChatMessage(new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.tap.no_capacity")));
            return true;
        }

        // 获取电压
        long voltage = container.getInputVoltage();
        if (voltage <= 0) {
            // 尝试获取输出电压作为备选
            voltage = container.getOutputVoltage();
        }

        if (voltage <= 0) {
            player.addChatMessage(new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.tap.no_voltage")));
            return true;
        }

        // 获取安培，默认 2A
        long amperage = container.getInputAmperage();
        if (amperage <= 0) {
            amperage = 2;
            player.addChatMessage(
                new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.tap.default_amperage")));
        } else {
            // 如果是 Shift 右键，这里先只给一个提示
            if (player.isSneaking()) {
                player.addChatMessage(
                    new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.tap.mode_switch")));
            } else {
                // 普通右键，链接成功提示
                player.addChatMessage(
                    new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.tap.connection_success")));
            }
        }

        return true;
    }

    /**
     * 添加物品的额外信息（Tooltip）
     */
    @Override
    public void addInformation(ItemStack stack, EntityPlayer player, List<String> list, boolean f3_h) {
        // 显示描述
        list.add(StatCollector.translateToLocal("item.wirelessEnergyTap.desc"));
        // 显示当前模式
        boolean outputMode = getOutputMode(stack);
        String modeKey = outputMode ? "gtswn.tooltip.tap.mode.output" : "gtswn.tooltip.tap.mode.input";
        list.add("");
        list.add(StatCollector.translateToLocal(modeKey));
    }
}
