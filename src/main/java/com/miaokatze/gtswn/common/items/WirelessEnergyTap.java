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

    /** NBT 键名：上次触发的时间戳，防止短时间内重复触发 */
    private static final String NBT_LAST_USE_TIME = "LastUseTime";

    /** 防重复触发的间隔（毫秒） */
    private static final long INTERVAL_MILLIS = 200L;

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
     * 检查是否可以触发（防止短时间内重复触发）
     */
    private boolean canTrigger(ItemStack aStack) {
        ensureNBT(aStack);
        long now = System.currentTimeMillis();
        long lastTime = aStack.stackTagCompound.getLong(NBT_LAST_USE_TIME);
        if (now - lastTime < INTERVAL_MILLIS) {
            return false;
        }
        // 更新最后使用时间
        aStack.stackTagCompound.setLong(NBT_LAST_USE_TIME, now);
        return true;
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
     * 处理机器交互的逻辑（共享方法）
     */
    private void handleMachineInteraction(ItemStack stack, EntityPlayer player, World world, int x, int y, int z) {
        TileEntity te = world.getTileEntity(x, y, z);
        if (!(te instanceof IBasicEnergyContainer)) {
            player.addChatMessage(
                new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.tap.not_energy_container")));
            return;
        }

        IBasicEnergyContainer container = (IBasicEnergyContainer) te;

        // 检查是否有容量
        long capacity = container.getEUCapacity();
        if (capacity <= 0) {
            player.addChatMessage(new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.tap.no_capacity")));
            return;
        }

        // 获取电压
        long voltage = container.getInputVoltage();
        if (voltage <= 0) {
            // 尝试获取输出电压作为备选
            voltage = container.getOutputVoltage();
        }

        if (voltage <= 0) {
            player.addChatMessage(new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.tap.no_voltage")));
            return;
        }

        // 获取安培，默认2A
        long amperage = container.getInputAmperage();
        if (amperage <= 0) {
            amperage = 2;
            player.addChatMessage(
                new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.tap.default_amperage")));
        }

        // === 阶段1：实现电压等级自动检测和频率计算 ===
        // 1. 输出检测信息到聊天
        player.addChatMessage(new ChatComponentText("§a[链路终端] 检测到机器："));
        player.addChatMessage(new ChatComponentText("§7 电容: " + capacity + " EU"));
        player.addChatMessage(new ChatComponentText("§7 电压: " + voltage + " EU/t"));
        player.addChatMessage(new ChatComponentText("§7 电流: " + amperage + " A"));

        // 2. 计算最低交互频率（需要电容和电压）
        double minFrequencyTicks = Double.MAX_VALUE;
        boolean canCalculate = false;
        if (capacity > 0 && voltage > 0 && amperage > 0) {
            minFrequencyTicks = (double) capacity / (double) (amperage * voltage);
            canCalculate = true;
            player.addChatMessage(
                new ChatComponentText("§a 最低交互频率: " + String.format("%.1f", minFrequencyTicks) + " tick"));
        } else {
            player.addChatMessage(new ChatComponentText("§c 无法计算最低交互频率：缺少必要参数"));
        }

        // 3. 选择实际交互频率（20的倍数，取前面那个倍数）
        if (canCalculate) {
            int actualFrequencyTicks = ((int) (minFrequencyTicks / 20)) * 20;
            if (actualFrequencyTicks < 20) actualFrequencyTicks = 20; // 至少20tick
            player.addChatMessage(new ChatComponentText("§b 实际交互频率: " + actualFrequencyTicks + " tick（20的倍数）"));
        }

        // 占位符提示
        player.addChatMessage(new ChatComponentText("§e[链路终端] 链接成功，拟定链接对应电压等级覆盖板（占位符）"));
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
     * 处理右击方块事件（阻止打开GUI）
     */
    @Override
    public boolean onItemUseFirst(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
        float hitX, float hitY, float hitZ) {
        // 只在服务端处理
        if (world.isRemote) {
            return false;
        }

        // 检查是否可以触发（防止短时间内重复触发）
        if (!canTrigger(stack)) {
            return true; // 返回true阻止继续处理
        }

        // Shift+右键：切换模式，不与机器交互
        if (player.isSneaking()) {
            boolean newMode = toggleOutputMode(stack);
            stack.setItemDamage(newMode ? 1 : 0);
            String modeKey = newMode ? "gtswn.chat.tap.mode.output" : "gtswn.chat.tap.mode.input";
            String msg = StatCollector.translateToLocal(modeKey);
            player.addChatMessage(new ChatComponentText(msg));
            return true; // 返回true阻止继续处理
        }

        // 普通右键：与机器交互
        handleMachineInteraction(stack, player, world, x, y, z);
        return true; // 返回true阻止继续处理
    }

    /**
     * 处理右击空气事件
     */
    @Override
    public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {
        // 只在服务端处理
        if (world.isRemote) {
            return stack;
        }

        // 检查是否可以触发（防止短时间内重复触发）
        if (!canTrigger(stack)) {
            return stack;
        }

        // 只有 shift+右键才切换模式（对着空气）
        if (player.isSneaking()) {
            boolean newMode = toggleOutputMode(stack);
            stack.setItemDamage(newMode ? 1 : 0);
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
