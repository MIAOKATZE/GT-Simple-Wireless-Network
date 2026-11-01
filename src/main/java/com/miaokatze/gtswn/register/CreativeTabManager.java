package com.miaokatze.gtswn.register;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;
import com.miaokatze.miaokatzecore.api.items.ItemOrderCatalog;

/**
 * 创造模式物品栏管理器
 * 负责管理模组专属的创造模式标签页，包括图标设置、名称显示以及物品列表的维护。
 */
public class CreativeTabManager {

    /**
     * 完整物品目录，组内顺序固定；当前只有监视器一个 LV 机器，没有多等级物品族。
     * 后续等级变体应放在对应设备之后，从低到高排列，不依赖注册时间或本地化名称。
     */
    private enum ItemGroup {

        TERMINALS(GTSWNItemList.Portable_Wireless_Network_Monitor, GTSWNItemList.Device_Info_Terminal,
            GTSWNItemList.Wireless_Energy_Tap, GTSWNItemList.ME_Network_Quantum_Terminal),
        WIRELESS_ENERGY(GTSWNItemList.Wireless_Energy_Monitor, GTSWNItemList.GTswn_Cover_Energy_Wireless,
            GTSWNItemList.GTswn_Cover_Dynamo_Wireless),
        DISPLAYS(GTSWNItemList.Network_Info_Panel, GTSWNItemList.Network_Info_Panel_Extender),
        ME_QUANTUM(GTSWNItemList.ME_Network_Quantum_Node, GTSWNItemList.Quantum_Incorporation_Anchor),
        ORDERED_INPUTS(GTSWNItemList.Matrix_Grouped_Item_Input_ME, GTSWNItemList.Matrix_Grouped_Fluid_Input_ME),
        TESTING(GTSWNItemList.TestCoin);

        private final GTSWNItemList[] items;

        ItemGroup(GTSWNItemList... items) {
            this.items = items;
        }
    }

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
            for (ItemStack item : getOrderedCreativeItems()) {
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
            itemsToAdd.add(itemStack.copy());
            // GT 机器在 sAfterGTPreload 中晚于普通物品注册，必须刷新共享目录。
            ItemOrderCatalog.invalidate();
        }
    }

    /**
     * 初始化创造模式物品栏
     * 建议在物品注册完成后调用此方法
     */
    public static void initCreativeTab() {
        // supplier 读取实时原始目录，不能调用 getOrderedCreativeItems 导致递归。
        ItemOrderCatalog.register(GTSimpleWirelessNetwork.MODID, CreativeTabManager::getRawCatalogItems);
    }

    /**
     * 获取所有要添加到创造模式物品栏的物品列表
     * 
     * @return 物品堆栈列表
     */
    public static List<ItemStack> getItemsToAdd() {
        return getOrderedCreativeItems();
    }

    /** 创造页和 Core 的 NEI 桥共用同一目录，返回独立的物品栈副本。 */
    public static List<ItemStack> getOrderedCreativeItems() {
        return ItemOrderCatalog.orderedItems(GTSimpleWirelessNetwork.MODID);
    }

    /** 仅重排实际注册的创造页条目，不补入尚未注册的物品或其他模组的 GT 机器元数据。 */
    private static List<ItemStack> getRawCatalogItems() {
        List<ItemStack> remaining = new ArrayList<>();
        for (ItemStack stack : itemsToAdd) {
            remaining.add(stack.copy());
        }
        List<ItemStack> ordered = new ArrayList<>();
        for (ItemGroup group : ItemGroup.values()) {
            for (GTSWNItemList entry : group.items) {
                if (!entry.hasBeenSet()) continue;
                for (int i = 0; i < remaining.size();) {
                    ItemStack stack = remaining.get(i);
                    if (stack.getItem() == entry.getItem() && stack.getItemDamage() == entry.getMeta()) {
                        ordered.add(remaining.remove(i));
                    } else {
                        i++;
                    }
                }
            }
        }
        // 扩展方通过旧 addItemToTab 接口添加的条目仍保留，并按原有注册顺序稳定收尾。
        ordered.addAll(remaining);
        return ordered;
    }
}
