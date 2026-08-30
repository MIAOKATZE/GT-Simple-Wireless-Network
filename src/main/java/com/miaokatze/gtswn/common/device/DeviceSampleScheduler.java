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
import gregtech.api.interfaces.tileentity.IBasicEnergyContainer;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.interfaces.tileentity.IMachineProgress;
import gregtech.api.metatileentity.implementations.MTEBasicGenerator;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.metatileentity.implementations.MTEExtendedPowerMultiBlockBase;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.common.tileentities.generators.MTELightningRod;
import gregtech.common.tileentities.generators.MTESolarGenerator;
import gregtech.common.tileentities.machines.multi.turbines.MTELargeTurbineBase;
import gregtech.common.tileentities.machines.multi.xlturbines.MTEXLTurbineBase;

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
 * <li>区块已加载但 TE 不满足 D1 判别式（{@link DeviceMachineTypes#isWorkingMachine}）
 * → <b>自愈</b>：登记表出册 + {@link DeviceTerminalDataStore#removeKeyFromAll} 级联解绑
 * （version++ 由 store 内部保证），下一台继续</li>
 * <li>有效机器 → 读三态（停机/运行/待机统一口径，isAllowedToWork/isActive 经基座
 * BaseMetaTileEntity 委托）、瞬时功率（{@link #readEUt} 按机器类型分支，取 abs 正数幅值）、
 * 功率分类（发电谓词刷新 powerType 0/1）、配方双侧快照
 * （v1.7.2：输入侧因 GT5U 无公开 lastRecipe 入口暂置空，输出侧经公有 mOutputItems/
 * mOutputFluids 采集；仅运行中，非运行置空串）→ 对含该键的所有活跃终端各自 MachineRecord
 * 追加 FIFO 环形采样点、重算 60 点均值、刷新 name/localName → version++</li>
 * </ul>
 */
public class DeviceSampleScheduler {

    /** 每 tick 采样排水预算（台） */
    private static final int PER_TICK_BUDGET = 100;

    /** 单侧配方快照字符串总长封顶（字符，输入/输出各自封顶） */
    private static final int RECIPE_STR_CAP = 120;

    /** 单侧配方快照条目数上限（超过 8 项服务端截断，GUI 侧另有 ellipsis 兜底） */
    private static final int RECIPE_SIDE_MAX_ITEMS = 8;

    /** 单项显示名长度封顶（字符，截断后再拼 xN/nL 后缀） */
    private static final int RECIPE_PART_NAME_CAP = 24;

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
            if (!DeviceMachineTypes.isWorkingMachine(mte)) {
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
            long eut = Math.abs(readEUt(mte, gtTE));
            // 功率分类（0=耗电 / 1=发电），随采样持续刷新（含旧档补齐）
            byte powerType = DeviceMachineTypes.isGeneratorMachine(mte) ? DeviceTerminalDataStore.POWER_TYPE_GENERATE
                : DeviceTerminalDataStore.POWER_TYPE_CONSUME;
            String localName = mte.getLocalName();
            String recipeIn = state == DeviceTerminalDataStore.STATE_RUNNING ? buildRecipeInputSnapshot(mte) : "";
            String recipeOut = state == DeviceTerminalDataStore.STATE_RUNNING ? buildRecipeOutputSnapshot(mte) : "";
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
                record.powerType = powerType;
                record.name = localName;
                record.dim = pos[0];
                record.x = pos[1];
                record.y = pos[2];
                record.z = pos[3];
                record.recipeIn = recipeIn;
                record.recipeOut = recipeOut;
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
     * 读瞬时/平均功率（调用方取 abs 保持正数幅值语义）。按序分支（GT5U 5.09.54.20 语义）：
     * <ol>
     * <li>发电常规机（MTEBasicGenerator / MTESolarGenerator / MTELightningRod）→
     * 基座 {@link IBasicEnergyContainer#getAverageElectricOutput()}（IGregTechTileEntity →
     * ICoverable → IBasicEnergyContainer，cast 基座 gtTE 而非 mte）。语义注记：mAverageEUOutput
     * 仅 drainEnergyUnits 累加，燃料入缓冲而未被抽取时显示 0，可接受</li>
     * <li>大型涡轮 / XL 涡轮（MTEExtendedPowerMultiBlockBase 子类）→ {@code Math.abs(lEUt)}
     * （lEUt &gt; 0 = addEnergyOutput）</li>
     * <li>其他多方块 → {@code mEUt}（&gt;0 = 发电 / &lt;0 = 耗电，setEnergyUsage 配方正值取负）</li>
     * <li>单方块加工机（MTEBasicMachine）→ 既有 {@code mEUt}（负 = 耗电）</li>
     * </ol>
     * 两类基类无公共父类持有功率字段，分别 instanceof 读取。
     */
    private static long readEUt(IMetaTileEntity mte, IGregTechTileEntity gtTE) {
        // ① 发电常规机：基座平均输出（燃料入缓冲未抽取显示 0 可接受）
        if (mte instanceof MTEBasicGenerator || mte instanceof MTESolarGenerator || mte instanceof MTELightningRod) {
            return ((IBasicEnergyContainer) gtTE).getAverageElectricOutput();
        }
        // ② 大型 / XL 涡轮：lEUt 正值 = 输出
        if (mte instanceof MTELargeTurbineBase || mte instanceof MTEXLTurbineBase) {
            return Math.abs(((MTEExtendedPowerMultiBlockBase) mte).lEUt);
        }
        // ③ 其他多方块：mEUt（>0 发电 / <0 耗电）
        if (mte instanceof MTEMultiBlockBase) {
            return ((MTEMultiBlockBase) mte).mEUt;
        }
        // ④ 单方块加工机：既有 mEUt（负 = 耗电）
        if (mte instanceof MTEBasicMachine) {
            return ((MTEBasicMachine) mte).mEUt;
        }
        return 0L;
    }

    /**
     * 输入侧配方快照（v1.7.2 双侧采集）。
     * <p>
     * 【GT5U 5.09.54.20 公开 API 核实结论】MTEBasicMachine.mLastRecipe 为 protected 字段
     * （MTEBasicMachine.java:139），全 gregtech.api 无公开取 lastRecipe 的入口；
     * MTEMultiBlockBase 在该版本无 lastRecipe 字段；配方类 gregtech.api.util.GTRecipe 亦无
     * getRepresentativeInputs/Outputs 数组方法（仅 per-index getRepresentativeInput(int)）。
     * 输入侧无可用的公开入口，按计划回退：置空串返回（禁止反射私有成员），
     * 待 GT5U 后续开放公开 API 再补双侧完整采样。
     */
    private static String buildRecipeInputSnapshot(IMetaTileEntity mte) {
        return "";
    }

    /**
     * 输出侧快照（近似）：mOutputItems / mOutputFluids 拼「显示名 xN|显示名 nL|...」式描述，
     * display name 取值，单项名封 {@value #RECIPE_PART_NAME_CAP} 字符、单侧条目数封
     * {@value #RECIPE_SIDE_MAX_ITEMS} 项、总长封顶 {@value #RECIPE_STR_CAP} 字符（超限截断）；
     * 空输出返回空串。
     */
    private static String buildRecipeOutputSnapshot(IMetaTileEntity mte) {
        StringBuilder sb = new StringBuilder();
        ItemStack[] outputItems = null;
        FluidStack[] outputFluids = null;
        if (mte instanceof MTEBasicMachine) {
            outputItems = ((MTEBasicMachine) mte).mOutputItems;
        } else if (mte instanceof MTEMultiBlockBase) {
            outputItems = ((MTEMultiBlockBase) mte).mOutputItems;
            outputFluids = ((MTEMultiBlockBase) mte).mOutputFluids;
        }
        int count = 0;
        if (outputItems != null) {
            for (ItemStack stack : outputItems) {
                if (stack == null || stack.getItem() == null || stack.stackSize <= 0) {
                    continue;
                }
                appendPart(sb, capName(stack.getDisplayName()) + " x" + stack.stackSize);
                if (++count >= RECIPE_SIDE_MAX_ITEMS) {
                    return capTotal(sb);
                }
            }
        }
        if (outputFluids != null) {
            for (FluidStack fluid : outputFluids) {
                if (fluid == null || fluid.getFluid() == null || fluid.amount <= 0) {
                    continue;
                }
                appendPart(sb, capName(fluid.getLocalizedName()) + " " + fluid.amount + "L");
                if (++count >= RECIPE_SIDE_MAX_ITEMS) {
                    return capTotal(sb);
                }
            }
        }
        return capTotal(sb);
    }

    /** 追加片段（"|" 分隔，v1.7.2 双侧序列化格式），超条目数上限后由调用方截断停止 */
    private static void appendPart(StringBuilder sb, String part) {
        if (sb.length() > 0) {
            sb.append('|');
        }
        sb.append(part);
    }

    /** 单项显示名截断（null 安全） */
    private static String capName(String name) {
        if (name == null) {
            return "";
        }
        return name.length() > RECIPE_PART_NAME_CAP ? name.substring(0, RECIPE_PART_NAME_CAP) : name;
    }

    /** 单侧总长封顶截断 */
    private static String capTotal(StringBuilder sb) {
        return sb.length() > RECIPE_STR_CAP ? sb.substring(0, RECIPE_STR_CAP) : sb.toString();
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
