
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
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.covers.GTswn_Cover_DynamoWireless;
import com.miaokatze.gtswn.common.covers.GTswn_Cover_EnergyWireless;

import gregtech.api.covers.CoverPlacer;
import gregtech.api.covers.CoverRegistry;
import gregtech.api.interfaces.tileentity.IBasicEnergyContainer;
import gregtech.api.interfaces.tileentity.ICoverable;
import gregtech.common.covers.Cover;

/**
 * 无线网络链路终端
 * <p>
 * 一个便携式的无线网络分接设备，允许玩家将任意能量容器连接到GT无线网络。
 * 功能特性：
 * - 右键空气：切换手持模式（能源/动力），更新材质
 * - 右键能量容器：赋予或取消无线连接状态
 * - Shift + 右键能量容器：切换输入/输出模式
 * - 自动读取目标能量容器的电压等级
 */
public class WirelessEnergyTap extends Item {

    /** NBT 键名：存储当前输出模式(true=动力, false=能源) */
    private static final String NBT_OUTPUT_MODE = "OutputMode";

    /** NBT 键名：上一次触发的时间戳，防止短时间内重复触发 */
    private static final String NBT_LAST_USE_TIME = "LastUseTime";

    /** 防止重复触发的间隔（毫秒） */
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
     * 检查指定的覆盖板是否是我们的GTswn覆盖板
     */
    private boolean isOurGTswnCover(ItemStack coverStack) {
        if (coverStack == null || coverStack.getItem() == null) {
            return false;
        }
        Item item = coverStack.getItem();
        return item instanceof GTSwnCoverEnergyWireless || item instanceof GTSwnCoverDynamoWireless;
    }

    /**
     * 处理机器交互的逻辑（公共方法）
     */
    private void handleMachineInteraction(ItemStack stack, EntityPlayer player, World world, int x, int y, int z,
        int side) {
        TileEntity te = world.getTileEntity(x, y, z);
        if (!(te instanceof IBasicEnergyContainer)) {
            player.addChatMessage(
                new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.tap.not_energy_container")));
            return;
        }

        IBasicEnergyContainer container = (IBasicEnergyContainer) te;

        // === 首先检查：整个机器是否有我们的GTswn覆盖板 ===
        if (te instanceof ICoverable) {
            ICoverable coverable = (ICoverable) te;
            ForgeDirection targetSide = ForgeDirection.getOrientation(side);

            // 检查所有面是否有我们的覆盖板
            boolean hasOurCover = false;
            ForgeDirection coverSide = null;
            ItemStack foundCover = null;
            for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
                ItemStack coverItem = coverable.getCoverItemAtSide(dir);
                if (isOurGTswnCover(coverItem)) {
                    hasOurCover = true;
                    coverSide = dir;
                    foundCover = coverItem;
                    break;
                }
            }

            // === 如果有我们的覆盖板，移除它 ===
            if (hasOurCover) {
                ItemStack removed = coverable.detachCover(coverSide);
                player.addChatMessage(
                    new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.tap.unlink_success")));
                if (removed != null) {
                    player.addChatMessage(
                        new ChatComponentText(
                            StatCollector.translateToLocal("gtswn.chat.tap.cover_item") + removed.getDisplayName()));
                }
                return;
            }

            // === 如果没有我们的覆盖板，继续检测参数，然后附着 ===

            // 1. 检查是否有电容
            long capacity = container.getEUCapacity();
            if (capacity <= 0) {
                player.addChatMessage(
                    new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.tap.no_capacity")));
                return;
            }

            // 2. 获取电压
            long voltage = container.getInputVoltage();
            if (voltage <= 0) {
                // 尝试获取输出电压作为备份
                voltage = container.getOutputVoltage();
            }

            if (voltage <= 0) {
                player
                    .addChatMessage(new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.tap.no_voltage")));
                return;
            }

            // 3. 获取安培，默认2A，不低于2A
            long amperage = container.getInputAmperage();
            if (amperage <= 1) {
                amperage = 2;
            }

            // 4. 计算理论最长间隔（minFrequencyTicks）= capacity / (amperage * voltage)
            double minFrequencyTicks = Double.MAX_VALUE;
            boolean canCalculate = false;
            if (capacity > 0 && voltage > 0 && amperage > 0) {
                minFrequencyTicks = (double) capacity / (double) (amperage * voltage);
                canCalculate = true;
            }

            // 5. 计算实际交互间隔：
            // 如果 <5，取2 tick
            // 如果5-10，取5 tick
            // 如果≥10，取最近10的倍数减10，最小10 tick
            int actualFrequencyTicks = 10;
            if (canCalculate) {
                if (minFrequencyTicks < 5) {
                    actualFrequencyTicks = 2;
                } else if (minFrequencyTicks < 10) {
                    actualFrequencyTicks = 5;
                } else {
                    // 先取最近的10的整数倍
                    int roundedTo10 = ((int) (minFrequencyTicks / 10)) * 10;
                    // 再减10
                    actualFrequencyTicks = roundedTo10 - 10;
                    // 确保不低于10
                    if (actualFrequencyTicks < 10) {
                        actualFrequencyTicks = 10;
                    }
                }
            }

            // 6. 计算单次传输能量 = 实际间隔 × 电压 × 电流 × 1.1
            long singleTransferEnergy = (long) (actualFrequencyTicks * voltage * amperage * 1.1);

            // 7. 输出检测信息到聊天
            player.addChatMessage(
                new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.tap.machine_detected")));
            player.addChatMessage(
                new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.tap.capacity") + capacity + " EU"));
            player.addChatMessage(
                new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.tap.voltage") + voltage + " EU/t"));
            player.addChatMessage(
                new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.tap.amperage") + amperage + " A"));
            player.addChatMessage(
                new ChatComponentText(
                    StatCollector.translateToLocal("gtswn.chat.tap.theoretical_interval") + (long) minFrequencyTicks
                        + " tick"));
            player.addChatMessage(
                new ChatComponentText(
                    StatCollector.translateToLocal("gtswn.chat.tap.actual_interval") + actualFrequencyTicks + " tick"));
            player.addChatMessage(
                new ChatComponentText(
                    StatCollector.translateToLocal("gtswn.chat.tap.single_transfer") + singleTransferEnergy
                        + " EU (1.1倍过冲)"));

            // === 准备附着我们的覆盖板 ===
            boolean outputMode = getOutputMode(stack);
            ItemStack coverStack = outputMode ? GTSWNItemList.GTswn_Cover_Dynamo_Wireless.get(1)
                : GTSWNItemList.GTswn_Cover_Energy_Wireless.get(1);

            if (coverStack != null) {
                // 1. 获取CoverPlacer
                CoverPlacer placer = CoverRegistry.getCoverPlacer(coverStack);

                // 2. 检查是否可以放置
                if (!placer.isCoverPlaceable(targetSide, coverStack, coverable)) {
                    player.addChatMessage(
                        new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.tap.cannot_place_cover")));
                    return;
                }

                // 3. 使用CoverPlacer放置
                String modeText = outputMode ? StatCollector.translateToLocal("gtswn.chat.tap.mode_power")
                    : StatCollector.translateToLocal("gtswn.chat.tap.mode_energy");
                player.addChatMessage(
                    new ChatComponentText(
                        StatCollector.translateToLocal("gtswn.chat.tap.attaching") + modeText
                            + StatCollector.translateToLocal("gtswn.chat.tap.attaching_suffix")));
                placer.placeCover(player, coverStack, coverable, targetSide);

                // 4. 找到刚附着的覆盖板并配置它
                Cover placedCover = coverable.getCoverAtSide(targetSide);
                if (placedCover != null) {
                    if (outputMode && placedCover instanceof GTswn_Cover_DynamoWireless) {
                        ((GTswn_Cover_DynamoWireless) placedCover)
                            .configure((int) voltage, (int) amperage, actualFrequencyTicks, singleTransferEnergy);
                    } else if (!outputMode && placedCover instanceof GTswn_Cover_EnergyWireless) {
                        ((GTswn_Cover_EnergyWireless) placedCover)
                            .configure((int) voltage, (int) amperage, actualFrequencyTicks, singleTransferEnergy);
                    }
                }

                // 5. 提示成功
                player.addChatMessage(
                    new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.tap.link_success")));
                player.addChatMessage(
                    new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.tap.mode_text") + modeText));
                player.addChatMessage(
                    new ChatComponentText(
                        StatCollector.translateToLocal("gtswn.chat.tap.voltage_tier") + voltage + " EU/t"));
                player.addChatMessage(
                    new ChatComponentText(
                        StatCollector.translateToLocal("gtswn.chat.tap.amperage_tier") + amperage + " A"));
                player.addChatMessage(
                    new ChatComponentText(
                        StatCollector.translateToLocal("gtswn.chat.tap.actual_interval_text") + actualFrequencyTicks
                            + " tick"));
                player.addChatMessage(
                    new ChatComponentText(
                        StatCollector.translateToLocal("gtswn.chat.tap.single_transfer_text") + singleTransferEnergy
                            + " EU"));
            } else {
                player.addChatMessage(
                    new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.tap.cannot_get_cover")));
            }
        } else {
            player.addChatMessage(
                new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.tap.no_cover_support")));
        }
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
     * 处理右键方块事件（阻止打开GUI）
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
        handleMachineInteraction(stack, player, world, x, y, z, side);
        return true; // 返回true阻止继续处理
    }

    /**
     * 处理右键空气事件
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
