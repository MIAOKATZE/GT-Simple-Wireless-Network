package com.miaokatze.gtswn.common.command;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import gregtech.common.misc.WirelessNetworkManager;
import gregtech.common.misc.spaceprojects.SpaceProjectManager;

/**
 * GTSWN 模组命令
 * <p>
 * 子命令:
 * <ul>
 * <li>/gtswn global_energy_trans &lt;fromUUID&gt; &lt;toUUID&gt; — EU 迁移</li>
 * <li>/gtswn global_energy_join &lt;memberUUID&gt; &lt;leaderUUID&gt; — 加入无线网络</li>
 * </ul>
 * 仅管理员(OP等级4)可用。
 */
public class CommandGTSWN extends CommandBase {

    private static final String SUBCMD_TRANSFER = "global_energy_trans";
    private static final String SUBCMD_JOIN = "global_energy_join";

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
            + " <memberUUID> <leaderUUID>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 4;
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, SUBCMD_TRANSFER, SUBCMD_JOIN);
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

        BigInteger existing = WirelessNetworkManager.getUserEU(toUUID);
        WirelessNetworkManager.setUserEU(toUUID, existing.add(eu));
        WirelessNetworkManager.setUserEU(fromUUID, BigInteger.ZERO);

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
}
