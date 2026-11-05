package com.one_studio

import java.util.function.Consumer
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraftforge.event.TickEvent
import net.minecraftforge.client.event.RegisterKeyMappingsEvent
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.entity.player.PlayerInteractEvent

object ClientTickEvents {
    fun interface StartTick {
        fun onStartTick(client: Minecraft)
    }

    fun interface EndTick {
        fun onEndTick(client: Minecraft)
    }

    private val startCallbacks = mutableListOf<StartTick>()
    private val endCallbacks = mutableListOf<EndTick>()
    private var initialized = false

    object START_CLIENT_TICK {
        fun register(callback: StartTick) {
            startCallbacks += callback
        }
    }

    object END_CLIENT_TICK {
        fun register(callback: EndTick) {
            endCallbacks += callback
        }
    }

    fun init() {
        if (initialized) return
        initialized = true
        MinecraftForge.EVENT_BUS.addListener(Consumer<TickEvent.ClientTickEvent> { event ->
            val client = Minecraft.getInstance()
            if (event.phase == TickEvent.Phase.START) {
                startCallbacks.forEach { it.onStartTick(client) }
            } else {
                endCallbacks.forEach { it.onEndTick(client) }
            }
        })
    }
}

object KeyMappingHelper {
    private val pending = mutableListOf<KeyMapping>()
    private var initialized = false

    fun registerKeyMapping(mapping: KeyMapping): KeyMapping {
        pending += mapping
        return mapping
    }

    fun init(event: RegisterKeyMappingsEvent) {
        if (initialized) return
        initialized = true
        pending.forEach { event.register(it) }
    }
}

object KeyBindingHelper {
    fun registerKeyBinding(mapping: KeyMapping): KeyMapping {
        return KeyMappingHelper.registerKeyMapping(mapping)
    }
}

object UseBlockCallback {
    fun interface OnUse {
        fun interact(player: Player, level: Level, hand: InteractionHand, hitResult: BlockHitResult): InteractionResult
    }

    private val callbacks = mutableListOf<OnUse>()
    private var initialized = false

    object EVENT {
        fun register(callback: OnUse) {
            callbacks += callback
        }
    }

    fun init() {
        if (initialized) return
        initialized = true
        MinecraftForge.EVENT_BUS.addListener(Consumer<PlayerInteractEvent.RightClickBlock> { event ->
            val result = callbacks
                .map { it.interact(event.entity, event.level, event.hand, event.hitVec) }
                .firstOrNull { it != InteractionResult.PASS }
            if (result != null && result != InteractionResult.PASS) {
                event.isCanceled = true
                event.cancellationResult = result
            }
        })
    }
}

object UseItemCallback {
    fun interface OnUse {
        fun interact(player: Player, level: Level, hand: InteractionHand): InteractionResult
    }

    private val callbacks = mutableListOf<OnUse>()
    private var initialized = false

    object EVENT {
        fun register(callback: OnUse) {
            callbacks += callback
        }
    }

    fun init() {
        if (initialized) return
        initialized = true
        MinecraftForge.EVENT_BUS.addListener(Consumer<PlayerInteractEvent.RightClickItem> { event ->
            val result = callbacks
                .map { it.interact(event.entity, event.level, event.hand) }
                .firstOrNull { it != InteractionResult.PASS }
            if (result != null && result != InteractionResult.PASS) {
                event.isCanceled = true
                event.cancellationResult = result
            }
        })
    }
}

object UseEntityCallback {
    fun interface OnUse {
        fun interact(
            player: Player,
            level: Level,
            hand: InteractionHand,
            entity: Entity,
            hitResult: EntityHitResult?
        ): InteractionResult
    }

    private val callbacks = mutableListOf<OnUse>()
    private var initialized = false

    object EVENT {
        fun register(callback: OnUse) {
            callbacks += callback
        }
    }

    fun init() {
        if (initialized) return
        initialized = true
        MinecraftForge.EVENT_BUS.addListener(Consumer<PlayerInteractEvent.EntityInteract> { event ->
            val result = callbacks
                .map { it.interact(event.entity, event.level, event.hand, event.target, null) }
                .firstOrNull { it != InteractionResult.PASS }
            if (result != null && result != InteractionResult.PASS) {
                event.isCanceled = true
                event.cancellationResult = result
            }
        })
    }
}
