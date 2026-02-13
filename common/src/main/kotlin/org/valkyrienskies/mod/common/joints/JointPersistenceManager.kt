package org.valkyrienskies.mod.common.joints

import net.minecraft.core.BlockPos
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.core.impl.util.serialization.VSJacksonUtil
import org.valkyrienskies.core.internal.joints.VSJoint
import org.valkyrienskies.core.internal.joints.VSJointPose
import org.valkyrienskies.core.internal.world.VsiPhysLevel
import org.valkyrienskies.mod.common.ShipSavedData
import org.valkyrienskies.mod.common.ShipSavedData.PersistentJointRecord
import org.valkyrienskies.mod.common.ShipSavedData.PersistentJointState
import org.valkyrienskies.mod.common.util.GameToPhysicsAdapter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

object JointPersistenceManager {
    private const val RESTORE_RETRY_INTERVAL_TICKS = 20
    private const val MISSING_BODY_GRACE_ATTEMPTS = 120
    private const val QUATERNION_NORM_TOLERANCE = 1e-3
    private val CLOCKWORK_PAIR_OWNER_REF_REGEX =
        Regex("^pair:[^|]+\\|-?\\d+,-?\\d+,-?\\d+\\|-?\\d+,-?\\d+,-?\\d+\\|[A-Za-z0-9_:-]+$")

    private val pendingRestoreByDimension: MutableMap<String, MutableSet<String>> = ConcurrentHashMap()
    private val inFlightRestoreByDimension: MutableMap<String, MutableSet<String>> = ConcurrentHashMap()
    private val restoreCooldownByKey: MutableMap<String, Int> = ConcurrentHashMap()
    private val missingBodyAttemptsByKey: MutableMap<String, Int> = ConcurrentHashMap()

    @Volatile
    private var shipSavedData: ShipSavedData? = null

    @JvmStatic
    fun bootstrap(savedData: ShipSavedData) {
        shipSavedData = savedData
        pendingRestoreByDimension.clear()
        inFlightRestoreByDimension.clear()
        restoreCooldownByKey.clear()
        missingBodyAttemptsByKey.clear()

        pruneLegacyClockworkOwnerRefs(savedData)

        savedData.getAllPersistentJoints()
            .asSequence()
            .filter { it.state == PersistentJointState.ACTIVE && it.dimensionId != null }
            .forEach { record ->
                pendingRestoreByDimension
                    .getOrPut(record.dimensionId!!) { ConcurrentHashMap.newKeySet() }
                    .add(record.persistentKey)
            }
    }

    @JvmStatic
    fun clear() {
        shipSavedData = null
        pendingRestoreByDimension.clear()
        inFlightRestoreByDimension.clear()
        restoreCooldownByKey.clear()
        missingBodyAttemptsByKey.clear()
    }

    fun registerAdapter(dimensionId: String, adapter: GameToPhysicsAdapter) {
        adapter.configurePersistence(dimensionId)
        pendingRestoreByDimension.getOrPut(dimensionId) { ConcurrentHashMap.newKeySet() }
        inFlightRestoreByDimension.getOrPut(dimensionId) { ConcurrentHashMap.newKeySet() }
    }

    fun newPersistentKey(): String = UUID.randomUUID().toString()

    fun buildOwnerRef(dimensionId: String, blockPos: BlockPos, slot: String): String {
        return "$dimensionId|${blockPos.x}|${blockPos.y}|${blockPos.z}|$slot"
    }

    fun resolvePersistentKeyForOwner(
        dimensionId: String,
        requestedKey: String,
        ownerType: String?,
        ownerRef: String?
    ): String {
        val savedData = shipSavedData ?: return requestedKey
        val existing = savedData.findActivePersistentJointByOwner(dimensionId, ownerType, ownerRef)
        return existing?.persistentKey ?: requestedKey
    }

    fun isActiveDescriptor(dimensionId: String, persistentKey: String): Boolean {
        val savedData = shipSavedData ?: return false
        val descriptor = savedData.getPersistentJoint(persistentKey) ?: return false
        return descriptor.state == PersistentJointState.ACTIVE && descriptor.dimensionId == dimensionId
    }

    fun findRestoredRuntimeIdForOwner(
        dimensionId: String,
        ownerType: String?,
        ownerRef: String?,
        adapter: GameToPhysicsAdapter,
        physLevel: PhysLevel?
    ): Int? {
        if (ownerType == null || ownerRef == null) {
            return null
        }
        val savedData = shipSavedData ?: return null
        val existing = savedData.findActivePersistentJointByOwner(dimensionId, ownerType, ownerRef) ?: return null
        val runtimeId = existing.lastKnownRuntimeId ?: return null
        if (physLevel != null && getJointById(physLevel, runtimeId) == null) {
            return null
        }
        adapter.bindPersistentKey(existing.persistentKey, runtimeId)
        return runtimeId
    }

    fun upsertActiveJointDescriptor(
        dimensionId: String,
        persistentKey: String,
        joint: VSJoint,
        ownerType: String?,
        ownerRef: String?,
        runtimeId: Int?
    ) {
        val savedData = shipSavedData ?: return
        val existing = savedData.getPersistentJoint(persistentKey)
        val (jointType, payload) = serializeJoint(joint) ?: return

        val ownerTypeToUse = ownerType ?: existing?.ownerType
        val ownerRefToUse = ownerRef ?: existing?.ownerRef
        val runtimeIdToUse = runtimeId ?: existing?.lastKnownRuntimeId

        savedData.upsertPersistentJoint(
            PersistentJointRecord(
                persistentKey = persistentKey,
                jointType = jointType,
                jointPayload = payload,
                lastKnownRuntimeId = runtimeIdToUse,
                ownerType = ownerTypeToUse,
                ownerRef = ownerRefToUse,
                dimensionId = dimensionId,
                state = PersistentJointState.ACTIVE
            )
        )
        pendingRestoreByDimension.getOrPut(dimensionId) { ConcurrentHashMap.newKeySet() }.add(persistentKey)
    }

    fun onAddResult(
        dimensionId: String,
        persistentKey: String,
        runtimeId: Int,
        adapter: GameToPhysicsAdapter
    ) {
        inFlightRestoreByDimension.getOrPut(dimensionId) { ConcurrentHashMap.newKeySet() }.remove(persistentKey)
        if (runtimeId < 0) {
            restoreCooldownByKey[persistentKey] = RESTORE_RETRY_INTERVAL_TICKS
            return
        }

        val savedData = shipSavedData
        val existing = savedData?.getPersistentJoint(persistentKey)
        val previousRuntimeId = existing?.lastKnownRuntimeId
        if (previousRuntimeId != null && previousRuntimeId != runtimeId) {
            adapter.registerRuntimeAlias(previousRuntimeId, runtimeId)
        }
        existing?.let { savedData?.upsertPersistentJoint(it.copy(lastKnownRuntimeId = runtimeId)) }
        adapter.bindPersistentKey(persistentKey, runtimeId)

        pendingRestoreByDimension.getOrPut(dimensionId) { ConcurrentHashMap.newKeySet() }.remove(persistentKey)
        restoreCooldownByKey.remove(persistentKey)
        missingBodyAttemptsByKey.remove(persistentKey)
        adapter.notifyPersistentRestoreResolved(persistentKey, runtimeId)
    }

    fun onRuntimeJointUpdated(
        dimensionId: String,
        runtimeId: Int,
        joint: VSJoint,
        adapter: GameToPhysicsAdapter
    ) {
        val savedData = shipSavedData ?: return
        val persistentKey = adapter.getPersistentKeyForRuntimeId(runtimeId)
            ?: savedData.findActivePersistentJointByRuntimeId(dimensionId, runtimeId)?.persistentKey
            ?: return
        val existing = savedData.getPersistentJoint(persistentKey)
        val (jointType, payload) = serializeJoint(joint) ?: return
        savedData.upsertPersistentJoint(
            PersistentJointRecord(
                persistentKey = persistentKey,
                jointType = jointType,
                jointPayload = payload,
                lastKnownRuntimeId = runtimeId,
                ownerType = existing?.ownerType,
                ownerRef = existing?.ownerRef,
                dimensionId = dimensionId,
                state = PersistentJointState.ACTIVE
            )
        )
    }

    fun onRuntimeJointRemoved(
        dimensionId: String,
        runtimeId: Int,
        adapter: GameToPhysicsAdapter
    ) {
        val savedData = shipSavedData ?: return
        val persistentKey = adapter.getPersistentKeyForRuntimeId(runtimeId)
            ?: savedData.findActivePersistentJointByRuntimeId(dimensionId, runtimeId)?.persistentKey
            ?: return
        savedData.markPersistentJointTombstone(persistentKey)
        adapter.unbindPersistentKey(persistentKey, runtimeId)
        pendingRestoreByDimension.getOrPut(dimensionId) { ConcurrentHashMap.newKeySet() }.remove(persistentKey)
        inFlightRestoreByDimension.getOrPut(dimensionId) { ConcurrentHashMap.newKeySet() }.remove(persistentKey)
    }

    fun onPhysTick(dimensionId: String, physLevel: PhysLevel, adapter: GameToPhysicsAdapter) {
        val savedData = shipSavedData ?: return
        val descriptors = savedData.getPersistentJointsForDimension(dimensionId)
        if (descriptors.isEmpty()) {
            return
        }

        val pendingSet = pendingRestoreByDimension.getOrPut(dimensionId) { ConcurrentHashMap.newKeySet() }
        val inflightSet = inFlightRestoreByDimension.getOrPut(dimensionId) { ConcurrentHashMap.newKeySet() }

        descriptors.forEach { descriptor ->
            if (descriptor.state == PersistentJointState.TOMBSTONE) {
                return@forEach
            }
            pendingSet.add(descriptor.persistentKey)
        }

        val ownerRefsWithRuntime = HashMap<String, Int>()
        descriptors.forEach { descriptor ->
            if (descriptor.state != PersistentJointState.ACTIVE) {
                return@forEach
            }
            val ownerKey = descriptor.ownerType?.let { ownerType ->
                descriptor.ownerRef?.let { ownerRef ->
                    "$ownerType|$ownerRef"
                }
            } ?: return@forEach
            val runtimeId = adapter.getRuntimeIdForPersistentKey(descriptor.persistentKey)
                ?: descriptor.lastKnownRuntimeId
            if (runtimeId != null && getJointById(physLevel, runtimeId) != null) {
                ownerRefsWithRuntime[ownerKey] = runtimeId
            }
        }

        val activeDescriptorsByOwner = HashMap<String, PersistentJointRecord>()
        descriptors.forEach { descriptor ->
            if (descriptor.state != PersistentJointState.ACTIVE) {
                return@forEach
            }
            val ownerKey = descriptor.ownerType?.let { ownerType ->
                descriptor.ownerRef?.let { ownerRef ->
                    "$ownerType|$ownerRef"
                }
            }
            if (ownerKey != null) {
                val existing = activeDescriptorsByOwner[ownerKey]
                if (existing == null || existing.persistentKey == descriptor.persistentKey) {
                    activeDescriptorsByOwner[ownerKey] = descriptor
                } else if (existing.persistentKey != descriptor.persistentKey) {
                    savedData.markPersistentJointTombstone(descriptor.persistentKey)
                    pendingSet.remove(descriptor.persistentKey)
                    adapter.notifyPersistentRestoreTerminalFailure(descriptor.persistentKey)
                }
            }
        }

        pendingSet.toList().forEach { persistentKey ->
            val descriptor = savedData.getPersistentJoint(persistentKey) ?: run {
                pendingSet.remove(persistentKey)
                adapter.notifyPersistentRestoreTerminalFailure(persistentKey)
                return@forEach
            }
            if (descriptor.state != PersistentJointState.ACTIVE) {
                pendingSet.remove(persistentKey)
                adapter.notifyPersistentRestoreTerminalFailure(persistentKey)
                return@forEach
            }
            if (inflightSet.contains(persistentKey)) {
                return@forEach
            }

            val currentRuntimeId = adapter.getRuntimeIdForPersistentKey(persistentKey)
            if (currentRuntimeId != null && getJointById(physLevel, currentRuntimeId) != null) {
                pendingSet.remove(persistentKey)
                adapter.notifyPersistentRestoreResolved(persistentKey, currentRuntimeId)
                return@forEach
            }

            val ownerKey = descriptor.ownerType?.let { ownerType ->
                descriptor.ownerRef?.let { ownerRef ->
                    "$ownerType|$ownerRef"
                }
            }
            if (ownerKey != null) {
                val existingRuntime = ownerRefsWithRuntime[ownerKey]
                if (existingRuntime != null) {
                    adapter.bindPersistentKey(persistentKey, existingRuntime)
                    savedData.upsertPersistentJoint(descriptor.copy(lastKnownRuntimeId = existingRuntime))
                    pendingSet.remove(persistentKey)
                    adapter.notifyPersistentRestoreResolved(persistentKey, existingRuntime)
                    return@forEach
                }
            }

            descriptor.lastKnownRuntimeId?.let { lastRuntimeId ->
                if (getJointById(physLevel, lastRuntimeId) != null) {
                    adapter.bindPersistentKey(persistentKey, lastRuntimeId)
                    pendingSet.remove(persistentKey)
                    adapter.notifyPersistentRestoreResolved(persistentKey, lastRuntimeId)
                    return@forEach
                }
            }

            val cooldown = restoreCooldownByKey[persistentKey] ?: 0
            if (cooldown > 0) {
                restoreCooldownByKey[persistentKey] = cooldown - 1
                return@forEach
            }

            val joint = deserializeJoint(descriptor) ?: run {
                savedData.markPersistentJointTombstone(persistentKey)
                pendingSet.remove(persistentKey)
                adapter.notifyPersistentRestoreTerminalFailure(persistentKey)
                return@forEach
            }
            if (!isJointNumericallyValid(joint)) {
                savedData.markPersistentJointTombstone(persistentKey)
                pendingSet.remove(persistentKey)
                adapter.notifyPersistentRestoreTerminalFailure(persistentKey)
                return@forEach
            }

            if (!areJointBodiesReady(physLevel, joint)) {
                val missingAttempts = (missingBodyAttemptsByKey[persistentKey] ?: 0) + 1
                missingBodyAttemptsByKey[persistentKey] = missingAttempts
                restoreCooldownByKey[persistentKey] = RESTORE_RETRY_INTERVAL_TICKS
                if (missingAttempts > MISSING_BODY_GRACE_ATTEMPTS) {
                    savedData.markPersistentJointTombstone(persistentKey)
                    pendingSet.remove(persistentKey)
                    adapter.notifyPersistentRestoreTerminalFailure(persistentKey)
                }
                return@forEach
            }

            missingBodyAttemptsByKey.remove(persistentKey)
            restoreCooldownByKey[persistentKey] = RESTORE_RETRY_INTERVAL_TICKS
            inflightSet.add(persistentKey)
            adapter.restorePersistentJoint(descriptor, joint)
        }

        savedData.compactPersistentJointTombstones()
    }

    private fun areJointBodiesReady(physLevel: PhysLevel, joint: VSJoint): Boolean {
        return isJointBodyReady(physLevel, joint.shipId0) && isJointBodyReady(physLevel, joint.shipId1)
    }

    private fun isJointBodyReady(physLevel: PhysLevel, shipId: Long?): Boolean {
        if (shipId == null || shipId < 0) {
            return true
        }
        return physLevel.getShipById(shipId) != null
    }

    private fun getJointById(physLevel: PhysLevel, jointId: Int): VSJoint? {
        return (physLevel as? VsiPhysLevel)?.getJointById(jointId)
    }

    internal fun serializeJoint(joint: VSJoint): Pair<String, ByteArray>? {
        return runCatching {
            joint.javaClass.name to VSJacksonUtil.dtoMapper.writeValueAsBytes(joint)
        }.getOrNull()
    }

    private fun deserializeJoint(record: PersistentJointRecord): VSJoint? {
        return runCatching {
            val clazz = Class.forName(record.jointType).asSubclass(VSJoint::class.java)
            VSJacksonUtil.dtoMapper.readValue(record.jointPayload, clazz)
        }.getOrNull()
    }

    private fun isJointNumericallyValid(joint: VSJoint): Boolean {
        return isPoseNumericallyValid(joint.pose0) && isPoseNumericallyValid(joint.pose1)
    }

    private fun isPoseNumericallyValid(pose: VSJointPose): Boolean {
        val pos = pose.pos
        val rot = pose.rot
        if (!pos.x().isFinite() || !pos.y().isFinite() || !pos.z().isFinite()) {
            return false
        }
        if (!rot.x().isFinite() || !rot.y().isFinite() || !rot.z().isFinite() || !rot.w().isFinite()) {
            return false
        }
        val norm = rot.x() * rot.x() + rot.y() * rot.y() + rot.z() * rot.z() + rot.w() * rot.w()
        if (!norm.isFinite() || norm <= 1e-12) {
            return false
        }
        return abs(norm - 1.0) <= QUATERNION_NORM_TOLERANCE
    }

    private fun pruneLegacyClockworkOwnerRefs(savedData: ShipSavedData) {
        savedData.getAllPersistentJoints().forEach { record ->
            if (record.state != PersistentJointState.ACTIVE) {
                return@forEach
            }
            val shouldPrune = when (record.ownerType) {
                "clockwork_slicker" -> {
                    val ownerRef = record.ownerRef
                    ownerRef.isNullOrBlank() || !ownerRef.startsWith("key:") || ownerRef.length <= 4
                }
                "clockwork_extendon",
                "clockwork_hose_port",
                "clockwork_spinoff_bearing" -> {
                    val ownerRef = record.ownerRef
                    ownerRef.isNullOrBlank() || !CLOCKWORK_PAIR_OWNER_REF_REGEX.matches(ownerRef)
                }
                else -> false
            }
            if (shouldPrune) {
                savedData.markPersistentJointTombstone(record.persistentKey)
            }
        }
        savedData.compactPersistentJointTombstones()
    }
}
