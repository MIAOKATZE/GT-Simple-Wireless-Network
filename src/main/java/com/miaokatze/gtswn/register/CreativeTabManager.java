package com.miaokatze.gtswn.register;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

/**
 * 创造模式物品栏管理器
 */
public class CreativeTabManager {

    public static final CreativeTabs CREATIVE_TAB = new CreativeTabs("gtswn") {

        @Override
        public Item getTabIconItem() {
            // 返回第一个物品作为图标，如果没有物品则返回空气
            List<ItemStack> items = getItemsToAdd();
            if (items != null && !items.isEmpty()) {
                return items.get(0)
                    .getItem();
            }
            // 兜底：返回一个安全的物品（防止崩溃）
            return net.minecraft.init.Items.diamond;
        }

        @Override
        public String getTranslatedTabLabel() {
            return "GT Simple Wireless Network";
        }

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

    private static final List<ItemStack> itemsToAdd = new ArrayList<>();

    /**
     * 添加物品到创造模式物品栏
     */
    public static void addItemToTab(ItemStack itemStack) {
        if (itemStack != null) {
            itemsToAdd.add(itemStack);
        }
    }

    /**
     * 初始化创造模式物品栏 - 在物品注册后调用
     */
    public static void initCreativeTab() {
        GTSimpleWirelessNetwork.LOG.info("Initializing creative tab with " + itemsToAdd.size() + " items");
        // 这里可以在需要时添加额外的初始化逻辑
    }

    /**
     * 获取所有要添加到创造模式物品栏的物品
     */
    public static List<ItemStack> getItemsToAdd() {
        return itemsToAdd;
    }
}
