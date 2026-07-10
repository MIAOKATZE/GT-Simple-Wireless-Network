package com.miaokatze.gtswn.client;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;
import net.minecraftforge.client.event.DrawBlockHighlightEvent;
import net.minecraftforge.common.util.ForgeDirection;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import com.miaokatze.gtswn.common.items.WirelessEnergyTap;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import gregtech.api.interfaces.tileentity.ICoverable;

/**
 * 无线链路终端辅助线渲染器
 * <p>
 * 监听 {@link DrawBlockHighlightEvent}，当玩家手持 {@link WirelessEnergyTap} 指向 GT 机器（{@link ICoverable}）时，
 * 绘制与 GT 扳手/覆盖板工具相同的九宫格辅助线（方块外框 + 面内井字格 + 覆盖板连接指示器）。
 * <p>
 * 实现方式：复制 gregtech.client.BlockOverlayRenderer.drawGrid 的渲染逻辑（该方法为 private static 无法直接调用）。
 * <p>
 * 颜色按链路终端模式区分，便于玩家直观识别：
 * <ul>
 * <li>能源模式（OutputMode=false）：黄色 RGBA(255, 213, 0, 160)</li>
 * <li>动力模式（OutputMode=true）：紫色 RGBA(170, 51, 255, 160)</li>
 * </ul>
 * <p>
 * 性能说明：GT 原版 BlockOverlayRenderer 也是用 @SubscribeEvent 监听器，每帧仅在准星指向方块时触发一次，
 * 本渲染器同理，且增加物品过滤（仅 WirelessEnergyTap）和目标过滤（仅 ICoverable），性能开销可忽略。
 */
public class WirelessTapHighlightRenderer {

    // 能源模式辅助线颜色：黄色（醒目，代表"供电"）
    private static final int ENERGY_RED = 255;
    private static final int ENERGY_GREEN = 213;
    private static final int ENERGY_BLUE = 0;

    // 动力模式辅助线颜色：紫色（醒目，代表"取电"）
    private static final int DYNAMO_RED = 170;
    private static final int DYNAMO_GREEN = 51;
    private static final int DYNAMO_BLUE = 255;

    // 透明度（0-255）
    private static final int ALPHA = 160;

    // 线宽基准（与 GT 默认接近，按显示高度自适应缩放）
    private static final float BASE_LINE_WIDTH = 2.0f;
    private static final float BASE_HEIGHT = 1080F;

    /**
     * 准星指向方块时触发
     * <p>
     * 判定条件：手持物品是 WirelessEnergyTap + 目标方块实体是 ICoverable（GT 机器/管道）。
     * 满足时取消原版黑色边框高亮，绘制自定义九宫格辅助线。
     */
    @SubscribeEvent
    public void onDrawBlockHighlight(DrawBlockHighlightEvent event) {
        // 物品过滤：必须是无线链路终端
        if (event.currentItem == null || !(event.currentItem.getItem() instanceof WirelessEnergyTap)) {
            return;
        }

        final MovingObjectPosition target = event.target;
        final EntityPlayer player = event.player;
        final World world = player.worldObj;

        // 目标过滤：必须是 GT 机器/管道（实现 ICoverable 接口）
        final TileEntity te = world.getTileEntity(target.blockX, target.blockY, target.blockZ);
        if (!(te instanceof ICoverable)) {
            return;
        }

        // 根据链路终端模式选择颜色：能源=黄，动力=紫
        final boolean isDynamoMode = WirelessEnergyTap.getOutputModeStatic(event.currentItem);
        final int red = isDynamoMode ? DYNAMO_RED : ENERGY_RED;
        final int green = isDynamoMode ? DYNAMO_GREEN : ENERGY_GREEN;
        final int blue = isDynamoMode ? DYNAMO_BLUE : ENERGY_BLUE;

        drawHighlightGrid(event, red, green, blue, ALPHA);
    }

    /**
     * 绘制九宫格辅助线
     * <p>
     * 仿写 gregtech.client.BlockOverlayRenderer.drawGrid 的渲染逻辑：
     * <ol>
     * <li>OpenGL 状态准备（混合、纹理、shader 暂停、线宽）</li>
     * <li>方块外框（12 条边：底面 4 + 顶面 4 + 立柱 4）</li>
     * <li>平移到目标面中心并旋转</li>
     * <li>面内九宫格（8 条线：2 横 + 2 竖）</li>
     * <li>覆盖板连接指示器（对角线/箭头，显示已放置覆盖板的面）</li>
     * <li>恢复 OpenGL 状态</li>
     * </ol>
     *
     * @param event 高亮事件
     * @param red   红色分量 0-255
     * @param green 绿色分量 0-255
     * @param blue  蓝色分量 0-255
     * @param alpha 透明度 0-255
     */
    private static void drawHighlightGrid(DrawBlockHighlightEvent event, int red, int green, int blue, int alpha) {
        // 取消原版默认黑色边框高亮，改用自定义辅助线
        event.setCanceled(true);

        GL11.glPushMatrix();

        // === OpenGL 状态准备 ===
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        // 暂停 shader，避免辅助线被光影 shader 干扰
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(0);
        // 线宽按显示高度自适应，保证不同分辨率下视觉效果一致
        GL11.glLineWidth(BASE_LINE_WIDTH * (Minecraft.getMinecraft().displayHeight / BASE_HEIGHT));

        final MovingObjectPosition target = event.target;
        final EntityPlayer player = event.player;
        // 摄像机相对坐标（插值，避免视角移动时辅助线抖动）
        final double camX = player.lastTickPosX + (player.posX - player.lastTickPosX) * (double) event.partialTicks;
        final double camY = player.lastTickPosY + (player.posY - player.lastTickPosY) * (double) event.partialTicks;
        final double camZ = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * (double) event.partialTicks;

        final TileEntity te = player.worldObj.getTileEntity(target.blockX, target.blockY, target.blockZ);
        final Block block = player.worldObj.getBlock(target.blockX, target.blockY, target.blockZ);

        // === 1. 绘制方块外框（12 条边） ===
        if (block.getMaterial() != Material.air) {
            final World world = player.worldObj;
            block.setBlockBoundsBasedOnState(world, target.blockX, target.blockY, target.blockZ);
            AxisAlignedBB box = block
                .getSelectedBoundingBoxFromPool(world, target.blockX, target.blockY, target.blockZ);
            // 微扩张防 Z-fighting（线框与方块表面重叠导致的闪烁）
            box = box.expand(0.002D, 0.002D, 0.002D);
            box = box.getOffsetBoundingBox(-camX, -camY, -camZ);

            Tessellator tess = Tessellator.instance;

            // 底面 4 条边（LINE_STRIP 连续绘制）
            tess.startDrawing(GL11.GL_LINE_STRIP);
            tess.setColorRGBA(red, green, blue, alpha);
            tess.addVertex(box.minX, box.minY, box.minZ);
            tess.addVertex(box.maxX, box.minY, box.minZ);
            tess.addVertex(box.maxX, box.minY, box.maxZ);
            tess.addVertex(box.minX, box.minY, box.maxZ);
            tess.addVertex(box.minX, box.minY, box.minZ);
            tess.draw();

            // 顶面 4 条边
            tess.startDrawing(GL11.GL_LINE_STRIP);
            tess.setColorRGBA(red, green, blue, alpha);
            tess.addVertex(box.minX, box.maxY, box.minZ);
            tess.addVertex(box.maxX, box.maxY, box.minZ);
            tess.addVertex(box.maxX, box.maxY, box.maxZ);
            tess.addVertex(box.minX, box.maxY, box.maxZ);
            tess.addVertex(box.minX, box.maxY, box.minZ);
            tess.draw();

            // 4 条立柱（连接底面与顶面）
            tess.startDrawing(GL11.GL_LINES);
            tess.setColorRGBA(red, green, blue, alpha);
            tess.addVertex(box.minX, box.minY, box.minZ);
            tess.addVertex(box.minX, box.maxY, box.minZ);
            tess.addVertex(box.maxX, box.minY, box.minZ);
            tess.addVertex(box.maxX, box.maxY, box.minZ);
            tess.addVertex(box.maxX, box.minY, box.maxZ);
            tess.addVertex(box.maxX, box.maxY, box.maxZ);
            tess.addVertex(box.minX, box.minY, box.maxZ);
            tess.addVertex(box.minX, box.maxY, box.maxZ);
            tess.draw();
        }

        // === 2. 平移到目标方块中心，并旋转到准星指向的面 ===
        // 坐标系切换为"方块局部坐标"：先减去摄像机坐标取整部分，再对齐到方块中心(0.5)
        GL11.glTranslated(target.blockX - (int) camX, target.blockY - (int) camY, target.blockZ - (int) camZ);
        GL11.glTranslated(0.5D - (camX - (int) camX), 0.5D - (camY - (int) camY), 0.5D - (camZ - (int) camZ));
        final int tSideHit = target.sideHit;
        // 旋转坐标系，使 -Y 方向（基准朝下）朝向准星指向的面外侧
        // 等效于 CCL Rotation.sideRotations[tSideHit].glApply()
        // CCL 以 -Y (DOWN) 为基准，sideRotations[i] 将 -Y 旋转到方向 i
        // 辅助线绘制在局部 -Y 方向（下方 glTranslated(0, -0.502, 0)），经此旋转后对齐到准星面外侧
        switch (tSideHit) {
            case 0 -> { /* DOWN: 单位变换，-Y 已朝下，无旋转 */ }
            case 1 -> GL11.glRotatef(180F, 1F, 0F, 0F); // UP: 绕 X 轴 180°，-Y → +Y
            case 2 -> GL11.glRotatef(90F, 1F, 0F, 0F); // NORTH: 绕 X 轴 +90°，-Y → -Z
            case 3 -> GL11.glRotatef(-90F, 1F, 0F, 0F); // SOUTH: 绕 X 轴 -90°，-Y → +Z
            case 4 -> GL11.glRotatef(-90F, 0F, 0F, 1F); // WEST: 绕 Z 轴 -90°，-Y → -X
            case 5 -> GL11.glRotatef(90F, 0F, 0F, 1F); // EAST: 绕 Z 轴 +90°，-Y → +X
            default -> {}
        }
        // 向外推 0.002，使辅助线浮在方块表面之上，避免 Z-fighting
        GL11.glTranslated(0.0D, -0.502D, 0.0D);

        // === 3. 绘制面内九宫格（8 条线：2 横 + 2 竖，构成井字） ===
        final Tessellator tess = Tessellator.instance;
        tess.startDrawing(GL11.GL_LINES);
        tess.setColorRGBA(red, green, blue, alpha);
        // 两条横线（Z=-0.25 和 Z=+0.25）
        tess.addVertex(+.50D, .0D, -.25D);
        tess.addVertex(-.50D, .0D, -.25D);
        tess.addVertex(+.50D, .0D, +.25D);
        tess.addVertex(-.50D, .0D, +.25D);
        // 两条竖线（X=+0.25 和 X=-0.25）
        tess.addVertex(+.25D, .0D, -.50D);
        tess.addVertex(+.25D, .0D, +.50D);
        tess.addVertex(-.25D, .0D, -.50D);
        tess.addVertex(-.25D, .0D, +.50D);

        // === 4. 绘制覆盖板连接指示器 ===
        // 显示已放置覆盖板的面（对角线/箭头标记），帮助玩家确认当前连接状态
        if (te instanceof ICoverable coverable) {
            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                if (coverable.hasCoverAtSide(side)) {
                    // 根据"准星面 + 已有覆盖板面"查表，确定连接指示器类型
                    int indicatorType = GRID_SWITCH_TABLE[tSideHit][side.ordinal()];
                    switch (indicatorType) {
                        case 0 -> { // 中心：X 型对角线
                            tess.addVertex(+.25D, .0D, +.25D);
                            tess.addVertex(-.25D, .0D, -.25D);
                            tess.addVertex(-.25D, .0D, +.25D);
                            tess.addVertex(+.25D, .0D, -.25D);
                        }
                        case 1 -> { // 上边：三角箭头
                            tess.addVertex(-.25D, .0D, +.50D);
                            tess.addVertex(+.25D, .0D, +.25D);
                            tess.addVertex(-.25D, .0D, +.25D);
                            tess.addVertex(+.25D, .0D, +.50D);
                        }
                        case 2 -> { // 左边：三角箭头
                            tess.addVertex(-.50D, .0D, -.25D);
                            tess.addVertex(-.25D, .0D, +.25D);
                            tess.addVertex(-.50D, .0D, +.25D);
                            tess.addVertex(-.25D, .0D, -.25D);
                        }
                        case 3 -> { // 下边：三角箭头
                            tess.addVertex(-.25D, .0D, -.50D);
                            tess.addVertex(+.25D, .0D, -.25D);
                            tess.addVertex(-.25D, .0D, -.25D);
                            tess.addVertex(+.25D, .0D, -.50D);
                        }
                        case 4 -> { // 右边：三角箭头
                            tess.addVertex(+.50D, .0D, -.25D);
                            tess.addVertex(+.25D, .0D, +.25D);
                            tess.addVertex(+.50D, .0D, +.25D);
                            tess.addVertex(+.25D, .0D, -.25D);
                        }
                        case 5 -> { // 四角：L 型标记（4 个角各画一组）
                            tess.addVertex(+.50D, .0D, +.50D);
                            tess.addVertex(+.25D, .0D, +.25D);
                            tess.addVertex(+.50D, .0D, +.25D);
                            tess.addVertex(+.25D, .0D, +.50D);
                            tess.addVertex(+.50D, .0D, -.50D);
                            tess.addVertex(+.25D, .0D, -.25D);
                            tess.addVertex(+.50D, .0D, -.25D);
                            tess.addVertex(+.25D, .0D, -.50D);
                            tess.addVertex(-.50D, .0D, +.50D);
                            tess.addVertex(-.25D, .0D, +.25D);
                            tess.addVertex(-.50D, .0D, +.25D);
                            tess.addVertex(-.25D, .0D, +.50D);
                            tess.addVertex(-.50D, .0D, -.50D);
                            tess.addVertex(-.25D, .0D, -.25D);
                            tess.addVertex(-.50D, .0D, -.25D);
                            tess.addVertex(-.25D, .0D, -.50D);
                        }
                        default -> { // case -1 或其他：不绘制
                        }
                    }
                }
            }
        }
        tess.draw();

        // === 5. 恢复 OpenGL 状态 ===
        GL20.glUseProgram(program); // 恢复 shader
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glPopMatrix(); // 恢复模型视图矩阵
    }

    /**
     * 九宫格连接指示器查表
     * <p>
     * 复制自 gregtech.client.BlockOverlayRenderer.GRID_SWITCH_TABLE。
     * 行索引 = 准星指向的面 sideHit (0-5)，列索引 = 已放置覆盖板的面 ordinal (0-5)。
     * 值含义：0=中心X型，1=上箭头，2=左箭头，3=下箭头，4=右箭头，5=四角L型，-1=不绘制。
     */
    private static final int[][] GRID_SWITCH_TABLE = new int[][] { { 0, 5, 3, 1, 2, 4 }, { 5, 0, 1, 3, 2, 4 },
        { 1, 3, 0, 5, 2, 4 }, { 3, 1, 5, 0, 2, 4 }, { 4, 2, 3, 1, 0, 5 }, { 2, 4, 3, 1, 5, 0 }, };
}
