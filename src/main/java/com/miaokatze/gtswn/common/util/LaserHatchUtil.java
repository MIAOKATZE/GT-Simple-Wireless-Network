package com.miaokatze.gtswn.common.util;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S2FPacketSetSlot;
import net.minecraft.tileentity.TileEntity;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.MetaTileEntityIDs;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatch;

/**
 * 激光仓工具类
 * <p>
 * 提供激光源仓（MTEHatchDynamoTunnel）/ 激光靶仓（MTEHatchEnergyTunnel）的识别、专属 V/A 读取
 * 与激光真空管消耗能力，供链路终端（WirelessEnergyTap / GTswn_Cover_DynamoWireless）共用。
 * <p>
 * 背景：激光仓将 {@code isEnetOutput} 覆写为 false，BaseMetaTileEntity.getOutputVoltage() 因此被门控恒返回 0，
 * 但 MetaTileEntity 层的 maxEUOutput() / maxAmperesOut() 等仓专属方法不受门控，必须直读取电。
 * <p>
 * Laser hatch utilities: identify Laser Source/Target Hatches (MTEHatchDynamoTunnel / MTEHatchEnergyTunnel),
 * read their hatch-specific V/A, and consume Laser Vacuum Pipes for the link terminal.
 * Background: laser hatches override isEnetOutput to false, gating getOutputVoltage() to 0,
 * so the hatch-specific MetaTileEntity methods must be read directly.
 */
public class LaserHatchUtil {

    /**
     * 判断 MTE 是否为激光仓（激光源仓/激光靶仓）
     * <p>
     * 全 GT5U 仅 MTEHatchDynamoTunnel 与 MTEHatchEnergyTunnel 覆写返回 LASER，无误伤其他仓型。
     * <p>
     * Whether the MTE is a laser hatch (Laser Source/Target Hatch).
     * Only MTEHatchDynamoTunnel and MTEHatchEnergyTunnel override getConnectionType() to LASER in GT5U.
     *
     * @param mte 机器 MTE（可为 null）
     * @return 是激光仓返回 true
     */
    public static boolean isLaserHatch(IMetaTileEntity mte) {
        return mte instanceof MTEHatch hatch && hatch.getConnectionType() == MTEHatch.ConnectionType.LASER;
    }

    /**
     * 判断方块实体是否为激光仓
     * <p>
     * 调用方传入的 te 为 net.minecraft.tileentity.TileEntity，GT 机器为 BaseMetaTileEntity（implements IGregTechTileEntity）。
     * <p>
     * Whether the tile entity is a laser hatch. GT machines are BaseMetaTileEntity (implements IGregTechTileEntity).
     *
     * @param te 方块实体（可为 null）
     * @return 是激光仓返回 true；非 GT 机器返回 false
     */
    public static boolean isLaserHatch(TileEntity te) {
        if (!(te instanceof IGregTechTileEntity gtTe)) return false;
        return isLaserHatch(gtTe.getMetaTileEntity());
    }

    /**
     * 读取激光仓专属电压：源仓 maxEUOutput()=V[mTier]、靶仓 maxEUInput()=V[mTier]，恒非零
     * <p>
     * IMetaTileEntity 未声明 maxEUOutput/maxEUInput（声明于 MetaTileEntity），运行时实例恒为 MetaTileEntity 子类。
     * <p>
     * Read laser hatch-specific voltage: source=maxEUOutput(), target=maxEUInput() (both V[mTier], never 0).
     * IMetaTileEntity does not declare maxEUOutput/maxEUInput (declared on MetaTileEntity); runtime instances
     * are always MetaTileEntity subclasses.
     *
     * @param mte 激光仓 MTE
     * @return 电压（EU/t）
     */
    public static long getLaserVoltage(IMetaTileEntity mte) {
        MetaTileEntity hatch = (MetaTileEntity) mte;
        long v = hatch.maxEUOutput();
        return v > 0 ? v : hatch.maxEUInput();
    }

    /**
     * 读取激光仓专属安培：源仓 maxAmperesOut()=Amperes、靶仓 maxAmperesIn()=Amperes+(Amperes>>2)
     * <p>
     * Read laser hatch-specific amperage: source=maxAmperesOut(), target=maxAmperesIn().
     *
     * @param mte 激光仓 MTE
     * @return 安培
     */
    public static long getLaserAmperage(IMetaTileEntity mte) {
        MetaTileEntity hatch = (MetaTileEntity) mte;
        long a = hatch.maxAmperesOut();
        return a > 1 ? a : hatch.maxAmperesIn();
    }

    /**
     * 获取激光真空管物品栈（机器方块 sBlockMachines + meta LaserVacuumPipe.ID，与 MetaTileEntity.getStackForm 同构造）
     * <p>
     * Get the Laser Vacuum Pipe item stack (same construction as MetaTileEntity.getStackForm).
     *
     * @return 1 根激光真空管
     */
    public static ItemStack getLaserPipeItemStack() {
        return new ItemStack(GregTechAPI.sBlockMachines, 1, MetaTileEntityIDs.LaserVacuumPipe.ID);
    }

    /**
     * 从玩家背包消耗 1 根激光真空管
     * <p>
     * 按 item + damage 精确匹配（1.7.10 静态 ItemStack.isItemEqual 还会比较 NBT，故手动比较字段避免误判），
     * 匹配槽位 stackSize--（减到 0 置 null）。不发聊天提示，提示由调用方处理。
     * <p>
     * Consume 1 Laser Vacuum Pipe from the player inventory. Matches by item + damage
     * (the 1.7.10 static ItemStack.isItemEqual also compares NBT, so compare fields explicitly).
     * No chat message here; the caller handles messaging.
     *
     * @param player 玩家
     * @return 消耗成功返回 true；背包无激光真空管返回 false
     */
    public static boolean consumeLaserPipe(EntityPlayer player) {
        ItemStack reference = getLaserPipeItemStack();
        ItemStack[] mainInventory = player.inventory.mainInventory;
        for (int i = 0; i < mainInventory.length; i++) {
            ItemStack stack = mainInventory[i];
            if (stack != null && stack.getItem() == reference.getItem()
                && stack.getItemDamage() == reference.getItemDamage()) {
                stack.stackSize--;
                if (stack.stackSize <= 0) {
                    mainInventory[i] = null;
                }
                // 1.7.10：无 GUI 打开时 openContainer(inventoryContainer) 无 ICrafting，detectAndSendChanges 不会
                // 向客户端推送槽位；显式发送 S2FPacketSetSlot 同步该槽位，否则客户端背包残留旧物品直至下次交互。
                // S2FPacketSetSlot windowId=0 使用 ContainerPlayer 容器槽序（36-44=快捷栏）：快捷栏数组序 0-8
                // 必须映射为 36+i，直发 i 会打到合成结果/合成格/护甲槽，客户端出现顶掉装备的残影（点击即被服务端纠正）。
                // 1.7.10: with no GUI open the inventoryContainer has no ICrafting listeners, so
                // detectAndSendChanges never pushes slots; send S2FPacketSetSlot explicitly so the
                // client slot updates immediately instead of showing stale items until the next interaction.
                // windowId=0 follows ContainerPlayer order (36-44=hotbar): map hotbar index i to 36+i,
                // raw i would hit the craft result/matrix/armor slots and ghost client-side.
                if (player instanceof EntityPlayerMP playerMP) {
                    playerMP.playerNetServerHandler
                        .sendPacket(new S2FPacketSetSlot(0, i < 9 ? 36 + i : i, mainInventory[i]));
                }
                return true;
            }
        }
        return false;
    }
}
