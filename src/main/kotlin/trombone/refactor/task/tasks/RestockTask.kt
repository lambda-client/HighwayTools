package trombone.refactor.task.tasks

import HighwayTools.anonymizeStats
import HighwayTools.debugLevel
import HighwayTools.fastFill
import HighwayTools.keepFreeSlots
import HighwayTools.leaveEmptyShulkers
import HighwayTools.material
import HighwayTools.mode
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.manager.managers.PlayerInventoryManager
import com.lambda.client.manager.managers.PlayerInventoryManager.addInventoryTask
import com.lambda.client.util.color.ColorHolder
import com.lambda.client.util.items.*
import com.lambda.client.util.math.CoordinateConverter.asString
import com.lambda.client.util.math.VectorUtils.toVec3dCenter
import com.lambda.client.util.text.MessageSendHelper
import com.lambda.client.util.world.getHitVec
import com.lambda.client.util.world.getHitVecOffset
import net.minecraft.block.Block
import net.minecraft.block.BlockContainer
import net.minecraft.block.BlockShulkerBox
import net.minecraft.client.gui.inventory.GuiContainer
import net.minecraft.init.Items
import net.minecraft.inventory.ClickType
import net.minecraft.inventory.Container
import net.minecraft.inventory.Slot
import net.minecraft.item.ItemPickaxe
import net.minecraft.network.play.client.CPacketPlayerTryUseItemOnBlock
import net.minecraft.network.play.server.SPacketOpenWindow
import net.minecraft.util.EnumFacing
import net.minecraft.util.EnumHand
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import trombone.IO
import trombone.IO.disableError
import trombone.Trombone
import trombone.Trombone.module
import trombone.refactor.task.ContainerHandler
import trombone.refactor.task.BuildTask
import trombone.refactor.task.TaskProcessor.convertTo

class RestockTask(
    blockPos: BlockPos,
    targetBlock: Block
) : BuildTask(blockPos, targetBlock, false, true, false) {
    private var state = State.INIT
    private var stopPull = false
    private var movedStuff = false

    override var priority = 3 + state.prioOffset
    override val timeout = 20
    override var threshold = 200
    override val color = state.colorHolder
    override var hitVec3d: Vec3d = Vec3d.ZERO

    enum class State(val colorHolder: ColorHolder, val prioOffset: Int) {
        INIT(ColorHolder(252, 3, 207 ), 0),
        OPEN_CONTAINER(ColorHolder(252, 3, 207), 0),
        PENDING_OPEN(ColorHolder(252, 3, 207), 0),
        PENDING_ITEM_LIST(ColorHolder(252, 3, 207), 0),
        MOVE_ITEMS(ColorHolder(252, 3, 207), 0),
        CLOSE(ColorHolder(252, 3, 207), 0)
    }

    override fun SafeClientEvent.isValid() = currentBlock is BlockContainer && isContainerTask && desiredItem != Items.AIR

    override fun SafeClientEvent.update(): Boolean {
        var wasUpdated = true

        hitVec3d = getHitVec(blockPos, getSideToOpen())

        when {
            currentBlock !is BlockContainer -> {
                convertTo<PlaceTask>()
            }
            else -> {
                wasUpdated = false
            }
        }

        return wasUpdated
    }

    override fun SafeClientEvent.execute() {
        when (state) {
            State.INIT -> {
                state = State.OPEN_CONTAINER
                execute()
            }
            State.OPEN_CONTAINER -> {
                val side = getSideToOpen()
                val hitVecNormalized = getHitVecOffset(side)

                connection.sendPacket(CPacketPlayerTryUseItemOnBlock(blockPos, side, EnumHand.MAIN_HAND, hitVecNormalized.x.toFloat(), hitVecNormalized.y.toFloat(), hitVecNormalized.z.toFloat()))
                player.swingArm(EnumHand.MAIN_HAND)

                state = State.PENDING_OPEN
            }
            State.PENDING_OPEN -> {
                if (timeTicking > 20) {
                    state = State.OPEN_CONTAINER
                    timeTicking = 0
                }
            }
            State.PENDING_ITEM_LIST -> {
                if (timeTicking > 20) {
                    state = State.OPEN_CONTAINER
                    timeTicking = 0
                }
            }
            State.MOVE_ITEMS -> {
                if (mc.currentScreen !is GuiContainer) {
                    state = State.OPEN_CONTAINER
                    return
                }

                val openContainer = player.openContainer

                if (leaveEmptyShulkers
                    && currentBlock is BlockShulkerBox
                    && !itemIsInEjectList(desiredItem)
                    && openContainer.getSlots(0..26).onlyContainsEjectables()
                ) {
                    if (debugLevel != IO.DebugLevel.OFF) {
                        if (!anonymizeStats) {
                            MessageSendHelper.sendChatMessage("${module.chatName} Left empty ${targetBlock.localizedName}@(${blockPos.asString()})")
                        } else {
                            MessageSendHelper.sendChatMessage("${module.chatName} Left empty ${targetBlock.localizedName}")
                        }
                    }

                    state = State.CLOSE
                    execute()
                    return
                }

                val freeSlots = openContainer.getSlots(27..62).count {
                    it.stack.isEmpty || itemIsInEjectList(it.stack.item)
                } - 1 - keepFreeSlots

                if (freeSlots < 1 || stopPull) {
                    state = State.CLOSE
                    execute()
                    return
                }

                openContainer.getSlots(0..26).firstItem(desiredItem)?.let {
                    moveToInventory(openContainer, it)
                    stopPull = true
                    movedStuff = true

                    if (fastFill) {
                        if (mode == Trombone.Structure.TUNNEL && desiredItem is ItemPickaxe) stopPull = false
                        if (mode != Trombone.Structure.TUNNEL && desiredItem == material.item) stopPull = false
                    }
                    return
                }

                if (movedStuff) {
                    state = State.CLOSE
                    execute()
                    return
                }

                ContainerHandler.getShulkerWith(openContainer.getSlots(0..26), desiredItem)?.let {
                    moveToInventory(openContainer, it)
                    state = State.CLOSE
                    execute()
                    return
                }

                disableError("No ${desiredItem.registryName} left in any container.")
            }
            State.CLOSE -> {
                player.closeScreen()

                convertTo<BreakTask>()
            }
        }
    }

    private fun SafeClientEvent.getSideToOpen(): EnumFacing {
        val center = blockPos.toVec3dCenter()
        val diff = player.getPositionEyes(1f).subtract(center)
        val normalizedVec = diff.normalize()

        return EnumFacing.getFacingFromVector(normalizedVec.x.toFloat(), normalizedVec.y.toFloat(), normalizedVec.z.toFloat())
    }

    private fun List<Slot>.onlyContainsEjectables() = all {
            it.stack.isEmpty || itemIsInEjectList(it.stack.item)
        }

    private fun List<Slot>.firstEmptyOrEjectableOrNull() = firstOrNull {
        it.stack.isEmpty || itemIsInEjectList(it.stack.item)
    }

    private fun SafeClientEvent.moveToInventory(openContainer: Container, originSlot: Slot) {
        openContainer.getSlots(27..62).firstOrNull {
            originSlot.stack.item == it.stack.item
                && it.stack.count < originSlot.stack.maxStackSize - originSlot.stack.count
        }?.let {
            module.addInventoryTask(
                PlayerInventoryManager.ClickInfo(
                    openContainer.windowId,
                    originSlot.slotNumber,
                    0,
                    ClickType.QUICK_MOVE
                )
            )
            return
        }

        openContainer.getSlots(54..62).firstEmptyOrEjectableOrNull()?.let {
            module.addInventoryTask(
                PlayerInventoryManager.ClickInfo(
                    openContainer.windowId,
                    originSlot.slotNumber,
                    it.slotNumber - 54,
                    ClickType.SWAP
                )
            )
            return
        }

        openContainer.getSlots(27..53).firstEmptyOrEjectableOrNull()?.let {
            module.addInventoryTask(
                PlayerInventoryManager.ClickInfo(
                    openContainer.windowId,
                    0,
                    it.slotNumber,
                    ClickType.SWAP
                ),
                PlayerInventoryManager.ClickInfo(
                    openContainer.windowId,
                    it.slotNumber,
                    0,
                    ClickType.SWAP
                )
            )
            return
        }

        zipInventory()
    }

    private fun SafeClientEvent.zipInventory() {
        val compressibleStacks = player.inventorySlots.filter { comp ->
            comp.stack.count < comp.stack.maxStackSize
                && player.inventorySlots.countByStack { comp.stack.item == it.item } > 1
        }

        if (compressibleStacks.isEmpty()) {
            disableError("Inventory full. (Considering that $keepFreeSlots slots are supposed to stay free)")
            return
        }

        compressibleStacks.forEach { slot ->
            module.addInventoryTask(
                PlayerInventoryManager.ClickInfo(slot = slot.slotNumber, type = ClickType.QUICK_MOVE)
            )
        }
    }

    fun acceptPacketOpen(packet: SPacketOpenWindow) {
        if (state == State.PENDING_OPEN) {
            if (packet.guiId == "minecraft:shulker_box"
                || packet.guiId == "minecraft:container"
            ) state = State.PENDING_ITEM_LIST
        }
    }

    fun acceptPacketLoaded() {
        if (state == State.PENDING_ITEM_LIST) {
            state = State.MOVE_ITEMS
        }
    }

    override fun gatherInfoToString() = "state=$state"

    override fun SafeClientEvent.gatherDebugInfo(): MutableList<Pair<String, String>> {
        val data: MutableList<Pair<String, String>> = mutableListOf()

        data.add(Pair("state", state.name))

        return data
    }
}