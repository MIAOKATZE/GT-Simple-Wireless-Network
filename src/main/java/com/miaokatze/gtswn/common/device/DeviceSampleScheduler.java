package com.miaokatze.gtswn.common.device;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.fluids.FluidStack;

import com.miaokatze.gtswn.config.Config;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.interfaces.tileentity.IMachineProgress;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;

/**
 * 设备信息终端采样调度器（实施计划 C1，注册于 CommonProxy.init 的 FML 总线）。
 * <p>
 * ServerTickEvent（END 相）两件事：
 * <ol>
 * <li>每 tick：排空设备终端数据请求队列（包 8 → {@link DeviceTerminalRequestQueue}，
 * Netty 线程入队 → 主线程装配分页回包，仿量子 {@code QuantumTerminalRequestQueue} 模式）</li>
 * <li>每 {@link Config#deviceSampleIntervalSeconds}×20t（读时钳 ≥20t）：把全部<b>活跃终端</b>
 * （{@code DeviceTerminalDataStore.isTerminalActive}，在线玩家持有）的绑定键去重后
 * 入工作队列；每 tick 预算 ≤100 台排水逐键采样</li>
 * </ol>
 * 单键采样流程：
 * <ul>
 * <li>解析 {@code dim:x:y:z} → 维度不存在 / 区块未加载（{@code chunkExists} 为 false）
 * → 跳过保留旧值（不强加载区块）</li>
 * <li>区块已加载但 TE 不满足判别式（IGregTechTileEntity + MTEBasicMachine/MTEMultiBlockBase）
 * → <b>自愈</b>：登记表出册 + {@link DeviceTerminalDataStore#removeKeyFromAll} 级联解绑
 * （version++ 由 store 内部保证），下一台继续</li>
 * <li>有效机器 → 读三态（停机/运行/待机统一口径，isAllowedToWork/isActive 经基座
 * BaseMetaTileEntity 委托）、瞬时功率（mEUt 绝对值）、输出快照
 * （仅运行中，非运行置空串）→ 对含该键的所有活跃终端各自 MachineRecord 追加 FIFO 环形
 * 采样点、重算 60 点均值、刷新 name/localName → version++</li>
 * </ul>
 */
public class DeviceSampleScheduler {

    /** 每 tick 采样排水预算（台） */
    private static final int PER_TICK_BUDGET = 100;

    /** 输出快照字符串总长封顶（字符） */
    private static final int RECIPE_STR_CAP = 120;

    /** 采样工作队列（去重；仅服务端主线程访问） */
    private final LinkedHashSet<String> workQueue = new LinkedHashSet<>();

    /** 采样间隔计数器（仅主线程） */
    private int intervalCounter = 0;

    /**
     * ServerTickEvent（END 相）：先排空请求队列（每 tick 响应 GUI 轮询），再推进采样。
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return;
        }
        // 主线程排空包 8 请求队列（版本未变则跳过重发，防轮询压力）
        DeviceTerminalRequestQueue.drain();
        World overworld = server.worldServerForDimension(0);
        if (overworld == null) {
            return;
        }
        // 采样间隔（读时钳 ≥20t，防 Config 调 0 引发每 tick 全量采样）
        int intervalTicks = Math.max(20, Config.deviceSampleIntervalSeconds * 20);
        this.intervalCounter++;
        if (this.intervalCounter >= intervalTicks) {
            this.intervalCounter = 0;
            refillWorkQueue(overworld);
        }
        if (!this.workQueue.isEmpty()) {
            drainSamples(server, overworld);
        }
    }

    /** 玩家登出：清理请求队列的已收版本缓存（防跨玩家 UUID 残留累积） */
    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!event.player.worldObj.isRemote) {
            DeviceTerminalRequestQueue.onPlayerLoggedOut(event.player.getUniqueID());
        }
    }

    // ==================== 工作队列填充 / 排水 ====================

    /**
     * 到点填充：全部活跃终端的绑定键去重入队（同键多终端共享只采一次，结果分发到各终端）。
     */
    private void refillWorkQueue(World overworld) {
        DeviceTerminalDataStore store = DeviceTerminalDataStore.get(overworld);
        for (Map.Entry<UUID, DeviceTerminalDataStore.TerminalData> entry : store.getAllTerminals()) {
            if (!DeviceTerminalDataStore.isTerminalActive(entry.getKey())) {
                continue;
            }
            this.workQueue.addAll(entry.getValue().boundKeys);
        }
    }

    /**
     * 每 tick 采样排水（≤{@value #PER_TICK_BUDGET} 台）：逐键解析-判别-采样/自愈。
     */
    private void drainSamples(MinecraftServer server, World overworld) {
        int processed = 0;
        DeviceTerminalDataStore store = null;
        DeviceRegistryData registry = null;
        // 活跃终端快照（本 tick 内复用：同键分发到含它的所有活跃终端）
        List<Map.Entry<UUID, DeviceTerminalDataStore.TerminalData>> activeTerminals = null;
        java.util.Iterator<String> it = this.workQueue.iterator();
        while (it.hasNext() && processed < PER_TICK_BUDGET) {
            String key = it.next();
            it.remove();
            processed++;
            int[] pos = parseKey(key);
            if (pos == null) {
                // 键格式损坏（正常路径不可达，仅存档手改/损坏时）：自愈出册级联解绑
                if (registry == null) {
                    registry = DeviceRegistryData.get(overworld);
                    store = DeviceTerminalDataStore.get(overworld);
                }
                registry.unregister(key);
                store.removeKeyFromAll(key);
                continue;
            }
            World world = server.worldServerForDimension(pos[0]);
            if (world == null || world.getChunkProvider() == null
                || !world.getChunkProvider()
                    .chunkExists(pos[1] >> 4, pos[3] >> 4)) {
                // 维度未注册 / 区块未加载：跳过保留旧值（不强加载区块）
                continue;
            }
            TileEntity te = world.getTileEntity(pos[1], pos[2], pos[3]);
            IGregTechTileEntity gtTE = te instanceof IGregTechTileEntity ? (IGregTechTileEntity) te : null;
            IMetaTileEntity mte = gtTE != null ? gtTE.getMetaTileEntity() : null;
            if (!(mte instanceof MTEBasicMachine) && !(mte instanceof MTEMultiBlockBase)) {
                // 区块已加载但 TE 不再是可监控机器 → 自愈：出册 + 级联解绑（version++），下一台继续
                if (registry == null) {
                    registry = DeviceRegistryData.get(overworld);
                    store = DeviceTerminalDataStore.get(overworld);
                }
                registry.unregister(key);
                store.removeKeyFromAll(key);
                continue;
            }
            // 三态经基座委托（BaseMetaTileEntity 实现 IMachineProgress），cast 基座而非 mte
            IMachineProgress progress = (IMachineProgress) gtTE;
            // 三态统一口径：!isAllowedToWork→停机 / isActive→运行 / 其余待机
            int state = !progress.isAllowedToWork() ? DeviceTerminalDataStore.STATE_STOPPED
                : progress.isActive() ? DeviceTerminalDataStore.STATE_RUNNING : DeviceTerminalDataStore.STATE_IDLE;
            long eut = Math.abs(readEUt(mte));
            String localName = mte.getLocalName();
            String recipeStr = state == DeviceTerminalDataStore.STATE_RUNNING ? buildRecipeSnapshot(mte) : "";
            // 分发到含该键的所有活跃终端
            if (activeTerminals == null) {
                DeviceTerminalDataStore dataStore = DeviceTerminalDataStore.get(overworld);
                activeTerminals = new ArrayList<>();
                for (Map.Entry<UUID, DeviceTerminalDataStore.TerminalData> entry : dataStore.getAllTerminals()) {
                    if (DeviceTerminalDataStore.isTerminalActive(entry.getKey())) {
                        activeTerminals.add(entry);
                    }
                }
                store = DeviceTerminalDataStore.get(overworld);
            }
            for (Map.Entry<UUID, DeviceTerminalDataStore.TerminalData> entry : activeTerminals) {
                DeviceTerminalDataStore.TerminalData data = entry.getValue();
                if (!data.boundKeys.contains(key)) {
                    continue;
                }
                DeviceTerminalDataStore.MachineRecord record = data.records.get(key);
                if (record == null) {
                    // 绑定在而记录缺（旧存档）：补建记录
                    record = new DeviceTerminalDataStore.MachineRecord();
                    data.records.put(key, record);
                }
                record.fifo[record.idx] = eut;
                record.idx = (record.idx + 1) % DeviceTerminalDataStore.FIFO_SIZE;
                if (record.count < DeviceTerminalDataStore.FIFO_SIZE) {
                    record.count++;
                }
                long sum = 0L;
                for (long value : record.fifo) {
                    sum += value;
                }
                record.avg = record.count > 0 ? (double) sum / record.count : 0D;
                record.state = state;
                record.name = localName;
                record.dim = pos[0];
                record.x = pos[1];
                record.y = pos[2];
                record.z = pos[3];
                record.recipeStr = recipeStr;
                data.version++;
            }
            if (store != null && !activeTerminals.isEmpty()) {
                // 任一终端记录更新即标脏（version++ 在上方逐终端完成）
                store.markDirty();
            }
        }
    }

    // ==================== 采样读取工具 ====================

    /**
     * 读瞬时功率 mEUt（MTEBasicMachine / MTEMultiBlockBase 公有字段，负=耗电）。
     * 两类基类无公共父类持有该字段，分别 instanceof 读取。
     */
    private static int readEUt(IMetaTileEntity mte) {
        if (mte instanceof MTEBasicMachine) {
            return ((MTEBasicMachine) mte).mEUt;
        }
        if (mte instanceof MTEMultiBlockBase) {
            return ((MTEMultiBlockBase) mte).mEUt;
        }
        return 0;
    }

    /**
     * 输出快照（近似）：mOutputItems / mOutputFluids 拼「物品 xN + 流体 1000L」式描述，
     * display name 取值，总长封顶 {@value #RECIPE_STR_CAP} 字符；空输出返回空串。
     */
    private static String buildRecipeSnapshot(IMetaTileEntity mte) {
        StringBuilder sb = new StringBuilder();
        ItemStack[] outputItems = null;
        FluidStack[] outputFluids = null;
        if (mte instanceof MTEBasicMachine) {
            outputItems = ((MTEBasicMachine) mte).mOutputItems;
        } else if (mte instanceof MTEMultiBlockBase) {
            outputItems = ((MTEMultiBlockBase) mte).mOutputItems;
            outputFluids = ((MTEMultiBlockBase) mte).mOutputFluids;
        }
        if (outputItems != null) {
            for (ItemStack stack : outputItems) {
                if (stack == null || stack.getItem() == null || stack.stackSize <= 0) {
                    continue;
                }
                appendPart(sb, stack.getDisplayName() + " x" + stack.stackSize);
            }
        }
        if (outputFluids != null) {
            for (FluidStack fluid : outputFluids) {
                if (fluid == null || fluid.getFluid() == null || fluid.amount <= 0) {
                    continue;
                }
                appendPart(sb, fluid.getLocalizedName() + " " + fluid.amount + "L");
            }
        }
        return sb.length() > RECIPE_STR_CAP ? sb.substring(0, RECIPE_STR_CAP) : sb.toString();
    }

    /** 追加片段（" + " 分隔），封顶后停止追加 */
    private static void appendPart(StringBuilder sb, String part) {
        if (sb.length() > 0) {
            sb.append(" + ");
        }
        sb.append(part);
    }

    /** 解析机器键 {@code dim:x:y:z} → [dim,x,y,z]；格式坏返回 null */
    private static int[] parseKey(String key) {
        if (key == null) {
            return null;
        }
        String[] parts = key.split(":");
        if (parts.length != 4) {
            return null;
        }
        try {
            return new int[] { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                Integer.parseInt(parts[3]) };
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
