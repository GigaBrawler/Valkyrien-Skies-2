package org.valkyrienskies.mod.mixin.server.world;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkMap.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.core.internal.world.VsiPlayer;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.MinecraftPlayer;
import org.valkyrienskies.mod.common.util.ShipSettingsKt;

//This should trump Very Many Players, which is set to 1050
@Mixin(value = ChunkMap.class, priority = 1100)
public abstract class MixinChunkMap {

    @Shadow
    @Final
    ServerLevel level;

    @Shadow
    @Final
    private Supplier<DimensionDataStorage> overworldDataStorage;

    /**
     * Force the game to generate empty chunks in the shipyard.
     *
     * <p>If a chunk already exists do nothing. If it doesn't yet exist, but it's in the shipyard, then pretend that
     * chunk already existed and return a new chunk.
     *
     * @author Tri0de
     */
    @Inject(method = "readChunk", at = @At("HEAD"), cancellable = true)
    private void preReadChunk(final ChunkPos chunkPos,
        final CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> cir) throws IOException {
        final ServerShip ship = VSGameUtilsKt.getShipManagingPos(level, chunkPos.x, chunkPos.z);
        final boolean shouldUseEmptyChunk =
            (ship == null && VSGameUtilsKt.isChunkInShipyard(level, chunkPos.x, chunkPos.z)) ||
                (ship instanceof org.valkyrienskies.core.api.ships.LoadedServerShip loadedShip &&
                    !ShipSettingsKt.getSettings(loadedShip).getShouldGenerateChunks());

        if (!shouldUseEmptyChunk) {
            return;
        }

        cir.setReturnValue(((ChunkStorage) (Object) this).read(chunkPos).thenApply(chunkData ->
            chunkData.map(tag ->
                ((ChunkMap) (Object) this).upgradeChunkTag(this.level.dimension(), this.overworldDataStorage, tag, Optional.empty())
            ).or(() -> Optional.of(createEmptyChunkTag(chunkPos)))
        ));
    }

    private CompoundTag createEmptyChunkTag(final ChunkPos chunkPos) {
        final LevelChunk generatedChunk = new LevelChunk(level,
            new ProtoChunk(chunkPos, UpgradeData.EMPTY, level,
                level.registryAccess().registryOrThrow(Registries.BIOME), null), null);
        return ChunkSerializer.write(level, generatedChunk);
    }

    /**
     * Force the game send chunk update packets to players watching ship chunks.
     *
     * @author Tri0de
     */
    @Inject(method = "getPlayers", at = @At("RETURN"), cancellable = true)
    private void postGetPlayersWatchingChunk(final ChunkPos chunkPos, final boolean onlyOnWatchDistanceEdge,
        final CallbackInfoReturnable<List<ServerPlayer>> cir) {

        final Iterator<VsiPlayer> playersWatchingShipChunk =
            VSGameUtilsKt.getShipObjectWorld(level)
                .getIPlayersWatchingShipChunk(chunkPos.x, chunkPos.z, VSGameUtilsKt.getDimensionId(level));

        if (!playersWatchingShipChunk.hasNext()) {
            // No players watching this ship chunk, so we don't need to modify anything
            return;
        }

        final List<ServerPlayer> oldReturnValue = cir.getReturnValue();
        final Set<ServerPlayer> watchingPlayers = new HashSet<>(oldReturnValue);

        playersWatchingShipChunk.forEachRemaining(
            iPlayer -> {
                final MinecraftPlayer minecraftPlayer = (MinecraftPlayer) iPlayer;
                final ServerPlayer playerEntity =
                    (ServerPlayer) minecraftPlayer.getPlayerEntityReference().get();
                if (playerEntity != null) {
                    watchingPlayers.add(playerEntity);
                }
            }
        );

        cir.setReturnValue(new ArrayList<>(watchingPlayers));
    }

    @WrapOperation(method = "anyPlayerCloseEnoughForSpawning", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap$DistanceManager;hasPlayersNearby(J)Z"))
    private boolean onHasPlayersNearby(
        DistanceManager instance, long l, Operation<Boolean> original, @Local(argsOnly = true) ChunkPos arg) {
        return original.call(instance, new ChunkPos(BlockPos.containing(VSGameUtilsKt.toWorldCoordinates(level, arg.getMiddleBlockPosition(63)))).toLong());
    }

    @WrapOperation(method = "playerIsCloseEnoughForSpawning", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;euclideanDistanceSquared(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/entity/Entity;)D"))
    private double onEuclideanDistanceSquared(ChunkPos d0, Entity d1, Operation<Double> original) {
        return original.call(new ChunkPos(BlockPos.containing(VSGameUtilsKt.toWorldCoordinates(level, d0.getMiddleBlockPosition(63)))), d1);
    }

    /**
     * Only save ship chunks that actually have something in them to avoid massive lag when closing/saving the game
     */
    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void preSave(ChunkAccess chunkAccess, CallbackInfoReturnable<Boolean> cir) {
        final ChunkPos pos = chunkAccess.getPos();
        final Ship ship = VSGameUtilsKt.getShipManagingPos(level, pos);
        if (ship != null) {
            if (!ship.getActiveChunksSet().contains(pos.x, pos.z)) {
                cir.setReturnValue(false);
            }
        }
    }
}
