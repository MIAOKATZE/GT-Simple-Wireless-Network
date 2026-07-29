package com.miaokatze.gtswn.common.block;

import java.util.ArrayList;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
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
 * <p>
 * v1.6.4 任务4：状态材质——世界内按 TE 在线状态渲染（在线动画 / 离线静态），
 * 物品栏与破坏粒子等无世界上下文路径恒显示在线动画图标。
 */
public class BlockNetworkQuantumNode extends BlockContainer {

    /**
     * ISBRH 渲染 ID（v1.6.1 问题 1「线缆形态」）。
     * <p>
     * 【双端安全】Block 类只持有本 int 字段（默认 -1 未注册），不直接引用 client 包类，
     * 避免服务端加载 Block 时触发 NoClassDefFoundError；
     * 由 {@code ClientProxy.init()} 注册 ISBRH 后回写真实 renderId。
     */
    public static int renderId = -1;

    /** 在线动画图标（ME_Network_Quantum_Node.png，16x64 四帧竖条，mcmeta frametime=10） */
    private IIcon iconOnline;

    /** 离线静态图标（ME_Network_Quantum_Node_OFF.png，16x16 单帧，无 mcmeta） */
    private IIcon iconOffline;

    /**
     * 构造函数：初始化量子节点方块的基础属性
     */
    public BlockNetworkQuantumNode() {
        super(Material.iron);
        // 设置未本地化名称 (Block Name)，用于关联语言文件
        setBlockName("NetworkQuantumNode_GTswn");
        // 设置材质路径，指向 assets/gtswn/textures/blocks/ME_Network_Quantum_Node.png
        setBlockTextureName("gtswn:ME_Network_Quantum_Node");
        // 硬度 = 黑曜石 × 20 = 1000，与量子化控制器等效硬度一致
        setHardness(1000.0F);
        // 爆炸抗性 = 黑曜石 × 200 = 400000
        setResistance(400000.0F);
        // 脚步/破坏音效：金属（Material.iron 对应音色）
        setStepSound(Block.soundTypeMetal);
        // 加入模组创造模式标签页
        setCreativeTab(CreativeTabManager.CREATIVE_TAB);
        // v1.6.1 问题 1：线缆形态——小核心包围盒（5/16~11/16，仿 AE 线缆核心），
        // 选中框/碰撞箱即核心大小，连接臂仅作渲染延伸（见 RenderNetworkQuantumNode）
        setBlockBounds(0.3125F, 0.3125F, 0.3125F, 0.6875F, 0.6875F, 0.6875F);
    }

    // ==================== v1.6.1 问题 1：线缆形态渲染（非整方块） ====================

    /** 非不透明整方块：避免邻居面被剔除（核心四周需可见） */
    @Override
    public boolean isOpaqueCube() {
        return false;
    }

    /** 非标准整方块渲染：走 ISBRH 自定义渲染 */
    @Override
    public boolean renderAsNormalBlock() {
        return false;
    }

    /**
     * 渲染类型：返回静态 renderId（双端安全，见字段注释）。
     * 注意 BlockContainer 默认返回 -1（不渲染），必须覆盖为 ISBRH renderId。
     */
    @Override
    public int getRenderType() {
        return renderId;
    }

    // ==================== v1.6.4 任务4：状态材质（在线动画 / 离线静态） ====================

    @Override
    public void registerBlockIcons(IIconRegister register) {
        this.iconOnline = register.registerIcon("gtswn:ME_Network_Quantum_Node");
        this.iconOffline = register.registerIcon("gtswn:ME_Network_Quantum_Node_OFF");
        // 兼容第三方直接读 blockIcon 字段的路径（WAILA/NEI 图标等）
        this.blockIcon = this.iconOnline;
    }

    /** 世界内渲染图标（ISBRH renderStandardBlock → RenderBlocks.getBlockIcon → 本方法）：在线动画 / 离线静态 */
    @Override
    public IIcon getIcon(IBlockAccess world, int x, int y, int z, int side) {
        // v1.6.13 任务1：防御 TE 为空或类型不符
        if (world == null) {
            return this.iconOffline;
        }
        TileEntity te = world.getTileEntity(x, y, z);
        if (te instanceof TileEntityNetworkQuantumNode && ((TileEntityNetworkQuantumNode) te).isLinkedClient()) {
            return this.iconOnline;
        }
        return this.iconOffline;
    }

    /** 物品栏/破坏粒子等无世界上下文路径：恒显示在线动画图标 */
    @Override
    public IIcon getIcon(int side, int meta) {
        return this.iconOnline;
    }

    /**
     * 创建对应的 TileEntity（T1 为空实现存根，T4 将加入 AENetworkProxy 桥接）
     */
    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileEntityNetworkQuantumNode();
    }

    /**
     * v1.6.2：挖掘不掉落。
     * <p>
     * 节点只能经「Shift+右击」由量子终端销毁（见 onBlockActivated 潜行分支），挖掘直接销毁不掉落，
     * 防止玩家用镐子批量采掘绕过锚点语义（掉落物重新放置即丢失锚点，成为无效空白节点）。
     */
    @Override
    public ArrayList<ItemStack> getDrops(World world, int x, int y, int z, int metadata, int fortune) {
        return new ArrayList<ItemStack>();
    }

    /**
     * 禁用精准采集：即使通过精准采集镐挖掘也直接销毁，不掉落任何物品。
     */
    @Override
    public boolean canSilkHarvest(World world, EntityPlayer player, int x, int y, int z, int metadata) {
        return false;
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
        // v1.6.2：Shift+右击仅当手持量子终端时销毁节点（无掉落），pop 音效仿 AE 扳手回收
        if (player.isSneaking()) {
            ItemStack held = player.getHeldItem();
            if (held != null && held.getItem() instanceof ItemNetworkQuantumTerminal) {
                if (!world.isRemote) {
                    world.setBlockToAir(x, y, z);
                    world.playSoundEffect(
                        x + 0.5D,
                        y + 0.5D,
                        z + 0.5D,
                        "random.pop",
                        0.2F,
                        ((world.rand.nextFloat() - world.rand.nextFloat()) * 0.7F + 1.0F) * 2.0F);
                    player.addChatComponentMessage(new ChatComponentTranslation("gtswn.chat.quantum.node_destroyed"));
                }
                return true;
            }
            // 潜行但手持非终端：落入下方状态提示分支，不破坏方块
        }
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
