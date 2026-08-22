package com.miaokatze.gtswn.network;

import java.math.BigInteger;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;
import com.miaokatze.gtswn.common.util.PlayerRequestQueue;

import baubles.api.BaublesApi;
import gregtech.common.misc.WirelessNetworkManager;

/**
 * 无线 EU 查询请求的待处理队列（v1.6.19）。
 * <p>
 * {@code WirelessNetworkManager} 是服务端主线程维护的 HashMap，Netty 网络线程直读存在并发风险。
 * {@link PacketRequestWirelessEU.Handler} 仅入队 (player, ownerUUID)，由
 * {@code QuantumControllerEventHandler} 的 ServerTickEvent（END phase）在主线程逐条 drain：
 * 主线程查询 EU 并回发 {@link PacketResponseWirelessEU}；玩家掉线/格式异常时静默丢弃，
 * 客户端下轮轮询重试。与 {@code QuantumTerminalRequestQueue} 同一「Netty 入队 → 主线程 drain」模式。
 * <p>
 * O2-16：队列骨架（去重门 + 排空）由 {@link PlayerRequestQueue} 基类承载；
 * 本子类保留 payload=(player, ownerUUID)、B2-03 持有校验与「掉线/格式异常静默丢弃」策略
 * （与终端侧离线快照兜底契约不同，故异常处理不进基类）。
 */
public final class WirelessEURequestQueue extends PlayerRequestQueue<WirelessEURequestQueue.Request> {

    private static final WirelessEURequestQueue INSTANCE = new WirelessEURequestQueue();

    private WirelessEURequestQueue() {}

    /** Netty 线程入队（仅缓存玩家引用与拥有者 UUID，主线程 drain 时再查询回包） */
    public static void enqueue(EntityPlayerMP player, String ownerUUID) {
        if (ownerUUID != null) {
            INSTANCE.offer(player, new Request(player, ownerUUID));
        }
    }

    /** 主线程逐条处理；本 tick 内排空当前快照 */
    public static void drain() {
        INSTANCE.drainAll();
    }

    @Override
    protected EntityPlayerMP playerOf(Request request) {
        return request.player;
    }

    /** 主线程处理单条：校验在线与持有（B2-03）后查询回包；掉线/格式异常静默丢弃 */
    @Override
    protected void process(Request request) {
        try {
            if (request.player.playerNetServerHandler == null) {
                return;
            }
            // B2-03 持有校验：仅当请求玩家实际持有绑定到该 UUID 的便携监测终端时才查询回发，
            // 封死「仅凭枚举他人 UUID 即可读取其无线电网余额」的信息泄露面；
            // 不持有（含终端已转移/丢弃）时静默丢弃，客户端下轮轮询重试
            if (!holdsMonitorFor(request.player, request.ownerUUID)) {
                return;
            }
            BigInteger eu = WirelessNetworkManager.getUserEU(UUID.fromString(request.ownerUUID));
            GTSWNPacketHandler.NETWORK.sendTo(new PacketResponseWirelessEU(eu.toString()), request.player);
        } catch (Throwable t) {
            // 玩家掉线/格式异常：静默丢弃，客户端下轮轮询
        }
    }

    /**
     * 持有校验（B2-03）：扫描玩家主手 → Baubles 饰品栏 → 主背包（0-35），槽位口径与客户端
     * {@code WirelessMonitorHUD.scanMonitorInInventory} 对齐。
     * <p>
     * 语义 = 持有即授权：终端物品是能力凭证，物品可合法转移且转移后无需重绑
     * （跨 owner 显示是既定功能，故不做「UUID == 请求者本人」的身份校验）。
     *
     * @param player    请求玩家（服务端）
     * @param ownerUUID 包体携带的拥有者 UUID 字符串
     * @return 是否持有绑定到该 UUID 的 {@code PortableWirelessNetworkMonitor}
     */
    private static boolean holdsMonitorFor(EntityPlayerMP player, String ownerUUID) {
        if (ownerUUID == null || ownerUUID.isEmpty()) {
            return false;
        }
        if (matchesMonitor(player.getHeldItem(), ownerUUID)) {
            return true;
        }
        // --- 饰品栏扫描（Baubles 不存在时安全降级，与客户端 scanMonitorInInventory 同款 try/NoClassDefFoundError） ---
        try {
            IInventory baubles = BaublesApi.getBaubles(player);
            if (baubles != null) {
                for (int i = 0; i < baubles.getSizeInventory(); i++) {
                    if (matchesMonitor(baubles.getStackInSlot(i), ownerUUID)) {
                        return true;
                    }
                }
            }
        } catch (NoClassDefFoundError ignored) {
            // Baubles 未安装，跳过饰品栏扫描
        }
        // --- 主背包扫描（0-35） ---
        for (int i = 0; i < player.inventory.mainInventory.length; i++) {
            if (matchesMonitor(player.inventory.mainInventory[i], ownerUUID)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查单个槽位：是绑定到指定 UUID 的便携监测终端则命中（NBT 键名引用物品侧 public 常量）。
     */
    private static boolean matchesMonitor(ItemStack stack, String ownerUUID) {
        if (stack == null || stack.stackTagCompound == null) {
            return false;
        }
        if (!(stack.getItem() instanceof PortableWirelessNetworkMonitor)) {
            return false;
        }
        return stack.stackTagCompound.hasKey(PortableWirelessNetworkMonitor.NBT_OWNER_UUID)
            && ownerUUID.equals(stack.stackTagCompound.getString(PortableWirelessNetworkMonitor.NBT_OWNER_UUID));
    }

    static final class Request {

        private final EntityPlayerMP player;
        private final String ownerUUID;

        private Request(EntityPlayerMP player, String ownerUUID) {
            this.player = player;
            this.ownerUUID = ownerUUID;
        }
    }
}
