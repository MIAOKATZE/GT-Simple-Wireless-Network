package com.miaokatze.gtswn.common.gui;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;

/**
 * ME 网络量子终端的服务端容器（T6，规划 plan_20260722152445.md §3/§4）。
 * <p>
 * 纯展示型 GUI，无任何槽位：显示数据全部走包 5/6 轮询通道（C→S 请求 → S→C 同步），
 * 不经过 Container 的 slot/detectAndSendChanges 机制。
 * <p>
 * {@link #canInteractWith} 校验玩家当前手持仍是量子终端——
 * 玩家切换手中物品后 GUI 即失去交互资格被关闭，防止「GUI 开着但终端已不在手」的悬空状态。
 */
public class ContainerQuantumTerminal extends Container {

    /** 打开 GUI 时的手持终端引用（保留以便未来扩展权限/绑定一致性校验） */
    private final ItemStack terminalStack;

    public ContainerQuantumTerminal(EntityPlayer player, ItemStack held) {
        this.terminalStack = held;
    }

    /**
     * 玩家手持仍是 ME 网络量子终端时保持 GUI 打开。
     * <p>
     * 说明：服务端 {@code EntityPlayer.openContainer} 每 tick 调用本方法，
     * 返回 false 会自动关闭 GUI 并同步到客户端。
     */
    @Override
    public boolean canInteractWith(EntityPlayer player) {
        ItemStack held = player.getHeldItem();
        return held != null && held.getItem() instanceof ItemNetworkQuantumTerminal;
    }
}
