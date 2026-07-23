package com.miaokatze.gtswn.client;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;
import net.minecraftforge.client.event.DrawBlockHighlightEvent;
import net.minecraftforge.common.util.ForgeDirection;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;

import appeng.tile.networking.TileController;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * 量子节点放置预览框渲染器（v1.6.1 问题 2，规划 plan_20260723125054.md 任务 D）。
 * <p>
 * 监听 {@link DrawBlockHighlightEvent}，当玩家手持<b>已绑定</b>的
 * {@link ItemNetworkQuantumTerminal} 且准星指向可放置位置时，在放置目标点绘制
 * 量子节点小核心（5/16~11/16）的青色预览盒（线框 + 半透明填充），效果仿 AE 放置线缆的预览。
 * <p>
 * 【条件一致性】预览条件逐项对齐服务端
 * {@code ItemNetworkQuantumTerminal.onItemUseFirst / handleBlockClick} 的真实放置条件，
 * 保证「显示预览 ⇔ 右击能放上」，不出现两者不一致的情况：
 * <ol>
 * <li>手持 ME 网络量子终端（onItemUseFirst 仅对本物品触发）</li>
 * <li>终端已绑定（未绑定 handleBlockClick 直接 return false）</li>
 * <li>非潜行（潜行走收回节点/开 GUI/取消量子化等其他手势）</li>
 * <li>命中类型为 BLOCK</li>
 * <li>命中方块不是 ME 控制器（命中控制器时服务端分发到 handleControllerClick，
 * 执行量子化/绑定/取消手势，不会放置节点）</li>
 * <li>命中面外移一格的位置 isReplaceable（服务端唯一硬性位置校验；
 * 服务端<b>无</b>附着面/支撑方块校验，预览同样不加）</li>
 * </ol>
 * <p>
 * 【事件取消费略】<b>不</b>取消事件：与 WirelessTapHighlightRenderer 不同，本渲染器是
 * 「附加预览」而非「替代高亮」——保留原版指向方块黑色轮廓，玩家仍能看清自己在点哪个方块，
 * 预览盒叠加绘制在相邻放置位上，两者互不冲突（均有深度测试）。
 * <p>
 * 渲染手法复用 {@link WirelessTapHighlightRenderer} 的 GL 状态管理惯例：
 * pushMatrix → 混合/关纹理/暂停 shader/自适应线宽 → 摄像机插值相对坐标 → 绘制 → 恢复。
 */
public class QuantumNodeHighlightRenderer {

    // ==================== 预览盒外观（青色系，量子主题） ====================

    /** 红色分量 */
    private static final float RED = 0.2F;

    /** 绿色分量 */
    private static final float GREEN = 0.9F;

    /** 蓝色分量 */
    private static final float BLUE = 1.0F;

    /** 线框透明度 */
    private static final float LINE_ALPHA = 0.6F;

    /** 半透明填充透明度 */
    private static final float FILL_ALPHA = 0.4F;

    /**
     * 节点小核心包围盒边界（5/16 ~ 11/16），与 BlockNetworkQuantumNode 构造器 setBlockBounds
     * 及 RenderNetworkQuantumNode.C0/C1 保持一致（任务 C 已确认）
     */
    private static final double CORE_MIN = 0.3125D;

    private static final double CORE_MAX = 0.6875D;

    // 线宽基准（与 WirelessTapHighlightRenderer 相同，按显示高度自适应缩放）
    private static final float BASE_LINE_WIDTH = 2.0F;
    private static final float BASE_HEIGHT = 1080F;

    /**
     * 准星指向方块时触发。
     * <p>
     * 六个条件全部满足才绘制预览盒，条件逐项对齐服务端放置判定（见类 javadoc）。
     */
    @SubscribeEvent
    public void onDrawBlockHighlight(DrawBlockHighlightEvent event) {
        // 条件 1：手持物品必须是 ME 网络量子终端
        if (event.currentItem == null || !(event.currentItem.getItem() instanceof ItemNetworkQuantumTerminal)) {
            return;
        }
        // 条件 2：终端必须已绑定锚点控制器（未绑定则服务端 handleBlockClick 放行，不会放置）
        if (!ItemNetworkQuantumTerminal.isBound(event.currentItem)) {
            return;
        }
        // 条件 3：非潜行（潜行是收回/打开 GUI 等其他手势，服务端直接放行）
        if (event.player.isSneaking()) {
            return;
        }
        // 条件 4：准星必须命中方块（命中实体/空气无放置语义）
        final MovingObjectPosition target = event.target;
        if (target == null || target.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return;
        }
        final EntityPlayer player = event.player;
        final World world = player.worldObj;
        // 条件 5：命中 ME 控制器时服务端走 handleControllerClick（量子化/绑定/取消手势），
        // 不会放置节点，不显示预览
        if (world.getTileEntity(target.blockX, target.blockY, target.blockZ) instanceof TileController) {
            return;
        }
        // 条件 6：放置目标点 = 命中方块沿命中面外移一格，且该位置可替换
        final ForgeDirection face = ForgeDirection.getOrientation(target.sideHit);
        final int px = target.blockX + face.offsetX;
        final int py = target.blockY + face.offsetY;
        final int pz = target.blockZ + face.offsetZ;
        final Block blockAtPos = world.getBlock(px, py, pz);
        if (!blockAtPos.isReplaceable(world, px, py, pz)) {
            return;
        }

        drawPreviewBox(event, px, py, pz);
    }

    /**
     * 在 (px,py,pz) 处绘制节点小核心预览盒：先画半透明填充（不写深度），再画 12 边线框。
     *
     * @param event 高亮事件（提供玩家与 partialTicks 用于摄像机插值）
     * @param px    放置目标点 X
     * @param py    放置目标点 Y
     * @param pz    放置目标点 Z
     */
    private static void drawPreviewBox(DrawBlockHighlightEvent event, int px, int py, int pz) {
        final EntityPlayer player = event.player;
        // 摄像机相对坐标（插值，避免视角移动时预览盒抖动）
        final double camX = player.lastTickPosX + (player.posX - player.lastTickPosX) * (double) event.partialTicks;
        final double camY = player.lastTickPosY + (player.posY - player.lastTickPosY) * (double) event.partialTicks;
        final double camZ = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * (double) event.partialTicks;

        // 核心盒（方块局部 5/16~11/16），微扩张防 Z-fighting，再平移到视角相对坐标
        AxisAlignedBB box = AxisAlignedBB.getBoundingBox(CORE_MIN, CORE_MIN, CORE_MIN, CORE_MAX, CORE_MAX, CORE_MAX);
        box = box.expand(0.002D, 0.002D, 0.002D);
        box = box.getOffsetBoundingBox(px - camX, py - camY, pz - camZ);

        GL11.glPushMatrix();

        // === OpenGL 状态准备（与 WirelessTapHighlightRenderer 相同惯例） ===
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        // 暂停 shader，避免预览盒被光影 shader 干扰
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(0);
        // 线宽按显示高度自适应，保证不同分辨率下视觉效果一致
        GL11.glLineWidth(BASE_LINE_WIDTH * (Minecraft.getMinecraft().displayHeight / BASE_HEIGHT));

        final Tessellator tess = Tessellator.instance;

        // === 1. 半透明填充（6 面） ===
        // 不写深度：防止盒面之间互相遮挡产生条纹；关闭面剔除：玩家贴脸看入盒内时仍可见
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_CULL_FACE);
        tess.startDrawingQuads();
        tess.setColorRGBA_F(RED, GREEN, BLUE, FILL_ALPHA);
        addQuad(
            tess,
            box.minX,
            box.minY,
            box.minZ,
            box.minX,
            box.minY,
            box.maxZ,
            box.minX,
            box.maxY,
            box.maxZ,
            box.minX,
            box.maxY,
            box.minZ); // -X 面
        addQuad(
            tess,
            box.maxX,
            box.minY,
            box.minZ,
            box.maxX,
            box.maxY,
            box.minZ,
            box.maxX,
            box.maxY,
            box.maxZ,
            box.maxX,
            box.minY,
            box.maxZ); // +X 面
        addQuad(
            tess,
            box.minX,
            box.minY,
            box.minZ,
            box.maxX,
            box.minY,
            box.minZ,
            box.maxX,
            box.minY,
            box.maxZ,
            box.minX,
            box.minY,
            box.maxZ); // -Y 面
        addQuad(
            tess,
            box.minX,
            box.maxY,
            box.minZ,
            box.minX,
            box.maxY,
            box.maxZ,
            box.maxX,
            box.maxY,
            box.maxZ,
            box.maxX,
            box.maxY,
            box.minZ); // +Y 面
        addQuad(
            tess,
            box.minX,
            box.minY,
            box.minZ,
            box.minX,
            box.maxY,
            box.minZ,
            box.maxX,
            box.maxY,
            box.minZ,
            box.maxX,
            box.minY,
            box.minZ); // -Z 面
        addQuad(
            tess,
            box.minX,
            box.minY,
            box.maxZ,
            box.maxX,
            box.minY,
            box.maxZ,
            box.maxX,
            box.maxY,
            box.maxZ,
            box.minX,
            box.maxY,
            box.maxZ); // +Z 面
        tess.draw();
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glDepthMask(true);

        // === 2. 线框（12 条边：底面 4 + 顶面 4 + 立柱 4，同 WirelessTapHighlightRenderer 画法） ===
        // 底面 4 条边（LINE_STRIP 连续绘制）
        tess.startDrawing(GL11.GL_LINE_STRIP);
        tess.setColorRGBA_F(RED, GREEN, BLUE, LINE_ALPHA);
        tess.addVertex(box.minX, box.minY, box.minZ);
        tess.addVertex(box.maxX, box.minY, box.minZ);
        tess.addVertex(box.maxX, box.minY, box.maxZ);
        tess.addVertex(box.minX, box.minY, box.maxZ);
        tess.addVertex(box.minX, box.minY, box.minZ);
        tess.draw();

        // 顶面 4 条边
        tess.startDrawing(GL11.GL_LINE_STRIP);
        tess.setColorRGBA_F(RED, GREEN, BLUE, LINE_ALPHA);
        tess.addVertex(box.minX, box.maxY, box.minZ);
        tess.addVertex(box.maxX, box.maxY, box.minZ);
        tess.addVertex(box.maxX, box.maxY, box.maxZ);
        tess.addVertex(box.minX, box.maxY, box.maxZ);
        tess.addVertex(box.minX, box.maxY, box.minZ);
        tess.draw();

        // 4 条立柱（连接底面与顶面）
        tess.startDrawing(GL11.GL_LINES);
        tess.setColorRGBA_F(RED, GREEN, BLUE, LINE_ALPHA);
        tess.addVertex(box.minX, box.minY, box.minZ);
        tess.addVertex(box.minX, box.maxY, box.minZ);
        tess.addVertex(box.maxX, box.minY, box.minZ);
        tess.addVertex(box.maxX, box.maxY, box.minZ);
        tess.addVertex(box.maxX, box.minY, box.maxZ);
        tess.addVertex(box.maxX, box.maxY, box.maxZ);
        tess.addVertex(box.minX, box.minY, box.maxZ);
        tess.addVertex(box.minX, box.maxY, box.maxZ);
        tess.draw();

        // === 3. 恢复 OpenGL 状态 ===
        GL20.glUseProgram(program); // 恢复 shader
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glPopMatrix(); // 恢复模型视图矩阵
    }

    /** 向 Tessellator 追加一个四边形面（4 顶点，调用方需已 startDrawingQuads 并设置颜色） */
    private static void addQuad(Tessellator tess, double x1, double y1, double z1, double x2, double y2, double z2,
        double x3, double y3, double z3, double x4, double y4, double z4) {
        tess.addVertex(x1, y1, z1);
        tess.addVertex(x2, y2, z2);
        tess.addVertex(x3, y3, z3);
        tess.addVertex(x4, y4, z4);
    }
}
