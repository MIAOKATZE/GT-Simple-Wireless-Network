package com.miaokatze.gtswn.common.items;

import java.util.List;
import java.util.UUID;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.EnumAction;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.server.S2FPacketSetSlot;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.device.DeviceMachineIdentity;
import com.miaokatze.gtswn.common.device.DeviceMachineTypes;
import com.miaokatze.gtswn.common.device.DevicePowerCapabilities;
import com.miaokatze.gtswn.common.device.DeviceRegistryData;
import com.miaokatze.gtswn.common.device.DeviceScanManager;
import com.miaokatze.gtswn.common.device.DeviceTerminalDataStore;
import com.miaokatze.gtswn.common.device.DeviceTerminalDataStore.AddResult;
import com.miaokatze.gtswn.common.device.DeviceTerminalRecognitionMode;
import com.miaokatze.gtswn.common.util.DeviceTooltip;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import appeng.api.features.IWirelessTermHandler;
import appeng.api.util.IConfigManager;
import appeng.util.ConfigManager;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/**
 * 设备信息终端（实施计划阶段 A：物品 + NBT 偏好 + 机器绑定手势）。
 * <p>
 * 手势表：
 * <ul>
 * <li>可视化关闭时右击<b>加工 GT 机器</b> = 绑定到本终端：onItemUseFirst 服务端权威
 * （客户端返回 false 放行让 C08 包发出、服务端返回 true 拦截，仿
 * {@link ItemNetworkQuantumTerminal#onItemUseFirst} 先例；登记表无则补登 owner=点击者）</li>
 * <li>右击<b>GT 非加工机器</b> = not_machine 提示并拦截（不开其 GUI）；右击<b>非 GT 方块</b>
 * = 放行（等效空手交互）</li>
 * <li>可视化开启时普通右击瞄准绑定机器或标记 = 传送；没有目标 = 打开终端列表</li>
 * <li>右击空气 = 打开终端 GUI；Shift+右击机器循环当前终端识别方式，Shift+右击空地打开设置</li>
 * <li>Alt+右击长按一秒 = 扫描（允许瞄准方块）；Shift+Alt+右击切换可视化</li>
 * </ul>
 * <p>
 * 判别式（统一口径 D1，{@link DeviceMachineTypes#isWorkingMachine}）：
 * {@code te instanceof IGregTechTileEntity && isWorkingMachine(mte)}
 * （MTEBasicMachine ∥ MTEMultiBlockBase ∥ MTEBasicGenerator ∥ MTESolarGenerator ∥ MTELightningRod）。
 * <p>
 * NBT 结构：DIT_UUID（终端实例 UUID，首用生成，绑定数据仓锚点）+
 * UI 偏好三键（排序列 / 排序方向 / 计数法，非法值读侧回退默认）。
 */
@cpw.mods.fml.common.Optional.InterfaceList({
    @cpw.mods.fml.common.Optional.Interface(iface = "baubles.api.IBauble", modid = "Baubles"),
    @cpw.mods.fml.common.Optional.Interface(iface = "baubles.api.expanded.IBaubleExpanded", modid = "Baubles") })
public class ItemDeviceInfoTerminal extends Item
    implements baubles.api.IBauble, baubles.api.expanded.IBaubleExpanded, IWirelessTermHandler {

    @Override
    public void onUpdate(ItemStack stack, World world, net.minecraft.entity.Entity entity, int slot, boolean held) {
        com.miaokatze.gtswn.common.baubles.TerminalAcquisitionHint.observe(stack, entity);
    }

    /** 仅供社区按类型识别，不接管 AE 无线终端。 */
    @Override
    public boolean canHandle(ItemStack stack) {
        return false;
    }

    /** 不提供 AE 能源判定。 */
    @Override
    public boolean hasPower(EntityPlayer player, double amount, ItemStack stack) {
        return false;
    }

    /** 不消耗 AE 能源。 */
    @Override
    public boolean usePower(EntityPlayer player, double amount, ItemStack stack) {
        return false;
    }

    /** 每次返回独立空配置，不关联终端 NBT。 */
    @Override
    public IConfigManager getConfigManager(ItemStack stack) {
        return new ConfigManager((manager, settingName, newValue) -> {});
    }

    /** 此终端不保存 AE 配对密钥。 */
    @Override
    public String getEncryptionKey(ItemStack stack) {
        return "";
    }

    /** 此终端忽略 AE 配对写入。 */
    @Override
    public void setEncryptionKey(ItemStack stack, String key, String name) {}

    // ==================== NBT 键名 ====================

    /** 终端实例 UUID（string，首用生成后常驻） */
    public static final String NBT_UUID = "DIT_UUID";

    /** Persistent visual preference; only the server's toggle action writes this value. */
    public static final String NBT_VISUAL_ENABLED = "DIT_VisualEnabled";

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

    public static boolean isVisualizationEnabled(ItemStack stack) {
        return stack != null && stack.getItem() instanceof ItemDeviceInfoTerminal
            && stack.stackTagCompound != null
            && stack.stackTagCompound.getBoolean(NBT_VISUAL_ENABLED);
    }

    public static void setVisualizationEnabled(ItemStack stack, boolean enabled) {
        ensureNBT(stack).setBoolean(NBT_VISUAL_ENABLED, enabled);
    }

    /** Held enabled terminal wins, then the current enabled UUID, then a stable inventory-slot fallback. */
    public static int findEnabledTerminalSlot(ItemStack[] inventory, int heldSlot, UUID preferred) {
        if (heldSlot >= 0 && heldSlot < inventory.length
            && isVisualizationEnabled(inventory[heldSlot])
            && readTerminalId(inventory[heldSlot]) != null) return heldSlot;
        String token = preferred == null ? null : preferred.toString();
        int fallback = -1;
        for (int i = 0; i < inventory.length; i++) {
            ItemStack stack = inventory[i];
            if (!isVisualizationEnabled(stack) || readTerminalId(stack) == null) continue;
            if (token != null && token.equals(stack.stackTagCompound.getString(NBT_UUID))) return i;
            if (fallback < 0) fallback = i;
        }
        return fallback;
    }

    @Override
    public boolean hasEffect(ItemStack stack, int pass) {
        return isVisualizationEnabled(stack) || super.hasEffect(stack, pass);
    }

    // ==================== 手势 1：右击机器 = 绑定（onItemUseFirst，服务端权威） ====================

    /**
     * 右击加工 GT 机器 = 绑定到本终端（onItemUseFirst 服务端权威处理并拦截，客户端放行）。
     * <p>
     * 客户端返回 false 让 C08 包发出（仿 {@link ItemNetworkQuantumTerminal} 先例）；
     * Shift 机器识别意图与设置由客户端分流并拦截机器交互，扫描由独立蓄力意图启动；
     * 非 Shift 才走绑定流程：非 GT 方块放行（不提示不拦截）；GT 非加工机器 → 聊天原因
     * 并拦截（不开其 GUI）；成功路径：登记表无该机器则补登（owner=点击者）→ 数据仓加绑定
     * （尊重 {@link Config#deviceTerminalMaxMachines} 上限，超限聊天拒绝）。
     */
    @Override
    public boolean onItemUseFirst(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
        float hitX, float hitY, float hitZ) {
        // Client visual/modifier intents consume use before native block activation/C08; ordinary binding sends C08.
        if (world.isRemote) {
            if (GTSimpleWirelessNetwork.proxy.handleDeviceVisualizationRightClick()) return true;
            if (player.isSneaking()) GTSimpleWirelessNetwork.proxy.openDeviceInfoTerminalSettingsGui();
            return false;
        }
        if (player instanceof EntityPlayerMP visualPlayer
            && com.miaokatze.gtswn.network.DeviceTerminalVisualActionQueue.shouldConsumeVanillaUse(visualPlayer, stack))
            return true;
        UUID profileTerminal = readTerminalId(stack);
        if (profileTerminal != null) {
            com.miaokatze.gtswn.common.monitoring.MonitoringProfiles profiles = com.miaokatze.gtswn.common.monitoring.MonitoringProfiles
                .get(world);
            if (!profiles.canModify(player.getUniqueID(), profileTerminal)) return true;
            // Profiles import a fixed authorized source set. Use a legacy terminal to prepare a new import.
            if (!player.isSneaking() && profiles.profileForTerminal(profileTerminal) != null) return true;
        }
        // Native/server callers retain the same mode action; the normal client sends an explicit action before C08.
        if (player.isSneaking()) {
            ensureTerminalIdSynced(player, stack);
            TileEntity clicked = world.getTileEntity(x, y, z);
            if (player instanceof EntityPlayerMP modePlayer && clicked instanceof IGregTechTileEntity
                && DeviceMachineTypes.isWorkingMachine(((IGregTechTileEntity) clicked).getMetaTileEntity()))
                DeviceTerminalRecognitionMode
                    .cycle(modePlayer, stack, DeviceRegistryData.makeKey(world.provider.dimensionId, x, y, z));
            return true;
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
        DeviceRegistryData.DeviceEntry known = registry.getEntry(key);
        if (known == null) {
            registry.register(key, player.getUniqueID(), localName, ((IGregTechTileEntity) te).getMetaTileID());
        } else if (known.metaTileId != ((IGregTechTileEntity) te).getMetaTileID()
            || !java.util.Objects.equals(known.localName, localName)) {
                registry.register(key, known.owner, localName, ((IGregTechTileEntity) te).getMetaTileID());
            }
        UUID terminalId = ensureTerminalIdSynced(player, stack);
        // 首用生成 UUID 后立即同步手持槽：GUI 锚点/客户端缓存键依赖客户端 NBT 的 UUID，
        // 1.7.10 原地 NBT 变更不自动推送（否则新终端扫描绑定后 GUI 因客户端 UUID 缺失永远空列表）
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
                : DeviceTerminalDataStore.POWER_TYPE_CONSUME,
            ((IGregTechTileEntity) te).getMetaTileID(),
            DeviceMachineIdentity.linkNodeType(mte),
            DevicePowerCapabilities.capabilityMask(mte));
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

    /** Plain visual clicks travel when aimed or open the list; Shift opens settings; Alt charges a scan. */
    @Override
    public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {
        if (world.isRemote) {
            if (GTSimpleWirelessNetwork.proxy.handleDeviceVisualizationRightClick()) return stack;
            // 原纯GuiScreen保持本地打开，不污染背包容器windowId。
            if (player.isSneaking()) {
                GTSimpleWirelessNetwork.proxy.openDeviceInfoTerminalSettingsGui();
            } else {
                GTSimpleWirelessNetwork.proxy.openDeviceInfoTerminalGui();
            }
            return stack;
        }
        if (player instanceof EntityPlayerMP visualPlayer
            && com.miaokatze.gtswn.network.DeviceTerminalVisualActionQueue.shouldConsumeVanillaUse(visualPlayer, stack))
            return stack;
        ensureTerminalIdSynced(player, stack);
        return stack;
    }

    @Override
    public EnumAction getItemUseAction(ItemStack stack) {
        return EnumAction.bow;
    }

    @Override
    public int getMaxItemUseDuration(ItemStack stack) {
        return 72000;
    }

    public static void startChargedScan(EntityPlayerMP player, ItemStack stack) {
        DeviceScanManager.startScan(player, ensureTerminalIdSynced(player, stack));
    }

    @Override
    public void addInformation(ItemStack stack, EntityPlayer player, List<String> list, boolean advanced) {
        com.miaokatze.gtswn.common.baubles.TerminalBaubles.addSlotTooltip(list, 2);
        DeviceTooltip.state(list);
        DeviceTooltip.add(
            list,
            readTerminalId(stack) == null ? "gtswn.tooltip.device.new" : "gtswn.tooltip.device.initialized",
            isVisualizationEnabled(stack) ? "gtswn.tooltip.device.visual.on" : "gtswn.tooltip.device.visual.off");
        DeviceTooltip.description(
            list,
            "gtswn.tooltip.device.description",
            "gtswn.tooltip.device.bindings",
            "gtswn.tooltip.device.visual.inventory");
        DeviceTooltip.controls(
            list,
            "item.gtswn.deviceInfoTerminal.tooltip.l1",
            "item.gtswn.deviceInfoTerminal.tooltip.l2",
            "item.gtswn.deviceInfoTerminal.tooltip.l3",
            "item.gtswn.deviceInfoTerminal.tooltip.visual",
            "item.gtswn.deviceInfoTerminal.tooltip.scan.details",
            "item.gtswn.deviceInfoTerminal.tooltip.scan.repeat",
            "item.gtswn.deviceInfoTerminal.tooltip.l4",
            "gtswn.tooltip.device.usage.name");
        DeviceTooltip.finish(list);
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

    /**
     * 服务端显式同步手持槽的终端 NBT 到客户端（首用生成 UUID / 偏好写回后调用）。
     * <p>
     * 1.7.10 {@code S2FPacketSetSlot} 对 windowId=0 使用 {@code ContainerPlayer} 的
     * <b>容器槽序</b>（0=合成结果，1-4=合成矩阵，5-8=护甲，9-35=主背包，36-44=快捷栏），
     * 快捷栏槽 {@code inventory.currentItem}（0-8）必须映射为 {@code 36 + currentItem}——
     * 直发 currentItem 会打到合成格/护甲槽，终端 NBT 从未真正到达客户端（v1.7.15 修复：
     * 新终端扫描绑定成功但 GUI 因客户端 UUID 缺失而永远空列表，仅老终端/重登后正常）。
     */
    private static void syncHeldSlot(EntityPlayerMP player, ItemStack stack) {
        player.playerNetServerHandler.sendPacket(new S2FPacketSetSlot(0, 36 + player.inventory.currentItem, stack));
    }

    /** Profile recovery updates only the actual server-held item. */
    public static void syncHeldTerminalSlot(EntityPlayerMP player) {
        syncHeldSlot(player, player.getHeldItem());
    }

    /** Reusing unchanged NBT must not replace the client's held stack/re-equip the terminal on each right-click. */
    public static UUID ensureTerminalIdSynced(EntityPlayer player, ItemStack stack) {
        UUID before = readTerminalId(stack);
        if (before != null) return before;
        if (stack.stackTagCompound != null) stack.stackTagCompound.removeTag(NBT_UUID);
        UUID created = getOrCreateTerminalId(stack);
        if (player instanceof EntityPlayerMP playerMP) syncHeldSlot(playerMP, stack);
        return created;
    }

    /** 服务端向玩家发送本地化聊天提示（仅服务端调用） */
    private static void sendMessage(EntityPlayer player, String key, Object... args) {
        player.addChatMessage(new ChatComponentTranslation(key, args));
    }

    @Override
    @cpw.mods.fml.common.Optional.Method(modid = "Baubles")
    public baubles.api.BaubleType getBaubleType(ItemStack stack) {
        return baubles.api.BaubleType.UNIVERSAL;
    }

    @Override
    @cpw.mods.fml.common.Optional.Method(modid = "Baubles")
    public String[] getBaubleTypes(ItemStack stack) {
        return new String[] { com.miaokatze.gtswn.common.baubles.TerminalBaubles.TYPES[2] };
    }

    @Override
    @cpw.mods.fml.common.Optional.Method(modid = "Baubles")
    public void onWornTick(ItemStack stack, net.minecraft.entity.EntityLivingBase player) {
        com.miaokatze.gtswn.common.baubles.TerminalAcquisitionHint.observe(stack, player);
    }

    @Override
    @cpw.mods.fml.common.Optional.Method(modid = "Baubles")
    public void onEquipped(ItemStack stack, net.minecraft.entity.EntityLivingBase player) {
        com.miaokatze.gtswn.common.baubles.TerminalBaubles.onEquipped(stack, player);
    }

    @Override
    @cpw.mods.fml.common.Optional.Method(modid = "Baubles")
    public void onUnequipped(ItemStack stack, net.minecraft.entity.EntityLivingBase player) {}

    @Override
    @cpw.mods.fml.common.Optional.Method(modid = "Baubles")
    public boolean canEquip(ItemStack stack, net.minecraft.entity.EntityLivingBase player) {
        return stack != null && stack.stackSize == 1;
    }

    @Override
    @cpw.mods.fml.common.Optional.Method(modid = "Baubles")
    public boolean canUnequip(ItemStack stack, net.minecraft.entity.EntityLivingBase player) {
        return true;
    }
}
