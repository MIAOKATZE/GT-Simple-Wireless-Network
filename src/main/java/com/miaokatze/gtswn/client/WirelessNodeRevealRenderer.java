package com.miaokatze.gtswn.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.world.WorldEvent;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import com.miaokatze.gtswn.common.covers.WirelessNodeIndexCodec;
import com.miaokatze.gtswn.network.PacketSyncNodeReveal;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * 手持链路终端节点显形渲染器（RenderWorldLastEvent 穿墙线框）。
 * <p>
 * 数据流（切片接缝）：包 12 客户端 Handler → @SidedProxy → ClientProxy 以
 * {@code Minecraft.func_152344_a} 切主线程 → {@link #acceptReveal} 写入本缓存 →
 * {@code RenderWorldLastEvent} 每帧读取并绘制 12 条边线框（type：0=能源=黄色 /
 * 1=动力=紫色，60 秒恒定不闪烁）。
 * <p>
 * 画法逐项照 {@code WirelessTapHighlightRenderer} 的成熟工具（blend、关纹理、
 * {@code GL20.glUseProgram(0)} 暂停光影、线宽 2×分辨率缩放、lastTickPos+partialTicks
 * 摄像机相对插值），并额外 {@code glDisable(GL_DEPTH_TEST)} 实现节点穿墙可见；
 * GL 状态进出成对（matrix/blend/texture/program/depth test）。
 * <p>
 * 过期语义（客户端时钟）：{@link #expireAt} = 客户端 {@code theWorld.getTotalWorldTime()
 * + durationTicks}——服务端与客户端时钟不同源不可直接比较，{@code serverTotalWorldTime}
 * 仅作契约保留（javadoc 记录用），不参与过期计算。空列表 {@code acceptReveal(..., empty)}
 * 语义 = 清空缓存（服务端无可见节点时也照发的约定回执）。{@code WorldEvent.Unload}
 * （{@code isRemote} 侧）清缓存防跨世界残留。
 */
public final class WirelessNodeRevealRenderer {

    /** 显形所在维度（客户端渲染时据此与当前维度比对，跨维度自动跳过） */
    private static int dimension;

    /** 入选节点缓存（acceptReveal 整体替换，防撕裂：先清后拷贝） */
    private static final List<PacketSyncNodeReveal.RevealedNode> NODES = new ArrayList<>();

    /**
     * 过期锚点（tick，客户端时钟）：acceptReveal 写入时取
     * {@code Minecraft.theWorld.getTotalWorldTime() + durationTicks}；
     * 服务端下发锚点仅契约保留，不参与比较。
     */
    private static long expireAt;

    // 能源节点线框颜色：黄色（醒目，代表"供电"，与 WirelessTapHighlightRenderer 同口径）
    private static final int ENERGY_RED = 255;
    private static final int ENERGY_GREEN = 213;
    private static final int ENERGY_BLUE = 0;

    // 动力节点线框颜色：紫色（醒目，代表"取电"，与 WirelessTapHighlightRenderer 同口径）
    private static final int DYNAMO_RED = 170;
    private static final int DYNAMO_GREEN = 51;
    private static final int DYNAMO_BLUE = 255;

    /** 透明度（0-255），60 秒恒定不闪烁 */
    private static final int ALPHA = 160;

    /** 线宽基准（与 GT 默认接近，按显示高度自适应缩放） */
    private static final float BASE_LINE_WIDTH = 2.0f;
    private static final float BASE_HEIGHT = 1080F;

    /** 线框外扩量：防 Z-fighting（与 WirelessTapHighlightRenderer 同值） */
    private static final double BOX_EXPAND = 0.002D;

    /**
     * 事件监听实例构造器（仅 ClientProxy init 注册一次；节点缓存本身全 static 共享）。
     */
    public WirelessNodeRevealRenderer() {}

    /**
     * 主线程写入（由 ClientProxy 调度，勿在 Netty 线程调用）：
     * 整体替换显形缓存——空列表等价清缓存。
     * <p>
     * 过期锚点在本方法用<b>客户端</b>世界 tick 计算（服务端时钟不同源不可比较）；
     * {@code theWorld} 为 null（尚未进世界）时丢弃本次。
     *
     * @param dimension            显形所在维度（调用方取客户端当前维度）
     * @param serverTotalWorldTime 服务端下发的时间锚点（仅契约保留，不参与过期计算）
     * @param durationTicks        显形时长（tick，服务端权威 1200t=60s）
     * @param revealedNodes        入选节点（null 视为空 = 清缓存）
     */
    public static void acceptReveal(int dimension, long serverTotalWorldTime, int durationTicks,
        List<PacketSyncNodeReveal.RevealedNode> revealedNodes) {
        final WorldClient world = Minecraft.getMinecraft().theWorld;
        if (world == null) {
            // 无客户端世界无从渲染，丢弃本次
            return;
        }
        WirelessNodeRevealRenderer.dimension = dimension;
        NODES.clear();
        if (revealedNodes != null) {
            NODES.addAll(revealedNodes);
        }
        // 过期锚点 = 客户端当前世界 tick + 时长
        expireAt = world.getTotalWorldTime() + durationTicks;
    }

    /**
     * 每帧世界末渲染：绘制所有显形节点的 12 条边线框（穿墙）。
     * <p>
     * 双守卫：缓存空 / 玩家维度 ≠ 缓存维度 → 跳过；客户端时钟过 {@link #expireAt} →
     * 清缓存并跳过。GL 状态照 {@code WirelessTapHighlightRenderer} 成对进出，
     * 额外关深度测试实现穿墙可见。
     */
    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        final Minecraft mc = Minecraft.getMinecraft();
        final WorldClient world = mc.theWorld;
        final EntityPlayer player = mc.thePlayer;
        // 缓存空 / 无世界 / 无玩家：跳过
        if (NODES.isEmpty() || world == null || player == null) {
            return;
        }
        // 维度守卫：跨维度不渲染
        if (world.provider.dimensionId != dimension) {
            return;
        }
        // 过期守卫（客户端时钟）：到点清缓存
        if (world.getTotalWorldTime() >= expireAt) {
            clearCache();
            return;
        }

        GL11.glPushMatrix();

        // === OpenGL 状态准备（照 WirelessTapHighlightRenderer，额外关深度测试穿墙） ===
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        // 暂停 shader，避免线框被光影 shader 干扰
        final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(0);
        // 线宽按显示高度自适应，保证不同分辨率下视觉效果一致
        GL11.glLineWidth(BASE_LINE_WIDTH * (mc.displayHeight / BASE_HEIGHT));
        // 穿墙可见：关闭深度测试（结束处成对恢复）
        GL11.glDisable(GL11.GL_DEPTH_TEST);

        // 摄像机相对坐标（插值，避免视角移动时线框抖动）
        final double camX = player.lastTickPosX + (player.posX - player.lastTickPosX) * (double) event.partialTicks;
        final double camY = player.lastTickPosY + (player.posY - player.lastTickPosY) * (double) event.partialTicks;
        final double camZ = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * (double) event.partialTicks;

        for (PacketSyncNodeReveal.RevealedNode node : NODES) {
            drawNodeBox(world, node, camX, camY, camZ);
        }

        // === 恢复 OpenGL 状态（与准备阶段成对） ===
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL20.glUseProgram(program); // 恢复 shader
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glPopMatrix(); // 恢复模型视图矩阵
    }

    /**
     * World 卸载（客户端侧）：清缓存，防跨世界（下线/切维度世界重建）残留渲染。
     */
    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.world.isRemote) {
            clearCache();
        }
    }

    /**
     * 绘制单节点 12 条边线框（照 {@code WirelessTapHighlightRenderer} 的
     * LINE_STRIP×2 + LINES 模式）：方块真实包围盒 + 0.002 外扩 − 摄像机相对坐标。
     * <p>
     * 节点方块未加载/已被破坏时 {@code getBlock} 退化为 air，绘制默认整格包围盒
     * （显形语义是"注册表索引位置"，非当前方块形态，仍应可见）。
     */
    private static void drawNodeBox(World world, PacketSyncNodeReveal.RevealedNode node, double camX, double camY,
        double camZ) {
        final Block block = world.getBlock(node.x, node.y, node.z);
        block.setBlockBoundsBasedOnState(world, node.x, node.y, node.z);
        AxisAlignedBB box = block.getSelectedBoundingBoxFromPool(world, node.x, node.y, node.z);
        if (box == null) {
            return;
        }
        // 微扩张防 Z-fighting（线框与方块表面重叠导致的闪烁）
        box = box.expand(BOX_EXPAND, BOX_EXPAND, BOX_EXPAND);
        // 摄像机相对坐标
        box = box.getOffsetBoundingBox(-camX, -camY, -camZ);

        // 颜色按节点类型区分：能源=黄，动力=紫（值域单一来源：WirelessNodeIndexCodec 常量）
        final boolean isDynamo = node.type == WirelessNodeIndexCodec.TYPE_DYNAMO;
        final int red = isDynamo ? DYNAMO_RED : ENERGY_RED;
        final int green = isDynamo ? DYNAMO_GREEN : ENERGY_GREEN;
        final int blue = isDynamo ? DYNAMO_BLUE : ENERGY_BLUE;

        final Tessellator tess = Tessellator.instance;

        // 底面 4 条边（LINE_STRIP 连续绘制）
        tess.startDrawing(GL11.GL_LINE_STRIP);
        tess.setColorRGBA(red, green, blue, ALPHA);
        tess.addVertex(box.minX, box.minY, box.minZ);
        tess.addVertex(box.maxX, box.minY, box.minZ);
        tess.addVertex(box.maxX, box.minY, box.maxZ);
        tess.addVertex(box.minX, box.minY, box.maxZ);
        tess.addVertex(box.minX, box.minY, box.minZ);
        tess.draw();

        // 顶面 4 条边
        tess.startDrawing(GL11.GL_LINE_STRIP);
        tess.setColorRGBA(red, green, blue, ALPHA);
        tess.addVertex(box.minX, box.maxY, box.minZ);
        tess.addVertex(box.maxX, box.maxY, box.minZ);
        tess.addVertex(box.maxX, box.maxY, box.maxZ);
        tess.addVertex(box.minX, box.maxY, box.maxZ);
        tess.addVertex(box.minX, box.maxY, box.minZ);
        tess.draw();

        // 4 条立柱（连接底面与顶面）
        tess.startDrawing(GL11.GL_LINES);
        tess.setColorRGBA(red, green, blue, ALPHA);
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

    /** 清空显形缓存（过期 / 世界卸载共用） */
    private static void clearCache() {
        NODES.clear();
        dimension = 0;
        expireAt = 0L;
    }

    /** @return 显形所在维度 */
    public static int getDimension() {
        return dimension;
    }

    /** @return 当前显形节点集（只读视图；可能为空） */
    public static List<PacketSyncNodeReveal.RevealedNode> getNodes() {
        return Collections.unmodifiableList(NODES);
    }

    /** @return 过期锚点（客户端时钟 tick），超过该值即显形结束 */
    public static long getExpireAt() {
        return expireAt;
    }
}
