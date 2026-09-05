package com.miaokatze.gtswn.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 服务端→客户端 同步包：手持链路终端节点显形回发（discriminator = 12）。
 * <p>
 * 字段布局：{@code count}（封顶 {@value #MAX_NODES}，读写双侧截断）+ N ×
 * {x(int), y(int), z(int), type(byte：0=能源 / 1=动力，客户端按此着色)} +
 * {@code serverTotalWorldTime}（long，服务端本维世界 tick）+
 * {@code durationTicks}（int，显形时长 1200t=60s）。
 * <p>
 * <b>count=0（空列表）语义 = 客户端清缓存</b>（无可见节点 / 全被过滤时服务端也照发）。
 * <p>
 * fromBytes 全防御（照 {@link PacketSyncDeviceTerminalData} 教训）：整体 try-catch +
 * count 封顶截断 + 逐条 readableBytes 预检（单条 13 字节），坏包退化为空列表惰性消息。
 * <p>
 * 客户端 Handler 方法体只引用双端类型（hotfix v1.5.14 类加载安全模式：不得引用
 * {@code @SideOnly(Side.CLIENT)} 客户端类），经 @SidedProxy 委托 →
 * {@code ClientProxy.handleSyncNodeReveal}（后续渲染切片补齐）以
 * {@code Minecraft.func_152344_a} 切主线程调用
 * {@code WirelessNodeRevealRenderer.acceptReveal(dim, serverTotalWorldTime, durationTicks, nodes)}。
 */
public class PacketSyncNodeReveal implements IMessage {

    /** 节点条目硬上限（与查询侧 REVEAL_LIMIT 一致，防异常包体内存暴涨） */
    public static final int MAX_NODES = 256;

    /** 单条节点序列化字节数（x/y/z 各 int + type byte） */
    private static final int BYTES_PER_NODE = 13;

    /** 入选节点（服务端已按 tap 模式过滤；客户端只读） */
    private final List<RevealedNode> nodes = new ArrayList<>();

    /** 服务端本维世界 tick（契约保留字段，不参与客户端过期计算——客户端以收包墙钟 + durationTicks×50ms 为锚） */
    private long serverTotalWorldTime;

    /** 显形时长（tick，服务端权威 1200t=60s） */
    private int durationTicks;

    /** Forge 反射无参构造（反序列化时必需） */
    public PacketSyncNodeReveal() {}

    /**
     * @param nodes                入选节点（null 视为空；超 {@value #MAX_NODES} 截断）
     * @param serverTotalWorldTime 服务端本维世界 tick
     * @param durationTicks        显形时长（tick）
     */
    public PacketSyncNodeReveal(List<RevealedNode> nodes, long serverTotalWorldTime, int durationTicks) {
        if (nodes != null) {
            this.nodes.addAll(nodes);
            if (this.nodes.size() > MAX_NODES) {
                this.nodes.subList(MAX_NODES, this.nodes.size())
                    .clear();
            }
        }
        this.serverTotalWorldTime = serverTotalWorldTime;
        this.durationTicks = durationTicks;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        int count = Math.min(nodes.size(), MAX_NODES);
        buf.writeInt(count);
        for (int i = 0; i < count; i++) {
            RevealedNode node = nodes.get(i);
            buf.writeInt(node.x);
            buf.writeInt(node.y);
            buf.writeInt(node.z);
            buf.writeByte(node.type);
        }
        buf.writeLong(serverTotalWorldTime);
        buf.writeInt(durationTicks);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        try {
            int count = Math.min(buf.readInt(), MAX_NODES);
            for (int i = 0; i < count; i++) {
                // 单条最小防御：可读字节不足一条即停止（保留已解析前缀）
                if (buf.readableBytes() < BYTES_PER_NODE) {
                    break;
                }
                nodes.add(new RevealedNode(buf.readInt(), buf.readInt(), buf.readInt(), buf.readByte()));
            }
            serverTotalWorldTime = buf.readLong();
            durationTicks = buf.readInt();
        } catch (Exception e) {
            // 包体损坏：退化为空列表惰性消息（客户端语义 = 清缓存，不抛异常）
            nodes.clear();
        }
    }

    // ==================== 客户端读取 getter ====================

    /** @return 入选节点（count=0 时为空列表 = 清缓存语义；只读视图） */
    public List<RevealedNode> getNodes() {
        return Collections.unmodifiableList(nodes);
    }

    /** @return 服务端本维世界 tick（显形过期锚点基准） */
    public long getServerTotalWorldTime() {
        return serverTotalWorldTime;
    }

    /** @return 显形时长（tick） */
    public int getDurationTicks() {
        return durationTicks;
    }

    /**
     * 单条入选节点（双端类型，不可变值对象）。
     */
    public static final class RevealedNode {

        /** 节点方块 X */
        public final int x;

        /** 节点方块 Y */
        public final int y;

        /** 节点方块 Z */
        public final int z;

        /** 节点类型：0 = 能源无线 / 1 = 动力无线（客户端着色来源） */
        public final byte type;

        public RevealedNode(int x, int y, int z, byte type) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.type = type;
        }
    }

    /**
     * 客户端处理：委托给 {@link com.miaokatze.gtswn.main.CommonProxy#handleSyncNodeReveal}。
     * <p>
     * 【hotfix v1.5.14 类加载安全模式】方法体只引用双端类型，实际客户端逻辑（切主线程 +
     * 写 {@code WirelessNodeRevealRenderer} 缓存）由后续渲染切片在 ClientProxy 重写实现。
     */
    public static class Handler implements IMessageHandler<PacketSyncNodeReveal, IMessage> {

        @Override
        public IMessage onMessage(PacketSyncNodeReveal msg, MessageContext ctx) {
            // 包注册在 CLIENT，但防御性校验 side 避免异常场景
            if (ctx.side.isServer()) {
                return null;
            }
            // [GTSWN-REVEAL-PROBE] C3
            GTSimpleWirelessNetwork.LOG.info(
                "[GTSWN-REVEAL] C3 recv count=" + msg.getNodes()
                    .size());
            // 委托给 @SidedProxy：服务端调用 CommonProxy 空实现，客户端调用 ClientProxy 实际处理
            GTSimpleWirelessNetwork.proxy.handleSyncNodeReveal(msg);
            return null;
        }
    }
}
