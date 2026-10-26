package com.miaokatze.gtswn.client.engineering.board;

import static org.junit.Assert.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.miaokatze.gtswn.client.engineering.DisplayContinuity;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources.Source;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources.SourceOrigin;
import com.miaokatze.gtswn.common.hud.MonitorBindings;
import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor.Panel;
import com.miaokatze.gtswn.network.PortableHistoryPatch;

public class PortableDisplayContinuityTest {

    @Test
    @SuppressWarnings("unchecked")
    public void reauthorizationOnOpenBoardCannotRestoreCardsUnderAnotherTerminal() throws Exception {
        PortableSuperDashboard.close();
        ItemStack terminal = new ItemStack(new PortableWirelessNetworkMonitor());
        terminal.stackTagCompound = new NBTTagCompound();
        terminal.stackTagCompound.setBoolean(PortableWirelessNetworkMonitor.NBT_INITIALIZED, true);
        terminal.stackTagCompound.setString(
            PortableWirelessNetworkMonitor.NBT_OWNER_UUID,
            UUID.randomUUID()
                .toString());
        UUID id = MonitorBindings.ensureId(terminal);
        Constructor<Source> sourceConstructor = Source.class
            .getDeclaredConstructor(ItemStack.class, SourceOrigin.class);
        sourceConstructor.setAccessible(true);
        Source source = sourceConstructor.newInstance(terminal, SourceOrigin.INVENTORY);
        Field scopeField = PortableSuperDashboard.class.getDeclaredField("displayScope");
        Field displayField = PortableSuperDashboard.class.getDeclaredField("DISPLAY");
        Field openField = PortableSuperDashboard.class.getDeclaredField("open");
        scopeField.setAccessible(true);
        displayField.setAccessible(true);
        openField.setAccessible(true);
        Constructor<?> scopeConstructor = scopeField.getType()
            .getDeclaredConstructor(Object.class, Object.class, Object.class, Source.class);
        scopeConstructor.setAccessible(true);
        Object scope = scopeConstructor.newInstance(new Object(), new Object(), new Object(), source);
        DisplayContinuity<Map<String, Object>> display = (DisplayContinuity<Map<String, Object>>) displayField
            .get(null);
        Object previousScope = scopeField.get(null);
        try {
            scopeField.set(null, scope);
            openField.setBoolean(null, true);
            display.receive(scope, Collections.singletonMap("shared-panel", "old EU detail"), 100);
            PortableSuperDashboard.revoke(id);
            assertTrue(PortableSuperDashboard.isOpen());
            // update() receives a later authorized snapshot while the board remains open.
            display.receive(scopeField.get(null), Collections.singletonMap("shared-panel", "restored EU detail"), 200);
            PortableSuperDashboard.close();
            Object anotherTerminal = new Object();
            display.open(anotherTerminal);
            assertNull(display.display(anotherTerminal));
        } finally {
            PortableSuperDashboard.close();
            display.clear();
            scopeField.set(null, previousScope);
        }
    }

    @Test
    public void SummaryProjectionDoesNotInvalidatePriorDetailDictionary() {
        Panel detail = panel();
        detail.detail = true;
        detail.metadataVersion = 10;
        detail.configVersion = 3;
        detail.aeWindow = "5m";
        Panel summary = panel();
        summary.binding = detail.binding;
        summary.metadataVersion = 11;
        // fillSummary does not query AE history and reports a zero span.
        summary.aeWindow = "0s";
        assertTrue(PortableSuperDashboard.sameDisplaySource(detail, summary));
        summary.ownerName = "different owner";
        assertFalse(PortableSuperDashboard.sameDisplaySource(detail, summary));
    }

    @Test
    public void acknowledgedOmissionKeepsDisplayButChangedMetadataOrSourceDoesNot() {
        Panel old = panel(), next = panel();
        next.binding = old.binding;
        old.detail = next.detail = true;
        old.metadataVersion = next.metadataVersion = 7;
        next.metadataUnchanged = true;
        assertTrue(PortableSuperDashboard.sameDisplaySource(old, next));
        next.metadataVersion++;
        assertFalse(PortableSuperDashboard.sameDisplaySource(old, next));
        next.metadataVersion--;
        next.binding = new MonitorBindings.Binding(0, 99, 2, 3, old.binding.panelId);
        assertFalse(PortableSuperDashboard.sameDisplaySource(old, next));
    }

    @Test
    public void rawConfigGenerationIsCheckedBeforeHistoryReconstruction() {
        Panel old = panel(), next = panel();
        next.binding = old.binding;
        old.detail = next.detail = true;
        old.metadataVersion = next.metadataVersion = 1;
        old.configVersion = 10;
        next.historyPatch = new PortableHistoryPatch();
        next.historyPatch.configVersion = 10;
        assertTrue(PortableSuperDashboard.sameDisplaySource(old, next));
        next.historyPatch.configVersion = 11;
        assertFalse(PortableSuperDashboard.sameDisplaySource(old, next));
    }

    private static Panel panel() {
        Panel panel = new Panel();
        panel.binding = new MonitorBindings.Binding(0, 1, 2, 3, UUID.randomUUID());
        return panel;
    }
}
