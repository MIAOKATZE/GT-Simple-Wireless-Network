package com.miaokatze.gtswn.crossmod.waila;

import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;

import cpw.mods.fml.common.Optional;
import mcp.mobius.waila.api.IWailaConfigHandler;
import mcp.mobius.waila.api.IWailaDataAccessor;
import mcp.mobius.waila.api.IWailaDataProvider;

/**
 * 量子节点 WAILA 数据提供器（v1.6.2）。
 * <p>
 * 显示节点桥接状态：在线（已桥接至锚点网络）/ 离线。v1.6.4 起 WAILA 不显示离线原因，
 * 原因仍由右键聊天提示（chat.quantum.* 键系）提供。
 * 数据流：服务端 {@link #getNBTData} 把 {@code isLinked()}
 * 写入同步 NBT；客户端 {@link #getWailaBody} 读 NBT 渲染文本——避免客户端直接触达
 * 服务端专属的 AE 网格状态。
 * <p>
 * {@link Optional.Interface} 保证无 WAILA 时本类被加载也不实现接口（双保险：
 * 正常路径下本类仅经 WailaIntegration 在 isModLoaded 门控后触达）。
 */
@Optional.Interface(iface = "mcp.mobius.waila.api.IWailaDataProvider", modid = "Waila")
public class QuantumNodeWailaDataProvider implements IWailaDataProvider {

    /** 同步 NBT 键：桥接在线状态 */
    private static final String TAG_LINKED = "gtswn_qn_linked";

    @Override
    public List<String> getWailaBody(ItemStack itemStack, List<String> currenttip, IWailaDataAccessor accessor,
        IWailaConfigHandler config) {
        NBTTagCompound tag = accessor.getNBTData();
        // 同步数据未到位（如服务端无 NBT 提供器）时不追加任何行
        if (!tag.hasKey(TAG_LINKED)) {
            return currenttip;
        }
        if (tag.getBoolean(TAG_LINKED)) {
            currenttip.add(StatCollector.translateToLocal("gtswn.waila.quantum_node.online"));
        } else {
            // v1.6.4 起离线不显示原因，原因由右键聊天提示提供
            currenttip.add(StatCollector.translateToLocal("gtswn.waila.quantum_node.offline"));
        }
        return currenttip;
    }

    @Override
    public NBTTagCompound getNBTData(EntityPlayerMP player, TileEntity te, NBTTagCompound tag, World world, int x,
        int y, int z) {
        if (te instanceof TileEntityNetworkQuantumNode) {
            TileEntityNetworkQuantumNode node = (TileEntityNetworkQuantumNode) te;
            tag.setBoolean(TAG_LINKED, node.isLinked());
        }
        return tag;
    }

    // ==================== 接口其余方法：默认实现（不改动堆叠/头/尾） ====================

    @Override
    public ItemStack getWailaStack(IWailaDataAccessor accessor, IWailaConfigHandler config) {
        return null;
    }

    @Override
    public List<String> getWailaHead(ItemStack itemStack, List<String> currenttip, IWailaDataAccessor accessor,
        IWailaConfigHandler config) {
        return currenttip;
    }

    @Override
    public List<String> getWailaTail(ItemStack itemStack, List<String> currenttip, IWailaDataAccessor accessor,
        IWailaConfigHandler config) {
        return currenttip;
    }
}
