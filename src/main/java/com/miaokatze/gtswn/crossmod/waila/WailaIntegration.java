package com.miaokatze.gtswn.crossmod.waila;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;

import cpw.mods.fml.common.event.FMLInterModComms;
import mcp.mobius.waila.api.IWailaDataProvider;
import mcp.mobius.waila.api.IWailaRegistrar;

/**
 * WAILA 集成入口（v1.6.2，软依赖）。
 * <p>
 * 注册模式仿 GT5U {@code gregtech.crossmod.waila.Waila}：init() 经 FMLInterModComms 向 WAILA
 * 发送 "register" 消息，WAILA 在合适时机回调 {@link #callbackRegister(IWailaRegistrar)}。
 * 本类仅在没有 WAILA 时不被触达——调用方（CommonProxy）已用 Loader.isModLoaded 门控。
 */
public final class WailaIntegration {

    private WailaIntegration() {}

    /**
     * WAILA 回调注册（由 WAILA 经 IMC 反射调用）。
     * 为量子节点注册 body 提供器（状态文本）与 NBT 提供器（服务端数据同步）。
     */
    public static void callbackRegister(IWailaRegistrar registrar) {
        final IWailaDataProvider provider = new QuantumNodeWailaDataProvider();
        registrar.registerBodyProvider(provider, TileEntityNetworkQuantumNode.class);
        registrar.registerNBTProvider(provider, TileEntityNetworkQuantumNode.class);
    }

    /** 发送 IMC 注册消息（仅在 Loader.isModLoaded("Waila") 为真时调用） */
    public static void init() {
        FMLInterModComms.sendMessage("Waila", "register", WailaIntegration.class.getName() + ".callbackRegister");
    }
}
