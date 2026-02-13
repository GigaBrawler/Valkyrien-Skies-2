package org.valkyrienskies.mod.common.util

import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.ships.properties.ShipId
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.core.internal.joints.VSJoint
import org.valkyrienskies.core.internal.joints.VSJointAndId
import org.valkyrienskies.core.internal.joints.VSJointId
import org.valkyrienskies.core.internal.world.VsiPhysLevel
import org.valkyrienskies.core.util.pollUntilEmpty
import org.valkyrienskies.mod.common.ShipSavedData
import org.valkyrienskies.mod.common.joints.JointPersistenceManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.function.Consumer

@OptIn(VsBeta::class)
class GameToPhysicsAdapter {
    private val worldForces = ConcurrentLinkedQueue<Pair<ShipId, ForceAtPos>>()
    private val worldTorques = ConcurrentLinkedQueue<Pair<ShipId, Vector3dc>>()
    private val modelForces = ConcurrentLinkedQueue<Pair<ShipId, ForceAtPos>>()
    private val modelTorques = ConcurrentLinkedQueue<Pair<ShipId, Vector3dc>>()
    private val bodyForces = ConcurrentLinkedQueue<Pair<ShipId, ForceAtPos>>()
    private val bodyTorques = ConcurrentLinkedQueue<Pair<ShipId, Vector3dc>>()
    private val worldToModelForces = ConcurrentLinkedQueue<Pair<ShipId, ForceAtPos>>()
    private val worldToBodyForces = ConcurrentLinkedQueue<Pair<ShipId, ForceAtPos>>()

    private val addedJoints = ConcurrentHashMap<Pair<VSJoint, Consumer<VSJointId>>, Int>()
    private val updatedJoints = ConcurrentLinkedQueue<VSJointAndId>()
    private val deletedJoints = ConcurrentLinkedQueue<VSJointId>()

    private val shipToJointIds = ConcurrentHashMap<Long, Set<Int>>()
    private val jointById = ConcurrentHashMap<Int, VSJoint>()
    private val runtimeIdAliases = ConcurrentHashMap<Int, Int>()
    private val persistentKeyToRuntimeId = ConcurrentHashMap<String, Int>()
    private val runtimeIdToPersistentKey = ConcurrentHashMap<Int, String>()
    private val deferredRestoreCallbacks = ConcurrentHashMap<String, ConcurrentLinkedQueue<Consumer<VSJointId>>>()

    private val toBeStatic = ConcurrentLinkedQueue<Pair<ShipId, Boolean>>()

    private val enablePairs = ConcurrentLinkedQueue<Pair<ShipId, ShipId>>()
    private val disablePairs = ConcurrentLinkedQueue<Pair<ShipId, ShipId>>()

    @Volatile
    private var persistenceDimensionId: String? = null


    fun physTick(physLevel: PhysLevel, delta: Double) {

        worldForces.pollUntilEmpty { pair ->
            val ship = physLevel.getShipById(pair.first)
            if (pair.second.pos != null) {
                ship?.applyWorldForce(pair.second.force, pair.second.pos!!)
            } else {
                ship?.applyWorldForce(
                    pair.second.force
                )
            }
        }
        worldTorques.pollUntilEmpty { pair ->
            physLevel.getShipById(pair.first)
                ?.applyWorldTorque(pair.second)
        }
        modelForces.pollUntilEmpty { pair ->
            val ship = physLevel.getShipById(pair.first)
            if (pair.second.pos != null) {
                ship?.applyModelForce(pair.second.force, pair.second.pos!!)
            } else {
                ship?.applyModelForce(
                    pair.second.force
                )
            }
        }
        modelTorques.pollUntilEmpty { pair ->
            physLevel.getShipById(pair.first)
                ?.applyModelTorque(pair.second)
        }
        bodyForces.pollUntilEmpty { pair ->
            val ship = physLevel.getShipById(pair.first)
            if (pair.second.pos != null) {
                ship?.applyBodyForce(pair.second.force, pair.second.pos!!)
            } else {
                ship?.applyBodyForce(
                    pair.second.force
                )
            }

        }
        bodyTorques.pollUntilEmpty { pair ->
            physLevel.getShipById(pair.first)
                ?.applyBodyTorque(pair.second)
        }
        worldToModelForces.pollUntilEmpty { pair ->
            val ship = physLevel.getShipById(pair.first)
            if (pair.second.pos != null) {
                ship?.applyWorldForceToModelPos(pair.second.force, pair.second.pos!!)
            } else {
                ship?.applyWorldForceToModelPos(
                    pair.second.force
                )
            }

        }
        worldToBodyForces.pollUntilEmpty { pair ->
            val ship = physLevel.getShipById(pair.first)
            if (pair.second.pos != null) {
                ship?.applyWorldForceToBodyPos(pair.second.force, pair.second.pos!!)
            } else {
                ship?.applyWorldForceToBodyPos(
                    pair.second.force
                )
            }
        }

        // We have to have this weird queue so that we can add all our joints,
        // then update our jointById maps, then call the callbacks.
        // Otherwise, people trying to get their joint by id _in_ the callback will get null.
        val callbackQueue = ArrayList<Pair<Consumer<VSJointId>, VSJointId>>()

        val safeJoints = HashMap(addedJoints)
        safeJoints.forEach { newJoint, timer ->
            if (timer > 0) {
                addedJoints[newJoint] = timer - 1
            } else {
                callbackQueue.add(Pair(newJoint.second, (physLevel as VsiPhysLevel).addJoint(newJoint.first)))
                addedJoints.remove(newJoint)
            }
        }

        updatedJoints.pollUntilEmpty { jointAndId ->
            (physLevel as VsiPhysLevel).updateJoint(jointAndId.jointId, jointAndId.joint)
        }
        deletedJoints.pollUntilEmpty { jointId ->
            (physLevel as VsiPhysLevel).removeJoint(jointId)
        }

        // Update our joint maps - strategically placed between adding the joints, and calling the callbacks
        shipToJointIds.clear()
        jointById.clear()

        shipToJointIds.putAll((physLevel as VsiPhysLevel).getJointsByShipIds())
        jointById.putAll((physLevel as VsiPhysLevel).getAllJoints())
        val activeJointIds = jointById.keys.toSet()
        persistentKeyToRuntimeId.entries.removeIf { (_, runtimeId) ->
            val resolvedRuntimeId = resolveRuntimeJointId(runtimeId)
            !activeJointIds.contains(resolvedRuntimeId)
        }
        runtimeIdToPersistentKey.entries.removeIf { (runtimeId, _) ->
            val resolvedRuntimeId = resolveRuntimeJointId(runtimeId)
            !activeJointIds.contains(resolvedRuntimeId)
        }

        // and finally... call the callbacks
        callbackQueue.forEach { (consumer, i) -> consumer.accept(i) }

        toBeStatic.pollUntilEmpty { pair -> physLevel.getShipById(pair.first)?.isStatic = pair.second }

        enablePairs.pollUntilEmpty { pair -> physLevel.enableCollisionBetween(pair.first, pair.second) }
        disablePairs.pollUntilEmpty { pair -> physLevel.disableCollisionBetween(pair.first, pair.second) }

    }

    /**
     * Applies a force in World Space to a ship at a World Space position. A World Space force is independent of the ship's transform, and is always global; for example, up in World Space
     * is ALWAYS (0, 1, 0) (as in, towards the sky), regardless of the ship's orientation.
     *
     * @param forceInWorld The force vector in World Space.
     * @param posInWorld The position in World Space where the force is applied. Defaults to the ship's center of mass in World Space.
     */
    fun applyWorldForce(ship: ShipId, forceInWorld: Vector3dc, posInWorld: Vector3dc?) {
        worldForces.add(ship to ForceAtPos(forceInWorld, posInWorld))
    }
    /**
     * Applies a torque in World Space to a ship at a World Space position. A World Space torque is independent of the ship's transform, and is always global; for example, up in World Space
     * is ALWAYS (0, 1, 0) (as in, towards the sky), regardless of the ship's orientation.
     *
     * @param torqueInWorld The force vector in World Space.
     */
    fun applyWorldTorque(ship: ShipId, torqueInWorld: Vector3dc) {
        worldTorques.add(ship to torqueInWorld)
    }

    /**
     * Applies a force in Model Space to a ship at a Model Space position. A Model Space force is relative to the ship's transform, meaning that it rotates and scales with the ship; for example,
     * a ship rotated on its side applying a force pointing to (0, 1, 0) in Model Space would be **perpendicular** to World Space up.
     *
     * This is useful for a Thruster or similar block that should apply a force relative to the ship's orientation.
     *
     * @param forceInShip The force vector in Model Space.
     * @param posInShip The position in Model Space where the force is applied. Defaults to the ship's center of mass in Model Space.
     */
    fun applyModelForce(ship: ShipId, forceInShip: Vector3dc, posInShip: Vector3dc?) {
        modelForces.add(ship to ForceAtPos(forceInShip, posInShip))
    }
    /**
     * Applies a torque in Model Space to a ship at a Model Space position. A Model Space torque is relative to the ship's transform, meaning that it rotates and scales with the ship.
     *
     * @param torqueInShip The torque vector in Model Space.
     * @param posInShip The position in Model Space where the torque is applied. Defaults to the ship's center of mass in Model Space.
     */
    fun applyModelTorque(ship: ShipId, torqueInShip: Vector3dc) {
        modelTorques.add(ship to torqueInShip)
    }
    /**
     * Applies a force in World Space to a ship at a Model Space position. A World Space force is independent of the ship's transform, and is always global; for example, up in World Space
     * is ALWAYS (0, 1, 0) (as in, towards the sky), regardless of the ship's orientation.
     *
     * This is useful for a balloon or similar block that should apply a force relative to the world, such as always pushing up against gravity.
     *
     * @param forceInWorld The force vector in World Space.
     * @param posInShip The position in Model Space where the force is applied. Defaults to the ship's center of mass in Model Space.
     */
    fun applyWorldForceToModelPos(ship: ShipId, forceInWorld: Vector3dc, posInShip: Vector3dc) {
        worldToModelForces.add(ship to ForceAtPos(forceInWorld, posInShip))
    }

    /**
     * Applies a force in Body Space to a ship at a Body Space position. A Body Space force is positionally relative to the ship's Center of Mass, and applies relative to the ship's transform, meaning that it rotates and scales with the ship.
     *
     * @param forceInBody The force vector in Body Space.
     * @param posInBody The position in Body Space where the force is applied. Defaults to (0,0,0), the ship's center of mass.
     */
    fun applyBodyForce(ship: ShipId, forceInBody: Vector3dc, posInBody: Vector3dc = Vector3d()) {
        bodyForces.add(ship to ForceAtPos(forceInBody, posInBody))
    }
    /**
     * Applies a torque in Body Space to a ship at a Body Space position. A Body Space torque is positionally relative to the ship's Center of Mass, and applies relative to the ship's transform, meaning that it rotates and scales with the ship.
     *
     * @param torqueInBody The force vector in Body Space.
     * @param posInBody The position in Body Space where the force is applied. Defaults to (0,0,0), the ship's center of mass.
     */
    fun applyBodyTorque(ship: ShipId, torqueInBody: Vector3dc) {
        bodyTorques.add(ship to torqueInBody)
    }
    /**
     * Applies a force in World Space to a ship at a Body Space position. A World Space force is independent of the ship's transform, and is always global; for example, up in World Space
     * is ALWAYS (0, 1, 0) (as in, towards the sky), regardless of the ship's orientation.
     *
     * @param forceInWorld The force vector in World Space.
     * @param posInBody The position in Body Space where the force is applied. Defaults to (0,0,0), the ship's center of mass.
     */
    fun applyWorldForceToBodyPos(ship: ShipId, forceInWorld: Vector3dc, posInBody: Vector3dc = Vector3d()) {
        worldToBodyForces.add(ship to ForceAtPos(forceInWorld, posInBody))
    }

    @Deprecated("Use applyWorldForceToBodyPos instead")
    fun applyInvariantForce(ship: ShipId, force: Vector3dc) {
        applyWorldForceToBodyPos(ship, force)
    }

    @Deprecated("Use applyWorldTorque instead")
    fun applyInvariantTorque(ship: ShipId, torque: Vector3dc) {
        applyWorldTorque(ship, torque)
    }

    @Deprecated("Use applyBodyForce instead")
    fun applyRotDependentForce(ship: ShipId, force: Vector3dc) {
        applyBodyForce(ship, force)
    }

    @Deprecated("Use applyBodyTorque instead")
    fun applyRotDependentTorque(ship: ShipId, torque: Vector3dc) {
        applyBodyTorque(ship, torque)
    }

    @Deprecated("Use applyWorldForceToBodyPos instead")
    fun applyInvariantForceToPos(ship: ShipId, force: Vector3dc, pos: Vector3dc) {
        applyWorldForceToBodyPos(ship, force, pos)
    }

    @Deprecated("Use applyBodyForce instead")
    fun applyRotDependentForceToPos(ship: ShipId, force: Vector3dc, pos: Vector3dc) {
        applyBodyForce(ship, force, pos)
    }

    fun setStatic(ship: ShipId, b: Boolean) {
        toBeStatic.add(ship to b)
    }

    fun configurePersistence(dimensionId: String) {
        persistenceDimensionId = dimensionId
    }

    fun clearRuntimeState() {
        worldForces.clear()
        worldTorques.clear()
        modelForces.clear()
        modelTorques.clear()
        bodyForces.clear()
        bodyTorques.clear()
        worldToModelForces.clear()
        worldToBodyForces.clear()

        addedJoints.clear()
        updatedJoints.clear()
        deletedJoints.clear()

        shipToJointIds.clear()
        jointById.clear()
        runtimeIdAliases.clear()
        persistentKeyToRuntimeId.clear()
        runtimeIdToPersistentKey.clear()
        deferredRestoreCallbacks.clear()

        toBeStatic.clear()
        enablePairs.clear()
        disablePairs.clear()

        persistenceDimensionId = null
    }

    fun registerRuntimeAlias(legacyRuntimeId: Int, runtimeId: Int) {
        if (legacyRuntimeId < 0 || runtimeId < 0 || legacyRuntimeId == runtimeId) {
            return
        }
        runtimeIdAliases[legacyRuntimeId] = runtimeId
    }

    fun resolveRuntimeJointId(runtimeOrLegacyId: Int): Int {
        if (runtimeOrLegacyId < 0) {
            return runtimeOrLegacyId
        }
        val visited = HashSet<Int>()
        var current = runtimeOrLegacyId
        while (true) {
            if (!visited.add(current)) {
                break
            }
            val next = runtimeIdAliases[current] ?: break
            current = next
        }
        return current
    }

    fun bindPersistentKey(persistentKey: String, runtimeId: Int) {
        if (runtimeId < 0 || persistentKey.isBlank()) {
            return
        }
        val resolvedRuntimeId = resolveRuntimeJointId(runtimeId)
        persistentKeyToRuntimeId[persistentKey] = resolvedRuntimeId
        runtimeIdToPersistentKey[resolvedRuntimeId] = persistentKey
    }

    fun unbindPersistentKey(persistentKey: String, runtimeId: Int? = null) {
        val resolvedRuntimeId = runtimeId?.let(::resolveRuntimeJointId) ?: persistentKeyToRuntimeId[persistentKey]
        if (resolvedRuntimeId != null) {
            runtimeIdToPersistentKey.remove(resolvedRuntimeId)
        }
        persistentKeyToRuntimeId.remove(persistentKey)
    }

    fun getRuntimeIdForPersistentKey(persistentKey: String): Int? {
        val runtimeId = persistentKeyToRuntimeId[persistentKey] ?: return null
        val resolvedRuntimeId = resolveRuntimeJointId(runtimeId)
        if (resolvedRuntimeId != runtimeId) {
            bindPersistentKey(persistentKey, resolvedRuntimeId)
        }
        return resolvedRuntimeId
    }

    fun getPersistentKeyForRuntimeId(runtimeId: Int): String? {
        val resolvedRuntimeId = resolveRuntimeJointId(runtimeId)
        return runtimeIdToPersistentKey[resolvedRuntimeId] ?: runtimeIdToPersistentKey[runtimeId]
    }

    fun hasPersistentKeyBinding(persistentKey: String): Boolean {
        return getRuntimeIdForPersistentKey(persistentKey) != null
    }

    fun queueDeferredRestoreCallback(persistentKey: String, callback: Consumer<VSJointId>) {
        if (persistentKey.isBlank()) {
            callback.accept(-1)
            return
        }
        deferredRestoreCallbacks.computeIfAbsent(persistentKey) { ConcurrentLinkedQueue() }.add(callback)
    }

    fun notifyPersistentRestoreResolved(persistentKey: String, runtimeId: Int) {
        if (persistentKey.isBlank() || runtimeId < 0) {
            return
        }
        dispatchDeferredRestoreCallbacks(persistentKey, resolveRuntimeJointId(runtimeId))
    }

    fun notifyPersistentRestoreTerminalFailure(persistentKey: String) {
        if (persistentKey.isBlank()) {
            return
        }
        dispatchDeferredRestoreCallbacks(persistentKey, -1)
    }

    fun getRuntimeIdAliasesSnapshot(): Map<Int, Int> = runtimeIdAliases.toMap()

    fun getPersistentKeyBindingsSnapshot(): Map<String, Int> = persistentKeyToRuntimeId.toMap()

    fun addJointPersistent(
        joint: VSJoint,
        ownerType: String? = null,
        ownerRef: String? = null,
        persistentKey: String? = null,
        delay: Int = 0,
        function: Consumer<VSJointId>
    ) {
        val dimensionId = persistenceDimensionId
        if (dimensionId == null) {
            addedJoints[joint to function] = delay
            return
        }

        var resolvedPersistentKey = persistentKey?.takeIf { it.isNotBlank() }
            ?: JointPersistenceManager.newPersistentKey()
        resolvedPersistentKey = JointPersistenceManager.resolvePersistentKeyForOwner(
            dimensionId,
            resolvedPersistentKey,
            ownerType,
            ownerRef
        )
        val hadActiveDescriptor = JointPersistenceManager.isActiveDescriptor(dimensionId, resolvedPersistentKey)

        val existingRuntimeId = JointPersistenceManager.findRestoredRuntimeIdForOwner(
            dimensionId,
            ownerType,
            ownerRef,
            this,
            null
        ) ?: getRuntimeIdForPersistentKey(resolvedPersistentKey)
        val resolvedExistingRuntimeId = existingRuntimeId?.let(::resolveRuntimeJointId)
        if (resolvedExistingRuntimeId != null && jointById.containsKey(resolvedExistingRuntimeId)) {
            JointPersistenceManager.upsertActiveJointDescriptor(
                dimensionId = dimensionId,
                persistentKey = resolvedPersistentKey,
                joint = joint,
                ownerType = ownerType,
                ownerRef = ownerRef,
                runtimeId = resolvedExistingRuntimeId
            )
            bindPersistentKey(resolvedPersistentKey, resolvedExistingRuntimeId)
            function.accept(resolvedExistingRuntimeId)
            return
        }

        JointPersistenceManager.upsertActiveJointDescriptor(
            dimensionId = dimensionId,
            persistentKey = resolvedPersistentKey,
            joint = joint,
            ownerType = ownerType,
            ownerRef = ownerRef,
            runtimeId = resolvedExistingRuntimeId
        )
        if (hadActiveDescriptor) {
            queueDeferredRestoreCallback(resolvedPersistentKey, function)
            return
        }

        val wrappedCallback = Consumer<VSJointId> { runtimeId ->
            JointPersistenceManager.onAddResult(dimensionId, resolvedPersistentKey, runtimeId, this)
            function.accept(resolveRuntimeJointId(runtimeId))
        }
        addedJoints[joint to wrappedCallback] = delay
    }

    fun restorePersistentJoint(descriptor: ShipSavedData.PersistentJointRecord, joint: VSJoint) {
        val dimensionId = persistenceDimensionId ?: return
        val wrappedCallback = Consumer<VSJointId> { runtimeId ->
            JointPersistenceManager.onAddResult(dimensionId, descriptor.persistentKey, runtimeId, this)
        }
        addedJoints[joint to wrappedCallback] = 0
    }

    fun addJoint(joint: VSJoint, delay: Int = 0, function: Consumer<VSJointId>) {
        addJointPersistent(
            joint = joint,
            ownerType = null,
            ownerRef = null,
            persistentKey = null,
            delay = delay,
            function = function
        )
    }

    fun updateJointPersistent(runtimeOrLegacyId: VSJointId, joint: VSJoint) {
        val resolvedRuntimeId = resolveRuntimeJointId(runtimeOrLegacyId)
        updatedJoints.add(VSJointAndId(resolvedRuntimeId, joint))
        val dimensionId = persistenceDimensionId ?: return
        JointPersistenceManager.onRuntimeJointUpdated(dimensionId, resolvedRuntimeId, joint, this)
    }

    fun updateJoint(jointAndId: VSJointAndId) {
        updateJointPersistent(jointAndId.jointId, jointAndId.joint)
    }

    fun removeJointPersistent(runtimeOrLegacyId: VSJointId) {
        val resolvedRuntimeId = resolveRuntimeJointId(runtimeOrLegacyId)
        deletedJoints.add(resolvedRuntimeId)
        val dimensionId = persistenceDimensionId ?: return
        JointPersistenceManager.onRuntimeJointRemoved(dimensionId, resolvedRuntimeId, this)
    }

    fun removeJoint(jointId: VSJointId) {
        removeJointPersistent(jointId)
    }

    /**
     * Returns a joint by its ID.
     *
     * @param jointId The ID of the joint to retrieve.
     * @return The joint with the specified ID, or null if it does not exist.
     */
    fun getJointById(jointId: VSJointId): VSJoint? {
        return jointById[jointId]
    }

    /**
     * Returns a set containing the IDs of all joints currently attached to the ship with the specified ID.
     *
     * All returned Ids should be valid on the frame requested, but it is not advised to store this result, as it may change.
     *
     * @see [getJointById]
     */
    fun getJointsFromShip(shipId: ShipId): Set<VSJointId>? {
        return shipToJointIds[shipId]
    }

    /**
     * Retuns a map of all joints and their IDs in this PhysLevel.
     *
     * @see [getJointById]
     */
    fun getAllJoints(): Map<VSJointId, VSJoint> {
        return jointById.toMap()
    }

    /**
     * Returns a map of ShipIds to the IDs of any joints attached to them.
     *
     * @see [getJointsFromShip]
     */
    fun getJointsByShipIds(): Map<ShipId, Set<VSJointId>> {
        return shipToJointIds.toMap()
    }

    fun enableCollisionBetween(shipA: ShipId, shipB: ShipId) {
        enablePairs.add(shipA to shipB)
    }

    fun disableCollisionBetween(shipA: ShipId, shipB: ShipId) {
        disablePairs.add(shipA to shipB)
    }

    private fun dispatchDeferredRestoreCallbacks(persistentKey: String, runtimeId: Int) {
        val callbacks = deferredRestoreCallbacks.remove(persistentKey) ?: return
        while (true) {
            val callback = callbacks.poll() ?: break
            callback.accept(runtimeId)
        }
    }

    private data class ForceAtPos(val force: Vector3dc, val pos: Vector3dc?)
}
