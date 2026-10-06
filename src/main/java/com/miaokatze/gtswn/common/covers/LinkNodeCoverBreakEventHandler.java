package com.miaokatze.gtswn.common.covers;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.world.BlockEvent;

import com.miaokatze.gtswn.network.LinkNodeDismantleMarkerQueue;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.tileentity.ICoverable;
import gregtech.api.util.GTUtility;

/**
 * 链路节点覆盖板拆机分流处理器（v1.8.22，服务端，与 {@link CoverDropSuppressionHandler} 同包）。
 * <p>
 * 监听 {@code MinecraftForge.EVENT_BUS} 的 {@code BlockEvent.BreakEvent}（机器方块被玩家
 * 拆除时触发；GT 多方块方块 {@code BlockMachines.removedByPlayer} 路径同样发布本事件），
 * 对「被拆机器六面带本 mod 链路节点覆盖板」的三条分流：
 * <ol>
 * <li><b>Alt+扳手拆除</b>（{@link LinkNodeDismantleMarkerQueue#isMarked} 命中 20t TTL 标记 +
 * 手持 GT 扳手）：逐面 {@code detachCover}——返回的覆盖板物品栈直接丢弃 = 静默清除，
 * {@code onCoverRemoval} 链照常执行（节点索引出册 + 剩余缓冲 EU 回网），再按
 * {@code WirelessEnergyTap} 拆除路径的 D3 模式逐坐标 {@code WirelessNodeRegistry.unregister}
 * 兜底（幂等）。不取消事件：机器本体照常被原版/GT 流程拆除。</li>
 * <li><b>Shift（潜行）拆除</b>：直接放行——GT5U 原版潜行拆机路径（removedByPlayer 对全部
 * 侧面 dropCover）已把覆盖板静默清除（掉落物由 CoverDropSuppressionHandler 取消，
 * onCoverRemoval 链在 detachCover 内照常执行），零新代码即正确，不提示不打扰。</li>
 * <li><b>无修饰键+扳手拆除（生存）</b>：前 10 次聊天提示「按住 Shift 或 Alt 再拆可静默清除」
 * （per-player 持久计数，{@code PlayerPersisted} 复合标签键 {@code gtswn.linkNodeDismantleHintShown}，
 * 上限 10——老玩家引导性提示，长期游戏不再刷屏）。非扳手拆除（炸/挖/镐）不提示：
 * 该路径覆盖板按 GT5U 默认行为掉落并被掉落抑制器取消，提示无行动意义。</li>
 * </ol>
 * <p>
 * 【创造守卫的取舍】提示路径带 {@code isCreativeState} 守卫：创造玩家拆除不计数不提示
 * （提示是生存玩家的操作引导，对创造模式是噪音）。Alt 路径<b>不设</b>创造守卫：静默清除
 * 的核心是「出册卫生」（节点索引 + 缓冲 EU 回网），创造玩家同样会放链路节点覆盖板，
 * 跳过会让 WirelessNodeRegistry 留下脏条目、覆盖板缓冲 EU 蒸发——与拆除方式无关的
 * 数据一致性义务，必须照跑。
 */
public class LinkNodeCoverBreakEventHandler {

    /** 提示计数的 PlayerPersisted 键（per-player 持久，跨存档/退出保留） */
    private static final String HINT_COUNT_KEY = "gtswn.linkNodeDismantleHintShown";

    /** 提示次数上限（超过后不再提示，也不继续累加计数） */
    private static final int HINT_COUNT_MAX = 10;

    /**
     * 方块破坏分流：仅服务端；被拆方块 TE 为 GT {@link ICoverable} 且六面带本 mod
     * 链路节点覆盖板时进入三条分流（见类 javadoc），否则零开销放行。
     */
    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.world.isRemote) {
            return;
        }
        TileEntity te = event.world.getTileEntity(event.x, event.y, event.z);
        if (!(te instanceof ICoverable)) {
            return;
        }
        ICoverable coverable = (ICoverable) te;
        // 六面扫描：收集带链路节点覆盖板的面（无则普通方块破坏，零开销返回）
        List<ForgeDirection> sides = null;
        for (ForgeDirection d : ForgeDirection.VALID_DIRECTIONS) {
            if (coverable.getCoverAtSide(d) instanceof GTswnCoverWirelessBase) {
                if (sides == null) {
                    sides = new ArrayList<>();
                }
                sides.add(d);
            }
        }
        if (sides == null) {
            return;
        }
        EntityPlayer player = event.getPlayer();
        if (player == null) {
            // 防御：正常挖掘路径必有玩家
            return;
        }
        boolean alt = LinkNodeDismantleMarkerQueue.isMarked(player.getUniqueID());
        boolean heldWrench = GTUtility.isStackInList(player.getHeldItem(), GregTechAPI.sWrenchList);
        if (alt && heldWrench) {
            // 分流 1：Alt+扳手 = 静默清除全部链路节点覆盖板
            for (ForgeDirection d : sides) {
                // 返回栈丢弃 = 不掉落不进背包；detachCover 内 onCoverRemoval 照常执行
                // （节点索引出册 + 剩余缓冲 EU 回网）
                coverable.detachCover(d);
            }
            // D3 兜底出册（幂等，坐标未册时无效果）：防覆盖板被绕过终端移除后索引残留，
            // 同 WirelessEnergyTap 拆除路径
            WirelessNodeRegistry.get(event.world)
                .unregister(event.world, event.x, event.y, event.z);
        } else if (player.isSneaking()) {
            // 分流 2：原版潜行拆机已静默清除，零新代码即正确——直接放行
            return;
        } else if (heldWrench && !player.capabilities.isCreativeMode) {
            // 分流 3：生存玩家无修饰键扳手拆机的前 N 次操作引导（创造不计数不提示）
            NBTTagCompound persisted = player.getEntityData()
                .getCompoundTag("PlayerPersisted");
            int shown = persisted.getInteger(HINT_COUNT_KEY);
            if (shown < HINT_COUNT_MAX) {
                persisted.setInteger(HINT_COUNT_KEY, shown + 1);
                player.getEntityData()
                    .setTag("PlayerPersisted", persisted);
                player.addChatMessage(new ChatComponentTranslation("gtswn.chat.linknode.dismantle_hint"));
            }
        }
    }
}
