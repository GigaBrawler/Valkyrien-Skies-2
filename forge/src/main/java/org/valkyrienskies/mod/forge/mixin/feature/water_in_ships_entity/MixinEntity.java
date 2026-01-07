package org.valkyrienskies.mod.forge.mixin.feature.water_in_ships_entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.config.VSGameConfig;
import org.valkyrienskies.mod.common.feature.ship_water_pockets.ShipWaterPocketManager;
import org.valkyrienskies.mod.mixinducks.feature.ship_water_pockets.ShipWaterPocketEntityDuck;

@Mixin(Entity.class)
public abstract class MixinEntity {
    @Shadow
    public Level level;
    @Shadow
    private AABB bb;

    @Shadow
    public abstract double getEyeY();

    @Shadow
    public abstract double getX();

    @Shadow
    public abstract double getZ();

    @Unique
    private boolean isShipWater = false;

    @Unique
    private boolean valkyrienskies$ignoreWorldWaterInAirPocket = false;

    /**
     * used to replace updateFluidHeightAndDoFluidPushing aabb in ship context
     * */
    @Unique
    private AABB valkyrienskies$fluidPushAABB = null;

    /**
     * list of fluid push to calculate
     * used to combine updateFluidHeightAndDoFluidPushing interimCalcs of normal and ship context
     * */
    @Unique
    private Object2ObjectMap<?,?> valkyrienskies$interimCalcs = null;

    @Shadow
    public abstract void updateFluidHeightAndDoFluidPushing(Predicate<FluidState> par1);

    @Unique
    private boolean inShipContext() {
        return valkyrienskies$fluidPushAABB != null;
    }

    //IDE may show error, ignore its valid mixin
    @ModifyVariable(
        method = "updateFluidHeightAndDoFluidPushing(Ljava/util/function/Predicate;)V",
        at = @At(value = "STORE"),
        remap = false
    )
    private AABB setFluidPushAABB(AABB original) {
        if (inShipContext())
            return valkyrienskies$fluidPushAABB;

        return original;
    }

    @Redirect(
        method = "updateFluidHeightAndDoFluidPushing(Ljava/util/function/Predicate;)V",
        at = @At(value = "NEW", target = "(I)Lit/unimi/dsi/fastutil/objects/Object2ObjectArrayMap;"),
        remap = false
    )
    private Object2ObjectArrayMap<?, ?> setInterimCalcInstance(int capacity) {
        if (inShipContext()) {
            return (Object2ObjectArrayMap<?, ?>) valkyrienskies$interimCalcs;
        }
        return (Object2ObjectArrayMap<?, ?>) (valkyrienskies$interimCalcs = new Object2ObjectArrayMap<>(capacity));
    }

    @Inject(
        method = "updateFluidHeightAndDoFluidPushing(Ljava/util/function/Predicate;)V",
        at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/objects/Object2ObjectMap;forEach(Ljava/util/function/BiConsumer;)V"),
        remap = false,
        cancellable = true
    )
    private void shouldProcessPush(Predicate<FluidState> shouldUpdate, CallbackInfo ci) {
        if (inShipContext()) {
            ci.cancel();
        }
    }

    @Redirect(
        method = "updateFluidHeightAndDoFluidPushing(Ljava/util/function/Predicate;)V",
        at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/objects/Object2ObjectMap;forEach(Ljava/util/function/BiConsumer;)V"),
        remap = false
    )
    private void collectShipFluidPush(Object2ObjectMap instance, BiConsumer consumer,
        @Local(ordinal = 0, argsOnly = true) Predicate<FluidState> shouldUpdate, @Local(ordinal = 0) AABB aabb) {
        if (VSGameConfig.COMMON.WATER_POCKETS.getEnableShipWaterPockets()) {
            instance.forEach(consumer);
            valkyrienskies$fluidPushAABB = null;
            valkyrienskies$interimCalcs = null;
            return;
        }
        VSGameUtilsKt.transformFromWorldToNearbyShipsAndWorld(level, aabb, (shipAabb) -> {
            valkyrienskies$fluidPushAABB = shipAabb; // enable ship context
            this.updateFluidHeightAndDoFluidPushing(shouldUpdate); //recall in the ship context
        });
        valkyrienskies$fluidPushAABB = null;
        valkyrienskies$interimCalcs = null;

        //processing collected push (vanilla and ship)
        instance.forEach(consumer);
    }

    @WrapOperation(
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"),
        method = "updateFluidOnEyes"
    )
    private FluidState getFluidStateRedirect(final Level level, final BlockPos blockPos,
        final Operation<FluidState> getFluidState) {
        final FluidState original = getFluidState.call(level, blockPos);
        isShipWater = false;
        if (VSGameConfig.COMMON.WATER_POCKETS.getEnableShipWaterPockets()) {
            final double eyeY = this.getEyeY() - 0.1111111119389534;
            final FluidState overridden =
                ShipWaterPocketManager.overrideWaterFluidState(level, this.getX(), eyeY, this.getZ(), original);
            isShipWater = overridden != original && !overridden.isEmpty();
            return overridden;
        }

        if (original.isEmpty()) {
            final FluidState[] fluidState = {original};

            final double d = this.getEyeY() - 0.1111111119389534;

            final double origX = this.getX();
            final double origY = d;
            final double origZ = this.getZ();

            VSGameUtilsKt.transformToNearbyShipsAndWorld(this.level, origX, origY, origZ, this.bb.getSize(),
                (x, y, z) -> {
                    fluidState[0] = getFluidState.call(level, BlockPos.containing(x, y, z));
                });
            isShipWater = true;
            return fluidState[0];
        }

        return original;
    }

    @Inject(
        method = "updateFluidHeightAndDoFluidPushing(Ljava/util/function/Predicate;)V",
        at = @At("HEAD"),
        remap = false
    )
    private void vs$setupWaterPocketFluidPushOverride(final Predicate<FluidState> shouldUpdate,
        final CallbackInfo ci) {
        valkyrienskies$ignoreWorldWaterInAirPocket = false;
        if (!VSGameConfig.COMMON.WATER_POCKETS.getEnableShipWaterPockets()) return;
        if (inShipContext()) return;

        valkyrienskies$ignoreWorldWaterInAirPocket =
            ((ShipWaterPocketEntityDuck) (Object) this).vs$isInShipAirPocketForWorldWater();
    }

    @WrapOperation(
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"),
        method = "updateFluidHeightAndDoFluidPushing(Ljava/util/function/Predicate;)V",
        remap = false
    )
    private FluidState overrideFluidStateRedirect(final Level level, final BlockPos blockPos,
        final Operation<FluidState> getFluidState) {
        final FluidState original = getFluidState.call(level, blockPos);
        if (inShipContext()) {
            return original;
        }
        if (!VSGameConfig.COMMON.WATER_POCKETS.getEnableShipWaterPockets()) return original;

        if (valkyrienskies$ignoreWorldWaterInAirPocket && !original.isEmpty() && original.is(Fluids.WATER)) {
            return Fluids.EMPTY.defaultFluidState();
        }

        return ShipWaterPocketManager.overrideWaterFluidState(level, blockPos, original);
    }

    @WrapOperation(
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/material/FluidState;getHeight(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F"),
        method = "updateFluidOnEyes"
    )
    private float fluidHeightOverride(final FluidState instance, final BlockGetter arg, final BlockPos arg2,
        final Operation<Float> getHeight) {
        if (!instance.isEmpty() && this.level instanceof Level) {

            if (isShipWater) {
                if (instance.isSource()) {
                    return 1;
                }
            }

        }
        return getHeight.call(instance, arg, arg2);
    }

}
