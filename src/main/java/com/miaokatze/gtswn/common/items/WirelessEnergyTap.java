
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
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicMachineWithRecipe;
import gregtech.api.recipe.RecipeMaps;
import gregtech.api.util.GTUtility;
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

    /** NBT 键名：上一次触发的时间戳（v1.2.1 起改为世界 tick），防止短时间内重复触发 */
    private static final String NBT_LAST_USE_TIME = "LastUseTime";

    /** 防止重复触发的间隔（tick，4 tick ≈ 200ms @ 20TPS）/ Anti-repeat interval (ticks) */
    private static final long INTERVAL_TICKS = 4L;

    /** NBT 键名：绑定提示已显示次数(达到上限后不再提示) / Bind notify count (stops after reaching max) */
    private static final String NBT_BIND_NOTIFY_COUNT = "BindNotifyCount";

    /** 绑定提示最大显示次数 / Max bind notify times */
    private static final int MAX_BIND_NOTIFY = 10;

    /** 两个材质图标 */
    private net.minecraft.util.IIcon[] icons = new net.minecraft.util.IIcon[2];

    /**
     * 构造函数：初始化无线网络链路终端的基础属性
     */
    public WirelessEnergyTap() {
        super();
        // 设置未本地化名称 (Unlocalized Name)，用于关联语言文件
        setUnlocalizedName("wirelessEnergyTap");
        // 默认材质由 registerIcons + getIconFromDamage 处理，无需在此 setTextureName（v1.2.1 移除冗余调用）
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
     * <p>
     * v1.2.1 改进：改用 {@code world.getTotalWorldTime()} 替代 {@code System.currentTimeMillis()}，
     * 与游戏时间同步，避免服务端卡顿时 TPS 波动导致时间判断失真，且更符合 Minecraft 惯例。
     * <p>
     * v1.2.2 修复：兼容 v1.2.1 之前用 {@code System.currentTimeMillis()} 存储的老 NBT。
     * 老存档中已使用过的链路终端物品 NBT 里 {@code LastUseTime} 存的是毫秒时间戳（约 1.7×10^12），
     * 而新代码 {@code now} 是 game tick（通常 &lt; 10^8），{@code now - lastTime} 会是巨大负数 &lt; {@code INTERVAL_TICKS}，
     * 导致 canTrigger 永远返回 false，链路终端完全失效（无法附着覆盖板、无法切换模式）。
     * 修复：检测 lastTime 是否超过 game tick 合理上限（10^10），若超过视为老数据重置为 0。
     *
     * @param aStack 物品栈
     * @param world  当前世界（用于获取世界 tick）
     * @return 允许触发返回 true，仍在冷却中返回 false
     */
    private boolean canTrigger(ItemStack aStack, World world) {
        ensureNBT(aStack);
        long now = world.getTotalWorldTime();
        long lastTime = aStack.stackTagCompound.getLong(NBT_LAST_USE_TIME);
        // v1.2.2 兼容性修复：老 NBT 存的是毫秒时间戳（~1.7×10^12），新代码用 game tick（~10^7）
        // 超过 10^10 视为老数据，重置为 0 避免负数差值导致 canTrigger 永远返回 false
        if (lastTime > 10_000_000_000L) {
            lastTime = 0;
        }
        if (now - lastTime < INTERVAL_TICKS) {
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
     * 静态方法：获取物品的输出模式（供客户端渲染器等外部调用）
     * <p>
     * 返回 true = 动力模式（紫色辅助线），false = 能源模式（黄色辅助线）。
     * 不修改 NBT，仅读取。
     *
     * @param stack 物品栈
     * @return 是否为动力模式
     */
    public static boolean getOutputModeStatic(ItemStack stack) {
        if (stack == null || stack.stackTagCompound == null) {
            return false; // 默认能源模式
        }
        return stack.stackTagCompound.getBoolean("OutputMode");
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
     * 绑定成功后提示玩家:覆盖板绑定的是机器拥有者的电网,请确认团队已共享电网。
     * 前 MAX_BIND_NOTIFY 次显示,之后不再提示。计数写入链路终端 NBT。
     * Notify player after successful bind: cover binds to machine owner's grid, confirm team shared grid.
     * Shown first MAX_BIND_NOTIFY times, then suppressed. Count stored in tap NBT.
     */
    private void notifyBindOwner(ItemStack stack, EntityPlayer player) {
        ensureNBT(stack);
        int count = stack.stackTagCompound.getInteger(NBT_BIND_NOTIFY_COUNT);
        if (count >= MAX_BIND_NOTIFY) return;
        int remaining = MAX_BIND_NOTIFY - count;
        String msg = String.format(StatCollector.translateToLocal("gtswn.chat.tap.bind_notify"), remaining);
        player.addChatMessage(new ChatComponentText(msg));
        stack.stackTagCompound.setInteger(NBT_BIND_NOTIFY_COUNT, count + 1);
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
        int side, float hitX, float hitY, float hitZ) {
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
            // 使用 GT 工具同款九宫格换算：点击面中心=原面，边中=相邻面，角=对侧面
            // 使链路终端支持像 GT 扳手/覆盖板工具一样通过方块边角向其他面放置覆盖板
            ForgeDirection targetSide = GTUtility
                .determineWrenchingSide(ForgeDirection.getOrientation(side), hitX, hitY, hitZ);

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

            // === 目标面已有外来覆盖板时终止附着，防止挤掉原有覆盖板 ===
            // 本 mod 覆盖板已在上方分支被移除，能走到这里说明 targetSide 上的覆盖板必为外来覆盖板
            // 必须检测 targetSide（九宫格换算的预计附加面）而非原始 side，与下方 placeCover 严格同面
            if (coverable.hasCoverAtSide(targetSide)) {
                player.addChatMessage(
                    new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.tap.cover_exists")));
                return;
            }

            // 1. 检查是否有电容
            long capacity = container.getEUCapacity();
            if (capacity <= 0) {
                player.addChatMessage(
                    new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.tap.no_capacity")));
                return;
            }

            // 获取当前输出模式
            boolean outputMode = getOutputMode(stack);

            if (outputMode) {
                // === 动力模式:虚拟导线,读取机器输出 V/A 取电 ===
                // Dynamo cover reads machine output V/A per tick, drains into capacity buffer
                attachDynamoCoverForFullTake(stack, player, coverable, targetSide);
                return;
            }

            // === 能源模式:读取电压/安培,输出检测信息 ===
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

            // 4. 检查是否为单方块电弧炉(通过配方表精确识别),如果是则强制 4A
            // Check if machine is a single-block arc furnace (via recipe map), force 4A if so
            if (te instanceof BaseMetaTileEntity bmte) {
                if (bmte.getMetaTileEntity() instanceof MTEBasicMachineWithRecipe mte
                    && mte.getRecipeMap() == RecipeMaps.arcFurnaceRecipes) {
                    amperage = 4;
                }
            }

            // 5. 计算电容量 = 电压 × 安培 × 800 tick
            // Calculate cover capacity = voltage × amperage × 800 ticks
            long coverCapacity = voltage * amperage * 800L;

            // 6. 输出检测信息到聊天
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
                    StatCollector.translateToLocal("gtswn.chat.tap.cover_capacity") + coverCapacity + " EU"));

            // === 准备附着我们的覆盖板（能源模式） ===
            ItemStack coverStack = GTSWNItemList.GTswn_Cover_Energy_Wireless.get(1);

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
                String modeText = StatCollector.translateToLocal("gtswn.chat.tap.mode_energy");
                player.addChatMessage(
                    new ChatComponentText(
                        StatCollector.translateToLocal("gtswn.chat.tap.attaching") + modeText
                            + StatCollector.translateToLocal("gtswn.chat.tap.attaching_suffix")));
                placer.placeCover(player, coverStack, coverable, targetSide);

                // 4. 找到刚附着的覆盖板并配置它
                Cover placedCover = coverable.getCoverAtSide(targetSide);
                if (placedCover instanceof GTswn_Cover_EnergyWireless) {
                    // v1.2.1 改进：使用 Math.toIntExact 替代 (int) 强转，溢出时抛出异常暴露问题而非静默截断
                    // GT 电压/安培实际不会超出 int 范围（MAX 级约 2^30），此处 toIntExact 安全
                    ((GTswn_Cover_EnergyWireless) placedCover)
                        .configure(Math.toIntExact(voltage), Math.toIntExact(amperage));
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
                        StatCollector.translateToLocal("gtswn.chat.tap.cover_capacity") + coverCapacity + " EU"));
                // 6. 绑定提示(前 MAX_BIND_NOTIFY 次)
                notifyBindOwner(stack, player);
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
     * 动力模式附着覆盖板（来着全收）
     * <p>
     * 动力覆盖板每 tick 读取机器输出 V/A 并取电,存入电容量为 2^63-1 的内部缓冲池,每 600 tick 上送到电网。
     * 无需读取电压/安培/间隔参数,configure() 不接受参数。
     * 不输出 chat 调试信息,只提示链接成功+模式。
     *
     * @param stack      链路终端物品栈(用于读写绑定提示计数NBT)
     * @param player     操作玩家
     * @param coverable  目标机器
     * @param targetSide 附着面
     */
    private void attachDynamoCoverForFullTake(ItemStack stack, EntityPlayer player, ICoverable coverable,
        ForgeDirection targetSide) {
        ItemStack coverStack = GTSWNItemList.GTswn_Cover_Dynamo_Wireless.get(1);
        if (coverStack == null) {
            player.addChatMessage(
                new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.tap.cannot_get_cover")));
            return;
        }

        CoverPlacer placer = CoverRegistry.getCoverPlacer(coverStack);
        if (!placer.isCoverPlaceable(targetSide, coverStack, coverable)) {
            player.addChatMessage(
                new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.tap.cannot_place_cover")));
            return;
        }

        // 放置覆盖板
        // 动力模式:虚拟导线,每 tick 读取机器输出 V/A 取电,每 600 tick 上送电网
        // Dynamo mode: virtual cable, drains V×A per tick, uploads to network every 600 ticks
        String modeText = StatCollector.translateToLocal("gtswn.chat.tap.mode_power");
        player.addChatMessage(
            new ChatComponentText(
                StatCollector.translateToLocal("gtswn.chat.tap.attaching") + modeText
                    + StatCollector.translateToLocal("gtswn.chat.tap.attaching_suffix")));
        placer.placeCover(player, coverStack, coverable, targetSide);

        // 配置覆盖板:无需参数
        Cover placedCover = coverable.getCoverAtSide(targetSide);
        if (placedCover instanceof GTswn_Cover_DynamoWireless) {
            ((GTswn_Cover_DynamoWireless) placedCover).configure();
        }

        // 只输出简洁成功信息
        player.addChatMessage(new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.tap.link_success")));
        player.addChatMessage(
            new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.tap.mode_text") + modeText));
        // 绑定提示(前 MAX_BIND_NOTIFY 次)
        notifyBindOwner(stack, player);
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
        if (!canTrigger(stack, world)) {
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
        handleMachineInteraction(stack, player, world, x, y, z, side, hitX, hitY, hitZ);
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
        if (!canTrigger(stack, world)) {
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
