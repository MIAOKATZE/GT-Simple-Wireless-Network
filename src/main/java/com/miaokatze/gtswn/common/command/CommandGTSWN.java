package com.miaokatze.gtswn.common.command;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.PlayerNotFoundException;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.panel.NetworkInfoDataStore;
import com.miaokatze.gtswn.config.Config;

import gregtech.common.misc.WirelessNetworkManager;
import gregtech.common.misc.spaceprojects.SpaceProjectManager;

/**
 * GTSWN 模组命令
 * <p>
 * 子命令:
 * <ul>
 * <li>/gtswn global_energy_trans &lt;fromUUID&gt; &lt;toUUID&gt; — EU 迁移</li>
 * <li>/gtswn global_energy_join &lt;memberUUID&gt; &lt;leaderUUID&gt; — 加入无线网络</li>
 * <li>/gtswn cleanup_info_data &lt;all|player&gt; — 清理网络信息屏历史数据（v1.5.15 新增）</li>
 * </ul>
 * 仅管理员(OP等级4)可用。
 */
public class CommandGTSWN extends CommandBase {

    private static final String SUBCMD_TRANSFER = "global_energy_trans";
    private static final String SUBCMD_JOIN = "global_energy_join";
    private static final String SUBCMD_CLEANUP_INFO = "cleanup_info_data";

    @Override
    public String getCommandName() {
        return "gtswn";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/gtswn " + SUBCMD_TRANSFER
            + " <fromUUID> <toUUID>"
            + " | /gtswn "
            + SUBCMD_JOIN
            + " <memberUUID> <leaderUUID>"
            + " | /gtswn "
            + SUBCMD_CLEANUP_INFO
            + " <all|player>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 4;
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, SUBCMD_TRANSFER, SUBCMD_JOIN, SUBCMD_CLEANUP_INFO);
        }
        // cleanup_info_data 子命令补全：all + 在线玩家名
        if (args.length == 2 && SUBCMD_CLEANUP_INFO.equals(args[0])) {
            String[] players = MinecraftServer.getServer()
                .getAllUsernames();
            String[] candidates = new String[players.length + 1];
            candidates[0] = "all";
            System.arraycopy(players, 0, candidates, 1, players.length);
            return getListOfStringsMatchingLastWord(args, candidates);
        }
        return null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 1) {
            throw new WrongUsageException(getCommandUsage(sender));
        }
        if (SUBCMD_TRANSFER.equals(args[0])) {
            processTransfer(sender, args);
        } else if (SUBCMD_JOIN.equals(args[0])) {
            processJoin(sender, args);
        } else if (SUBCMD_CLEANUP_INFO.equals(args[0])) {
            processCleanupInfoData(sender, args);
        } else {
            throw new WrongUsageException(getCommandUsage(sender));
        }
    }

    private void processTransfer(ICommandSender sender, String[] args) {
        if (args.length < 3) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        UUID fromUUID;
        UUID toUUID;
        try {
            fromUUID = UUID.fromString(args[1]);
            toUUID = UUID.fromString(args[2]);
        } catch (IllegalArgumentException e) {
            sender.addChatMessage(
                new ChatComponentText(EnumChatFormatting.RED + "UUID 格式错误,请检查参数。用法: " + getCommandUsage(sender)));
            return;
        }

        if (fromUUID.equals(toUUID)) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + "源 UUID 与目标 UUID 相同,无需迁移。"));
            return;
        }

        BigInteger eu = WirelessNetworkManager.getUserEU(fromUUID);
        if (eu.signum() <= 0) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + "源网络无 EU 可迁移: " + fromUUID));
            return;
        }

        // v1.2.1 修复：先扣除源，再增加目标，避免非原子性导致 EU 凭空增加
        // 原代码先增加目标、再清零源，若第二步失败 EU 会凭空增加；现改为先扣除源（验证足够），再增加目标
        // 若第一步失败 EU 仅损失（优于凭空增加）。重新读取源当前 EU 以防上面读取后被其他操作改变
        BigInteger sourceEU = WirelessNetworkManager.getUserEU(fromUUID);
        if (sourceEU.compareTo(eu) < 0) {
            // 源不足（可能在上面读取后被消耗），转全部当前余额
            eu = sourceEU;
        }
        WirelessNetworkManager.setUserEU(fromUUID, sourceEU.subtract(eu));
        BigInteger targetEU = WirelessNetworkManager.getUserEU(toUUID);
        WirelessNetworkManager.setUserEU(toUUID, targetEU.add(eu));

        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "迁移成功: " + eu + " EU"));
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "从: " + fromUUID));
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "到: " + toUUID));
        sender.addChatMessage(
            new ChatComponentText(
                EnumChatFormatting.AQUA + "目标网络当前总量: " + WirelessNetworkManager.getUserEU(toUUID) + " EU"));
    }

    private void processJoin(ICommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.addChatMessage(
                new ChatComponentText(
                    EnumChatFormatting.RED + StatCollector.translateToLocal("gtswn.command.join.usage")));
            return;
        }

        UUID memberUUID;
        UUID leaderUUID;
        try {
            memberUUID = UUID.fromString(args[1]);
            leaderUUID = UUID.fromString(args[2]);
        } catch (IllegalArgumentException e) {
            sender.addChatMessage(
                new ChatComponentText(EnumChatFormatting.RED + "UUID format error, please check parameters."));
            return;
        }

        // 同一UUID检查
        if (memberUUID.equals(leaderUUID)) {
            sender.addChatMessage(
                new ChatComponentText(
                    String.format(StatCollector.translateToLocal("gtswn.command.join.same_uuid"), memberUUID)));
            return;
        }

        // NPE防护: 调用 getLeader 内部会 checkOrCreateTeam，确保 leaderUUID 已注册
        UUID leadersLeader = SpaceProjectManager.getLeader(leaderUUID);

        // 循环检测: 如果 memberUUID 是 leaderUUID 的队长，形成循环，拒绝
        if (leadersLeader.equals(memberUUID)) {
            sender.addChatMessage(
                new ChatComponentText(
                    String.format(
                        StatCollector.translateToLocal("gtswn.command.join.cycle_detect"),
                        memberUUID,
                        leaderUUID,
                        memberUUID,
                        leaderUUID)));
            return;
        }

        // 级联提示: 如果 leaderUUID 自己也在别人的队里，提示实际加入的网络
        if (!leadersLeader.equals(leaderUUID)) {
            sender.addChatMessage(
                new ChatComponentText(
                    String.format(
                        StatCollector.translateToLocal("gtswn.command.join.cascade_warn"),
                        leaderUUID,
                        leadersLeader,
                        memberUUID,
                        leadersLeader)));
        }

        // 执行加入
        SpaceProjectManager.putInTeam(memberUUID, leaderUUID);

        // 成功反馈
        sender.addChatMessage(
            new ChatComponentText(
                String.format(StatCollector.translateToLocal("gtswn.command.join.success"), memberUUID, leaderUUID)));

        // 显示目标网络EU余额
        BigInteger targetEU = WirelessNetworkManager.getUserEU(leaderUUID);
        sender.addChatMessage(
            new ChatComponentText(
                String.format(StatCollector.translateToLocal("gtswn.command.join.network_eu"), targetEU)));
    }

    /**
     * 处理 cleanup_info_data 子命令（v1.5.15 新增）。
     * <p>
     * 清理网络信息屏历史数据集，支持两种模式：
     * <ul>
     * <li>{@code /gtswn cleanup_info_data all} — 清理所有超过 {@link Config#keepHistoryDays} 天未采样的数据集</li>
     * <li>{@code /gtswn cleanup_info_data <player>} — 清理指定在线玩家（按 UUID）的数据集</li>
     * </ul>
     * 注意：清理操作不可逆，被清理的玩家下次放置信息屏时会从空数据集重新开始采集。
     *
     * @param sender 命令发送者
     * @param args   原始参数数组，args[0]=cleanup_info_data，args[1]=all|player
     */
    private void processCleanupInfoData(ICommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.addChatMessage(
                new ChatComponentText(EnumChatFormatting.RED + "用法: /gtswn " + SUBCMD_CLEANUP_INFO + " <all|player>"));
            return;
        }

        // 获取 overworld（NetworkInfoDataStore 存储在 perWorldStorage 中）
        World overworld = MinecraftServer.getServer()
            .worldServerForDimension(0);
        if (overworld == null) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "无法获取 overworld，清理失败。"));
            return;
        }

        NetworkInfoDataStore store = NetworkInfoDataStore.get(overworld);
        String target = args[1];

        if ("all".equalsIgnoreCase(target)) {
            // 全量清理模式：按 keepHistoryDays 计算 cutoff
            if (Config.keepHistoryDays <= 0) {
                sender.addChatMessage(
                    new ChatComponentText(EnumChatFormatting.YELLOW + "keepHistoryDays=0（永不清理），强制全量清理所有过期数据集。"));
                // 即使配置为 0，命令也执行清理（命令优先级高于配置）
                // 用一个极长的时间阈值，确保所有"有 lastSampleTimeMs 记录"的数据集都被判断
                // 但 lastSampleTimeMs=0（从未采样或旧存档）的数据集也会被清理（0 < cutoffMs 恒成立）
            }
            long cutoffMs = System.currentTimeMillis() - Config.keepHistoryDays * 24L * 3600L * 1000L;
            int removed = store.cleanupStale(cutoffMs);
            sender.addChatMessage(
                new ChatComponentText(
                    EnumChatFormatting.GREEN + "[cleanup_info_data] 已清理 "
                        + removed
                        + " 个数据集（阈值 "
                        + Config.keepHistoryDays
                        + " 天）。"));
        } else {
            // 指定玩家清理模式：解析玩家名 → UUID → remove
            EntityPlayerMP player;
            try {
                player = getPlayer(sender, target);
            } catch (PlayerNotFoundException e) {
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "未找到玩家: " + target + "（仅支持在线玩家）"));
                return;
            }
            UUID playerUUID = player.getUniqueID();
            boolean removed = store.remove(playerUUID.toString());
            if (removed) {
                sender.addChatMessage(
                    new ChatComponentText(
                        EnumChatFormatting.GREEN + "[cleanup_info_data] 已移除玩家 "
                            + target
                            + " ("
                            + playerUUID
                            + ") 的网络信息屏数据集。"));
            } else {
                sender.addChatMessage(
                    new ChatComponentText(
                        EnumChatFormatting.YELLOW + "[cleanup_info_data] 玩家 "
                            + target
                            + " ("
                            + playerUUID
                            + ") 无数据集可移除。"));
            }
        }
    }
}
