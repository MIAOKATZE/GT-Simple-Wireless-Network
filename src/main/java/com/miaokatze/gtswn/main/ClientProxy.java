package com.miaokatze.gtswn.main;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;

import com.miaokatze.gtswn.common.hud.WirelessMonitorHUD;
import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;

/**
 * 客户端代理类
 * 继承自 CommonProxy，用于处理仅在客户端（Client Side）执行的逻辑。
 * 例如：渲染注册、按键绑定、GUI 打开等。
 */
public class ClientProxy extends CommonProxy {

    /**
     * 初始化阶段 (Init)
     * 在此阶段注册客户端特定的事件处理器，如 HUD 渲染器。
     */
    @Override
    public void init(FMLInitializationEvent event) {
        // 调用父类的 init 方法，确保通用逻辑正常执行
        super.init(event);

        // 注册 HUD 渲染器到 Forge 事件总线（仅在客户端）
        // 注意：RenderGameOverlayEvent 是 Forge 事件，必须注册到 MinecraftForge.EVENT_BUS
        GTSimpleWirelessNetwork.LOG.info("[2/2] 注册客户端 HUD 渲染器...");
        MinecraftForge.EVENT_BUS.register(new WirelessMonitorHUD());

        // 注册玩家退出世界事件处理器（用于保存便携式 HUD 历史到物品 NBT）
        // PlayerLoggedOutEvent 属于 FML 事件，必须注册到 FMLCommonHandler.bus()
        FMLCommonHandler.instance()
            .bus()
            .register(new Object() {

                @SubscribeEvent
                public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
                    // 仅客户端处理（确保是客户端玩家退出）
                    if (event.player.worldObj.isRemote) {
                        // 遍历玩家背包找便携式监视器，保存 HUD 历史到物品 NBT
                        for (int i = 0; i < event.player.inventory.mainInventory.length; i++) {
                            ItemStack stack = event.player.inventory.mainInventory[i];
                            if (stack != null && stack.getItem() instanceof PortableWirelessNetworkMonitor) {
                                WirelessMonitorHUD.saveHistoryToItemStack(stack);
                            }
                        }
                        // 也检查 Baubles 饰品栏（如果安装了 Baubles）
                        try {
                            IInventory baublesInv = baubles.api.BaublesApi.getBaubles(event.player);
                            if (baublesInv != null) {
                                for (int i = 0; i < baublesInv.getSizeInventory(); i++) {
                                    ItemStack stack = baublesInv.getStackInSlot(i);
                                    if (stack != null && stack.getItem() instanceof PortableWirelessNetworkMonitor) {
                                        WirelessMonitorHUD.saveHistoryToItemStack(stack);
                                    }
                                }
                            }
                        } catch (NoClassDefFoundError ignored) {
                            // Baubles 未安装
                        }
                    }
                }
            });
    }

}
