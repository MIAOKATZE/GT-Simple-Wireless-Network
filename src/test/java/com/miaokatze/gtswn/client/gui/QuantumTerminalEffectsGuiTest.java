package com.miaokatze.gtswn.client.gui;

import static org.junit.Assert.*;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.PagedWidget;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.miaokatze.gtswn.common.gui.ContainerTerminal;
import com.miaokatze.gtswn.common.gui.TerminalGuiFactory;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;
import com.miaokatze.gtswn.config.Config;

import cpw.mods.fml.relauncher.FMLInjectionData;

/** Invokes the production MUI2 effect buttons without a Minecraft client or GL context. */
public class QuantumTerminalEffectsGuiTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();
    private final Map<Field, Object> originalConfig = new HashMap<>();
    private Field minecraftHome;
    private Object originalHome;
    private File configFile;

    private ParentWidget<?> effects(boolean quantum) throws Exception {
        Field factory = TerminalGuiFactory.class.getDeclaredField(quantum ? "QUANTUM" : "LINK");
        factory.setAccessible(true);
        Method build = TerminalGuiFactory.class.getDeclaredMethod("effects", EntityPlayer.class);
        build.setAccessible(true);
        return (ParentWidget<?>) build.invoke(factory.get(null), new Object[] { null });
    }

    private ButtonWidget<?> find(ParentWidget<?> page, int setting) {
        for (IWidget child : page.getChildren()) {
            if (child.getName()
                .endsWith(".effects." + setting)) return (ButtonWidget<?>) child;
        }
        return null;
    }

    private void activate(ParentWidget<?> page, int setting, boolean right) {
        ButtonWidget<?> button = find(page, setting);
        assertNotNull(button);
        button.playClickSound(false)
            .onMousePressed(right ? 1 : 0);
    }

    @Test
    public void nativeRenameFieldKeepsEAndChineseDraftWhenSnapshotsArrive() throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeType.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        EntityPlayer player = (EntityPlayer) unsafeType.getMethod("allocateInstance", Class.class)
            .invoke(unsafeField.get(null), EntityPlayerMP.class);
        player.inventory = new InventoryPlayer(player);
        Field factory = TerminalGuiFactory.class.getDeclaredField("QUANTUM");
        factory.setAccessible(true);
        Method build = TerminalGuiFactory.class.getDeclaredMethod(
            "rename",
            EntityPlayer.class,
            ContainerTerminal.class,
            PagedWidget.Controller.class,
            PagedWidget.class);
        build.setAccessible(true);
        ParentWidget<?> page = (ParentWidget<?>) build
            .invoke(factory.get(null), player, null, new PagedWidget.Controller(), new PagedWidget<>());
        TextFieldWidget field = null;
        for (IWidget child : page.getChildren())
            if ("quantum.rename.field".equals(child.getName())) field = (TextFieldWidget) child;
        assertNotNull(field);
        field.setText("量子e中");
        GuiQuantumTerminal.receiveData(new QuantumNetworkData());
        assertEquals("量子e中", field.getText());
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
        for (Map.Entry<Field, Object> entry : originalConfig.entrySet()) entry.getKey()
            .set(null, entry.getValue());
        minecraftHome.set(null, originalHome);
    }

    @Test
    public void quantumSafetyControlKeepsServerAuthorityAcrossBothNetworkModes() {
        QuantumNetworkData data = new QuantumNetworkData();
        data.online = true;
        data.canChangeNetworkSafety = true;
        data.networkSafetyIdentity = "generation-A";
        data.networkSafetyRequestToken = 45L;
        data.managementOnly = true;
        assertTrue(GuiQuantumTerminal.canToggleSafety(data));
        data.networkSafetyEnabled = true;
        assertTrue(GuiQuantumTerminal.canToggleSafety(data));
        data.canChangeNetworkSafety = false;
        assertFalse(GuiQuantumTerminal.canToggleSafety(data));
        assertEquals("gtswn.gui.quantum.safety_permission", GuiQuantumTerminal.safetyReason(data));
        data.canChangeNetworkSafety = true;
        data.globalSafetyEnabled = false;
        assertFalse(GuiQuantumTerminal.canToggleSafety(data));
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
    }

    @Test
    public void tapVoltageButtonTogglesPersistsAndResetRefreshesItsLabel() throws Exception {
        ParentWidget<?> gui = effects(false);
        assertTrue(
            ((IKey) find(gui, 6).getOverlay()).getFormatted()
                .endsWith(": ON"));
        activate(gui, 6, false);
        assertFalse(Config.tapVoltageColorsEnabled);
        Config.synchronizeNetworkConfiguration(configFile);
        assertFalse(Config.tapVoltageColorsEnabled);
        activate(gui, 5, false);
        assertTrue(Config.tapVoltageColorsEnabled);
        Config.synchronizeNetworkConfiguration(configFile);
        assertTrue(Config.tapVoltageColorsEnabled);
    }

    @Test
    public void tapKeepsEightNamedSettingsAndNumericRightClickStillDecreases() throws Exception {
        ParentWidget<?> gui = effects(false);
        assertEquals(
            8,
            gui.getChildren()
                .size());
        for (int setting = 0; setting < 8; setting++) assertNotNull(find(gui, setting));
        activate(gui, 1, false);
        assertEquals(125, Config.tapParticleDensityPercent);
        activate(gui, 1, true);
        assertEquals(100, Config.tapParticleDensityPercent);
        assertTrue(Config.tapVoltageColorsEnabled);
        boolean enabled = Config.tapParticlesEnabled;
        String material = Config.tapMaterial;
        activate(gui, 0, true);
        activate(gui, 4, true);
        activate(gui, 5, true);
        activate(gui, 6, true);
        assertEquals(enabled, Config.tapParticlesEnabled);
        assertEquals(material, Config.tapMaterial);
        assertEquals(100, Config.tapParticleDensityPercent);
        assertTrue(Config.tapVoltageColorsEnabled);
    }

    @Test
    public void quantumRetainsItsFiveButtonsAndResetDoesNotChangeTapVoltage() throws Exception {
        Config.tapVoltageColorsEnabled = false;
        Config.tapVoltageColorPercent = 25;
        Config.saveParticleConfiguration();
        ParentWidget<?> gui = effects(true);
        assertEquals(
            5,
            gui.getChildren()
                .size());
        assertNull(find(gui, 4));
        assertNull(find(gui, 6));
        assertNull(find(gui, 7));
        activate(gui, 5, false);
        Config.synchronizeNetworkConfiguration(configFile);
        assertFalse(Config.tapVoltageColorsEnabled);
        assertEquals(25, Config.tapVoltageColorPercent);
        TerminalGuiFactory.changeEffect(true, 6, false);
        TerminalGuiFactory.changeEffect(true, 7, false);
        assertFalse(Config.tapVoltageColorsEnabled);
        assertEquals(25, Config.tapVoltageColorPercent);
    }

    @Test
    public void ratioButtonCyclesBothWaysPersistsAndResetRestoresDefault() throws Exception {
        ParentWidget<?> gui = effects(false);
        assertEquals(75, Config.tapVoltageColorPercent);
        activate(gui, 7, false);
        assertEquals(100, Config.tapVoltageColorPercent);
        Config.synchronizeNetworkConfiguration(configFile);
        assertEquals(100, Config.tapVoltageColorPercent);
        activate(gui, 7, false);
        assertEquals(25, Config.tapVoltageColorPercent);
        Config.synchronizeNetworkConfiguration(configFile);
        assertEquals(25, Config.tapVoltageColorPercent);
        activate(gui, 7, true);
        assertEquals(100, Config.tapVoltageColorPercent);
        activate(gui, 7, true);
        assertEquals(75, Config.tapVoltageColorPercent);
        activate(gui, 7, true);
        assertEquals(50, Config.tapVoltageColorPercent);
        activate(gui, 5, false);
        assertEquals(75, Config.tapVoltageColorPercent);
        Config.synchronizeNetworkConfiguration(configFile);
        assertEquals(75, Config.tapVoltageColorPercent);
    }
}
