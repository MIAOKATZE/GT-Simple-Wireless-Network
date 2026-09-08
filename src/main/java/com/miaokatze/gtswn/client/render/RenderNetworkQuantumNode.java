package com.miaokatze.gtswn.client.render;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.common.util.ForgeDirection;

import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import appeng.client.render.BusRenderHelper;
import appeng.client.render.BusRenderer;
import appeng.client.render.RenderBlocksWorkaround;
import appeng.parts.CableBusContainer;
import cpw.mods.fml.client.registry.ISimpleBlockRenderingHandler;
import cpw.mods.fml.client.registry.RenderingRegistry;

/**
 * 量子节点 ISBRH 渲染器（v1.6.1 问题 1）：小核心 + 朝真实连接的 AE 网格方向渲染连接臂，
 * 视觉仿 AE 线缆，使节点能自然贴在 AE 面板/线缆/机器旁。
 * <p>
 * 【连接判定（v1.6.24）】服务端把 GridNode 真实连接映射为每方向位掩码，经 S35
 * （getDescriptionPacket NBT 键 "sides"，复刻 AE2 原生 PartCable writeToStream 策略）
 * 同步到客户端；渲染器只对置位方向画臂。已核实 AE2 原生线缆 PartCable：服务端
 * {@code for (IGridConnection gc : n.getConnections()) { ... cs |= 1 << side.ordinal(); }}
 * 仅对 getDirection 确定且非 UNKNOWN 的方向置位，客户端按位重建方向并只对连接方向渲染
 * （E:\...\Applied-Energistics-2-Unofficial-rv3-beta-1000-GTNH\
 * src\main\java\appeng\parts\networking\PartCable.java:357-372、388-421、301-303）。
 * v1.6.24 起删除原「邻居为 IGridHost 即画臂」判定：GT 普通机器/裸线缆锚宿主
 * 虽实现 IGridHost 但无真实网格连接，不再画臂；量子节点到锚点控制器的桥接连接方向无关
 * （getDirection 返回 UNKNOWN），天然不进掩码，也不会画出向控制器的臂。
 * <p>
 * 【双端安全】renderId 静态字段由 {@code ClientProxy.init()} 调 {@link #register()} 时赋值，
 * Block.getRenderType 只读 Block 类上的 int 字段，不直接引用本客户端类。
 */
public class RenderNetworkQuantumNode implements ISimpleBlockRenderingHandler {

    /** 单例（无状态渲染器） */
    public static final RenderNetworkQuantumNode INSTANCE = new RenderNetworkQuantumNode();

    /** 由 {@link #register()} 赋值；默认 -1 代表未注册（服务端不会走到渲染路径） */
    private static int renderId = -1;

    /**
     * v1.8.6：AE2 部件原生渲染总开关。renderStatic 签名探针全部未命中、或渲染入口类抛
     * LinkageError（宿主 AE2U 过老）时永久关闭并 log-once；关闭后核心 + 连接臂照常渲染
     * （= v1.8.3 行为），任何 AE2U 版本下不再产生崩溃面。
     */
    private static volatile boolean ae2PartRenderEnabled = true;

    /** v1.8.6：AE2 部件静态渲染抛异常时仅记首帧（避免区块重建期刷屏；不据此永久关闭渲染） */
    private static volatile boolean renderExceptionLogged = false;

    /** v1.8.6：反射解析缓存的 renderStatic（探针命中后恒定；volatile 保证跨区块构建线程可见） */
    private static volatile Method renderStaticMethod;

    /**
     * v1.8.6：{@code CableBusContainer.renderStatic} 候选签名探针序：
     * ① 4 参 (IBlockAccess,double,double,double)：仅 AE2U rv3-beta-1050+（GTNH 2.9.0 beta-3 基线）；
     * ② 3 参 (double,double,double)：rv3-beta-1000 及更早（内部经 CableRenderHelper +
     * Minecraft.getMinecraft().theWorld，与该版本 AE2 原生 RendererCableBus 渲染路径逐字等价）。
     */
    private static final Class<?>[][] RENDER_STATIC_SIGNATURES = {
        // spotless:off
        { IBlockAccess.class, double.class, double.class, double.class },
        { double.class, double.class, double.class },
        // spotless:on
    };

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
     * 世界内渲染：核心（方块自身包围盒）+ 六向连接臂（服务端同步的连接方向位掩码置位时）。
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
        // 六向：v1.6.24 起不再判邻居是否为 IGridHost 宿主，改为读本 TE 经 S35 同步的连接方向位掩码，
        // 只对置位方向画臂（self 为 null 或非本 TE 类型时 mask=0 不画臂，安全降级）
        TileEntity self = world.getTileEntity(x, y, z);
        int mask = (self instanceof TileEntityNetworkQuantumNode)
            ? ((TileEntityNetworkQuantumNode) self).getConnectedSidesMask()
            : 0;
        for (ForgeDirection d : ForgeDirection.VALID_DIRECTIONS) {
            if (((mask >> d.ordinal()) & 1) != 0) {
                double[] b = ARM_BOUNDS[d.ordinal()];
                renderer.setRenderBounds(b[0], b[1], b[2], b[3], b[4], b[5]);
                renderer.renderStandardBlock(block, x, y, z);
            }
        }
        // v1.8.5：AE2 部件原生渲染（镜像 RendererCableBus.renderInWorld :40-53）——
        // 换 BusRenderer 的 RenderBlocksWorkaround 驱动 CableRenderHelper 渲染容器内全部部件；
        // hasParts()=false 时零开销，行为与 v1.8.3 完全一致。
        // v1.8.6：4 参 renderStatic(IBlockAccess,double,double,double) 仅 AE2U rv3-beta-1050+ 存在，
        // rv3-beta-1000（玩家 GTNH 2.9.5 实机）只有 3 参变体，直调即 NoSuchMethodError 客户端崩溃
        // （crash-2026-09-08_09.48.53：容器含部件的节点构建区块网格时炸在 ：116）——改为反射签名
        // 探针 + LinkageError 兜底：任一 AE2U 版本最坏降级为不画部件（= v1.8.3 行为），绝不崩溃。
        if (self instanceof TileEntityNetworkQuantumNode && ae2PartRenderEnabled
            && ((TileEntityNetworkQuantumNode) self).hasParts()) {
            renderPartsReflective(world, x, y, z, (TileEntityNetworkQuantumNode) self, renderer);
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

    /**
     * v1.8.6：部件原生渲染主体（v1.8.5 直调块的兼容安全版）。
     * <p>
     * 整体捕获 {@link LinkageError}：若宿主 AE2U 更老致 BusRenderer/RenderBlocksWorkaround
     * 成员缺失（beta-1000 实机已被崩溃堆栈证明可达，此处仅防更早世代漂移），永久关闭部件渲染
     * 并 log-once。AE2 部件自身渲染异常（{@link InvocationTargetException}）按帧跳过、仅记首帧
     * ——其在 AE2 原生线缆总线上可独立复现观察，不在本 mod 崩溃面上掩盖；finally 恢复共享
     * rbw.renderAllFaces，防止异常路径污染 AE2 后续总线渲染状态。
     */
    private static void renderPartsReflective(IBlockAccess world, int x, int y, int z,
        TileEntityNetworkQuantumNode node, RenderBlocks renderer) {
        try {
            RenderBlocksWorkaround rbw = BusRenderer.INSTANCE.getRenderer();
            rbw.renderAllFaces = true;
            rbw.overrideBlockTexture = renderer.overrideBlockTexture;
            BusRenderHelper.instances.get()
                .setPass(0);
            try {
                invokeRenderStatic(node.getPartContainer(), world, x, y, z);
            } finally {
                rbw.renderAllFaces = false;
            }
        } catch (LinkageError e) {
            ae2PartRenderEnabled = false;
            GTSimpleWirelessNetwork.LOG.warn("[量子节点] AE2 部件渲染类不兼容，部件渲染永久降级关闭（核心与连接臂不受影响）", e);
        } catch (InvocationTargetException e) {
            if (!renderExceptionLogged) {
                renderExceptionLogged = true;
                GTSimpleWirelessNetwork.LOG.warn("[量子节点] AE2 部件静态渲染异常（后续同类静默跳过；核心与连接臂不受影响）", e.getCause());
            }
        }
    }

    /**
     * v1.8.6：反射分发 {@code CableBusContainer.renderStatic}。
     * <p>
     * 首次调用按 {@link #RENDER_STATIC_SIGNATURES} 探针序解析并静态缓存；两签名皆无
     * （未知 AE2U 版本）时永久关闭部件渲染并 log-once。多区块构建线程并发解析幂等
     * （getMethod 结果恒定，volatile 回写保证可见）。{@code setAccessible(true)} 后仍拒访
     * 属正常运行不可达，防御性记录。
     */
    private static void invokeRenderStatic(CableBusContainer container, IBlockAccess world, int x, int y, int z)
        throws InvocationTargetException {
        Method method = renderStaticMethod;
        if (method == null) {
            for (Class<?>[] signature : RENDER_STATIC_SIGNATURES) {
                try {
                    method = CableBusContainer.class.getMethod("renderStatic", signature);
                    method.setAccessible(true);
                    break;
                } catch (NoSuchMethodException ignored) {
                    // 尝试下一候选签名
                }
            }
            if (method == null) {
                ae2PartRenderEnabled = false;
                GTSimpleWirelessNetwork.LOG.warn("[量子节点] AE2U 无可识别的 CableBusContainer.renderStatic 签名，部件渲染降级关闭");
                return;
            }
            renderStaticMethod = method;
        }
        try {
            if (method.getParameterTypes().length == 4) {
                method.invoke(container, world, (double) x, (double) y, (double) z);
            } else {
                method.invoke(container, (double) x, (double) y, (double) z);
            }
        } catch (IllegalAccessException e) {
            GTSimpleWirelessNetwork.LOG.warn("[量子节点] renderStatic 反射调用被拒绝", e);
        }
    }

    @Override
    public int getRenderId() {
        return renderId;
    }
}
