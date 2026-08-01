package com.miaokatze.gtswn.common.quantum;

import java.util.List;

import net.minecraft.world.World;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.LoadingCallback;
import net.minecraftforge.common.ForgeChunkManager.Ticket;

import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

/**
 * 清理旧版本量子节点遗留的 ForgeChunkManager Ticket。
 * <p>
 * 当前量子节点不再主动申请或恢复 Ticket，而是仿 AE2 无线接入器依赖服务器或其他模组的自然区块加载。
 * Forge 在加载旧 forcedchunks.dat 时仍会回调本类，因此必须释放所有属于本模组的旧 Ticket。
 * 不依赖旧 Ticket 的 modData 类型，避免未知或损坏标识造成 Ticket 泄漏。
 */
public class QuantumChunkLoaderCallback implements LoadingCallback {

    @Override
    public void ticketsLoaded(List<Ticket> tickets, World world) {
        int released = 0;
        for (Ticket ticket : tickets) {
            ForgeChunkManager.releaseTicket(ticket);
            released++;
        }
        if (released > 0) {
            GTSimpleWirelessNetwork.LOG.info(
                "[Quantum Node] Released {} legacy ForgeChunkManager ticket(s); natural chunk loading is now used",
                released);
        }
    }
}
