package com.miaokatze.gtswn.common.covers;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import com.miaokatze.gtswn.common.items.GTSwnCoverDynamoWireless;
import com.miaokatze.gtswn.common.items.GTSwnCoverEnergyWireless;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * 链路终端覆盖板物品掉落抑制器（v1.6.30，服务端）。
 * <p>
 * 【为什么需要】链路终端覆盖板由能源/动力链路终端物品放置时免费创建（不消耗覆盖板物品），
 * 因此覆盖板本身不可作为物品获得——任何掉落路径产出覆盖板 EntityItem 都等价于无限复制。
 * GT5U 存在三条把覆盖板作为 {@link EntityItem} 掉落的路径：
 * <ul>
 * <li>撬棍撬下：{@code BaseMetaTileEntity.onBlockActivated} → {@code dropCover}</li>
 * <li>机器潜行拆除：{@code BlockMachines.removedByPlayer} 对全部侧面 {@code dropCover}</li>
 * <li>重放机器/扳手换面时 {@code checkDropCover} 把不兼容覆盖板顶落</li>
 * </ul>
 * 本监听在覆盖板 EntityItem 进入世界时直接取消生成，堵死全部三条路径。
 * <p>
 * 【设计语义】缓存上传无需在此处理：移除覆盖板时 GT5U 的
 * {@code dropCover → detachCover → onCoverRemoval} 链会先把剩余电量上传无线电网，
 * 与无线网络链路终端的拆卸行为天然一致。
 * 创造模式下玩家从物品栏丢弃该覆盖板物品同样会被取消（消失），属预期行为。
 * <p>
 * Cover Drop Suppression Handler (server-side). Link-terminal covers are created for free by the
 * terminal items, so they must never exist as items; any drop would be an infinite dupe. This
 * handler cancels the three GT5U drop paths (crowbar pry-off, sneaky machine break, checkDropCover
 * eviction) by canceling EntityJoinWorldEvent for those EntityItems. Buffer upload on removal is
 * already handled by dropCover→detachCover→onCoverRemoval. Creative-mode item discard also
 * vanishes — intended.
 */
public class CoverDropSuppressionHandler {

    /**
     * 取消链路终端覆盖板 EntityItem 的世界生成（仅服务端；客户端镜像事件直接忽略）。
     * Cancel world-join of link-terminal cover EntityItems (server side only).
     */
    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent event) {
        // 仅服务端处理 / Server side only
        if (event.world.isRemote) {
            return;
        }
        if (!(event.entity instanceof EntityItem entityItem)) {
            return;
        }
        ItemStack stack = entityItem.getEntityItem();
        // 空栈或无效数量直接放行 / Let empty or invalid stacks pass through
        if (stack == null || stack.stackSize <= 0) {
            return;
        }
        if (stack.getItem() instanceof GTSwnCoverEnergyWireless
            || stack.getItem() instanceof GTSwnCoverDynamoWireless) {
            event.setCanceled(true);
        }
    }
}
