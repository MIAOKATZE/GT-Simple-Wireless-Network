package com.miaokatze.gtswn.common.assembly;

import net.minecraftforge.common.util.ForgeDirection;

import gregtech.api.enums.Textures;
import gregtech.api.interfaces.ITexture;
import gregtech.api.render.TextureFactory;

/** Six world connection bits project onto the actual rendered face; sixteen finite edge sprites. */
public final class MatrixAssemblyTextures {

    private static final ITexture[] BRIDGES = new ITexture[16];
    private static final ITexture CENTER = TextureFactory
        .of(Textures.BlockIcons.custom("gtswn", "matrix_input_center"));
    private static final ITexture[] COLORS = new ITexture[17];

    static {
        for (int i = 0; i < BRIDGES.length; i++)
            BRIDGES[i] = TextureFactory.of(Textures.BlockIcons.custom("gtswn", "matrix/bridge_" + i));
        for (int i = 0; i < COLORS.length; i++)
            COLORS[i] = TextureFactory.of(Textures.BlockIcons.OVERLAY_ME_CRAFTING_INPUT_COLORS[i]);
    }

    private MatrixAssemblyTextures() {}

    /** Initialize from the MTE icon hook, before GregTech runs its block-icon load queue. */
    static void init() {}

    public static ITexture[] front(MatrixAssemblyMember member, ITexture base) {
        int mask = faceMask(
            member.matrixPort()
                .connectionMask(),
            member.getBaseMetaTileEntity()
                .getFrontFacing());
        return new ITexture[] { base, BRIDGES[mask], CENTER, COLORS[member.getBaseMetaTileEntity()
            .getColorization() + 1] };
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
