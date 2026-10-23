package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.*;

import java.io.File;
import java.lang.reflect.Field;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.miaokatze.gtswn.client.engineering.FactoryDisplayPreferences.Key;
import com.miaokatze.miaokatzecore.api.camera.HeightSlice;

public class FactoryDisplayPreferencesTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();
    private Field home;
    private Object originalHome;

    @Before
    public void initializeConfigDirectory() throws Exception {
        home = cpw.mods.fml.relauncher.FMLInjectionData.class.getDeclaredField("minecraftHome");
        home.setAccessible(true);
        originalHome = home.get(null);
        home.set(null, temporary.getRoot());
    }

    @After
    public void restoreConfigDirectory() throws Exception {
        home.set(null, originalHome);
    }

    @Test
    public void displayValuesAndProfileSlicesRoundTripWithoutWorldSamples() {
        File file = new File(temporary.getRoot(), "display.cfg");
        FactoryDisplayPreferences.Store first = new FactoryDisplayPreferences.Store(file);
        assertFalse(file.exists());
        first.put(Key.SELECTED_SLOT, 5);
        first.put(Key.PAGE, 3);
        first.put(Key.SETTINGS_PAGE, 1);
        first.put(Key.RAIL, 0);
        first.put(Key.GRID, 2);
        first.put(Key.NODE_5, 1);
        first.put(Key.MACHINE_FILTER, 6);
        first.put(Key.YAW, 71.5);
        first.put(Key.ZOOM, 2.75);
        String profile = FactoryDisplayPreferences.profile("local:world1", "player1", 0);
        HeightSlice slice = HeightSlice.between(23, 77, 1.5)
            .withFeatheredCaps(true)
            .withLightCompensation(true);
        first.text(profile, FactoryDisplayPreferences.encode(slice));
        first.save();
        assertFalse(first.dirty());
        FactoryDisplayPreferences.Store reloaded = new FactoryDisplayPreferences.Store(file);
        for (Key key : Key.values()) assertEquals(first.number(key), reloaded.number(key), 0);
        assertEquals(slice, FactoryDisplayPreferences.decode(reloaded.text(profile)));
        assertEquals("", reloaded.text(FactoryDisplayPreferences.profile("local:world2", "player1", 0)));
        assertEquals("", reloaded.text(FactoryDisplayPreferences.profile("local:world1", "player2", 0)));
        assertEquals("", reloaded.text(FactoryDisplayPreferences.profile("local:world1", "player1", 1)));
        assertEquals("", reloaded.text(FactoryDisplayPreferences.profile("server:world1", "player1", 0)));
        reloaded.put(Key.ZOOM, 2.75);
        reloaded.text(profile, reloaded.text(profile));
        assertFalse(reloaded.dirty());
    }

    @Test
    public void continuousCameraChangesCoalesceAndExplicitCloseCanFlushImmediately() {
        File file = new File(temporary.getRoot(), "display.cfg");
        FactoryDisplayPreferences.Store store = new FactoryDisplayPreferences.Store(file);
        store.put(Key.YAW, 50);
        store.saveIfDue(0);
        store.put(Key.YAW, 80);
        store.saveIfDue(500_000_000L);
        assertTrue(store.dirty());
        assertEquals(50, new FactoryDisplayPreferences.Store(file).number(Key.YAW), 0);
        store.saveIfDue(1_000_000_000L);
        assertFalse(store.dirty());
        assertEquals(80, new FactoryDisplayPreferences.Store(file).number(Key.YAW), 0);
        store.put(Key.YAW, 95);
        store.save();
        assertEquals(95, new FactoryDisplayPreferences.Store(file).number(Key.YAW), 0);
    }

    @Test
    public void corruptValuesCannotProduceInvalidCameraOrUiState() {
        assertEquals(45, Key.YAW.sanitize(Double.NaN), 0);
        assertEquals(8, Key.ZOOM.sanitize(1000), 0);
        assertEquals(0, Key.GRID.sanitize(-10), 0);
        assertEquals(3, Key.PAGE.sanitize(99), 0);
        assertFalse(
            FactoryDisplayPreferences.decode("1,1,90,20,.25,true,true")
                .enabled());
        assertFalse(
            FactoryDisplayPreferences.decode("1,0,20,0,NaN,true,true")
                .enabled());
        assertFalse(
            FactoryDisplayPreferences.decode("bad data")
                .enabled());
    }

    @Test
    public void disabledXrayRemembersTheWholePolicyAcrossPersistence() {
        FactoryXrayState state = new FactoryXrayState();
        HeightSlice slice = HeightSlice.above(42, .75)
            .withLightCompensation(true);
        state.request(slice);
        state.toggle(80);
        FactoryXrayState restored = new FactoryXrayState();
        restored.restore(
            FactoryDisplayPreferences.decode(FactoryDisplayPreferences.encode(state.requested())),
            FactoryDisplayPreferences.decode(FactoryDisplayPreferences.encode(state.remembered())));
        assertFalse(
            restored.requested()
                .enabled());
        restored.toggle(100);
        assertEquals(slice, restored.requested());
    }
}
