package com.one_studio

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem

import net.minecraft.world.item.Items
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.jvm.JvmStatic
import org.slf4j.LoggerFactory
import com.one_studio.TargetPriority

import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback

object MidnightAssistClient : ClientModInitializer {
    private val logger = LoggerFactory.getLogger("MidnightAssist")

    private val KEY_CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("midnight-assist", "main"))
    private val keyToggle = KeyMappingHelper.registerKeyMapping(KeyMapping("key.midnight-assist.toggle", InputConstants.KEY_J, KEY_CATEGORY))
    private val keyLock = KeyMappingHelper.registerKeyMapping(KeyMapping("key.midnight-assist.lock", InputConstants.KEY_N, KEY_CATEGORY))
    private val keySettings = KeyMappingHelper.registerKeyMapping(KeyMapping("key.midnight-assist.settings", InputConstants.KEY_K, KEY_CATEGORY))
    private val keyEntityList = KeyMappingHelper.registerKeyMapping(KeyMapping("key.midnight-assist.entity_list", InputConstants.KEY_O, KEY_CATEGORY))

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
        logger.info("MidnightAssistClient initialized")

        UseBlockCallback.EVENT.register { _, _, _, _ ->
            if (shouldCancelItemUse()) InteractionResult.FAIL else InteractionResult.PASS
        }
        UseItemCallback.EVENT.register { _, _, _ ->
            if (shouldCancelItemUse()) InteractionResult.FAIL else InteractionResult.PASS
        }
        UseEntityCallback.EVENT.register { _, _, _, _, _ ->
            if (shouldCancelItemUse()) InteractionResult.FAIL else InteractionResult.PASS
        }

        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { PresetWatcher.tick() })

        ClientTickEvents.START_CLIENT_TICK.register(ClientTickEvents.StartTick { client: Minecraft ->
            val player = client.player ?: return@StartTick
            val level = client.level ?: return@StartTick

            if (!MidnightAssistConfig.data.globalEnabled) {
                resetCameraState()
                resetAssistState()
                return@StartTick
            }

            val handProfile = MidnightAssistConfig.findWeaponProfile(player.mainHandItem)
            if (handProfile != null && !handProfile.enabled) {
                resetCameraState()
                resetAssistState()
                return@StartTick
            }

            recentlyAttacked.entries.removeAll { System.currentTimeMillis() - it.value > RECENTLY_ATTACKED_TIMEOUT }

            meleeCameraRunning = false

            val entityReach = if (player.abilities.instabuild) 5.0 else 3.0

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
                    if (clicks > 0 && client.gui.screen() == null) {
                        repeat(clicks) {
                            if (meleeLockOnTargetId != -1) {
                                logger.info("Melee lock-on released")
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
                                logger.info("Melee lock-on released")
                                releaseMeleeLock()
                            }
                        }
                    }
                }

                if (meleeLockOnTargetId != -1) {
                    val target = level.getEntity(meleeLockOnTargetId)
                    if (target == null || !target.isAlive || target.distanceTo(player) > entityReach || !player.hasLineOfSight(target)) {
                        handleMeleeTargetGone(client, player)
                    }
                }
            }

            val meleeTarget = if (effMeleeLockOnEnabled() && meleeLockOnTargetId != -1)
                level.getEntity(meleeLockOnTargetId) else null

            var currentTarget: Entity? = null

            if (meleeTarget != null && meleeTarget.isAlive) {
                currentTarget = meleeTarget
                lockedTargetId = meleeTarget.id
                isHunting = true
            } else if (lockedTargetId != -1) {
                val entity = level.getEntity(lockedTargetId)
                if (entity != null && entity.isAlive && entity.distanceTo(player) <= entityReach && player.hasLineOfSight(entity)) {
                    currentTarget = entity
                } else {
                    lockedTargetId = -1
                    isHunting = false
                    pendingAttack = false
                }
            }

            if (pendingTargetId != -1 && currentTarget == null) {
                val target = level.getEntity(pendingTargetId)
                if (target != null && target.isAlive && target.distanceTo(player) <= 5.0 && player.hasLineOfSight(target)) {
                    currentTarget = target
                    lockedTargetId = target.id
                    isHunting = true
                    attackStartTime = System.currentTimeMillis()
                    ticksSinceTargetAcquired = 0
                    logger.info("Acquired intercepted target: {} (id={})", target.name.string, target.id)
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
                val hitEntity = client.crosshairPickEntity
                var hasEntity = false
                if (hitEntity != null) {
                    if (hitEntity.isAlive && MidnightAssistConfig.isEntityEnabled(hitEntity) && hitEntity !is Player) {
                        hasEntity = true
                    }
                }
                if (!hasEntity) {
                    currentTarget = findTarget(client, 5.0)
                    if (currentTarget != null) {
                        val newTargetId = currentTarget.id
                        val isNewTarget = lockedTargetId != newTargetId
                        lockedTargetId = newTargetId
                        isHunting = true
                        attackStartTime = System.currentTimeMillis()
                        if (isNewTarget) ticksSinceTargetAcquired = 0
                        logger.info("Found target via findTarget: {} (id={})", currentTarget.name.string, currentTarget.id)
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
            }

            if (currentTarget == null && pendingAttack) {
                pendingAttack = false
            }

            if (lockedTargetId == -1 && meleeLockOnTargetId == -1) {
                camInitialized = false
            }

            if (effMeleeLockOnEnabled() && meleeLockOnTargetId != -1) {
                val target = level.getEntity(meleeLockOnTargetId)
                if (target != null && target.isAlive && target.distanceTo(player) <= entityReach && player.hasLineOfSight(target)) {
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
        })

        ClientTickEvents.START_CLIENT_TICK.register(ClientTickEvents.StartTick { client: Minecraft ->
            handleKeybinds(client)
            assistTick(client)
        })

        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client: Minecraft ->
            val player = client.player ?: return@EndTick
            val gameMode = client.gameMode ?: return@EndTick

            val entityReach = if (player.abilities.instabuild) 5.0 else 3.0

            val attackHeld = isAttackPressed(client)

            if (isHunting && lockedTargetId != -1 && (pendingAttack || attackHeld)) {
                val target = client.level?.getEntity(lockedTargetId)
                if (target != null && target.isAlive && target.distanceTo(player) <= entityReach) {
                    val onTarget = isTargetUnderCrosshair(client, target)
                    val cameraAligned = isCameraAligned(player, target)
                    val cooldown = player.getAttackStrengthScale(0.0f)
                    if (attackStartTime == 0L) attackStartTime = System.currentTimeMillis()
                    val elapsed = System.currentTimeMillis() - attackStartTime

                    val isFirstAttack = ticksSinceTargetAcquired <= 3
                    val maxAlignTicks = 20
                    val canAttack = onTarget && cameraAligned && cooldown >= 1.0f &&
                        (if (isFirstAttack) ticksSinceTargetAcquired >= 3 else elapsed > MIN_ATTACK_DELAY)

                    val shouldRelease = ticksSinceTargetAcquired > maxAlignTicks && !cameraAligned

                    if (canAttack) {
                        logger.info("ATTACK! target={} (id={}) onTarget={} cameraAligned={} cooldown={} elapsed={} ticks={}", target.name.string, target.id, onTarget, cameraAligned, cooldown, elapsed, ticksSinceTargetAcquired)

                        gameMode.attack(player, target)
                        player.swing(InteractionHand.MAIN_HAND, true)
                        recentlyAttacked[target.id] = System.currentTimeMillis()
                        attackStartTime = System.currentTimeMillis()
                        pendingAttack = false

                        if (!attackHeld && meleeLockOnTargetId == -1) {
                            isHunting = false
                            lockedTargetId = -1
                            lockOnCamera = null
                            lastLockTargetId = -1
                            camInitialized = false
                            pendingAttack = false
                            pendingTargetId = -1
                            ticksSinceTargetAcquired = 0
                        }
                    } else if (shouldRelease && meleeLockOnTargetId == -1) {
                        logger.info("Releasing target - couldn't align camera after {} ticks", maxAlignTicks)
                        isHunting = false
                        lockedTargetId = -1
                        lockOnCamera = null
                        lastLockTargetId = -1
                        camInitialized = false
                        pendingAttack = false
                        pendingTargetId = -1
                        ticksSinceTargetAcquired = 0
                    } else {
                        if (elapsed % 200L < 50L) logger.info("Waiting: onTarget={} cameraAligned={} cooldown={} elapsed={} ticks={}", onTarget, cameraAligned, cooldown, elapsed, ticksSinceTargetAcquired)
                    }
                } else {
                    logger.info("Target lost: id={}", lockedTargetId)
                    isHunting = false
                    lockedTargetId = -1
                    lockOnCamera = null
                    lastLockTargetId = -1
                    pendingAttack = false
                    pendingTargetId = -1
                    ticksSinceTargetAcquired = 0
                }
            }
        })

        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client: Minecraft ->
            val player = client.player ?: return@EndTick
            val gameMode = client.gameMode ?: return@EndTick

            if (!MidnightAssistConfig.data.globalEnabled) return@EndTick
            val handProfile = MidnightAssistConfig.findWeaponProfile(player.mainHandItem)
            if (handProfile != null && !handProfile.enabled) return@EndTick
            if (isHunting || lockedTargetId != -1 || meleeLockOnTargetId != -1) return@EndTick

            val realPressed = isAttackPressed(client)
            if (!realPressed) return@EndTick

            val hitEntity = client.crosshairPickEntity
            if (hitEntity != null) {
                if (hitEntity.isAlive && hitEntity !is Player) {
                    val cooldown = player.getAttackStrengthScale(0.0f)
                    if (cooldown >= 1.0f && !gameMode.isDestroying()) {
                        gameMode.attack(player, hitEntity)
                        player.swing(InteractionHand.MAIN_HAND, true)
                    }
                }
            }
        })
    }

    // ------------------------------------------------------------------
    // Keybinds
    // ------------------------------------------------------------------

    private fun handleKeybinds(client: Minecraft) {
        while (keyToggle.consumeClick()) {
            val enabled = !MidnightAssistConfig.data.globalEnabled
            MidnightAssistConfig.data.globalEnabled = enabled
            MidnightAssistConfig.save()
            if (!enabled) {
                resetAssistState()
            }
            client.player?.sendOverlayMessage(
                Component.translatable(if (enabled) "chat.midnight-assist.enabled" else "chat.midnight-assist.disabled")
            )
        }
        while (keyLock.consumeClick()) {
            toggleTargetLock(client)
        }
        while (keySettings.consumeClick()) {
            if (client.gui.screen() == null) {
                client.setScreenAndShow(MidnightAssistModMenu.createConfigScreen(null))
            }
        }
        while (keyEntityList.consumeClick()) {
            if (client.gui.screen() == null) {
                client.setScreenAndShow(EntityListScreen(null))
            }
        }
    }

    private fun toggleTargetLock(client: Minecraft) {
        val player = client.player ?: return
        if (assistTargetId != -1) {
            assistLocked = !assistLocked
            player.sendOverlayMessage(
                Component.translatable(if (assistLocked) "chat.midnight-assist.locked" else "chat.midnight-assist.unlocked")
            )
            return
        }
        val hit = client.crosshairPickEntity
        if (hit != null && hit.isAlive && MidnightAssistConfig.isEntityEnabled(hit)) {
            acquireTarget(player, hit)
            assistLocked = true
            player.sendOverlayMessage(Component.translatable("chat.midnight-assist.locked"))
        }
    }

    // ------------------------------------------------------------------
    // Aim assist
    // ------------------------------------------------------------------

    private fun assistTick(client: Minecraft) {
        val player = client.player ?: return
        val level = client.level ?: return

        if (!MidnightAssistConfig.data.globalEnabled) {
            return
        }
        if (client.gui.screen() != null || player.isSpectator) {
            if (assistTargetId != -1) {
                cancelTracking()
            }
            return
        }

        val handProfile = MidnightAssistConfig.findWeaponProfile(player.mainHandItem)
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

        var target = if (assistTargetId != -1) level.getEntity(assistTargetId) else null

        if (target != null && (!target.isAlive || target.isRemoved || target.level() !== level)) {
            onTargetKilled(player)
            target = null
        }

        if (target != null && !isStillValidTarget(player, target)) {
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

        target = if (assistTargetId != -1) level.getEntity(assistTargetId) else null

        if (target != null) {
            val point = aimPoint(player, target)
            if (!assistLocked && angleTo(player, point) > releaseAngle()) {
                releaseTarget(manual = true)
            }
        }
    }

    private fun acquireTarget(player: Player, target: Entity) {
        assistTargetId = target.id
        assistManualRelease = false
        nextSearchTicks = -1
        lastAppliedYaw = player.yRot
        lastAppliedPitch = player.xRot
    }

    private fun onTargetKilled(player: Player) {
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

    private fun angleTo(player: Player, point: Vec3): Double {
        val eye = player.getEyePosition(1.0f)
        val dir = point.subtract(eye)
        if (dir.lengthSqr() < 1.0E-6) return 0.0
        val dot = player.lookAngle.dot(dir.normalize()).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(dot))
    }

    private fun releaseAngle(): Double {
        val fov = effAimAssistDistance().toDouble()
        if (fov >= 360.0) return 180.0
        return (fov / 2.0).coerceAtLeast(5.0) + 15.0
    }

    private fun assistRange(): Double = 4.5

    private fun activeProfile(): WeaponProfile? {
        val player = Minecraft.getInstance().player ?: return null
        return MidnightAssistConfig.findWeaponProfile(player.mainHandItem)
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

    private fun canActivateMeleeLock(player: LocalPlayer): Boolean {
        if (player.isUsingItem) return false
        val stack = player.mainHandItem
        if (stack.isEmpty) return true
        if (stack.item is BlockItem) return false
        val id = BuiltInRegistries.ITEM.getKey(stack.item).path
        if (id == "bow" || id == "crossbow") return false
        return true
    }

    private fun acquireMeleeLock(client: Minecraft, player: LocalPlayer) {
        if (!canActivateMeleeLock(player)) {
            return
        }
        val lockOnTarget = findTarget(client, 5.0, ignoreFov = false, coneAngle = effAimAssistDistance().toDouble())
        if (lockOnTarget == null) {
            return
        }
        meleeLockOnTargetId = lockOnTarget.id
        meleeTargetType = lockOnTarget.type
        meleeLockSuppressed = false
        logger.info("Melee lock-on target: {} (id={})", lockOnTarget.name.string, lockOnTarget.id)
    }

    private fun handleMeleeTargetGone(client: Minecraft, player: LocalPlayer) {
        logger.info("Melee lock-on target lost")
        if (effMeleeRetarget() != MeleeRetarget.STOP_AFTER_DEATH && retargetMelee(client, player)) return
        releaseMeleeLock()
        if (effMeleeLockMode() == MeleeLockMode.HOLD) {
            meleeLockSuppressed = true
        }
    }

    private fun retargetMelee(client: Minecraft, player: LocalPlayer): Boolean {
        val next = when (effMeleeRetarget()) {
            MeleeRetarget.SWITCH_SAME_SPECIES -> meleeTargetType?.let { findSameSpeciesTarget(client, player, it, 5.0) }
            MeleeRetarget.SWITCH_PRIORITIZED -> findTarget(client, 5.0, ignoreFov = true)
            else -> null
        } ?: return false
        if (!next.isAlive) return false
        meleeLockOnTargetId = next.id
        meleeTargetType = next.type
        lockOnCamera = null
        lastLockTargetId = -1
        camInitialized = false
        logger.info("Melee lock-on switched to: {} (id={})", next.name.string, next.id)
        return true
    }

    private fun findSameSpeciesTarget(client: Minecraft, player: LocalPlayer, type: EntityType<*>, range: Double): Entity? {
        val level = client.level ?: return null
        var best: Entity? = null
        var bestDistance = Double.MAX_VALUE
        for (entity in level.getEntities(player, player.boundingBox.inflate(range))) {
            if (entity === player || !entity.isAlive || entity.type !== type) continue
            if (!MidnightAssistConfig.isEntityEnabled(entity)) continue
            val distance = entity.distanceTo(player).toDouble()
            if (distance > range || distance >= bestDistance) continue
            if (!player.hasLineOfSight(entity)) continue
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

    private fun isStillValidTarget(player: Player, target: Entity): Boolean {
        val range = assistRange()
        if (player.distanceTo(target) > range * 1.6) return false
        if (assistLocked) {
            return player.distanceTo(target) <= range * 2.5
        }
        return player.hasLineOfSight(target)
    }

    private fun currentItemMode(player: Player): ItemMode {
        if (player.isSpectator) return ItemMode.NONE
        val stack = player.mainHandItem
        val profile = MidnightAssistConfig.findWeaponProfile(stack)
        if (stack.isEmpty) {
            return if (profile != null && profile.enabled) ItemMode.EMPTY else ItemMode.NONE
        }
        if (profile != null && !profile.enabled) return ItemMode.NONE
        val id = BuiltInRegistries.ITEM.getKey(stack.item).path
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

    private fun aimPoint(player: Player, target: Entity): Vec3 {
        val bb = target.boundingBox
        val center = bb.center
        val height = bb.maxY - bb.minY
        val head = Vec3(center.x, target.eyeY - 0.1, center.z)
        val torso = Vec3(center.x, bb.minY + height * 0.62, center.z)
        val legs = Vec3(center.x, bb.minY + height * 0.22, center.z)
        return when (effAimPoint()) {
            AimPoint.CENTER -> center
            AimPoint.UPPER_BODY -> Vec3(center.x, bb.minY + height * 0.8, center.z)
            AimPoint.HEAD -> head
            AimPoint.DYNAMIC -> nearestBodyPart(player, head, torso, legs)
        }
    }

    private fun nearestBodyPart(player: Player, vararg parts: Vec3): Vec3 {
        val eye = player.getEyePosition(1.0f)
        val look = player.lookAngle
        var best = parts[0]
        var bestAngle = Double.MAX_VALUE
        for (part in parts) {
            val dir = part.subtract(eye)
            if (dir.lengthSqr() < 1.0E-6) return part
            val angle = acos(look.dot(dir.normalize()).coerceIn(-1.0, 1.0))
            if (angle < bestAngle) {
                bestAngle = angle
                best = part
            }
        }
        return best
    }

    private fun findAssistTarget(client: Minecraft, player: Player, coneAngle: Double): Entity? {
        val level = client.level ?: return null
        val range = assistRange()
        val entities = level.getEntities(player, player.boundingBox.inflate(range))
        val halfCone = coneAngle / 2.0

        var best: Entity? = null
        var bestScore = Double.MAX_VALUE

        for (entity in entities) {
            if (entity === player || !entity.isAlive) continue
            if (!MidnightAssistConfig.isEntityEnabled(entity)) continue
            val dist = player.distanceTo(entity).toDouble()
            if (dist > range) continue
            if (!player.hasLineOfSight(entity)) continue

            if (coneAngle < 360.0) {
                val angle = angleTo(player, entity.boundingBox.center)
                if (angle > halfCone) continue
            }

            val angle = angleTo(player, entity.boundingBox.center)
            val score = scoreTarget(player, entity, angle, dist)
            if (score < bestScore) {
                bestScore = score
                best = entity
            }
        }
        return best
    }

    private fun scoreTarget(player: Player, entity: Entity, angle: Double, dist: Double): Double {
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
            TargetPriority.LOOKING_AT_YOU -> if (entity is Mob && entity.target === player) dist * 0.5 else dist * 2.0
            TargetPriority.RECENTLY_ATTACKED -> {
                val lastAttack = recentlyAttacked[entity.id]
                if (lastAttack != null && now - lastAttack < RECENTLY_ATTACKED_TIMEOUT) dist * 0.3 else dist * 2.0
            }
        }
        return if (MidnightAssistConfig.isPriorityEntity(entity)) base * 0.5 else base
    }

    private fun threatLevel(player: Player, entity: Entity): Double {
        if (entity is Mob) {
            if (entity.target === player) return 1.0
            return if (entity.type.category === net.minecraft.world.entity.MobCategory.MONSTER) 0.6 else 0.2
        }
        return 0.1
    }

    // ------------------------------------------------------------------
    // Existing helpers
    // ------------------------------------------------------------------

    private fun isUsePressed(client: Minecraft): Boolean {
        if (client.gui.screen() != null) return false
        return client.options.keyUse.isDown
    }

    private fun isAttackPressed(client: Minecraft): Boolean {
        return client.options.keyAttack.isDown
    }

    private fun isTargetUnderCrosshair(client: Minecraft, target: Entity): Boolean {
        val player = client.player ?: return false
        val reach = 5.0
        val cameraPos = player.getEyePosition(1.0f)
        val rotation = player.lookAngle
        val endPos = cameraPos.add(rotation.x * reach, rotation.y * reach, rotation.z * reach)
        val box = target.boundingBox.inflate(target.pickRadius.toDouble() + 0.1)
        val hit = box.clip(cameraPos, endPos)
        return hit.isPresent
    }

    private fun isCameraAligned(player: Player, target: Entity): Boolean {
        val playerPos = player.getEyePosition(1.0f)
        val targetPos = aimPoint(player, target)
        val dirToTarget = targetPos.subtract(playerPos).normalize()
        val playerRotation = player.lookAngle
        val dot = playerRotation.dot(dirToTarget)
        return dot > 0.98
    }

    private fun findTarget(client: Minecraft, range: Double, ignoreFov: Boolean = false, coneAngle: Double = -1.0): Entity? {
        val player = client.player ?: return null
        val level = client.level ?: return null
        val entities = level.getEntities(player, player.boundingBox.inflate(range))

        val priority = effTargetPriority()
        val fov = if (coneAngle >= 0.0) coneAngle else effAimAssistDistance().toDouble()
        val now = System.currentTimeMillis()

        var bestTarget: Entity? = null
        var bestScore = Double.MAX_VALUE

        for (entity in entities) {
            if (entity is Player || !entity.isAlive) continue
            if (!MidnightAssistConfig.isEntityEnabled(entity)) continue
            val dist = entity.distanceTo(player).toDouble()
            if (dist > range) continue
            if (!player.hasLineOfSight(entity)) continue
            if (!ignoreFov && fov < 360.0 && angleTo(player, entity.boundingBox.center) > fov / 2.0) continue

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
                    val isLooking = if (entity is Mob) entity.target == player else false
                    if (isLooking) dist * 0.5 else dist * 2.0
                }
                TargetPriority.RECENTLY_ATTACKED -> {
                    val lastAttack = recentlyAttacked[entity.id]
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

    private fun lockedCameraSoft(player: Player, target: Entity) {
        val playerPos = Vec3f(player.x.toFloat(), player.eyeY.toFloat(), player.z.toFloat())
        val aim = aimPoint(player, target)
        val targetPos = Vec3f(aim.x.toFloat(), aim.y.toFloat(), aim.z.toFloat())
        val vel = target.deltaMovement
        val targetVel = Vec3f(vel.x.toFloat() * 20f, vel.y.toFloat() * 20f, vel.z.toFloat() * 20f)
        val aimSpeed = effAimSpeed().toFloat()
        if (lockOnCamera == null || target.id != lastLockTargetId || !camInitialized) {
            val predicted = LockOnCamera.predictTarget(targetPos, targetVel)
            lockOnCamera = LockOnCamera(predicted, player.yRot, player.xRot, aimSpeed)
            lastLockTargetId = target.id
            if (!camInitialized) {
                prevCamYaw = player.yRot
                prevCamPitch = player.xRot
                curCamYaw = player.yRot
                curCamPitch = player.xRot
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

    private fun updateBlockAimAssist(client: Minecraft, player: Player, level: net.minecraft.client.multiplayer.ClientLevel) {
        val range = MidnightAssistConfig.data.blockAimAssistRange
        val hitResult = client.hitResult

        if (hitResult is BlockHitResult) {
            val blockPos = hitResult.blockPos
            val targetVec = Vec3(blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5)
            val dx = player.x - targetVec.x
            val dy = player.y - targetVec.y
            val dz = player.z - targetVec.z
            val dist = Math.sqrt(dx * dx + dy * dy + dz * dz)

            if (dist <= range) {
                if (!blockAimAssistInitialized || lastTargetedBlockPos != blockPos) {
                    if (!blockAimAssistInitialized) {
                        prevBlockCamYaw = player.yRot
                        prevBlockCamPitch = player.xRot
                        curBlockCamYaw = player.yRot
                        curBlockCamPitch = player.xRot
                        blockAimAssistInitialized = true
                    }
                    lastTargetedBlockPos = blockPos

                    if (blockSpring == null || lastTargetedBlockPos != blockPos || !blockAimAssistInitialized) {
                        val targetVec3f = Vec3f(targetVec.x.toFloat(), targetVec.y.toFloat(), targetVec.z.toFloat())
                        blockSpring = CameraSpring(targetVec3f, MidnightAssistConfig.effectiveAimSpeed().toFloat())
                        lastTargetedBlockPos = blockPos
                    }

                    val playerPos = Vec3f(player.x.toFloat(), player.eyeY.toFloat(), player.z.toFloat())
                    val targetVec3f = Vec3f(targetVec.x.toFloat(), targetVec.y.toFloat(), targetVec.z.toFloat())
                    val spring = blockSpring ?: return

                    spring.update(targetVec3f, 0.05f)

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
    fun onUseKeyClick(key: InputConstants.Key) {
        val client = Minecraft.getInstance() ?: return
        if (client.gui.screen() != null) return
        if (!client.options.keyUse.matches(key)) return
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
    }

    @JvmStatic
    fun tryInterceptAttack(client: Minecraft): Boolean {
        if (!MidnightAssistConfig.data.globalEnabled) return false
        val player = client.player ?: return false
        val level = client.level ?: return false
        val handProfile = MidnightAssistConfig.findWeaponProfile(player.mainHandItem)
        if (handProfile != null && !handProfile.enabled) return false
        if (meleeLockOnTargetId != -1) {
            val target = level.getEntity(meleeLockOnTargetId)
            if (target != null && target.isAlive) {
                if (lockedTargetId != target.id || !isHunting) {
                    lockedTargetId = target.id
                    isHunting = true
                    attackStartTime = System.currentTimeMillis()
                    ticksSinceTargetAcquired = 0
                    logger.info("Attack during melee lock-on: {} (id={})", target.name.string, target.id)
                }
                pendingAttack = true
                return true
            }
        }
        if (isHunting) return true
        if (client.crosshairPickEntity != null) return false
        val target = findTarget(client, 5.0, coneAngle = effAimAssistDistance().toDouble()) ?: return false
        lockedTargetId = target.id
        isHunting = true
        pendingAttack = true
        attackStartTime = System.currentTimeMillis()
        ticksSinceTargetAcquired = 0
        logger.info("Intercepted attack, hunting: {} (id={})", target.name.string, target.id)
        return true
    }

    @JvmStatic
    fun shouldSuppressBlockBreak(): Boolean {
        if (!MidnightAssistConfig.data.globalEnabled) return false
        return isHunting || meleeLockOnTargetId != -1
    }

    @JvmStatic
    fun shouldCancelItemUse(): Boolean {
        val instance = Minecraft.getInstance()
        val player = instance.player ?: return false
        if (instance.gui.screen() != null) return false
        val mainHand = player.mainHandItem
        val offHand = player.offhandItem
        if (mainHand.`is`(Items.SHIELD) || offHand.`is`(Items.SHIELD)) return false
        if (!effMeleeLockOnBlockUse()) return false
        return instance.options.keyUse.isDown && meleeLockOnTargetId != -1 && effMeleeLockOnEnabled() && MidnightAssistConfig.data.globalEnabled
    }

    @JvmStatic
    fun applyPartialTicks(partialTicks: Float) {
        val player = Minecraft.getInstance().player ?: return
        if (!MidnightAssistConfig.data.globalEnabled) return

        // Entity aim assist has priority
        if (camInitialized) {
            val yawDiff = Mth.wrapDegrees(curCamYaw - prevCamYaw)
            val yaw = prevCamYaw + yawDiff * partialTicks
            val pitch = prevCamPitch + (curCamPitch - prevCamPitch) * partialTicks
            player.setYRot(yaw)
            player.setXRot(pitch)
            player.yRotO = yaw
            player.xRotO = pitch
            lastAppliedYaw = yaw
            lastAppliedPitch = pitch
        }
        // Block aim assist (only when not using entity aim assist)
        else if (blockAimAssistInitialized) {
            val yawDiff = Mth.wrapDegrees(curBlockCamYaw - prevBlockCamYaw)
            val yaw = prevBlockCamYaw + yawDiff * partialTicks
            val pitch = prevBlockCamPitch + (curBlockCamPitch - prevBlockCamPitch) * partialTicks
            player.setYRot(yaw)
            player.setXRot(pitch)
            player.yRotO = yaw
            player.xRotO = pitch
        }
    }
}

private data class Vec3f(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f) {
    fun add(o: Vec3f) = Vec3f(x + o.x, y + o.y, z + o.z)
    fun sub(o: Vec3f) = Vec3f(x - o.x, y - o.y, z - o.z)
    fun mul(s: Float) = Vec3f(x * s, y * s, z * s)
}

private class CameraSpring(start: Vec3f, aimSpeed: Float = 0.5f) {
    var position = start
        private set
    private var velocity = Vec3f()
    private val stiffness = aimSpeed * 120f + 1f
    private val damping = 2f * sqrt(stiffness) * 1.0f

    fun update(target: Vec3f, dt: Float) {
        val toTarget = target.sub(position)
        val accel = toTarget.mul(stiffness).sub(velocity.mul(damping))
        velocity = velocity.add(accel.mul(dt))
        position = position.add(velocity.mul(dt))
    }
}

private class LockOnCamera(start: Vec3f, startYaw: Float = 0f, startPitch: Float = 0f, aimSpeed: Float = 0.5f) {
    private val camera = CameraSpring(start, aimSpeed)
    var yaw = startYaw
        private set
    var pitch = startPitch
        private set
    private val rotSpeed = (aimSpeed * 40f).coerceAtLeast(0.01f)

    fun nudge(deltaYaw: Float, deltaPitch: Float) {
        yaw = Mth.wrapDegrees(yaw + deltaYaw)
        pitch = (pitch + deltaPitch).coerceIn(-90f, 90f)
    }

    fun update(playerPos: Vec3f, targetPos: Vec3f, targetVel: Vec3f, dt: Float) {
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
        fun predictTarget(targetPos: Vec3f, targetVel: Vec3f) = targetPos.add(targetVel.mul(predictionTime))
    }

    private fun dampAngle(current: Float, target: Float, dt: Float): Float {
        var diff = target - current
        while (diff > 180f) diff -= 360f
        while (diff < -180f) diff += 360f
        return current + diff * (1f - exp((-rotSpeed * dt).toDouble()).toFloat())
    }
}
