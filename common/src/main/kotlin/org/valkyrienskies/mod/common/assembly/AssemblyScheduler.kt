package org.valkyrienskies.mod.common.assembly

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.ChunkStatus
import net.minecraft.world.ticks.ScheduledTick
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.mod.common.BlockStateInfo
import org.valkyrienskies.mod.common.config.VSGameConfig
import org.valkyrienskies.mod.mixin.accessors.server.level.ServerChunkCacheAccessor
import org.valkyrienskies.mod.common.networking.PacketRestartChunkUpdates
import org.valkyrienskies.mod.common.networking.PacketStopChunkUpdates
import org.valkyrienskies.mod.common.playerWrapper
import org.valkyrienskies.mod.common.util.SplittingDisablerAttachment
import org.valkyrienskies.mod.common.util.toJOML
import org.valkyrienskies.mod.common.vsCore
import org.valkyrienskies.mod.util.AIR
import org.valkyrienskies.mod.util.logger
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture

data class BlockTransfer(val sourcePos: BlockPos, val destPos: BlockPos)

internal data class AssemblyTransferPlan(
    val level: ServerLevel,
    val transfers: List<BlockTransfer>,
    val fromShip: ServerShip?,
    val toShip: ServerShip?,
    val fromCenter: Vector3d,
    val toCenter: Vector3d,
    val minPos: Vector3d,
    val maxPos: Vector3d,
    val beforeCopyBlockPositions: Set<BlockPos>,
    val oldShipIdToNewShipId: Map<Long, Long>,
    val centerPositionMap: Map<Long, Pair<Vector3d, Vector3d>>,
    val copyCenterMap: Map<Long, Vector3d>,
    val shipsBeingCopied: List<ServerShip>,
    val removeOriginal: Boolean,
    val emitAssemblyEvents: Boolean,
    val wasSplittingEnabled: Boolean,
    val stallChunkUpdates: Boolean,
)

private enum class AssemblyPhase {
    SNAPSHOT,
    WAIT_FOR_CHUNKS,
    PAUSE_CHUNK_UPDATES,
    APPLY_MUTATION,
    REPLAY_BLOCK_UPDATES,
    FINALIZE_SERVER_EFFECTS,
    LIGHTING,
    REFRESH_CHUNKS,
    RESUME_CHUNK_UPDATES,
    COMPLETE,
    DONE
}

private data class SnapshotBlock(
    val sourcePos: BlockPos,
    val destPos: BlockPos,
    val state: net.minecraft.world.level.block.state.BlockState,
    val tag: CompoundTag?,
    val transferScheduledBlockTick: Boolean,
)

internal class TransferAssemblyJob<R>(
    private val plan: AssemblyTransferPlan,
    val future: CompletableFuture<R>,
    private val resultFactory: () -> R,
    private val successCallback: (() -> Unit)? = null,
    private val failureCleanup: (() -> Unit)? = null,
) {
    companion object {
        private val logger by logger("Assembly Scheduler")
        private const val SNAPSHOTS_PER_TICK = 2048
        private const val MUTATIONS_PER_TICK = 1024
        private const val REPLAYS_PER_TICK = 2048
        private const val CHUNK_REFRESHES_PER_TICK = 16
    }

    val level: ServerLevel = plan.level
    private val snapshots = ArrayList<SnapshotBlock>(plan.transfers.size)
    private val changedBlockPositions = LinkedHashSet<BlockPos>(plan.transfers.size * 2)
    private val changedChunks = LinkedHashSet<ChunkPos>()
    private val replayedBlockChanges = ArrayList<CapturedBlockChange>(plan.transfers.size * 2)
    private val deferredServerEffects = BlockStateInfo.DeferredServerEffects()
    private val eventData = mutableMapOf<String, CompoundTag>()
    private val chunkCache = HashMap<Long, LevelChunk>()
    private val requestedChunkLoads = LinkedHashMap<ChunkPos, CompletableFuture<*>>()
    private val forcedDestinationChunks = plan.transfers
        .mapTo(LinkedHashSet()) { ChunkPos(it.destPos) }

    private var phase = AssemblyPhase.SNAPSHOT
    private var snapshotIndex = 0
    private var mutationIndex = 0
    private var replayIndex = 0
    private var lightIndex = 0
    private var refreshIndex = 0
    private var pauseSent = false
    private var releasedForcedChunks = false

    private val changedChunksList: MutableList<ChunkPos> = ArrayList()
    private val changedChunksJoml = ArrayList<org.joml.Vector2i>()
    private val changedPositionsList: MutableList<BlockPos> = ArrayList()

    init {
        AssemblyScheduler.retainForcedChunks(level, forcedDestinationChunks)
        requestChunkLoads(forcedDestinationChunks)
    }

    private fun getChunkNow(pos: BlockPos): LevelChunk? {
        val chunkKey = ChunkPos.asLong(pos.x shr 4, pos.z shr 4)
        return chunkCache[chunkKey] ?: ShipAssembler.getLoadedChunk(plan.level, pos)?.also { chunkCache[chunkKey] = it }
    }

    private fun requestChunkLoads(chunks: Iterable<ChunkPos>) {
        chunks.forEach(::requestChunkLoad)
    }

    private fun requestChunkLoad(chunkPos: ChunkPos) {
        requestedChunkLoads.getOrPut(chunkPos) {
            (plan.level.chunkSource as ServerChunkCacheAccessor)
                .callGetChunkFutureMainThread(chunkPos.x, chunkPos.z, ChunkStatus.FULL, true)
        }
    }

    private fun waitForRequestedChunks() {
        for ((chunkPos, future) in requestedChunkLoads) {
            if (future.isCompletedExceptionally) {
                future.join()
            }
            if (plan.level.chunkSource.getChunkNow(chunkPos.x, chunkPos.z) == null) {
                return
            }
        }
        phase = AssemblyPhase.PAUSE_CHUNK_UPDATES
    }

    fun tick(budgetNanos: Long, lightBudget: Int): Boolean {
        if (phase == AssemblyPhase.DONE) {
            return true
        }

        val startNanos = System.nanoTime()
        return try {
            while (!hasExceededBudget(startNanos, budgetNanos) && phase != AssemblyPhase.DONE) {
                when (phase) {
                    AssemblyPhase.SNAPSHOT -> snapshotStep(startNanos, budgetNanos)
                    AssemblyPhase.WAIT_FOR_CHUNKS -> waitForRequestedChunks()
                    AssemblyPhase.PAUSE_CHUNK_UPDATES -> pauseChunkUpdates()
                    AssemblyPhase.APPLY_MUTATION -> mutationStep(startNanos, budgetNanos)
                    AssemblyPhase.REPLAY_BLOCK_UPDATES -> replayStep(startNanos, budgetNanos)
                    AssemblyPhase.FINALIZE_SERVER_EFFECTS -> finalizeServerEffects()
                    AssemblyPhase.LIGHTING -> lightingStep(startNanos, budgetNanos, lightBudget)
                    AssemblyPhase.REFRESH_CHUNKS -> refreshChunksStep(startNanos, budgetNanos)
                    AssemblyPhase.RESUME_CHUNK_UPDATES -> resumeChunkUpdates()
                    AssemblyPhase.COMPLETE -> completeSuccessfully()
                    AssemblyPhase.DONE -> Unit
                }
            }
            phase == AssemblyPhase.DONE
        } catch (t: Throwable) {
            fail(t)
            true
        }
    }

    private fun snapshotStep(startNanos: Long, budgetNanos: Long) {
        if (snapshotIndex == 0 && plan.emitAssemblyEvents) {
            VSAssemblyEvents.beforeCopy.emit(
                VSAssemblyEvents.BeforeCopy(
                    plan.level,
                    plan.minPos,
                    plan.maxPos,
                    plan.fromCenter,
                    plan.fromShip,
                    plan.beforeCopyBlockPositions,
                    eventData
                )
            )
        }

        var processed = 0
        while (
            snapshotIndex < plan.transfers.size &&
            processed < SNAPSHOTS_PER_TICK &&
            !hasExceededBudget(startNanos, budgetNanos)
        ) {
            val transfer = plan.transfers[snapshotIndex]
            val sourceChunk = getChunkNow(transfer.sourcePos) ?: return
            val state = sourceChunk.getBlockState(transfer.sourcePos)
            val tag = ShipAssembler.copyBlockTag(
                plan.level,
                transfer.sourcePos,
                state,
                sourceChunk.getBlockEntity(transfer.sourcePos),
                plan.shipsBeingCopied,
                plan.copyCenterMap
            )

            snapshots += SnapshotBlock(
                transfer.sourcePos,
                transfer.destPos,
                state,
                tag,
                plan.level.blockTicks.hasScheduledTick(transfer.sourcePos, state.block)
            )
            changedBlockPositions += transfer.sourcePos
            changedBlockPositions += transfer.destPos
            changedChunks += ChunkPos(transfer.sourcePos)
            changedChunks += ChunkPos(transfer.destPos)
            snapshotIndex++
            processed++
        }

        if (snapshotIndex >= plan.transfers.size) {
            changedChunksList += changedChunks
            changedChunksJoml += changedChunksList.map(ChunkPos::toJOML)
            changedPositionsList += changedBlockPositions
            phase = AssemblyPhase.WAIT_FOR_CHUNKS
        }
    }

    private fun pauseChunkUpdates() {
        if (plan.stallChunkUpdates && !pauseSent && changedChunksJoml.isNotEmpty()) {
            plan.level.players().forEach { player ->
                with(vsCore.simplePacketNetworking) {
                    PacketStopChunkUpdates(changedChunksJoml).sendToClient(player.playerWrapper)
                }
            }
            pauseSent = true
        }
        if (plan.emitAssemblyEvents) {
            VSAssemblyEvents.onPasteBeforeBlocksAreLoaded.emit(
                VSAssemblyEvents.OnPasteBeforeBlocksAreLoaded(
                    plan.level,
                    plan.fromShip,
                    plan.toShip,
                    plan.fromCenter to plan.toCenter,
                    eventData
                )
            )
        }
        phase = AssemblyPhase.APPLY_MUTATION
    }

    private fun mutationStep(startNanos: Long, budgetNanos: Long) {
        val session = AssemblyBlockCapture.begin(plan.level)
        try {
            var processed = 0
            while (
                mutationIndex < snapshots.size &&
                processed < MUTATIONS_PER_TICK &&
                !hasExceededBudget(startNanos, budgetNanos)
            ) {
                val snapshot = snapshots[mutationIndex]
                val sourceChunk = if (plan.removeOriginal) getChunkNow(snapshot.sourcePos) else null
                if (plan.removeOriginal && sourceChunk == null) {
                    requestChunkLoad(ChunkPos(snapshot.sourcePos))
                    phase = AssemblyPhase.WAIT_FOR_CHUNKS
                    return
                }
                val destChunk = getChunkNow(snapshot.destPos) ?: run {
                    requestChunkLoad(ChunkPos(snapshot.destPos))
                    phase = AssemblyPhase.WAIT_FOR_CHUNKS
                    return
                }
                if (plan.removeOriginal) {
                    ShipAssembler.clearSourceBlockEntity(plan.level, snapshot.sourcePos, sourceChunk)
                    sourceChunk!!.setBlockState(snapshot.sourcePos, ShipAssembler.airBlockState, false)
                }

                destChunk.removeBlockEntity(snapshot.destPos)
                destChunk.setBlockState(snapshot.destPos, snapshot.state, false)

                val block = snapshot.state.block
                val finalTag = if (block is ICopyableBlock) {
                    block.onPaste(
                        plan.level,
                        snapshot.destPos,
                        snapshot.state,
                        plan.oldShipIdToNewShipId,
                        plan.centerPositionMap,
                        snapshot.tag
                    )
                } else {
                    snapshot.tag
                } ?: snapshot.tag

                if (snapshot.transferScheduledBlockTick) {
                    @Suppress("UNCHECKED_CAST")
                    plan.level.blockTicks.schedule(ScheduledTick(block as Block, snapshot.destPos, 0, 0))
                }

                if (snapshot.state.hasBlockEntity() && finalTag != null) {
                    val blockEntity = ShipAssembler.ensureBlockEntity(plan.level, snapshot.destPos, snapshot.state, destChunk)
                    val retargetedTag = ShipAssembler.retargetBlockEntityTag(finalTag, snapshot.destPos)
                    if (blockEntity != null && retargetedTag != null) {
                        blockEntity.load(retargetedTag)
                        blockEntity.setChanged()
                    }
                }
                mutationIndex++
                processed++
            }
        } finally {
            replayedBlockChanges += AssemblyBlockCapture.finish(session)
        }

        if (mutationIndex >= snapshots.size) {
            phase = AssemblyPhase.REPLAY_BLOCK_UPDATES
        }
    }

    private fun replayStep(startNanos: Long, budgetNanos: Long) {
        var processed = 0
        while (
            replayIndex < replayedBlockChanges.size &&
            processed < REPLAYS_PER_TICK &&
            !hasExceededBudget(startNanos, budgetNanos)
        ) {
            BlockStateInfo.replayCapturedBlockChange(plan.level, replayedBlockChanges[replayIndex++], deferredServerEffects)
            processed++
        }
        if (replayIndex >= replayedBlockChanges.size) {
            phase = AssemblyPhase.FINALIZE_SERVER_EFFECTS
        }
    }

    private fun finalizeServerEffects() {
        BlockStateInfo.finalizeDeferredServerEffects(plan.level, deferredServerEffects)
        phase = AssemblyPhase.LIGHTING
    }

    private fun lightingStep(startNanos: Long, budgetNanos: Long, lightBudget: Int) {
        var refreshedThisTick = 0
        while (
            lightIndex < changedPositionsList.size &&
            refreshedThisTick < lightBudget &&
            !hasExceededBudget(startNanos, budgetNanos)
        ) {
            plan.level.chunkSource.lightEngine.checkBlock(changedPositionsList[lightIndex++])
            refreshedThisTick++
        }
        if (lightIndex >= changedPositionsList.size) {
            phase = AssemblyPhase.REFRESH_CHUNKS
        }
    }

    private fun refreshChunksStep(startNanos: Long, budgetNanos: Long) {
        var refreshed = 0
        while (
            refreshIndex < changedChunksList.size &&
            refreshed < CHUNK_REFRESHES_PER_TICK &&
            !hasExceededBudget(startNanos, budgetNanos)
        ) {
            val chunkPos = changedChunksList[refreshIndex]
            if (!ShipAssembler.sendChunkRefreshPacket(plan.level, chunkPos)) {
                requestChunkLoad(chunkPos)
                phase = AssemblyPhase.WAIT_FOR_CHUNKS
                return
            }
            refreshIndex++
            refreshed++
        }
        if (refreshIndex >= changedChunksList.size) {
            phase = AssemblyPhase.RESUME_CHUNK_UPDATES
        }
    }

    private fun resumeChunkUpdates() {
        if (plan.stallChunkUpdates && pauseSent && changedChunksJoml.isNotEmpty()) {
            plan.level.players().forEach { player ->
                with(vsCore.simplePacketNetworking) {
                    PacketRestartChunkUpdates(changedChunksJoml).sendToClient(player.playerWrapper)
                }
            }
        }
        phase = AssemblyPhase.COMPLETE
    }

    private fun completeSuccessfully() {
        try {
            if (plan.emitAssemblyEvents) {
                VSAssemblyEvents.onPasteAfterBlocksAreLoaded.emit(
                    VSAssemblyEvents.OnPasteAfterBlocksAreLoaded(
                        plan.level,
                        plan.fromShip,
                        plan.toShip,
                        plan.fromCenter to plan.toCenter,
                        eventData
                    )
                )
            }
            restoreSplitState()
            successCallback?.invoke()
            releaseForcedChunks()
            future.complete(resultFactory())
        } catch (t: Throwable) {
            fail(t)
            return
        }
        phase = AssemblyPhase.DONE
    }

    private fun fail(t: Throwable) {
        logger.error("Queued ship assembly failed in phase $phase", t)
        restoreSourceBlocks()
        if (plan.stallChunkUpdates && pauseSent && changedChunksJoml.isNotEmpty()) {
            plan.level.players().forEach { player ->
                with(vsCore.simplePacketNetworking) {
                    PacketRestartChunkUpdates(changedChunksJoml).sendToClient(player.playerWrapper)
                }
            }
        }
        restoreSplitState()
        failureCleanup?.invoke()
        releaseForcedChunks()
        future.completeExceptionally(t)
        phase = AssemblyPhase.DONE
    }

    private fun restoreSourceBlocks() {
        if (mutationIndex <= 0 || snapshots.isEmpty()) {
            return
        }

        val restoredCount = mutationIndex.coerceAtMost(snapshots.size)
        for (index in restoredCount - 1 downTo 0) {
            val snapshot = snapshots[index]
            try {
                restoreSourceSnapshot(snapshot)
            } catch (restoreError: Throwable) {
                logger.error("Failed to restore source block during queued assembly rollback at ${snapshot.sourcePos}", restoreError)
            }
            try {
                clearDestinationSnapshot(snapshot)
            } catch (clearError: Throwable) {
                logger.error("Failed to clear destination block during queued assembly rollback at ${snapshot.destPos}", clearError)
            }
        }
    }

    private fun restoreSourceSnapshot(snapshot: SnapshotBlock) {
        val sourceChunk = getChunkNow(snapshot.sourcePos) ?: return
        sourceChunk.removeBlockEntity(snapshot.sourcePos)
        sourceChunk.setBlockState(snapshot.sourcePos, snapshot.state, false)
        if (snapshot.state.hasBlockEntity() && snapshot.tag != null) {
            val blockEntity = ShipAssembler.ensureBlockEntity(plan.level, snapshot.sourcePos, snapshot.state, sourceChunk)
            val retargetedTag = ShipAssembler.retargetBlockEntityTag(snapshot.tag, snapshot.sourcePos)
            if (blockEntity != null && retargetedTag != null) {
                blockEntity.load(retargetedTag)
                blockEntity.setChanged()
            }
        }
        if (snapshot.transferScheduledBlockTick) {
            @Suppress("UNCHECKED_CAST")
            plan.level.blockTicks.schedule(ScheduledTick(snapshot.state.block as Block, snapshot.sourcePos, 0, 0))
        }
    }

    private fun clearDestinationSnapshot(snapshot: SnapshotBlock) {
        val destChunk = getChunkNow(snapshot.destPos) ?: return
        destChunk.removeBlockEntity(snapshot.destPos)
        destChunk.setBlockState(snapshot.destPos, AIR, false)
    }

    private fun restoreSplitState() {
        if (plan.fromShip is LoadedServerShip && plan.wasSplittingEnabled) {
            plan.fromShip.getAttachment(SplittingDisablerAttachment::class.java)?.enableSplitting()
        }
    }

    private fun releaseForcedChunks() {
        if (releasedForcedChunks) {
            return
        }
        releasedForcedChunks = true
        AssemblyScheduler.releaseForcedChunks(level, forcedDestinationChunks)
    }

    private fun hasExceededBudget(startNanos: Long, budgetNanos: Long): Boolean {
        if (budgetNanos == Long.MAX_VALUE) {
            return false
        }
        return System.nanoTime() - startNanos >= budgetNanos
    }
}

object AssemblyScheduler {
    private val queuedJobs = LinkedHashMap<ServerLevel, ArrayDeque<TransferAssemblyJob<*>>>()
    private val activeJobs = LinkedHashMap<ServerLevel, TransferAssemblyJob<*>>()
    private val forcedChunkRefs = LinkedHashMap<ServerLevel, MutableMap<Long, Int>>()

    internal fun <R> enqueue(job: TransferAssemblyJob<R>): CompletableFuture<R> {
        queuedJobs.getOrPut(job.level) { ArrayDeque() }.add(job)
        return job.future
    }

    internal fun <R> runNow(job: TransferAssemblyJob<R>): R {
        while (!job.tick(Long.MAX_VALUE, Int.MAX_VALUE)) {
            // keep advancing until the job finishes
        }
        return job.future.join()
    }

    internal fun retainForcedChunks(level: ServerLevel, chunks: Set<ChunkPos>) {
        if (chunks.isEmpty()) return
        val refs = forcedChunkRefs.getOrPut(level) { LinkedHashMap() }
        for (chunkPos in chunks) {
            val key = chunkPos.toLong()
            val count = refs[key] ?: 0
            if (count == 0) {
                level.chunkSource.updateChunkForced(chunkPos, true)
            }
            refs[key] = count + 1
        }
    }

    internal fun releaseForcedChunks(level: ServerLevel, chunks: Set<ChunkPos>) {
        val refs = forcedChunkRefs[level] ?: return
        for (chunkPos in chunks) {
            val key = chunkPos.toLong()
            val count = refs[key] ?: continue
            if (count <= 1) {
                refs.remove(key)
                level.chunkSource.updateChunkForced(chunkPos, false)
            } else {
                refs[key] = count - 1
            }
        }
        if (refs.isEmpty()) {
            forcedChunkRefs.remove(level)
        }
    }

    fun tickServer(server: MinecraftServer) {
        val budgetNanos = VSGameConfig.SERVER.assemblyWorkBudgetMicros.toLong().coerceAtLeast(1L) * 1000L
        val lightBudget = VSGameConfig.SERVER.assemblyLightBudget.coerceAtLeast(1)

        server.allLevels.forEach { level ->
            val active = activeJobs[level] ?: queuedJobs[level]?.let { queue ->
                if (queue.isEmpty()) {
                    null
                } else {
                    queue.removeFirst().also { activeJobs[level] = it }
                }
            } ?: return@forEach
            if (active.tick(budgetNanos, lightBudget)) {
                activeJobs.remove(level)
                if (queuedJobs[level].isNullOrEmpty()) {
                    queuedJobs.remove(level)
                }
            }
        }
    }

    fun clear() {
        queuedJobs.clear()
        activeJobs.clear()
        forcedChunkRefs.forEach { (level, refs) ->
            refs.keys.forEach { chunkKey ->
                level.chunkSource.updateChunkForced(ChunkPos(chunkKey), false)
            }
        }
        forcedChunkRefs.clear()
    }
}
