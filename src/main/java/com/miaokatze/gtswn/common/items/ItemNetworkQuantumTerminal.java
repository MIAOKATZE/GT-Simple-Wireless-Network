package com.miaokatze.gtswn.common.items;

import java.util.List;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.quantum.QuantumControllerEventHandler;
import com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;
import com.miaokatze.gtswn.register.BlockRegistrar;

import appeng.api.networking.pathing.ControllerState;
import appeng.api.util.DimensionalCoord;
import appeng.me.GridAccessException;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import appeng.tile.networking.TileController;
import appeng.util.LookDirection;
import appeng.util.Platform;

/**
 * ME 网络量子终端（T3 手势逻辑完整实现，规划 plan_20260722152445.md §2 D3 / §5.1 / §7）。
 * <p>
 * 手势表（D3 已确认）：
 * <ul>
 * <li>右击<b>未量子化</b>且正常运行的控制器 = 量子化整结构（D10 洪泛）并绑定</li>
 * <li>右击<b>已量子化</b>控制器 = 绑定 / 改绑（仅写终端 NBT 锚点）</li>
 * <li>Shift+右击已量子化控制器 = 取消量子化并解绑（整结构出册 + 恢复全方向可连接 + 清除终端锚点）</li>
 * <li>右击普通方块（已绑定）= 在点击面放置「ME 网络量子节点」（D4 不消耗物品）</li>
 * <li>Shift+右击空气 = 打开终端 GUI（v1.6.2：有且仅有此路径开 GUI，见 onItemRightClick 射线守卫）</li>
 * </ul>
 * <p>
 * 双端模型（1.7.10 机制，已核实）：客户端 {@code onItemUseFirst} 返回 true 会拦截 C08 包
 * 导致服务端永远收不到交互（AE2 ToolNetworkTool 依赖此行为走自定义包）；返回 false 时
 * 客户端继续走 onBlockActivated 镜像并发 C08，服务端在 processPlayerBlockPlacement 中
 * 再次调用 onItemUseFirst。AE2 的 AEBaseTileBlock.onBlockActivated 整体被
 * {@code !w.isRemote} 门控（客户端直接落到基类 onActivated 返回 false），故客户端放行
 * 不会开出控制器 GUI。因此本物品客户端无条件 return false，全部业务逻辑仅在服务端执行。
 * <p>
 * NBT 结构（§5.1）：QT_Bound(byte) / QT_AnchorDim / QT_AnchorX/Y/Z(int) / QT_BoundName(string)。
 */
public class ItemNetworkQuantumTerminal extends Item {

    // ==================== NBT 键名（规划 §5.1） ====================

    /** 已绑定标记：1 = 已绑定（byte） */
    private static final String NBT_BOUND = "QT_Bound";

    /** 锚点控制器维度 ID */
    private static final String NBT_ANCHOR_DIM = "QT_AnchorDim";

    /** 锚点控制器坐标 */
    private static final String NBT_ANCHOR_X = "QT_AnchorX";
    private static final String NBT_ANCHOR_Y = "QT_AnchorY";
    private static final String NBT_ANCHOR_Z = "QT_AnchorZ";

    /** 绑定时维度显示名（tooltip 用） */
    private static final String NBT_BOUND_NAME = "QT_BoundName";

    /**
     * 构造函数：初始化 ME 网络量子终端的基础属性（仿 PortableWirelessNetworkMonitor）
     */
    public ItemNetworkQuantumTerminal() {
        super();
        // 设置未本地化名称 (Unlocalized Name)，用于关联语言文件
        setUnlocalizedName("MENetworkQuantumTerminal_GTswn");
        // 设置材质路径 (Texture Name)，指向 assets/gtswn/textures/items/ME_Network_Quantum_Terminal.png
        setTextureName("gtswn:ME_Network_Quantum_Terminal");
        // 设置创造模式标签页，使其能在游戏中被玩家获取
        setCreativeTab(CreativeTabs.tabMisc);
        // 设置最大堆叠数量为 1（绑定类设备不可堆叠）
        setMaxStackSize(1);
    }

    // v1.6.2：终端固定单材质（用户定夺：未绑定/绑定不再区分图标），无 registerIcons/getIconIndex 覆写

    /**
     * v1.6.2 修复：Shift+右击量子节点收回时不再误开终端 GUI。
     * <p>
     * 1.7.10 机制：潜行持物品右击方块时，若物品的 doesSneakBypassUse 返回 false，
     * 客户端会跳过方块 onBlockActivated 而直接走 sendUseItem → 服务端 onItemRightClick，
     * 导致节点收回（方块潜行分支）不执行、反而打开终端 GUI。返回 true 则潜行交互
     * 正常派发给方块 onBlockActivated，由方块执行收回逻辑。
     * 仅对量子节点返回 true，其余方块保持默认（潜行时不绕过，等效空手行为不变）。
     */
    @Override
    public boolean doesSneakBypassUse(World world, int x, int y, int z, EntityPlayer player) {
        return world.getBlock(x, y, z) == BlockRegistrar.networkQuantumNode;
    }

    // ==================== 手势 1-4：右击方块（onItemUseFirst） ====================

    /**
     * 右击方块手势分发（D3）。
     * <p>
     * 客户端直接放行（返回 false 让 C08 发出）；服务端权威处理并返回 true 拦截后续
     * onBlockActivated / onItemUse，避免控制器 GUI 被打开或物品被使用。
     */
    @Override
    public boolean onItemUseFirst(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
        float hitX, float hitY, float hitZ) {
        GTSimpleWirelessNetwork.LOG
            .debug("[量子终端] onItemUseFirst 进入 @ ({},{},{}) side={} 玩家={}", x, y, z, side, player.getCommandSenderName());
        // 客户端：返回 false 让 C08 包发出，全部逻辑交给服务端权威执行
        if (world.isRemote) {
            return false;
        }
        TileEntity te = world.getTileEntity(x, y, z);
        if (te instanceof TileController) {
            boolean result = handleControllerClick(stack, player, world, (TileController) te, x, y, z);
            GTSimpleWirelessNetwork.LOG.debug("[量子终端] handleControllerClick 返回 {}", result);
            return result;
        }
        return handleBlockClick(stack, player, world, x, y, z, side);
    }

    /**
     * 手势 1/2/3：右击控制器（服务端）。
     *
     * @return true = 已处理并拦截后续交互；false = 放行（Shift+右击未量子化控制器，等效空手）
     */
    private boolean handleControllerClick(ItemStack stack, EntityPlayer player, World world, TileController controller,
        int x, int y, int z) {
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        boolean quantized = registry.isQuantized(x, y, z);
        if (player.isSneaking()) {
            // 手势 3：Shift+右击已量子化控制器 = 取消量子化并解绑终端（v1.6.2：同步清除绑定）
            if (!quantized) {
                // 未量子化时 Shift 无取消语义：放行（等效空手点击，可正常打开控制器）
                return false;
            }
            if (!Platform.hasPermissions(new DimensionalCoord(world, x, y, z), player)) {
                sendMessage(player, "gtswn.chat.quantum.no_permission");
                return true;
            }
            dequantizeStructure(player, world, registry, x, y, z);
            clearAnchor(stack);
            return true;
        }
        if (quantized) {
            // 手势 2：右击已量子化控制器 = 绑定 / 改绑
            if (!Platform.hasPermissions(new DimensionalCoord(world, x, y, z), player)) {
                sendMessage(player, "gtswn.chat.quantum.no_permission");
                return true;
            }
            writeAnchor(stack, world, x, y, z);
            sendMessage(player, "gtswn.chat.quantum.bound", x, y, z);
            return true;
        }
        // 手势 1：右击未量子化控制器 = 量子化整结构并绑定
        if (!isControllerActive(controller)) {
            sendMessage(player, "gtswn.chat.quantum.not_active");
            return true;
        }
        if (!Platform.hasPermissions(new DimensionalCoord(world, x, y, z), player)) {
            sendMessage(player, "gtswn.chat.quantum.no_permission");
            return true;
        }
        quantizeStructure(stack, player, world, registry, x, y, z);
        return true;
    }

    /**
     * 手势 4：右击普通方块 = 放置量子节点（服务端）。
     * <p>
     * 未绑定或按住 Shift 时放行（终端等效空手，不妨碍开箱等日常交互）；
     * 已绑定时一律拦截：目标位置可替换则放置节点，不可替换则静默拦截（§7 暂无对应提示键）。
     *
     * @return true = 拦截后续交互；false = 放行
     */
    private boolean handleBlockClick(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side) {
        if (player.isSneaking()) {
            return false;
        }
        if (!isBound(stack)) {
            return false;
        }
        // 放置位置 = 点击面偏移格
        ForgeDirection face = ForgeDirection.getOrientation(side);
        int nx = x + face.offsetX;
        int ny = y + face.offsetY;
        int nz = z + face.offsetZ;
        if (!world.getBlock(nx, ny, nz)
            .isReplaceable(world, nx, ny, nz)) {
            // 位置被占用：静默拦截，防止误触发对方块功能（§7 无对应提示键，T7 可增补）
            return true;
        }
        placeQuantumNode(stack, player, world, nx, ny, nz);
        return true;
    }

    // ==================== 手势 5：右击空气（onItemRightClick） ====================

    /**
     * Shift+右击空气 = 打开终端 GUI（v1.6.2：有且仅有此路径开 GUI）。
     * <p>
     * 右击空气走 C08(side=255) 路径，双端均会调用本方法；开 GUI 属服务端权威行为
     * （openGui 由服务端发包），客户端直接返回。
     * <p>
     * 【射线守卫】潜行持本物品右击<b>任意方块</b>时，客户端因 doesSneakBypassUse=false
     * （量子节点除外）会跳过方块激活、落到 sendUseItem → 服务端同样走进本方法。
     * 此处用玩家视线射线判定：命中方块即视为方块交互（如 Shift+右击控制器取消量子化
     * 已由 onItemUseFirst 处理），直接返回不开 GUI；仅视线落空（右击空气）才开 GUI。
     * 距离取与客户端一致的手长：创造 5.0 / 生存 4.5。
     * <p>
     * v1.6.11 hotfix：原 {@code player.rayTrace(reach, 1.0F)} 在 dedicated server 抛
     * {@link NoSuchMethodError}（{@code EntityPlayer.rayTrace} 在服务端不可用），
     * 改用 AE2 {@link appeng.util.Platform#getPlayerRay} + {@link net.minecraft.world.World#rayTraceBlocks}
     * 标准视线检测；reach 由 {@code EntityPlayerMP.theItemInWorldManager.getBlockReachDistance()} 自动决定。
     */
    @Override
    public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {
        GTSimpleWirelessNetwork.LOG
            .debug("[量子终端] onItemRightClick 进入 玩家={} isRemote={}", player.getCommandSenderName(), world.isRemote);
        if (world.isRemote) {
            return stack;
        }
        if (player.isSneaking()) {
            if (!isBound(stack)) {
                sendMessage(player, "gtswn.chat.quantum.need_bind");
                return stack;
            }
            // v1.6.11 hotfix：原 player.rayTrace(reach, 1.0F) 在 dedicated server 抛 NoSuchMethodError
            // （EntityPlayer.rayTrace 在服务端不可用），改用 AE2 Platform 标准视线检测。
            // Platform.getPlayerRay 内部对 EntityPlayerMP 自动用 theItemInWorldManager.getBlockReachDistance()
            // 取 reach（创造 5.0 / 生存 4.5），与原代码意图一致；AE2 PartPlacement.java:72 / AEBaseBlock.java:219
            // 均用此模式做服务端视线检测，证明服务端兼容。
            GTSimpleWirelessNetwork.LOG.trace("[量子终端] 调用 Platform.getPlayerRay 前");
            LookDirection look = Platform.getPlayerRay(player, Platform.getEyeOffset(player));
            GTSimpleWirelessNetwork.LOG.trace("[量子终端] 调用 Platform.getPlayerRay 后");
            MovingObjectPosition hit = world.rayTraceBlocks(look.getA(), look.getB(), true);
            if (hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
                // 瞄准方块（控制器/节点/任意方块）→ 方块交互路径，不开 GUI
                return stack;
            }
            // 坐标参数对手持物品 GUI 无意义（T6 Container 取 player.getHeldItem()），传玩家位置占位
            GTSimpleWirelessNetwork.LOG.debug("[量子终端] 打开量子终端 GUI 玩家={}", player.getCommandSenderName());
            player.openGui(
                GTSimpleWirelessNetwork.instance,
                GTSimpleWirelessNetwork.GUI_QUANTUM_TERMINAL,
                world,
                (int) player.posX,
                (int) player.posY,
                (int) player.posZ);
        }
        return stack;
    }

    // ==================== 手势实现：量子化 / 取消量子化 / 放置节点 ====================

    /**
     * 量子化整结构（手势 1 主体）：洪泛入册 → 逐块应用连接过滤 → 写终端锚点 → 提示。
     * 对已量子化块幂等（重复入册/重复过滤无副作用），故与已量子化结构贴邻时自然合并。
     */
    private void quantizeStructure(ItemStack stack, EntityPlayer player, World world,
        QuantumControllerRegistry registry, int x, int y, int z) {
        // D10：洪泛 6 邻接全部相连控制器（整结构语义）
        Set<Long> structure = QuantumControllerRegistry.floodControllers(world, x, y, z);
        registry.quantizeAll(structure);
        // 逐块应用连接过滤（仅放行同伴控制器/能量接收器/石英纤维朝向面）
        for (long packed : structure) {
            QuantumControllerEventHandler.applyConnectionFilter(
                world,
                QuantumControllerRegistry.unpackX(packed),
                QuantumControllerRegistry.unpackY(packed),
                QuantumControllerRegistry.unpackZ(packed));
        }
        writeAnchor(stack, world, x, y, z);
        int totalChannels = QuantumControllerRegistry.computeTotalChannels(structure);
        if (QuantumControllerRegistry.isChannelsInfinite()) {
            sendMessage(player, "gtswn.chat.quantum.quantized_infinite", structure.size());
        } else {
            sendMessage(player, "gtswn.chat.quantum.quantized", structure.size(), totalChannels);
        }
    }

    /**
     * 取消量子化（手势 3 主体）：洪泛整结构 → 逐块出册 + 恢复全方向可连接 → 提示。
     * <p>
     * 恢复 setValidSides 不可遗漏（规划 §9）：出册即恢复，与事件处理器的
     * {@code restoreAllSides} 共用同一路径；结构本身不合法时 TileController.onNeighborChange
     * 后续会自行修正 validSides，无需额外处理。
     */
    private void dequantizeStructure(EntityPlayer player, World world, QuantumControllerRegistry registry, int x, int y,
        int z) {
        Set<Long> structure = QuantumControllerRegistry.floodControllers(world, x, y, z);
        for (long packed : structure) {
            int cx = QuantumControllerRegistry.unpackX(packed);
            int cy = QuantumControllerRegistry.unpackY(packed);
            int cz = QuantumControllerRegistry.unpackZ(packed);
            registry.dequantize(packed);
            QuantumControllerEventHandler.restoreAllSides(world, cx, cy, cz);
        }
        sendMessage(player, "gtswn.chat.quantum.dequantized");
    }

    /**
     * 放置量子节点（手势 4 主体）：setBlock → 写 TE 锚点 → 提示。
     * <p>
     * 锚点原样写入终端绑定的 dim/xyz：跨维度放置时节点离线判定由 T4 桥接逻辑处理（D6 v1
     * 不支持跨维度）；锚点控制器已失效时节点同样离线（D7）。本方法不做锚点有效性预校验。
     */
    private void placeQuantumNode(ItemStack stack, EntityPlayer player, World world, int x, int y, int z) {
        Block nodeBlock = BlockRegistrar.networkQuantumNode;
        // flag 3 = 通知客户端 + 触发邻接更新（邻接更新驱动连接过滤重算）
        world.setBlock(x, y, z, nodeBlock, 0, 3);
        // 与 ItemBlock 一致的放置音效
        world.playSoundEffect(
            x + 0.5D,
            y + 0.5D,
            z + 0.5D,
            nodeBlock.stepSound.func_150496_b(),
            (nodeBlock.stepSound.getVolume() + 1.0F) / 2.0F,
            nodeBlock.stepSound.getPitch() * 0.8F);
        TileEntity te = world.getTileEntity(x, y, z);
        if (te instanceof TileEntityNetworkQuantumNode) {
            TileEntityNetworkQuantumNode node = (TileEntityNetworkQuantumNode) te;
            NBTTagCompound tag = stack.stackTagCompound;
            node.setAnchor(
                tag.getInteger(NBT_ANCHOR_DIM),
                tag.getInteger(NBT_ANCHOR_X),
                tag.getInteger(NBT_ANCHOR_Y),
                tag.getInteger(NBT_ANCHOR_Z));
            // AE2 标准 owner 模式（AEBaseItemBlock 放置路径会 setOwner，本路径绕过必须手动补）：
            // 否则节点 GridNode.playerID 恒为 -1，带安全终端的网络上 securityCheck 恒失败，
            // 连网络主人自己的网络也桥接不上
            node.setPlacer(player);
        }
        sendMessage(player, "gtswn.chat.quantum.node_placed");
    }

    // ==================== 状态判定与 NBT 工具 ====================

    /**
     * 判定控制器是否「正常运行」（手势 1 前置条件）。
     * <p>
     * TileController 无公开 isFormed/isActive 方法（isValid 为私有字段），此处采用与其自身
     * {@code updateMeta()} 一致的状态判定路径：proxy 就绪 + 结构在线（formed 且无冲突，
     * CONTROLLER_ONLINE）+ 网络有电。
     */
    private static boolean isControllerActive(TileController controller) {
        // 经 IGridProxyable 接口调用 getProxy()：源表达式先上溯为 TileEntity——
        // 直接从 TileController cast 会触发 javac 解析 AEPowerTile 上挂的
        // Mekanism/CoFH/RotaryCraft 可选接口（不在编译 classpath，报「无法访问」）
        AENetworkProxy proxy = ((IGridProxyable) (TileEntity) controller).getProxy();
        if (!proxy.isReady()) {
            return false;
        }
        try {
            return proxy.getPath()
                .getControllerState() == ControllerState.CONTROLLER_ONLINE && proxy.getEnergy()
                    .isNetworkPowered();
        } catch (GridAccessException e) {
            // 网格未就绪（节点未创建/未入网）视为不活跃
            return false;
        }
    }

    /** 写入终端绑定锚点（含维度显示名），QT_Bound 置 1 */
    private static void writeAnchor(ItemStack stack, World world, int x, int y, int z) {
        NBTTagCompound tag = ensureNBT(stack);
        tag.setByte(NBT_BOUND, (byte) 1);
        tag.setInteger(NBT_ANCHOR_DIM, world.provider.dimensionId);
        tag.setInteger(NBT_ANCHOR_X, x);
        tag.setInteger(NBT_ANCHOR_Y, y);
        tag.setInteger(NBT_ANCHOR_Z, z);
        tag.setString(NBT_BOUND_NAME, world.provider.getDimensionName());
    }

    /**
     * 清除终端绑定（v1.6.2：Shift+右击已量子化控制器取消量子化时同步解绑）。
     * 仅移除本模组写入的 6 个键，不整段置空以兼容改名/附魔等外部 NBT。
     */
    private static void clearAnchor(ItemStack stack) {
        NBTTagCompound tag = stack.stackTagCompound;
        if (tag == null) {
            return;
        }
        tag.removeTag(NBT_BOUND);
        tag.removeTag(NBT_ANCHOR_DIM);
        tag.removeTag(NBT_ANCHOR_X);
        tag.removeTag(NBT_ANCHOR_Y);
        tag.removeTag(NBT_ANCHOR_Z);
        tag.removeTag(NBT_BOUND_NAME);
    }

    /** 终端是否已绑定（public：v1.6.1 起供客户端放置预览渲染器等外部调用） */
    public static boolean isBound(ItemStack stack) {
        return stack.stackTagCompound != null && stack.stackTagCompound.getByte(NBT_BOUND) == 1;
    }

    /**
     * 读取终端绑定的锚点（v1.6.5：客户端 GUI 缓存匹配用）。
     * <p>
     * 客户端手持物品的 NBT 由服务端同步，可直接读取。
     *
     * @return {dim, x, y, z}；未绑定或入参为空返回 {@code null}
     */
    public static int[] getAnchor(ItemStack stack) {
        if (stack == null || !isBound(stack)) {
            return null;
        }
        NBTTagCompound tag = stack.stackTagCompound;
        return new int[] { tag.getInteger(NBT_ANCHOR_DIM), tag.getInteger(NBT_ANCHOR_X), tag.getInteger(NBT_ANCHOR_Y),
            tag.getInteger(NBT_ANCHOR_Z) };
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

    // ==================== Tooltip（§7） ====================

    /**
     * 显示绑定状态与操作提示（布局仿 PortableWirelessNetworkMonitor）。
     */
    @Override
    public void addInformation(ItemStack stack, EntityPlayer player, List<String> list, boolean advanced) {
        if (isBound(stack)) {
            NBTTagCompound tag = stack.stackTagCompound;
            list.add(
                StatCollector.translateToLocalFormatted(
                    "gtswn.tooltip.quantum_terminal.bound",
                    tag.getString(NBT_BOUND_NAME),
                    tag.getInteger(NBT_ANCHOR_X),
                    tag.getInteger(NBT_ANCHOR_Y),
                    tag.getInteger(NBT_ANCHOR_Z)));
            // v1.6.1 问题 4a：已绑定时提示供电要求——量子化会过滤控制器连接面，
            // 需先接入能源元件保持网络供电，否则量子化后网络断电停机
            list.add(StatCollector.translateToLocal("gtswn.tooltip.quantum_terminal.power_requirement"));
        } else {
            list.add(StatCollector.translateToLocal("gtswn.tooltip.quantum_terminal.unbound"));
        }
        // 空行分隔 + 逐手势操作说明（v1.6.2：五行完整手势表）
        list.add("");
        list.add(StatCollector.translateToLocal("gtswn.tooltip.quantum_terminal.usage.controller"));
        list.add(StatCollector.translateToLocal("gtswn.tooltip.quantum_terminal.usage.dequantize"));
        list.add(StatCollector.translateToLocal("gtswn.tooltip.quantum_terminal.usage.place"));
        list.add(StatCollector.translateToLocal("gtswn.tooltip.quantum_terminal.usage.pickup"));
        list.add(StatCollector.translateToLocal("gtswn.tooltip.quantum_terminal.usage.gui"));
    }
}
