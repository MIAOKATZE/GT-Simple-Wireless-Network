package com.miaokatze.gtswn.common.items;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;

import ic2.api.item.ElectricItem;
import ic2.api.item.IElectricItem;

/**
 * 电子测试硬币物品
 * <p>
 * 实现 IElectricItem 接口，支持充电。
 * Shift + 右击可消耗 100,000 EU 生成一套测试机器。
 */
public class TestCoinE extends Item implements IElectricItem {

    private static final long MAX_CHARGE = 1000000L;
    private static final int TIER = 5; // HV
    private static final long COST_PER_USE = 100000L;

    public TestCoinE() {
        super();
        setUnlocalizedName("TestCoin_GTswn_E");
        setTextureName("gtswn:TestCoin_GTswn");
        setCreativeTab(CreativeTabs.tabMisc);
        setMaxStackSize(1);
        setMaxDamage((int) MAX_CHARGE);
    }

    @Override
    public boolean canProvideEnergy(ItemStack aStack) {
        return true;
    }

    @Override
    public double getMaxCharge(ItemStack aStack) {
        return MAX_CHARGE;
    }

    @Override
    public int getTier(ItemStack aStack) {
        return TIER;
    }

    @Override
    public double getTransferLimit(ItemStack aStack) {
        return 8192L; // HV limit
    }

    @Override
    public Item getChargedItem(ItemStack aStack) {
        return this;
    }

    @Override
    public Item getEmptyItem(ItemStack aStack) {
        return this;
    }

    /**
     * 处理右击事件
     */
    @Override
    public ItemStack onItemRightClick(ItemStack aStack, World aWorld, EntityPlayer aPlayer) {
        // 检测是否按住 Shift 键
        if (aPlayer.isSneaking()) {
            // 客户端不处理逻辑
            if (aWorld.isRemote) {
                return aStack;
            }

            // 【IC2 标准做法】使用 ElectricItem.manager 尝试扣除电量
            // use 方法会自动处理耐久度、NBT 和同步逻辑，返回 true 表示扣电成功
            if (ElectricItem.manager.use(aStack, COST_PER_USE, aPlayer)) {
                // 扣电成功，生成机器
                giveMachineToPlayer(aPlayer, GTSWNItemList.Test_Machine_EV);
                giveMachineToPlayer(aPlayer, GTSWNItemList.Test_Machine_IV);
                giveMachineToPlayer(aPlayer, GTSWNItemList.Test_Machine_LuV);
                giveMachineToPlayer(aPlayer, GTSWNItemList.Test_Multiblock_HV);

                aPlayer.addChatMessage(new net.minecraft.util.ChatComponentText("§a已消耗 100,000 EU 生成测试机器组！"));
            } else {
                // 扣电失败（电量不足）
                aPlayer.addChatMessage(new net.minecraft.util.ChatComponentText("§c电量不足！需要 100,000 EU。"));
            }
        }
        return aStack;
    }

    /**
     * 将机器物品添加到玩家背包
     */
    private void giveMachineToPlayer(EntityPlayer aPlayer, GTSWNItemList machine) {
        if (!machine.hasBeenSet()) return;

        ItemStack machineStack = machine.get(1);
        if (machineStack != null) {
            // 尝试直接加入背包
            if (!aPlayer.inventory.addItemStackToInventory(machineStack)) {
                // 如果背包满了，则掉落在玩家脚下
                aPlayer.entityDropItem(machineStack, 0.5F);
            }
        }
    }
}
