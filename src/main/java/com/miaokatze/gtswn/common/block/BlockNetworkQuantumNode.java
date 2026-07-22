package com.miaokatze.gtswn.common.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.register.CreativeTabManager;

/**
 * ME 网络量子节点（T1 存根，桥接逻辑 T4 实现）
 * <p>
 * 规划用途（见 plan_20260722152445.md）：本质是无频道上限的 ME 线缆，
 * 通过 AEApi.createGridConnection 桥接到锚点控制器所属网络，
 * 把相邻设备以普通 AE 邻接连接（每连接 ≤32 频道）接入对应 ME 网络。
 * <p>
 * 本任务（T1）仅完成注册骨架：黑曜石×10 级硬度/抗性、材质、创造 Tab，
 * createNewTileEntity 返回空 TE 存根，不实现任何网络桥接逻辑。
 */
public class BlockNetworkQuantumNode extends BlockContainer {

    /**
     * 构造函数：初始化量子节点方块的基础属性
     */
    public BlockNetworkQuantumNode() {
        super(Material.iron);
        // 设置未本地化名称 (Block Name)，用于关联语言文件
        setBlockName("NetworkQuantumNode_GTswn");
        // 设置材质路径，指向 assets/gtswn/textures/blocks/ME_Network_Quantum_Node.png
        setBlockTextureName("gtswn:ME_Network_Quantum_Node");
        // 硬度先给黑曜石基础值 50：等效 500（黑曜石×10）的挖掘减速由 T2 的
        // PlayerEvent.BreakSpeed 事件实现，与量子化 ME 控制器的处理方式保持一致
        setHardness(50.0F);
        // 爆炸抗性 = 黑曜石级 6000
        setResistance(6000.0F);
        // 脚步/破坏音效：金属（Material.iron 对应音色）
        setStepSound(Block.soundTypeMetal);
        // 加入模组创造模式标签页
        setCreativeTab(CreativeTabManager.CREATIVE_TAB);
    }

    /**
     * 创建对应的 TileEntity（T1 为空实现存根，T4 将加入 AENetworkProxy 桥接）
     */
    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileEntityNetworkQuantumNode();
    }

    /**
     * 右键显示节点桥接状态（T4，规划 §3「右键显示状态」）。
     * <p>
     * 客户端直接返回 true（等待服务端权威消息，与服务端返回 true 保持 C08 交互一致）；
     * 服务端以 {@link TileEntityNetworkQuantumNode#isLinked()} 为权威在线判据，离线时按
     * {@link TileEntityNetworkQuantumNode#getOfflineReasonKey()} 给出原因提示。
     * 瞬态说明：连接刚断开而 20tick 维护循环尚未刷新原因缓存时（≤1 秒窗口），
     * 离线原因可能仍为 NONE，此时按通用离线键提示，避免误报在线。
     */
    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        if (world.isRemote) {
            return true;
        }
        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof TileEntityNetworkQuantumNode)) {
            return false;
        }
        TileEntityNetworkQuantumNode node = (TileEntityNetworkQuantumNode) tile;
        String key;
        if (node.isLinked()) {
            key = "gtswn.chat.quantum.node_online";
        } else if (node.getOfflineReason() == TileEntityNetworkQuantumNode.OfflineReason.NONE) {
            // 瞬态窗口：连接已断但原因缓存未刷新，按通用离线处理
            key = "gtswn.chat.quantum.node_offline";
        } else {
            key = node.getOfflineReasonKey();
        }
        player.addChatComponentMessage(new ChatComponentTranslation(key));
        return true;
    }
}
