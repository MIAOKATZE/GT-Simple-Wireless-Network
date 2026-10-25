package com.miaokatze.gtswn.client.engineering.desktop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.Minecraft;

import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

/** One explicit preset application, followed only by reads of existing authorized source choices. */
public final class DesktopAutoBind {

    private DesktopAutoBind() {}

    /** New copy for every application; neither a shared template nor the previous desktop is changed. */
    public static DesktopLayout initialize(DesktopLayout template) {
        if (template == null) throw new IllegalArgumentException("template");
        DesktopLayout result = new DesktopLayout().withTheme(template.theme());
        for (WidgetSpec widget : template.widgets()) {
            WidgetSpec clean = new WidgetSpec(
                widget.id,
                widget.kind,
                widget.column,
                widget.row,
                widget.columns,
                widget.rows,
                widget.title,
                null,
                widget.fields,
                widget.displayColumns,
                widget.fieldsConfigured,
                widget.presentation,
                widget.metricField,
                widget.userTarget,
                widget.borderColor,
                null,
                null).withSingleResource(widget.singleResource)
                    .withRate(widget.averageRate, widget.averageWindowSeconds)
                    .styled(widget.effects)
                    .withAutoResource(widget.kind == WidgetKind.RESOURCES)
                    .withAutoDevice(widget.kind.supportsDeviceBinding());
            if (!result.add(clean)) throw new IllegalArgumentException("template geometry");
        }
        return result;
    }

    /** No requests, selectors, polling demand, permissions or samples are created here. */
    public static boolean initializeAvailable(DesktopLayout layout) {
        if (layout == null) return false;
        boolean changed = WidgetResources.initializeDefaults(layout);
        boolean pending = false;
        for (WidgetSpec widget : layout.widgets()) if (widget.autoDevicePending) pending = true;
        if (!pending) return changed;
        Minecraft client = Minecraft.getMinecraft();
        if (client == null || client.theWorld == null || client.thePlayer == null || client.getNetHandler() == null)
            return changed;
        List<DeviceBinding> candidates = new ArrayList<>();
        for (DesktopDeviceSources.Source source : DesktopDeviceSources.sources()) {
            for (Entry entry : DesktopDeviceSources.choices(source.id)) {
                DeviceBinding binding = DesktopDeviceSources.binding(source.id, entry);
                if (binding != null) candidates.add(binding);
            }
        }
        return initializeDevices(layout, candidates) || changed;
    }

    /** Pure assignment seam: candidates must already have passed the existing source authorization checks. */
    static boolean initializeDevices(DesktopLayout layout, List<DeviceBinding> candidates) {
        List<DeviceBinding> sorted = new ArrayList<>(candidates);
        sorted.sort(
            Comparator.comparing(DesktopAutoBind::identity)
                .thenComparing(value -> value.source.toString())
                .thenComparing(value -> value.key));
        Set<String> used = new HashSet<>();
        for (WidgetSpec widget : layout.widgets()) if (widget.device != null) used.add(identity(widget.device));
        boolean changed = false;
        for (WidgetSpec widget : layout.widgets()) {
            if (!widget.autoDevicePending) continue;
            if (widget.device != null) {
                changed |= layout.replace(widget.withAutoDevice(false));
                continue;
            }
            for (DeviceBinding candidate : sorted) {
                if (!used.add(identity(candidate))) continue;
                changed |= layout.replace(widget.boundDevice(candidate));
                break;
            }
        }
        return changed;
    }

    /** Same machine seen through several terminals consumes only one automatic slot. */
    private static String identity(DeviceBinding binding) {
        return binding.dimension + ":" + binding.x + ":" + binding.y + ":" + binding.z + ":" + binding.meta;
    }
}
