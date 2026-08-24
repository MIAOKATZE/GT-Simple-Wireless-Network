package com.miaokatze.gtswn.network;

import net.minecraft.entity.player.EntityPlayerMP;

import com.miaokatze.gtswn.common.performance.PerformanceAudit;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端→服务端 动作包：设备信息终端 GUI 操作（discriminator = 10，阶段 D3）。
 * <p>
 * 字段：action（byte）+ 列 byte + 方向 byte + 计数法 byte + key（str，正则校验）。
 * <ul>
 * <li>{@link #ACTION_SORT} = 0 排序（column 0-4 NAME/STATE/INST/AVG/POS + direction 0/1）</li>
 * <li>{@link #ACTION_COUNT_MODE} = 1 计数法（0-2 NORMAL/SCIENTIFIC/THOUSANDS）</li>
 * <li>{@link #ACTION_UNBIND} = 2 解绑（key）</li>
 * <li>{@link #ACTION_TELEPORT} = 3 传送（key；3s 冷却 + 经验复查扣减 + 同/跨维传送）</li>
 * </ul>
 * 线程模式照 {@code PanelActionQueue}（Netty 入队 → ServerTickEvent END 主线程 drain）：
 * Handler 仅入队 {@link DeviceTerminalActionQueue}（异质变更序列不去重，FIFO 保到达序）。
 * 动作 0/1 写回物品 NBT 偏好（服务端权威，静默）；动作 2/3 后失效该玩家已收版本，
 * 下轮请求排水立即回发快照。
 */
public class PacketDeviceTerminalAction implements IMessage {

    /** 动作：GUI 排序偏好写回 */
    public static final byte ACTION_SORT = 0;

    /** 动作：GUI 计数法偏好写回 */
    public static final byte ACTION_COUNT_MODE = 1;

    /** 动作：解绑指定机器 */
    public static final byte ACTION_UNBIND = 2;

    /** 动作：传送到指定机器 */
    public static final byte ACTION_TELEPORT = 3;

    /** 排序列：机器名 */
    public static final byte COLUMN_NAME = 0;

    /** 排序列：状态 */
    public static final byte COLUMN_STATE = 1;

    /** 排序列：瞬时 */
    public static final byte COLUMN_INST = 2;

    /** 排序列：平均 */
    public static final byte COLUMN_AVG = 3;

    /** 排序列：位置 */
    public static final byte COLUMN_POS = 4;

    /** 排序方向：升序 */
    public static final byte DIR_ASC = 0;

    /** 排序方向：降序 */
    public static final byte DIR_DESC = 1;

    /** 计数法：常规 */
    public static final byte MODE_NORMAL = 0;

    /** 计数法：科学计数 */
    public static final byte MODE_SCIENTIFIC = 1;

    /** 计数法：千位分隔 */
    public static final byte MODE_THOUSANDS = 2;

    /** key 字段长度上限（防御） */
    private static final int MAX_KEY_LEN = 64;

    /** 动作类型（0-3） */
    private byte action;

    /** 排序列（0-4，动作 0 用） */
    private byte column;

    /** 排序方向（0/1，动作 0 用） */
    private byte direction;

    /** 计数法（0-2，动作 1 用） */
    private byte countMode;

    /** 目标机器键（动作 2/3 用，dim:x:y:z） */
    private String key = "";

    /** Forge 反射无参构造（反序列化时必需） */
    public PacketDeviceTerminalAction() {}

    public PacketDeviceTerminalAction(byte action, byte column, byte direction, byte countMode, String key) {
        this.action = action;
        this.column = column;
        this.direction = direction;
        this.countMode = countMode;
        this.key = key == null ? "" : key;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(action);
        buf.writeByte(column);
        buf.writeByte(direction);
        buf.writeByte(countMode);
        ByteBufUtils.writeUTF8String(buf, key);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        try {
            action = buf.readByte();
            column = buf.readByte();
            direction = buf.readByte();
            countMode = buf.readByte();
            String readKey = ByteBufUtils.readUTF8String(buf);
            key = readKey != null && readKey.length() <= MAX_KEY_LEN ? readKey : "";
        } catch (Exception e) {
            // 坏包退化为无动作（action 超界由队列侧忽略）
            action = -1;
            key = "";
        }
    }

    /**
     * 服务端处理：仅入队 {@link DeviceTerminalActionQueue}（照 PanelActionQueue 的
     * Netty→主线程模式），世界态修改全部在主线程 drain 复验后执行。
     */
    public static class Handler implements IMessageHandler<PacketDeviceTerminalAction, IMessage> {

        @Override
        public IMessage onMessage(PacketDeviceTerminalAction msg, MessageContext ctx) {
            // 性能审计——C→S 包计数（discriminator 10）
            if (PerformanceAudit.enabled()) PerformanceAudit.recordPacketReceived(10);
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player != null) {
                DeviceTerminalActionQueue
                    .enqueue(player, msg.action, msg.column, msg.direction, msg.countMode, msg.key);
            }
            return null;
        }
    }
}
