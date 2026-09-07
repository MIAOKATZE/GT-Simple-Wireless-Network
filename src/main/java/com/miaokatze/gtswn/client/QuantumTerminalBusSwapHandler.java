package com.miaokatze.gtswn.client;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketQuantumTerminalSwapBus;

import appeng.api.parts.SelectedPart;
import appeng.tile.networking.TileCableBus;
import appeng.util.LookDirection;
import appeng.util.Platform;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * 量子终端「裸部件 cable-bus 原位替换」客户端拦截处理器（v1.8.5）。
 * <p>
 * 手持已绑定的 {@link ItemNetworkQuantumTerminal}（非潜行）右击一个<b>无中心线缆</b>且命中面
 * 装有部件的 AE2 cable-bus 方块时，先于 AE2 与原版取消本次交互，改发
 * {@link PacketQuantumTerminalSwapBus}（disc 13）由服务端主线程把全部面部件原位迁入量子节点。
 * 有中心线缆的 bus（真线缆宿主）不拦截，AE2 原生行为不受影响；潜行保持 AE2 部件 Shift 激活。
 * <p>
 * 【优先级】{@code EventPriority.HIGHEST}——已核实 AE2 {@code PartPlacement} 的
 * PlayerInteractEvent 主处理器为 {@code EventPriority.LOW}（PartPlacement.java
 * {@code @SubscribeEvent(priority = EventPriority.LOW)}，注册点 Registration.java:539 无优先级覆写）。
 * HIGHEST 先于 LOW 执行，且 1.7.10 事件总线对未声明 {@code receiveCanceled=true} 的监听器
 * 不投递已取消事件（AE2 全部监听器均为默认 false），故本处取消后 AE2 的部件交互/放置分支
 * （含 INTERACT_FIRST_PASS 命中部件时发 PacketPartInteraction 的 sneak 路径）与原版
 * C08 流程都不会触发。
 * <p>
 * 【1.7.10 形态】1.7.10 无 {@code RightClickBlock} 子类事件，与 AE2 PartPlacement 同款：
 * 监听 {@link PlayerInteractEvent} + {@code action == RIGHT_CLICK_BLOCK} 判别。
 * <p>
 * 【命中面判定】镜像 AE2 {@code PartPlacement.place}：{@code block.collisionRayTrace}
 * （包含部件外扩包围盒）取真实命中面与命中向量，再经 {@code host.selectPart} 判定该处
 * 确有部件——事件携带的 {@code event.face} 可能与部件凸出面不一致，不用作判据。
 * <p>
 * 【注册】{@code GTSWNPacketHandler.register()}（preInit 双端）挂到 Forge 事件总线；
 * 本类虽置于 client 包但未引用任何 client-only 类，服务端注册后由
 * {@code !worldObj.isRemote} 早退，与 AE2 PartPlacement 的服务端守卫同一写法。
 */
public class QuantumTerminalBusSwapHandler {

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        // 仅客户端拦截；服务端由包 13 的主线程 drain 权威执行
        if (!event.entityPlayer.worldObj.isRemote) {
            return;
        }
        if (event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        EntityPlayer player = event.entityPlayer;
        // 共享判定（与 QuantumNodeHighlightRenderer 预览同源，保证「显示预览 ⇔ 右击可换」）
        ForgeDirection side = findSwapTargetFace(player, event.x, event.y, event.z);
        if (side == null) {
            return;
        }
        event.setCanceled(true);
        GTSWNPacketHandler.NETWORK
            .sendToServer(new PacketQuantumTerminalSwapBus(event.x, event.y, event.z, side.ordinal()));
    }

    /**
     * 判定准星指向 (x,y,z) 是否为「裸部件 bus 换体」目标：手持已绑定量子终端、非潜行、
     * 目标为无中心线缆的 TileCableBus 且真实命中面确有部件。命中返回真实命中面
     * （{@code PacketQuantumTerminalSwapBus} 的 side 字段语义），否则返回 null。
     * <p>
     * 客户端拦截（{@link #onPlayerInteract}）与放置预览（QuantumNodeHighlightRenderer）
     * 共用本判定，保证「显示换体预览 ⇔ 右击触发换体」不出现两者不一致。
     */
    public static ForgeDirection findSwapTargetFace(EntityPlayer player, int x, int y, int z) {
        ItemStack held = player.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemNetworkQuantumTerminal)) {
            return null;
        }
        // 潜行放行（镜像终端手势表：潜行不替换，保持 AE2 部件 Shift 激活原生行为）
        if (player.isSneaking()) {
            return null;
        }
        // 未绑定终端不拦截（镜像手势 4 前置：未绑定等效空手）
        if (!ItemNetworkQuantumTerminal.isBound(held)) {
            return null;
        }
        World world = player.worldObj;
        TileEntity te = world.getTileEntity(x, y, z);
        if (!(te instanceof TileCableBus)) {
            return null;
        }
        // cast 源表达式保持 TileEntity 静态类型（避免 javac 解析 AEBaseTile 可选接口）
        TileCableBus bus = (TileCableBus) (TileEntity) te;
        if (bus.getPart(ForgeDirection.UNKNOWN) != null) {
            // 有中心线缆 = AE2 原生域，不拦截
            return null;
        }
        // 真实命中面 + 命中面部件判定（镜像 AE2 PartPlacement.place 的 collisionRayTrace 链）
        LookDirection look = Platform.getPlayerRay(player, Platform.getEyeOffset(player));
        Block block = world.getBlock(x, y, z);
        MovingObjectPosition mop = block.collisionRayTrace(world, x, y, z, look.getA(), look.getB());
        if (mop == null || mop.sideHit == -1 || mop.hitVec == null) {
            return null;
        }
        Vec3 local = Vec3.createVectorHelper(
            mop.hitVec.xCoord - mop.blockX,
            mop.hitVec.yCoord - mop.blockY,
            mop.hitVec.zCoord - mop.blockZ);
        SelectedPart sp = bus.selectPart(local);
        if (sp == null || sp.part == null) {
            // 命中处无部件（裸线缆核心区等）：不拦截，保持既有手势 4（面偏移放节点）路径
            return null;
        }
        return ForgeDirection.getOrientation(mop.sideHit);
    }
}
