package com.miaokatze.gtswn.common.command;

import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.StatCollector;

import com.miaokatze.gtswn.config.Config;

/** 客户端 HUD 调整命令。 */
public class CommandGTSWNClient extends CommandBase {

    private static final String SUBCMD_X = "HudXOffset";
    private static final String SUBCMD_Y = "HudYOffset";
    private static final String SUBCMD_SCALE = "HudScale";

    @Override
    public String getCommandName() {
        return "gtswn";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/gtswn <HudXOffset|HudYOffset|HudScale> [value] | /gtswn hud <charge|eu|instant|average> <on|off>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, SUBCMD_X, SUBCMD_Y, SUBCMD_SCALE, "hud");
        }
        if (args.length == 2 && "hud".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "charge", "eu", "instant", "average");
        }
        if (args.length == 3 && "hud".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "on", "off");
        }
        return null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length > 0 && "hud".equalsIgnoreCase(args[0])) {
            processLineToggle(sender, args);
            return;
        }
        if (args.length < 1 || args.length > 2) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        String subcommand = args[0];
        if (!SUBCMD_X.equals(subcommand) && !SUBCMD_Y.equals(subcommand) && !SUBCMD_SCALE.equals(subcommand)) {
            throw new WrongUsageException(getCommandUsage(sender));
        }
        if (args.length == 1) {
            sender.addChatMessage(new ChatComponentText(subcommand + " = " + getValue(subcommand)));
            return;
        }

        try {
            if (SUBCMD_X.equals(subcommand)) {
                int value = Integer.parseInt(args[1]);
                if (value < -500 || value > 500) {
                    throw new IllegalArgumentException();
                }
                Config.hudXOffset = value;
            } else if (SUBCMD_Y.equals(subcommand)) {
                int value = Integer.parseInt(args[1]);
                if (value < -500 || value > 500) {
                    throw new IllegalArgumentException();
                }
                Config.hudYOffset = value;
            } else {
                float value = Float.parseFloat(args[1]);
                if (Float.isNaN(value) || Float.isInfinite(value) || value < 0.2f || value > 5.0f) {
                    throw new IllegalArgumentException();
                }
                Config.hudScale = value;
            }
        } catch (IllegalArgumentException e) {
            sender.addChatMessage(
                new ChatComponentText(
                    StatCollector.translateToLocalFormatted(
                        "gtswn.command.hud.invalid_value",
                        subcommand,
                        expectedRange(subcommand))));
            return;
        }

        if (!Config.saveHudConfiguration()) {
            sender
                .addChatMessage(new ChatComponentText(StatCollector.translateToLocal("gtswn.command.hud.save_failed")));
            return;
        }
        sender.addChatMessage(new ChatComponentText(subcommand + " = " + getValue(subcommand)));
    }

    private static String getValue(String subcommand) {
        if (SUBCMD_X.equals(subcommand)) {
            return Integer.toString(Config.hudXOffset);
        }
        if (SUBCMD_Y.equals(subcommand)) {
            return Integer.toString(Config.hudYOffset);
        }
        return Float.toString(Config.hudScale);
    }

    private void processLineToggle(ICommandSender sender, String[] args) {
        if (args.length != 3 || !("on".equalsIgnoreCase(args[2]) || "off".equalsIgnoreCase(args[2]))) {
            throw new WrongUsageException(getCommandUsage(sender));
        }
        boolean enabled = "on".equalsIgnoreCase(args[2]);
        if ("charge".equalsIgnoreCase(args[1])) {
            Config.hudChargeEnabled = enabled;
        } else if ("eu".equalsIgnoreCase(args[1])) {
            Config.hudEUEnabled = enabled;
        } else if ("instant".equalsIgnoreCase(args[1])) {
            Config.hudInstantEnabled = enabled;
        } else if ("average".equalsIgnoreCase(args[1])) {
            Config.hudAverageEnabled = enabled;
        } else {
            throw new WrongUsageException(getCommandUsage(sender));
        }
        if (!Config.saveHudConfiguration()) {
            sender
                .addChatMessage(new ChatComponentText(StatCollector.translateToLocal("gtswn.command.hud.save_failed")));
            return;
        }
        sender.addChatMessage(new ChatComponentText("hud " + args[1] + " = " + (enabled ? "on" : "off")));
    }

    private static String expectedRange(String subcommand) {
        return SUBCMD_SCALE.equals(subcommand) ? "[0.2, 5.0]" : "[-500, 500]";
    }
}
