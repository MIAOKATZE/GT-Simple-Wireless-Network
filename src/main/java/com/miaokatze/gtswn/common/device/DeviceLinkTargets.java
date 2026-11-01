package com.miaokatze.gtswn.common.device;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.covers.GTswnCoverWirelessBase;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicGenerator;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.metatileentity.implementations.MTEHatchDynamo;
import gregtech.api.metatileentity.implementations.MTEHatchEnergy;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gtPlusPlus.xmod.gregtech.api.metatileentity.implementations.base.GTPPMultiBlockBase;
import tectech.thing.metaTileEntity.hatch.MTEHatchDynamoMulti;
import tectech.thing.metaTileEntity.hatch.MTEHatchEnergyMulti;

/** Ephemeral candidates from the controller's current structure lists, shared by counts and actions. */
public final class DeviceLinkTargets {

    private DeviceLinkTargets() {}

    public static final class Target {

        public final IGregTechTileEntity tile;
        public final IMetaTileEntity machine;
        public final ForgeDirection side;
        public final boolean dynamo;

        private Target(IGregTechTileEntity tile, boolean dynamo, boolean hatch) {
            this.tile = tile;
            this.machine = tile.getMetaTileEntity();
            this.side = hatch ? tile.getFrontFacing()
                : tile.getFrontFacing()
                    .getOpposite();
            this.dynamo = dynamo;
        }

        public boolean current() {
            return live(machine) && tile.getMetaTileEntity() == machine
                && side == (machine instanceof MTEHatch ? tile.getFrontFacing()
                    : tile.getFrontFacing()
                        .getOpposite());
        }

        /** A connected target has exactly one node, of the corresponding type, on the required face. */
        public boolean connected() {
            int count = 0;
            boolean correct = false;
            for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) {
                Object cover = tile.getCoverAtSide(face);
                if (cover instanceof GTswnCoverWirelessBase) {
                    count++;
                    correct = face == side && ((GTswnCoverWirelessBase) cover).nodeTypeId() == (dynamo ? 1 : 0);
                }
            }
            return count == 1 && correct;
        }
    }

    public static final class Discovery {

        public final DeviceLinkSummary summary;
        public final List<Target> targets;

        private Discovery(DeviceLinkSummary summary, List<Target> targets) {
            this.summary = summary;
            this.targets = Collections.unmodifiableList(targets);
        }
    }

    public static Discovery discover(IMetaTileEntity controller) {
        if (!live(controller)) return result(DeviceLinkSummary.UNVERIFIED);
        List<Target> targets = new ArrayList<>();
        if (controller instanceof MTEMultiBlockBase) {
            MTEMultiBlockBase multi = (MTEMultiBlockBase) controller;
            if (!multi.mMachine) return result(DeviceLinkSummary.UNVERIFIED);
            Map<IGregTechTileEntity, Target> unique = new IdentityHashMap<>();
            boolean valid = add(targets, unique, multi.mEnergyHatches, false);
            valid &= add(targets, unique, multi.getExoticAndNormalEnergyHatchList(), false);
            valid &= add(targets, unique, multi.mDynamoHatches, true);
            valid &= add(targets, unique, multi.getExoticDynamoHatches(), true);
            if (multi instanceof GTPPMultiBlockBase) {
                GTPPMultiBlockBase<?> gtpp = (GTPPMultiBlockBase<?>) multi;
                valid &= add(targets, unique, gtpp.mTecTechEnergyHatches, false);
                valid &= add(targets, unique, gtpp.mTecTechDynamoHatches, true);
            }
            if (!valid) return result(DeviceLinkSummary.UNVERIFIED);
        } else if (single(controller)) {
            targets.add(new Target(controller.getBaseMetaTileEntity(), controller instanceof MTEBasicGenerator, false));
        } else return result(DeviceLinkSummary.NONE);
        if (targets.isEmpty()) return result(DeviceLinkSummary.NONE);
        int energy = 0, energyConnected = 0, dynamo = 0, dynamoConnected = 0;
        for (Target target : targets) {
            if (target.dynamo) {
                dynamo++;
                if (target.connected()) dynamoConnected++;
            } else {
                energy++;
                if (target.connected()) energyConnected++;
            }
        }
        return new Discovery(
            new DeviceLinkSummary(DeviceLinkSummary.KNOWN, energyConnected, energy, dynamoConnected, dynamo),
            targets);
    }

    /** Automatic placement is independent of formed structures and never selects dynamo hatches. */
    public static Target placed(IMetaTileEntity machine) {
        if (!live(machine)) return null;
        if (single(machine))
            return new Target(machine.getBaseMetaTileEntity(), machine instanceof MTEBasicGenerator, false);
        if (machine instanceof MTEHatchEnergy || machine instanceof MTEHatchEnergyMulti)
            return new Target(machine.getBaseMetaTileEntity(), false, true);
        return null;
    }

    private static boolean single(IMetaTileEntity machine) {
        return (machine instanceof MTEBasicMachine || machine instanceof MTEBasicGenerator)
            && machine.getBaseMetaTileEntity()
                .getEUCapacity() > 0;
    }

    private static boolean add(List<Target> targets, Map<IGregTechTileEntity, Target> unique, Iterable<?> hatches,
        boolean dynamo) {
        boolean valid = true;
        for (Object entry : hatches) {
            if (!(entry instanceof MTEHatch)) {
                valid = false;
                continue;
            }
            IMetaTileEntity machine = (IMetaTileEntity) entry;
            if (dynamo ? !(machine instanceof MTEHatchDynamo || machine instanceof MTEHatchDynamoMulti)
                : !(machine instanceof MTEHatchEnergy || machine instanceof MTEHatchEnergyMulti)) continue;
            if (!live(machine)) {
                valid = false;
                continue;
            }
            IGregTechTileEntity tile = machine.getBaseMetaTileEntity();
            Target prior = unique.get(tile);
            if (prior != null) {
                if (prior.dynamo != dynamo) valid = false;
                continue;
            }
            Target target = new Target(tile, dynamo, true);
            unique.put(tile, target);
            targets.add(target);
        }
        return valid;
    }

    private static boolean live(IMetaTileEntity machine) {
        if (machine == null) return false;
        IGregTechTileEntity tile = machine.getBaseMetaTileEntity();
        if (tile == null || tile.isDead() || tile.getMetaTileEntity() != machine) return false;
        World world = tile.getWorld();
        return world != null && world.blockExists(tile.getXCoord(), tile.getYCoord(), tile.getZCoord())
            && world.getTileEntity(tile.getXCoord(), tile.getYCoord(), tile.getZCoord()) == tile
            && (!(tile instanceof TileEntity) || !((TileEntity) tile).isInvalid());
    }

    private static Discovery result(DeviceLinkSummary summary) {
        return new Discovery(summary, new ArrayList<>());
    }
}
