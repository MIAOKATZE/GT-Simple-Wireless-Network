package com.miaokatze.gtswn.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.panel.AEMonitorSample;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;

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
 */
public class PacketSyncAEMonitorData implements IMessage {

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
        Map<String, AEMonitorSample> monitorLatest) {
        this(x, y, z, chartKey, chartSamples, monitorLatest, null);
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

        buf.writeInt(monitorLatest.size());
        for (Map.Entry<String, AEMonitorSample> entry : monitorLatest.entrySet()) {
            ByteBufUtils.writeUTF8String(buf, entry.getKey());
            writeSample(buf, entry.getValue());
        }

        buf.writeInt(monitorAvg300s.size());
        for (Map.Entry<String, Double> entry : monitorAvg300s.entrySet()) {
            ByteBufUtils.writeUTF8String(buf, entry.getKey());
            buf.writeDouble(entry.getValue());
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
            for (int i = 0; i < sampleCount; i++) {
                chartSamples.add(readSample(buf));
            }
        } else {
            chartKey = null;
        }

        int latestCount = buf.readInt();
        for (int i = 0; i < latestCount; i++) {
            String key = ByteBufUtils.readUTF8String(buf);
            AEMonitorSample sample = readSample(buf);
            monitorLatest.put(key, sample);
        }

        int avgCount = buf.readInt();
        for (int i = 0; i < avgCount; i++) {
            String key = ByteBufUtils.readUTF8String(buf);
            double avg = buf.readDouble();
            monitorAvg300s.put(key, avg);
        }
    }

    /** 将一个 AEMonitorSample 的四个字段写入 ByteBuf。 */
    private static void writeSample(ByteBuf buf, AEMonitorSample sample) {
        buf.writeLong(sample.timeMs);
        buf.writeLong(sample.tick);
        buf.writeLong(sample.amount);
        buf.writeDouble(sample.rate);
    }

    /** 从 ByteBuf 读取一个 AEMonitorSample。 */
    private static AEMonitorSample readSample(ByteBuf buf) {
        long timeMs = buf.readLong();
        long tick = buf.readLong();
        long amount = buf.readLong();
        double rate = buf.readDouble();
        return new AEMonitorSample(timeMs, tick, amount, rate);
    }

    /**
     * 客户端处理：定位信息屏 TileEntity，切回主线程后写入 AE 缓存。
     * <p>
     * 【线程安全】SimpleChannelHandlerWrapper 在 Netty 网络线程调用 onMessage，而 GUI/TESR
     * 在客户端主线程读取 TileEntity 字段，故用 {@link Minecraft#func_152344_a(Runnable)}
     * 把写操作调度到主线程，避免并发读到半更新状态。
     * <p>
     * 【注意】不要在此类上加 @SideOnly(Side.CLIENT)！registerMessage 需要在双端都传入 Handler 的 Class 对象，
     * 加 @SideOnly 会导致服务端 SideTransformer 剥离该类，抛出 NoClassDefFoundError 崩服。
     */
    public static class Handler implements IMessageHandler<PacketSyncAEMonitorData, IMessage> {

        @Override
        public IMessage onMessage(final PacketSyncAEMonitorData msg, MessageContext ctx) {
            // 包注册在 CLIENT，但防御性校验 side 避免异常场景
            if (ctx.side.isServer()) {
                return null;
            }

            final World world = Minecraft.getMinecraft().theWorld;
            if (world == null) {
                return null;
            }

            // 先在网络线程定位 TileEntity（避免主线程 world 快照不一致）
            final TileEntity te = world.getTileEntity(msg.x, msg.y, msg.z);
            if (!(te instanceof TileEntityNetworkInfoPanel)) {
                return null;
            }

            // 切到主线程再修改 TileEntity 字段
            Minecraft.getMinecraft()
                .func_152344_a(() -> {
                    TileEntityNetworkInfoPanel panel = (TileEntityNetworkInfoPanel) world
                        .getTileEntity(msg.x, msg.y, msg.z);
                    if (panel != null) {
                        panel.receiveAEMonitorData(msg.chartSamples, msg.monitorLatest, msg.monitorAvg300s);
                    }
                });
            return null;
        }
    }
}
