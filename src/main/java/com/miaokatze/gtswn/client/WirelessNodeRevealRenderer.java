package com.miaokatze.gtswn.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.world.WorldEvent;

import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.common.covers.WirelessNodeIndexCodec;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;
import com.miaokatze.gtswn.network.PacketSyncNodeReveal;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * 手持链路终端节点显形渲染器（RenderWorldLastEvent 穿墙线框）。
 * <p>
 * 数据流（切片接缝）：包 12 客户端 Handler → @SidedProxy → ClientProxy 以
 * {@code Minecraft.func_152344_a} 切主线程 → {@link #acceptReveal} 写入本缓存 →
 * {@code RenderWorldLastEvent} 每帧读取并绘制 12 条边线框（type：0=能源=黄色 /
 * 1=动力=紫色；500ms 相位与白色交替闪烁提高可视性）。
 * <p>
 * 画法（v1.7.22 换血）：逐项照 FindIt {@code fx/BlockHighlighter.java:31-68} +
 * {@code fx/FxHelper.java:41-73} 的 Angelica 实证兼容画法——raw {@code glBegin/glEnd}
 * 立即模式（每盒 24 顶点单条 GL_LINE_STRIP 覆盖 12 条边）、
 * {@code glPushAttrib(GL_ALL_ATTRIB_BITS)/glPopAttrib} 成对整体保管 GL 状态、
 * {@code glDisable(GL_TEXTURE_2D)} + 穿墙 {@code glDisable(GL_DEPTH_TEST)}、
 * 不开 blend、不透明 glColor。坐标基准保持 lastTickPos×partialTicks 插值 +
 * {@code glTranslated(-cam)}（对齐 FindIt BlockHighlighter.java:47-55 语义）。
 * <p>
 * 过期语义（客户端墙钟，v1.7.22 起不再依赖世界 tick）：{@link #expireAt} =
 * 收包时刻 {@code System.currentTimeMillis() + durationTicks×50ms}；
 * {@code serverTotalWorldTime} 仅作契约保留（javadoc 记录用），不参与过期计算。
 * 空列表 {@code acceptReveal(..., empty)} 语义 = 清空缓存（服务端无可见节点时也照发的
 * 约定回执）。{@code WorldEvent.Unload}（{@code isRemote} 侧）清缓存防跨世界残留。
 */
public final class WirelessNodeRevealRenderer {

    /** 显形所在维度（客户端渲染时据此与当前维度比对，跨维度自动跳过） */
    private static int dimension;

    /** 入选节点缓存（acceptReveal 整体替换，防撕裂：先清后拷贝） */
    private static final List<PacketSyncNodeReveal.RevealedNode> NODES = new ArrayList<>();

    /**
     * 过期锚点（客户端墙钟 ms，System.currentTimeMillis 基准）：acceptReveal 写入时取
     * 收包时刻 + {@code durationTicks×50ms}；服务端下发锚点仅契约保留，不参与比较。
     */
    private static long expireAt;

    // 能源节点线框颜色：黄色（醒目，代表"供电"）
    private static final int ENERGY_RED = 255;
    private static final int ENERGY_GREEN = 213;
    private static final int ENERGY_BLUE = 0;

    // 动力节点线框颜色：紫色（醒目，代表"取电"）
    private static final int DYNAMO_RED = 170;
    private static final int DYNAMO_GREEN = 51;
    private static final int DYNAMO_BLUE = 255;

    /** 线宽基准（与 GT 默认接近，按显示高度自适应缩放） */
    private static final float BASE_LINE_WIDTH = 2.0f;
    private static final float BASE_HEIGHT = 1080F;

    /** 线框外扩量：防 Z-fighting（与 WirelessTapHighlightRenderer 同值） */
    private static final double BOX_EXPAND = 0.002D;

    /** [GTSWN-REVEAL-PROBE] C5 帧计数器（100 帧节流打点，仅客户端主线程读写） */
    private static int frameCounter;

    /** [GTSWN-REVEAL-PROBE] C6 首帧绘制打点标记（每次显形会话只打一次） */
    private static boolean drewLogged;

    /**
     * 事件监听实例构造器（仅 ClientProxy init 注册一次；节点缓存本身全 static 共享）。
     */
    public WirelessNodeRevealRenderer() {}

    /**
     * 主线程写入（由 ClientProxy 调度，勿在 Netty 线程调用）：
     * 整体替换显形缓存——空列表等价清缓存。
     * <p>
     * 过期锚点在本方法用<b>客户端墙钟</b>计算（收包时刻 + {@code durationTicks×50ms}，
     * 世界 tick 不同源不可靠）；{@code theWorld} 为 null（尚未进世界）时丢弃本次。
     *
     * @param dimension            显形所在维度（调用方取客户端当前维度）
     * @param serverTotalWorldTime 服务端下发的时间锚点（仅契约保留，不参与过期计算）
     * @param durationTicks        显形时长（tick，服务端权威 1200t=60s，1t=50ms）
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
        // 过期锚点 = 收包墙钟时刻 + 时长（tick→ms 换算 1t=50ms）
        expireAt = System.currentTimeMillis() + durationTicks * 50L;
        // [GTSWN-REVEAL-PROBE] 新会话重置 C6 首帧打点标记
        drewLogged = false;
    }

    /**
     * 每帧世界末渲染：绘制所有显形节点的 12 条边线框（穿墙）。
     * <p>
     * 双守卫：缓存空 / 玩家维度 ≠ 缓存维度 → 跳过；客户端墙钟过 {@link #expireAt} →
     * 清缓存并跳过。GL 状态照 FindIt BlockHighlighter 成对进出（push/pop attrib），
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
        // 过期守卫（客户端墙钟）：到点清缓存
        final long now = System.currentTimeMillis();
        if (now >= expireAt) {
            clearCache();
            return;
        }

        // [GTSWN-REVEAL-PROBE] C5：100 帧节流的渲染入口探针（走到此处缓存必非空）
        frameCounter++;
        if (frameCounter % 100 == 0) {
            GTSimpleWirelessNetwork.LOG
                .info("[GTSWN-REVEAL] C5 frame nodes=" + NODES.size() + " expireIn=" + (expireAt - now) + "ms");
        }

        // 500ms 闪烁相位：false=节点类型色 / true=白色（FindIt 同款墙钟相位）
        final boolean altColor = (now / 500L) % 2L == 1L;

        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);

        // 线宽按显示高度自适应，保证不同分辨率下视觉效果一致
        GL11.glLineWidth(BASE_LINE_WIDTH * (mc.displayHeight / BASE_HEIGHT));

        // 摄像机相对坐标（插值，避免视角移动时线框抖动）——对齐 FindIt glTranslated 语义
        final double camX = player.lastTickPosX + (player.posX - player.lastTickPosX) * (double) event.partialTicks;
        final double camY = player.lastTickPosY + (player.posY - player.lastTickPosY) * (double) event.partialTicks;
        final double camZ = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * (double) event.partialTicks;
        GL11.glTranslated(-camX, -camY, -camZ);

        // 穿墙可见 + 无纹理（FindIt 最小兼容组合：不混合、不透明 glColor）
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_TEXTURE_2D);

        int drawn = 0;
        for (PacketSyncNodeReveal.RevealedNode node : NODES) {
            if (drawNodeBox(world, node, altColor)) {
                drawn++;
            }
        }

        // [GTSWN-REVEAL-PROBE] C6：本次显形首个实际绘制帧打点一次（N>0 才打）
        if (drawn > 0 && !drewLogged) {
            drewLogged = true;
            GTSimpleWirelessNetwork.LOG.info("[GTSWN-REVEAL] C6 drew " + drawn + " boxes");
        }

        GL11.glPopAttrib();
        GL11.glPopMatrix();
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
     * 绘制单节点 12 条边线框（照 FindIt {@code FxHelper.renderOutline} 的 24 顶点
     * 单条 GL_LINE_STRIP，raw glBegin/glEnd 立即模式）：方块真实包围盒 + 0.002 外扩，
     * 世界坐标（摄像机平移由调用方 {@code glTranslated(-cam)} 统一完成）。
     * <p>
     * 节点方块未加载/已被破坏时 {@code getBlock} 退化为 air，绘制默认整格包围盒
     * （显形语义是"注册表索引位置"，非当前方块形态，仍应可见）。
     *
     * @return 本帧是否实际绘制（box 为 null 时 false，供 C6 探针计数）
     */
    private static boolean drawNodeBox(World world, PacketSyncNodeReveal.RevealedNode node, boolean altColor) {
        final Block block = world.getBlock(node.x, node.y, node.z);
        block.setBlockBoundsBasedOnState(world, node.x, node.y, node.z);
        AxisAlignedBB box = block.getSelectedBoundingBoxFromPool(world, node.x, node.y, node.z);
        if (box == null) {
            return false;
        }
        // 微扩张防 Z-fighting（线框与方块表面重叠导致的闪烁）
        box = box.expand(BOX_EXPAND, BOX_EXPAND, BOX_EXPAND);

        // 颜色：500ms 相位交替——类型色（能源=黄 / 动力=紫，值域单一来源：WirelessNodeIndexCodec 常量）↔ 白
        if (altColor) {
            GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
        } else {
            final boolean isDynamo = node.type == WirelessNodeIndexCodec.TYPE_DYNAMO;
            GL11.glColor4f(
                (isDynamo ? DYNAMO_RED : ENERGY_RED) / 255.0f,
                (isDynamo ? DYNAMO_GREEN : ENERGY_GREEN) / 255.0f,
                (isDynamo ? DYNAMO_BLUE : ENERGY_BLUE) / 255.0f,
                1.0f);
        }

        GL11.glBegin(GL11.GL_LINE_STRIP);
        GL11.glVertex3d(box.minX, box.minY, box.minZ);
        GL11.glVertex3d(box.minX, box.maxY, box.minZ);
        GL11.glVertex3d(box.minX, box.maxY, box.maxZ);
        GL11.glVertex3d(box.minX, box.minY, box.maxZ);
        GL11.glVertex3d(box.minX, box.minY, box.minZ);

        GL11.glVertex3d(box.maxX, box.minY, box.minZ);
        GL11.glVertex3d(box.maxX, box.maxY, box.minZ);
        GL11.glVertex3d(box.maxX, box.maxY, box.maxZ);
        GL11.glVertex3d(box.maxX, box.minY, box.maxZ);
        GL11.glVertex3d(box.maxX, box.minY, box.minZ);

        GL11.glVertex3d(box.minX, box.minY, box.minZ);
        GL11.glVertex3d(box.maxX, box.minY, box.minZ);
        GL11.glVertex3d(box.maxX, box.minY, box.maxZ);
        GL11.glVertex3d(box.minX, box.minY, box.maxZ);
        GL11.glVertex3d(box.minX, box.maxY, box.maxZ);
        GL11.glVertex3d(box.maxX, box.maxY, box.maxZ);
        GL11.glVertex3d(box.maxX, box.maxY, box.minZ);
        GL11.glVertex3d(box.maxX, box.minY, box.minZ);

        GL11.glVertex3d(box.minX, box.minY, box.minZ);
        GL11.glVertex3d(box.maxX, box.minY, box.minZ);
        GL11.glVertex3d(box.maxX, box.maxY, box.minZ);
        GL11.glVertex3d(box.minX, box.maxY, box.minZ);
        GL11.glVertex3d(box.minX, box.maxY, box.maxZ);
        GL11.glVertex3d(box.maxX, box.maxY, box.maxZ);
        GL11.glVertex3d(box.maxX, box.minY, box.maxZ);
        GL11.glVertex3d(box.minX, box.minY, box.maxZ);
        GL11.glEnd();
        return true;
    }

    /** 清空显形缓存（过期 / 世界卸载共用） */
    private static void clearCache() {
        NODES.clear();
        dimension = 0;
        expireAt = 0L;
        // [GTSWN-REVEAL-PROBE] 缓存清空后重置 C6 首帧打点标记
        drewLogged = false;
    }

    /** @return 显形所在维度 */
    public static int getDimension() {
        return dimension;
    }

    /** @return 当前显形节点集（只读视图；可能为空） */
    public static List<PacketSyncNodeReveal.RevealedNode> getNodes() {
        return Collections.unmodifiableList(NODES);
    }

    /** @return 过期锚点（客户端墙钟 ms，System.currentTimeMillis 基准），超过即显形结束 */
    public static long getExpireAt() {
        return expireAt;
    }
}
