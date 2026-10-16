package com.miaokatze.gtswn.common.testworld;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.Teleporter;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import com.miaokatze.gtswn.common.util.DebugTestGate;

/** Delegated from the existing OP4 /gtswn command. Console operations never need a player. */
public final class TestWorldCommand {

    private static final String RETURN_POINT = "gtswn.test.return";
    private static final String[] ACTIONS = { "help", "list", "actors", "enter", "leave", "build", "reset", "run",
        "status", "report", "stop", "kit" };

    private TestWorldCommand() {}

    public static List<String> complete(String[] args) {
        if (!DebugTestGate.isEnabled()) return null;
        if (args.length == 2) return CommandBase.getListOfStringsMatchingLastWord(args, ACTIONS);
        if (args.length == 3 && "run".equals(args[1])) {
            List<String> ids = new ArrayList<>(TestWorldManager.ids());
            ids.add("all");
            return CommandBase.getListOfStringsMatchingLastWord(args, ids.toArray(new String[0]));
        }
        if (args.length == 3 && "kit".equals(args[1]))
            return CommandBase.getListOfStringsMatchingLastWord(args, "ui", "restore");
        return null;
    }

    public static void execute(ICommandSender sender, String[] args) {
        if (!DebugTestGate.isEnabled()) throw new CommandException(DebugTestGate.DISABLED_MESSAGE);
        String action = args.length < 2 ? "help" : args[1];
        if (args.length > ("run".equals(action) || "kit".equals(action) ? 3 : 2))
            throw new CommandException("/gtswn test help");
        try {
            switch (action) {
                case "help":
                    say(
                        sender,
                        "/gtswn test help|list|actors|enter|leave|build|reset|run [all|id]|status|report|stop|kit ui|restore");
                    say(
                        sender,
                        "Explicit test dimension only. Build/reset rebuilds the 64x64 test area over server ticks. Run resets between scenarios.");
                    say(
                        sender,
                        "Console supports build/run/status/report/stop. Client UI checks: /gtswnui batch all quick.");
                    break;
                case "list":
                    for (String id : TestWorldManager.ids()) say(sender, id + " - " + TestWorldManager.description(id));
                    break;
                case "actors":
                    for (String actor : TestActors.describe()) say(sender, actor);
                    break;
                case "enter":
                    enter(player(sender));
                    say(sender, "Entered GTSWN-TEST. /gtswn test leave returns to your recorded position.");
                    break;
                case "leave":
                    TestUiKit.restore(player(sender));
                    leave(player(sender));
                    say(sender, "Returned to the recorded position.");
                    break;
                case "kit":
                    if (args.length != 3) throw new CommandException("/gtswn test kit ui|restore");
                    if ("ui".equals(args[2])) TestUiKit.grant(player(sender));
                    else if ("restore".equals(args[2])) TestUiKit.restore(player(sender));
                    else throw new CommandException("/gtswn test kit ui|restore");
                    say(sender, "UI kit: " + args[2]);
                    break;
                case "build":
                case "reset":
                    TestWorldManager.build();
                    say(sender, "BUILDING; use /gtswn test status to observe completion.");
                    break;
                case "run":
                    String runId = TestWorldManager.run(args.length == 3 ? args[2] : "all");
                    say(sender, "BUILDING run=" + runId + "; results: /gtswn test report");
                    break;
                case "status":
                    say(sender, TestWorldManager.status());
                    break;
                case "report":
                    say(sender, TestWorldManager.report());
                    break;
                case "stop":
                    TestWorldManager.stop("stopped by administrator");
                    say(sender, TestWorldManager.status());
                    break;
                default:
                    throw new CommandException("/gtswn test help");
            }
        } catch (IllegalArgumentException | IllegalStateException ex) {
            throw new CommandException("GTSWN-TEST: " + ex.getMessage());
        }
    }

    private static void say(ICommandSender sender, String text) {
        sender.addChatMessage(new ChatComponentText(text));
    }

    private static EntityPlayerMP player(ICommandSender sender) {
        if (!(sender instanceof EntityPlayerMP)) throw new CommandException("This operation requires a player");
        return (EntityPlayerMP) sender;
    }

    private static void enter(EntityPlayerMP player) {
        if (!TestWorldManager.built())
            throw new CommandException("Run /gtswn test build and wait for completion first");
        WorldServer world = TestDimension.ensureWorld();
        if (!TestDimension.isTest(player.worldObj)) {
            NBTTagCompound persisted = player.getEntityData()
                .getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
            NBTTagCompound point = new NBTTagCompound();
            point.setInteger("dimension", player.dimension);
            point.setDouble("x", player.posX);
            point.setDouble("y", player.posY);
            point.setDouble("z", player.posZ);
            point.setFloat("yaw", player.rotationYaw);
            point.setFloat("pitch", player.rotationPitch);
            persisted.setTag(RETURN_POINT, point);
            player.getEntityData()
                .setTag(EntityPlayer.PERSISTED_NBT_TAG, persisted);
        }
        transfer(player, world, .5, 65, .5, 0, 0);
    }

    private static void leave(EntityPlayerMP player) {
        if (!TestDimension.isTest(player.worldObj)) throw new CommandException("You are not in GTSWN-TEST");
        NBTTagCompound persisted = player.getEntityData()
            .getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        if (!persisted.hasKey(RETURN_POINT))
            throw new CommandException("No return position recorded; use an administrator teleport");
        NBTTagCompound point = persisted.getCompoundTag(RETURN_POINT);
        int dimension = point.getInteger("dimension");
        if (!DimensionManager.isDimensionRegistered(dimension))
            throw new CommandException("Return dimension unavailable; position preserved");
        if (DimensionManager.getWorld(dimension) == null) DimensionManager.initDimension(dimension);
        WorldServer world = DimensionManager.getWorld(dimension);
        if (world == null) throw new CommandException("Return world unavailable; position preserved");
        transfer(
            player,
            world,
            point.getDouble("x"),
            point.getDouble("y"),
            point.getDouble("z"),
            point.getFloat("yaw"),
            point.getFloat("pitch"));
        persisted.removeTag(RETURN_POINT);
    }

    private static void transfer(EntityPlayerMP player, WorldServer world, double x, double y, double z, float yaw,
        float pitch) {
        world.theChunkProviderServer.loadChunk(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4);
        if (player.dimension != world.provider.dimensionId) MinecraftServer.getServer()
            .getConfigurationManager()
            .transferPlayerToDimension(player, world.provider.dimensionId, new Teleporter(world) {

                @Override
                public void placeInPortal(Entity entity, double oldX, double oldY, double oldZ, float rotation) {
                    entity.setLocationAndAngles(x, y, z, yaw, pitch);
                }

                @Override
                public boolean placeInExistingPortal(Entity entity, double px, double py, double pz, float rotation) {
                    return true;
                }

                @Override
                public boolean makePortal(Entity entity) {
                    return false;
                }
            });
        player.playerNetServerHandler.setPlayerLocation(x, y, z, yaw, pitch);
    }
}
