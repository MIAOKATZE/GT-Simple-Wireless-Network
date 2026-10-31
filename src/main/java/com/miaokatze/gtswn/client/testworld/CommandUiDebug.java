package com.miaokatze.gtswn.client.testworld;

import java.util.Arrays;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.ChatComponentText;

/** Client-owned command; common/server command classes do not link this class. */
public final class CommandUiDebug extends CommandBase {

    @Override
    public String getCommandName() {
        return "gtswnui";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/gtswnui help|list|capture|inspect|batch [all|current|portable|device|settings|quantum|link|panel|monitor|cover-input|cover-output] [quick|matrix]|click <button-id-or-path>|type <field-path> <text>|stop";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        String operation = args.length == 0 ? "help" : args[0];
        if (operation.equals("help") || operation.equals("list")) {
            for (String line : new String[] { getCommandUsage(sender),
                "Opt-in, GTSWN test dimension only. No startup autorun. all/portable/device/settings/quantum request OP4 server /gtswn test kit ui, then restore. Originals are persisted server-side.",
                "capture/inspect/current: invoke while a production GUI is active through automation, or arm command then open it within 20 seconds. capture includes PNG; inspect is JSON only.",
                "quick: current language/scale + safe local tabs/pages. matrix: installed current/en_US/zh_CN languages x scales 1/2/3; resource/font reload cost is logged and settings restored.",
                "Presets: portable=actual bound monitor, device=real terminal list, settings=real terminal settings, quantum=server-authorized ContainerTerminal. panel/monitor: native activation of dedicated kit fixtures near test spawn, within normal reach. current accepts an already open vanilla or MUI2 GUI.",
                "click: actual mouse press/release at enabled visible button center. type: real field path (e.g. textFields[0]); inspected lwjgl3ify 3.0.42 uses synthetic TextEvent then production keyTyped, vanilla uses keyTyped. Unsupported versions or pending native text return NOT_RUN before editing. Server effects remain NOT_VERIFIED until independently checked.",
                "MUI2 click: exact observed paths or name:<widget-name>, native ButtonWidget/PageButton/TextFieldWidget, version 2.3.94, stable target panel and real hit testing, no overlay/drag/held input. MUI2 type uses native focus, key deletion and TextEvent dispatch. SENT records dispatch; independent server data and visual review remain required. Wheel and drag are not automated.",
                "Artifacts: <game>/gtswn-ui-debug/<run>/index.json, results.json, per-case JSON + screenshots/PNG. Stable field paths, IDs, actual font widths, widget classes/bounds, state, container window ID; no credentials/NBT dumped.",
                "Presets wait up to 400 natural ticks for actual portable/device/quantum responses or panel owner+tab sync. Empty responses are valid. DATA_TIMEOUT still captures layout/PNG and remains NOT_VERIFIED. current/capture/inspect allow loading screens without waiting.",
                "FAIL/NOT_RUN continues to next case; 400 tick case timeout; stop restores language/scale and requests kit restore. Real server effects/custom painted rows/pixel clipping/window resizing are MANUAL, never automatic PASS." })
                sender.addChatMessage(new ChatComponentText(line));
            return;
        }
        if (operation.equals("stop")) {
            UiDebugSuite.INSTANCE.stop();
            return;
        }
        try {
            if (operation.equals("capture") || operation.equals("inspect"))
                UiDebugSuite.INSTANCE.start("current", false, operation.equals("capture"), "capture", null);
            else if (operation.equals("batch")) UiDebugSuite.INSTANCE
                .start(args.length > 1 ? args[1] : "all", args.length > 2 && args[2].equals("matrix"));
            else if (operation.equals("click") && args.length == 2)
                UiDebugSuite.INSTANCE.start("current", false, true, "click", args[1]);
            else if (operation.equals("type") && args.length >= 3) UiDebugSuite.INSTANCE
                .start("current", false, true, "type", String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
            else throw new WrongUsageException(getCommandUsage(sender));
        } catch (IllegalArgumentException | IllegalStateException failure) {
            sender.addChatMessage(new ChatComponentText("[GTSWN-UI] " + failure.getMessage()));
        }
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) return getListOfStringsMatchingLastWord(
            args,
            "help",
            "list",
            "capture",
            "inspect",
            "batch",
            "click",
            "type",
            "stop");
        if (args.length == 2 && args[0].equals("batch")) return getListOfStringsMatchingLastWord(
            args,
            "all",
            "current",
            "portable",
            "device",
            "settings",
            "quantum",
            "link",
            "cover-input",
            "cover-output",
            "panel",
            "monitor");
        if (args.length == 3 && args[0].equals("batch"))
            return getListOfStringsMatchingLastWord(args, "quick", "matrix");
        return null;
    }
}
