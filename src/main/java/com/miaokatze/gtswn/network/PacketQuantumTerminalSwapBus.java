package com.miaokatze.gtswn.network;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;
import com.miaokatze.gtswn.register.BlockRegistrar;

import appeng.api.parts.IPart;
import appeng.api.parts.PartItemStack;
import appeng.api.util.DimensionalCoord;
import appeng.tile.networking.TileCableBus;
import appeng.util.Platform;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端→服务端 请求包：量子终端「裸部件 cable-bus 原位替换为量子节点」（discriminator = 13，v1.8.5）。
 * <p>
 * 字段：x/y/z（目标裸 bus 方块坐标）+ side（{@code block.collisionRayTrace} 真实命中面，
 * 客户端 {@code QuantumTerminalBusSwapHandler} 以 HIGHEST 优先级先于 AE2 PartPlacement 拦截并取消
 * 原版交互后发送本包；AE2 对部件的原生交互/放置因事件被取消而完全不触发）。
 * <p>
 * 线程模式与包 5/8/10/11 一致：Handler 运行在 Netty 网络线程，不得触世界；此处仅入队
 * {@link PacketQuantumTerminalSwapBus.SwapJob}，由本类内聚的 {@code ServerTickEvent(END)} 排水器
 * 在主线程执行全部校验与迁移（注册入口 {@link #register()} 由 {@code GTSWNPacketHandler.register()}
 * 双端调用；客户端永不收到本包，队列恒空零开销）。
 * <p>
 * 服务端迁移语义（镜像 {@code ItemNetworkQuantumTerminal.onItemUseFirst} 绑定校验 + 手势 4 锚点写入）：
 * <ol>
 * <li>复验：手持已绑定量子终端、目标仍为无中心线缆（getPart(UNKNOWN)==null）的 {@link TileCableBus}；</li>
 * <li>收集全部面部件 {@code part.getItemStack(PartItemStack.Wrench).copy()} + {@code part.getDrops(…,true)}
 * （AE2 wrench 双路语义：部件设置随物品 NBT 保留，升级/透视/配置卡单独弹出，重挂时经
 * IPartItem 的 ItemStack 构造器回读）；</li>
 * <li>逐面 {@code host.removePart(side,false)}（不掉落；末件移除后容器已空 → 显式 {@code cleanup()}
 * 置空气——DIFF：AE2 是在 wrenchLogic 中显式调 cleanup，removePart 本身不自动触发）；</li>
 * <li>{@code setBlock(量子节点, 0, 3)} + 锚点/owner 写入（复用终端抽取的
 * {@link ItemNetworkQuantumTerminal#applyAnchorToNode}，与手势 4 逐字段一致）；</li>
 * <li>按原面 {@code node.addPart(stack, side, player)} 重挂（proxy 未就绪由每 tick
 * rewirePartConnections 自动补连）；</li>
 * <li>任何一步失败：已移除/未挂上的部件按 wrench 掉落物弹回原地，绝不隐形丢件，全程中文状态提示。</li>
 * </ol>
 */
public class PacketQuantumTerminalSwapBus implements IMessage {

    /** 客户端拦截半径守卫：与玩家距离 &gt; 8 格的坐标视为伪造包静默丢弃 */
    private static final double MAX_INTERACT_DISTANCE_SQ = 64.0D;

    /** Forge 反射无参构造（反序列化时必需） */
    public PacketQuantumTerminalSwapBus() {}

    // ==================== 包字段 ====================

    private int x;
    private int y;
    private int z;
    private int side;

    public PacketQuantumTerminalSwapBus(int x, int y, int z, int side) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.side = side;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.x = buf.readInt();
        this.y = buf.readInt();
        this.z = buf.readInt();
        this.side = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.x);
        buf.writeInt(this.y);
        buf.writeInt(this.z);
        buf.writeInt(this.side);
    }

    // ==================== 主线程排水（队列 + ServerTickEvent END） ====================

    /** 待处理替换作业（Netty 线程入队，主线程 drain 消费） */
    private static final class SwapJob {

        final EntityPlayerMP player;
        final int x;
        final int y;
        final int z;
        final int side;

        SwapJob(EntityPlayerMP player, int x, int y, int z, int side) {
            this.player = player;
            this.x = x;
            this.y = y;
            this.z = z;
            this.side = side;
        }
    }

    /** 待处理作业队列（Netty 线程生产 / 主线程消费，仅服务端非空） */
    private static final ConcurrentLinkedQueue<SwapJob> QUEUE = new ConcurrentLinkedQueue<>();

    /** 包 13 注册时同步挂载本排水器（双端安全：客户端队列恒空） */
    public static void register() {
        FMLCommonHandler.instance()
            .bus()
            .register(new DrainListener());
    }

    /** ServerTickEvent(END) 主线程排水器（包 8/10/11 同款线程切换模式） */
    public static class DrainListener {

        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            SwapJob job;
            while ((job = QUEUE.poll()) != null) {
                try {
                    handleSwap(job);
                } catch (Throwable t) {
                    GTSimpleWirelessNetwork.LOG.error("[量子终端替换] 裸 bus 原位替换异常（作业丢弃）", t);
                }
            }
        }
    }

    /** Netty 线程 Handler：仅入队，不触世界 */
    public static class Handler implements IMessageHandler<PacketQuantumTerminalSwapBus, IMessage> {

        @Override
        public IMessage onMessage(PacketQuantumTerminalSwapBus msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player != null) {
                QUEUE.add(new SwapJob(player, msg.x, msg.y, msg.z, msg.side));
            }
            return null;
        }
    }

    // ==================== 服务端迁移主体（主线程） ====================

    /**
     * 执行原位替换。任一环节失败即回滚：已移除部件按 wrench 掉落物弹回原地并提示，
     * 绝不留隐形丢件；目标格被第三方改变的竞态（发包后 setBlock 前）一律静默放弃。
     */
    private static void handleSwap(SwapJob job) {
        EntityPlayerMP player = job.player;
        World world = player.worldObj;
        int x = job.x;
        int y = job.y;
        int z = job.z;
        // 距离守卫：防伪造包隔空拆机器（客户端真实命中 ≤ 手长 + 部件外扩，8 格上限极宽裕）
        if (player.getDistanceSq(x + 0.5D, y + 0.5D, z + 0.5D) > MAX_INTERACT_DISTANCE_SQ) {
            return;
        }
        // 手持复验（镜像 ItemNetworkQuantumTerminal.onItemUseFirst：仅服务端权威、非潜行、已绑定）
        ItemStack held = player.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemNetworkQuantumTerminal)) {
            return;
        }
        if (player.isSneaking()) {
            return;
        }
        if (!ItemNetworkQuantumTerminal.isBound(held)) {
            // 事件已在客户端取消，无法「放行」，改发绑定提示（任务 B2 未绑定→false/提示 的提示分支）
            ItemNetworkQuantumTerminal.sendMessage(player, "gtswn.chat.quantum.need_bind");
            return;
        }
        // 目标复验：仍为无中心线缆的裸 TileCableBus（cast 源表达式保持 TileEntity 静态类型，
        // 避免 javac 解析 AEBaseTile 上不在编译 classpath 的可选接口）
        TileEntity te = world.getTileEntity(x, y, z);
        if (!(te instanceof TileCableBus)) {
            return;
        }
        TileCableBus bus = (TileCableBus) (TileEntity) te;
        if (bus.getPart(ForgeDirection.UNKNOWN) != null) {
            // 有中心线缆 = AE2 原生域（客户端拦截条件的服务端镜像复验）
            return;
        }
        if (!Platform.hasPermissions(new DimensionalCoord(world, x, y, z), player)) {
            ItemNetworkQuantumTerminal.sendMessage(player, "gtswn.chat.quantum.no_permission");
            return;
        }
        // a. 收集全部面部件迁移 ItemStack（wrench 语义，copy 脱钩原部件内部引用）；
        // 镜像 PartPlacement.wrenchLogic 双路：升级/透视/配置卡由 part.getDrops 单独给出，
        // 不收集即静默销毁（AEBasePart.getItemStack 仅含部件自身）
        Map<ForgeDirection, ItemStack> migration = new LinkedHashMap<>();
        List<ItemStack> extraDrops = new ArrayList<>();
        for (ForgeDirection d : ForgeDirection.VALID_DIRECTIONS) {
            IPart part = bus.getPart(d);
            if (part != null) {
                ItemStack is = part.getItemStack(PartItemStack.Wrench);
                if (is != null) {
                    migration.put(d, is.copy());
                }
                part.getDrops(extraDrops, true);
            }
        }
        if (migration.isEmpty()) {
            return;
        }
        // b. 逐面移除（removePart(false) 不掉落；末件后容器空 → 显式 cleanup 置空气 + setBlockToAir 兜底）
        List<ItemStack> removed = new ArrayList<>();
        try {
            for (ForgeDirection d : migration.keySet()) {
                bus.removePart(d, false);
                removed.add(migration.get(d));
            }
        } catch (Throwable t) {
            GTSimpleWirelessNetwork.LOG.error("[量子终端替换] 部件移除失败，已移除件弹回原地", t);
            Platform.spawnDrops(world, x, y, z, removed);
            Platform.spawnDrops(world, x, y, z, extraDrops);
            ItemNetworkQuantumTerminal.sendMessage(player, "gtswn.chat.quantum.swap_failed");
            return;
        }
        if (!bus.isEmpty()) {
            // 个别部件无 ItemStack（理论不可达）：拒绝换体，防 setBlock 吞掉残部
            Platform.spawnDrops(world, x, y, z, removed);
            Platform.spawnDrops(world, x, y, z, extraDrops);
            ItemNetworkQuantumTerminal.sendMessage(player, "gtswn.chat.quantum.swap_failed");
            return;
        }
        if (!world.isRemote) {
            // 镜像 AE2 PartPlacement.wrenchLogic 的末件清理（TileCableBus.cleanup → setBlock(AIR)）
            bus.cleanup();
        }
        if (!world.isAirBlock(x, y, z)) {
            world.setBlockToAir(x, y, z);
        }
        // c. 原位放置量子节点（flag 3 = 通知客户端 + 邻接更新，镜像手势 4）
        Block nodeBlock = BlockRegistrar.networkQuantumNode;
        if (!world.setBlock(x, y, z, nodeBlock, 0, 3)) {
            Platform.spawnDrops(world, x, y, z, removed);
            Platform.spawnDrops(world, x, y, z, extraDrops);
            ItemNetworkQuantumTerminal.sendMessage(player, "gtswn.chat.quantum.swap_failed");
            return;
        }
        TileEntity placedTe = world.getTileEntity(x, y, z);
        if (!(placedTe instanceof TileEntityNetworkQuantumNode)) {
            Platform.spawnDrops(world, x, y, z, removed);
            Platform.spawnDrops(world, x, y, z, extraDrops);
            ItemNetworkQuantumTerminal.sendMessage(player, "gtswn.chat.quantum.swap_failed");
            return;
        }
        // d. 锚点 + owner 写入（复用终端抽取辅助，与手势 4 placeQuantumNode 逐字段一致）
        TileEntityNetworkQuantumNode node = (TileEntityNetworkQuantumNode) placedTe;
        ItemNetworkQuantumTerminal.applyAnchorToNode(held, node, player);
        // e. 按原面重挂部件（proxy 未就绪时内部建连跳过，rewirePartConnections 每 tick 补连）
        int remounted = 0;
        List<ItemStack> remountFailed = new ArrayList<>();
        for (Map.Entry<ForgeDirection, ItemStack> e : migration.entrySet()) {
            boolean ok = false;
            try {
                ok = node.addPart(e.getValue(), e.getKey(), player) != null;
            } catch (Throwable t) {
                GTSimpleWirelessNetwork.LOG.error("[量子终端替换] 部件重挂异常 side={}", e.getKey(), t);
            }
            if (ok) {
                remounted++;
            } else {
                remountFailed.add(e.getValue());
            }
        }
        // f. 失败件弹回原地 + 状态提示（沿用 gtswn.chat.* 风格）；
        // 升级/透视/配置卡无论重挂成败一律按 wrench 语义弹出（与 AE2 扳手拆件一致）
        if (!remountFailed.isEmpty()) {
            Platform.spawnDrops(world, x, y, z, remountFailed);
        }
        if (!extraDrops.isEmpty()) {
            Platform.spawnDrops(world, x, y, z, extraDrops);
        }
        // 与手势 4 一致的放置音效
        world.playSoundEffect(
            x + 0.5D,
            y + 0.5D,
            z + 0.5D,
            nodeBlock.stepSound.func_150496_b(),
            (nodeBlock.stepSound.getVolume() + 1.0F) / 2.0F,
            nodeBlock.stepSound.getPitch() * 0.8F);
        if (remountFailed.isEmpty()) {
            ItemNetworkQuantumTerminal.sendMessage(player, "gtswn.chat.quantum.swap_done", remounted);
        } else {
            ItemNetworkQuantumTerminal
                .sendMessage(player, "gtswn.chat.quantum.swap_partial", remounted, remountFailed.size());
        }
    }
}
