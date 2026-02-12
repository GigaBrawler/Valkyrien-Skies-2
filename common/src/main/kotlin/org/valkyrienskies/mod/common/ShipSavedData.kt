package org.valkyrienskies.mod.common

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.world.level.saveddata.SavedData
import org.valkyrienskies.core.internal.world.VsiPipeline
import java.util.concurrent.ConcurrentHashMap

/**
 * This class saves/loads ship data for a world.
 *
 * This is only a temporary solution, and should be replaced eventually because it is very inefficient.
 */
class ShipSavedData : SavedData() {

    companion object {
        const val SAVED_DATA_ID = "vs_ship_data"
        private const val QUERYABLE_SHIP_DATA_NBT_KEY = "queryable_ship_data"
        private const val CHUNK_ALLOCATOR_NBT_KEY = "chunk_allocator"
        private const val PIPELINE_NBT_KEY = "vs_pipeline"
        private const val STABILIZATION_PENDING_DYNAMIC_RESTORE_SHIP_IDS_NBT_KEY =
            "stabilization_pending_dynamic_restore_ship_ids"
        private const val PERSISTENT_JOINT_REGISTRY_NBT_KEY = "persistent_joint_registry_v1"
        private const val PERSISTENT_JOINT_KEY_NBT_KEY = "persistentKey"
        private const val PERSISTENT_JOINT_TYPE_NBT_KEY = "jointType"
        private const val PERSISTENT_JOINT_PAYLOAD_NBT_KEY = "jointPayload"
        private const val PERSISTENT_JOINT_LAST_RUNTIME_ID_NBT_KEY = "lastKnownRuntimeId"
        private const val PERSISTENT_JOINT_OWNER_TYPE_NBT_KEY = "ownerType"
        private const val PERSISTENT_JOINT_OWNER_REF_NBT_KEY = "ownerRef"
        private const val PERSISTENT_JOINT_DIMENSION_ID_NBT_KEY = "dimensionId"
        private const val PERSISTENT_JOINT_STATE_NBT_KEY = "state"

        fun createEmpty(): ShipSavedData {
            return ShipSavedData().apply { pipeline = vsCore.newPipeline() }
        }

        @JvmStatic
        fun load(compoundTag: CompoundTag): ShipSavedData {
            val data = ShipSavedData()

            // Read bytes from the [CompoundTag]
            val queryableShipDataAsBytes = compoundTag.getByteArray(QUERYABLE_SHIP_DATA_NBT_KEY)
            val chunkAllocatorAsBytes = compoundTag.getByteArray(CHUNK_ALLOCATOR_NBT_KEY)
            val pipelineAsBytes = compoundTag.getByteArray(PIPELINE_NBT_KEY)

            try {
                if (pipelineAsBytes.isNotEmpty()) {
                    data.pipeline = vsCore.newPipeline(pipelineAsBytes)
                } else if (queryableShipDataAsBytes.isNotEmpty() && chunkAllocatorAsBytes.isNotEmpty()) {
                    data.pipeline = vsCore.newPipelineLegacyData(queryableShipDataAsBytes, chunkAllocatorAsBytes)
                } else {
                    throw IllegalStateException("Couldn't find serialized ship data")
                }
            } catch (ex: Exception) {
                data.loadingException = ex
            }

            data.shipsPendingDynamicRestoreShipIds +=
                compoundTag.getLongArray(STABILIZATION_PENDING_DYNAMIC_RESTORE_SHIP_IDS_NBT_KEY).toList()

            val persistentJointList = compoundTag.getList(
                PERSISTENT_JOINT_REGISTRY_NBT_KEY,
                Tag.TAG_COMPOUND.toInt()
            )
            for (index in 0 until persistentJointList.size) {
                val entry = persistentJointList.getCompound(index)
                val persistentKey = entry.getString(PERSISTENT_JOINT_KEY_NBT_KEY)
                val jointType = entry.getString(PERSISTENT_JOINT_TYPE_NBT_KEY)
                val jointPayload = entry.getByteArray(PERSISTENT_JOINT_PAYLOAD_NBT_KEY)
                if (persistentKey.isEmpty() || jointType.isEmpty() || jointPayload.isEmpty()) {
                    continue
                }

                val runtimeId = if (entry.contains(PERSISTENT_JOINT_LAST_RUNTIME_ID_NBT_KEY, Tag.TAG_INT.toInt())) {
                    entry.getInt(PERSISTENT_JOINT_LAST_RUNTIME_ID_NBT_KEY)
                } else {
                    null
                }
                val ownerType = if (entry.contains(PERSISTENT_JOINT_OWNER_TYPE_NBT_KEY, Tag.TAG_STRING.toInt())) {
                    entry.getString(PERSISTENT_JOINT_OWNER_TYPE_NBT_KEY).takeIf { it.isNotEmpty() }
                } else {
                    null
                }
                val ownerRef = if (entry.contains(PERSISTENT_JOINT_OWNER_REF_NBT_KEY, Tag.TAG_STRING.toInt())) {
                    entry.getString(PERSISTENT_JOINT_OWNER_REF_NBT_KEY).takeIf { it.isNotEmpty() }
                } else {
                    null
                }
                val dimensionId = if (entry.contains(PERSISTENT_JOINT_DIMENSION_ID_NBT_KEY, Tag.TAG_STRING.toInt())) {
                    entry.getString(PERSISTENT_JOINT_DIMENSION_ID_NBT_KEY).takeIf { it.isNotEmpty() }
                } else {
                    null
                }
                val state = if (entry.contains(PERSISTENT_JOINT_STATE_NBT_KEY, Tag.TAG_STRING.toInt())) {
                    PersistentJointState.values().firstOrNull {
                        it.name == entry.getString(PERSISTENT_JOINT_STATE_NBT_KEY)
                    } ?: PersistentJointState.ACTIVE
                } else {
                    PersistentJointState.ACTIVE
                }

                data.upsertPersistentJoint(
                    PersistentJointRecord(
                        persistentKey = persistentKey,
                        jointType = jointType,
                        jointPayload = jointPayload,
                        lastKnownRuntimeId = runtimeId,
                        ownerType = ownerType,
                        ownerRef = ownerRef,
                        dimensionId = dimensionId,
                        state = state
                    )
                )
            }

            return data
        }
    }

    enum class PersistentJointState {
        ACTIVE,
        TOMBSTONE
    }

    data class PersistentJointRecord(
        val persistentKey: String,
        val jointType: String,
        val jointPayload: ByteArray,
        val lastKnownRuntimeId: Int?,
        val ownerType: String?,
        val ownerRef: String?,
        val dimensionId: String?,
        val state: PersistentJointState
    )

    lateinit var pipeline: VsiPipeline

    private val shipsPendingDynamicRestoreShipIds: MutableSet<Long> = mutableSetOf()
    private val persistentJointRegistry: MutableMap<String, PersistentJointRecord> = ConcurrentHashMap()

    var loadingException: Throwable? = null
        private set

    fun markPendingDynamicRestore(shipId: Long, pending: Boolean) {
        if (pending) {
            shipsPendingDynamicRestoreShipIds.add(shipId)
        } else {
            shipsPendingDynamicRestoreShipIds.remove(shipId)
        }
    }

    fun isPendingDynamicRestore(shipId: Long): Boolean {
        return shipsPendingDynamicRestoreShipIds.contains(shipId)
    }

    fun getPendingDynamicRestoreShipIds(): Set<Long> {
        return shipsPendingDynamicRestoreShipIds
    }

    @Synchronized
    fun upsertPersistentJoint(record: PersistentJointRecord) {
        persistentJointRegistry[record.persistentKey] = clonePersistentJointRecord(record)
    }

    @Synchronized
    fun getPersistentJoint(persistentKey: String): PersistentJointRecord? {
        return persistentJointRegistry[persistentKey]?.let(::clonePersistentJointRecord)
    }

    @Synchronized
    fun getAllPersistentJoints(): List<PersistentJointRecord> {
        return persistentJointRegistry.values.map(::clonePersistentJointRecord)
    }

    @Synchronized
    fun getPersistentJointsForDimension(dimensionId: String): List<PersistentJointRecord> {
        return persistentJointRegistry.values
            .asSequence()
            .filter { it.dimensionId == dimensionId }
            .map(::clonePersistentJointRecord)
            .toList()
    }

    @Synchronized
    fun findActivePersistentJointByOwner(
        dimensionId: String,
        ownerType: String?,
        ownerRef: String?
    ): PersistentJointRecord? {
        if (ownerType == null || ownerRef == null) {
            return null
        }
        return persistentJointRegistry.values.firstOrNull {
            it.state == PersistentJointState.ACTIVE &&
                it.dimensionId == dimensionId &&
                it.ownerType == ownerType &&
                it.ownerRef == ownerRef
        }?.let(::clonePersistentJointRecord)
    }

    @Synchronized
    fun markPersistentJointTombstone(persistentKey: String) {
        val existing = persistentJointRegistry[persistentKey] ?: return
        persistentJointRegistry[persistentKey] = clonePersistentJointRecord(
            existing.copy(state = PersistentJointState.TOMBSTONE)
        )
    }

    @Synchronized
    fun removePersistentJoint(persistentKey: String) {
        persistentJointRegistry.remove(persistentKey)
    }

    @Synchronized
    fun compactPersistentJointTombstones() {
        val iterator = persistentJointRegistry.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.state == PersistentJointState.TOMBSTONE) {
                iterator.remove()
            }
        }
    }

    @Synchronized
    fun findActivePersistentJointByRuntimeId(dimensionId: String, runtimeId: Int): PersistentJointRecord? {
        return persistentJointRegistry.values.firstOrNull {
            it.state == PersistentJointState.ACTIVE &&
                it.dimensionId == dimensionId &&
                it.lastKnownRuntimeId == runtimeId
        }?.let(::clonePersistentJointRecord)
    }

    @Synchronized
    fun updatePersistentJointRuntimeId(persistentKey: String, runtimeId: Int?) {
        val existing = persistentJointRegistry[persistentKey] ?: return
        persistentJointRegistry[persistentKey] = clonePersistentJointRecord(
            existing.copy(lastKnownRuntimeId = runtimeId)
        )
    }

    @Synchronized
    private fun clonePersistentJointRecord(record: PersistentJointRecord): PersistentJointRecord {
        return record.copy(jointPayload = record.jointPayload.copyOf())
    }

    override fun save(compoundTag: CompoundTag): CompoundTag {
        compoundTag.putByteArray(PIPELINE_NBT_KEY, vsCore.serializePipeline(pipeline))
        compoundTag.putLongArray(
            STABILIZATION_PENDING_DYNAMIC_RESTORE_SHIP_IDS_NBT_KEY,
            shipsPendingDynamicRestoreShipIds.toLongArray()
        )
        val persistentJointList = ListTag()
        getAllPersistentJoints().forEach { record ->
            val entry = CompoundTag()
            entry.putString(PERSISTENT_JOINT_KEY_NBT_KEY, record.persistentKey)
            entry.putString(PERSISTENT_JOINT_TYPE_NBT_KEY, record.jointType)
            entry.putByteArray(PERSISTENT_JOINT_PAYLOAD_NBT_KEY, record.jointPayload)
            if (record.lastKnownRuntimeId != null) {
                entry.putInt(PERSISTENT_JOINT_LAST_RUNTIME_ID_NBT_KEY, record.lastKnownRuntimeId)
            }
            if (record.ownerType != null) {
                entry.putString(PERSISTENT_JOINT_OWNER_TYPE_NBT_KEY, record.ownerType)
            }
            if (record.ownerRef != null) {
                entry.putString(PERSISTENT_JOINT_OWNER_REF_NBT_KEY, record.ownerRef)
            }
            if (record.dimensionId != null) {
                entry.putString(PERSISTENT_JOINT_DIMENSION_ID_NBT_KEY, record.dimensionId)
            }
            entry.putString(PERSISTENT_JOINT_STATE_NBT_KEY, record.state.name)
            persistentJointList.add(entry)
        }
        compoundTag.put(PERSISTENT_JOINT_REGISTRY_NBT_KEY, persistentJointList)

        return compoundTag
    }

    /**
     * This is not efficient, but it will work for now.
     */
    override fun isDirty(): Boolean {
        return true
    }
}
