package com.miaokatze.gtswn.main;

import net.minecraftforge.common.MinecraftForge;

import com.miaokatze.gtswn.common.hud.WirelessMonitorHUD;

import cpw.mods.fml.common.event.FMLInitializationEvent;

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

        // 注：原 PlayerLoggedOutEvent 监听器用于保存便携式 HUD 历史到物品 NBT，
        // 已随 WirelessMonitorHUD.saveHistoryToItemStack 删除而移除（用户确认便携式随退出登录重置）。
        // HUD 状态会在下次 findMonitorInInventory 时自动重置：
        // - 无监视器 → clearCache() 清空所有缓存
        // - 有监视器 → 重新初始化，靠 gap 检测和首次检测重建数据集
    }

}
