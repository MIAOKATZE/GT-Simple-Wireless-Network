package com.miaokatze.gtswn.client.render;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.common.util.ForgeDirection;

import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;

import appeng.api.networking.IGridHost;
import cpw.mods.fml.client.registry.ISimpleBlockRenderingHandler;
import cpw.mods.fml.client.registry.RenderingRegistry;

/**
 * 量子节点 ISBRH 渲染器（v1.6.1 问题 1）：小核心 + 朝邻接 AE 网格宿主方向渲染连接臂，
 * 视觉仿 AE 线缆，使节点能自然贴在 AE 面板/线缆/机器旁。
 * <p>
 * 【连接判定】邻居 TileEntity instanceof {@link IGridHost} 即画臂。已核实 AE2 源码：
 * {@code TileCableBus implements AEMultiTile}（线缆/面板的宿主），而
 * {@code AEMultiTile extends IGridHost}（appeng/helpers/AEMultiTile.java:17），
 * 机器 Tile 经 {@code IGridProxyable extends IGridHost} 接入——故单一 instanceof
 * 同时覆盖 AE 线缆、面板与机器；本模组的量子节点 TE 亦实现 IGridProxyable，但
 * v1.6.19 起节点间不连臂（量子节点之间互不连接，渲染与 AE2 连接逻辑保持一致）。
 * <p>
 * 【双端安全】renderId 静态字段由 {@code ClientProxy.init()} 调 {@link #register()} 时赋值，
 * Block.getRenderType 只读 Block 类上的 int 字段，不直接引用本客户端类。
 */
public class RenderNetworkQuantumNode implements ISimpleBlockRenderingHandler {

    /** 单例（无状态渲染器） */
    public static final RenderNetworkQuantumNode INSTANCE = new RenderNetworkQuantumNode();

    /** 由 {@link #register()} 赋值；默认 -1 代表未注册（服务端不会走到渲染路径） */
    private static int renderId = -1;

    /** 核心包围盒边界（5/16 ~ 11/16，与 BlockNetworkQuantumNode 构造器中的 setBlockBounds 一致） */
    private static final double C0 = 0.3125D;

    private static final double C1 = 0.6875D;

    /**
     * 六向连接臂包围盒 {minX,minY,minZ,maxX,maxY,maxZ}，按 {@link ForgeDirection} ordinal 排列
     * （DOWN/UP/NORTH/SOUTH/WEST/EAST），臂从核心延伸至对应方块面。
     */
    private static final double[][] ARM_BOUNDS = {
        // spotless:off
        { C0, 0.0D, C0, C1, C0,  C1  }, // DOWN
        { C0, C1,   C0, C1, 1.0D, C1 }, // UP
        { C0, C0,   0.0D, C1, C1,  C0 }, // NORTH
        { C0, C0,   C1,   C1, C1, 1.0D }, // SOUTH
        { 0.0D, C0, C0,   C0, C1,  C1 }, // WEST
        { C1, C0,   C0, 1.0D, C1,  C1 }, // EAST
        // spotless:on
    };

    /** 申请 renderId 并注册本 ISBRH（仅客户端，由 ClientProxy.init 调用） */
    public static void register() {
        renderId = RenderingRegistry.getNextAvailableRenderId();
        RenderingRegistry.registerBlockHandler(renderId, INSTANCE);
    }

    // 注：不再提供 static getRenderId() 访问器——与接口实例方法 getRenderId() 签名冲突（Java 禁止同类
    // 同名 static/实例方法共存）。外部读取 renderId 请走 INSTANCE.getRenderId()。

    /**
     * 世界内渲染：核心（方块自身包围盒）+ 六向连接臂（邻居为 AE 网格宿主时）。
     * <p>
     * 注意 1.7.10 接口签名为 boolean 返回值（true = 已渲染）。
     */
    @Override
    public boolean renderWorldBlock(IBlockAccess world, int x, int y, int z, Block block, int modelId,
        RenderBlocks renderer) {
        // v1.6.13 任务1：防御 world 为空
        if (world == null) {
            return false;
        }
        // 核心：renderBounds 取自方块 setBlockBounds 设定的小核心包围盒
        renderer.setRenderBoundsFromBlock(block);
        renderer.renderStandardBlock(block, x, y, z);
        // 六向：邻居为 AE 网格宿主（IGridHost，含 AE 线缆/面板/机器，不含本模组量子节点）时渲染连接臂
        for (ForgeDirection d : ForgeDirection.VALID_DIRECTIONS) {
            TileEntity neighbor = world.getTileEntity(x + d.offsetX, y + d.offsetY, z + d.offsetZ);
            // v1.6.13 任务1：增加 null 检查；v1.6.19：量子节点之间不连臂
            if (neighbor != null && neighbor instanceof IGridHost
                && !(neighbor instanceof TileEntityNetworkQuantumNode)) {
                double[] b = ARM_BOUNDS[d.ordinal()];
                renderer.setRenderBounds(b[0], b[1], b[2], b[3], b[4], b[5]);
                renderer.renderStandardBlock(block, x, y, z);
            }
        }
        return true;
    }

    /**
     * 物品栏渲染：仅渲染小核心（1.7.10 惯用写法：Tessellator 逐面 startDrawingQuads + renderFace*，
     * 参考 GT5U RenderSpaceElevatorCable#renderInventoryBlock）。
     * renderFace* 读取 renderer 当前 renderBounds，故先 setRenderBounds 为核心盒。
     */
    @Override
    public void renderInventoryBlock(Block block, int metadata, int modelId, RenderBlocks renderer) {
        renderer.setRenderBounds(C0, C0, C0, C1, C1, C1);
        IIcon icon = block.getIcon(0, metadata);
        Tessellator tess = Tessellator.instance;
        // 物品渲染原点在 (-0.5,-0.5,-0.5)，先平移使核心居中
        GL11.glTranslatef(-0.5F, -0.5F, -0.5F);
        tess.startDrawingQuads();
        tess.setNormal(0.0F, -1.0F, 0.0F);
        renderer.renderFaceYNeg(block, 0.0D, 0.0D, 0.0D, icon);
        tess.draw();
        tess.startDrawingQuads();
        tess.setNormal(0.0F, 1.0F, 0.0F);
        renderer.renderFaceYPos(block, 0.0D, 0.0D, 0.0D, icon);
        tess.draw();
        tess.startDrawingQuads();
        tess.setNormal(0.0F, 0.0F, -1.0F);
        renderer.renderFaceZNeg(block, 0.0D, 0.0D, 0.0D, icon);
        tess.draw();
        tess.startDrawingQuads();
        tess.setNormal(0.0F, 0.0F, 1.0F);
        renderer.renderFaceZPos(block, 0.0D, 0.0D, 0.0D, icon);
        tess.draw();
        tess.startDrawingQuads();
        tess.setNormal(-1.0F, 0.0F, 0.0F);
        renderer.renderFaceXNeg(block, 0.0D, 0.0D, 0.0D, icon);
        tess.draw();
        tess.startDrawingQuads();
        tess.setNormal(1.0F, 0.0F, 0.0F);
        renderer.renderFaceXPos(block, 0.0D, 0.0D, 0.0D, icon);
        tess.draw();
        GL11.glTranslatef(0.5F, 0.5F, 0.5F);
    }

    /** 物品栏中以 3D 渲染（核心小方块） */
    @Override
    public boolean shouldRender3DInInventory(int modelId) {
        return true;
    }

    @Override
    public int getRenderId() {
        return renderId;
    }
}
