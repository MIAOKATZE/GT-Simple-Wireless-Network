package com.miaokatze.gtswn.common.assembly;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.alignment.enumerable.Flip;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/** A data-stick address and immutable physical membership snapshot, never an inventory. */
final class MatrixMirrorLink {

    static final String DATA_TYPE = "gtswnMatrixSource";
    private final NBTTagCompound data;

    MatrixMirrorLink(NBTTagCompound data) {
        if (!DATA_TYPE.equals(data.getString("type")) || data.getInteger("schema") != 1
            || !data.hasKey("dimension", 3)
            || data.getIntArray("anchor").length != 3
            || !data.hasKey("member", 8)
            || !data.hasKey("members", 9)) throw new IllegalArgumentException("mirror_invalid_link");
        UUID.fromString(data.getString("member"));
        NBTTagList members = data.getTagList("members", 10);
        if (members.tagCount() < 2 || members.tagCount() > 20)
            throw new IllegalArgumentException("mirror_invalid_link");
        Set<UUID> identities = new HashSet<>();
        boolean anchorFound = false;
        for (int i = 0; i < members.tagCount(); i++) {
            NBTTagCompound member = members.getCompoundTagAt(i);
            UUID id = UUID.fromString(member.getString("id"));
            if (!identities.add(id) || member.getIntArray("position").length != 3 || !member.hasKey("fluid", 1))
                throw new IllegalArgumentException("mirror_invalid_link");
            if (member.getString("id")
                .equals(data.getString("member"))
                && java.util.Arrays.equals(member.getIntArray("position"), data.getIntArray("anchor")))
                anchorFound = true;
        }
        if (!anchorFound) throw new IllegalArgumentException("mirror_invalid_link");
        this.data = (NBTTagCompound) data.copy();
    }

    static NBTTagCompound copy(MatrixAssemblyMember anchor, OrderedAssemblyGroup source) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("type", DATA_TYPE);
        tag.setInteger("schema", 1);
        tag.setInteger(
            "dimension",
            anchor.getBaseMetaTileEntity()
                .getWorld().provider.dimensionId);
        tag.setIntArray("anchor", position(anchor));
        tag.setString(
            "member",
            anchor.orderedMemberId()
                .toString());
        NBTTagList members = new NBTTagList();
        for (MatrixAssemblyMember member : source.physicalMembers()) {
            NBTTagCompound saved = new NBTTagCompound();
            saved.setString(
                "id",
                member.orderedMemberId()
                    .toString());
            saved.setIntArray("position", position(member));
            saved.setBoolean("fluid", member.matrixFluid());
            members.appendTag(saved);
        }
        tag.setTag("members", members);
        return tag;
    }

    NBTTagCompound save() {
        return (NBTTagCompound) data.copy();
    }

    int[] address() {
        int[] anchor = data.getIntArray("anchor");
        return new int[] { data.getInteger("dimension"), anchor[0], anchor[1], anchor[2] };
    }

    OrderedAssemblyGroup source(World world) {
        if (world.provider.dimensionId != data.getInteger("dimension")) return null;
        int[] pos = data.getIntArray("anchor");
        if (!world.blockExists(pos[0], pos[1], pos[2])) return null;
        TileEntity tile = world.getTileEntity(pos[0], pos[1], pos[2]);
        if (!(tile instanceof IGregTechTileEntity gt)
            || !(gt.getMetaTileEntity() instanceof MatrixAssemblyMember anchor)
            || anchor.getBaseMetaTileEntity() != gt
            || !anchor.orderedMemberId()
                .toString()
                .equals(data.getString("member")))
            return null;
        OrderedAssemblyGroup group = anchor.matrixPort()
            .group();
        if (group == null || group.isMirror() || !group.topologyValid()) return null;
        NBTTagList saved = data.getTagList("members", 10);
        if (saved.tagCount() != group.physicalMembers()
            .size()) return null;
        for (int i = 0; i < saved.tagCount(); i++) {
            NBTTagCompound expected = saved.getCompoundTagAt(i);
            boolean found = false;
            for (MatrixAssemblyMember member : group.physicalMembers()) {
                if (expected.getString("id")
                    .equals(
                        member.orderedMemberId()
                            .toString())
                    && expected.getBoolean("fluid") == member.matrixFluid()
                    && java.util.Arrays.equals(expected.getIntArray("position"), position(member))) found = true;
            }
            if (!found) return null;
        }
        return group;
    }

    /** The copied member maps to the mirror; every typed relative position must use the same rotation. */
    Map<MatrixAssemblyMember, MatrixAssemblyMember> mapping(OrderedAssemblyGroup source,
        OrderedAssemblyGroup destination, MatrixAssemblyMember mirror) {
        int[] from = data.getIntArray("anchor"), to = position(mirror);
        MatrixAssemblyMember sourceAnchor = null;
        for (MatrixAssemblyMember member : source.physicalMembers()) if (member.orderedMemberId()
            .toString()
            .equals(data.getString("member"))) sourceAnchor = member;
        if (sourceAnchor == null || sourceAnchor.matrixFluid() != mirror.matrixFluid()
            || source.physicalMembers()
                .size()
                != destination.physicalMembers()
                    .size())
            return null;
        return mapPositions(source.physicalMembers(), destination.physicalMembers(), from, to);
    }

    static Map<MatrixAssemblyMember, MatrixAssemblyMember> mapPositions(List<MatrixAssemblyMember> source,
        List<MatrixAssemblyMember> destination, int[] from, int[] to) {
        // Preserve the exact priority and facing-independent interpretation of existing horizontal links.
        for (int rotation = 0; rotation < 4; rotation++) {
            Map<MatrixAssemblyMember, MatrixAssemblyMember> result = new IdentityHashMap<>();
            for (MatrixAssemblyMember member : source) {
                int[] pos = position(member);
                int dx = pos[0] - from[0], dz = pos[2] - from[2];
                for (int turn = 0; turn < rotation; turn++) {
                    int nextX = -dz;
                    dz = dx;
                    dx = nextX;
                }
                for (MatrixAssemblyMember target : destination) {
                    int[] candidate = position(target);
                    if (target.matrixFluid() == member.matrixFluid() && candidate[0] == to[0] + dx
                        && candidate[1] == to[1] + pos[1] - from[1]
                        && candidate[2] == to[2] + dz) result.put(target, member);
                }
            }
            if (result.size() == source.size() && result.size() == destination.size()) return result;
        }
        // New horizontal/vertical conversions rotate fronts along with positions. StructureLib already supplies
        // the 24 proper orthogonal rotations; flips would change the copied layout's handedness.
        for (ExtendedFacing rotation : ExtendedFacing.VALUES) {
            if (rotation.getFlip() != Flip.NONE) continue;
            Map<MatrixAssemblyMember, MatrixAssemblyMember> result = new IdentityHashMap<>();
            for (MatrixAssemblyMember member : source) {
                int[] pos = position(member), offset = new int[3], front = new int[3];
                rotation.getWorldOffset(new int[] { pos[0] - from[0], pos[1] - from[1], pos[2] - from[2] }, offset);
                ForgeDirection facing = member.getBaseMetaTileEntity()
                    .getFrontFacing();
                rotation.getWorldOffset(new int[] { facing.offsetX, facing.offsetY, facing.offsetZ }, front);
                for (MatrixAssemblyMember target : destination) {
                    int[] candidate = position(target);
                    ForgeDirection targetFacing = target.getBaseMetaTileEntity()
                        .getFrontFacing();
                    if (target.matrixFluid() == member.matrixFluid() && candidate[0] == to[0] + offset[0]
                        && candidate[1] == to[1] + offset[1]
                        && candidate[2] == to[2] + offset[2]
                        && targetFacing.offsetX == front[0]
                        && targetFacing.offsetY == front[1]
                        && targetFacing.offsetZ == front[2]) result.put(target, member);
                }
            }
            if (result.size() == source.size() && result.size() == destination.size()) return result;
        }
        return null;
    }

    private static int[] position(MatrixAssemblyMember member) {
        IGregTechTileEntity tile = member.getBaseMetaTileEntity();
        return new int[] { tile.getXCoord(), tile.getYCoord(), tile.getZCoord() };
    }
}
