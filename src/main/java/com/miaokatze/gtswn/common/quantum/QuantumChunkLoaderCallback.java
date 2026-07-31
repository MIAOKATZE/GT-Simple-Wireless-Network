package com.miaokatze.gtswn.common.quantum;

import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.LoadingCallback;
import net.minecraftforge.common.ForgeChunkManager.Ticket;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

/**
 * v1.6.10：量子节点跨维度强制加载的 ForgeChunkManager callback。
 * <p>
 * 持久化策略：只持久化 nodeTicket（节点维度 ticket，modData 存 type=node + 节点坐标）。
 * 服务器重启后 ForgeChunkManager 加载持久化的 nodeTicket 并调用 ticketsLoaded，
 * callback 据此找到节点 TE 并关联 nodeTicket → 节点区块强制加载 → TE tick →
 * maintainTickets 重建 anchorTicket → 锚点区块强制加载 → 桥接恢复。
 * <p>
 * anchorTicket（type=anchor）不在此恢复，callback 遇到直接释放，靠 TE tick 重建
 * （避免锚点维度 callback 找不到跨维度节点 TE 的顺序依赖问题）。
 * <p>
 * 实现的是 {@link LoadingCallback}（而非 {@link net.minecraftforge.common.ForgeChunkManager.OrderedLoadingCallback}）：
 * 本项目不需要按 maxTicketCount 配额筛选持久化 ticket，也不需要 player ticket，
 * 只需在服务器重启后处理已加载的 ticket 列表，故选最简接口。
 */
public class QuantumChunkLoaderCallback implements LoadingCallback {

    @Override
    public void ticketsLoaded(List<Ticket> tickets, World world) {
        for (Ticket ticket : tickets) {
            NBTTagCompound data = ticket.getModData();
            // 无 type 标识的 ticket 不是本系统签发的，跳过（防御性，理论上不会出现）
            if (!data.hasKey("type")) {
                continue;
            }
            String type = data.getString("type");
            if ("node".equals(type)) {
                // nodeTicket：找节点 TE 关联，节点 TE tick 后自动重建 anchorTicket
                int nodeX = data.getInteger("nodeX");
                int nodeY = data.getInteger("nodeY");
                int nodeZ = data.getInteger("nodeZ");
                TileEntity te = world.getTileEntity(nodeX, nodeY, nodeZ);
                if (te instanceof TileEntityNetworkQuantumNode) {
                    ((TileEntityNetworkQuantumNode) te).setNodeTicketFromCallback(ticket);
                    GTSimpleWirelessNetwork.LOG
                        .debug("[量子节点] callback 恢复 nodeTicket @ ({},{},{})", nodeX, nodeY, nodeZ);
                } else {
                    // 节点 TE 已不存在（被销毁但 ticket 残留）：释放，避免配额泄漏
                    ForgeChunkManager.releaseTicket(ticket);
                }
            } else if ("anchor".equals(type)) {
                // anchorTicket：不恢复，直接释放（靠节点 TE tick 重建）
                // 设计原因：anchorTicket 在锚点维度，但持有它的 TE 在节点维度，
                // 跨维度 callback 找 TE 有顺序依赖问题；改由 nodeTicket 恢复后 TE tick 重建
                ForgeChunkManager.releaseTicket(ticket);
            }
        }
    }
}
