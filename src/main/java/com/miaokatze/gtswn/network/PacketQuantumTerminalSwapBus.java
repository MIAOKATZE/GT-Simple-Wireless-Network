package com.miaokatze.gtswn.network;

import java.util.concurrent.ArrayBlockingQueue;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumBusReplacement;
import com.miaokatze.gtswn.common.quantum.QuantumBusTarget;
import com.miaokatze.gtswn.common.quantum.QuantumIncorporationRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumTerminalAccess;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.common.util.AnimationQualityHints;
import com.miaokatze.gtswn.common.util.PlayerRequestQueue;
import com.miaokatze.gtswn.common.util.QualityHintCounter;
import com.miaokatze.gtswn.common.util.QuantumTerminalSecurityHints;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;
import com.miaokatze.gtswn.register.BlockRegistrar;

import appeng.api.AEApi;
import appeng.api.util.DimensionalCoord;
import appeng.tile.networking.TileCableBus;
import appeng.util.Platform;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端→服务端 请求包：量子终端「裸部件 cable-bus 原位替换为量子节点」（discriminator = 13，v1.8.5）。
 * <p>
 * 字段：x/y/z（原始点击坐标）+ side（有效点击面）+ removeCore（显式移除意图）。
 * 客户端 {@code QuantumTerminalBusSwapHandler} 以 HIGHEST 优先级先于 AE2 PartPlacement 拦截并取消
 * 原版交互后发送本包；AE2 对部件的原生交互/放置因事件被取消而完全不触发。
 * <p>
 * 线程模式与包 5/8/10/11 一致：Handler 运行在 Netty 网络线程，不得触世界；此处仅入队
 * {@link PacketQuantumTerminalSwapBus.SwapJob}，由本类内聚的 {@code ServerTickEvent(END)} 排水器
 * 在主线程执行全部校验与迁移（注册入口 {@link #register()} 由 {@code GTSWNPacketHandler.register()}
 * 双端调用；客户端永不收到本包，队列恒空零开销）。
 * <p>
 * 服务端迁移语义（镜像 {@code ItemNetworkQuantumTerminal.onItemUseFirst} 绑定校验 + 手势 4 锚点写入）：
 * <ol>
 * <li>复验：手持已绑定量子终端、目标仍为无中心线缆（getPart(UNKNOWN)==null）的 {@link TileCableBus}；</li>
 * <li>整体接管原生 CableBusContainer，保留每个 IPart、库存和 GridNode；</li>
 * <li>先隔离旧宿主的生命周期，再原位放置带锚点/owner 的量子节点；</li>
 * <li>安装失败时回挂同一个容器和原 TileCableBus，不产生拆卸掉落物。</li>
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
    private boolean removeCore;

    public PacketQuantumTerminalSwapBus(int x, int y, int z, int side) {
        this(x, y, z, side, false);
    }

    public PacketQuantumTerminalSwapBus(int x, int y, int z, int side, boolean removeCore) {
        this.removeCore = removeCore;
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
        this.removeCore = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.x);
        buf.writeInt(this.y);
        buf.writeInt(this.z);
        buf.writeInt(this.side);
        buf.writeBoolean(this.removeCore);
    }

    // ==================== 主线程排水（队列 + ServerTickEvent END） ====================

    /** 待处理替换作业（Netty 线程入队，主线程 drain 消费） */
    private static final class SwapJob {

        final EntityPlayerMP player;
        final int dimension;
        final int x;
        final int y;
        final int z;
        final int side;
        final boolean removeCore;

        SwapJob(EntityPlayerMP player, int x, int y, int z, int side, boolean removeCore) {
            this.removeCore = removeCore;
            this.player = player;
            this.dimension = player.dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.side = side;
        }
    }

    /** 待处理作业队列（Netty 线程生产 / 主线程消费，仅服务端非空） */
    private static final int MAX_PENDING = 256, TICK_BUDGET = 8;
    private static final ArrayBlockingQueue<SwapJob> QUEUE = new ArrayBlockingQueue<>(MAX_PENDING);

    public static void clear() {
        QUEUE.clear();
    }

    /** 包 13 注册时同步挂载本排水器（双端安全：客户端队列恒空） */
    public static void register() {
        FMLCommonHandler.instance()
            .bus()
            .register(new DrainListener());
    }

    /** ServerTickEvent(END) 主线程排水器（包 8/10/11 同款线程切换模式） */
    public static class DrainListener {

        @SubscribeEvent
        public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
            QUEUE.removeIf(job -> job.player == event.player);
        }

        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            for (int processed = 0; processed < TICK_BUDGET; processed++) {
                SwapJob job = QUEUE.poll();
                if (job == null) break;
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
                // Full queue rejects the new intent; never coalesce or overwrite ordered world mutations.
                QUEUE.offer(new SwapJob(player, msg.x, msg.y, msg.z, msg.side, msg.removeCore));
            }
            return null;
        }
    }

    // ==================== 服务端迁移主体（主线程） ====================

    /**
     * 执行原位替换。安装失败即恢复原方块、宿主及活容器；成功后才通知邻居。
     */
    private static void handleSwap(SwapJob job) {
        EntityPlayerMP player = job.player;
        if (!PlayerRequestQueue.isOnline(player) || player.dimension != job.dimension) return;
        handleInteraction(player, job.x, job.y, job.z, job.side, job.removeCore);
    }

    /** Main-thread entry shared by the packet and the ordinary item/block interaction path. */
    public static boolean handleInteraction(EntityPlayer player, int clickX, int clickY, int clickZ, int side) {
        return handleInteraction(player, clickX, clickY, clickZ, side, player.isSneaking());
    }

    private static boolean handleInteraction(EntityPlayer player, int clickX, int clickY, int clickZ, int side,
        boolean removeCore) {
        World world = player.worldObj;
        if (removeCore != player.isSneaking()) return false;
        if (world.isRemote || side < 0 || side >= 6) return false;
        int x = clickX;
        int y = clickY;
        int z = clickZ;
        // 距离守卫：防伪造包隔空拆机器（客户端真实命中 ≤ 手长 + 部件外扩，8 格上限极宽裕）
        if (player.getDistanceSq(x + 0.5D, y + 0.5D, z + 0.5D) > MAX_INTERACT_DISTANCE_SQ) {
            return false;
        }
        // 手持复验（镜像 ItemNetworkQuantumTerminal.onItemUseFirst：仅服务端权威、非潜行、已绑定）
        ItemStack held = player.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemNetworkQuantumTerminal)) {
            return false;
        }
        if (!QuantumTerminalAccess.validate(player, held)) return true;
        if (QuantumIncorporationRegistry.isIncorporated(world, x, y, z)) return false;
        TileEntity clickedTile = world.getTileEntity(x, y, z);
        if (removeCore) {
            if (clickedTile instanceof TileEntityNetworkQuantumNode) {
                TileEntityNetworkQuantumNode node = (TileEntityNetworkQuantumNode) clickedTile;
                node.applyPendingMigrations(com.miaokatze.gtswn.common.quantum.QuantumMigrationJournal.get(world));
                if (node.hasAnchor() && !QuantumTerminalAccess.canAccessAnchor(
                    player,
                    held,
                    net.minecraftforge.common.DimensionManager.getWorld(node.getAnchorDim()),
                    node.getAnchorX(),
                    node.getAnchorY(),
                    node.getAnchorZ())) {
                    ItemNetworkQuantumTerminal.sendMessage(player, "gtswn.chat.quantum.no_permission");
                    return true;
                }
                removeCore(player, (TileEntityNetworkQuantumNode) clickedTile);
                return true;
            }
            return false;
        }
        if (!ItemNetworkQuantumTerminal.isBound(held)) return false;
        QuantumBusTarget target = QuantumBusTarget.resolve(world, x, y, z, side);
        if (target == null) return false;
        if (QuantumIncorporationRegistry.isIncorporated(world, target.x, target.y, target.z)) return true;
        x = target.x;
        y = target.y;
        z = target.z;
        if (player.getDistanceSq(x + 0.5D, y + 0.5D, z + 0.5D) > MAX_INTERACT_DISTANCE_SQ) return true;
        return replaceBus(player, held, world, target.bus, x, y, z);
    }

    private static boolean replaceBus(EntityPlayer player, ItemStack held, World world, TileCableBus bus, int x, int y,
        int z) {
        if (!Platform.hasPermissions(new DimensionalCoord(world, x, y, z), player)) {
            ItemNetworkQuantumTerminal.sendMessage(player, "gtswn.chat.quantum.no_permission");
            return true;
        }
        int partCount = 0;
        for (ForgeDirection d : ForgeDirection.VALID_DIRECTIONS) {
            if (bus.getPart(d) != null) {
                partCount++;
            }
        }
        if (partCount == 0) {
            return false;
        }
        boolean wasOpen = QuantumTerminalSecurityHints.isOpen(held);
        Block originalBlock = world.getBlock(x, y, z);
        int originalMetadata = world.getBlockMetadata(x, y, z);
        Block nodeBlock = BlockRegistrar.networkQuantumNode;
        TileEntityNetworkQuantumNode node = new TileEntityNetworkQuantumNode();
        node.setWorldObj(world);
        node.xCoord = x;
        node.yCoord = y;
        node.zCoord = z;
        ItemNetworkQuantumTerminal.applyAnchorToNode(held, node, player);
        boolean replaced;
        try {
            replaced = QuantumBusReplacement.replace(bus, node, new QuantumBusReplacement.Placement() {

                @Override
                public boolean install() {
                    // 不先通知邻居：旧 bus 已隔离，默认新节点为空，不会在换 TE 时销毁原部件。
                    if (!world.setBlock(x, y, z, nodeBlock, 0, 2)
                        || !(world.getTileEntity(x, y, z) instanceof TileEntityNetworkQuantumNode)) {
                        return false;
                    }
                    world.setTileEntity(x, y, z, node);
                    return world.getBlock(x, y, z) == nodeBlock && world.getTileEntity(x, y, z) == node;
                }

                @Override
                public void restore() {
                    if (world.getBlock(x, y, z) != originalBlock
                        || world.getBlockMetadata(x, y, z) != originalMetadata) {
                        if (!world.setBlock(x, y, z, originalBlock, originalMetadata, 2)) {
                            throw new IllegalStateException("Cannot restore original cable bus block");
                        }
                    }
                    // World.setTileEntity rejects invalid tiles; setBlock invalidated the original host.
                    bus.validate();
                    world.setTileEntity(x, y, z, bus);
                    if (world.getTileEntity(x, y, z) != bus) {
                        throw new IllegalStateException("Cannot restore original cable bus tile");
                    }
                    bus.markForSave();
                    bus.markForUpdate();
                    bus.notifyNeighbors();
                }
            });
        } catch (Throwable t) {
            GTSimpleWirelessNetwork.LOG.error("[量子终端替换] 整容器迁移失败，尝试恢复原宿主", t);
            replaced = false;
        }
        if (!replaced) {
            ItemNetworkQuantumTerminal.sendMessage(player, "gtswn.chat.quantum.swap_failed");
            return true;
        }
        node.markForSave();
        node.markForUpdate();
        node.notifyNeighbors();
        // 与手势 4 一致的放置音效
        world.playSoundEffect(
            x + 0.5D,
            y + 0.5D,
            z + 0.5D,
            nodeBlock.stepSound.func_150496_b(),
            (nodeBlock.stepSound.getVolume() + 1.0F) / 2.0F,
            nodeBlock.stepSound.getPitch() * 0.8F);
        AnimationQualityHints.show(player, QualityHintCounter.Kind.NODE);
        ItemNetworkQuantumTerminal.sendMessage(player, "gtswn.chat.quantum.swap_done", partCount);
        QuantumTerminalSecurityHints.show(player, wasOpen);
        return true;
    }

    private static void removeCore(EntityPlayer player, TileEntityNetworkQuantumNode node) {
        World world = player.worldObj;
        int x = node.xCoord;
        int y = node.yCoord;
        int z = node.zCoord;
        if (!Platform.hasPermissions(new DimensionalCoord(world, x, y, z), player)) {
            ItemNetworkQuantumTerminal.sendMessage(player, "gtswn.chat.quantum.no_permission");
            return;
        }
        boolean removed = false;
        boolean wasOpen = node.hasAnchor() && QuantumTerminalSecurityHints.isOpen(
            net.minecraftforge.common.DimensionManager.getWorld(node.getAnchorDim()),
            node.getAnchorX(),
            node.getAnchorY(),
            node.getAnchorZ());
        try {
            if (!node.hasParts()) {
                removed = world.setBlockToAir(x, y, z);
            } else {
                Block nativeBlock = AEApi.instance()
                    .definitions()
                    .blocks()
                    .multiPart()
                    .maybeBlock()
                    .get();
                // AE2's factory supplies the combined tile class, including its integration layers.
                TileEntity nativeTile = nativeBlock.createTileEntity(world, 0);
                if (!(nativeTile instanceof TileCableBus)) {
                    throw new IllegalStateException("AE2 multipart factory did not create a cable bus");
                }
                TileCableBus bus = (TileCableBus) nativeTile;
                bus.setWorldObj(world);
                bus.xCoord = x;
                bus.yCoord = y;
                bus.zCoord = z;
                Block originalBlock = world.getBlock(x, y, z);
                int originalMetadata = world.getBlockMetadata(x, y, z);
                removed = QuantumBusReplacement.removeCore(node, bus, new QuantumBusReplacement.Placement() {

                    @Override
                    public boolean install() {
                        if (!world.setBlock(x, y, z, nativeBlock, 0, 2)
                            || !(world.getTileEntity(x, y, z) instanceof TileCableBus)) return false;
                        world.setTileEntity(x, y, z, bus);
                        return world.getBlock(x, y, z) == nativeBlock && world.getTileEntity(x, y, z) == bus;
                    }

                    @Override
                    public void restore() {
                        if (world.getBlock(x, y, z) != originalBlock
                            || world.getBlockMetadata(x, y, z) != originalMetadata) {
                            if (!world.setBlock(x, y, z, originalBlock, originalMetadata, 2)) {
                                throw new IllegalStateException("Cannot restore original quantum node block");
                            }
                        }
                        node.validate();
                        world.setTileEntity(x, y, z, node);
                        if (world.getTileEntity(x, y, z) != node) {
                            throw new IllegalStateException("Cannot restore original quantum node tile");
                        }
                        node.markForSave();
                        node.markForUpdate();
                        node.notifyNeighbors();
                    }
                });
                if (removed) {
                    bus.markForSave();
                    bus.markForUpdate();
                    bus.notifyNeighbors();
                }
            }
        } catch (Throwable t) {
            GTSimpleWirelessNetwork.LOG.error("[量子终端移除] 核心移除失败，尝试恢复原宿主", t);
        }
        if (!removed) {
            ItemNetworkQuantumTerminal.sendMessage(player, "gtswn.chat.quantum.swap_failed");
            return;
        }
        world.playSoundEffect(
            x + 0.5D,
            y + 0.5D,
            z + 0.5D,
            "random.pop",
            0.2F,
            ((world.rand.nextFloat() - world.rand.nextFloat()) * 0.7F + 1.0F) * 2.0F);
        ItemNetworkQuantumTerminal.sendMessage(player, "gtswn.chat.quantum.node_destroyed");
        QuantumTerminalSecurityHints.show(player, wasOpen);
    }
}
