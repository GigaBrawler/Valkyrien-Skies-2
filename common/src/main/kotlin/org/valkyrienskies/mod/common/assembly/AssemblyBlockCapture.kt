package org.valkyrienskies.mod.common.assembly

import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState

data class CapturedBlockChange(
    val pos: BlockPos,
    val prevState: BlockState,
    val newState: BlockState,
)

class AssemblyCaptureSession internal constructor(private val level: Level) {
    internal val blockChanges = ArrayList<CapturedBlockChange>()

    internal fun matches(level: Level): Boolean = this.level === level

    internal fun record(pos: BlockPos, prevState: BlockState, newState: BlockState) {
        blockChanges += CapturedBlockChange(pos.immutable(), prevState, newState)
    }
}

object AssemblyBlockCapture {
    private val activeSession = ThreadLocal<AssemblyCaptureSession?>()

    fun begin(level: Level): AssemblyCaptureSession {
        check(activeSession.get() == null) { "Assembly block capture session already active on this thread" }
        return AssemblyCaptureSession(level).also(activeSession::set)
    }

    fun finish(session: AssemblyCaptureSession): List<CapturedBlockChange> {
        check(activeSession.get() === session) { "Tried to finish a non-active assembly block capture session" }
        activeSession.remove()
        return session.blockChanges.toList()
    }

    fun capture(level: Level, pos: BlockPos, prevState: BlockState, newState: BlockState): Boolean {
        val session = activeSession.get() ?: return false
        if (!session.matches(level)) {
            return false
        }
        if (prevState == newState) {
            return true
        }
        session.record(pos, prevState, newState)
        return true
    }
}
