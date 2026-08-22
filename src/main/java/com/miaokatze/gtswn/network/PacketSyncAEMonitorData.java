package com.miaokatze.gtswn.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.miaokatze.gtswn.common.panel.AEMonitorSample;
import com.miaokatze.gtswn.common.panel.AEMonitorWindowSeries;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 服务端→客户端 同步包：将 AE 走势图样本与实时监控列表最新值推送到客户端信息屏。
 * <p>
 * 服务端 {@link TileEntityNetworkInfoPanel#sendAEMonitorDataToClients()} 在每次 AE 采样后构造并发送此包，
 * 客户端 {@link Handler} 收到后将数据写入 TileEntity 的客户端缓存，供 GUI/TESR 渲染读取。
 * <p>
 * discriminator = 4（见 {@link GTSWNPacketHandler#register()}）。
 * <p>
 * 【O2-20 广播第一刀（同 jar 双端，零协议语义变化）】
 * <ul>
 * <li>样本 timeMs 不上线（全 client/ grep 实证零消费，客户端重建以 0 占位）：32→24B/样本</li>
 * <li>monitorLatest / monitorAvg300s 双 Map 合一上线：同 key 只写一次字符串，
 * 1B flags（bit0=hasLatest，bit1=hasAvg）标记各段有无——latest 的 key 集 ⊆ avg 的 key 集
 * （avg 无条件写入，latest 依赖 newest 非空），满配 64 key 约省 1.3KB</li>
 * </ul>
 */
public class PacketSyncAEMonitorData implements IMessage {

    /**
     * 实时监控 key 数（monitorLatest / monitorAvg300s 合并 key 集）的防御性读取上限（B2-12）。
     * 推导 = 2 × aeMaxMonitoredItems 上限（物品+流体，Config 钳制 1-256）= 2 × 256 = 512。
     */
    private static final int MAX_MONITOR_KEYS = 512;

    /** 目标信息屏坐标 */
    private int x, y, z;

    /** 走势图 key（null 表示无绑定） */
    private String chartKey;

    /** 走势图样本列表 */
    private List<AEMonitorSample> chartSamples;

    /** 实时监控列表各 key 的最新采样值 */
    private Map<String, AEMonitorSample> monitorLatest;

    /** 实时监控列表各 key 的 300s 平均变化率 */
    private Map<String, Double> monitorAvg300s;

    /** Forge 反射无参构造（反序列化时必需） */
    public PacketSyncAEMonitorData() {
        this.chartSamples = new ArrayList<>();
        this.monitorLatest = new HashMap<>();
        this.monitorAvg300s = new HashMap<>();
    }

    public PacketSyncAEMonitorData(int x, int y, int z, String chartKey, List<AEMonitorSample> chartSamples,
        Map<String, AEMonitorSample> monitorLatest, Map<String, Double> monitorAvg300s) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.chartKey = chartKey;
        // 深拷贝，避免外部修改包数据
        this.chartSamples = chartSamples == null ? new ArrayList<>() : new ArrayList<>(chartSamples);
        this.monitorLatest = monitorLatest == null ? new HashMap<>() : new HashMap<>(monitorLatest);
        this.monitorAvg300s = monitorAvg300s == null ? new HashMap<>() : new HashMap<>(monitorAvg300s);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);

        boolean hasChart = chartKey != null;
        buf.writeBoolean(hasChart);
        if (hasChart) {
            ByteBufUtils.writeUTF8String(buf, chartKey);
            buf.writeInt(chartSamples.size());
            for (AEMonitorSample sample : chartSamples) {
                writeSample(buf, sample);
            }
        }

        // O2-20：latest/avg 双 Map 合一上线——同 key 只写一次，flags 标记各段有无
        Set<String> monitorKeys = new HashSet<>();
        monitorKeys.addAll(monitorLatest.keySet());
        monitorKeys.addAll(monitorAvg300s.keySet());
        buf.writeInt(monitorKeys.size());
        for (String key : monitorKeys) {
            ByteBufUtils.writeUTF8String(buf, key);
            AEMonitorSample sample = monitorLatest.get(key);
            Double avg = monitorAvg300s.get(key);
            buf.writeByte((sample != null ? 1 : 0) | (avg != null ? 2 : 0));
            if (sample != null) {
                writeSample(buf, sample);
            }
            if (avg != null) {
                buf.writeDouble(avg);
            }
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();

        chartSamples = new ArrayList<>();
        monitorLatest = new HashMap<>();
        monitorAvg300s = new HashMap<>();

        boolean hasChart = buf.readBoolean();
        if (hasChart) {
            chartKey = ByteBufUtils.readUTF8String(buf);
            int sampleCount = buf.readInt();
            // 防御性上限（B2-12）：走势图样本不会超过 FIFO 容量 61（与包 6 entryCount 钳制同范式）
            sampleCount = Math.min(sampleCount, AEMonitorWindowSeries.CAPACITY);
            for (int i = 0; i < sampleCount; i++) {
                chartSamples.add(readSample(buf));
            }
        } else {
            chartKey = null;
        }

        // O2-20：合并 key 集读取，flags（bit0=hasLatest，bit1=hasAvg）标记各段有无
        int monitorCount = buf.readInt();
        // 防御性上限（B2-12）：见 MAX_MONITOR_KEYS 注释（2 × 256 推导）
        monitorCount = Math.min(monitorCount, MAX_MONITOR_KEYS);
        for (int i = 0; i < monitorCount; i++) {
            String key = ByteBufUtils.readUTF8String(buf);
            int flags = buf.readByte() & 0xFF;
            if ((flags & 1) != 0) {
                monitorLatest.put(key, readSample(buf));
            }
            if ((flags & 2) != 0) {
                monitorAvg300s.put(key, buf.readDouble());
            }
        }
    }

    /**
     * 将一个 AEMonitorSample 写入 ByteBuf。
     * <p>
     * O2-20：timeMs 客户端零消费（全 client/ grep 实证），不再上线——样本 32→24B；
     * 服务端 WSD/NBT 与聚合链的 timeMs 不受影响。
     */
    private static void writeSample(ByteBuf buf, AEMonitorSample sample) {
        buf.writeLong(sample.tick);
        buf.writeLong(sample.amount);
        buf.writeDouble(sample.rate);
    }

    /** 从 ByteBuf 读取一个 AEMonitorSample（timeMs 未上线，重建以 0 占位）。 */
    private static AEMonitorSample readSample(ByteBuf buf) {
        long tick = buf.readLong();
        long amount = buf.readLong();
        double rate = buf.readDouble();
        return new AEMonitorSample(0L, tick, amount, rate);
    }

    /**
     * 客户端处理：委托给 {@link com.miaokatze.gtswn.main.CommonProxy#handleSyncAEMonitorData}。
     * <p>
     * 【hotfix v1.5.14】原实现直接调用 {@code Minecraft.getMinecraft().theWorld}，
     * 而 theWorld 字段类型 WorldClient 是 @SideOnly(Side.CLIENT) 类。在 Java 25 JVM 下，
     * registerMessage 调用 Handler.class.newInstance() 会触发 getDeclaredConstructors0()
     * 解析方法体引用类型，导致 WorldClient 被加载，被 SideTransformer 拒绝崩服。
     * <p>
     * 现改为通过 @SidedProxy 委托：本 Handler 方法体只引用 CommonProxy（双端类型），
     * 不引用任何客户端类，从根源上避免类加载触发。实际客户端逻辑在 ClientProxy 中实现。
     * <p>
     * 【注意】不要在此类上加 @SideOnly(Side.CLIENT)！registerMessage 需要在双端都传入
     * Handler 的 Class 对象，加 @SideOnly 会导致服务端 SideTransformer 剥离该类，
     * 抛出 NoClassDefFoundError 崩服。
     */
    public static class Handler implements IMessageHandler<PacketSyncAEMonitorData, IMessage> {

        @Override
        public IMessage onMessage(PacketSyncAEMonitorData msg, MessageContext ctx) {
            // 包注册在 CLIENT，但防御性校验 side 避免异常场景
            if (ctx.side.isServer()) {
                return null;
            }
            // 委托给 @SidedProxy：服务端调用 CommonProxy 空实现，客户端调用 ClientProxy 实际处理
            // 这样 Handler 类方法体不引用任何 @SideOnly 客户端类，避免 registerMessage 时的类加载崩溃
            GTSimpleWirelessNetwork.proxy.handleSyncAEMonitorData(msg);
            return null;
        }
    }

    // ==================== Getter 方法（hotfix v1.5.14 新增）====================
    // 供 ClientProxy 通过 message 对象访问 private 字段，避免破坏封装性

    /** @return 目标信息屏 X 坐标 */
    public int getX() {
        return x;
    }

    /** @return 目标信息屏 Y 坐标 */
    public int getY() {
        return y;
    }

    /** @return 目标信息屏 Z 坐标 */
    public int getZ() {
        return z;
    }

    /** @return 走势图 key（null 表示无绑定） */
    public String getChartKey() {
        return chartKey;
    }

    /** @return 走势图样本列表 */
    public List<AEMonitorSample> getChartSamples() {
        return chartSamples;
    }

    /** @return 实时监控列表各 key 的最新采样值 */
    public Map<String, AEMonitorSample> getMonitorLatest() {
        return monitorLatest;
    }

    /** @return 实时监控列表各 key 的 300s 平均变化率 */
    public Map<String, Double> getMonitorAvg300s() {
        return monitorAvg300s;
    }
}
