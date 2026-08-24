package com.miaokatze.gtswn.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.device.DeviceTerminalDataStore;
import com.miaokatze.gtswn.common.device.DeviceTerminalRequestQueue;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.util.GTUtility;

/**
 * 设备信息终端动作待处理队列（阶段 D3，照 {@link PanelActionQueue} 的
 * 「Netty 入队 → 主线程 drain」模式）：包 10 的世界态 / NBT 修改全部归位服务端主线程。
 * <p>
 * 设计取舍（与 PanelActionQueue 一致）：
 * <ul>
 * <li><b>无同玩家去重门</b>：GUI 动作是异质变更序列（连点排序/解绑），去重会丢操作；
 * {@link ConcurrentLinkedQueue} FIFO 保到达序</li>
 * <li><b>自宿主 drain</b>：本类自带 @SubscribeEvent ServerTickEvent(END) 监听，
 * CommonProxy.preInit 与 {@code PanelActionQueue.register()} 同址调用一次</li>
 * <li><b>主线程复验</b>：逐条复验在线（playerNetServerHandler 非空）与终端持有后才执行</li>
 * </ul>
 * 动作语义：
 * <ul>
 * <li>0 排序 / 1 计数法：写回发送者物品栏终端物品的 UI 偏好 NBT（服务端权威，聊天静默）</li>
 * <li>2 解绑：{@link DeviceTerminalDataStore#removeBinding}（version++ 内部保证，未绑定静默）</li>
 * <li>3 传送：per-player 3s 冷却 → experienceLevel≥{@link Config#deviceTeleportXPCost} 复查
 * （不足聊天提示）→ addExperienceLevel(-cost) → 落点=目标机器正前方 1 格（TE null/非 GT 回退
 * 正上方，见 {@code computeLanding}）→ 同维 setPlayerLocation（先下坐骑）/ 跨维
 * {@link GTUtility#moveEntityToDimensionAtCoords}（目标维度 world 先解析朝向）</li>
 * </ul>
 * 任何动作应用后 {@link DeviceTerminalRequestQueue#invalidate} 失效该玩家已收版本，
 * 下轮请求排水立即回发快照（闭环）。
 */
public final class DeviceTerminalActionQueue {

    /** 待处理动作队列（仅缓存载荷，主线程 drain 时再复验在线/持终端） */
    private static final ConcurrentLinkedQueue<Action> PENDING = new ConcurrentLinkedQueue<>();

    /** 传送冷却：玩家 UUID → 上次传送 tick（仅主线程访问） */
    private static final Map<UUID, Long> TELEPORT_COOLDOWN = new HashMap<>();

    /** 传送冷却（tick）：3 秒 */
    private static final long TELEPORT_COOLDOWN_TICKS = 60L;

    private DeviceTerminalActionQueue() {}

    /**
     * 注册自宿主 tick 监听（CommonProxy.preInit 与 {@code PanelActionQueue.register()} 同址调用一次）。
     */
    public static void register() {
        FMLCommonHandler.instance()
            .bus()
            .register(new DeviceTerminalActionQueue());
    }

    /** Netty 线程入队：包 10 动作载荷（fromBytes 后线程封闭，可直接引用传递） */
    public static void enqueue(EntityPlayerMP player, byte action, byte column, byte direction, byte countMode,
        String key) {
        PENDING.add(new Action(player, action, column, direction, countMode, key));
    }

    /** ServerTickEvent END phase 自宿主排空（主线程语义） */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        drain();
    }

    /** 主线程逐条处理：复验在线 + 终端持有后应用；单条异常仅记日志丢弃 */
    private static void drain() {
        Action action;
        while ((action = PENDING.poll()) != null) {
            EntityPlayerMP player = action.player;
            if (player == null || player.playerNetServerHandler == null) {
                continue;
            }
            try {
                apply(player, action);
            } catch (Throwable t) {
                GTSimpleWirelessNetwork.LOG.error("[DeviceTerminalActionQueue] 动作应用异常（丢弃该条，继续后续）", t);
            }
        }
    }

    /** 主线程应用单条动作 */
    private static void apply(EntityPlayerMP player, Action action) {
        ItemStack stack = findTerminalStack(player);
        if (stack == null) {
            // 发送者已不持有终端：静默丢弃
            return;
        }
        UUID terminalId = ItemDeviceInfoTerminal.getOrCreateTerminalId(stack);
        // 会话内新获得终端补登记活跃索引（否则采样调度跳过直到重登）
        DeviceTerminalDataStore.ensureActiveTerminal(player.getUniqueID(), terminalId);
        switch (action.action) {
            case PacketDeviceTerminalAction.ACTION_SORT: {
                // 排序偏好写回物品 NBT（服务端权威，聊天静默）
                String column = columnName(action.column);
                if (column != null) {
                    ItemDeviceInfoTerminal.setSortColumn(stack, column);
                }
                ItemDeviceInfoTerminal.setSortDirection(
                    stack,
                    action.direction == PacketDeviceTerminalAction.DIR_ASC ? ItemDeviceInfoTerminal.DIR_ASC
                        : ItemDeviceInfoTerminal.DIR_DESC);
                break;
            }
            case PacketDeviceTerminalAction.ACTION_COUNT_MODE: {
                String mode = countModeName(action.countMode);
                if (mode != null) {
                    ItemDeviceInfoTerminal.setCountMode(stack, mode);
                }
                break;
            }
            case PacketDeviceTerminalAction.ACTION_UNBIND: {
                // 解绑（找不到绑定静默；version++ 由 store 内部保证）
                if (PacketSyncDeviceTerminalData.KEY_PATTERN.matcher(action.key)
                    .matches()) {
                    DeviceTerminalDataStore.get(player.worldObj)
                        .removeBinding(terminalId, action.key);
                }
                break;
            }
            case PacketDeviceTerminalAction.ACTION_TELEPORT: {
                teleport(player, terminalId, action.key);
                break;
            }
            default:
                // 未知动作：忽略
                return;
        }
        if (action.action == PacketDeviceTerminalAction.ACTION_UNBIND
            || action.action == PacketDeviceTerminalAction.ACTION_TELEPORT) {
            // 仅数据 / 界面语义变化动作失效重发（排序 / 计数法客户端已本地应用且不改 TerminalData，
            // 无条件失效会在修复批次重开条件前引发同版本整包重发 NPE，且常态下也无谓全量重同步）
            DeviceTerminalRequestQueue.invalidate(player);
        }
    }

    /** 传送：冷却 → 记录存在 → 经验复查扣减 → 同/跨维落位 */
    private static void teleport(EntityPlayerMP player, UUID terminalId, String key) {
        if (!PacketSyncDeviceTerminalData.KEY_PATTERN.matcher(key)
            .matches()) {
            return;
        }
        DeviceTerminalDataStore.TerminalData data = DeviceTerminalDataStore.get(player.worldObj)
            .getTerminal(terminalId);
        if (data == null) {
            return;
        }
        DeviceTerminalDataStore.MachineRecord record = data.records.get(key);
        if (record == null) {
            // GUI 行已过期（绑定已删）：静默丢弃
            return;
        }
        // per-player 3s 冷却（静默拒绝）
        long now = currentTick();
        Long last = TELEPORT_COOLDOWN.get(player.getUniqueID());
        if (last != null && now - last < TELEPORT_COOLDOWN_TICKS) {
            return;
        }
        // 经验复查（不足聊天提示后拒绝）
        if (player.experienceLevel < Config.deviceTeleportXPCost) {
            player.addChatMessage(
                new ChatComponentText(
                    StatCollector
                        .translateToLocalFormatted("gtswn.device.chat.teleport.no_xp", Config.deviceTeleportXPCost)));
            return;
        }
        TELEPORT_COOLDOWN.put(player.getUniqueID(), now);
        player.addExperienceLevel(-Config.deviceTeleportXPCost);
        if (player.dimension == record.dim) {
            // 同维：先下坐骑再落位（落点=机器正前方，见 computeLanding）
            player.mountEntity(null);
            double[] landing = computeLanding(player.worldObj, record);
            player.playerNetServerHandler
                .setPlayerLocation(landing[0], landing[1], landing[2], player.rotationYaw, player.rotationPitch);
        } else {
            // 跨维：先用目标维度 world 解析机器朝向再落位（区块加载属可接受开销）；
            // GT5U moveEntityToDimensionAtCoords（内部处理坐骑/重生包；非跨维返回 false）
            MinecraftServer server = MinecraftServer.getServer();
            WorldServer targetWorld = server == null ? null : server.worldServerForDimension(record.dim);
            double[] landing = computeLanding(targetWorld, record);
            GTUtility.moveEntityToDimensionAtCoords(player, record.dim, landing[0], landing[1], landing[2]);
        }
    }

    /**
     * 传送落点计算（v1.7.2 传送正前方）：从目标维度读目标 TE（区块加载属可接受开销），
     * GT 机器经 {@code getFrontFacing()} 得朝向 → 落点=机器坐标+朝向前方 1 格中心、
     * feet y=机器 y（与机器同层）；TE null / 非 GT / 朝向未知（UNKNOWN）一律回退现行为
     * （机器正上方 y+1.0，防落进机器方块内）。
     */
    private static double[] computeLanding(World world, DeviceTerminalDataStore.MachineRecord record) {
        double fallbackX = record.x + 0.5D;
        double fallbackY = record.y + 1.0D;
        double fallbackZ = record.z + 0.5D;
        if (world == null) {
            return new double[] { fallbackX, fallbackY, fallbackZ };
        }
        TileEntity te = world.getTileEntity(record.x, record.y, record.z);
        if (!(te instanceof IGregTechTileEntity)) {
            return new double[] { fallbackX, fallbackY, fallbackZ };
        }
        ForgeDirection front = ((IGregTechTileEntity) te).getFrontFacing();
        if (front == null || front == ForgeDirection.UNKNOWN) {
            return new double[] { fallbackX, fallbackY, fallbackZ };
        }
        return new double[] { record.x + front.offsetX + 0.5D, record.y, record.z + front.offsetZ + 0.5D };
    }

    /** 当前服务端 tick（overworld 总 tick 基准） */
    private static long currentTick() {
        MinecraftServer server = MinecraftServer.getServer();
        World overworld = server == null ? null : server.worldServerForDimension(0);
        return overworld == null ? 0L : overworld.getTotalWorldTime();
    }

    /** 排序列 byte → 物品 NBT 列名（非法返回 null） */
    private static String columnName(byte column) {
        switch (column) {
            case PacketDeviceTerminalAction.COLUMN_NAME:
                return ItemDeviceInfoTerminal.SORT_NAME;
            case PacketDeviceTerminalAction.COLUMN_STATE:
                return ItemDeviceInfoTerminal.SORT_STATE;
            case PacketDeviceTerminalAction.COLUMN_INST:
                return ItemDeviceInfoTerminal.SORT_INST;
            case PacketDeviceTerminalAction.COLUMN_AVG:
                return ItemDeviceInfoTerminal.SORT_AVG;
            case PacketDeviceTerminalAction.COLUMN_POS:
                return ItemDeviceInfoTerminal.SORT_POS;
            default:
                return null;
        }
    }

    /** 计数法 byte → 物品 NBT 计数法名（非法返回 null） */
    private static String countModeName(byte mode) {
        switch (mode) {
            case PacketDeviceTerminalAction.MODE_NORMAL:
                return ItemDeviceInfoTerminal.NUM_NORMAL;
            case PacketDeviceTerminalAction.MODE_SCIENTIFIC:
                return ItemDeviceInfoTerminal.NUM_SCIENTIFIC;
            case PacketDeviceTerminalAction.MODE_THOUSANDS:
                return ItemDeviceInfoTerminal.NUM_THOUSANDS;
            case PacketDeviceTerminalAction.MODE_VOLTAGE:
                return ItemDeviceInfoTerminal.NUM_VOLTAGE;
            default:
                return null;
        }
    }

    /** 物品栏首台设备信息终端（手持优先，其次主背包扫描；与请求队列同款解析规则） */
    private static ItemStack findTerminalStack(EntityPlayerMP player) {
        ItemStack held = player.getHeldItem();
        if (held != null && held.getItem() instanceof ItemDeviceInfoTerminal) {
            return held;
        }
        for (ItemStack stack : player.inventory.mainInventory) {
            if (stack != null && stack.getItem() instanceof ItemDeviceInfoTerminal) {
                return stack;
            }
        }
        return null;
    }

    /** 包 10 载荷（Netty 线程捕获，主线程 drain 复验后应用） */
    private static final class Action {

        final EntityPlayerMP player;
        final byte action;
        final byte column;
        final byte direction;
        final byte countMode;
        final String key;

        Action(EntityPlayerMP player, byte action, byte column, byte direction, byte countMode, String key) {
            this.player = player;
            this.action = action;
            this.column = column;
            this.direction = direction;
            this.countMode = countMode;
            this.key = key == null ? "" : key;
        }
    }
}
