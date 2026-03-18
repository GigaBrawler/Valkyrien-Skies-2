package org.valkyrienskies.mod.common.item

import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.block.state.BlockState
import org.valkyrienskies.core.util.datastructures.DenseBlockPosSet
import org.valkyrienskies.mod.api.toBlockPos
import org.valkyrienskies.mod.common.assembly.ShipAssembler
import org.valkyrienskies.mod.common.isChunkInShipyard

class ShipAssemblerItem(properties: Properties) : Item(properties) {

    override fun isFoil(stack: ItemStack): Boolean {
        return true
    }

    override fun useOn(ctx: UseOnContext): InteractionResult {
        val level = ctx.level as? ServerLevel ?: return super.useOn(ctx)
        val pos = ctx.clickedPos
        val blockState: BlockState = level.getBlockState(pos)

        if (!level.isClientSide) {
            if (ctx.level.isChunkInShipyard(pos.x shr 4, pos.z shr 4)) {
                ctx.player?.sendSystemMessage(Component.literal("That chunk is already part of a ship!"))
            } else if (!blockState.isAir) {
                // Make a ship
                val set = DenseBlockPosSet()
                for (x in -3..3) {
                    for (z in -3..3) {
                        set.add(pos.x + x, pos.y, pos.z + z)
                    }
                }

                ctx.player?.sendSystemMessage(Component.literal("Assembling ship..."))
                ShipAssembler.queueAssembleToShip(level, set.map { it.toBlockPos() }.toSet(), 1.0)
                    .thenAccept { shipData ->
                        ctx.player?.sendSystemMessage(
                            Component.translatable("command.valkyrienskies.shipify.success_one", shipData.slug)
                        )
                    }
                    .exceptionally { error ->
                        ctx.player?.sendSystemMessage(
                            Component.literal("Ship assembly failed: ${error.cause?.message ?: error.message ?: "unknown error"}")
                        )
                        null
                    }
            }
        }

        return super.useOn(ctx)
    }
}
