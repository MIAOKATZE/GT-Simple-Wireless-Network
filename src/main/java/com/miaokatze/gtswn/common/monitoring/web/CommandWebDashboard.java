package com.miaokatze.gtswn.common.monitoring.web;

import java.util.List;
import java.util.UUID;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;

import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.common.monitoring.MonitoringProfiles;

/** OP-only dashboard composition; held-item import copies an existing profile reference, never consumes an item. */
public final class CommandWebDashboard extends CommandBase {

    @Override
    public String getCommandName() {
        return "gtswnweb";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/gtswnweb create <name> | list | add <dashboard> <consented profile> | add-held <dashboard>"
            + " | remove <dashboard> <profile> | enable|disable|delete <dashboard>";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        boolean operator = sender.canCommandSenderUseCommand(2, getCommandName());
        if (!operator) {
            say(sender, "Operator permission level 2 required.");
            return;
        }
        try {
            WebDashboardRegistry registry = WebDashboardRegistry.get(sender.getEntityWorld());
            MonitoringProfiles profiles = MonitoringProfiles.get(sender.getEntityWorld());
            if (args.length == 1 && "list".equals(args[0])) {
                for (WebDashboardRegistry.Dashboard dashboard : registry.list(operator)) say(
                    sender,
                    dashboard.id + " "
                        + dashboard.title
                        + " enabled="
                        + dashboard.enabled()
                        + " blocked="
                        + dashboard.blocked()
                        + " profiles="
                        + dashboard.profiles());
            } else if (args.length >= 2 && "create".equals(args[0])) {
                StringBuilder name = new StringBuilder(args[1]);
                for (int i = 2; i < args.length; i++) name.append(' ')
                    .append(args[i]);
                WebDashboardRegistry.Dashboard dashboard = registry.create(operator, name.toString());
                say(
                    sender,
                    "Created disabled dashboard " + dashboard.id + ". Add owner-consented profiles, then enable.");
            } else if (args.length == 3 && "add".equals(args[0])) {
                registry.add(operator, UUID.fromString(args[1]), UUID.fromString(args[2]), profiles);
                say(sender, "Fixed consented profile reference added; no item or source copied.");
            } else if (args.length == 2 && "add-held".equals(args[0])) {
                if (!(sender instanceof EntityPlayerMP))
                    throw new IllegalArgumentException("Hold an imported terminal as an OP player");
                ItemStack held = ((EntityPlayerMP) sender).getHeldItem();
                UUID reference = MonitoringProfiles.readProfileReference(held);
                if (reference == null && held != null
                    && held.getItem() instanceof ItemDeviceInfoTerminal
                    && held.stackTagCompound != null
                    && held.stackTagCompound.hasKey(ItemDeviceInfoTerminal.NBT_UUID))
                    reference = UUID.fromString(held.stackTagCompound.getString(ItemDeviceInfoTerminal.NBT_UUID));
                if (reference == null) throw new IllegalArgumentException(
                    "Held item has no recoverable profile; owner must import and consent first");
                registry.add(operator, UUID.fromString(args[1]), reference, profiles);
                say(sender, "Ghost reference added. Held item is unchanged and not consumed.");
            } else if (args.length == 3 && "remove".equals(args[0])) {
                registry.remove(operator, UUID.fromString(args[1]), UUID.fromString(args[2]));
                say(sender, "Profile reference removed; published cache invalidated.");
            } else if (args.length == 2 && ("enable".equals(args[0]) || "disable".equals(args[0]))) {
                registry.setEnabled(operator, UUID.fromString(args[1]), "enable".equals(args[0]), profiles);
                say(
                    sender,
                    "Dashboard state updated. Global service enable/provisioning and offline sampling jobs are separate.");
            } else if (args.length == 2 && "delete".equals(args[0])) {
                registry.delete(operator, UUID.fromString(args[1]));
                say(sender, "Dashboard definition deleted; source profiles/history retained.");
            } else say(sender, getCommandUsage(sender));
        } catch (IllegalArgumentException denied) {
            say(sender, denied.getMessage());
        }
    }

    private static void say(ICommandSender sender, String message) {
        sender.addChatMessage(new ChatComponentText(message));
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (!sender.canCommandSenderUseCommand(2, getCommandName())) return null;
        return args.length == 1
            ? getListOfStringsMatchingLastWord(
                args,
                "create",
                "list",
                "add",
                "add-held",
                "remove",
                "enable",
                "disable",
                "delete")
            : null;
    }
}
