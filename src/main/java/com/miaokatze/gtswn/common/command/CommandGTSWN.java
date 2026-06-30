package com.miaokatze.gtswn.common.command;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import gregtech.common.misc.WirelessNetworkManager;

/**
 * GTSWN 模组命令
 * <p>
 * 用法: /gtswn global_energy_trans &lt;fromUUID&gt; &lt;toUUID&gt;
 * <p>
 * 将 fromUUID 网络的所有 EU 一次性转移到 toUUID 网络。
 * 用于玩家换账户(正版转第三方等)导致 UUID 变化后,把旧 UUID 网络的 EU 迁移到新 UUID 网络。
 * 仅管理员(OP等级4)可用。
 */
public class CommandGTSWN extends CommandBase {

    private static final String SUBCMD_TRANSFER = "global_energy_trans";

    @Override
    public String getCommandName() {
        return "gtswn";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/gtswn " + SUBCMD_TRANSFER + " <fromUUID> <toUUID>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 4;
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, SUBCMD_TRANSFER);
        }
        return null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 3 || !SUBCMD_TRANSFER.equals(args[0])) {
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
}
