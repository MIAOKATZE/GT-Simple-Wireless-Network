package com.miaokatze.gtswn.common.items;

import java.util.List;
import java.util.UUID;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.server.S2FPacketSetSlot;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.device.DeviceMachineTypes;
import com.miaokatze.gtswn.common.device.DeviceRegistryData;
import com.miaokatze.gtswn.common.device.DeviceScanManager;
import com.miaokatze.gtswn.common.device.DeviceTerminalDataStore;
import com.miaokatze.gtswn.common.device.DeviceTerminalDataStore.AddResult;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/**
 * 设备信息终端（实施计划阶段 A：物品 + NBT 偏好 + 机器绑定手势）。
 * <p>
 * 手势表：
 * <ul>
 * <li>右击<b>加工 GT 机器</b> = 绑定到本终端：onItemUseFirst 服务端权威
 * （客户端返回 false 放行让 C08 包发出、服务端返回 true 拦截，仿
 * {@link ItemNetworkQuantumTerminal#onItemUseFirst} 先例；登记表无则补登 owner=点击者）</li>
 * <li>右击<b>GT 非加工机器</b> = not_machine 提示并拦截（不开其 GUI）；右击<b>非 GT 方块</b>
 * = 放行（等效空手交互）</li>
 * <li>右击空气 = 打开终端 GUI（阶段 E 客户端本地打开，本阶段服务端无操作）</li>
 * <li>Shift+右击（机器/空气均可）= 扫描开关（onItemRightClick 的 Shift 分支处理，
 * onItemUseFirst 对 Shift 一律放行防双 toggle 抵消）</li>
 * </ul>
 * <p>
 * 判别式（统一口径 D1，{@link DeviceMachineTypes#isWorkingMachine}）：
 * {@code te instanceof IGregTechTileEntity && isWorkingMachine(mte)}
 * （MTEBasicMachine ∥ MTEMultiBlockBase ∥ MTEBasicGenerator ∥ MTESolarGenerator ∥ MTELightningRod）。
 * <p>
 * NBT 结构：DIT_UUID（终端实例 UUID，首用生成，绑定数据仓锚点）+
 * UI 偏好三键（排序列 / 排序方向 / 计数法，非法值读侧回退默认）。
 */
public class ItemDeviceInfoTerminal extends Item {

    // ==================== NBT 键名 ====================

    /** 终端实例 UUID（string，首用生成后常驻） */
    public static final String NBT_UUID = "DIT_UUID";

    /** GUI 排序列偏好（string：NAME / STATE / INST / AVG / POS，默认 AVG） */
    public static final String NBT_SORT_COLUMN = "DIT_SortCol";

    /** GUI 排序方向偏好（string：ASC / DESC，默认 DESC） */
    public static final String NBT_SORT_DIR = "DIT_SortDir";

    /** GUI 计数法偏好（string：NORMAL / SCIENTIFIC / THOUSANDS，默认 NORMAL） */
    public static final String NBT_NUM_FORMAT = "DIT_NumFmt";

    // ==================== UI 偏好常量（阶段 E GUI / 阶段 D 动作包写回） ====================

    /** 排序列：机器名 */
    public static final String SORT_NAME = "NAME";

    /** 排序列：状态 */
    public static final String SORT_STATE = "STATE";

    /** 排序列：瞬时 EU/t */
    public static final String SORT_INST = "INST";

    /** 排序列：平均 EU/t（默认） */
    public static final String SORT_AVG = "AVG";

    /** 排序列：位置 */
    public static final String SORT_POS = "POS";

    /** 排序方向：升序 */
    public static final String DIR_ASC = "ASC";

    /** 排序方向：降序（默认） */
    public static final String DIR_DESC = "DESC";

    /** 计数法：常规（默认） */
    public static final String NUM_NORMAL = "NORMAL";

    /** 计数法：科学计数 */
    public static final String NUM_SCIENTIFIC = "SCIENTIFIC";

    /** 计数法：千位分隔 */
    public static final String NUM_THOUSANDS = "THOUSANDS";

    /** 计数法：电压等级（v1.7.2 第四模式） */
    public static final String NUM_VOLTAGE = "VOLTAGE";

    /**
     * 构造函数：初始化设备信息终端的基础属性（仿 {@link ItemNetworkQuantumTerminal}）。
     */
    public ItemDeviceInfoTerminal() {
        super();
        // 未本地化名称带 gtswn. 命名空间前缀 → lang 键 item.gtswn.deviceInfoTerminal.name
        setUnlocalizedName("gtswn.deviceInfoTerminal");
        // 材质路径 assets/gtswn/textures/items/Device_Info_Terminal.png（复制自量子终端）
        setTextureName("gtswn:Device_Info_Terminal");
        setCreativeTab(CreativeTabs.tabMisc);
        // 绑定类设备不可堆叠（每台终端持有独立 DIT_UUID）
        setMaxStackSize(1);
    }

    // ==================== 手势 1：右击机器 = 绑定（onItemUseFirst，服务端权威） ====================

    /**
     * 右击加工 GT 机器 = 绑定到本终端（onItemUseFirst 服务端权威处理并拦截，客户端放行）。
     * <p>
     * 客户端返回 false 让 C08 包发出（仿 {@link ItemNetworkQuantumTerminal} 先例）；
     * Shift 一律放行（return false，终端等效空手）——扫描开关统一由
     * {@link #onItemRightClick} 的 Shift 分支经 vanilla 回退路径触发（不在本方法拦截，
     * 否则潜行右击机器时双路径各触发一次 toggle 相互抵消导致扫描无法启动）；
     * 非 Shift 才走绑定流程：非 GT 方块放行（不提示不拦截）；GT 非加工机器 → 聊天原因
     * 并拦截（不开其 GUI）；成功路径：登记表无该机器则补登（owner=点击者）→ 数据仓加绑定
     * （尊重 {@link Config#deviceTerminalMaxMachines} 上限，超限聊天拒绝）。
     */
    @Override
    public boolean onItemUseFirst(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
        float hitX, float hitY, float hitZ) {
        // ① 客户端：返回 false 让 C08 包发出，全部逻辑交给服务端权威执行
        if (world.isRemote) {
            return false;
        }
        // ② Shift+右击机器：一律放行（终端等效空手，仿 ItemNetworkQuantumTerminal 先例）——
        // 扫描开关统一走 onItemRightClick 的 Shift 分支，此处拦截会导致 vanilla 回退路径
        // 再触发一次 toggle 相互抵消（扫描无法启动）
        if (player.isSneaking()) {
            return false;
        }
        // ③ 非 GT 方块：放行（等效空手交互，不提示不拦截）
        TileEntity te = world.getTileEntity(x, y, z);
        if (!(te instanceof IGregTechTileEntity)) {
            return false;
        }
        // ④ GT 非工作机器（D1 不通过，含发电常规机/太阳能/避雷针在内均可绑定）：提示原因并拦截
        IMetaTileEntity mte = ((IGregTechTileEntity) te).getMetaTileEntity();
        if (!DeviceMachineTypes.isWorkingMachine(mte)) {
            sendMessage(player, "gtswn.device.chat.not_machine");
            return true;
        }
        // ⑤ 加工机器：登记表无则补登（owner=点击者；放置事件未覆盖到的旁路路径在此兜底）
        int dim = world.provider.dimensionId;
        String key = DeviceRegistryData.makeKey(dim, x, y, z);
        String localName = mte.getLocalName();
        DeviceRegistryData registry = DeviceRegistryData.get(world);
        if (registry.getEntry(key) == null) {
            registry.register(key, player.getUniqueID(), localName);
        }
        UUID terminalId = getOrCreateTerminalId(stack);
        DeviceTerminalDataStore store = DeviceTerminalDataStore.get(world);
        AddResult result = store.addBinding(
            terminalId,
            key,
            localName,
            dim,
            x,
            y,
            z,
            DeviceMachineTypes.isGeneratorMachine(mte) ? DeviceTerminalDataStore.POWER_TYPE_GENERATE
                : DeviceTerminalDataStore.POWER_TYPE_CONSUME);
        switch (result) {
            case SUCCESS:
                sendMessage(player, "gtswn.device.chat.bound", localName);
                break;
            case ALREADY_BOUND:
                sendMessage(player, "gtswn.device.chat.already_bound");
                break;
            case LIMIT_REACHED:
                sendMessage(player, "gtswn.device.chat.limit", Config.deviceTerminalMaxMachines);
                break;
            default:
                break;
        }
        return true;
    }

    // ==================== 手势 2/3：右击空气（onItemRightClick） ====================

    /**
     * 右击空气手势。
     * <ul>
     * <li>Shift 分支 = 扫描开关（阶段 C 接线）：服务端移交 {@link DeviceScanManager}——
     * 进行中=取消，否则 5s 逐秒倒计时后「主线程快照（当前维度） → 后台单线程过滤合并 → 结果队列 →
     * ServerTick END 排水应用」</li>
     * <li>普通分支 = 打开终端 GUI（阶段 E 接线）：客户端经 @SidedProxy 本地
     * {@code displayGuiScreen} 打开（仿量子终端 v1.6.26 纯客户端路径，不触碰服务端容器）；
     * 服务端同拍确保 DIT_UUID 存在并<b>显式 S2FPacketSetSlot 同步该槽位</b>——GUI 锚点解析
     * 依赖客户端 NBT 的 UUID，而 1.7.10 原地 NBT 变更不会自动推送（见 LaserHatchUtil 先例），
     * 同步后客户端 GUI 的逐 tick 只读重解析即可自动锚定</li>
     * </ul>
     */
    @Override
    public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {
        if (world.isRemote) {
            // 客户端：非 Shift 右击空气 = 本地打开终端 GUI（Shift=扫描开关走服务端权威分支）
            if (!player.isSneaking()) {
                GTSimpleWirelessNetwork.proxy.openDeviceInfoTerminalGui();
            }
            return stack;
        }
        if (player.isSneaking()) {
            // 扫描开关（服务端权威；终端 UUID 解析后移交 DeviceScanManager）
            if (player instanceof EntityPlayerMP) {
                DeviceScanManager.toggleScan((EntityPlayerMP) player, getOrCreateTerminalId(stack));
            }
            return stack;
        }
        // 服务端非 Shift 分支：确保 DIT_UUID 存在（首用生成）并显式同步手持槽位到客户端
        // （GUI 锚点/UI 偏好初始读取依赖客户端 NBT；同步动作包服务端写回的偏好也借此通道校正）
        getOrCreateTerminalId(stack);
        if (player instanceof EntityPlayerMP playerMP) {
            playerMP.playerNetServerHandler.sendPacket(new S2FPacketSetSlot(0, player.inventory.currentItem, stack));
        }
        return stack;
    }

    /**
     * 物品提示四行（阶段 F1）：开 UI / 绑定 / 扫描 / Ctrl 解绑与传送消耗。
     * <p>
     * 文案与灰字风格经 lang 键 {@code item.gtswn.deviceInfoTerminal.tooltip.l1-l4}（值自带
     * §7/§f 颜色码，照量子终端 addInformation 手势表风格），双语文案由语言文件提供。
     */
    @Override
    public void addInformation(ItemStack stack, EntityPlayer player, List<String> list, boolean advanced) {
        list.add(StatCollector.translateToLocal("item.gtswn.deviceInfoTerminal.tooltip.l1"));
        list.add(StatCollector.translateToLocal("item.gtswn.deviceInfoTerminal.tooltip.l2"));
        list.add(StatCollector.translateToLocal("item.gtswn.deviceInfoTerminal.tooltip.l3"));
        list.add(StatCollector.translateToLocal("item.gtswn.deviceInfoTerminal.tooltip.l4"));
    }

    // ==================== NBT 读写工具（静态，GUI/事件/动作包共用） ====================

    /**
     * 取或生成本终端实例 UUID（首用生成并写入 NBT）。
     * <p>
     * 该 UUID 是 {@link DeviceTerminalDataStore} 的绑定锚点；服务端调用会写入 NBT
     * 并随物品同步，客户端读已同步 NBT 不再生成。
     */
    public static UUID getOrCreateTerminalId(ItemStack stack) {
        NBTTagCompound tag = ensureNBT(stack);
        String id = tag.getString(NBT_UUID);
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID()
                .toString();
            tag.setString(NBT_UUID, id);
        }
        return UUID.fromString(id);
    }

    /**
     * 只读解析终端 UUID（缺失 / 非法返回 null）。
     * <p>
     * 客户端 GUI 锚点解析专用（{@code ClientProxy.openDeviceInfoTerminalGui} 与
     * {@code GuiDeviceInfoTerminal} 逐 tick 重解析）：不生成、不写 NBT——服务端才是
     * UUID 的权威生成方，客户端本地生成会与服务端 getOrCreate 产生分叉导致缓存锚点错位。
     */
    public static UUID readTerminalId(ItemStack stack) {
        if (stack == null || stack.stackTagCompound == null) {
            return null;
        }
        String id = stack.stackTagCompound.getString(NBT_UUID);
        if (id == null || id.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 读取排序列偏好（非法 / 缺省回退 AVG） */
    public static String getSortColumn(ItemStack stack) {
        if (stack.stackTagCompound == null) {
            return SORT_AVG;
        }
        String value = stack.stackTagCompound.getString(NBT_SORT_COLUMN);
        if (SORT_NAME.equals(value) || SORT_STATE.equals(value)
            || SORT_INST.equals(value)
            || SORT_AVG.equals(value)
            || SORT_POS.equals(value)) {
            return value;
        }
        return SORT_AVG;
    }

    /** 写入排序列偏好（服务端权威，阶段 D 动作包调用） */
    public static void setSortColumn(ItemStack stack, String column) {
        ensureNBT(stack).setString(NBT_SORT_COLUMN, column);
    }

    /** 读取排序方向偏好（非法 / 缺省回退 DESC） */
    public static String getSortDirection(ItemStack stack) {
        if (stack.stackTagCompound == null) {
            return DIR_DESC;
        }
        String value = stack.stackTagCompound.getString(NBT_SORT_DIR);
        return DIR_ASC.equals(value) ? DIR_ASC : DIR_DESC;
    }

    /** 写入排序方向偏好（服务端权威，阶段 D 动作包调用） */
    public static void setSortDirection(ItemStack stack, String direction) {
        ensureNBT(stack).setString(NBT_SORT_DIR, direction);
    }

    /** 读取计数法偏好（非法 / 缺省回退 NORMAL） */
    public static String getCountMode(ItemStack stack) {
        if (stack.stackTagCompound == null) {
            return NUM_NORMAL;
        }
        String value = stack.stackTagCompound.getString(NBT_NUM_FORMAT);
        if (NUM_NORMAL.equals(value) || NUM_SCIENTIFIC.equals(value)
            || NUM_THOUSANDS.equals(value)
            || NUM_VOLTAGE.equals(value)) {
            return value;
        }
        return NUM_NORMAL;
    }

    /** 写入计数法偏好（服务端权威，阶段 D 动作包调用） */
    public static void setCountMode(ItemStack stack, String mode) {
        ensureNBT(stack).setString(NBT_NUM_FORMAT, mode);
    }

    /** 确保 ItemStack NBT 存在 */
    private static NBTTagCompound ensureNBT(ItemStack stack) {
        if (stack.stackTagCompound == null) {
            stack.stackTagCompound = new NBTTagCompound();
        }
        return stack.stackTagCompound;
    }

    /** 服务端向玩家发送本地化聊天提示（仅服务端调用） */
    private static void sendMessage(EntityPlayer player, String key, Object... args) {
        player.addChatMessage(new ChatComponentText(StatCollector.translateToLocalFormatted(key, args)));
    }
}
