package com.miaokatze.gtswn.common.command;

import java.util.List;
import java.util.UUID;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;

import com.miaokatze.gtswn.common.monitoring.MonitorProfile;
import com.miaokatze.gtswn.common.monitoring.MonitoringProfiles;
import com.miaokatze.gtswn.config.Config;

/** Player-facing recovery UI. Arguments identify records but never substitute for the authenticated sender. */
public final class CommandMonitorProfile extends CommandBase {

    @Override
    public String getCommandName() {
        return "monitorprofile";
    }

    @Override
    public List<String> getCommandAliases() {
        return java.util.Collections.singletonList("gtswnprofile");
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        // Native 1.7 still rejects level-zero commands for non-OP players; profile ACLs guard each operation.
        return sender instanceof EntityPlayerMP || super.canCommandSenderUseCommand(sender);
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/monitorprofile ui | list | create | import | recover|release <profile> | share|revoke|transfer <profile> <player UUID>"
            + " | rename <profile> <name...> | offline|web <profile> on|off | detach <profile> <dim:x:y:z> | status <profile>";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (!(sender instanceof EntityPlayerMP)) {
            sender.addChatMessage(new ChatComponentText("Use this command as an authenticated player."));
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;
        MonitoringProfiles store = MonitoringProfiles.get(player.worldObj);
        UUID actor = player.getUniqueID();
        try {
            if (args.length == 1 && "ui".equals(args[0])) {
                com.miaokatze.gtswn.network.C2SPacketMonitorProfileAction.ServerQueue.open(player);
            } else if (args.length == 1 && "list".equals(args[0])) {
                List<MonitorProfile> profiles = store.visibleProfiles(actor);
                if (profiles.isEmpty()) say(
                    player,
                    "No profiles. Hold an authorized DIT, portable monitor or quantum terminal and /monitorprofile import.");
                for (MonitorProfile profile : profiles) describe(player, profile);
            } else if (args.length == 1 && ("create".equals(args[0]) || "import".equals(args[0]))) {
                MonitorProfile profile = store.importHeld(player);
                say(
                    player,
                    "Profile ready: " + profile.id
                        + ". Legacy data/history is retained independently; imported sources are fixed.");
            } else if (args.length == 2 && "recover".equals(args[0])) {
                store.recover(player, UUID.fromString(args[1]));
                say(player, "Held terminal now references profile " + args[1] + "; no history copied.");
            } else if (args.length == 2 && "release".equals(args[0])) {
                store.releaseQuantum(player, UUID.fromString(args[1]));
                say(
                    player,
                    "Released only the held quantum terminal's profile reference. Native pairing is unchanged; rebind/import explicitly. Old profile, permissions and history are retained.");
            } else if (args.length == 2 && "status".equals(args[0])) {
                UUID id = UUID.fromString(args[1]);
                MonitorProfile profile = store.profileForTerminal(id);
                if (profile == null || !profile.mayRead(actor))
                    throw new IllegalArgumentException("No profile permission");
                store.canRead(actor, id); // Refresh availability from loaded-only authoritative source checks.
                describe(player, profile);
            } else if (args.length >= 2 && "rename".equals(args[0])) {
                String name = args.length == 2 ? ""
                    : String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
                store.rename(actor, UUID.fromString(args[1]), name);
                say(player, "Profile renamed.");
            } else if (args.length == 3) {
                UUID id = UUID.fromString(args[1]);
                if ("share".equals(args[0])) store.share(actor, id, UUID.fromString(args[2]));
                else if ("revoke".equals(args[0])) store.revoke(actor, id, UUID.fromString(args[2]));
                else if ("transfer".equals(args[0])) store.transfer(actor, id, UUID.fromString(args[2]));
                else if ("detach".equals(args[0])) {
                    if (!store.canModify(actor, id)) throw new IllegalArgumentException("Only owner may detach");
                    store.detachSource(actor, id, args[2]);
                    com.miaokatze.gtswn.common.device.DeviceTerminalDataStore.get(player.worldObj)
                        .removeBinding(id, args[2]);
                } else if ("offline".equals(args[0])) {
                    store.setOffline(
                        actor,
                        id,
                        enabled(args[2]),
                        Math.max(20, Math.min(72000, Config.deviceSampleIntervalSeconds * 20)));
                } else if ("web".equals(args[0])) {
                    boolean publish = enabled(args[2]);
                    store.setWebEnabled(actor, id, publish);
                    if (publish) say(
                        player,
                        "Web consent granted. An operator must separately add this fixed profile to a dashboard; then ALL configured Web bearer-token readers may see it, not only ACL readers. Consent does not enable a dashboard or offline sampling.");
                } else throw new IllegalArgumentException(getCommandUsage(sender));
                say(player, "Profile updated: " + id);
            } else say(player, getCommandUsage(sender));
        } catch (IllegalArgumentException denied) {
            say(player, denied.getMessage());
        }
    }

    private static boolean enabled(String value) {
        if ("on".equals(value)) return true;
        if ("off".equals(value)) return false;
        throw new IllegalArgumentException("Expected on or off");
    }

    private static void describe(EntityPlayerMP player, MonitorProfile profile) {
        say(
            player,
            profile.name() + " ["
                + profile.id
                + "] owner="
                + profile.owner()
                + " sources="
                + profile.sources()
                    .size()
                + " status="
                + profile.status()
                + " sampledTick="
                + profile.sampleTime()
                + " offline="
                + profile.offlineEnabled()
                + " intervalTicks="
                + profile.intervalTicks()
                + " retainedPoints="
                + profile.historyPoints()
                + " web="
                + profile.webEnabled());
    }

    private static void say(EntityPlayerMP player, String message) {
        player.addChatMessage(new ChatComponentText(message));
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        return args.length == 1
            ? getListOfStringsMatchingLastWord(
                args,
                "ui",
                "list",
                "rename",
                "create",
                "import",
                "recover",
                "release",
                "share",
                "revoke",
                "transfer",
                "offline",
                "web",
                "status",
                "detach")
            : null;
    }
}
