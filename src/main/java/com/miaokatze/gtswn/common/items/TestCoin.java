package com.miaokatze.gtswn.common.items;

import java.math.BigInteger;
import java.util.UUID;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.util.FormatUtil;

import gregtech.common.misc.WirelessNetworkManager;

/**
 * 测试硬币物品
 * <p>
 * 这是一个基础的 Minecraft 物品，用于验证模组的物品注册流程、创造模式标签页集成以及配方系统。
 * 支持右键操作：
 * - 右键：给操作者的无线电网增加 1,000,000 EU
 * - Shift + 右键：从操作者的无线电网扣除 1,000,000 EU
 */
public class TestCoin extends Item {

    /** 每次操作的 EU 变化量 */
    private static final long EU_AMOUNT = 1_000_000L;

    /**
     * 构造函数：初始化测试硬币的基础属性
     */
    public TestCoin() {
        super();
        // 设置未本地化名称 (Unlocalized Name)，用于关联语言文件
        setUnlocalizedName("TestCoin_GTswn");
        // 设置材质路径 (Texture Name)，指向 assets/gtswn/textures/items/TestCoin_GTswn.png
        setTextureName("gtswn:TestCoin_GTswn");
        // 设置创造模式标签页，使其能在游戏中被玩家获取
        setCreativeTab(CreativeTabs.tabMisc);
        // 设置最大堆叠数量为 64
        setMaxStackSize(64);
    }

    /**
     * 处理右击事件
     */
    @Override
    public ItemStack onItemRightClick(ItemStack aStack, World aWorld, EntityPlayer aPlayer) {
        // 客户端不处理逻辑
        if (aWorld.isRemote) {
            return aStack;
        }

        // 获取玩家 UUID
        UUID playerUUID = aPlayer.getUniqueID();

        // 获取当前无线电网能量
        BigInteger currentEU = WirelessNetworkManager.getUserEU(playerUUID);

        boolean isSneaking = aPlayer.isSneaking();
        BigInteger newEU;

        if (isSneaking) {
            // Shift + 右键：扣除 EU
            BigInteger deduction = BigInteger.valueOf(EU_AMOUNT);
            if (currentEU.compareTo(deduction) < 0) {
                // 能量不足，无法扣除
                aPlayer.addChatMessage(
                    new ChatComponentText(StatCollector.translateToLocal("gtswn.chat.testcoin.insufficient")));
                return aStack;
            }
            newEU = currentEU.subtract(deduction);
            aPlayer.addChatMessage(
                new ChatComponentText(
                    StatCollector.translateToLocalFormatted("gtswn.chat.testcoin.deducted", EU_AMOUNT)));
        } else {
            // 普通右键：增加 EU
            newEU = currentEU.add(BigInteger.valueOf(EU_AMOUNT));
            aPlayer.addChatMessage(
                new ChatComponentText(StatCollector.translateToLocalFormatted("gtswn.chat.testcoin.added", EU_AMOUNT)));
        }

        // 更新无线电网能量
        WirelessNetworkManager.setUserEU(playerUUID, newEU);

        // 显示当前总能量
        String euFormatted = FormatUtil.formatBigInteger(newEU);
        aPlayer.addChatMessage(
            new ChatComponentText(StatCollector.translateToLocalFormatted("gtswn.chat.testcoin.current", euFormatted)));

        return aStack;
    }
}
