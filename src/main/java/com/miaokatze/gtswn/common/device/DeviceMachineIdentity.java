package com.miaokatze.gtswn.common.device;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.covers.GTswnCoverWirelessBase;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;

/** Identity metadata is sampled outside tick hot paths; association is taken from controller hatch lists. */
public final class DeviceMachineIdentity {

    private static final ClassValue<Accessors> ACCESSORS = new ClassValue<Accessors>() {

        @Override
        protected Accessors computeValue(Class<?> type) {
            return new Accessors(type);
        }
    };

    private DeviceMachineIdentity() {}

    private static final class Accessors {

        final List<Method> methods = new ArrayList<>();
        final List<Field> fields = new ArrayList<>();

        Accessors(Class<?> type) {
            for (String name : new String[] { "getExoticAndNormalEnergyHatchList", "getExoticDynamoHatches" }) {
                try {
                    methods.add(type.getMethod(name));
                } catch (NoSuchMethodException ignored) {}
            }
            for (String name : new String[] { "mTecTechDynamoHatches", "mTecTechEnergyHatches" }) {
                try {
                    fields.add(type.getField(name));
                } catch (NoSuchFieldException ignored) {}
            }
        }
    }

    public static byte linkNodeType(IMetaTileEntity controller) {
        int flags = flags(controller.getBaseMetaTileEntity());
        if (controller instanceof MTEMultiBlockBase) {
            MTEMultiBlockBase multi = (MTEMultiBlockBase) controller;
            flags |= hatchFlags(multi.mEnergyHatches);
            flags |= hatchFlags(multi.mDynamoHatches);
            Accessors access = ACCESSORS.get(controller.getClass());
            for (Method method : access.methods) {
                try {
                    flags |= hatchFlags(method.invoke(controller));
                } catch (ReflectiveOperationException | RuntimeException ignored) {}
            }
            for (Field field : access.fields) {
                try {
                    flags |= hatchFlags(field.get(controller));
                } catch (ReflectiveOperationException | RuntimeException ignored) {}
            }
        }
        return (byte) flags;
    }

    private static int hatchFlags(Object hatches) {
        int result = 0;
        if (hatches instanceof Iterable<?>) {
            for (Object hatch : (Iterable<?>) hatches) {
                if (hatch instanceof IMetaTileEntity)
                    result |= flags(((IMetaTileEntity) hatch).getBaseMetaTileEntity());
                if (result == 3) break;
            }
        }
        return result;
    }

    private static int flags(IGregTechTileEntity tile) {
        if (tile == null) return 0;
        int result = 0;
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            Object cover = tile.getCoverAtSide(side);
            if (cover instanceof GTswnCoverWirelessBase) {
                result |= ((GTswnCoverWirelessBase) cover).nodeTypeId() == 0 ? 1 : 2;
            }
        }
        return result;
    }
}
