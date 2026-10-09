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
        return "/gtswn <tap|quantum> [on|off|density <0..200>|size <10..400>|distance <1..256>|help|status|reset]"
            + " | /gtswn tap material <old|oldplus|low|medium|high>"
            + " | /gtswn <HudXOffset|HudYOffset|HudScale> [value]"
            + " | /gtswn hud <charge|eu|instant|average> <on|off> | /gtswn <help|status|reset>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(
                args,
                "tap",
                "quantum",
                "help",
                "status",
                "reset",
                SUBCMD_X,
                SUBCMD_Y,
                SUBCMD_SCALE,
                "hud");
        }
        if (args.length == 2 && isGroup(args[0])) {
            return "tap".equalsIgnoreCase(args[0])
                ? getListOfStringsMatchingLastWord(
                    args,
                    "on",
                    "off",
                    "density",
                    "size",
                    "distance",
                    "material",
                    "help",
                    "status",
                    "reset")
                : getListOfStringsMatchingLastWord(
                    args,
                    "on",
                    "off",
                    "density",
                    "size",
                    "distance",
                    "help",
                    "status",
                    "reset");
        }
        if (args.length == 3 && isGroup(args[0])) {
            if ("material".equalsIgnoreCase(args[1]) && "tap".equalsIgnoreCase(args[0])) {
                return getListOfStringsMatchingLastWord(args, "old", "oldplus", "low", "medium", "high");
            }
            if ("density".equalsIgnoreCase(args[1]))
                return getListOfStringsMatchingLastWord(args, "0", "50", "100", "200");
            if ("size".equalsIgnoreCase(args[1]))
                return getListOfStringsMatchingLastWord(args, "10", "50", "100", "200", "400");
            if ("distance".equalsIgnoreCase(args[1]))
                return getListOfStringsMatchingLastWord(args, "1", "32", "64", "128", "256");
        }
        if (args.length == 2 && "hud".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "charge", "eu", "instant", "average");
        }
        if (args.length == 3 && "hud".equalsIgnoreCase(args[0])
            && java.util.Arrays.asList("charge", "eu", "instant", "average")
                .contains(args[1].toLowerCase(java.util.Locale.ROOT))) {
            return getListOfStringsMatchingLastWord(args, "on", "off");
        }
        return java.util.Collections.emptyList();
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0) {
            message(sender, "gtswn.command.effects.guide");
            return;
        }
        if (isGroup(args[0])) {
            processEffects(sender, args);
            return;
        }
        if ("help".equalsIgnoreCase(args[0]) || "status".equalsIgnoreCase(args[0])
            || "reset".equalsIgnoreCase(args[0])) {
            if (args.length != 1) throw new WrongUsageException(getCommandUsage(sender));
            if ("help".equalsIgnoreCase(args[0])) {
                message(sender, "gtswn.command.effects.guide");
                sender.addChatMessage(new ChatComponentText(getCommandUsage(sender)));
            } else {
                if ("reset".equalsIgnoreCase(args[0])) {
                    boolean tapSaved = Config.resetParticleConfiguration(true);
                    boolean quantumSaved = Config.resetParticleConfiguration(false);
                    if (!tapSaved || !quantumSaved) message(sender, "gtswn.command.effects.save_failed");
                }
                showEffects(sender, true);
                showEffects(sender, false);
            }
            return;
        }
        if (args.length > 0 && "hud".equalsIgnoreCase(args[0])) {
            processLineToggle(sender, args);
            return;
        }
        if (args.length < 1 || args.length > 2) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        String subcommand = SUBCMD_X.equalsIgnoreCase(args[0]) ? SUBCMD_X
            : SUBCMD_Y.equalsIgnoreCase(args[0]) ? SUBCMD_Y
                : SUBCMD_SCALE.equalsIgnoreCase(args[0]) ? SUBCMD_SCALE : args[0];
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

    private static boolean isGroup(String group) {
        return "tap".equalsIgnoreCase(group) || "quantum".equalsIgnoreCase(group);
    }

    private static void message(ICommandSender sender, String key, Object... values) {
        sender.addChatMessage(new ChatComponentText(StatCollector.translateToLocalFormatted(key, values)));
    }

    private static void showEffects(ICommandSender sender, boolean tap) {
        message(
            sender,
            "gtswn.command.effects.current",
            tap ? "tap" : "quantum",
            (tap ? Config.tapParticlesEnabled : Config.quantumParticlesEnabled) ? "on" : "off",
            tap ? Config.tapParticleDensityPercent : Config.quantumParticleDensityPercent,
            tap ? Config.tapParticleSizePercent : Config.quantumParticleSizePercent,
            tap ? Config.tapRenderDistance : Config.quantumRenderDistance);
        if (tap) message(sender, "gtswn.command.effects.material", Config.tapMaterial);
    }

    private void processEffects(ICommandSender sender, String[] args) {
        boolean tap = "tap".equalsIgnoreCase(args[0]);
        String usage = tap ? "gtswn.command.effects.tap_usage" : "gtswn.command.effects.quantum_usage";
        String operation = args.length < 2 ? "status" : args[1].toLowerCase(java.util.Locale.ROOT);
        if ("help".equals(operation) || "status".equals(operation)) {
            if (args.length > 2) throw new WrongUsageException(usage);
            showEffects(sender, tap);
            message(sender, tap ? "gtswn.command.effects.tap_usage" : "gtswn.command.effects.quantum_usage");
            return;
        }
        if ("on".equals(operation) || "off".equals(operation) || "reset".equals(operation)) {
            if (args.length != 2) throw new WrongUsageException(usage);
            if ("reset".equals(operation)) {
                if (!Config.resetParticleConfiguration(tap)) message(sender, "gtswn.command.effects.save_failed");
                showEffects(sender, tap);
                return;
            }
            if (tap) Config.tapParticlesEnabled = "on".equals(operation);
            else Config.quantumParticlesEnabled = "on".equals(operation);
        } else if ("material".equals(operation) && tap) {
            if (args.length != 3) throw new WrongUsageException(usage);
            String material = args[2].toLowerCase(java.util.Locale.ROOT);
            if (!Config.validTapMaterial(material)) throw new WrongUsageException(usage);
            Config.tapMaterial = material;
        } else if ("density".equals(operation) || "size".equals(operation) || "distance".equals(operation)) {
            if (args.length != 3) throw new WrongUsageException(usage);
            int minimum = "density".equals(operation) ? 0 : "size".equals(operation) ? 10 : 1;
            int maximum = "density".equals(operation) ? 200 : "size".equals(operation) ? 400 : 256;
            int value;
            try {
                value = Integer.parseInt(args[2]);
                if (value < minimum || value > maximum) throw new IllegalArgumentException();
            } catch (IllegalArgumentException exception) {
                throw new WrongUsageException(usage);
            }
            if ("density".equals(operation)) {
                if (tap) Config.tapParticleDensityPercent = value;
                else Config.quantumParticleDensityPercent = value;
            } else if ("size".equals(operation)) {
                if (tap) Config.tapParticleSizePercent = value;
                else Config.quantumParticleSizePercent = value;
            } else {
                if (tap) Config.tapRenderDistance = value;
                else Config.quantumRenderDistance = value;
            }
        } else {
            throw new WrongUsageException(usage);
        }
        if (!Config.saveParticleConfiguration()) message(sender, "gtswn.command.effects.save_failed");
        showEffects(sender, tap);
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
