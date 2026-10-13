package com.miaokatze.gtswn.common.items;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MovingObjectPosition;

import com.miaokatze.gtswn.common.device.DeviceTerminalVisualRules;

import appeng.util.LookDirection;
import appeng.util.Platform;

/** 双端可用的空气交互守卫，同时排除视线中的方块与可交互实体。 */
public final class TerminalAirInteraction {

    private TerminalAirInteraction() {}

    public static boolean isAir(EntityPlayer player) {
        // Forge SP's default eye height is .12, while MP's is 1.62. AE's getEyeOffset is client-only.
        float eyeY = (float) DeviceTerminalVisualRules
            .eyeY(player.posY, player.yOffset, player.getEyeHeight(), player.getDefaultEyeHeight());
        LookDirection look = Platform.getPlayerRay(player, eyeY);
        MovingObjectPosition hit = player.worldObj.rayTraceBlocks(look.getA(), look.getB(), true);
        if (hit != null && hit.typeOfHit != MovingObjectPosition.MovingObjectType.MISS) return false;
        for (Object object : player.worldObj.getEntitiesWithinAABBExcludingEntity(
            player,
            player.boundingBox
                .addCoord(
                    look.getB().xCoord - look.getA().xCoord,
                    look.getB().yCoord - look.getA().yCoord,
                    look.getB().zCoord - look.getA().zCoord)
                .expand(1.0, 1.0, 1.0))) {
            Entity entity = (Entity) object;
            if (!entity.canBeCollidedWith()) continue;
            float border = entity.getCollisionBorderSize();
            if (entity.boundingBox.expand(border, border, border)
                .isVecInside(look.getA())
                || entity.boundingBox.expand(border, border, border)
                    .calculateIntercept(look.getA(), look.getB()) != null)
                return false;
        }
        return true;
    }
}
