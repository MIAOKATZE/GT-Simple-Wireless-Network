package com.miaokatze.gtswn.client.gui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.GuiButton;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.miaokatze.gtswn.common.gui.ContainerTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;
import com.miaokatze.gtswn.config.Config;

import cpw.mods.fml.relauncher.FMLInjectionData;

/** Exercises the actual GUI button list and callbacks without constructing a Minecraft client or GL context. */
public class QuantumTerminalEffectsGuiTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final Map<Field, Object> originalConfig = new HashMap<>();
    private Field minecraftHome;
    private Object originalHome;
    private File configFile;

    private static class ActualGui extends GuiQuantumTerminal {

        private ActualGui() {
            super((ContainerTerminal) null);
        }

        private void initialize() {
            buttonList = new ArrayList<GuiButton>();
            guiLeft = 0;
            guiTop = 0;
            actionPerformed(new GuiButton(101, 0, 0, ""));
        }

        private GuiButton find(int id) {
            for (Object entry : buttonList) {
                GuiButton button = (GuiButton) entry;
                if (button.id == id) return button;
            }
            return null;
        }

        private void activate(int id) {
            GuiButton button = find(id);
            assertNotNull(button);
            assertTrue(button.mousePressed(null, button.xPosition + 1, button.yPosition + 1));
            actionPerformed(button);
        }

        private void rightClick(int id) {
            GuiButton button = find(id);
            mouseClicked(button.xPosition + 1, button.yPosition + 1, 1);
        }

        private List<GuiButton> settings() {
            List<GuiButton> settings = new ArrayList<>();
            for (Object entry : buttonList) {
                GuiButton button = (GuiButton) entry;
                if (button.id >= 110) settings.add(button);
            }
            return settings;
        }
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeClass.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return unsafeClass.getMethod("allocateInstance", Class.class)
            .invoke(singleton.get(null), type);
    }

    private ActualGui actualGui(boolean quantum) throws Exception {
        ContainerTerminal container = (ContainerTerminal) allocate(ContainerTerminal.class);
        Field quantumField = ContainerTerminal.class.getDeclaredField("quantum");
        quantumField.setAccessible(true);
        quantumField.setBoolean(container, quantum);
        ActualGui gui = (ActualGui) allocate(ActualGui.class);
        Field containerField = GuiQuantumTerminal.class.getDeclaredField("terminalContainer");
        containerField.setAccessible(true);
        containerField.set(gui, container);
        gui.initialize();
        return gui;
    }

    @Before
    public void setupConfiguration() throws Exception {
        for (Field field : Config.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) {
                field.setAccessible(true);
                originalConfig.put(field, field.get(null));
            }
        }
        minecraftHome = FMLInjectionData.class.getDeclaredField("minecraftHome");
        minecraftHome.setAccessible(true);
        originalHome = minecraftHome.get(null);
        minecraftHome.set(null, temporaryFolder.getRoot());
        configFile = temporaryFolder.newFile("effects-gui.cfg");
        Config.synchronizeNetworkConfiguration(configFile);
    }

    @After
    public void restoreConfiguration() throws Exception {
        GuiQuantumTerminal.receiveData(null);
        for (Map.Entry<Field, Object> entry : originalConfig.entrySet()) {
            entry.getKey()
                .set(null, entry.getValue());
        }
        minecraftHome.set(null, originalHome);
    }

    @Test
    public void quantumSafetyControlUsesServerAuthorityInBothModesAndFitsBelowPalette() throws Exception {
        QuantumNetworkData data = new QuantumNetworkData();
        data.online = true;
        data.canChangeNetworkSafety = true;
        data.networkSafetyIdentity = "generation-A";
        data.networkSafetyRequestToken = 45L;
        GuiQuantumTerminal.receiveData(data);
        ActualGui gui = actualGui(true);
        assertNull(gui.find(102));
        gui.actionPerformed(new GuiButton(100, 0, 0, ""));
        GuiButton safety = gui.find(102);
        assertNotNull(safety);
        assertTrue(safety.enabled);
        assertTrue(safety.displayString.endsWith(": ON"));
        assertEquals(178, safety.xPosition);
        assertEquals(128, safety.yPosition);
        assertTrue(safety.xPosition + safety.width <= 292);
        assertTrue(safety.yPosition + safety.height <= 146);
        data.networkSafetyEnabled = false;
        gui.actionPerformed(new GuiButton(100, 0, 0, ""));
        assertTrue(gui.find(102).enabled);
        assertTrue(gui.find(102).displayString.endsWith(": OFF"));
        data.canChangeNetworkSafety = false;
        gui.actionPerformed(new GuiButton(100, 0, 0, ""));
        assertFalse(gui.find(102).enabled);
        assertEquals("gtswn.gui.quantum.safety_permission", GuiQuantumTerminal.safetyReason(data));
        data.canChangeNetworkSafety = true;
        data.globalSafetyEnabled = false;
        gui.actionPerformed(new GuiButton(100, 0, 0, ""));
        assertFalse(gui.find(102).enabled);
        assertEquals("gtswn.gui.quantum.safety_global_off", GuiQuantumTerminal.safetyReason(data));
        data.globalSafetyEnabled = true;
        data.online = false;
        assertFalse(GuiQuantumTerminal.canToggleSafety(data));
        assertEquals("gtswn.gui.quantum.safety_offline", GuiQuantumTerminal.safetyReason(data));
        assertFalse(GuiQuantumTerminal.canToggleSafety(new QuantumNetworkData()));
        assertFalse(GuiQuantumTerminal.canToggleSafety(null));
        data.online = true;
        data.networkSafetyRequestToken = 0;
        assertFalse(GuiQuantumTerminal.canToggleSafety(data));
        assertEquals("gtswn.gui.quantum.safety_waiting", GuiQuantumTerminal.safetyReason(data));
        ActualGui tap = actualGui(false);
        tap.actionPerformed(new GuiButton(100, 0, 0, ""));
        assertNull(tap.find(102));
    }

    @Test
    public void tapVoltageButtonTogglesPersistsAndResetRefreshesItsLabel() throws Exception {
        ActualGui gui = actualGui(false);
        assertTrue(gui.find(116).displayString.endsWith(": ON"));
        gui.activate(116);
        assertFalse(Config.tapVoltageColorsEnabled);
        assertTrue(gui.find(116).displayString.endsWith(": OFF"));
        Config.synchronizeNetworkConfiguration(configFile);
        assertFalse(Config.tapVoltageColorsEnabled);
        gui.activate(115);
        assertTrue(Config.tapVoltageColorsEnabled);
        assertTrue(gui.find(116).displayString.endsWith(": ON"));
        Config.synchronizeNetworkConfiguration(configFile);
        assertTrue(Config.tapVoltageColorsEnabled);
    }

    @Test
    public void tapSettingsFitAboveInventoryWithoutOverlappingAndNumericRightClickStillDecreases() throws Exception {
        ActualGui gui = actualGui(false);
        List<GuiButton> settings = gui.settings();
        assertEquals(8, settings.size());
        for (GuiButton button : settings) {
            assertTrue(button.xPosition >= 10);
            assertTrue(button.xPosition + button.width <= 290);
            assertTrue(button.yPosition >= 26);
            assertTrue(button.yPosition + button.height <= 126);
            for (GuiButton other : settings) {
                if (button == other) continue;
                assertFalse(
                    button.xPosition < other.xPosition + other.width
                        && button.xPosition + button.width > other.xPosition
                        && button.yPosition < other.yPosition + other.height
                        && button.yPosition + button.height > other.yPosition);
            }
        }
        gui.activate(111);
        assertEquals(125, Config.tapParticleDensityPercent);
        gui.rightClick(111);
        assertEquals(100, Config.tapParticleDensityPercent);
        assertTrue(Config.tapVoltageColorsEnabled);
    }

    @Test
    public void quantumRetainsItsFiveButtonsAndResetDoesNotChangeTapVoltage() throws Exception {
        Config.tapVoltageColorsEnabled = false;
        Config.tapVoltageColorPercent = 25;
        Config.saveParticleConfiguration();
        ActualGui gui = actualGui(true);
        assertEquals(
            5,
            gui.settings()
                .size());
        assertNull(gui.find(114));
        assertNull(gui.find(116));
        assertNull(gui.find(117));
        assertEquals(154, gui.find(115).xPosition);
        assertEquals(94, gui.find(115).yPosition);
        assertEquals(28, gui.find(115).height);
        gui.activate(115);
        Config.synchronizeNetworkConfiguration(configFile);
        assertFalse(Config.tapVoltageColorsEnabled);
        assertEquals(25, Config.tapVoltageColorPercent);
        gui.actionPerformed(new GuiButton(116, 0, 0, ""));
        assertFalse(Config.tapVoltageColorsEnabled);
        gui.actionPerformed(new GuiButton(117, 0, 0, ""));
        assertEquals(25, Config.tapVoltageColorPercent);
    }

    @Test
    public void ratioButtonCyclesBothWaysPersistsAndResetRestoresDefault() throws Exception {
        ActualGui gui = actualGui(false);
        assertEquals(75, Config.tapVoltageColorPercent);
        assertEquals(154, gui.find(117).xPosition);
        assertEquals(78, gui.find(117).yPosition);
        assertEquals(104, gui.find(115).yPosition);
        assertTrue(gui.find(117).displayString.endsWith(": 75%"));
        gui.activate(117);
        assertEquals(100, Config.tapVoltageColorPercent);
        assertTrue(gui.find(117).displayString.endsWith(": 100%"));
        Config.synchronizeNetworkConfiguration(configFile);
        assertEquals(100, Config.tapVoltageColorPercent);
        gui.activate(117);
        assertEquals(25, Config.tapVoltageColorPercent);
        assertTrue(gui.find(117).displayString.endsWith(": 25%"));
        Config.synchronizeNetworkConfiguration(configFile);
        assertEquals(25, Config.tapVoltageColorPercent);
        gui.rightClick(117);
        assertEquals(100, Config.tapVoltageColorPercent);
        gui.rightClick(117);
        assertEquals(75, Config.tapVoltageColorPercent);
        gui.rightClick(117);
        assertEquals(50, Config.tapVoltageColorPercent);
        assertTrue(gui.find(117).displayString.endsWith(": 50%"));
        gui.activate(115);
        assertEquals(75, Config.tapVoltageColorPercent);
        assertTrue(gui.find(117).displayString.endsWith(": 75%"));
        Config.synchronizeNetworkConfiguration(configFile);
        assertEquals(75, Config.tapVoltageColorPercent);
    }
}
