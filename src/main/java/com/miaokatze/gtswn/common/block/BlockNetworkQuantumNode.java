package com.miaokatze.gtswn.common.block;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IIcon;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.register.CreativeTabManager;

import appeng.api.parts.PartItemStack;
import appeng.api.parts.SelectedPart;
import appeng.util.LookDirection;
import appeng.util.Platform;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

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
 * <p>
 * v1.8.5：AE2 部件宿主方块面——右键部件交互、核心∪部件碰撞/选框/射线
 * （仿 AEBaseBlock :188-333）、破坏/受控销毁弹射部件掉落、中键取部件、红石与邻居转发。
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

    /** 核心包围盒下界（5/16，与构造器 setBlockBounds 一致；v1.8.5 碰撞/射线复用） */
    private static final float CORE_MIN = 0.3125F;

    /** 核心包围盒上界（11/16） */
    private static final float CORE_MAX = 0.6875F;

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
        // v1.8.5：零光阻（仿 BlockCableBus :89-97）——非整方块 + 部件贴面渲染需要，
        // 否则节点占位的面会把相邻方块的贴面剔除成黑洞
        setLightOpacity(0);
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
     * 右键处理（T4 状态提示 + v1.8.5 部件交互/受控销毁）。
     * <p>
     * v1.8.5 优先级：①装有部件时先尝试命中部件交互（仿 BlockCableBus.onActivated :387-391，
     * hitX/Y/Z 即局部坐标）；②Shift+右击且手持量子终端 → 销毁节点（弹射部件掉落后 setBlockToAir，
     * 仿 breakBlock 链 cb.getDrops :891-908）；③状态提示（客户端直接返回 true，服务端以
     * {@link TileEntityNetworkQuantumNode#isLinked()} 为权威在线判据，离线时按
     * {@link TileEntityNetworkQuantumNode#getOfflineReasonKey()} 给出原因提示）。
     * 瞬态说明：连接刚断开而 20tick 维护循环尚未刷新原因缓存时（≤1 秒窗口），
     * 离线原因可能仍为 NONE，此时按通用离线键提示，避免误报在线。
     */
    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile instanceof TileEntityNetworkQuantumNode) {
            TileEntityNetworkQuantumNode node = (TileEntityNetworkQuantumNode) tile;
            // v1.8.5：命中部件已处理则直接结束（部件 GUI/开关等由 AE2 部件自身接管）
            if (node.hasParts() && node.getPartContainer()
                .activate(player, Vec3.createVectorHelper(hitX, hitY, hitZ))) {
                return true;
            }
            // v1.6.2：Shift+右击仅当手持量子终端时销毁节点（本体无掉落），pop 音效仿 AE 扳手回收
            if (player.isSneaking()) {
                ItemStack held = player.getHeldItem();
                if (held != null && held.getItem() instanceof ItemNetworkQuantumTerminal) {
                    if (!world.isRemote) {
                        // v1.8.5：部件掉落由 breakBlock 统一弹射（setBlockToAir 必经），
                        // 此处不手动弹射，否则与 breakBlock 各掉一份翻倍
                        world.setBlockToAir(x, y, z);
                        world.playSoundEffect(
                            x + 0.5D,
                            y + 0.5D,
                            z + 0.5D,
                            "random.pop",
                            0.2F,
                            ((world.rand.nextFloat() - world.rand.nextFloat()) * 0.7F + 1.0F) * 2.0F);
                        player
                            .addChatComponentMessage(new ChatComponentTranslation("gtswn.chat.quantum.node_destroyed"));
                    }
                    return true;
                }
                // 潜行但手持非终端：落入下方状态提示分支，不破坏方块
            }
        }
        if (world.isRemote) {
            return true;
        }
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

    // ==================== v1.8.5：AE2 部件方块面（碰撞/选框/射线/掉落/红石/邻居） ====================

    /** 取本方块 TE（类型不符/缺失返回 null，所有部件面方法共用） */
    private TileEntityNetworkQuantumNode getNodeTE(IBlockAccess world, int x, int y, int z) {
        TileEntity te = world.getTileEntity(x, y, z);
        return te instanceof TileEntityNetworkQuantumNode ? (TileEntityNetworkQuantumNode) te : null;
    }

    /**
     * 核心盒 ∪ 部件容器盒（全部为方块内局部坐标 0-1，仿 TileCableBus.getSelectedBoundingBoxesFromPool
     * :238-241 取 ignoreConnections=false, includeFacades=true；调用方自行加 x/y/z 偏移）。
     */
    private List<AxisAlignedBB> getCoreAndPartBoxes(TileEntityNetworkQuantumNode node, Entity e, boolean visual) {
        List<AxisAlignedBB> boxes = new ArrayList<>();
        boxes.add(AxisAlignedBB.getBoundingBox(CORE_MIN, CORE_MIN, CORE_MIN, CORE_MAX, CORE_MAX, CORE_MAX));
        for (AxisAlignedBB bb : node.getPartContainer()
            .getSelectedBoundingBoxesFromPool(false, true, e, visual)) {
            boxes.add(AxisAlignedBB.getBoundingBox(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ));
        }
        return boxes;
    }

    /** 把方块包围盒恢复为核心盒（构造器初值；v1.8.5 射线/选框遍历每步后调用） */
    private void restoreCoreBounds() {
        setBlockBounds(CORE_MIN, CORE_MIN, CORE_MIN, CORE_MAX, CORE_MAX, CORE_MAX);
    }

    /**
     * v1.8.5：碰撞箱 = 核心盒 ∪ 部件盒（仿 AEBaseBlock.addCollisionBoxesToList :188-209）。
     * <p>
     * 空容器（无部件）直接走 super——与 v1.8.3 现状逐字节一致（仅核心盒）。
     */
    @Override
    @SuppressWarnings("unchecked")
    public void addCollisionBoxesToList(World world, int x, int y, int z, AxisAlignedBB mask, List output, Entity e) {
        TileEntityNetworkQuantumNode node = getNodeTE(world, x, y, z);
        if (node == null || !node.hasParts()) {
            super.addCollisionBoxesToList(world, x, y, z, mask, output, e);
            return;
        }
        for (AxisAlignedBB bb : getCoreAndPartBoxes(node, e, false)) {
            AxisAlignedBB abs = AxisAlignedBB
                .getBoundingBox(bb.minX + x, bb.minY + y, bb.minZ + z, bb.maxX + x, bb.maxY + y, bb.maxZ + z);
            if (mask == null || mask.intersectsWith(abs)) {
                output.add(abs);
            }
        }
    }

    /**
     * v1.8.5：选框（仿 AEBaseBlock.getSelectedBoundingBoxFromPool :213-279）。
     * <p>
     * 客户端用玩家视线在逐盒射线上取最近命中盒作为选框（避免多盒并集撑满整格）；
     * 空容器走 super——与 v1.8.3 现状一致（核心盒）。
     */
    @Override
    @SideOnly(Side.CLIENT)
    public AxisAlignedBB getSelectedBoundingBoxFromPool(World world, int x, int y, int z) {
        TileEntityNetworkQuantumNode node = getNodeTE(world, x, y, z);
        if (node == null || !node.hasParts()) {
            return super.getSelectedBoundingBoxFromPool(world, x, y, z);
        }
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player != null) {
            LookDirection ld = Platform.getPlayerRay(player, Platform.getEyeOffset(player));
            AxisAlignedBB best = null;
            double lastDist = 0.0D;
            for (AxisAlignedBB bb : getCoreAndPartBoxes(node, player, true)) {
                setBlockBounds(
                    (float) bb.minX,
                    (float) bb.minY,
                    (float) bb.minZ,
                    (float) bb.maxX,
                    (float) bb.maxY,
                    (float) bb.maxZ);
                MovingObjectPosition r = super.collisionRayTrace(world, x, y, z, ld.getA(), ld.getB());
                restoreCoreBounds();
                if (r != null) {
                    double dx = ld.getA().xCoord - r.hitVec.xCoord;
                    double dy = ld.getA().yCoord - r.hitVec.yCoord;
                    double dz = ld.getA().zCoord - r.hitVec.zCoord;
                    double dist = dx * dx + dy * dy + dz * dz;
                    if (best == null || lastDist > dist) {
                        lastDist = dist;
                        best = bb;
                    }
                }
            }
            if (best != null) {
                return best.setBounds(
                    best.minX + x,
                    best.minY + y,
                    best.minZ + z,
                    best.maxX + x,
                    best.maxY + y,
                    best.maxZ + z);
            }
        }
        // 后备（无玩家视线/无命中）：全盒并集（仿 AEBaseBlock :260-275）
        AxisAlignedBB union = AxisAlignedBB.getBoundingBox(16.0D, 16.0D, 16.0D, 0.0D, 0.0D, 0.0D);
        for (AxisAlignedBB bb : getCoreAndPartBoxes(node, null, false)) {
            union.setBounds(
                Math.min(union.minX, bb.minX),
                Math.min(union.minY, bb.minY),
                Math.min(union.minZ, bb.minZ),
                Math.max(union.maxX, bb.maxX),
                Math.max(union.maxY, bb.maxY),
                Math.max(union.maxZ, bb.maxZ));
        }
        return union
            .setBounds(union.minX + x, union.minY + y, union.minZ + z, union.maxX + x, union.maxY + y, union.maxZ + z);
    }

    /**
     * v1.8.5：逐盒射线取最近命中（仿 AEBaseBlock.collisionRayTrace :287-333）。
     * <p>
     * AE2 PartPlacement.place :73 依赖宿主本方法把视线定位到具体部件面。
     * 空容器走 super——与 v1.8.3 现状一致（核心盒射线）。遍历后恢复核心盒
     * （而非 AE2 的满盒复位）：本方块碰撞/选框以 blockBounds 为权威，必须还原。
     */
    @Override
    public MovingObjectPosition collisionRayTrace(World world, int x, int y, int z, Vec3 a, Vec3 b) {
        TileEntityNetworkQuantumNode node = getNodeTE(world, x, y, z);
        if (node == null || !node.hasParts()) {
            return super.collisionRayTrace(world, x, y, z, a, b);
        }
        MovingObjectPosition best = null;
        double lastDist = 0.0D;
        for (AxisAlignedBB bb : getCoreAndPartBoxes(node, null, true)) {
            setBlockBounds(
                (float) bb.minX,
                (float) bb.minY,
                (float) bb.minZ,
                (float) bb.maxX,
                (float) bb.maxY,
                (float) bb.maxZ);
            MovingObjectPosition r = super.collisionRayTrace(world, x, y, z, a, b);
            restoreCoreBounds();
            if (r != null) {
                double dx = a.xCoord - r.hitVec.xCoord;
                double dy = a.yCoord - r.hitVec.yCoord;
                double dz = a.zCoord - r.hitVec.zCoord;
                double dist = dx * dx + dy * dy + dz * dz;
                if (best == null || lastDist > dist) {
                    lastDist = dist;
                    best = r;
                }
            }
        }
        return best;
    }

    /**
     * v1.8.5：方块被移除（挖掘/爆炸/指令）时弹射部件掉落（仿 AEBaseTileBlock.breakBlock →
     * te.getDrops → Platform.spawnDrops 链）。节点本体仍不掉落（getDrops 保持空，现状）。
     * vanilla 在 super.breakBlock 内才移除 TE，此时尚可读取容器。
     */
    @Override
    public void breakBlock(World world, int x, int y, int z, Block block, int meta) {
        TileEntityNetworkQuantumNode node = getNodeTE(world, x, y, z);
        if (node != null && node.hasParts()) {
            List<ItemStack> drops = new ArrayList<>();
            node.getPartContainer()
                .getDrops(drops);
            Platform.spawnDrops(world, x, y, z, drops);
        }
        super.breakBlock(world, x, y, z, block, meta);
    }

    /**
     * v1.8.5：创造中键取部件物品（仿 BlockCableBus.getPickBlock :212-225）。
     * 未命中部件时走 super（GTNH patch 默认 null → 调用方回退到方块物品，与现状一致）。
     */
    @Override
    public ItemStack getPickBlock(MovingObjectPosition target, World world, int x, int y, int z, EntityPlayer player) {
        TileEntityNetworkQuantumNode node = getNodeTE(world, x, y, z);
        if (node != null && node.hasParts() && target != null && target.hitVec != null) {
            Vec3 local = target.hitVec.addVector(-x, -y, -z);
            SelectedPart sp = node.getPartContainer()
                .selectPart(local);
            if (sp.part != null) {
                return sp.part.getItemStack(PartItemStack.Pick);
            }
            if (sp.facade != null) {
                return sp.facade.getItemStack();
            }
        }
        return super.getPickBlock(target, world, x, y, z, player);
    }

    /** v1.8.5：节点本体永不被替换放置（部件挂节点、不占节点位） */
    @Override
    public boolean isReplaceable(IBlockAccess world, int x, int y, int z) {
        return false;
    }

    /** v1.8.5：部件可能输出红石（仿 BlockCableBus.canProvidePower；空部件时 weak=0，净效果与现状一致） */
    @Override
    public boolean canProvidePower() {
        return true;
    }

    @Override
    public int isProvidingWeakPower(IBlockAccess world, int x, int y, int z, int side) {
        TileEntityNetworkQuantumNode node = getNodeTE(world, x, y, z);
        if (node != null && node.hasParts()) {
            return node.getPartContainer()
                .isProvidingWeakPower(
                    ForgeDirection.getOrientation(side)
                        .getOpposite());
        }
        return 0;
    }

    @Override
    public int isProvidingStrongPower(IBlockAccess world, int x, int y, int z, int side) {
        TileEntityNetworkQuantumNode node = getNodeTE(world, x, y, z);
        if (node != null && node.hasParts()) {
            return node.getPartContainer()
                .isProvidingStrongPower(
                    ForgeDirection.getOrientation(side)
                        .getOpposite());
        }
        return 0;
    }

    /** v1.8.5：红石连接判定（仿 BlockCableBus.canConnectRedstone :189-199 的 side→方向映射） */
    @Override
    public boolean canConnectRedstone(IBlockAccess world, int x, int y, int z, int side) {
        TileEntityNetworkQuantumNode node = getNodeTE(world, x, y, z);
        if (node != null && node.hasParts()) {
            switch (side) {
                case -1:
                case 4:
                    return node.getPartContainer()
                        .canConnectRedstone(EnumSet.of(ForgeDirection.UP, ForgeDirection.DOWN));
                case 0:
                    return node.getPartContainer()
                        .canConnectRedstone(EnumSet.of(ForgeDirection.NORTH));
                case 1:
                    return node.getPartContainer()
                        .canConnectRedstone(EnumSet.of(ForgeDirection.EAST));
                case 2:
                    return node.getPartContainer()
                        .canConnectRedstone(EnumSet.of(ForgeDirection.SOUTH));
                case 3:
                    return node.getPartContainer()
                        .canConnectRedstone(EnumSet.of(ForgeDirection.WEST));
                default:
                    return false;
            }
        }
        return false;
    }

    /**
     * v1.8.5：邻居变化转发部件容器（仿 BlockCableBus.onNeighborBlockChange :104-107，
     * 含红石缓存失效与部件 onNeighborChanged）；无部件时零开销。
     */
    @Override
    public void onNeighborBlockChange(World world, int x, int y, int z, Block neighbor) {
        super.onNeighborBlockChange(world, x, y, z, neighbor);
        TileEntityNetworkQuantumNode node = getNodeTE(world, x, y, z);
        if (node != null && node.hasParts()) {
            node.getPartContainer()
                .onNeighborChanged();
        }
    }
}
