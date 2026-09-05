
package com.miaokatze.gtswn.common.items;

import java.util.List;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.EnumAction;
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
import com.miaokatze.gtswn.common.covers.WirelessNodeRegistry;
import com.miaokatze.gtswn.common.util.CoverMaths;
import com.miaokatze.gtswn.common.util.LaserHatchUtil;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketRequestNodeReveal;

import gregtech.api.covers.CoverPlacer;
import gregtech.api.covers.CoverRegistry;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IBasicEnergyContainer;
import gregtech.api.interfaces.tileentity.ICoverable;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
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
 * - 对空按住不动 0.5 秒后开始蓄力（客户端动作动画切为拉弓），约 2.5 秒蓄满，松手触发扫描：
 * 向服务端请求显形周围已绑定的链路节点（客户端发请求，服务端查询回发）
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

    /**
     * 蓄力宽限期（tick）：10t = 0.5 秒。右击后按住不动的阶段——此期间客户端动作动画为
     * {@code none}（无拉弓姿态），松手直接走「未蓄满静默取消」既有语义；有效蓄力从宽限期
     * 结束后才开始感知。
     * Grace period (ticks): 0.5 s hold-still before charging is perceived.
     */
    private static final int GRACE_TICKS = 10;

    /** 有效蓄力时长（tick）：40t = 2 秒，宽限期结束后的蓄力窗口 / Effective charge window (ticks) */
    private static final int CHARGE_TICKS = 40;

    /**
     * 节点显形蓄力总时长（tick）= 宽限 + 有效蓄力 = 50t = 2.5 秒，与原版弓箭同款蓄力路径
     * （{@code setItemInUse} → {@code onPlayerStoppedUsing}），蓄满后释放才触发显形请求。
     * Total use duration (ticks) = grace + charge = 50 ticks (2.5 s), vanilla bow-style use path.
     */
    private static final int MAX_CHARGE_DURATION_TICKS = GRACE_TICKS + CHARGE_TICKS;

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
     * 显形冷却消费入口（公共）：语义与 {@link #canTrigger} 完全一致（4 tick 冷却检查 + LastUseTime 更新），
     * 供网络层 {@code NodeRevealRequestQueue} 主线程 drain 消费节点显形请求。
     * Consume the reveal cooldown (public): same semantics as canTrigger, for NodeRevealRequestQueue.
     *
     * @param player 请求玩家（取其当前手持物品栈参与冷却 NBT 读写）
     * @return 冷却已消耗（本次允许触发）返回 true；仍在冷却或未手持本物品返回 false
     */
    public boolean tryConsumeRevealCooldown(EntityPlayer player) {
        ItemStack held = player.getCurrentEquippedItem();
        if (held == null || held.getItem() != this) {
            return false;
        }
        return canTrigger(held, player.worldObj);
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

        // 激光仓检测：激光源仓/靶仓的 getOutputVoltage 被 isEnetOutput=false 门控为 0，需直读仓专属 V/A
        // Laser hatch: isEnetOutput=false gates getOutputVoltage to 0, read hatch-specific V/A directly
        IMetaTileEntity laserMte = (te instanceof IGregTechTileEntity igte) ? igte.getMetaTileEntity() : null;
        boolean laserHatch = laserMte != null && LaserHatchUtil.isLaserHatch(laserMte);

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
                // D3 兜底自愈：拆下成功即出册（幂等，坐标未册时无效果），防覆盖板被绕过终端移除后索引残留
                WirelessNodeRegistry.get(world)
                    .unregister(world, x, y, z);
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
                attachDynamoCoverForFullTake(stack, player, world, x, y, z, coverable, targetSide);
                return;
            }

            // === 能源模式:读取电压/安培,输出检测信息 ===
            // 2. 获取电压
            long voltage = container.getInputVoltage();
            if (voltage <= 0) {
                // 尝试获取输出电压作为备份
                voltage = container.getOutputVoltage();
            }

            // 激光仓专属电压（源仓=maxEUOutput()、靶仓=maxEUInput()，均 V[mTier] 恒非零）
            if (laserHatch) {
                voltage = LaserHatchUtil.getLaserVoltage(laserMte);
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

            // 激光仓专属安培（源仓=maxAmperesOut()、靶仓=maxAmperesIn()，恒>1，2A 兜底不生效）
            if (laserHatch) {
                amperage = LaserHatchUtil.getLaserAmperage(laserMte);
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
            // SWN-BUG-06+SWN-OPT-17：公式收敛至 CoverMaths.bufferCapacity 单源（与能源覆盖板实配共享）
            long coverCapacity = CoverMaths.bufferCapacity(voltage, amperage);

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

                // 激光仓绑定：消耗 1 根激光真空管（失败终止附着，不扣管）
                if (laserHatch && !LaserHatchUtil.consumeLaserPipe(player)) {
                    player.addChatMessage(
                        new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.tap.laser_missing_pipe")));
                    return;
                }
                if (laserHatch) {
                    player.addChatMessage(
                        new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.tap.laser_consumed")));
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
                    // D3 兜底自愈：placeCover+configure 成功即入册（能源节点，幂等）
                    WirelessNodeRegistry.get(world)
                        .register(world, x, y, z, WirelessNodeRegistry.TYPE_ENERGY);
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
     * @param world      目标世界(附着成功后入册 {@link WirelessNodeRegistry})
     * @param x          目标机器 X(入册坐标)
     * @param y          目标机器 Y(入册坐标)
     * @param z          目标机器 Z(入册坐标)
     * @param coverable  目标机器
     * @param targetSide 附着面
     */
    private void attachDynamoCoverForFullTake(ItemStack stack, EntityPlayer player, World world, int x, int y, int z,
        ICoverable coverable, ForgeDirection targetSide) {
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

        // 激光仓绑定：消耗 1 根激光真空管（失败终止附着，不扣管）
        // Laser hatch binding: consumes 1 Laser Vacuum Pipe (abort on missing pipe, no consumption on failure)
        IMetaTileEntity mte = (coverable instanceof IGregTechTileEntity igte) ? igte.getMetaTileEntity() : null;
        boolean laserHatch = mte != null && LaserHatchUtil.isLaserHatch(mte);
        if (laserHatch && !LaserHatchUtil.consumeLaserPipe(player)) {
            player.addChatMessage(
                new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.tap.laser_missing_pipe")));
            return;
        }
        if (laserHatch) {
            player
                .addChatMessage(new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.tap.laser_consumed")));
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
            // D3 兜底自愈：placeCover+configure 成功即入册（动力节点，幂等）
            WirelessNodeRegistry.get(world)
                .register(world, x, y, z, WirelessNodeRegistry.TYPE_DYNAMO);
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
     * <p>
     * Shift 分支（切换模式）：行为与原版保持一致——客户端直接 return，服务端 canTrigger 冷却检查
     * 通过后 toggle 模式 + chat 提示。
     * 非 Shift 分支（节点显形蓄力）：改为双端进入物品使用状态（原版弓箭同款蓄力路径，
     * vanilla 同步机制保证双端 {@code onPlayerStoppedUsing} 都会被回调），本方法不消耗冷却
     * （服务端 {@code NodeRevealRequestQueue} 主线程 drain 时经
     * {@link #tryConsumeRevealCooldown} 统一消费）。
     */
    @Override
    public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {
        // 只有 shift+右键才切换模式（对着空气）
        if (player.isSneaking()) {
            // 只在服务端处理
            if (world.isRemote) {
                return stack;
            }

            // 检查是否可以触发（防止短时间内重复触发）
            if (!canTrigger(stack, world)) {
                return stack;
            }

            boolean newMode = toggleOutputMode(stack);
            stack.setItemDamage(newMode ? 1 : 0);
            String modeKey = newMode ? "gtswn.chat.tap.mode.output" : "gtswn.chat.tap.mode.input";
            String msg = StatCollector.translateToLocal(modeKey);
            player.addChatMessage(new ChatComponentText(msg));
            return stack;
        }

        // 非 Shift：双端进入蓄力状态（vanilla 弓箭同款），释放时经 onPlayerStoppedUsing 触发显形请求
        player.setItemInUse(stack, getMaxItemUseDuration(stack));
        return stack;
    }

    /**
     * 蓄力总时长（tick）：50t = 2.5 秒（宽限 10t + 有效蓄力 40t，见
     * {@link #GRACE_TICKS} / {@link #CHARGE_TICKS}）。
     */
    @Override
    public int getMaxItemUseDuration(ItemStack stack) {
        return MAX_CHARGE_DURATION_TICKS;
    }

    /**
     * 蓄力动作动画（v1.7.21 动态分派）：宽限期内 {@code none}（无拉弓姿态），有效蓄力期
     * {@code bow}（原版拉弓视觉）。判定经 {@code GTSimpleWirelessNetwork.proxy} 虚分派——
     * 服务端默认实现恒返回 {@code none}（无副作用），客户端由 {@code ClientProxy} 覆写读取
     * 使用时长；本类共用路径零客户端类直接引用（专用服类加载安全）。
     */
    @Override
    public EnumAction getItemUseAction(ItemStack stack) {
        return GTSimpleWirelessNetwork.proxy.getTapUseAction(stack, MAX_CHARGE_DURATION_TICKS, GRACE_TICKS);
    }

    /**
     * 释放蓄力（双端回调，原版弓箭同款路径）：
     * <ul>
     * <li>{@code ticksUsed < 50}：未蓄满（含 0.5 秒宽限期内松手），静默取消（无 chat、不发包、不耗冷却）。</li>
     * <li>{@code ticksUsed >= 50} 且客户端侧：防御当前手持仍为本物品（防换槽）后发送
     * {@link PacketRequestNodeReveal}（disc 11），服务端队列 drain 完成全部校验与回包。</li>
     * <li>服务端侧：不做事（手持/冷却/查询全部由服务端主线程 drain 统一处理；
     * {@code world.isRemote} 守卫保证双端回调只发一次包）。</li>
     * </ul>
     * <p>
     * 4 参语义（vanilla {@code ItemBow} 同款）：{@code remaining} 为剩余使用 tick，
     * {@code ticksUsed = 50 - remaining}。满档后继续持蓄会使 {@code itemInUseCount} 转负，
     * 释放时 {@code max - count} 仍 ≥ 50，同样触发一次（无双重触发路径：本物品未覆写
     * {@code onItemUseFinish}，满档自动结算不引入额外入口）。
     */
    @Override
    public void onPlayerStoppedUsing(ItemStack stack, World world, EntityPlayer player, int remaining) {
        int ticksUsed = getMaxItemUseDuration(stack) - remaining;
        // [GTSWN-REVEAL-PROBE] C1
        GTSimpleWirelessNetwork.LOG
            .info("[GTSWN-REVEAL] C1 release ticksUsed=" + ticksUsed + " isRemote=" + world.isRemote);
        // 未蓄满：静默取消
        if (ticksUsed < MAX_CHARGE_DURATION_TICKS) {
            return;
        }
        // 服务端不做事；客户端防御换槽后发显形请求
        if (world.isRemote) {
            ItemStack held = player.getCurrentEquippedItem();
            if (held != null && held.getItem() == this) {
                // [GTSWN-REVEAL-PROBE] C2
                GTSimpleWirelessNetwork.LOG.info("[GTSWN-REVEAL] C2 send disc11");
                GTSWNPacketHandler.NETWORK.sendToServer(new PacketRequestNodeReveal());
            }
        }
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
        // 激光仓绑定消耗提示 / Binding a laser hatch consumes a Laser Vacuum Pipe
        list.add(StatCollector.translateToLocal("gtswn.tooltip.tap.laser_pipe_cost"));
        // 蓄力显形提示 / Charge-and-release node reveal hint
        list.add(StatCollector.translateToLocal("gtswn.tooltip.tap.reveal"));
    }
}
