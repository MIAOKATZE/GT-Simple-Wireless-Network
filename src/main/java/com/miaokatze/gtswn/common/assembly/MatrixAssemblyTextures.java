// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.assembly;

import net.minecraftforge.common.util.ForgeDirection;

import gregtech.api.enums.Textures;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.render.TextureFactory;

/** Six world connection bits project onto the actual rendered face; sixteen finite edge sprites. */
public final class MatrixAssemblyTextures {

    private static final ITexture[][] BRIDGES = new ITexture[4][16];
    private static final ITexture[][] TRANSITIONS = new ITexture[4][4];
    private static final ITexture[] CENTERS = new ITexture[4];
    private static final ITexture[] COLORS = new ITexture[17];

    static {
        String[] styles = { "item", "fluid", "item_mirror", "fluid_mirror" };
        for (int style = 0; style < styles.length; style++) {
            CENTERS[style] = TextureFactory.of(
                Textures.BlockIcons
                    .custom("gtswn", style == 0 ? "matrix_input_center" : "matrix/" + styles[style] + "/center"));
            for (int mask = 0; mask < 16; mask++) BRIDGES[style][mask] = TextureFactory.of(
                Textures.BlockIcons
                    .custom("gtswn", "matrix/" + (style == 0 ? "" : styles[style] + "/") + "bridge_" + mask));
            for (int edge = 0; edge < 4; edge++) TRANSITIONS[style][edge] = TextureFactory
                .of(Textures.BlockIcons.custom("gtswn", "matrix/" + styles[style] + "/transition_" + (1 << edge)));
        }
        for (int i = 0; i < COLORS.length; i++)
            COLORS[i] = TextureFactory.of(Textures.BlockIcons.OVERLAY_ME_CRAFTING_INPUT_COLORS[i]);
    }

    private MatrixAssemblyTextures() {}

    /** Initialize from the MTE icon hook, before GregTech runs its block-icon load queue. */
    static void init() {}

    public static ITexture[] front(MatrixAssemblyMember member, ITexture base) {
        IGregTechTileEntity tile = member.getBaseMetaTileEntity();
        int mask = faceMask(
            member.matrixPort()
                .connectionMask(),
            tile.getFrontFacing());
        int style = (member.matrixMirror() ? 2 : 0) + (member.matrixFluid() ? 1 : 0);
        int transitions = faceMask(transitionMask(member), tile.getFrontFacing());
        ITexture[] layers = new ITexture[4 + Integer.bitCount(transitions)];
        layers[0] = base;
        layers[1] = BRIDGES[style][mask];
        layers[2] = CENTERS[style];
        layers[3] = COLORS[tile.getColorization() + 1];
        int next = 4;
        for (int edge = 0; edge < 4; edge++)
            if ((transitions & (1 << edge)) != 0) layers[next++] = TRANSITIONS[style][edge];
        return layers;
    }

    /** Client-side groups are not reconstructed; only verified, synchronized physical edges may blend. */
    static int transitionMask(MatrixAssemblyMember member) {
        IGregTechTileEntity tile = member.getBaseMetaTileEntity();
        if (tile.getWorld() == null) return 0;
        int connections = member.matrixPort()
            .connectionMask();
        int result = 0;
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) if ((connections & side.flag) != 0) {
            IGregTechTileEntity neighbour = tile.getIGregTechTileEntityAtSide(side);
            if (neighbour != null && neighbour.getMetaTileEntity() instanceof MatrixAssemblyMember other
                && other.matrixMirror() != member.matrixMirror()) result |= side.flag;
        }
        return result;
    }

    public static int faceMask(int worldMask, ForgeDirection normal) {
        ForgeDirection right;
        ForgeDirection down;
        switch (normal) {
            case NORTH:
                right = ForgeDirection.WEST;
                down = ForgeDirection.DOWN;
                break;
            case SOUTH:
                right = ForgeDirection.EAST;
                down = ForgeDirection.DOWN;
                break;
            case WEST:
                right = ForgeDirection.SOUTH;
                down = ForgeDirection.DOWN;
                break;
            case EAST:
                right = ForgeDirection.NORTH;
                down = ForgeDirection.DOWN;
                break;
            case DOWN:
                // GregTech's bottom-face U coordinates run westward, unlike the top face.
                right = ForgeDirection.WEST;
                down = ForgeDirection.SOUTH;
                break;
            default:
                right = ForgeDirection.EAST;
                down = ForgeDirection.SOUTH;
                break;
        }
        int mask = 0;
        if ((worldMask & right.getOpposite().flag) != 0) mask |= 1;
        if ((worldMask & right.flag) != 0) mask |= 2;
        if ((worldMask & down.getOpposite().flag) != 0) mask |= 4;
        if ((worldMask & down.flag) != 0) mask |= 8;
        return mask;
    }
}
