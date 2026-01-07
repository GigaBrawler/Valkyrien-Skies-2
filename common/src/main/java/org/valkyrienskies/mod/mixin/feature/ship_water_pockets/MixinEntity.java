package org.valkyrienskies.mod.mixin.feature.ship_water_pockets;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.mod.common.config.VSGameConfig;
import org.valkyrienskies.mod.common.feature.ship_water_pockets.ShipWaterPocketManager;
import org.valkyrienskies.mod.mixinducks.feature.ship_water_pockets.ShipWaterPocketEntityDuck;

@Mixin(Entity.class)
public abstract class MixinEntity implements ShipWaterPocketEntityDuck {
    @Shadow
    public Level level;

    @Shadow
    public abstract double getEyeY();

    @Shadow
    public abstract double getX();

    @Shadow
    public abstract double getZ();

    @Shadow
    public abstract AABB getBoundingBox();

    @org.spongepowered.asm.mixin.Unique
    private static final FluidState vs$WATER = Fluids.WATER.defaultFluidState();

    @org.spongepowered.asm.mixin.Unique
    private static final int vs$AIR_POCKET_EXIT_DELAY_TICKS = 2;

    @org.spongepowered.asm.mixin.Unique
    private long vs$airPocketCacheTick = Long.MIN_VALUE;

    @org.spongepowered.asm.mixin.Unique
    private boolean vs$airPocketCacheValue = false;

    @org.spongepowered.asm.mixin.Unique
    private int vs$airPocketConsecutiveFalseTicks = 0;

    @Override
    public boolean vs$isInShipAirPocketForWorldWater() {
        if (!VSGameConfig.COMMON.WATER_POCKETS.getEnableShipWaterPockets()) return false;
        final Level lvl = this.level;
        if (lvl == null) return false;

        final long now = lvl.getGameTime();
        if (vs$airPocketCacheTick == now) {
            return vs$airPocketCacheValue;
        }

        final boolean inAirPocketNow = vs$computeInShipAirPocketNow(lvl);
        final boolean prev = vs$airPocketCacheValue;

        final boolean value;
        if (inAirPocketNow) {
            vs$airPocketConsecutiveFalseTicks = 0;
            value = true;
        } else if (prev) {
            vs$airPocketConsecutiveFalseTicks++;
            value = vs$airPocketConsecutiveFalseTicks < vs$AIR_POCKET_EXIT_DELAY_TICKS;
        } else {
            value = false;
        }

        vs$airPocketCacheTick = now;
        vs$airPocketCacheValue = value;
        return value;
    }

    @org.spongepowered.asm.mixin.Unique
    private boolean vs$computeInShipAirPocketNow(final Level level) {
        final AABB bb = this.getBoundingBox();
        final double halfX = (bb.maxX - bb.minX) * 0.5;
        final double halfZ = (bb.maxZ - bb.minZ) * 0.5;
        final double ox = Math.min(0.125, halfX * 0.5);
        final double oz = Math.min(0.125, halfZ * 0.5);

        final double eyeY = this.getEyeY() - 0.1111111119389534;
        if (vs$isInAirPocketAtHeight(level, eyeY, ox, oz)) return true;

        final double feetY = bb.minY + 0.1;
        return vs$isInAirPocketAtHeight(level, feetY, ox, oz);
    }

    @org.spongepowered.asm.mixin.Unique
    private boolean vs$isInAirPocketAtHeight(final Level level, final double y, final double ox, final double oz) {
        final double x = this.getX();
        final double z = this.getZ();

        if (vs$isAirPocketAtPoint(level, x, y, z)) return true;
        if (ox <= 0.0 && oz <= 0.0) return false;

        if (ox > 0.0) {
            if (vs$isAirPocketAtPoint(level, x + ox, y, z)) return true;
            if (vs$isAirPocketAtPoint(level, x - ox, y, z)) return true;
        }
        if (oz > 0.0) {
            if (vs$isAirPocketAtPoint(level, x, y, z + oz)) return true;
            if (vs$isAirPocketAtPoint(level, x, y, z - oz)) return true;
        }
        if (ox > 0.0 && oz > 0.0) {
            if (vs$isAirPocketAtPoint(level, x + ox, y, z + oz)) return true;
            if (vs$isAirPocketAtPoint(level, x + ox, y, z - oz)) return true;
            if (vs$isAirPocketAtPoint(level, x - ox, y, z + oz)) return true;
            if (vs$isAirPocketAtPoint(level, x - ox, y, z - oz)) return true;
        }

        return false;
    }

    @org.spongepowered.asm.mixin.Unique
    private static boolean vs$isAirPocketAtPoint(final Level level, final double x, final double y, final double z) {
        final FluidState overridden = ShipWaterPocketManager.overrideWaterFluidState(level, x, y, z, vs$WATER);
        return overridden.isEmpty();
    }

    @Inject(method = "doWaterSplashEffect", at = @At("HEAD"), cancellable = true)
    private void vs$cancelSplashInShipAirPockets(final CallbackInfo ci) {
        if (vs$isInShipAirPocketForWorldWater()) {
            ci.cancel();
        }
    }

    @Inject(method = "waterSwimSound", at = @At("HEAD"), cancellable = true)
    private void vs$cancelSwimSplashSoundInShipAirPockets(final CallbackInfo ci) {
        if (vs$isInShipAirPocketForWorldWater()) {
            ci.cancel();
        }
    }

    @Inject(method = "playSwimSound", at = @At("HEAD"), cancellable = true)
    private void vs$cancelSwimSoundInShipAirPockets(final float volume, final CallbackInfo ci) {
        if (vs$isInShipAirPocketForWorldWater()) {
            ci.cancel();
        }
    }

    @Inject(method = "isInWater", at = @At("RETURN"), cancellable = true)
    private void vs$overrideInWaterFlagInShipAirPockets(final CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) return;
        if (vs$isInShipAirPocketForWorldWater()) {
            cir.setReturnValue(false);
        }
    }
}
