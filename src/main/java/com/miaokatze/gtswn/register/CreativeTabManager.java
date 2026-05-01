package com.miaokatze.gtswn.register;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

/**
 * 创造模式物品栏管理器
 * 负责管理模组专属的创造模式标签页，包括图标设置、名称显示以及物品列表的维护。
 */
public class CreativeTabManager {

    /**
     * 模组专属的创造模式标签页实例
     */
    public static final CreativeTabs CREATIVE_TAB = new CreativeTabs("gtswn") {

        /**
         * 获取标签页的图标物品
         * 使用便携式无线网络监测终端作为图标
         */
        @Override
        public Item getTabIconItem() {
            // 使用便携式无线网络监测终端作为图标
            if (com.miaokatze.gtswn.common.api.enums.GTSWNItemList.Portable_Wireless_Network_Monitor.hasBeenSet()) {
                return com.miaokatze.gtswn.common.api.enums.GTSWNItemList.Portable_Wireless_Network_Monitor.getItem();
            }
            // 兜底：返回一个安全的物品（防止崩溃）
            return net.minecraft.init.Items.diamond;
        }

        /**
         * 获取标签页的显示名称（未本地化）
         */
        @Override
        public String getTranslatedTabLabel() {
            return "GT Simple Wireless Network";
        }

        /**
         * 向标签页中添加所有相关物品
         * 注意：Minecraft 1.7.10 中该方法名拼写为 displayAllReleventItems
         */
        @Override
        @SuppressWarnings({ "rawtypes", "unchecked" })
        public void displayAllReleventItems(List list) {
            // 将所有添加的物品显示在创造模式标签页中
            for (ItemStack item : getItemsToAdd()) {
                if (item != null) {
                    list.add(item);
                }
            }
        }
    };

    // 存储待添加到创造模式标签页的物品列表
    private static final List<ItemStack> itemsToAdd = new ArrayList<>();

    /**
     * 添加物品到创造模式物品栏
     * 
     * @param itemStack 要添加的物品堆栈
     */
    public static void addItemToTab(ItemStack itemStack) {
        if (itemStack != null) {
            itemsToAdd.add(itemStack);
        }
    }

    /**
     * 初始化创造模式物品栏
     * 建议在物品注册完成后调用此方法
     */
    public static void initCreativeTab() {
        GTSimpleWirelessNetwork.LOG.info("正在初始化创造模式物品栏，当前包含 " + itemsToAdd.size() + " 个物品");
        // 这里可以在需要时添加额外的初始化逻辑
    }

    /**
     * 获取所有要添加到创造模式物品栏的物品列表
     * 
     * @return 物品堆栈列表
     */
    public static List<ItemStack> getItemsToAdd() {
        return itemsToAdd;
    }
}
