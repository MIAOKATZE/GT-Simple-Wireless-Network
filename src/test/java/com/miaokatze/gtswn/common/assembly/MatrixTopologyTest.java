// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.assembly;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraftforge.common.util.ForgeDirection;

import org.junit.Test;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.miaokatze.gtswn.assembly.nativeintegration.NativeAssemblyAdapter;
import com.miaokatze.gtswn.assembly.nativeintegration.NativeAssemblySession;
import com.miaokatze.gtswn.common.assembly.pattern.OrderedPatternLayout;
import com.miaokatze.gtswn.test.FluidRegistryTestBootstrap;

import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.crafting.ICraftingProviderHelper;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.me.helpers.AENetworkProxy;
import appeng.util.ReadOnlyCollection;
import cpw.mods.fml.common.Loader;
import gregtech.api.GregTechAPI;
import gregtech.api.enums.ItemList;
import gregtech.api.interfaces.IDataCopyable;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.CommonMetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchInput;
import gregtech.api.metatileentity.implementations.MTEHatchInputBus;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.util.GTRecipe.RecipeAssemblyLine;
import gregtech.common.tileentities.machines.multi.MTEAssemblyLine;

public class MatrixTopologyTest {

    @Test
    public void detachedLiveControllerClaimsDoNotBlockANewOwnerButRealSharedInputsRemainRejected() throws Exception {
        Fixture fixture = new Fixture();
        MTEHatchInputBus member = (MTEHatchInputBus) fixture.add(false, 0, 64, 0);
        initializeNativeAssemblyLine(fixture.unsafe);
        MTEAssemblyLine first = new MTEAssemblyLine("matrix_identity_first");
        MTEAssemblyLine second = new MTEAssemblyLine("matrix_identity_second");
        fixture.attach(first, 20, 64, 20);
        fixture.attach(second, 24, 64, 20);
        first.mInputBusses.add(member);
        second.mInputBusses.add(member);
        NativeAssemblySession before = new NativeAssemblySession(first);
        NativeAssemblySession after = new NativeAssemblySession(second);
        Class<?> index = Class.forName("com.miaokatze.gtswn.assembly.nativeintegration.NativeAssemblyIdentityIndex");
        Method verify = index.getDeclaredMethod("verify", NativeAssemblySession.class);
        Method removed = index.getDeclaredMethod("removed", NativeAssemblySession.class);
        verify.setAccessible(true);
        removed.setAccessible(true);
        try {
            assertTrue((Boolean) verify.invoke(null, before));
            assertFalse(
                "Two live controllers still using the same member must conflict",
                (Boolean) verify.invoke(null, after));
            assertFalse((Boolean) verify.invoke(null, before));
            first.mInputBusses.clear();
            assertTrue(NativeAssemblyAdapter.isLive(first));
            assertTrue(
                "A live controller that no longer contains this member must release its obsolete ownership claim",
                (Boolean) verify.invoke(null, after));
            assertTrue((Boolean) verify.invoke(null, before));
            MatrixAssemblyMember duplicate = fixture.add(false, 1, 64, 0);
            field(MatrixAssemblyPort.class, "memberId")
                .set(duplicate.matrixPort(), ((MatrixAssemblyMember) member).orderedMemberId());
            second.mInputBusses.clear();
            first.mInputBusses.add((MTEHatchInputBus) duplicate);
            assertTrue(
                "A copied UUID at another physical member must not block the independent structure",
                (Boolean) verify.invoke(null, before));
        } finally {
            removed.invoke(null, before);
            removed.invoke(null, after);
        }
    }

    @Test
    public void threeCopiedAssemblyLinesKeepTheirSavedUuidsAtIndependentWorldPositions() throws Exception {
        Fixture fixture = new Fixture();
        fixture.world.provider.dimensionId = 180;
        initializeNativeAssemblyLine(fixture.unsafe);
        String[] itemIds = { "6290cd28-f666-48dd-bdaa-bbe913a457c2", "38dfb18e-e61d-479f-b4c8-0b3cf96a43fc",
            "012a74e2-535b-413a-9b6e-cf0d48ff9b7f", "051e7689-c02d-4c0a-81bc-83ab7ada08c6",
            "8af4b738-fdaa-4c87-b369-36ea3199e70e", "25656092-f6d5-471f-b7c8-2709c025df8b",
            "78cb1a80-baf6-452a-9886-2eea5853df3a", "c6a9696e-4632-4781-8673-11e5827139ca",
            "337c7b34-0eed-4753-8a87-4d3a9b26b7ca", "ab19d526-93f5-4561-a4c1-7158c38fa214" };
        String[] fluidIds = { "9e0f9d8d-23cf-416b-8762-aaf3358fdf9f", "2babfacc-8484-46c3-a506-eb19d2727e36",
            "547028cf-74f9-4d6d-bf17-7609806ec9f6", "949e3fa3-7c19-4972-afc3-97f0f316663a" };
        NBTTagCompound identity = savedNativeIdentity(UUID.fromString("686f3a20-1351-42c9-b770-37e81621fa2e"));
        List<NativeAssemblySession> sessions = new ArrayList<>();
        Method verify = identityIndexMethod("verify"), removed = identityIndexMethod("removed");
        try {
            // Exact controller/member positions and UUID relationships from the read-only dimension-180 evidence.
            for (int line = 0; line < 3; line++) {
                int z = 40 + line * 6;
                MTEAssemblyLine controller = new MTEAssemblyLine("matrix_copied_line_" + line);
                fixture.attach(controller, 219, 40, z);
                for (int i = 0; i < itemIds.length; i++) {
                    MatrixAssemblyMember member = fixture.add(false, 219 + i, 38, z - 1);
                    String id = itemIds[i];
                    if (i == 0 && line < 2) id = line == 0 ? "097ae118-4f79-46a6-851b-dd6b35279f71"
                        : "0b2bd3af-bcaf-4ee1-b35e-31dcdc0914a1";
                    if (i == 1 && line == 1) id = "d9c8a322-8910-4f41-ac1b-78679ac8ef0b";
                    field(MatrixAssemblyPort.class, "memberId").set(member.matrixPort(), UUID.fromString(id));
                    controller.mInputBusses.add((MTEHatchInputBus) member);
                }
                for (int i = 0; i < fluidIds.length; i++) {
                    MatrixAssemblyMember member = fixture.add(true, 219 + i, 38, z);
                    String id = i == 3 && line < 2 ? "4ea5fac0-c3de-4035-bafe-6f81d4e80b0b" : fluidIds[i];
                    field(MatrixAssemblyPort.class, "memberId").set(member.matrixPort(), UUID.fromString(id));
                    controller.mInputHatches.add((MTEHatchInput) member);
                }
                NativeAssemblySession session = new NativeAssemblySession(controller);
                session.load(identity);
                sessions.add(session);
                OrderedAssemblyGroup.refreshTopology((MatrixAssemblyMember) controller.mInputBusses.get(0));
                assertEquals(
                    14,
                    ((MatrixAssemblyMember) controller.mInputBusses.get(0)).matrixPort()
                        .group()
                        .physicalMembers()
                        .size());
                assertTrue("Independent copied line at z=" + z, (Boolean) verify.invoke(null, session));
            }
            for (NativeAssemblySession session : sessions) {
                assertTrue((Boolean) verify.invoke(null, session));
                NBTTagCompound after = new NBTTagCompound();
                session.save(after);
                assertEquals("Verification must not rewrite copied controller identity", identity, after);
            }
        } finally {
            for (NativeAssemblySession session : sessions) removed.invoke(null, session);
        }
    }

    @Test
    public void samePositionReloadAndSeparateWorldCopiesRetainTheirSavedIdentity() throws Exception {
        Fixture fixture = new Fixture();
        initializeNativeAssemblyLine(fixture.unsafe);
        MatrixAssemblyMember original = fixture.add(false, 0, 64, 0);
        MTEAssemblyLine first = new MTEAssemblyLine("matrix_identity_before_reload");
        fixture.attach(first, 20, 64, 20);
        first.mInputBusses.add((MTEHatchInputBus) original);
        NativeAssemblySession before = new NativeAssemblySession(first);
        NBTTagCompound identity = new NBTTagCompound();
        before.save(identity);
        List<NativeAssemblySession> sessions = new ArrayList<>();
        sessions.add(before);
        Method verify = identityIndexMethod("verify"), removed = identityIndexMethod("removed");
        try {
            assertTrue((Boolean) verify.invoke(null, before));
            for (Fixture destination : new Fixture[] { fixture, new Fixture() }) {
                MatrixAssemblyMember reloaded = destination.add(false, 0, 64, 0);
                field(MatrixAssemblyPort.class, "memberId").set(reloaded.matrixPort(), original.orderedMemberId());
                MTEAssemblyLine controller = new MTEAssemblyLine("matrix_identity_reloaded");
                destination.attach(controller, 20, 64, 20);
                controller.mInputBusses.add((MTEHatchInputBus) reloaded);
                NativeAssemblySession restored = new NativeAssemblySession(controller);
                restored.load(identity);
                sessions.add(restored);
                assertTrue((Boolean) verify.invoke(null, restored));
                NBTTagCompound after = new NBTTagCompound();
                restored.save(after);
                assertEquals(identity, after);
                assertEquals(original.orderedMemberId(), reloaded.orderedMemberId());
            }
        } finally {
            for (NativeAssemblySession session : sessions) removed.invoke(null, session);
        }
    }

    @Test
    public void duplicateUuidsWithinOnePhysicalGroupStillFailTheExistingTopologyGate() throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> items = fixture.items(false);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        OrderedAssemblyGroup group = items.get(0)
            .matrixPort()
            .group();
        field(MatrixAssemblyPort.class, "memberId").set(
            items.get(1)
                .matrixPort(),
            items.get(0)
                .orderedMemberId());
        Method check = OrderedAssemblyGroup.class.getDeclaredMethod("checkTopology");
        check.setAccessible(true);
        assertFalse((Boolean) check.invoke(group));
        assertEquals(
            "duplicate_identity",
            group.saveLedger()
                .getString("reason"));
    }

    private static Method identityIndexMethod(String name) throws Exception {
        Class<?> index = Class.forName("com.miaokatze.gtswn.assembly.nativeintegration.NativeAssemblyIdentityIndex");
        Method method = index.getDeclaredMethod(name, NativeAssemblySession.class);
        method.setAccessible(true);
        return method;
    }

    private static NBTTagCompound savedNativeIdentity(UUID id) {
        NBTTagCompound identity = new NBTTagCompound(), root = new NBTTagCompound();
        identity.setInteger("schema", 1);
        identity.setLong("uuidMost", id.getMostSignificantBits());
        identity.setLong("uuidLeast", id.getLeastSignificantBits());
        identity.setLong("runSequence", 20);
        identity.setBoolean("recordedRunning", false);
        identity.setString("lastNativeEvent", "LOCAL_FINISHED");
        root.setTag("gtswnOrderedAssemblyController", identity);
        return root;
    }

    private static void initializeNativeAssemblyLine(sun.misc.Unsafe unsafe) throws Exception {
        // Reuse the headless Loader precondition from FluidRegistryTestBootstrap. The real AL's static
        // structure also requires two nonnull casing blocks, even though this test never scans a structure.
        Field instance = field(Loader.class, "instance");
        Object previousLoader = instance.get(null);
        Block previousSecond = GregTechAPI.sBlockCasings2, previousThird = GregTechAPI.sBlockCasings3;
        Thread thread = Thread.currentThread();
        String previousName = thread.getName();
        try {
            if (previousLoader == null) {
                Object headless = unsafe.allocateInstance(Loader.class);
                field(Loader.class, "namedMods").set(headless, Collections.emptyMap());
                instance.set(null, headless);
            }
            if (previousSecond == null) GregTechAPI.sBlockCasings2 = new Block(Material.iron) {};
            if (previousThird == null) GregTechAPI.sBlockCasings3 = new Block(Material.iron) {};
            Field hint = field(com.gtnewhorizon.structurelib.StructureLib.class, "blockHint");
            Object previousHint = hint.get(null);
            try {
                if (previousHint == null) hint.set(null, new Block(Material.iron) {});
                thread.setName("Server thread");
                Class.forName(MTEAssemblyLine.class.getName(), true, MatrixTopologyTest.class.getClassLoader());
            } finally {
                hint.set(null, previousHint);
            }
        } finally {
            thread.setName(previousName);
            GregTechAPI.sBlockCasings2 = previousSecond;
            GregTechAPI.sBlockCasings3 = previousThird;
            instance.set(null, previousLoader);
        }
    }

    @Test
    public void aFormedNativeControllersCurrentIdentityFailureIsReportedByBothStatusAndPatternPreview()
        throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> items = fixture.items(false);
        initializeNativeAssemblyLine(fixture.unsafe);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        OrderedAssemblyGroup group = items.get(0)
            .matrixPort()
            .group();
        Graph graph = new Graph(fixture);
        graph.external(items.get(0));
        MTEAssemblyLine controller = new MTEAssemblyLine("matrix_identity_diagnostic");
        fixture.attach(controller, 20, 64, 20);
        for (MatrixAssemblyMember member : items) controller.mInputBusses.add((MTEHatchInputBus) member);
        controller.mMachine = true;
        OrderedAssemblyGroup.controllerChecked(items.get(0), controller, true);
        NativeAssemblySession session = new NativeAssemblySession(controller);
        NBTTagCompound saved = new NBTTagCompound(), unknown = new NBTTagCompound();
        unknown.setInteger("schema", 42);
        saved.setTag("gtswnOrderedAssemblyController", unknown);
        session.load(saved);
        field(NativeAssemblySession.class, "blockedReason").set(session, "old_recipe_check_failure");
        field(OrderedAssemblyGroup.class, "session").set(group, session);
        field(OrderedAssemblyGroup.class, "nativeBound").setBoolean(group, true);
        OrderedPatternLayout pattern = layout(1, new int[0]);
        group.master()
            .matrixPort()
            .patterns()
            .setStackInSlot(0, pattern.pattern());
        assertEquals("unknown_controller_identity", session.patternAdvertisementReason());
        assertFalse(session.readyForPatternAdvertisement());
        assertEquals("unknown_controller_identity", group.reason());
        assertEquals("unknown_controller_identity", group.previewReason(pattern));
        group.provideCrafting(
            (ICraftingProviderHelper) Proxy.newProxyInstance(
                ICraftingProviderHelper.class.getClassLoader(),
                new Class<?>[] { ICraftingProviderHelper.class },
                (proxy, method, args) -> {
                    throw new AssertionError("An invalid native identity advertised a pattern");
                }));
    }

    @Test
    public void horizontalBareColumnsConfigureWithZeroThroughFourFluidsWithoutGrantingNativeAccess() throws Exception {
        for (boolean alongZ : new boolean[] { false, true }) for (int fluidCount = 0; fluidCount <= 4; fluidCount++) {
            Fixture fixture = new Fixture();
            List<MatrixAssemblyMember> items = fixture.items(alongZ);
            for (int i = 0; i < fluidCount; i++) fixture.add(true, alongZ ? 1 : i, 64, alongZ ? i : 1);
            OrderedAssemblyGroup.refreshTopology(items.get(3));
            OrderedAssemblyGroup group = items.get(0)
                .matrixPort()
                .group();
            assertNotNull(group);
            assertNull(group.session());
            assertNull(group.diagnosticControllerPosition());
            assertTrue(group.editable());
            assertFalse(group.accepting());
            assertFalse(group.validateNativeState(new NativeAssemblySession(null)));
            OrderedAssemblyGroup.structureChecked(items.get(0), new NativeAssemblySession(null), false);
            assertSame(
                group,
                items.get(0)
                    .matrixPort()
                    .group());
            assertTrue(group.editable());
            assertEquals(
                fluidCount,
                group.fluidMembers()
                    .size());
            for (MatrixAssemblyMember member : fixture.members) {
                assertSame(
                    group,
                    member.matrixPort()
                        .group());
                assertFalse(NativeAssemblyAdapter.permitsNativeAccess(member));
                assertEquals(
                    fixture.expectedMask(member),
                    member.matrixPort()
                        .connectionMask());
            }
            group.master()
                .matrixPort()
                .patterns()
                .setStackInSlot(
                    0,
                    layout(1, new int[0]).details()
                        .getPattern());
            group.provideCrafting(
                (ICraftingProviderHelper) Proxy.newProxyInstance(
                    ICraftingProviderHelper.class.getClassLoader(),
                    new Class<?>[] { ICraftingProviderHelper.class },
                    (proxy, method, args) -> {
                        throw new AssertionError("An unformed group must not advertise its stored pattern");
                    }));
            NBTTagCompound ledger = group.saveLedger();
            assertFalse(ledger.hasKey("controller"));
            assertFalse(ledger.hasKey("controllerPosition"));
            assertEquals(
                group.master()
                    .orderedMemberId()
                    .toString(),
                ledger.getString("master"));
        }
    }

    @Test
    public void verticalSingleTypeColumnsKeepHorizontalFrontsAndVerifiedUpDownConnections() throws Exception {
        for (boolean fluid : new boolean[] { false, true }) {
            Fixture fixture = new Fixture();
            int count = fluid ? 4 : 16;
            for (int i = 0; i < count; i++) {
                MatrixAssemblyMember member = fixture.add(fluid, 0, 64 + i, 0);
                field(BaseMetaTileEntity.class, "mFacing").set(member.getBaseMetaTileEntity(), ForgeDirection.NORTH);
            }
            OrderedAssemblyGroup.refreshTopology(fixture.members.get(count - 1));
            OrderedAssemblyGroup group = fixture.members.get(0)
                .matrixPort()
                .group();
            assertNotNull(group);
            assertTrue(group.editable());
            assertNull(group.session());
            assertFalse(group.accepting());
            for (int i = 0; i < count; i++) {
                MatrixAssemblyMember member = fixture.members.get(i);
                assertSame(
                    group,
                    member.matrixPort()
                        .group());
                int mask = (i > 0 ? ForgeDirection.DOWN.flag : 0) | (i + 1 < count ? ForgeDirection.UP.flag : 0);
                assertEquals(
                    mask,
                    member.matrixPort()
                        .connectionMask());
                EnumSet<ForgeDirection> sides = EnumSet.of(ForgeDirection.NORTH);
                if (i > 0) sides.add(ForgeDirection.DOWN);
                if (i + 1 < count) sides.add(ForgeDirection.UP);
                assertEquals(sides, MatrixAssemblyPort.connectionSides(ForgeDirection.NORTH, mask));
                assertEquals(
                    (i > 0 ? 8 : 0) | (i + 1 < count ? 4 : 0),
                    MatrixAssemblyTextures.faceMask(mask, ForgeDirection.NORTH));
            }
        }
    }

    @Test
    public void verticalParallelTypedColumnsAllowFluidGapsAndOneMemberOfTheOtherType() throws Exception {
        for (boolean besideX : new boolean[] { false, true }) for (int fluidCount = 1; fluidCount <= 4; fluidCount++) {
            Fixture fixture = new Fixture();
            for (int i = 0; i < 7; i++) fixture.add(false, 0, 64 + i, 0);
            for (int i = 0; i < fluidCount; i++) fixture.add(true, besideX ? 1 : 0, 64 + i * 2, besideX ? 0 : 1);
            OrderedAssemblyGroup.refreshTopology(fixture.members.get(3));
            OrderedAssemblyGroup group = fixture.members.get(0)
                .matrixPort()
                .group();
            assertNotNull(group);
            assertTrue(group.editable());
            assertEquals(
                fluidCount,
                group.fluidMembers()
                    .size());
            for (MatrixAssemblyMember member : fixture.members) assertEquals(
                fixture.expectedMask(member),
                member.matrixPort()
                    .connectionMask());
        }
        Fixture fixture = new Fixture();
        MatrixAssemblyMember item = fixture.add(false, 0, 64, 0);
        fixture.add(true, 1, 64, 0);
        fixture.add(true, 1, 65, 0);
        OrderedAssemblyGroup.refreshTopology(item);
        assertTrue(
            item.matrixPort()
                .group()
                .editable());
    }

    @Test
    public void verticalTopologyStillRejectsMixedAxesBranchesItemGapsAndDetachedColumns() throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> items = new ArrayList<>(), fluids = new ArrayList<>();
        items.add(fixture.add(false, 0, 64, 0));
        items.add(fixture.add(false, 0, 65, 0));
        fluids.add(fixture.add(true, 1, 64, 0));
        fluids.add(fixture.add(true, 2, 64, 0));
        assertEquals("fluid_column", OrderedAssemblyGroup.topologyFault(items, fluids));
        fluids.clear();
        items.add(fixture.add(false, 1, 65, 0));
        assertEquals("item_column", OrderedAssemblyGroup.topologyFault(items, fluids));
        items.remove(2);
        items.set(1, fixture.add(false, 0, 66, 0));
        assertEquals("item_column", OrderedAssemblyGroup.topologyFault(items, fluids));
        items.set(1, fixture.add(false, 0, 65, 0));
        fluids.add(fixture.add(true, 2, 64, 0));
        fluids.add(fixture.add(true, 2, 65, 0));
        assertEquals("disconnected_members", OrderedAssemblyGroup.topologyFault(items, fluids));
    }

    @Test
    public void aVerticalGroupsUniqueMeEntryOrdersBothTypesAndMultipleEntriesRemainRejected() throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> items = new ArrayList<>(), fluids = new ArrayList<>();
        for (int i = 0; i < 5; i++) items.add(fixture.add(false, 0, 64 + i, 0));
        for (int i = 0; i < 4; i++) fluids.add(fixture.add(true, 1, 64 + i, 0));
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        OrderedAssemblyGroup group = items.get(0)
            .matrixPort()
            .group();
        Graph graph = new Graph(fixture);
        IGridConnection top = graph.external(items.get(4));
        assertEquals(1, group.externalConnectionCount());
        assertSame(
            items.get(4),
            group.itemMembers()
                .get(0));
        assertSame(
            fluids.get(3),
            group.fluidMembers()
                .get(0));
        IGridConnection bottom = graph.external(items.get(0));
        assertEquals(2, group.externalConnectionCount());
        assertEquals("multiple_external_connections", group.reason());
        assertSame(
            items.get(4),
            group.itemMembers()
                .get(0));
        graph.remove(top);
        assertEquals(1, group.externalConnectionCount());
        assertSame(
            items.get(0),
            group.itemMembers()
                .get(0));
        assertSame(
            fluids.get(0),
            group.fluidMembers()
                .get(0));
        graph.remove(bottom);
        assertEquals(0, group.externalConnectionCount());
        assertEquals("no_external_connection", group.reason());
    }

    @Test
    public void aVerticalNativeBindingUsesItsActualAlignmentAndStillChecksEveryMemberFront() throws Exception {
        Fixture fixture = new Fixture();
        ExtendedFacing alignment = ExtendedFacing.NORTH_CLOCKWISE_NONE;
        for (int i = 0; i < 5; i++) {
            MatrixAssemblyMember member = fixture.add(false, 0, 64 + i, 0);
            field(BaseMetaTileEntity.class, "mFacing")
                .set(member.getBaseMetaTileEntity(), alignment.getRelativeDownInWorld());
        }
        OrderedAssemblyGroup.refreshTopology(fixture.members.get(0));
        OrderedAssemblyGroup group = fixture.members.get(0)
            .matrixPort()
            .group();
        initializeNativeAssemblyLine(fixture.unsafe);
        MTEAssemblyLine controller = new MTEAssemblyLine("matrix_vertical_alignment");
        fixture.attach(controller, 20, 64, 20);
        field(gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase.class, "mExtendedFacing")
            .set(controller, alignment);
        for (MatrixAssemblyMember member : fixture.members) controller.mInputBusses.add((MTEHatchInputBus) member);
        field(OrderedAssemblyGroup.class, "session").set(group, new NativeAssemblySession(controller));
        field(OrderedAssemblyGroup.class, "alignment").set(group, alignment);
        Method check = OrderedAssemblyGroup.class.getDeclaredMethod("checkNativeBinding");
        check.setAccessible(true);
        assertTrue((Boolean) check.invoke(group));
        field(BaseMetaTileEntity.class, "mFacing").set(
            fixture.members.get(2)
                .getBaseMetaTileEntity(),
            ForgeDirection.NORTH);
        assertFalse((Boolean) check.invoke(group));
        assertEquals("member_facing", field(OrderedAssemblyGroup.class, "reason").get(group));
    }

    @Test
    public void topologyInvalidationAndRecoveryNotifyThePatternCatalogueOnlyOnTransitions() throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> items = fixture.items(false);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        OrderedAssemblyGroup group = items.get(0)
            .matrixPort()
            .group();
        MatrixAssemblyPort port = group.master()
            .matrixPort();
        Field patternChange = field(MatrixAssemblyPort.class, "patternChange");
        patternChange.setBoolean(port, false);
        group.invalidate("topology_changed");
        assertTrue(patternChange.getBoolean(port));
        patternChange.setBoolean(port, false);
        group.invalidate("topology_changed");
        assertFalse(patternChange.getBoolean(port));
        OrderedAssemblyGroup.refreshTopology(items.get(1));
        assertSame(
            group,
            items.get(1)
                .matrixPort()
                .group());
        assertTrue(group.editable());
        assertTrue(patternChange.getBoolean(port));
        patternChange.setBoolean(port, false);
        OrderedAssemblyGroup.refreshTopology(items.get(2));
        assertFalse(patternChange.getBoolean(port));
    }

    @Test
    public void capacityCheckUsesPhysicalPositionsAndTanksBeforeAnyNativeBinding() throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> items = fixture.items(false);
        MatrixFluidInputHatch fluid = (MatrixFluidInputHatch) fixture.add(true, 0, 64, 1);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        OrderedAssemblyGroup group = items.get(0)
            .matrixPort()
            .group();
        assertNull(group.session());
        assertFalse(group.accepting());
        Method capacity = OrderedAssemblyGroup.class.getDeclaredMethod("capacityReason", OrderedPatternLayout.class);
        capacity.setAccessible(true);
        assertEquals("", capacity.invoke(group, layout(5, new int[0])));
        assertEquals("not_enough_members", capacity.invoke(group, layout(6, new int[0])));
        assertEquals("", capacity.invoke(group, layout(1, new int[] { fluid.getCapacity() })));
        assertEquals(Integer.MAX_VALUE, fluid.getCapacity());
        assertEquals("not_enough_members", capacity.invoke(group, layout(1, new int[] { 1, 1 })));
        for (MatrixAssemblyMember member : fixture.members) {
            assertNull(member.matrixItem());
            assertNull(member.matrixFluidStack());
            assertFalse(NativeAssemblyAdapter.permitsNativeAccess(member));
        }
    }

    @Test
    public void verticalColumnsFormButSideBranchesNeverFormAGroup() throws Exception {
        Fixture vertical = new Fixture();
        for (int i = 0; i < 5; i++) vertical.add(false, 0, 64 + i, 0);
        OrderedAssemblyGroup.refreshTopology(vertical.members.get(0));
        assertNotNull(
            vertical.members.get(0)
                .matrixPort()
                .group());

        Fixture branch = new Fixture();
        List<MatrixAssemblyMember> items = branch.items(false);
        branch.add(false, 2, 64, 1);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        assertNull(
            items.get(0)
                .matrixPort()
                .group());

        Fixture fluidBranch = new Fixture();
        items = fluidBranch.items(false);
        fluidBranch.add(true, 0, 64, 1);
        fluidBranch.add(true, 0, 64, 2);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        assertNull(
            items.get(0)
                .matrixPort()
                .group());
    }

    @Test
    public void aNativeRecheckPreservesTheCurrentGroupsRealBranchRefusal() throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> items = fixture.items(false);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        OrderedAssemblyGroup group = items.get(0)
            .matrixPort()
            .group();
        NativeAssemblySession session = new NativeAssemblySession(null);
        field(OrderedAssemblyGroup.class, "session").set(group, session);
        field(OrderedAssemblyGroup.class, "nativeBound").setBoolean(group, true);
        MatrixAssemblyMember branch = fixture.add(false, 2, 65, 0);
        OrderedAssemblyGroup.structureChecked(items.get(0), session, true);
        assertSame(
            group,
            items.get(0)
                .matrixPort()
                .group());
        assertEquals("item_column", group.reason());
        assertFalse(group.editable());
        assertFalse(group.accepting());
        assertFalse(field(OrderedAssemblyGroup.class, "nativeBound").getBoolean(group));
        assertNull(
            branch.matrixPort()
                .group());
        for (MatrixAssemblyMember member : items) assertEquals(
            0,
            member.matrixPort()
                .connectionMask());
        fixture.world.tiles.remove(key(2, 65, 0));
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        assertSame(
            group,
            items.get(0)
                .matrixPort()
                .group());
        assertTrue(group.editable());
        assertFalse(group.accepting());
    }

    @Test
    public void fluidGapsUseOnlyRealFaceBridgesAndAnAddedItemKeepsTheExistingMaster() throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> items = fixture.items(false);
        MatrixAssemblyMember fluid = fixture.add(true, 0, 64, 1);
        fixture.add(true, 3, 64, 1);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        OrderedAssemblyGroup first = items.get(0)
            .matrixPort()
            .group();
        assertNotNull(first);
        assertEquals(
            ForgeDirection.NORTH.flag,
            fluid.matrixPort()
                .connectionMask());
        MatrixAssemblyMember added = fixture.add(false, -1, 64, 0);
        OrderedAssemblyGroup.refreshTopology(added);
        OrderedAssemblyGroup expanded = added.matrixPort()
            .group();
        assertNotSame(first, expanded);
        assertSame(first.master(), expanded.master());
        assertEquals(
            ForgeDirection.EAST.flag,
            added.matrixPort()
                .connectionMask());
        for (MatrixAssemblyMember member : fixture.members) assertEquals(
            fixture.expectedMask(member),
            member.matrixPort()
                .connectionMask());
    }

    @Test
    public void anInflightMembershipChangeRetainsTheBatchAndPhysicalMaterials() throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> items = fixture.items(false);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        OrderedAssemblyGroup group = items.get(0)
            .matrixPort()
            .group();
        field(OrderedAssemblyGroup.class, "stage").set(group, OrderedAssemblyGroup.Stage.STAGED);
        field(OrderedAssemblyGroup.class, "batch").set(group, UUID.randomUUID());
        field(OrderedAssemblyGroup.class, "recipeKey")
            .set(group, "v1:" + String.join("", Collections.nCopies(64, "0")));
        field(OrderedAssemblyGroup.class, "patternSlot").setInt(group, 0);
        items.get(0)
            .matrixSetItem(new ItemStack(new Item(), 7));
        NBTTagCompound before = group.saveLedger();
        MatrixAssemblyMember extra = fixture.add(false, -1, 64, 0);
        OrderedAssemblyGroup.refreshTopology(extra);
        assertNull(
            extra.matrixPort()
                .group());
        assertSame(
            group,
            items.get(0)
                .matrixPort()
                .group());
        assertEquals(OrderedAssemblyGroup.Stage.STAGED, group.stage());
        assertEquals(
            before.getString("batch"),
            group.saveLedger()
                .getString("batch"));
        assertEquals(
            7,
            items.get(0)
                .matrixItem().stackSize);
        assertFalse(group.editable());
        for (MatrixAssemblyMember member : items) assertEquals(
            0,
            member.matrixPort()
                .connectionMask());
    }

    @Test
    public void aLegacyReverseScanLedgerRetainsRecipeOrdinalsAndThePhysicalUiMasterBeforeNativeRebinding()
        throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> items = fixture.items(false);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        OrderedAssemblyGroup group = items.get(0)
            .matrixPort()
            .group();
        MatrixAssemblyMember master = group.master();
        NBTTagCompound legacy = group.saveLedger();
        legacy.removeTag("master");
        legacy.setString(
            "controller",
            UUID.randomUUID()
                .toString());
        legacy.setIntArray("controllerPosition", new int[] { 0, 0, 66, 2 });
        NBTTagList original = legacy.getTagList("members", 10), reversed = new NBTTagList();
        for (int i = original.tagCount() - 1; i >= 0; i--) reversed.appendTag(
            original.getCompoundTagAt(i)
                .copy());
        legacy.setTag("members", reversed);
        for (MatrixAssemblyMember member : items) member.matrixPort()
            .bind(null);
        field(MatrixAssemblyPort.class, "restoredLedger").set(master.matrixPort(), legacy);
        OrderedAssemblyGroup.refreshTopology(items.get(2));
        OrderedAssemblyGroup restored = master.matrixPort()
            .group();
        assertNotSame(group, restored);
        assertSame(master, restored.master());
        assertSame(
            items.get(4),
            restored.itemMembers()
                .get(0));
        assertArrayEquals(new int[] { 4, 64, 0 }, restored.diagnosticMemberPosition(false, 0));
        assertArrayEquals(new int[] { 0, 0, 66, 2 }, restored.diagnosticControllerPosition());
        assertNull(restored.session());
        assertTrue(restored.editable());
        assertFalse(restored.accepting());
        assertFalse(restored.validateNativeState(new NativeAssemblySession(null)));
        assertEquals("no_external_connection", restored.reason());
    }

    @Test
    public void aReloadedMasterKeepsTheSameBatchOnlyAtItsSavedIdentityAndPosition() throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> items = fixture.items(false);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        OrderedAssemblyGroup group = items.get(0)
            .matrixPort()
            .group();
        field(OrderedAssemblyGroup.class, "stage").set(group, OrderedAssemblyGroup.Stage.STAGED);
        UUID batch = UUID.randomUUID();
        field(OrderedAssemblyGroup.class, "batch").set(group, batch);
        MatrixAssemblyMember replacement = fixture.add(false, 0, 64, 0);
        field(MatrixAssemblyPort.class, "memberId").set(
            replacement.matrixPort(),
            items.get(0)
                .orderedMemberId());
        OrderedAssemblyGroup.refreshTopology(items.get(1));
        assertSame(
            group,
            replacement.matrixPort()
                .group());
        assertSame(replacement, group.master());
        assertEquals(OrderedAssemblyGroup.Stage.STAGED, group.stage());
        assertEquals(
            batch.toString(),
            group.saveLedger()
                .getString("batch"));
        assertFalse(group.editable());
        assertFalse(group.accepting());
    }

    @Test
    public void missingWorldTilesAndForeignGroupOwnershipNeverRetainABridge() throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> items = fixture.items(false);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        OrderedAssemblyGroup group = items.get(0)
            .matrixPort()
            .group();
        fixture.world.tiles.remove(key(4, 64, 0));
        java.lang.reflect.Method update = OrderedAssemblyGroup.class.getDeclaredMethod("updateConnections");
        update.setAccessible(true);
        update.invoke(group);
        assertEquals(
            ForgeDirection.WEST.flag,
            items.get(3)
                .matrixPort()
                .connectionMask());
        assertEquals(
            0,
            items.get(4)
                .matrixPort()
                .connectionMask());
        items.get(2)
            .matrixPort()
            .bind(null);
        update.invoke(group);
        assertEquals(
            ForgeDirection.WEST.flag,
            items.get(1)
                .matrixPort()
                .connectionMask());
        assertEquals(
            0,
            items.get(2)
                .matrixPort()
                .connectionMask());
        assertEquals(
            0,
            items.get(3)
                .matrixPort()
                .connectionMask());
    }

    @Test
    public void synchronousAeCraftingRefreshDuringConnectionUpdatesKeepsEveryPhysicalMember() throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> items = fixture.items(false);
        fixture.add(true, 0, 64, 1);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        OrderedAssemblyGroup group = items.get(0)
            .matrixPort()
            .group();
        Graph graph = new Graph(fixture);
        graph.external(items.get(4));
        for (MatrixAssemblyMember member : fixture.members) member.matrixPort()
            .connectionMask(0);
        int[] callbacks = { 0 };
        ICraftingProviderHelper helper = (ICraftingProviderHelper) Proxy.newProxyInstance(
            ICraftingProviderHelper.class.getClassLoader(),
            new Class<?>[] { ICraftingProviderHelper.class },
            (proxy, method, args) -> { throw new AssertionError("A bare group cannot advertise a pattern"); });
        MatrixAssemblyMember first = items.get(0);
        field(MatrixAssemblyPort.class, "proxy").set(first.matrixPort(), new AENetworkProxy(null, "test", null, true) {

            @Override
            public IGridNode getNode() {
                return graph.nodes.get(first).node;
            }

            @Override
            public boolean isActive() {
                return true;
            }

            @Override
            public void setValidSides(EnumSet<ForgeDirection> sides) {
                callbacks[0]++;
                first.matrixPort()
                    .provideCrafting(helper);
            }
        });
        Method update = OrderedAssemblyGroup.class.getDeclaredMethod("updateConnections");
        update.setAccessible(true);
        update.invoke(group);
        assertEquals(1, callbacks[0]);
        assertSame(
            items.get(4),
            group.itemMembers()
                .get(0));
        assertArrayEquals(new int[] { 4, 64, 0 }, group.diagnosticMemberPosition(false, 0));
        assertEquals(
            items.get(4)
                .orderedMemberId()
                .toString(),
            group.saveLedger()
                .getTagList("members", 10)
                .getCompoundTagAt(0)
                .getString("id"));
        assertEquals(
            fixture.members.size(),
            group.physicalMembers()
                .size());
        for (MatrixAssemblyMember member : fixture.members) {
            assertSame(
                group,
                member.matrixPort()
                    .group());
            assertEquals(
                fixture.expectedMask(member),
                member.matrixPort()
                    .connectionMask());
        }
    }

    @Test
    public void connectionCallbacksCannotClearMembersAlreadyReboundToAReplacementGroup() throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> items = fixture.items(false);
        fixture.add(true, 0, 64, 1);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        OrderedAssemblyGroup previous = items.get(0)
            .matrixPort()
            .group();
        Graph graph = new Graph(fixture);
        graph.external(items.get(4));
        for (MatrixAssemblyMember member : fixture.members) member.matrixPort()
            .connectionMask(0);
        MatrixAssemblyMember added = fixture.add(false, -1, 64, 0);
        TileEntity addedTile = fixture.world.tiles.remove(key(-1, 64, 0));
        boolean[] rebound = { false };
        MatrixAssemblyMember first = items.get(0);
        field(MatrixAssemblyPort.class, "proxy").set(first.matrixPort(), new AENetworkProxy(null, "test", null, true) {

            @Override
            public IGridNode getNode() {
                return graph.nodes.get(first).node;
            }

            @Override
            public boolean isActive() {
                return true;
            }

            @Override
            public void setValidSides(EnumSet<ForgeDirection> sides) {
                if (rebound[0]) return;
                rebound[0] = true;
                fixture.world.tiles.put(key(-1, 64, 0), addedTile);
                OrderedAssemblyGroup.refreshTopology(added);
            }
        });
        Method update = OrderedAssemblyGroup.class.getDeclaredMethod("updateConnections");
        update.setAccessible(true);
        update.invoke(previous);
        OrderedAssemblyGroup replacement = first.matrixPort()
            .group();
        assertTrue(rebound[0]);
        assertNotSame(previous, replacement);
        for (MatrixAssemblyMember member : fixture.members) {
            assertSame(
                replacement,
                member.matrixPort()
                    .group());
            assertEquals(
                fixture.expectedMask(member),
                member.matrixPort()
                    .connectionMask());
        }
    }

    @Test
    public void losingNativeFormationNotifiesThePatternCatalogueOnlyOnce() throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> items = fixture.items(false);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        OrderedAssemblyGroup group = items.get(0)
            .matrixPort()
            .group();
        NativeAssemblySession session = new NativeAssemblySession(null);
        field(OrderedAssemblyGroup.class, "session").set(group, session);
        field(OrderedAssemblyGroup.class, "nativeBound").setBoolean(group, true);
        MatrixAssemblyPort port = group.master()
            .matrixPort();
        field(MatrixAssemblyPort.class, "patternChange").setBoolean(port, false);
        OrderedAssemblyGroup.structureChecked(items.get(0), session, false);
        assertFalse(field(OrderedAssemblyGroup.class, "nativeBound").getBoolean(group));
        assertTrue(field(MatrixAssemblyPort.class, "patternChange").getBoolean(port));
        field(MatrixAssemblyPort.class, "patternChange").setBoolean(port, false);
        OrderedAssemblyGroup.structureChecked(items.get(0), session, false);
        assertFalse(field(MatrixAssemblyPort.class, "patternChange").getBoolean(port));
        assertTrue(group.editable());
    }

    @Test
    public void nativeStoppedAfterGtClearsAMembersBaseStillRetainsTheBatchAndNotifiesLiveMembers() throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> items = fixture.items(false);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        OrderedAssemblyGroup group = items.get(0)
            .matrixPort()
            .group();
        NativeAssemblySession session = new NativeAssemblySession(null);
        field(OrderedAssemblyGroup.class, "session").set(group, session);
        field(OrderedAssemblyGroup.class, "stage").set(group, OrderedAssemblyGroup.Stage.RUNNING);
        UUID batch = UUID.randomUUID();
        field(OrderedAssemblyGroup.class, "batch").set(group, batch);
        group.invalidate("member_unloaded");
        fixture.world.tiles.remove(key(4, 64, 0));
        field(MetaTileEntity.class, "mBaseMetaTileEntity").set(items.get(4), null);
        field(MatrixAssemblyPort.class, "patternChange").setBoolean(
            items.get(0)
                .matrixPort(),
            false);
        group.onNativeStopped(session, "structure_incomplete");
        assertEquals(OrderedAssemblyGroup.Stage.NATIVE_ABORTED, group.stage());
        assertEquals(
            batch.toString(),
            group.saveLedger()
                .getString("batch"));
        assertTrue(
            field(MatrixAssemblyPort.class, "patternChange").getBoolean(
                items.get(0)
                    .matrixPort()));
        assertFalse(group.accepting());
    }

    @Test
    public void repeatedPositionsAccumulateWholePackagesAndOverflowRetainsEveryOtherPosition() throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> items = fixture.items(false);
        fixture.add(true, 0, 64, 1);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        OrderedAssemblyGroup group = items.get(0)
            .matrixPort()
            .group();
        Graph graph = new Graph(fixture);
        graph.external(items.get(4));
        fixture.controller(group, 20);
        OrderedPatternLayout layout = bufferLayout(new int[] { 0, 1, 1, 2 });
        group.master()
            .matrixPort()
            .patterns()
            .setStackInSlot(0, layout.pattern());
        assertTrue(deliver(group, layout, 10));
        assertTrue(deliver(group, layout, 6));
        assertSame(
            items.get(4),
            group.itemMembers()
                .get(0));
        for (int i = 0; i < 4; i++) {
            ItemStack stored = group.itemMembers()
                .get(i)
                .matrixItem();
            assertEquals(16, stored.stackSize);
            assertEquals(new int[] { 0, 1, 1, 2 }[i], stored.getItemDamage());
        }
        assertNull(
            group.itemMembers()
                .get(4)
                .matrixItem());
        assertEquals(
            1600,
            group.fluidMembers()
                .get(0)
                .matrixFluidStack().amount);
        MatrixItemInputBus ordinary = (MatrixItemInputBus) group.itemMembers()
            .get(0);
        assertEquals(1, ordinary.getSizeInventory());
        assertEquals(16, ordinary.getStackInSlot(0).stackSize);
        ItemStack full = group.itemMembers()
            .get(3)
            .matrixItem();
        full.stackSize = Integer.MAX_VALUE;
        group.itemMembers()
            .get(3)
            .matrixSetItem(full);
        assertFalse(deliver(group, layout, 1));
        assertEquals("amount_overflow", group.reason());
        assertEquals(
            16,
            group.itemMembers()
                .get(0)
                .matrixItem().stackSize);
        assertEquals(
            Integer.MAX_VALUE,
            group.itemMembers()
                .get(3)
                .matrixItem().stackSize);
        assertEquals(
            1600,
            group.fluidMembers()
                .get(0)
                .matrixFluidStack().amount);
        assertFalse(deliver(group, bufferLayout(new int[] { 1, 1, 1, 2 }), 1));
        assertEquals("different_pattern", group.reason());
    }

    @Test
    public void invisibleExternalEdgesCountButInternalEdgesDoNotAndAnInflightOriginCannotMove() throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> items = fixture.items(false);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        OrderedAssemblyGroup group = items.get(0)
            .matrixPort()
            .group();
        Graph graph = new Graph(fixture);
        assertEquals(0, group.externalConnectionCount());
        IGridConnection original = graph.external(items.get(4));
        fixture.controller(group, 20);
        assertEquals(1, group.externalConnectionCount());
        int reads = graph.reads();
        assertEquals(1, group.externalConnectionCount());
        assertTrue(group.permitsMachineWork());
        assertEquals(reads, graph.reads());
        field(OrderedAssemblyGroup.class, "stage").set(group, OrderedAssemblyGroup.Stage.STAGED);
        field(OrderedAssemblyGroup.class, "genericInput").setBoolean(group, true);
        items.get(0)
            .matrixSetItem(new ItemStack(new Item(), 10));
        IGridConnection extra = graph.external(items.get(0));
        assertEquals(2, group.externalConnectionCount());
        assertEquals("multiple_external_connections", group.reason());
        assertFalse(group.permitsMachineWork());
        assertNull(((MatrixItemInputBus) items.get(0)).getStackInSlot(0));
        graph.remove(original);
        assertEquals(1, group.externalConnectionCount());
        assertEquals("entry_changed", group.reason());
        assertEquals(
            10,
            items.get(0)
                .matrixItem().stackSize);
        graph.remove(extra);
        graph.external(items.get(4));
        assertTrue(group.permitsMachineWork());
        assertEquals(10, ((MatrixItemInputBus) items.get(0)).getStackInSlot(0).stackSize);
    }

    @Test
    public void genericWorkKeepsItsPatternLockUntilNativeCompletionAndSharedControllersStopTheGroup() throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> items = fixture.items(false);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        OrderedAssemblyGroup group = items.get(0)
            .matrixPort()
            .group();
        Graph graph = new Graph(fixture);
        graph.external(items.get(0));
        MTEMultiBlockBase first = fixture.controller(group, 20);
        field(OrderedAssemblyGroup.class, "stage").set(group, OrderedAssemblyGroup.Stage.STAGED);
        field(OrderedAssemblyGroup.class, "genericInput").setBoolean(group, true);
        first.mMaxProgresstime = 40;
        group.tick();
        assertEquals(OrderedAssemblyGroup.Stage.STAGED, group.stage());
        assertFalse(group.editable());
        MTEMultiBlockBase second = fixture.controller(group, 24);
        assertFalse(group.permitsMachineWork());
        assertEquals("controller_conflict", group.reason());
        OrderedAssemblyGroup.controllerChecked(items.get(0), second, false);
        assertTrue(group.permitsMachineWork());
        first.mMaxProgresstime = 0;
        group.tick();
        assertEquals(OrderedAssemblyGroup.Stage.IDLE, group.stage());
        assertTrue(group.editable());
    }

    @Test
    public void flashMemoryMouseHooksBindBothMemberTypesAndKeepTheExistingCopyFormat() throws Exception {
        Field stack = field(ItemList.class, "mStack");
        Field unset = field(ItemList.class, "mHasNotBeenSet");
        Object previousStack = stack.get(ItemList.Tool_DataStick);
        boolean previousUnset = unset.getBoolean(ItemList.Tool_DataStick);
        Item tool = new Item();
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        Field feather = field(Items.class, "feather");
        Object featherBase = unsafe.staticFieldBase(feather);
        long featherOffset = unsafe.staticFieldOffset(feather);
        Object previousFeather = feather.get(null);
        // Native GT stack matching reads raw metadata through feather; plain JUnit has no vanilla bootstrap.
        if (previousFeather == null) unsafe.putObjectVolatile(featherBase, featherOffset, tool);
        ItemList.Tool_DataStick.set(new ItemStack(tool));
        try {
            for (boolean fluid : new boolean[] { false, true }) {
                Fixture fixture = new Fixture();
                MatrixAssemblyMember source = fixture.add(fluid, 0, 64, 0);
                fixture.add(fluid, 1, 64, 0);
                MatrixAssemblyMember mirror = fixture.add(fluid, 20, 64, 0, true);
                fixture.add(fluid, 21, 64, 0);
                FixturePlayer player = (FixturePlayer) fixture.unsafe.allocateInstance(FixturePlayer.class);
                player.inventory = new InventoryPlayer(player);
                player.messages = new ArrayList<>();
                ItemStack flash = new ItemStack(tool);
                NBTTagCompound originalTag = new NBTTagCompound();
                originalTag.setString("untouched", "unbound");
                flash.setTagCompound(originalTag);
                player.inventory.mainInventory[0] = flash;

                ((MetaTileEntity) mirror).onLeftclick(mirror.getBaseMetaTileEntity(), player);
                assertEquals(originalTag, flash.getTagCompound());
                assertEquals("gtswn.matrix.data_stick.copy_failed", player.lastMessageKey());
                ((MetaTileEntity) source).onLeftclick(source.getBaseMetaTileEntity(), player);
                NBTTagCompound address = (NBTTagCompound) flash.getTagCompound()
                    .copy();
                assertEquals(MatrixMirrorLink.DATA_TYPE, address.getString("type"));
                assertEquals(1, address.getInteger("schema"));
                assertTrue(flash.hasDisplayName());
                assertEquals("gtswn.matrix.data_stick.copied", player.lastMessageKey());
                assertTrue(((MetaTileEntity) mirror).onRightclick(mirror.getBaseMetaTileEntity(), player));
                assertEquals("gtswn.matrix.data_stick.linked", player.lastMessageKey());
                assertEquals(
                    address,
                    mirror.matrixPort()
                        .mirrorLink()
                        .save());

                // A linked mirror copies the same source address, rather than its own physical coordinates.
                ((MetaTileEntity) mirror).onLeftclick(mirror.getBaseMetaTileEntity(), player);
                assertEquals(address, flash.getTagCompound());
                flash.getTagCompound()
                    .setInteger("dimension", 99);
                assertTrue(((MetaTileEntity) mirror).onRightclick(mirror.getBaseMetaTileEntity(), player));
                assertEquals("gtswn.matrix.data_stick.link_failed", player.lastMessageKey());
                assertEquals(
                    address,
                    mirror.matrixPort()
                        .mirrorLink()
                        .save());
                player.inventory.mainInventory[0] = new ItemStack(new Item());
                assertFalse(
                    mirror.matrixPort()
                        .tryBindDataStick(player));

                // Existing copy/paste tools and their schema-1 payload remain accepted without migration.
                NBTTagCompound copied = ((IDataCopyable) source).getCopiedData(player);
                assertEquals(1, copied.getInteger("schema"));
                assertTrue(((IDataCopyable) mirror).pasteCopiedData(player, copied));
                assertEquals(
                    copied,
                    mirror.matrixPort()
                        .mirrorLink()
                        .save());
            }
        } finally {
            stack.set(ItemList.Tool_DataStick, previousStack);
            unset.setBoolean(ItemList.Tool_DataStick, previousUnset);
            unsafe.putObjectVolatile(featherBase, featherOffset, previousFeather);
        }
    }

    @Test
    public void mixedRoleTextureTransitionsFollowOnlyVerifiedPhysicalEdgesOnEveryFace() throws Exception {
        Fixture fixture = new Fixture();
        MatrixAssemblyMember item = fixture.add(false, 0, 64, 0);
        MatrixAssemblyMember mirror = fixture.add(false, 1, 64, 0, true);
        MatrixAssemblyMember fluid = fixture.add(true, 1, 64, 1);
        assertEquals(0, MatrixAssemblyTextures.transitionMask(item));
        OrderedAssemblyGroup.refreshTopology(item);
        assertEquals(ForgeDirection.EAST.flag, MatrixAssemblyTextures.transitionMask(item));
        assertEquals(
            ForgeDirection.WEST.flag | ForgeDirection.SOUTH.flag,
            MatrixAssemblyTextures.transitionMask(mirror));
        assertEquals(ForgeDirection.NORTH.flag, MatrixAssemblyTextures.transitionMask(fluid));
        Field facing = field(BaseMetaTileEntity.class, "mFacing");
        for (MatrixAssemblyMember member : new MatrixAssemblyMember[] { item, mirror, fluid }) {
            for (ForgeDirection front : ForgeDirection.VALID_DIRECTIONS) {
                facing.set(member.getBaseMetaTileEntity(), front);
                assertEquals(
                    4 + Integer.bitCount(
                        MatrixAssemblyTextures.faceMask(MatrixAssemblyTextures.transitionMask(member), front)),
                    MatrixAssemblyTextures.front(member, null).length);
            }
        }
        item.matrixPort()
            .readConnectionMask(0);
        assertEquals(0, MatrixAssemblyTextures.transitionMask(item));
        assertEquals(4, MatrixAssemblyTextures.front(item, null).length);
    }

    @Test
    public void standaloneSourceFeedsARotatedMirrorWithoutCreatingLocalStockOrASecondProvider() throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> original = fixture.items(false);
        fixture.add(true, 0, 64, 1);
        OrderedAssemblyGroup.refreshTopology(original.get(0));
        OrderedAssemblyGroup source = original.get(0)
            .matrixPort()
            .group();
        Graph graph = new Graph(fixture);
        graph.external(original.get(4));
        OrderedPatternLayout layout = bufferLayout(new int[] { 0, 1, 1, 2 });
        source.master()
            .matrixPort()
            .patterns()
            .setStackInSlot(0, layout.pattern());
        NBTTagCompound address = original.get(0)
            .matrixPort()
            .copySource();
        assertNotNull(address);
        assertFalse(source.accepting());
        MatrixAssemblyMember anchor = fixture.add(false, 20, 64, 20, true);
        for (int i = 1; i < 5; i++) fixture.add(false, 20, 64, 20 + i);
        fixture.add(true, 19, 64, 20);
        OrderedAssemblyGroup.refreshTopology(anchor);
        OrderedAssemblyGroup destination = anchor.matrixPort()
            .group();
        assertTrue(
            anchor.matrixPort()
                .bindMirror(address));
        fixture.controller(destination, 40);
        assertTrue(destination.permitsMachineWork());
        assertTrue(source.accepting());
        assertNull(source.session());
        initializeNativeAssemblyLine(fixture.unsafe);
        MTEAssemblyLine unformed = new MTEAssemblyLine("matrix_unformed_local_source");
        fixture.attach(unformed, 60, 64, 20);
        for (MatrixAssemblyMember member : source.itemMembers()) unformed.mInputBusses.add((MTEHatchInputBus) member);
        for (MatrixAssemblyMember member : source.fluidMembers()) unformed.mInputHatches.add((MTEHatchInput) member);
        OrderedAssemblyGroup.controllerChecked(source.master(), unformed, true);
        field(OrderedAssemblyGroup.class, "session").set(source, new NativeAssemblySession(unformed));
        assertFalse(unformed.mMachine);
        assertTrue(source.accepting());
        assertEquals("", source.reason());
        assertEquals("", destination.reason());
        assertEquals("", source.previewReason(layout));
        assertFalse(destination.accepting());
        assertFalse(((MatrixItemInputBus) anchor).shouldDisplay());
        assertNull(((MatrixItemInputBus) anchor).getGridNode(ForgeDirection.DOWN));
        assertTrue(deliver(source, layout, 2));
        for (int i = 0; i < 4; i++) {
            MatrixItemInputBus target = (MatrixItemInputBus) destination.itemMembers()
                .get(i);
            MatrixItemInputBus owner = (MatrixItemInputBus) source.itemMembers()
                .get(i);
            assertNull(target.matrixItem());
            assertSame(owner.matrixNativeItems(), target.getRealInventory());
            assertSame(owner.matrixNativeItems()[0], target.getFirstStack());
        }
        MatrixFluidInputHatch targetFluid = (MatrixFluidInputHatch) destination.fluidMembers()
            .get(0);
        assertNull(targetFluid.matrixFluidStack());
        assertSame(
            source.fluidMembers()
                .get(0)
                .matrixNativeFluid(),
            targetFluid.getFillableStack());
        assertEquals(50, targetFluid.drain(50, true).amount);
        assertEquals(
            150,
            source.fluidMembers()
                .get(0)
                .matrixFluidStack().amount);
        assertFalse(
            anchor.matrixPort()
                .clearMirror());
        IGridConnection extra = graph.external(original.get(0));
        assertFalse(destination.permitsMachineWork());
        assertNull(targetFluid.getFillableStack());
        assertEquals(
            150,
            source.fluidMembers()
                .get(0)
                .matrixFluidStack().amount);
        graph.remove(extra);
        assertTrue(destination.permitsMachineWork());
        original.get(0)
            .matrixPort()
            .removed();
        fixture.world.unloaded.add(key(0, 64, 0));
        assertFalse(destination.permitsMachineWork());
        assertNull(
            ((MatrixItemInputBus) destination.itemMembers()
                .get(0)).getFirstStack());
        assertEquals(
            2,
            source.itemMembers()
                .get(0)
                .matrixItem().stackSize);
    }

    @Test
    public void mirrorHorizontalVerticalRotationKeepsTheCopiedMemberAndEveryTypedOffsetAndFront() throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> sourceItems = fixture.items(false);
        // Asymmetric typed offsets make the rotation unique even though the copied item is the middle member.
        MatrixAssemblyMember firstFluid = fixture.add(true, 0, 64, 1);
        MatrixAssemblyMember secondFluid = fixture.add(true, 3, 64, 1);
        for (MatrixAssemblyMember member : fixture.members)
            field(BaseMetaTileEntity.class, "mFacing").set(member.getBaseMetaTileEntity(), ForgeDirection.NORTH);
        OrderedAssemblyGroup.refreshTopology(sourceItems.get(2));
        OrderedAssemblyGroup source = sourceItems.get(0)
            .matrixPort()
            .group();
        NBTTagCompound address = sourceItems.get(2)
            .matrixPort()
            .copySource();
        assertEquals(1, address.getInteger("schema"));
        List<MatrixAssemblyMember> targets = new ArrayList<>();
        for (int i = 0; i < 5; i++) targets.add(fixture.add(false, 20, 68 + i, 20, i == 2));
        MatrixAssemblyMember targetFirstFluid = fixture.add(true, 20, 68, 21);
        MatrixAssemblyMember targetSecondFluid = fixture.add(true, 20, 71, 21);
        targets.add(targetFirstFluid);
        targets.add(targetSecondFluid);
        for (MatrixAssemblyMember member : targets)
            field(BaseMetaTileEntity.class, "mFacing").set(member.getBaseMetaTileEntity(), ForgeDirection.NORTH);
        MatrixAssemblyMember anchor = targets.get(2);
        OrderedAssemblyGroup.refreshTopology(anchor);
        OrderedAssemblyGroup destination = anchor.matrixPort()
            .group();
        assertNotNull(destination);
        MatrixMirrorLink link = new MatrixMirrorLink(address);
        Map<MatrixAssemblyMember, MatrixAssemblyMember> mapping = link.mapping(source, destination, anchor);
        assertNotNull(mapping);
        for (int i = 0; i < 5; i++) assertSame(sourceItems.get(i), mapping.get(targets.get(i)));
        assertSame(firstFluid, mapping.get(targetFirstFluid));
        assertSame(secondFluid, mapping.get(targetSecondFluid));
        Map<MatrixAssemblyMember, MatrixAssemblyMember> reverse = MatrixMirrorLink.mapPositions(
            destination.physicalMembers(),
            source.physicalMembers(),
            new int[] { 20, 70, 20 },
            new int[] { 2, 64, 0 });
        assertNotNull(reverse);
        for (Map.Entry<MatrixAssemblyMember, MatrixAssemblyMember> pair : mapping.entrySet())
            assertSame(pair.getKey(), reverse.get(pair.getValue()));
        assertTrue(
            anchor.matrixPort()
                .bindMirror(address));
        assertEquals(
            address,
            anchor.matrixPort()
                .mirrorLink()
                .save());
        field(BaseMetaTileEntity.class, "mFacing").set(targetFirstFluid.getBaseMetaTileEntity(), ForgeDirection.EAST);
        assertNull(link.mapping(source, destination, anchor));
        field(BaseMetaTileEntity.class, "mFacing").set(targetFirstFluid.getBaseMetaTileEntity(), ForgeDirection.NORTH);
        assertNull(link.mapping(source, destination, targets.get(1)));
    }

    @Test
    public void mirrorBindingRequiresMatchingTypedLayoutExactIdentityAndEmptyDestination() throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> sourceItems = fixture.items(false);
        OrderedAssemblyGroup.refreshTopology(sourceItems.get(0));
        NBTTagCompound address = sourceItems.get(0)
            .matrixPort()
            .copySource();
        MatrixAssemblyMember anchor = fixture.add(false, 20, 64, 20, true);
        for (int i = 1; i < 5; i++) fixture.add(false, 20 + i, 64, 20);
        OrderedAssemblyGroup.refreshTopology(anchor);
        anchor.matrixSetItem(new ItemStack(new Item(), 1));
        assertFalse(
            anchor.matrixPort()
                .bindMirror(address));
        assertNotNull(anchor.matrixItem());
        anchor.matrixSetItem(null);
        assertTrue(
            anchor.matrixPort()
                .bindMirror(address));
        assertTrue(
            anchor.matrixPort()
                .clearMirror());
        field(MatrixAssemblyPort.class, "memberId").set(
            sourceItems.get(0)
                .matrixPort(),
            UUID.randomUUID());
        assertFalse(
            anchor.matrixPort()
                .bindMirror(address));
        NBTTagCompound invalid = (NBTTagCompound) address.copy();
        invalid.setInteger("dimension", 99);
        assertFalse(
            anchor.matrixPort()
                .bindMirror(invalid));
        fixture.add(true, 20, 64, 21);
        OrderedAssemblyGroup.refreshTopology(anchor);
        assertFalse(
            anchor.matrixPort()
                .bindMirror(address));
    }

    @Test
    public void twoMirrorConsumersKeepTheSourceBatchLockedUntilBothActuallyFinish() throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> original = fixture.items(false);
        fixture.add(true, 0, 64, 1);
        OrderedAssemblyGroup.refreshTopology(original.get(0));
        OrderedAssemblyGroup source = original.get(0)
            .matrixPort()
            .group();
        Graph graph = new Graph(fixture);
        graph.external(original.get(0));
        OrderedPatternLayout layout = bufferLayout(new int[] { 0, 1, 1, 2 });
        source.master()
            .matrixPort()
            .patterns()
            .setStackInSlot(0, layout.pattern());
        NBTTagCompound address = original.get(0)
            .matrixPort()
            .copySource();
        List<OrderedAssemblyGroup> groups = new ArrayList<>();
        List<MTEMultiBlockBase> controllers = new ArrayList<>();
        for (int offset : new int[] { 20, 40 }) {
            MatrixAssemblyMember anchor = fixture.add(false, offset, 64, 0, true);
            for (int i = 1; i < 5; i++) fixture.add(false, offset + i, 64, 0);
            fixture.add(true, offset, 64, 1);
            OrderedAssemblyGroup.refreshTopology(anchor);
            OrderedAssemblyGroup destination = anchor.matrixPort()
                .group();
            assertTrue(
                anchor.matrixPort()
                    .bindMirror(address));
            controllers.add(fixture.controller(destination, offset));
            assertTrue(destination.permitsMachineWork());
            groups.add(destination);
        }
        assertTrue(deliver(source, layout, 2));
        for (int i = 0; i < 2; i++) {
            controllers.get(i).mMaxProgresstime = 40;
            groups.get(i)
                .ordinaryStarted(controllers.get(i));
        }
        for (MatrixAssemblyMember member : source.itemMembers()) member.matrixSetItem(null);
        source.fluidMembers()
            .get(0)
            .matrixSetFluid(null);
        assertEquals(
            2,
            source.saveLedger()
                .getTagList("sharedRuns", 10)
                .tagCount());
        groups.get(0)
            .ordinaryFinished(controllers.get(0), "");
        controllers.get(0).mMaxProgresstime = 0;
        source.tick();
        assertEquals(OrderedAssemblyGroup.Stage.RUNNING, source.stage());
        assertFalse(source.editable());
        assertFalse(
            groups.get(0)
                .master()
                .matrixPort()
                .clearMirror());
        groups.get(1)
            .ordinaryFinished(controllers.get(1), "");
        controllers.get(1).mMaxProgresstime = 0;
        source.tick();
        assertEquals(OrderedAssemblyGroup.Stage.IDLE, source.stage());
        assertTrue(source.editable());
        assertTrue(
            groups.get(0)
                .master()
                .matrixPort()
                .clearMirror());
    }

    @Test
    public void copiedNativeMirrorControllersKeepIndependentRunsUntilEachFinishes() throws Exception {
        NativeMirrorRuns runs = new NativeMirrorRuns();
        runs.start(0);
        runs.start(1);
        NBTTagList saved = runs.source.saveLedger()
            .getTagList("sharedRuns", 10);
        assertEquals("Copied controller UUIDs must not overwrite another physical run", 2, saved.tagCount());
        assertNotEquals(
            saved.getCompoundTagAt(0)
                .getString("key"),
            saved.getCompoundTagAt(1)
                .getString("key"));
        runs.groups.get(0)
            .onNativeFinished(runs.sessions.get(0));
        assertEquals(OrderedAssemblyGroup.Stage.RUNNING, runs.source.stage());
        assertEquals(
            1,
            runs.source.saveLedger()
                .getTagList("sharedRuns", 10)
                .tagCount());
        runs.groups.get(1)
            .onNativeFinished(runs.sessions.get(1));
        assertEquals(OrderedAssemblyGroup.Stage.LOCAL_FINISHED, runs.source.stage());
        assertEquals(
            0,
            runs.source.saveLedger()
                .getTagList("sharedRuns", 10)
                .tagCount());
    }

    @Test
    public void legacyNativeSharedRunsFinishOnlyForTheSavedControllerPosition() throws Exception {
        NativeMirrorRuns runs = new NativeMirrorRuns();
        runs.start(0);
        NBTTagCompound ledger = runs.source.saveLedger();
        NBTTagCompound legacy = ledger.getTagList("sharedRuns", 10)
            .getCompoundTagAt(0);
        String oldKey = "native:" + runs.sessions.get(0)
            .controllerId();
        legacy.setString("key", oldKey);
        MatrixAssemblyMember master = runs.source.master();
        for (MatrixAssemblyMember member : runs.source.physicalMembers()) member.matrixPort()
            .bind(null);
        field(MatrixAssemblyPort.class, "restoredLedger").set(master.matrixPort(), ledger);
        OrderedAssemblyGroup.refreshTopology(master);
        runs.source = master.matrixPort()
            .group();
        for (OrderedAssemblyGroup group : runs.groups) group.networkChanged();
        assertEquals(OrderedAssemblyGroup.Stage.RUNNING, runs.source.stage());
        assertEquals(
            legacy,
            runs.source.saveLedger()
                .getTagList("sharedRuns", 10)
                .getCompoundTagAt(0));
        runs.groups.get(1)
            .onNativeFinished(runs.sessions.get(1));
        assertEquals(
            "A copied UUID at another position cannot finish the legacy run",
            1,
            runs.source.saveLedger()
                .getTagList("sharedRuns", 10)
                .tagCount());
        runs.start(1);
        assertEquals(
            2,
            runs.source.saveLedger()
                .getTagList("sharedRuns", 10)
                .tagCount());
        field(NativeAssemblySession.class, "runSequence").setLong(runs.sessions.get(1), 21);
        runs.groups.get(1)
            .onNativeFinished(runs.sessions.get(1));
        assertEquals(
            "A stale sequence cannot finish this physical controller's run",
            2,
            runs.source.saveLedger()
                .getTagList("sharedRuns", 10)
                .tagCount());
        field(NativeAssemblySession.class, "runSequence").setLong(runs.sessions.get(1), 20);
        runs.groups.get(0)
            .onNativeFinished(runs.sessions.get(0));
        assertEquals(OrderedAssemblyGroup.Stage.RUNNING, runs.source.stage());
        NBTTagList remaining = runs.source.saveLedger()
            .getTagList("sharedRuns", 10);
        assertEquals(1, remaining.tagCount());
        assertNotEquals(
            oldKey,
            remaining.getCompoundTagAt(0)
                .getString("key"));
        runs.groups.get(1)
            .onNativeFinished(runs.sessions.get(1));
        assertEquals(OrderedAssemblyGroup.Stage.LOCAL_FINISHED, runs.source.stage());
        assertEquals(
            0,
            runs.source.saveLedger()
                .getTagList("sharedRuns", 10)
                .tagCount());
    }

    /**
     * Exercise native callbacks with real physical mirrors, without bypassing the missing-hook permit in production.
     */
    private static final class NativeMirrorRuns {

        OrderedAssemblyGroup source;
        final List<OrderedAssemblyGroup> groups = new ArrayList<>();
        final List<NativeAssemblySession> sessions = new ArrayList<>();
        final RecipeAssemblyLine recipe;

        NativeMirrorRuns() throws Exception {
            Fixture fixture = new Fixture();
            initializeNativeAssemblyLine(fixture.unsafe);
            List<MatrixAssemblyMember> items = fixture.items(false);
            fixture.add(true, 0, 64, 1);
            OrderedAssemblyGroup.refreshTopology(items.get(0));
            source = items.get(0)
                .matrixPort()
                .group();
            NBTTagCompound address = source.master()
                .matrixPort()
                .copySource();
            NBTTagCompound identity = savedNativeIdentity(UUID.fromString("686f3a20-1351-42c9-b770-37e81621fa2e"));
            for (int offset : new int[] { 20, 40 }) {
                MatrixAssemblyMember anchor = fixture.add(false, offset, 64, 0, true);
                for (int i = 1; i < 5; i++) fixture.add(false, offset + i, 64, 0);
                fixture.add(true, offset, 64, 1);
                OrderedAssemblyGroup.refreshTopology(anchor);
                assertTrue(
                    anchor.matrixPort()
                        .bindMirror(address));
                OrderedAssemblyGroup group = anchor.matrixPort()
                    .group();
                MTEAssemblyLine controller = new MTEAssemblyLine("matrix_copied_native_mirror_" + offset);
                fixture.attach(controller, offset, 64, 20);
                for (MatrixAssemblyMember member : group.itemMembers())
                    controller.mInputBusses.add((MTEHatchInputBus) member);
                for (MatrixAssemblyMember member : group.fluidMembers())
                    controller.mInputHatches.add((MTEHatchInput) member);
                NativeAssemblySession session = new NativeAssemblySession(controller);
                session.load(identity);
                field(OrderedAssemblyGroup.class, "session").set(group, session);
                groups.add(group);
                sessions.add(session);
            }
            Class<?> fixtures = Class.forName("com.miaokatze.gtswn.common.assembly.pattern.AssemblyPatternFixtures");
            invoke(fixtures, "initialize", new Class<?>[0]);
            recipe = (RecipeAssemblyLine) invoke(
                fixtures,
                "recipe",
                new Class<?>[] { ItemStack[].class, net.minecraftforge.fluids.FluidStack[].class, ItemStack.class },
                new ItemStack[] { (ItemStack) invoke(fixtures, "input", new Class<?>[] { int.class }, 1) },
                new net.minecraftforge.fluids.FluidStack[0],
                invoke(fixtures, "output", new Class<?>[] { int.class }, 1));
            field(OrderedAssemblyGroup.class, "batch").set(source, UUID.randomUUID());
            field(OrderedAssemblyGroup.class, "patternSlot").setInt(source, 0);
        }

        void start(int index) {
            OrderedAssemblyGroup group = groups.get(index);
            assertSame(source.master(), group.diagnosticMaterialMember(group.master()));
            group.onNativeConsumption(sessions.get(index), recipe, 1, new long[5], new long[1], true);
        }
    }

    private static OrderedPatternLayout bufferLayout(int[] variants) throws Exception {
        Class<?> fixtures = Class.forName("com.miaokatze.gtswn.common.assembly.pattern.AssemblyPatternFixtures");
        invoke(fixtures, "initialize", new Class<?>[0]);
        IAEStack<?>[] inputs = new IAEStack<?>[variants.length + 1];
        for (int i = 0; i < variants.length; i++) {
            ItemStack item = (ItemStack) invoke(fixtures, "input", new Class<?>[] { int.class }, 1);
            item.setItemDamage(variants[i]);
            inputs[i] = (IAEStack<?>) invoke(fixtures, "item", new Class<?>[] { ItemStack.class }, item);
        }
        inputs[variants.length] = (IAEStack<?>) invoke(
            fixtures,
            "fluid",
            new Class<?>[] { net.minecraftforge.fluids.FluidStack.class },
            FluidRegistryTestBootstrap.water(100));
        IAEItemStack output = (IAEItemStack) invoke(
            fixtures,
            "item",
            new Class<?>[] { ItemStack.class },
            invoke(fixtures, "output", new Class<?>[] { int.class }, 1));
        return OrderedPatternLayout.decode(
            (ICraftingPatternDetails) invoke(
                fixtures,
                "ultimate",
                new Class<?>[] { IAEStack[].class, IAEItemStack.class },
                inputs,
                output));
    }

    private static boolean deliver(OrderedAssemblyGroup group, OrderedPatternLayout layout, int copies)
        throws Exception {
        IAEStack<?>[] inputs = layout.details()
            .getAEInputs()
            .clone();
        for (int i = 0; i < inputs.length; i++) inputs[i] = inputs[i].copy()
            .setStackSize(inputs[i].getStackSize() * copies);
        Class<?> fixtures = Class.forName("com.miaokatze.gtswn.common.assembly.pattern.AssemblyPatternFixtures");
        net.minecraft.inventory.InventoryCrafting table = (net.minecraft.inventory.InventoryCrafting) invoke(
            fixtures,
            "table",
            new Class<?>[] { IAEStack[].class },
            (Object) inputs);
        Method accept = OrderedAssemblyGroup.class.getDeclaredMethod(
            "acceptInstalled",
            OrderedPatternLayout.class,
            net.minecraft.inventory.InventoryCrafting.class,
            int.class);
        accept.setAccessible(true);
        return (Boolean) accept.invoke(group, layout, table, 0);
    }

    private static final class Graph {

        final Fixture fixture;
        final Map<MatrixAssemblyMember, Endpoint> nodes = new HashMap<>();

        Graph(Fixture fixture) throws Exception {
            this.fixture = fixture;
            for (MatrixAssemblyMember member : fixture.members) {
                Endpoint endpoint = new Endpoint();
                nodes.put(member, endpoint);
                field(MatrixAssemblyPort.class, "proxy")
                    .set(member.matrixPort(), new AENetworkProxy(null, "test", null, true) {

                        @Override
                        public IGridNode getNode() {
                            return endpoint.node;
                        }

                        @Override
                        public boolean isActive() {
                            return true;
                        }
                    });
            }
            for (int i = 1; i < fixture.members.size(); i++)
                connect(nodes.get(fixture.members.get(i - 1)), nodes.get(fixture.members.get(i)));
            fixture.members.get(0)
                .matrixPort()
                .networkChanged();
        }

        IGridConnection external(MatrixAssemblyMember member) {
            IGridConnection edge = connect(nodes.get(member), new Endpoint());
            member.matrixPort()
                .networkChanged();
            return edge;
        }

        void remove(IGridConnection edge) {
            for (Endpoint endpoint : nodes.values()) endpoint.edges.remove(edge);
            fixture.members.get(0)
                .matrixPort()
                .networkChanged();
        }

        int reads() {
            return nodes.values()
                .stream()
                .mapToInt(node -> node.reads)
                .sum();
        }

        private static IGridConnection connect(Endpoint left, Endpoint right) {
            IGridConnection edge = (IGridConnection) Proxy.newProxyInstance(
                IGridConnection.class.getClassLoader(),
                new Class<?>[] { IGridConnection.class },
                (proxy, method, args) -> {
                    if (method.getName()
                        .equals("getOtherSide")) return args[0] == left.node ? right.node : left.node;
                    if (method.getName()
                        .equals("equals")) return proxy == args[0];
                    throw new AssertionError(method);
                });
            left.edges.add(edge);
            right.edges.add(edge);
            return edge;
        }
    }

    private static final class Endpoint {

        final List<IGridConnection> edges = new ArrayList<>();
        int reads;
        final IGridNode node = (IGridNode) Proxy.newProxyInstance(
            IGridNode.class.getClassLoader(),
            new Class<?>[] { IGridNode.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("getConnections")) {
                    reads++;
                    return new ReadOnlyCollection<>(edges);
                }
                throw new AssertionError(method);
            });
    }

    private static final class Fixture {

        final sun.misc.Unsafe unsafe;
        final FixtureWorld world;
        final List<MatrixAssemblyMember> members = new ArrayList<>();

        Fixture() throws Exception {
            Field singleton = field(sun.misc.Unsafe.class, "theUnsafe");
            unsafe = (sun.misc.Unsafe) singleton.get(null);
            world = (FixtureWorld) unsafe.allocateInstance(FixtureWorld.class);
            world.tiles = new HashMap<>();
            world.unloaded = new HashSet<>();
            field(World.class, "provider").set(world, new WorldProviderSurface());
        }

        List<MatrixAssemblyMember> items(boolean alongZ) throws Exception {
            List<MatrixAssemblyMember> items = new ArrayList<>();
            for (int i = 0; i < 5; i++) items.add(add(false, alongZ ? 0 : i, 64, alongZ ? i : 0));
            return items;
        }

        MatrixAssemblyMember add(boolean fluid, int x, int y, int z) throws Exception {
            return add(fluid, x, y, z, false);
        }

        MatrixAssemblyMember add(boolean fluid, int x, int y, int z, boolean mirror) throws Exception {
            Class<? extends MetaTileEntity> type = fluid ? MatrixFluidInputHatch.class : MatrixItemInputBus.class;
            MetaTileEntity meta = (MetaTileEntity) unsafe.allocateInstance(type);
            MatrixAssemblyMember member = (MatrixAssemblyMember) meta;
            field(type, "mirror").setBoolean(meta, mirror);
            MatrixAssemblyPort port = new MatrixAssemblyPort(member);
            field(type, "matrix").set(meta, port);
            field(CommonMetaTileEntity.class, "mInventory").set(meta, new ItemStack[fluid ? 0 : 1]);
            field(MatrixAssemblyPort.class, "proxy").set(port, new AENetworkProxy(null, "matrixProxy", null, true) {

                @Override
                public IGridNode getNode() {
                    return null;
                }
            });
            attach(meta, x, y, z);
            members.add(member);
            return member;
        }

        void attach(MetaTileEntity meta, int x, int y, int z) {
            QuietTile tile = new QuietTile();
            tile.setWorldObj(world);
            tile.xCoord = x;
            tile.yCoord = y;
            tile.zCoord = z;
            meta.setBaseMetaTileEntity(tile);
            world.tiles.put(key(x, y, z), tile);
        }

        MTEMultiBlockBase controller(OrderedAssemblyGroup group, int x) throws Exception {
            MTEMultiBlockBase controller = new FixtureController();
            for (MatrixAssemblyMember member : group.itemMembers())
                controller.mInputBusses.add((MTEHatchInputBus) member);
            for (MatrixAssemblyMember member : group.fluidMembers())
                controller.mInputHatches.add((MTEHatchInput) member);
            attach(controller, x, 64, 20);
            controller.mMachine = true;
            OrderedAssemblyGroup.controllerChecked(group.master(), controller, true);
            return controller;
        }

        int expectedMask(MatrixAssemblyMember member) {
            BaseMetaTileEntity tile = (BaseMetaTileEntity) member.getBaseMetaTileEntity();
            int mask = 0;
            for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) {
                TileEntity other = world.tiles
                    .get(key(tile.xCoord + face.offsetX, tile.yCoord + face.offsetY, tile.zCoord + face.offsetZ));
                if (other instanceof BaseMetaTileEntity gt && gt.getMetaTileEntity() instanceof MatrixAssemblyMember)
                    mask |= face.flag;
            }
            return mask;
        }
    }

    private static final class FixturePlayer extends EntityPlayerMP {

        private List<IChatComponent> messages;

        private FixturePlayer() {
            super(null, null, null, null);
        }

        @Override
        public void addChatMessage(IChatComponent message) {
            messages.add(message);
        }

        String lastMessageKey() {
            return ((ChatComponentTranslation) messages.get(messages.size() - 1)).getKey();
        }
    }

    /** Ordinary native controller state without the client-only tooltip class initializer. */
    private static final class FixtureController extends MTEMultiBlockBase {

        FixtureController() {
            super("matrix_topology_test");
        }

        @Override
        public String[] getDescription() {
            return new String[0];
        }

        @Override
        public IMetaTileEntity newMetaEntity(IGregTechTileEntity tile) {
            return new FixtureController();
        }

        @Override
        public ITexture[] getTexture(IGregTechTileEntity tile, ForgeDirection side, ForgeDirection facing, int color,
            boolean active, boolean redstone) {
            return new ITexture[0];
        }
    }

    private static final class QuietTile extends BaseMetaTileEntity {

        @Override
        public void markDirty() {}
    }

    private static final class FixtureWorld extends WorldServer {

        Map<String, TileEntity> tiles;
        Set<String> unloaded;

        private FixtureWorld() {
            super(null, (ISaveHandler) null, "fixture", 0, (WorldSettings) null, (Profiler) null);
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return !unloaded.contains(key(x, y, z));
        }

        @Override
        public long getTotalWorldTime() {
            return 0;
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            assertTrue("Topology must not load a missing chunk", blockExists(x, y, z));
            return tiles.get(key(x, y, z));
        }

        @Override
        protected IChunkProvider createChunkProvider() {
            return null;
        }

        @Override
        protected int func_152379_p() {
            return 0;
        }

        @Override
        public Entity getEntityByID(int id) {
            return null;
        }
    }

    private static String key(int x, int y, int z) {
        return x + ":" + y + ":" + z;
    }

    private static OrderedPatternLayout layout(int itemCount, int[] fluidAmounts) throws Exception {
        Class<?> fixtures = Class.forName("com.miaokatze.gtswn.common.assembly.pattern.AssemblyPatternFixtures");
        invoke(fixtures, "initialize", new Class<?>[0]);
        IAEStack<?>[] inputs = new IAEStack<?>[itemCount + fluidAmounts.length];
        for (int i = 0; i < itemCount; i++) {
            ItemStack stack = (ItemStack) invoke(fixtures, "input", new Class<?>[] { int.class }, Integer.MAX_VALUE);
            inputs[i] = (IAEStack<?>) invoke(fixtures, "item", new Class<?>[] { ItemStack.class }, stack);
        }
        for (int i = 0; i < fluidAmounts.length; i++) inputs[itemCount + i] = (IAEStack<?>) invoke(
            fixtures,
            "fluid",
            new Class<?>[] { net.minecraftforge.fluids.FluidStack.class },
            FluidRegistryTestBootstrap.water(fluidAmounts[i]));
        ItemStack output = (ItemStack) invoke(fixtures, "output", new Class<?>[] { int.class }, 1);
        IAEItemStack result = (IAEItemStack) invoke(fixtures, "item", new Class<?>[] { ItemStack.class }, output);
        ICraftingPatternDetails details = (ICraftingPatternDetails) invoke(
            fixtures,
            "ultimate",
            new Class<?>[] { IAEStack[].class, IAEItemStack.class },
            inputs,
            result);
        return OrderedPatternLayout.decode(details);
    }

    private static Object invoke(Class<?> type, String name, Class<?>[] signature, Object... arguments)
        throws Exception {
        Method method = type.getDeclaredMethod(name, signature);
        method.setAccessible(true);
        return method.invoke(null, arguments);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
