package org.valkyrienskies.mod.mixin.feature.ship_water_pockets;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.feature.ship_water_pockets.ShipWaterPocketManager;

@Mixin(Level.class)
public abstract class MixinLevel {

    @Inject(
        method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
        at = @At("TAIL")
    )
    private void vs$markShipWaterPocketDirty(final BlockPos pos, final BlockState state, final int flags,
        final int recursionLeft, final CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) return;
        if (ShipWaterPocketManager.isApplyingInternalUpdates()) return;

        final Level level = Level.class.cast(this);
        if (!VSGameUtilsKt.isBlockInShipyard(level, pos)) return;

        final Ship ship = VSGameUtilsKt.getShipManagingPos(level, pos);
        if (ship == null) return;

        ShipWaterPocketManager.markShipDirty(level, ship.getId());
    }
}

