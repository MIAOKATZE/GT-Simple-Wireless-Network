package com.miaokatze.gtswn.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 服务端→客户端 同步包：设备信息终端绑定机器数据分页推送（discriminator = 9，阶段 D2）。
 * <p>
 * 分页协议（128 条/页，满配 1024 条拆 8 页）：terminalUuid / version / pageIndex /
 * pageTotal / entryTotal（封顶 {@value #MAX_ENTRIES}）/ 本页条目
 * {key（正则 {@code dim:x:y:z}）/ name（封 {@value #MAX_NAME_LEN}）/ state（byte 三态）/
 * powerType（byte 0=耗电 / 1=发电，v1.8.0 新增，紧随 state 对称写读）/
 * inst（long 瞬时 EU/t）/ avg（double 均值）/ dim,x,y,z（int）/ recipeIn + recipeOut
 * （各封 {@value #MAX_RECIPE_LEN}，v1.7.2 双侧配方快照）}。
 * <p>
 * fromBytes 全防御（吸收量子系统 v1.6.1 教训）：整体 try-catch（坏包退化为 terminalId=null
 * 的惰性消息，客户端 Handler 判空丢弃）、条目数 {@link #MAX_ENTRIES} 封顶、逐条
 * readableBytes 预检 + 单条异常截断（保留已解析前缀）、name/recipeIn/recipeOut 读侧截断、
 * key 正则校验非法跳过。
 * <p>
 * 客户端 Handler 方法体只引用双端类型（hotfix v1.5.14 类加载安全模式：不得引用
 * {@code @SideOnly(Side.CLIENT)} 客户端类，否则 registerMessage 类加载解析被
 * SideTransformer 拒绝崩服），经 @SidedProxy 委托 → ClientProxy 以
 * {@code Minecraft.func_152344_a} 切主线程写 {@code client.DeviceTerminalClientCache}
 * （分页到齐才整体替换防撕裂，缓存锚点 = terminalId 不随 GUI 关闭清空）。
 */
public class PacketSyncDeviceTerminalData implements IMessage {

    /** 条目总数硬上限（与 Config.deviceTerminalMaxMachines 默认一致，防异常包体内存暴涨） */
    public static final int MAX_ENTRIES = 1024;

    /** name 字段长度上限（字符） */
    public static final int MAX_NAME_LEN = 64;

    /** 配方单侧字段长度上限（字符，recipeIn/recipeOut 各自封顶） */
    public static final int MAX_RECIPE_LEN = 120;

    /** 机器键正则（dim:x:y:z，允许负坐标） */
    public static final Pattern KEY_PATTERN = Pattern.compile("-?\\d+:-?\\d+:-?\\d+:-?\\d+");

    /** 终端实例 UUID（fromBytes 解析失败为 null → 客户端丢弃整包） */
    private UUID terminalId;

    /** 数据版本号（与 TerminalData.version 对应，客户端缓存批次锚点） */
    private long version;

    /** 页下标（0 起） */
    private int pageIndex;

    /** 总页数 */
    private int pageTotal;

    /** 条目总数（≤{@value #MAX_ENTRIES}） */
    private int entryTotal;

    /** 本页条目 */
    private final List<Entry> entries = new ArrayList<>();

    /** Forge 反射无参构造（反序列化时必需） */
    public PacketSyncDeviceTerminalData() {}

    public PacketSyncDeviceTerminalData(UUID terminalId, long version, int pageIndex, int pageTotal, int entryTotal,
        List<Entry> pageEntries) {
        this.terminalId = terminalId;
        this.version = version;
        this.pageIndex = pageIndex;
        this.pageTotal = pageTotal;
        this.entryTotal = Math.min(entryTotal, MAX_ENTRIES);
        this.entries.addAll(pageEntries);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, terminalId.toString());
        buf.writeLong(version);
        buf.writeInt(pageIndex);
        buf.writeInt(pageTotal);
        buf.writeInt(entryTotal);
        buf.writeInt(entries.size());
        for (Entry entry : entries) {
            ByteBufUtils.writeUTF8String(buf, entry.key);
            ByteBufUtils.writeUTF8String(buf, entry.name);
            buf.writeByte(entry.state);
            buf.writeByte(entry.powerType);
            buf.writeLong(entry.inst);
            buf.writeDouble(entry.avg);
            buf.writeInt(entry.dim);
            buf.writeInt(entry.x);
            buf.writeInt(entry.y);
            buf.writeInt(entry.z);
            ByteBufUtils.writeUTF8String(buf, entry.recipeIn);
            ByteBufUtils.writeUTF8String(buf, entry.recipeOut);
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        try {
            terminalId = UUID.fromString(ByteBufUtils.readUTF8String(buf));
            version = buf.readLong();
            pageIndex = buf.readInt();
            pageTotal = buf.readInt();
            entryTotal = Math.min(buf.readInt(), MAX_ENTRIES);
            int count = Math.min(buf.readInt(), MAX_ENTRIES);
            for (int i = 0; i < count; i++) {
                // 单条最小防御：可读字节枯竭即停止（保留已解析前缀）
                if (buf.readableBytes() <= 0) {
                    break;
                }
                try {
                    String key = ByteBufUtils.readUTF8String(buf);
                    String name = truncate(ByteBufUtils.readUTF8String(buf), MAX_NAME_LEN);
                    byte state = buf.readByte();
                    byte powerType = buf.readByte();
                    long inst = buf.readLong();
                    double avg = buf.readDouble();
                    int dim = buf.readInt();
                    int x = buf.readInt();
                    int y = buf.readInt();
                    int z = buf.readInt();
                    String recipeIn = truncate(ByteBufUtils.readUTF8String(buf), MAX_RECIPE_LEN);
                    String recipeOut = truncate(ByteBufUtils.readUTF8String(buf), MAX_RECIPE_LEN);
                    // key 正则校验：非法键跳过该条（不阻断后续条目）
                    if (key == null || !KEY_PATTERN.matcher(key)
                        .matches()) {
                        continue;
                    }
                    entries.add(new Entry(key, name, state, powerType, inst, avg, dim, x, y, z, recipeIn, recipeOut));
                } catch (Exception e) {
                    // 单条损坏（字符串长度越界等）：截断解析，保留已解析前缀
                    break;
                }
            }
        } catch (Exception e) {
            // 包头 / UUID 损坏：退化为惰性消息（terminalId=null），客户端 Handler 判空丢弃
            terminalId = null;
            entries.clear();
        }
    }

    /** 长度截断（null 安全） */
    private static String truncate(String value, int cap) {
        if (value == null) {
            return "";
        }
        return value.length() > cap ? value.substring(0, cap) : value;
    }

    // ==================== 客户端读取 getter ====================

    /** @return 终端实例 UUID（坏包为 null，客户端判空丢弃） */
    public UUID getTerminalId() {
        return terminalId;
    }

    /** @return 数据版本号（缓存批次锚点） */
    public long getVersion() {
        return version;
    }

    /** @return 页下标（0 起） */
    public int getPageIndex() {
        return pageIndex;
    }

    /** @return 总页数 */
    public int getPageTotal() {
        return pageTotal;
    }

    /** @return 条目总数（≤{@value #MAX_ENTRIES}） */
    public int getEntryTotal() {
        return entryTotal;
    }

    /** @return 本页条目（只读语义，客户端不得修改） */
    public List<Entry> getEntries() {
        return entries;
    }

    /**
     * 单条机器条目（双端类型；构造时截断 name/recipeIn/recipeOut 至封顶长度）。
     */
    public static final class Entry {

        /** 机器键（dim:x:y:z） */
        public final String key;

        /** 机器显示名（≤{@value #MAX_NAME_LEN} 字符） */
        public final String name;

        /** 三态：0 待机 / 1 运行 / 2 停机 */
        public final byte state;

        /** 功率分类：0 耗电 / 1 发电（v1.8.0；非 1 值钳回 0） */
        public final byte powerType;

        /** 瞬时 EU/t（FIFO 最新采样点） */
        public final long inst;

        /** 平均 EU/t（60 点均值） */
        public final double avg;

        /** 机器维度 */
        public final int dim;

        /** 机器坐标 */
        public final int x;

        public final int y;

        public final int z;

        /** 当前执行配方输入侧描述（v1.7.2；GT5U 无公开 lastRecipe 入口暂为空串，≤{@value #MAX_RECIPE_LEN} 字符） */
        public final String recipeIn;

        /** 当前执行配方输出侧描述（输出快照，近似，≤{@value #MAX_RECIPE_LEN} 字符） */
        public final String recipeOut;

        public Entry(String key, String name, byte state, byte powerType, long inst, double avg, int dim, int x, int y,
            int z, String recipeIn, String recipeOut) {
            this.key = key == null ? "" : key;
            this.name = truncate(name, MAX_NAME_LEN);
            this.state = state;
            this.powerType = powerType == 1 ? (byte) 1 : (byte) 0;
            this.inst = inst;
            this.avg = avg;
            this.dim = dim;
            this.x = x;
            this.y = y;
            this.z = z;
            this.recipeIn = truncate(recipeIn, MAX_RECIPE_LEN);
            this.recipeOut = truncate(recipeOut, MAX_RECIPE_LEN);
        }
    }

    /**
     * 客户端处理：委托给 {@link com.miaokatze.gtswn.main.CommonProxy#handleSyncDeviceTerminalData}。
     * <p>
     * 【hotfix v1.5.14 类加载安全模式】方法体只引用双端类型，实际客户端逻辑在 ClientProxy
     * （func_152344_a 切主线程写 DeviceTerminalClientCache，分页到齐整体替换防撕裂）。
     */
    public static class Handler implements IMessageHandler<PacketSyncDeviceTerminalData, IMessage> {

        @Override
        public IMessage onMessage(PacketSyncDeviceTerminalData msg, MessageContext ctx) {
            // 包注册在 CLIENT，但防御性校验 side 避免异常场景
            if (ctx.side.isServer()) {
                return null;
            }
            // 委托给 @SidedProxy：服务端调用 CommonProxy 空实现，客户端调用 ClientProxy 实际处理
            GTSimpleWirelessNetwork.proxy.handleSyncDeviceTerminalData(msg);
            return null;
        }
    }
}
