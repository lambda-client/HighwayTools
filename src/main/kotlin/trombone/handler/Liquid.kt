package trombone.handler

import HighwayTools.debugLevel
import HighwayTools.fillerMat
import HighwayTools.illegalPlacements
import HighwayTools.maxReach
import HighwayTools.placementSearch
import com.lambda.client.LambdaMod
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.util.math.CoordinateConverter.asString
import com.lambda.client.util.math.VectorUtils.distanceTo
import com.lambda.client.util.world.getNeighbourSequence
import net.minecraft.block.BlockLiquid
import net.minecraft.util.EnumFacing
import trombone.IO
import trombone.blueprint.BlueprintTask
import trombone.task.BlockTask
import trombone.task.TaskManager.addTask
import trombone.task.TaskManager.tasks
import trombone.task.TaskState

object Liquid {
    fun SafeClientEvent.handleLiquid(blockTask: BlockTask): Boolean {
        var foundLiquid = false

        for (side in EnumFacing.values()) {
            if (side == EnumFacing.DOWN) continue
            val neighbourPos = blockTask.blockPos.offset(side)

            if (world.getBlockState(neighbourPos).block !is BlockLiquid) continue

            if (getNeighbourSequence(neighbourPos, placementSearch, maxReach, !illegalPlacements).isEmpty()) {
                if (debugLevel == IO.DebugLevel.VERBOSE) {
                    LambdaMod.LOG.info("[Trombone] Skipping liquid block at ${neighbourPos.asString()} due to distance")
                }
                blockTask.updateState(TaskState.DONE)
                return true
            }

            foundLiquid = true

            tasks[neighbourPos]?.let {
                updateLiquidTask(it)
            } ?: run {
                val newTask = BlockTask(neighbourPos, TaskState.LIQUID, fillerMat)
                val blueprintTask = BlueprintTask(fillerMat, isFiller = true)

                addTask(newTask, blueprintTask)
            }
        }

        return foundLiquid
    }

    fun SafeClientEvent.updateLiquidTask(blockTask: BlockTask) {
        blockTask.updateState(TaskState.LIQUID)
        blockTask.updateTask(this)
    }
}