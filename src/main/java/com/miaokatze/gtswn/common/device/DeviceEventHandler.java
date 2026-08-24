package com.miaokatze.gtswn.common.device;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.StatCollector;
import net.minecraftforge.event.world.BlockEvent;

import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.config.Config;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.interfaces.tileentity.IMachineProgress;
import gregtech.api.interfaces.tileentity.RecipeMapWorkable;

/**
 * 设备信息终端事件处理器（阶段 B，双总线注册见 CommonProxy）。
 * <ul>
 * <li>Forge 事件总线：PlaceEvent（可工作机器登记 + 放置者终端自动绑定）/
 * BreakEvent（出册 + 级联解除所有终端绑定）</li>
 * <li>FML 事件总线：PlayerLoggedIn / PlayerLoggedOut（维护活跃终端索引，供阶段 C 采样调度）</li>
 * </ul>
 * <p>
 * 性能口径：全部处理只做点查（登记表单键）与终端 Map 遍历（removeKeyFromAll），
 * 无世界 / 区块 / 实体遍历。PlaceEvent 时 MTE 尚未挂载则跳过（拿不到就跳过，
 * 完整登记由扫描（阶段 C）与终端右击补登兜底）。
 */
public class DeviceEventHandler {

    // ==================== 放置：登记 + 自动绑定 ====================

    /**
     * 方块放置事件：可工作 GT 机器 → 登记（owner=放置者，localName=机器本地名）；
     * 放置者背包（含热栏）内的设备信息终端逐台自动绑定该机器。
     */
    @SubscribeEvent
    public void onBlockPlace(BlockEvent.PlaceEvent event) {
        if (event.world.isRemote) {
            return;
        }
        // PlaceEvent 在 setBlock 后触发，TE 已存在但 GT 的 MTE 可能尚未挂载：拿不到就跳过
        TileEntity te = event.world.getTileEntity(event.x, event.y, event.z);
        if (!(te instanceof IGregTechTileEntity)) {
            return;
        }
        IMetaTileEntity mte = ((IGregTechTileEntity) te).getMetaTileEntity();
        if (mte == null || !(mte instanceof RecipeMapWorkable) || !(mte instanceof IMachineProgress)) {
            return;
        }
        int dim = event.world.provider.dimensionId;
        String key = DeviceRegistryData.makeKey(dim, event.x, event.y, event.z);
        String localName = mte.getLocalName();
        // 登记 / 覆盖（重放同位机器刷新 owner 与显示名，幂等）
        DeviceRegistryData.get(event.world)
            .register(key, event.player.getUniqueID(), localName);
        // 放置者背包内的终端逐台自动绑定 + 轻提示（同时补登记活跃索引，覆盖会话内新获得终端）
        DeviceTerminalDataStore store = DeviceTerminalDataStore.get(event.world);
        for (UUID terminalId : collectTerminalIds(event.player)) {
            DeviceTerminalDataStore.ensureActiveTerminal(event.player.getUniqueID(), terminalId);
            DeviceTerminalDataStore.AddResult result = store
                .addBinding(terminalId, key, localName, dim, event.x, event.y, event.z);
            switch (result) {
                case SUCCESS:
                    sendMessage(event.player, "gtswn.device.chat.auto_bound", localName);
                    break;
                case LIMIT_REACHED:
                    sendMessage(event.player, "gtswn.device.chat.limit", Config.deviceTerminalMaxMachines);
                    break;
                case ALREADY_BOUND:
                default:
                    // 已绑定：静默（重复放置常见，避免刷屏）
                    break;
            }
        }
    }

    // ==================== 破坏：出册 + 级联解绑 ====================

    /**
     * 方块破坏事件：已登记机器 → 登记表出册 + 遍历所有终端删除该键（version++）。
     * 只在确系已登记时级联，普通方块破坏零开销。
     */
    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.world.isRemote) {
            return;
        }
        String key = DeviceRegistryData.makeKey(event.world.provider.dimensionId, event.x, event.y, event.z);
        DeviceRegistryData registry = DeviceRegistryData.get(event.world);
        if (registry.unregister(key)) {
            DeviceTerminalDataStore.get(event.world)
                .removeKeyFromAll(key);
        }
    }

    // ==================== 登录 / 登出：活跃终端索引 ====================

    /**
     * 玩家登录：扫描背包（含热栏）收集终端 UUID，登记活跃索引（阶段 C 采样调度只采活跃终端）。
     */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player.worldObj.isRemote) {
            return;
        }
        DeviceTerminalDataStore.onPlayerLoggedIn(event.player.getUniqueID(), collectTerminalIds(event.player));
    }

    /**
     * 玩家登录出：移除活跃索引（无人持有的终端停止采样；扫描状态由阶段 C 登出清理接管）。
     */
    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player.worldObj.isRemote) {
            return;
        }
        DeviceTerminalDataStore.onPlayerLoggedOut(event.player.getUniqueID());
    }

    // ==================== 工具 ====================

    /**
     * 收集玩家背包（含热栏 0-8，1.7.10 无副手）内全部终端实例 UUID。
     * 服务端调用：首用终端会现场生成并写入 DIT_UUID。
     */
    private static Set<UUID> collectTerminalIds(EntityPlayer player) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (ItemStack stack : player.inventory.mainInventory) {
            if (stack != null && stack.getItem() instanceof ItemDeviceInfoTerminal) {
                ids.add(ItemDeviceInfoTerminal.getOrCreateTerminalId(stack));
            }
        }
        return ids;
    }

    /** 服务端向玩家发送本地化聊天提示（仅服务端调用） */
    private static void sendMessage(EntityPlayer player, String key, Object... args) {
        player.addChatMessage(new ChatComponentText(StatCollector.translateToLocalFormatted(key, args)));
    }
}
