package com.miaokatze.gtswn.common.command;

import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.ChatComponentText;

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
        return "/gtswn <HudXOffset|HudYOffset|HudScale> [value]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, SUBCMD_X, SUBCMD_Y, SUBCMD_SCALE);
        }
        return null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
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
                    "Invalid value for " + subcommand + "; expected " + expectedRange(subcommand) + "."));
            return;
        }

        if (!Config.saveHudConfiguration()) {
            sender.addChatMessage(new ChatComponentText("Failed to save HUD configuration."));
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

    private static String expectedRange(String subcommand) {
        return SUBCMD_SCALE.equals(subcommand) ? "a number in [0.2, 5.0]" : "an integer in [-500, 500]";
    }
}
