package org.valkyrienskies.mod.common.assembly

import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Clearable
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import org.joml.Quaterniond
import org.joml.RoundingMode
import org.joml.Vector3d
import org.joml.Vector3i
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.core.api.ships.properties.ShipId
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.core.internal.ships.VsiServerShip
import org.valkyrienskies.core.internal.world.chunks.VsiBlockType
import org.valkyrienskies.mod.common.BlockStateInfo
import org.valkyrienskies.mod.common.config.VSGameConfig
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.forEach
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.inAssemblyBlacklist
import org.valkyrienskies.mod.common.isTickingChunk
import org.valkyrienskies.mod.common.networking.PacketRestartChunkUpdates
import org.valkyrienskies.mod.common.networking.PacketStopChunkUpdates
import org.valkyrienskies.mod.common.playerWrapper
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.SplittingDisablerAttachment
import org.valkyrienskies.mod.common.util.toJOML
import org.valkyrienskies.mod.common.util.toJOMLD
import org.valkyrienskies.mod.common.vsCore
import org.valkyrienskies.mod.common.yRange
import org.valkyrienskies.mod.util.AIR
import org.valkyrienskies.mod.util.logger
import java.util.concurrent.CompletableFuture

object ShipAssembler {
    private const val QUEUED_ASSEMBLY_PLACEHOLDER_MASS = 1.0e-4

    val ASSEMBLY_LOGGER = logger("Sandwich Factory").logger
    internal val airBlockState: BlockState
        get() = AIR

    class SingleItemMap<K, V>(val mkey: K, val mvalue: V, val default: V, val defaultFn: ((K) -> V)? = null): Map<K, V> {
        override val size: Int = 1
        override val keys: Set<K> = setOf(mkey)

        override val values: Collection<V> = setOf(mvalue)
        override val entries: Set<Map.Entry<K, V>> = setOf(object : Map.Entry<K, V> {
            override val key = mkey
            override val value = mvalue
        })

        override fun isEmpty(): Boolean = false
        override fun containsKey(key: K): Boolean = true
        override fun containsValue(value: V): Boolean = true
        override fun get(key: K): V? = if (key == this.mkey) mvalue else defaultFn?.invoke(key) ?: default
    }

    @JvmStatic
    fun findMinAndMax(blocks: Iterable<BlockPos>): Pair<BlockPos, BlockPos> {
        val minCorner = BlockPos.MutableBlockPos(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
        val maxCorner = BlockPos.MutableBlockPos(Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)

        for (pos in blocks) {
            minCorner.x = Math.min(minCorner.x, pos.x)
            minCorner.y = Math.min(minCorner.y, pos.y)
            minCorner.z = Math.min(minCorner.z, pos.z)

            maxCorner.x = Math.max(maxCorner.x, pos.x)
            maxCorner.y = Math.max(maxCorner.y, pos.y)
            maxCorner.z = Math.max(maxCorner.z, pos.z)
        }

        return minCorner to maxCorner
    }

    @JvmStatic
    private fun getDistinctChunksFromBlockPosSet(blocks: Set<BlockPos>): Set<ChunkPos> {
        val chunkSet = hashSetOf<ChunkPos>()
        for (blockPos in blocks) {
            val chunkPos = ChunkPos(blockPos)
            chunkSet.add(chunkPos)
        }
        return chunkSet
    }

    private data class AssemblyBlockSnapshot(
        val sourcePos: BlockPos,
        val destPos: BlockPos,
        val state: BlockState,
        val tag: CompoundTag?
    )

    private data class AssemblySnapshotResult(
        val blocks: List<AssemblyBlockSnapshot>,
        val changedBlockPositions: Set<BlockPos>,
        val changedChunks: Set<ChunkPos>,
        val changedSections: Set<Vector3i>
    )

    private fun phaseStart(): Long = System.nanoTime()

    private fun logPhase(name: String, startNanos: Long) {
        ASSEMBLY_LOGGER.debug("{} took {} ms", name, (System.nanoTime() - startNanos) / 1_000_000.0)
    }

    internal fun copyBlockTag(
        level: ServerLevel,
        pos: BlockPos,
        state: BlockState,
        blockEntity: BlockEntity?,
        shipsBeingCopied: List<ServerShip>,
        centerPositions: Map<Long, Vector3d>
    ): CompoundTag? {
        val customTag = if (state.block is ICopyableBlock) {
            (state.block as ICopyableBlock).onCopy(level, pos, state, blockEntity, shipsBeingCopied, centerPositions)
        } else {
            null
        }
        return customTag ?: blockEntity?.saveWithId()
    }

    internal fun retargetBlockEntityTag(tag: CompoundTag?, pos: BlockPos): CompoundTag? {
        return tag?.copy()?.also {
            it.putInt("x", pos.x)
            it.putInt("y", pos.y)
            it.putInt("z", pos.z)
        }
    }

    internal fun getLoadedChunk(level: ServerLevel, pos: BlockPos): LevelChunk? =
        level.chunkSource.getChunkNow(pos.x shr 4, pos.z shr 4)

    @Suppress("NULL_FOR_NONNULL_TYPE")
    internal fun clearSourceBlockEntity(level: ServerLevel, pos: BlockPos, chunk: LevelChunk? = getLoadedChunk(level, pos)) {
        chunk?.getBlockEntity(pos)?.let {
            if (it is Clearable) {
                Clearable.tryClear(it)
            } else {
                it.load(CompoundTag())
            }
            if (it is RandomizableContainerBlockEntity) {
                it.setLootTable(null, 0)
            }
            chunk.removeBlockEntity(pos)
        }
    }

    internal fun ensureBlockEntity(
        level: ServerLevel,
        pos: BlockPos,
        state: BlockState,
        chunk: LevelChunk? = getLoadedChunk(level, pos)
    ): BlockEntity? {
        val targetChunk = chunk ?: return null
        val existing = targetChunk.getBlockEntity(pos)
        if (existing != null) {
            return existing
        }
        val block = state.block
        if (block is EntityBlock) {
            val created = block.newBlockEntity(pos, state)
            if (created != null) {
                targetChunk.setBlockEntity(created)
                return created
            }
        }
        return null
    }

    private fun buildAssemblySnapshot(
        level: ServerLevel,
        blocks: Set<BlockPos>,
        minStructurePos: BlockPos,
        cornerOfShip: BlockPos,
        shipsBeingCopied: List<ServerShip>,
        centerPositions: Map<Long, Vector3d>
    ): AssemblySnapshotResult {
        val snapshots = ArrayList<AssemblyBlockSnapshot>(blocks.size)
        val changedPositions = LinkedHashSet<BlockPos>(blocks.size * 2)
        val changedChunks = LinkedHashSet<ChunkPos>()
        val changedSections = LinkedHashSet<Vector3i>()

        for (sourcePos in blocks) {
            val relativePos = sourcePos.subtract(minStructurePos)
            val destPos = cornerOfShip.offset(relativePos)
            val sourceChunk = getLoadedChunk(level, sourcePos) ?: level.getChunk(sourcePos.x shr 4, sourcePos.z shr 4)
            val state = sourceChunk.getBlockState(sourcePos)
            val tag = copyBlockTag(level, sourcePos, state, sourceChunk.getBlockEntity(sourcePos), shipsBeingCopied, centerPositions)
            snapshots += AssemblyBlockSnapshot(sourcePos, destPos, state, tag)

            changedPositions += sourcePos
            changedPositions += destPos
            changedChunks += ChunkPos(sourcePos)
            changedChunks += ChunkPos(destPos)

            changedSections += Vector3i(
                SectionPos.blockToSectionCoord(sourcePos.x),
                SectionPos.blockToSectionCoord(sourcePos.y),
                SectionPos.blockToSectionCoord(sourcePos.z)
            )
            changedSections += Vector3i(
                SectionPos.blockToSectionCoord(destPos.x),
                SectionPos.blockToSectionCoord(destPos.y),
                SectionPos.blockToSectionCoord(destPos.z)
            )
        }

        return AssemblySnapshotResult(snapshots, changedPositions, changedChunks, changedSections)
    }

    private fun applyAssemblySnapshot(
        level: ServerLevel,
        snapshot: AssemblySnapshotResult,
        oldShipIdToNewShipId: Map<Long, Long>,
        centerPositions: Map<Long, Pair<Vector3d, Vector3d>>,
        removeOriginal: Boolean
    ) {
        val chunkCache = HashMap<Long, LevelChunk>()
        fun getChunk(pos: BlockPos) =
            chunkCache.getOrPut(ChunkPos.asLong(pos.x shr 4, pos.z shr 4)) { level.getChunk(pos.x shr 4, pos.z shr 4) }

        if (removeOriginal) {
            for (entry in snapshot.blocks) {
                val sourceChunk = getChunk(entry.sourcePos)
                clearSourceBlockEntity(level, entry.sourcePos, sourceChunk)
                sourceChunk.setBlockState(entry.sourcePos, AIR, false)
            }
        }

        for (entry in snapshot.blocks) {
            val destChunk = getChunk(entry.destPos)
            destChunk.removeBlockEntity(entry.destPos)
            destChunk.setBlockState(entry.destPos, entry.state, false)

            val block = entry.state.block
            val finalTag = if (block is ICopyableBlock) {
                block.onPaste(level, entry.destPos, entry.state, oldShipIdToNewShipId, centerPositions, entry.tag)
            } else {
                entry.tag
            } ?: entry.tag

            if (entry.state.hasBlockEntity() && finalTag != null) {
                val blockEntity = ensureBlockEntity(level, entry.destPos, entry.state, destChunk)
                val retargetedTag = retargetBlockEntityTag(finalTag, entry.destPos)
                if (blockEntity != null && retargetedTag != null) {
                    blockEntity.load(retargetedTag)
                    blockEntity.setChanged()
                }
            }
        }
    }

    internal fun sendChunkRefreshPacket(level: ServerLevel, chunkPos: ChunkPos): Boolean {
        val chunkMap = level.chunkSource.chunkMap
        val lightEngine = level.chunkSource.lightEngine
        val players = chunkMap.getPlayers(chunkPos, false)
        if (players.isEmpty()) return true

        val chunk = level.chunkSource.getChunkNow(chunkPos.x, chunkPos.z) ?: return false
        val packet = ClientboundLevelChunkWithLightPacket(chunk, lightEngine, null, null)
        players.forEach { player -> player.connection.send(packet) }
        return true
    }

    data class AssembleContext(val ship: ServerShip, val fromCenter: Vector3d, val toCenter: Vector3d)

    private fun disableSplittingIfNeeded(fromShip: ServerShip?): Boolean {
        if (fromShip !is LoadedServerShip) {
            return true
        }
        val splittingDisabler = fromShip.getAttachment(SplittingDisablerAttachment::class.java)
        val wasSplittingEnabled = splittingDisabler?.canSplit() != false
        splittingDisabler?.disableSplitting()
        return wasSplittingEnabled
    }

    private fun <T> failedFuture(throwable: Throwable): CompletableFuture<T> =
        CompletableFuture<T>().also { it.completeExceptionally(throwable) }

    private fun requireAirBlockType(): VsiBlockType =
        BlockStateInfo.get(airBlockState)?.second
            ?: error("Failed to resolve Valkyrien Skies air block type for queued assembly")

    private fun applyQueuedAssemblyPlaceholderMass(level: ServerLevel, placeholderPos: Vector3i) {
        val airType = requireAirBlockType()
        level.shipObjectWorld.onSetBlock(
            placeholderPos.x(),
            placeholderPos.y(),
            placeholderPos.z(),
            level.dimensionId,
            airType,
            airType,
            0.0,
            QUEUED_ASSEMBLY_PLACEHOLDER_MASS
        )
    }

    private fun clearQueuedAssemblyPlaceholderMass(level: ServerLevel, placeholderPos: Vector3i) {
        val airType = requireAirBlockType()
        level.shipObjectWorld.onSetBlock(
            placeholderPos.x(),
            placeholderPos.y(),
            placeholderPos.z(),
            level.dimensionId,
            airType,
            airType,
            QUEUED_ASSEMBLY_PLACEHOLDER_MASS,
            0.0
        )
    }

    private fun createAssembleJob(
        level: ServerLevel,
        blocks: Set<BlockPos>,
        scale: Double,
        stallChunkUpdates: Boolean = false
    ): TransferAssemblyJob<AssembleContext> {
        if (blocks.isEmpty()) {
            val error = RuntimeException("Assembly function received an empty set of blocks")
            ASSEMBLY_LOGGER.error(error)
            throw error
        }

        val filteredBlocks = blocks.filter { level.getBlockState(it).let { state -> !state.isAir && !state.inAssemblyBlacklist() } }.toSet()
        if (filteredBlocks.isEmpty()) {
            val error = RuntimeException("Assembly function received no valid blocks")
            ASSEMBLY_LOGGER.error(error)
            throw error
        }

        val (minB, maxB) = findMinAndMax(filteredBlocks)
        val oldMin = minB.toJOMLD()
        val oldMax = maxB.toJOMLD()
        val offset = oldMax.get(Vector3d())
            .sub(oldMin)
            .add(1.0, 1.0, 1.0)
            .div(2.0)
        val fromCenter = offset.get(Vector3d()).add(oldMin)

        val fromShip = level.getLoadedShipManagingPos(fromCenter) ?: level.getShipManagingPos(fromCenter)
        val oldScale = fromShip?.transform?.scaling?.x() ?: 1.0
        val worldOldCenter = fromShip?.shipToWorld?.transformPosition(fromCenter.get(Vector3d())) ?: fromCenter.get(Vector3d())

        val toShip = level.shipObjectWorld.createNewShipAtBlock(
            Vector3i(worldOldCenter, RoundingMode.FLOOR),
            false,
            scale * oldScale,
            level.dimensionId
        )
        toShip.isStatic = fromShip == null || fromShip.isStatic

        val targetClaimCenter = toShip.chunkClaim.getCenterBlockCoordinates(level.yRange, Vector3i())
        applyQueuedAssemblyPlaceholderMass(level, targetClaimCenter)
        val cornerOfShip = Vector3d(targetClaimCenter)
            .sub(offset)
            .ceil()
            .let { BlockPos(it.x.toInt(), it.y.toInt(), it.z.toInt()) }
        val centerOfShip = cornerOfShip.toJOMLD().add(offset)
        val fromId = fromShip?.id ?: -1L
        val wasSplittingEnabled = disableSplittingIfNeeded(fromShip)

        val transfers = filteredBlocks.map { sourcePos ->
            val relativePos = sourcePos.subtract(minB)
            BlockTransfer(sourcePos, cornerOfShip.offset(relativePos))
        }

        val plan = AssemblyTransferPlan(
            level = level,
            transfers = transfers,
            fromShip = fromShip,
            toShip = toShip,
            fromCenter = fromCenter,
            toCenter = centerOfShip,
            minPos = oldMin,
            maxPos = oldMax,
            beforeCopyBlockPositions = filteredBlocks,
            oldShipIdToNewShipId = SingleItemMap(fromId, toShip.id, -1L) { it },
            centerPositionMap = SingleItemMap(
                fromId,
                fromCenter to Vector3d(centerOfShip),
                Vector3d() to Vector3d()
            ),
            copyCenterMap = SingleItemMap(fromId, fromCenter, Vector3d()),
            shipsBeingCopied = fromShip?.let { listOf(it) } ?: emptyList(),
            removeOriginal = true,
            emitAssemblyEvents = true,
            wasSplittingEnabled = wasSplittingEnabled,
            stallChunkUpdates = stallChunkUpdates,
        )

        val future = CompletableFuture<AssembleContext>()
        val successCallback = {
            clearQueuedAssemblyPlaceholderMass(level, targetClaimCenter)
            val posOffset = Vector3d(toShip.inertiaData.centerOfMass)
                .sub(Vector3d(centerOfShip))
                .let { fromShip?.shipToWorld?.transformDirection(it) ?: it }

            (toShip as VsiServerShip).unsafeSetKinematics(
                vsCore.newBodyKinematics(
                    fromShip?.velocity ?: Vector3d(),
                    fromShip?.angularVelocity ?: Vector3d(),
                    vsCore.newBodyTransform(
                        (fromShip?.shipToWorld?.transformPosition(Vector3d(fromCenter)) ?: fromCenter).add(posOffset),
                        fromShip?.transform?.shipToWorldRotation ?: Quaterniond(),
                        Vector3d(scale * oldScale, scale * oldScale, scale * oldScale),
                        centerOfShip
                    )
                )
            )
            toShip.isStatic = false
        }
        val failureCleanup = {
            level.shipObjectWorld.deleteShip(toShip)
        }

        return TransferAssemblyJob(plan, future, { AssembleContext(toShip, fromCenter, centerOfShip) }, successCallback, failureCleanup)
    }

    private fun createMappedTransferJob(
        level: ServerLevel,
        transfers: Collection<BlockTransfer>,
        fromShip: ServerShip?,
        toShip: ServerShip?,
        fromCenter: Vector3d,
        toCenter: Vector3d,
        minStructurePos: BlockPos,
        maxStructurePos: BlockPos,
        removeOriginal: Boolean,
        emitAssemblyEvents: Boolean,
        stallChunkUpdates: Boolean
    ): TransferAssemblyJob<MoveContext> {
        val filteredTransfers = transfers.filter { transfer ->
            level.getBlockState(transfer.sourcePos).let { state -> !state.isAir && !state.inAssemblyBlacklist() }
        }

        val fromId = fromShip?.id ?: -1L
        val wasSplittingEnabled = disableSplittingIfNeeded(fromShip)
        val plan = AssemblyTransferPlan(
            level = level,
            transfers = filteredTransfers,
            fromShip = fromShip,
            toShip = toShip,
            fromCenter = Vector3d(fromCenter),
            toCenter = Vector3d(toCenter),
            minPos = minStructurePos.toJOMLD(),
            maxPos = maxStructurePos.toJOMLD(),
            beforeCopyBlockPositions = filteredTransfers.mapTo(LinkedHashSet(filteredTransfers.size)) { it.sourcePos },
            oldShipIdToNewShipId = SingleItemMap(fromId, toShip?.id ?: -1L, -1L) { it },
            centerPositionMap = SingleItemMap(
                fromId,
                Vector3d(fromCenter) to Vector3d(toCenter),
                Vector3d() to Vector3d()
            ),
            copyCenterMap = SingleItemMap(fromId, Vector3d(fromCenter), Vector3d()),
            shipsBeingCopied = buildList {
                if (fromShip != null) add(fromShip)
                if (toShip != null && toShip.id != fromShip?.id) add(toShip)
            },
            removeOriginal = removeOriginal,
            emitAssemblyEvents = emitAssemblyEvents,
            wasSplittingEnabled = wasSplittingEnabled,
            stallChunkUpdates = stallChunkUpdates,
        )

        return TransferAssemblyJob(
            plan,
            CompletableFuture(),
            { MoveContext(true, Vector3d(fromCenter), Vector3d(toCenter)) }
        )
    }

    @JvmStatic
    fun queueTransferBlocks(
        level: ServerLevel,
        transfers: Collection<BlockTransfer>,
        fromShip: ServerShip?,
        toShip: ServerShip?,
        fromCenter: Vector3d,
        toCenter: Vector3d,
        minStructurePos: BlockPos,
        maxStructurePos: BlockPos,
        removeOriginal: Boolean = true,
        emitAssemblyEvents: Boolean = true
    ): CompletableFuture<MoveContext> {
        val filteredTransfers = transfers.filter { transfer ->
            level.getBlockState(transfer.sourcePos).let { state -> !state.isAir && !state.inAssemblyBlacklist() }
        }
        if (filteredTransfers.isEmpty()) {
            return CompletableFuture.completedFuture(failedMove)
        }
        return AssemblyScheduler.enqueue(
            createMappedTransferJob(
                level,
                filteredTransfers,
                fromShip,
                toShip,
                fromCenter,
                toCenter,
                minStructurePos,
                maxStructurePos,
                removeOriginal,
                emitAssemblyEvents,
                false
            )
        )
    }

    @JvmStatic
    fun transferBlocksNow(
        level: ServerLevel,
        transfers: Collection<BlockTransfer>,
        fromShip: ServerShip?,
        toShip: ServerShip?,
        fromCenter: Vector3d,
        toCenter: Vector3d,
        minStructurePos: BlockPos,
        maxStructurePos: BlockPos,
        removeOriginal: Boolean = true,
        emitAssemblyEvents: Boolean = true
    ): MoveContext {
        val filteredTransfers = transfers.filter { transfer ->
            level.getBlockState(transfer.sourcePos).let { state -> !state.isAir && !state.inAssemblyBlacklist() }
        }
        if (filteredTransfers.isEmpty()) {
            return failedMove
        }
        return AssemblyScheduler.runNow(
            createMappedTransferJob(
                level,
                filteredTransfers,
                fromShip,
                toShip,
                fromCenter,
                toCenter,
                minStructurePos,
                maxStructurePos,
                removeOriginal,
                emitAssemblyEvents,
                true
            )
        )
    }

    @JvmStatic
    fun queueAssembleToShipFull(level: ServerLevel, blocks: Set<BlockPos>, scale: Double = 1.0): CompletableFuture<AssembleContext> {
        return try {
            AssemblyScheduler.enqueue(createAssembleJob(level, blocks, scale))
        } catch (t: Throwable) {
            failedFuture(t)
        }
    }

    @JvmStatic
    fun queueAssembleToShip(level: ServerLevel, blocks: Set<BlockPos>, scale: Double = 1.0): CompletableFuture<ServerShip> {
        return queueAssembleToShipFull(level, blocks, scale).thenApply { it.ship }
    }

    @JvmStatic
    @OptIn(GameTickOnly::class)
    fun assembleToShipFull(level: ServerLevel, blocks: Set<BlockPos>, scale: Double = 1.0): AssembleContext {
        return AssemblyScheduler.runNow(createAssembleJob(level, blocks, scale, true))
    }

    data class MoveContext(val wasSuccessful: Boolean, val fromCenter: Vector3d, val toCenter: Vector3d)
    private val failedMove = MoveContext(false, Vector3d(), Vector3d())

    @JvmStatic
    @OptIn(GameTickOnly::class)
    fun moveBlocksFromTo(
        level: ServerLevel,
        blocks: Set<BlockPos>,
        fromShip: ServerShip?, toShip: ServerShip?,
        minStructurePos: BlockPos, maxStructurePos: BlockPos,
        toCenter: Vector3i,
        removeOriginal: Boolean = true)
    : MoveContext {
        val oldMin = minStructurePos.toJOMLD()
        val oldMax = maxStructurePos.toJOMLD()
        val offset = oldMax.get(Vector3d())
            .sub(oldMin)
            .add(1.0, 1.0, 1.0)
            .div(2.0)
        val fromCenter = offset.get(Vector3d()).add(oldMin)
        val cornerOfShip = Vector3d(toCenter)
            .sub(offset)
            .ceil()
            .let {
                BlockPos(
                    it.x.toInt(),
                    it.y.toInt(),
                    it.z.toInt(),
                )
            }
        val centerOfShip = cornerOfShip.toJOMLD().add(offset)
        val transfers = blocks.map { sourcePos ->
            val relativePos = sourcePos.subtract(minStructurePos)
            BlockTransfer(sourcePos, cornerOfShip.offset(relativePos))
        }
        return transferBlocksNow(
            level,
            transfers,
            fromShip,
            toShip,
            fromCenter,
            centerOfShip,
            minStructurePos,
            maxStructurePos,
            removeOriginal,
            true
        )
    }

    @JvmStatic
    @OptIn(GameTickOnly::class)
    fun assembleToShip(level: ServerLevel, blocks: Set<BlockPos>, scale: Double = 1.0): ServerShip {
        return assembleToShipFull(level, blocks, scale).ship
    }
    //legacy method to not break shit
    @Deprecated("Old")
    fun assembleToShip(level: Level, blocks: List<BlockPos>, removeOriginal: Boolean, scale: Double = 1.0, shouldDisableSplitting: Boolean = false): ServerShip {
        return assembleToShip(level as ServerLevel, blocks.toSet(), scale)
    }

    @Suppress("unused")
    fun isValidShipBlock(state: BlockState?) : Boolean {
        if (state == null) return false
        if (state.isAir) return false
        val block: Block = state.block
        //assembly blacklist check
        return !state.inAssemblyBlacklist()
    }

    fun deleteShip(level: ServerLevel, ship: ServerShip, deleteBlocks: Boolean, dropBlocks: Boolean): Int {
        if (ship is LoadedServerShip) {
            val splittingDisabler = ship.getAttachment(SplittingDisablerAttachment::class.java)
            splittingDisabler?.disableSplitting()
        }
        if (deleteBlocks) {
            val aabb = ship.shipAABB ?: return 0
            aabb.forEach { x, y, z ->
                if (dropBlocks)
                    level.destroyBlock(BlockPos(x, y, z), true)
                else
                    // Not sure if 2 is what we want, but it's what /fill uses
                    level.setBlock(BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2)
            }
        }

        vsCore.deleteShips(level.shipObjectWorld, listOf<ServerShip>(ship))
        return 1
    }

    class ICopyableProcessor(
        val oldShipIdToNewShipId: Map<ShipId, ShipId>,
        val centerPositions: Map<Long, Pair<Vector3d, Vector3d>>
    ): StructureProcessor() {
        override fun processBlock(
            levelReader: LevelReader, oldBPos: BlockPos, newBPos: BlockPos,
            oldStructureBlockInfo: StructureTemplate.StructureBlockInfo,
            newStructureBlockInfo: StructureTemplate.StructureBlockInfo, structurePlaceSettings: StructurePlaceSettings
        ): StructureTemplate.StructureBlockInfo? {
            val block = newStructureBlockInfo.state.block
            if (block !is ICopyableBlock) return newStructureBlockInfo
            block.onPaste((levelReader as ServerLevelAccessor).level, newBPos, newStructureBlockInfo.state, oldShipIdToNewShipId, centerPositions, newStructureBlockInfo.nbt)
            return newStructureBlockInfo
        }

        // getType is used for referencing this processor from a datapack, which we don't need
        override fun getType(): StructureProcessorType<*>? = null
    }
}
