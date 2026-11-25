package com.one_studio

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.util.InputUtil
import org.lwjgl.glfw.GLFW
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.util.math.BlockPos
import net.minecraft.util.registry.Registry
import net.minecraft.text.Text
import net.minecraft.text.TranslatableText
import net.minecraft.util.Identifier
import net.minecraft.util.math.MathHelper
import net.minecraft.util.Hand
import net.minecraft.util.ActionResult
import net.minecraft.util.TypedActionResult
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityType
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.mob.MobEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.BlockItem

import net.minecraft.item.Items

import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.math.Vec3d

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.jvm.JvmStatic

import com.one_studio.TargetPriority

import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback

object MidnightAssistClient : ClientModInitializer {
    private fun log(vararg args: Any?) {}


    private const val KEY_CATEGORY = "key.categories.midnight-assist.main"
    private val keyToggle = KeyBindingHelper.registerKeyBinding(KeyBinding("key.midnight-assist.toggle", GLFW.GLFW_KEY_J, KEY_CATEGORY))
    private val keyLock = KeyBindingHelper.registerKeyBinding(KeyBinding("key.midnight-assist.lock", GLFW.GLFW_KEY_N, KEY_CATEGORY))
    private val keySettings = KeyBindingHelper.registerKeyBinding(KeyBinding("key.midnight-assist.settings", GLFW.GLFW_KEY_K, KEY_CATEGORY))
    private val keyEntityList = KeyBindingHelper.registerKeyBinding(KeyBinding("key.midnight-assist.entity_list", GLFW.GLFW_KEY_O, KEY_CATEGORY))

    // Tick dispatch is driven by ClientTickMixin so the mod does not depend on
    // ClientTickEvents, which is missing from some Fabric API builds for 1.16.
    private val tickStartHandlers = ArrayList<(MinecraftClient) -> Unit>()
    private val tickEndHandlers = ArrayList<(MinecraftClient) -> Unit>()

    @JvmStatic
    fun onClientTickStart(client: MinecraftClient) {
        for (handler in tickStartHandlers) handler(client)
    }

    @JvmStatic
    fun onClientTickEnd(client: MinecraftClient) {
        for (handler in tickEndHandlers) handler(client)
    }

    private fun onTickStart(handler: (MinecraftClient) -> Unit) {
        tickStartHandlers += handler
    }

    private fun onTickEnd(handler: (MinecraftClient) -> Unit) {
        tickEndHandlers += handler
    }

    private var lockedTargetId: Int = -1
    private var isHunting: Boolean = false
    private var attackStartTime: Long = 0L
    private val MIN_ATTACK_DELAY = 100L

    private var meleeLockOnTargetId: Int = -1
    private val recentlyAttacked: MutableMap<Int, Long> = mutableMapOf()
    private val RECENTLY_ATTACKED_TIMEOUT = 5000L
    private var lockOnCamera: LockOnCamera? = null
    private var lastLockTargetId: Int = -1
    private var camInitialized = false
    private var prevCamYaw = 0f
    private var prevCamPitch = 0f
    private var curCamYaw = 0f
    private var curCamPitch = 0f
    private var meleeCameraRunning = false
    private var pendingAttack = false
    private var ticksSinceTargetAcquired = 0
    private var pendingTargetId: Int = -1
    private var prevAttackPressed: Boolean = false
    private var meleeUseReleaseTicks: Int = 0
    private val MELEE_LOCKON_GRACE_TICKS = 5
    private var pendingMeleeLockClicks: Int = 0
    private var meleeLockSuppressed: Boolean = false
    private var justAttacked: Boolean = false
    private var meleeTargetType: EntityType<*>? = null

    // Auto aim assist state
    private var assistTargetId: Int = -1
    private var assistLocked = false
    private var assistManualRelease = false
    private var nextSearchTicks: Int = -1
    private var lastAppliedYaw = 0f
    private var lastAppliedPitch = 0f

    private val NEXT_SEARCH_DELAY = 8

    // Block aim assist state
    private var blockAimAssistInitialized = false
    private var prevBlockCamYaw = 0f
    private var prevBlockCamPitch = 0f
    private var curBlockCamYaw = 0f
    private var curBlockCamPitch = 0f
    private var lastTargetedBlockPos: BlockPos? = null
    private var blockSpring: CameraSpring? = null

    private enum class ItemMode { NONE, MELEE, EMPTY, OTHER }

    override fun onInitializeClient() {
        log("MidnightAssistClient initialized")

        UseBlockCallback.EVENT.register { _, _, _, _ ->
            if (shouldCancelItemUse()) ActionResult.FAIL else ActionResult.PASS
        }
        UseItemCallback.EVENT.register { player, _, hand ->
            val stack = player.getStackInHand(hand)
            if (shouldCancelItemUse()) TypedActionResult.fail(stack) else TypedActionResult.pass(stack)
        }
        UseEntityCallback.EVENT.register { _, _, _, _, _ ->
            if (shouldCancelItemUse()) ActionResult.FAIL else ActionResult.PASS
        }

        onTickEnd { PresetWatcher.tick() }

        onTickStart { client ->
            val player = client.player ?: return@onTickStart
            val level = client.world ?: return@onTickStart

            if (!MidnightAssistConfig.data.globalEnabled) {
                resetCameraState()
                resetAssistState()
                return@onTickStart
            }

            val handProfile = MidnightAssistConfig.findWeaponProfile(player.getMainHandStack())
            if (handProfile != null && !handProfile.enabled) {
                resetCameraState()
                resetAssistState()
                return@onTickStart
            }

            recentlyAttacked.entries.removeAll { System.currentTimeMillis() - it.value > RECENTLY_ATTACKED_TIMEOUT }

            meleeCameraRunning = false

            val entityReach = if (player.abilities.creativeMode) 5.0 else 3.0

            if (!effMeleeLockOnEnabled()) pendingMeleeLockClicks = 0

            if (effMeleeLockOnEnabled()) {
                val usePressed = isUsePressed(client)
                val meleeLockAllowed = canActivateMeleeLock(player)
                if (meleeLockOnTargetId != -1 && !meleeLockAllowed) {
                    releaseMeleeLock()
                }
                if (effMeleeLockMode() == MeleeLockMode.TOGGLE) {
                    val clicks = pendingMeleeLockClicks
                    pendingMeleeLockClicks = 0
                    if (clicks > 0 && client.currentScreen == null) {
                        repeat(clicks) {
                            if (meleeLockOnTargetId != -1) {
                                log("Melee lock-on released")
                                releaseMeleeLock()
                                meleeLockSuppressed = false
                            } else if (!isHunting && meleeLockAllowed) {
                                acquireMeleeLock(client, player)
                            }
                        }
                    }
                } else {
                    pendingMeleeLockClicks = 0
                    if (usePressed) {
                        meleeUseReleaseTicks = 0
                        if (meleeLockOnTargetId == -1 && !isHunting && !meleeLockSuppressed && meleeLockAllowed) {
                            acquireMeleeLock(client, player)
                        }
                    } else {
                        meleeLockSuppressed = false
                        if (meleeLockOnTargetId != -1) {
                            meleeUseReleaseTicks++
                            if (meleeUseReleaseTicks >= MELEE_LOCKON_GRACE_TICKS) {
                                log("Melee lock-on released")
                                releaseMeleeLock()
                            }
                        }
                    }
                }

                if (meleeLockOnTargetId != -1) {
                    val target = level.getEntityById(meleeLockOnTargetId)
                    if (target == null || !target.isAlive || target.distanceTo(player) > entityReach || !player.canSee(target)) {
                        handleMeleeTargetGone(client, player)
                    }
                }
            }

            val meleeTarget = if (effMeleeLockOnEnabled() && meleeLockOnTargetId != -1)
                level.getEntityById(meleeLockOnTargetId) else null

            var currentTarget: Entity? = null

            if (meleeTarget != null && meleeTarget.isAlive) {
                currentTarget = meleeTarget
                lockedTargetId = meleeTarget.entityId
                isHunting = true
            } else if (lockedTargetId != -1) {
                val entity = level.getEntityById(lockedTargetId)
                if (entity != null && entity.isAlive && entity.distanceTo(player) <= entityReach && player.canSee(entity)) {
                    currentTarget = entity
                } else {
                    lockedTargetId = -1
                    isHunting = false
                    pendingAttack = false
                }
            }

            if (pendingTargetId != -1 && currentTarget == null) {
                val target = level.getEntityById(pendingTargetId)
                if (target != null && target.isAlive && target.distanceTo(player) <= 5.0 && player.canSee(target)) {
                    currentTarget = target
                    lockedTargetId = target.entityId
                    isHunting = true
                    attackStartTime = System.currentTimeMillis()
                    ticksSinceTargetAcquired = 0
                    log("Acquired intercepted target: {} (id={})", target.name.string, target.entityId)
                } else {
                    pendingTargetId = -1
                    pendingAttack = false
                }
            }

            val realPressed = isAttackPressed(client)
            if (realPressed && !prevAttackPressed) {
                pendingAttack = true
            }
            prevAttackPressed = realPressed

            val realAttackPressed = realPressed || pendingAttack

            if (currentTarget == null && realAttackPressed) {
                val hitEntity = client.targetedEntity
                var hasEntity = false
                if (hitEntity != null) {
                    if (hitEntity.isAlive && MidnightAssistConfig.isEntityEnabled(hitEntity) && hitEntity !is PlayerEntity) {
                        hasEntity = true
                    }
                }
                if (!hasEntity) {
                    currentTarget = findTarget(client, 5.0)
                    if (currentTarget != null) {
                        val newTargetId = currentTarget.entityId
                        val isNewTarget = lockedTargetId != newTargetId
                        lockedTargetId = newTargetId
                        isHunting = true
                        attackStartTime = System.currentTimeMillis()
                        if (isNewTarget) ticksSinceTargetAcquired = 0
                        log("Found target via findTarget: {} (id={})", currentTarget.name.string, currentTarget.entityId)
                    }
                }
            }

            if (currentTarget == null && isHunting) {
                isHunting = false
                lockedTargetId = -1
                lockOnCamera = null
                lastLockTargetId = -1
                pendingAttack = false
                ticksSinceTargetAcquired = 0
                justAttacked = false
            }

            if (currentTarget == null && pendingAttack) {
                pendingAttack = false
            }

            if (lockedTargetId == -1 && meleeLockOnTargetId == -1) {
                camInitialized = false
            }

            if (effMeleeLockOnEnabled() && meleeLockOnTargetId != -1) {
                val target = level.getEntityById(meleeLockOnTargetId)
                if (target != null && target.isAlive && target.distanceTo(player) <= entityReach && player.canSee(target)) {
                    lockedCameraSoft(player, target)
                    meleeCameraRunning = true
                    camInitialized = true
                } else {
                    meleeLockOnTargetId = -1
                    lockOnCamera = null
                    lastLockTargetId = -1
                    camInitialized = false
                }
            }

            if (!meleeCameraRunning && currentTarget != null) {
                lockedCameraSoft(player, currentTarget)
                camInitialized = true
            }

            // Block aim assist
            if (MidnightAssistConfig.data.blockAimAssistEnabled && !isHunting && meleeLockOnTargetId == -1) {
                updateBlockAimAssist(client, player, level)
            } else {
                blockAimAssistInitialized = false
                lastTargetedBlockPos = null
            }

            if (isHunting) {
                ticksSinceTargetAcquired++
            }
        }

        onTickStart { client ->
            handleKeybinds(client)
            assistTick(client)
        }

        onTickEnd { client ->
            val player = client.player ?: return@onTickEnd
            val gameMode = client.interactionManager ?: return@onTickEnd

            val entityReach = if (player.abilities.creativeMode) 5.0 else 3.0

            val attackHeld = isAttackPressed(client)

            if (isHunting && lockedTargetId != -1 && (pendingAttack || attackHeld)) {
                val target = client.world?.getEntityById(lockedTargetId)
                if (target != null && target.isAlive && target.distanceTo(player) <= entityReach) {
                    val onTarget = isTargetUnderCrosshair(client, target)
                    val cameraAligned = isCameraAligned(player, target)
                    val cooldown = player.getAttackCooldownProgress(0.0f)
                    if (attackStartTime == 0L) attackStartTime = System.currentTimeMillis()
                    val elapsed = System.currentTimeMillis() - attackStartTime

                    val isFirstAttack = ticksSinceTargetAcquired <= 3
                    val maxAlignTicks = 20
                    val canAttack = onTarget && cameraAligned && cooldown >= 1.0f &&
                        (if (isFirstAttack) ticksSinceTargetAcquired >= 3 else elapsed > MIN_ATTACK_DELAY)

                    val shouldRelease = ticksSinceTargetAcquired > maxAlignTicks && !cameraAligned

                    if (canAttack) {
                        log("ATTACK! target={} (id={}) onTarget={} cameraAligned={} cooldown={} elapsed={} ticks={}", target.name.string, target.entityId, onTarget, cameraAligned, cooldown, elapsed, ticksSinceTargetAcquired)

                        gameMode.attackEntity(player, target)
                        player.swingHand(Hand.MAIN_HAND)
                        recentlyAttacked[target.entityId] = System.currentTimeMillis()
                        attackStartTime = System.currentTimeMillis()
                        pendingAttack = false
                        justAttacked = true

                        if (!attackHeld && meleeLockOnTargetId == -1) {
                            isHunting = false
                            lockedTargetId = -1
                            lockOnCamera = null
                            lastLockTargetId = -1
                            camInitialized = false
                            pendingAttack = false
                            pendingTargetId = -1
                            ticksSinceTargetAcquired = 0
                            justAttacked = false
                        }
                    } else if (shouldRelease && meleeLockOnTargetId == -1) {
                        log("Releasing target - couldn't align camera after {} ticks", maxAlignTicks)
                        isHunting = false
                        lockedTargetId = -1
                        lockOnCamera = null
                        lastLockTargetId = -1
                        camInitialized = false
                        pendingAttack = false
                        pendingTargetId = -1
                        ticksSinceTargetAcquired = 0
                    } else {
                        if (elapsed % 200L < 50L) log("Waiting: onTarget={} cameraAligned={} cooldown={} elapsed={} ticks={}", onTarget, cameraAligned, cooldown, elapsed, ticksSinceTargetAcquired)
                    }
                } else {
                    log("Target lost: id={}", lockedTargetId)
                    isHunting = false
                    lockedTargetId = -1
                    lockOnCamera = null
                    lastLockTargetId = -1
                    pendingAttack = false
                    pendingTargetId = -1
                    ticksSinceTargetAcquired = 0
                }
            }

            // Deferred release: after a single-click attack, release the target
            // once the attack key is no longer held (fixes "keeps tracking after hit")
            if (justAttacked && !attackHeld && meleeLockOnTargetId == -1 && lockedTargetId != -1) {
                isHunting = false
                lockedTargetId = -1
                lockOnCamera = null
                lastLockTargetId = -1
                camInitialized = false
                pendingAttack = false
                pendingTargetId = -1
                ticksSinceTargetAcquired = 0
                justAttacked = false
            }
            if (justAttacked && attackHeld) {
                // keep tracking while holding; do not release yet
            }
            if (!attackHeld && !isHunting) {
                justAttacked = false
            }
        }

        onTickEnd { client ->
            val player = client.player ?: return@onTickEnd
            val gameMode = client.interactionManager ?: return@onTickEnd

            if (!MidnightAssistConfig.data.globalEnabled) return@onTickEnd
            val handProfile = MidnightAssistConfig.findWeaponProfile(player.getMainHandStack())
            if (handProfile != null && !handProfile.enabled) return@onTickEnd
            if (isHunting || lockedTargetId != -1 || meleeLockOnTargetId != -1) return@onTickEnd

            val realPressed = isAttackPressed(client)
            if (!realPressed) return@onTickEnd

            val hitEntity = client.targetedEntity
            if (hitEntity != null) {
                if (hitEntity.isAlive && hitEntity !is PlayerEntity) {
                    val cooldown = player.getAttackCooldownProgress(0.0f)
                    if (cooldown >= 1.0f && !gameMode.isBreakingBlock()) {
                        gameMode.attackEntity(player, hitEntity)
                        player.swingHand(Hand.MAIN_HAND)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Keybinds
    // ------------------------------------------------------------------

    private fun handleKeybinds(client: MinecraftClient) {
        while (keyToggle.wasPressed()) {
            val enabled = !MidnightAssistConfig.data.globalEnabled
            MidnightAssistConfig.data.globalEnabled = enabled
            MidnightAssistConfig.save()
            if (!enabled) {
                resetAssistState()
            }
            client.player?.sendOverlayMessage(
                TranslatableText(if (enabled) "chat.midnight-assist.enabled" else "chat.midnight-assist.disabled")
            )
        }
        while (keyLock.wasPressed()) {
            toggleTargetLock(client)
        }
        while (keySettings.wasPressed()) {
            if (client.currentScreen == null) {
                // client.openScreen(MidnightAssistModMenu.createConfigScreen(null))
            }
        }
        while (keyEntityList.wasPressed()) {
            if (client.currentScreen == null) {
                // client.openScreen(EntityListScreen(null))
            }
        }
    }

    private fun toggleTargetLock(client: MinecraftClient) {
        val player = client.player ?: return
        if (assistTargetId != -1) {
            assistLocked = !assistLocked
            player.sendOverlayMessage(
                TranslatableText(if (assistLocked) "chat.midnight-assist.locked" else "chat.midnight-assist.unlocked")
            )
            return
        }
        val hit = client.targetedEntity
        if (hit != null && hit.isAlive && MidnightAssistConfig.isEntityEnabled(hit)) {
            acquireTarget(player, hit)
            assistLocked = true
            player.sendOverlayMessage(TranslatableText("chat.midnight-assist.locked"))
        }
    }

    // ------------------------------------------------------------------
    // Aim assist
    // ------------------------------------------------------------------

    private fun assistTick(client: MinecraftClient) {
        val player = client.player ?: return
        val level = client.world ?: return

        if (!MidnightAssistConfig.data.globalEnabled) {
            return
        }
        if (client.currentScreen != null || player.isSpectator) {
            if (assistTargetId != -1) {
                cancelTracking()
            }
            return
        }

        val handProfile = MidnightAssistConfig.findWeaponProfile(player.getMainHandStack())
        if (handProfile != null && !handProfile.enabled) {
            if (assistTargetId != -1) {
                cancelTracking()
            }
            return
        }

        // Melee lock-on / hunting takes priority over the soft assist
        if (isHunting || meleeLockOnTargetId != -1) {
            if (assistTargetId != -1) {
                cancelTracking()
            }
            return
        }

        val mode = currentItemMode(player)

        var target = if (assistTargetId != -1) level.getEntityById(assistTargetId) else null

        if (target != null && (!target.isAlive || target.removed || target.world !== level)) {
            onTargetKilled(player)
            target = null
        }

        val t = target
        if (t != null && !isStillValidTarget(player, t)) {
            releaseTarget(manual = false)
            target = null
        }

        if (target == null && nextSearchTicks > 0) {
            nextSearchTicks--
            if (nextSearchTicks <= 0) {
                nextSearchTicks = -1
                val next = if (mode != ItemMode.NONE)
                    findAssistTarget(client, player, effAimAssistDistance().toDouble())
                else null
                if (next != null) {
                    acquireTarget(player, next)
                } else {
                    releaseTarget(manual = false)
                }
            }
        }

        if (target == null && assistTargetId == -1 && nextSearchTicks < 0 && mode != ItemMode.NONE) {
            val found = findAssistTarget(client, player, effAimAssistDistance().toDouble())
            if (found != null) {
                acquireTarget(player, found)
            }
        }

        target = if (assistTargetId != -1) level.getEntityById(assistTargetId) else null

        val curTarget = target
        if (curTarget != null) {
            val point = aimPoint(player, curTarget)
            if (!assistLocked && angleTo(player, point) > releaseAngle()) {
                releaseTarget(manual = true)
            }
        }
    }

    private fun acquireTarget(player: PlayerEntity, target: Entity) {
        assistTargetId = target.entityId
        assistManualRelease = false
        nextSearchTicks = -1
        lastAppliedYaw = player.yaw
        lastAppliedPitch = player.pitch
    }

    private fun onTargetKilled(player: PlayerEntity) {
        assistTargetId = -1
        if (!assistManualRelease) {
            nextSearchTicks = NEXT_SEARCH_DELAY
        } else {
            releaseTarget(manual = false)
        }
    }

    private fun releaseTarget(manual: Boolean) {
        assistTargetId = -1
        nextSearchTicks = -1
        assistManualRelease = manual
        if (manual) {
            assistLocked = false
        }
        camInitialized = false
        lockOnCamera = null
        lastLockTargetId = -1
    }

    private fun cancelTracking() {
        assistTargetId = -1
        nextSearchTicks = -1
        assistLocked = false
        camInitialized = false
        lockOnCamera = null
        lastLockTargetId = -1
    }

    private fun resetAssistState() {
        assistTargetId = -1
        assistLocked = false
        assistManualRelease = false
        nextSearchTicks = -1
    }

    private fun angleTo(player: PlayerEntity, point: Vec3d): Double {
        val eye = player.getCameraPosVec(1.0f)
        val dir = point.subtract(eye)
        if (dir.lengthSquared() < 1.0E-6) return 0.0
        val dot = player.getRotationVec(1.0f).dotProduct(dir.normalize()).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(dot))
    }

    private fun releaseAngle(): Double {
        val fov = effAimAssistDistance().toDouble()
        if (fov >= 360.0) return 180.0
        return (fov / 2.0).coerceAtLeast(5.0) + 15.0
    }

    private fun assistRange(): Double = 4.5

    private fun activeProfile(): WeaponProfile? {
        val player = MinecraftClient.getInstance().player ?: return null
        return MidnightAssistConfig.findWeaponProfile(player.getMainHandStack())
    }

    private fun effAimSpeed(): Double =
        activeProfile()?.aimSpeed ?: MidnightAssistConfig.data.aimSpeed

    private fun effAimPoint(): AimPoint =
        activeProfile()?.aimPoint ?: MidnightAssistConfig.data.aimPoint

    private fun effTargetPriority(): TargetPriority =
        activeProfile()?.targetPriority ?: MidnightAssistConfig.data.targetPriority

    private fun effAimAssistDistance(): Int =
        activeProfile()?.aimAssistDistance ?: MidnightAssistConfig.data.aimAssistDistance

    private fun effMeleeLockOnEnabled(): Boolean {
        val profile = activeProfile() ?: return MidnightAssistConfig.data.meleeLockOnEnabled
        if (!profile.enabled) return false
        return profile.meleeLockOnEnabled
    }

    private fun effMeleeLockOnBlockUse(): Boolean =
        activeProfile()?.meleeLockOnBlockUse ?: MidnightAssistConfig.data.meleeLockOnBlockUse

    private fun effMeleeLockMode(): MeleeLockMode {
        val profileMode = activeProfile()?.meleeLockMode
        if (profileMode != null && profileMode != MeleeLockMode.GLOBAL) {
            return profileMode
        }
        val globalMode = MidnightAssistConfig.data.meleeLockMode
        return if (globalMode == MeleeLockMode.GLOBAL) MeleeLockMode.HOLD else globalMode
    }

    private fun effMeleeRetarget(): MeleeRetarget =
        activeProfile()?.meleeRetarget ?: MidnightAssistConfig.data.meleeRetarget

    private fun canActivateMeleeLock(player: ClientPlayerEntity): Boolean {
        if (player.isUsingItem) return false
        val stack = player.getMainHandStack()
        if (stack.isEmpty) return true
        if (stack.item is BlockItem) return false
        val id = Registry.ITEM.getId(stack.item).path
        if (id == "bow" || id == "crossbow") return false
        return true
    }

    private fun acquireMeleeLock(client: MinecraftClient, player: ClientPlayerEntity) {
        if (!canActivateMeleeLock(player)) {
            return
        }
        val lockOnTarget = findTarget(client, 5.0, ignoreFov = false, coneAngle = effAimAssistDistance().toDouble())
        if (lockOnTarget == null) {
            return
        }
        meleeLockOnTargetId = lockOnTarget.entityId
        meleeTargetType = lockOnTarget.type
        meleeLockSuppressed = false
        log("Melee lock-on target: {} (id={})", lockOnTarget.name.string, lockOnTarget.entityId)
    }

    private fun handleMeleeTargetGone(client: MinecraftClient, player: ClientPlayerEntity) {
        log("Melee lock-on target lost")
        if (effMeleeRetarget() != MeleeRetarget.STOP_AFTER_DEATH && retargetMelee(client, player)) return
        releaseMeleeLock()
        if (effMeleeLockMode() == MeleeLockMode.HOLD) {
            meleeLockSuppressed = true
        }
    }

    private fun retargetMelee(client: MinecraftClient, player: ClientPlayerEntity): Boolean {
        val next = when (effMeleeRetarget()) {
            MeleeRetarget.SWITCH_SAME_SPECIES -> meleeTargetType?.let { findSameSpeciesTarget(client, player, it, 5.0) }
            MeleeRetarget.SWITCH_PRIORITIZED -> findTarget(client, 5.0, ignoreFov = true)
            else -> null
        } ?: return false
        if (!next.isAlive) return false
        meleeLockOnTargetId = next.entityId
        meleeTargetType = next.type
        lockOnCamera = null
        lastLockTargetId = -1
        camInitialized = false
        log("Melee lock-on switched to: {} (id={})", next.name.string, next.entityId)
        return true
    }

    private fun findSameSpeciesTarget(client: MinecraftClient, player: ClientPlayerEntity, type: EntityType<*>, range: Double): Entity? {
        val level = client.world ?: return null
        var best: Entity? = null
        var bestDistance = Double.MAX_VALUE
        for (entity in level.getOtherEntities(player, player.getBoundingBox().expand(range), { true })) {
            if (entity === player || !entity.isAlive || entity.type !== type) continue
            if (!MidnightAssistConfig.isEntityEnabled(entity)) continue
            val distance = entity.distanceTo(player).toDouble()
            if (distance > range || distance >= bestDistance) continue
            if (!player.canSee(entity)) continue
            best = entity
            bestDistance = distance
        }
        return best
    }

    private fun releaseMeleeLock() {
        meleeLockOnTargetId = -1
        meleeTargetType = null
        lockedTargetId = -1
        lockOnCamera = null
        lastLockTargetId = -1
        isHunting = false
        pendingAttack = false
        pendingTargetId = -1
        camInitialized = false
    }

    private fun isStillValidTarget(player: PlayerEntity, target: Entity): Boolean {
        val range = assistRange()
        if (player.distanceTo(target) > range * 1.6) return false
        if (assistLocked) {
            return player.distanceTo(target) <= range * 2.5
        }
        return player.canSee(target)
    }

    private fun currentItemMode(player: PlayerEntity): ItemMode {
        if (player.isSpectator) return ItemMode.NONE
        val stack = player.getMainHandStack()
        val profile = MidnightAssistConfig.findWeaponProfile(stack)
        if (stack.isEmpty) {
            return if (profile != null && profile.enabled) ItemMode.EMPTY else ItemMode.NONE
        }
        if (profile != null && !profile.enabled) return ItemMode.NONE
        val id = Registry.ITEM.getId(stack.item).path
        return when {
            isMeleeId(id) -> ItemMode.MELEE
            profile != null -> ItemMode.OTHER
            else -> ItemMode.NONE
        }
    }

    private fun isMeleeId(id: String): Boolean {
        return id.contains("sword") || id.contains("axe") || id.contains("trident") ||
            id.contains("mace") || id.contains("spear")
    }

    private fun aimPoint(player: PlayerEntity, target: Entity): Vec3d {
        val bb = target.getBoundingBox()
        val center = bb.center
        val height = bb.maxY - bb.minY
        val head = Vec3d(center.x, target.eyeY - 0.1, center.z)
        val torso = Vec3d(center.x, bb.minY + height * 0.62, center.z)
        val legs = Vec3d(center.x, bb.minY + height * 0.22, center.z)
        return when (effAimPoint()) {
            AimPoint.CENTER -> center
            AimPoint.UPPER_BODY -> Vec3d(center.x, bb.minY + height * 0.8, center.z)
            AimPoint.HEAD -> head
            AimPoint.DYNAMIC -> nearestBodyPart(player, head, torso, legs)
        }
    }

    private fun nearestBodyPart(player: PlayerEntity, vararg parts: Vec3d): Vec3d {
        val eye = player.getCameraPosVec(1.0f)
        val look = player.getRotationVec(1.0f)
        var best = parts[0]
        var bestAngle = Double.MAX_VALUE
        for (part in parts) {
            val dir = part.subtract(eye)
            if (dir.lengthSquared() < 1.0E-6) return part
            val angle = acos(look.dotProduct(dir.normalize()).coerceIn(-1.0, 1.0))
            if (angle < bestAngle) {
                bestAngle = angle
                best = part
            }
        }
        return best
    }

    private fun findAssistTarget(client: MinecraftClient, player: PlayerEntity, coneAngle: Double): Entity? {
        val level = client.world ?: return null
        val range = assistRange()
        val entities = level.getOtherEntities(player, player.getBoundingBox().expand(range), { true })
        val halfCone = coneAngle / 2.0

        var best: Entity? = null
        var bestScore = Double.MAX_VALUE

        for (entity in entities) {
            if (entity === player || !entity.isAlive) continue
            if (!MidnightAssistConfig.isEntityEnabled(entity)) continue
            val dist = player.distanceTo(entity).toDouble()
            if (dist > range) continue
            if (!player.canSee(entity)) continue

            if (coneAngle < 360.0) {
                val angle = angleTo(player, entity.getBoundingBox().getCenter())
                if (angle > halfCone) continue
            }

            val angle = angleTo(player, entity.getBoundingBox().getCenter())
            val score = scoreTarget(player, entity, angle, dist)
            if (score < bestScore) {
                bestScore = score
                best = entity
            }
        }
        return best
    }

    private fun scoreTarget(player: PlayerEntity, entity: Entity, angle: Double, dist: Double): Double {
        val health = (entity as? LivingEntity)?.health?.toDouble() ?: 20.0
        val threat = threatLevel(player, entity)
        val now = System.currentTimeMillis()

        val base = when (effTargetPriority()) {
            TargetPriority.CROSSHAIR -> angle + dist * 0.01
            TargetPriority.NEAREST -> dist
            TargetPriority.LOWEST_HEALTH -> health * 4.0 + dist * 0.05
            TargetPriority.THREAT -> dist * (1.0 - 0.5 * threat) + (20.0 - health) * 0.02
            TargetPriority.BALANCED -> angle * 0.8 + dist * 0.6 + (20.0 - health) * 0.15 - threat * 8.0
            TargetPriority.FARTHEST -> -dist
            TargetPriority.WEAKEST -> health + dist * 0.01
            TargetPriority.STRONGEST -> -(health) + dist * 0.01
            TargetPriority.LOOKING_AT_YOU -> if (entity is MobEntity && entity.target === player) dist * 0.5 else dist * 2.0
            TargetPriority.RECENTLY_ATTACKED -> {
                val lastAttack = recentlyAttacked[entity.entityId]
                if (lastAttack != null && now - lastAttack < RECENTLY_ATTACKED_TIMEOUT) dist * 0.3 else dist * 2.0
            }
        }
        return if (MidnightAssistConfig.isPriorityEntity(entity)) base * 0.5 else base
    }

    private fun threatLevel(player: PlayerEntity, entity: Entity): Double {
        if (entity is MobEntity) {
            if (entity.target === player) return 1.0
            return if (entity.type.spawnGroup === net.minecraft.entity.SpawnGroup.MONSTER) 0.6 else 0.2
        }
        return 0.1
    }

    // ------------------------------------------------------------------
    // Existing helpers
    // ------------------------------------------------------------------

    private fun isUsePressed(client: MinecraftClient): Boolean {
        if (client.currentScreen != null) return false
        return client.options.keyUse.isPressed
    }

    private fun isAttackPressed(client: MinecraftClient): Boolean {
        return client.options.keyAttack.isPressed
    }

    private fun isTargetUnderCrosshair(client: MinecraftClient, target: Entity): Boolean {
        val player = client.player ?: return false
        val reach = 5.0
        val cameraPos = player.getCameraPosVec(1.0f)
        val rotation = player.getRotationVec(1.0f)
        val endPos = cameraPos.add(rotation.x * reach, rotation.y * reach, rotation.z * reach)
        val box = target.getBoundingBox().expand(0.1)
        val hit = box.raycast(cameraPos, endPos)
        return hit.isPresent
    }

    private fun isCameraAligned(player: PlayerEntity, target: Entity): Boolean {
        val playerPos = player.getCameraPosVec(1.0f)
        val targetPos = aimPoint(player, target)
        val dirToTarget = targetPos.subtract(playerPos).normalize()
        val playerRotation = player.getRotationVec(1.0f)
        val dot = playerRotation.dotProduct(dirToTarget)
        return dot > 0.98
    }

    private fun findTarget(client: MinecraftClient, range: Double, ignoreFov: Boolean = false, coneAngle: Double = -1.0): Entity? {
        val player = client.player ?: return null
        val level = client.world ?: return null
        val entities = level.getOtherEntities(player, player.getBoundingBox().expand(range), { true })

        val priority = effTargetPriority()
        val fov = if (coneAngle >= 0.0) coneAngle else effAimAssistDistance().toDouble()
        val now = System.currentTimeMillis()

        var bestTarget: Entity? = null
        var bestScore = Double.MAX_VALUE

        for (entity in entities) {
            if (entity is PlayerEntity || !entity.isAlive) continue
            if (!MidnightAssistConfig.isEntityEnabled(entity)) continue
            val dist = entity.distanceTo(player).toDouble()
            if (dist > range) continue
            if (!player.canSee(entity)) continue
            if (!ignoreFov && fov < 360.0 && angleTo(player, entity.getBoundingBox().getCenter()) > fov / 2.0) continue

            val score = when (priority) {
                TargetPriority.NEAREST, TargetPriority.CROSSHAIR -> dist
                TargetPriority.FARTHEST -> -dist
                TargetPriority.WEAKEST, TargetPriority.LOWEST_HEALTH -> {
                    if (entity is LivingEntity) entity.health.toDouble() + dist * 0.01 else dist
                }
                TargetPriority.STRONGEST, TargetPriority.BALANCED -> {
                    if (entity is LivingEntity) -(entity.health.toDouble()) + dist * 0.01 else -dist
                }
                TargetPriority.LOOKING_AT_YOU, TargetPriority.THREAT -> {
                    val isLooking = if (entity is MobEntity) entity.target == player else false
                    if (isLooking) dist * 0.5 else dist * 2.0
                }
                TargetPriority.RECENTLY_ATTACKED -> {
                    val lastAttack = recentlyAttacked[entity.entityId]
                    if (lastAttack != null) {
                        val elapsed = now - lastAttack
                        if (elapsed < RECENTLY_ATTACKED_TIMEOUT) dist * 0.3 else dist * 2.0
                    } else dist * 2.0
                }
            }

            if (score < bestScore) {
                bestScore = score
                bestTarget = entity
            }
        }
        return bestTarget
    }

    private fun lockedCameraSoft(player: PlayerEntity, target: Entity) {
        val playerPos = Vec3df(player.x.toFloat(), player.eyeY.toFloat(), player.z.toFloat())
        val aim = aimPoint(player, target)
        val targetPos = Vec3df(aim.x.toFloat(), aim.y.toFloat(), aim.z.toFloat())
        val vel = target.velocity
        val targetVel = Vec3df(vel.x.toFloat() * 20f, vel.y.toFloat() * 20f, vel.z.toFloat() * 20f)
        val aimSpeed = effAimSpeed().toFloat()
        if (lockOnCamera == null || target.entityId != lastLockTargetId || !camInitialized) {
            val predicted = LockOnCamera.predictTarget(targetPos, targetVel)
            lockOnCamera = LockOnCamera(predicted, player.yaw, player.pitch, aimSpeed)
            lastLockTargetId = target.entityId
            if (!camInitialized) {
                prevCamYaw = player.yaw
                prevCamPitch = player.pitch
                curCamYaw = player.yaw
                curCamPitch = player.pitch
                camInitialized = true
            }
        }
        val cam = lockOnCamera ?: return
        cam.update(playerPos, targetPos, targetVel, 0.05f)
        prevCamYaw = curCamYaw
        prevCamPitch = curCamPitch
        curCamYaw = cam.yaw
        curCamPitch = cam.pitch
    }

    private fun updateBlockAimAssist(client: MinecraftClient, player: PlayerEntity, level: net.minecraft.client.world.ClientWorld) {
        val range = MidnightAssistConfig.data.blockAimAssistRange
        val hitResult = client.crosshairTarget

        if (hitResult is BlockHitResult) {
            val blockPos = hitResult.blockPos
            val targetVec = Vec3d(blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5)
            val dx = player.x - targetVec.x
            val dy = player.y - targetVec.y
            val dz = player.z - targetVec.z
            val dist = Math.sqrt(dx * dx + dy * dy + dz * dz)

            if (dist <= range) {
                if (!blockAimAssistInitialized || lastTargetedBlockPos != blockPos) {
                    if (!blockAimAssistInitialized) {
                        prevBlockCamYaw = player.yaw
                        prevBlockCamPitch = player.pitch
                        curBlockCamYaw = player.yaw
                        curBlockCamPitch = player.pitch
                        blockAimAssistInitialized = true
                    }
                    lastTargetedBlockPos = blockPos

                    if (blockSpring == null || lastTargetedBlockPos != blockPos || !blockAimAssistInitialized) {
                        val targetVec3df = Vec3df(targetVec.x.toFloat(), targetVec.y.toFloat(), targetVec.z.toFloat())
                        blockSpring = CameraSpring(targetVec3df, MidnightAssistConfig.effectiveAimSpeed().toFloat())
                        lastTargetedBlockPos = blockPos
                    }

                    val playerPos = Vec3df(player.x.toFloat(), player.eyeY.toFloat(), player.z.toFloat())
                    val targetVec3df = Vec3df(targetVec.x.toFloat(), targetVec.y.toFloat(), targetVec.z.toFloat())
                    val spring = blockSpring ?: return

                    spring.update(targetVec3df, 0.05f)

                    val dir = spring.position.sub(playerPos)
                    val targetYaw = Math.toDegrees(Math.atan2(dir.z.toDouble(), dir.x.toDouble())).toFloat() - 90f
                    val targetPitch = -Math.toDegrees(
                        Math.atan2(dir.y.toDouble(), sqrt((dir.x * dir.x + dir.z * dir.z).toDouble()))
                    ).toFloat()

                    curBlockCamYaw = dampAngle(curBlockCamYaw, targetYaw, 0.05f)
                    curBlockCamPitch = dampAngle(curBlockCamPitch, targetPitch, 0.05f)
                }
            } else {
                blockAimAssistInitialized = false
                blockSpring = null
                lastTargetedBlockPos = null
            }
        } else {
            blockAimAssistInitialized = false
            blockSpring = null
            lastTargetedBlockPos = null
        }
    }

    private fun dampAngle(current: Float, target: Float, dt: Float): Float {
        var diff = target - current
        while (diff > 180f) diff -= 360f
        while (diff < -180f) diff += 360f
        val rotSpeed = (MidnightAssistConfig.effectiveAimSpeed().toFloat() * 40f).coerceAtLeast(0.01f)
        return current + diff * (1f - exp((-rotSpeed * dt).toDouble()).toFloat())
    }

    @JvmStatic
    fun onUseKeyClick(key: InputUtil.Key) {
        val client = MinecraftClient.getInstance() ?: return
        if (client.currentScreen != null) return
        if (client.options.keyUse.getBoundKeyTranslationKey() != key.getTranslationKey()) return
        pendingMeleeLockClicks++
    }

    private fun resetCameraState() {
        lockedTargetId = -1
        isHunting = false
        meleeLockOnTargetId = -1
        camInitialized = false
        lastLockTargetId = -1
        lockOnCamera = null
        pendingAttack = false
        pendingTargetId = -1
        prevAttackPressed = false
        meleeUseReleaseTicks = 0
        pendingMeleeLockClicks = 0
        meleeLockSuppressed = false
        meleeTargetType = null
        blockAimAssistInitialized = false
        lastTargetedBlockPos = null
        blockSpring = null
        justAttacked = false
    }

    @JvmStatic
    fun tryInterceptAttack(client: MinecraftClient): Boolean {
        if (!MidnightAssistConfig.data.globalEnabled) return false
        val player = client.player ?: return false
        val level = client.world ?: return false
        val handProfile = MidnightAssistConfig.findWeaponProfile(player.getMainHandStack())
        if (handProfile != null && !handProfile.enabled) return false
        if (meleeLockOnTargetId != -1) {
            val target = level.getEntityById(meleeLockOnTargetId)
            if (target != null && target.isAlive) {
                if (lockedTargetId != target.entityId || !isHunting) {
                    lockedTargetId = target.entityId
                    isHunting = true
                    attackStartTime = System.currentTimeMillis()
                    ticksSinceTargetAcquired = 0
                    log("Attack during melee lock-on: {} (id={})", target.name.string, target.entityId)
                }
                pendingAttack = true
                return true
            }
        }
        if (isHunting) return true
        if (client.targetedEntity != null) return false
        val target = findTarget(client, 5.0, coneAngle = effAimAssistDistance().toDouble()) ?: return false
        lockedTargetId = target.entityId
        isHunting = true
        pendingAttack = true
        attackStartTime = System.currentTimeMillis()
        ticksSinceTargetAcquired = 0
        log("Intercepted attack, hunting: {} (id={})", target.name.string, target.entityId)
        return true
    }

    @JvmStatic
    fun shouldSuppressBlockBreak(): Boolean {
        if (!MidnightAssistConfig.data.globalEnabled) return false
        return isHunting || meleeLockOnTargetId != -1
    }

    @JvmStatic
    fun shouldCancelItemUse(): Boolean {
        val instance = MinecraftClient.getInstance()
        val player = instance.player ?: return false
        if (instance.currentScreen != null) return false
        val mainHand = player.getMainHandStack()
        val offHand = player.getOffHandStack()
        if (mainHand.item === Items.SHIELD || offHand.item === Items.SHIELD) return false
        if (!effMeleeLockOnBlockUse()) return false
        return instance.options.keyUse.isPressed && meleeLockOnTargetId != -1 && effMeleeLockOnEnabled() && MidnightAssistConfig.data.globalEnabled
    }

    @JvmStatic
    fun applyPartialTicks(partialTicks: Float) {
        val player = MinecraftClient.getInstance().player ?: return
        if (!MidnightAssistConfig.data.globalEnabled) return

        // Entity aim assist has priority
        if (camInitialized) {
            val yawDiff = MathHelper.wrapDegrees(curCamYaw - prevCamYaw)
            val yaw = prevCamYaw + yawDiff * partialTicks
            val pitch = prevCamPitch + (curCamPitch - prevCamPitch) * partialTicks
            player.yaw = yaw
            player.pitch = pitch
            player.prevYaw = yaw
            player.prevPitch = pitch
            lastAppliedYaw = yaw
            lastAppliedPitch = pitch
        }
        // Block aim assist (only when not using entity aim assist)
        else if (blockAimAssistInitialized) {
            val yawDiff = MathHelper.wrapDegrees(curBlockCamYaw - prevBlockCamYaw)
            val yaw = prevBlockCamYaw + yawDiff * partialTicks
            val pitch = prevBlockCamPitch + (curBlockCamPitch - prevBlockCamPitch) * partialTicks
            player.yaw = yaw
            player.pitch = pitch
            player.prevYaw = yaw
            player.prevPitch = pitch
        }
    }
}

private data class Vec3df(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f) {
    fun add(o: Vec3df) = Vec3df(x + o.x, y + o.y, z + o.z)
    fun sub(o: Vec3df) = Vec3df(x - o.x, y - o.y, z - o.z)
    fun mul(s: Float) = Vec3df(x * s, y * s, z * s)
}

private class CameraSpring(start: Vec3df, aimSpeed: Float = 0.5f) {
    var position = start
        private set
    private var velocity = Vec3df()
    private val stiffness = aimSpeed * 120f + 1f
    private val damping = 2f * sqrt(stiffness) * 1.0f

    fun update(target: Vec3df, dt: Float) {
        val toTarget = target.sub(position)
        val accel = toTarget.mul(stiffness).sub(velocity.mul(damping))
        velocity = velocity.add(accel.mul(dt))
        position = position.add(velocity.mul(dt))
    }
}

private class LockOnCamera(start: Vec3df, startYaw: Float = 0f, startPitch: Float = 0f, aimSpeed: Float = 0.5f) {
    private val camera = CameraSpring(start, aimSpeed)
    var yaw = startYaw
        private set
    var pitch = startPitch
        private set
    private val rotSpeed = (aimSpeed * 40f).coerceAtLeast(0.01f)

    fun nudge(deltaYaw: Float, deltaPitch: Float) {
        yaw = MathHelper.wrapDegrees(yaw + deltaYaw)
        pitch = (pitch + deltaPitch).coerceIn(-90f, 90f)
    }

    fun update(playerPos: Vec3df, targetPos: Vec3df, targetVel: Vec3df, dt: Float) {
        val predicted = predictTarget(targetPos, targetVel)
        camera.update(predicted, dt)
        val dir = camera.position.sub(playerPos)
        val targetYaw = Math.toDegrees(Math.atan2(dir.z.toDouble(), dir.x.toDouble())).toFloat() - 90f
        val targetPitch = -Math.toDegrees(
            Math.atan2(dir.y.toDouble(), sqrt((dir.x * dir.x + dir.z * dir.z).toDouble()))
        ).toFloat()
        yaw = dampAngle(yaw, targetYaw, dt)
        pitch = dampAngle(pitch, targetPitch, dt)
    }

    companion object {
        private const val predictionTime = 0.08f
        fun predictTarget(targetPos: Vec3df, targetVel: Vec3df) = targetPos.add(targetVel.mul(predictionTime))
    }

    private fun dampAngle(current: Float, target: Float, dt: Float): Float {
        var diff = target - current
        while (diff > 180f) diff -= 360f
        while (diff < -180f) diff += 360f
        return current + diff * (1f - exp((-rotSpeed * dt).toDouble()).toFloat())
    }
}

internal fun ClientPlayerEntity.sendOverlayMessage(message: Text) {
    sendMessage(message, true)
}
