package org.valkyrienskies.mod.mixin.accessors.server.level;

import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheAccessor {
    @Invoker("clearCache")
    void callClearCache();

    @Invoker("getChunkFutureMainThread")
    CompletableFuture<?> callGetChunkFutureMainThread(int x, int z, ChunkStatus chunkStatus, boolean load);
}
