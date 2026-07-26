package com.one_studio

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.KeyBinding
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.mob.MobEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.util.hit.EntityHitResult
import net.minecraft.util.math.MathHelper

import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.jvm.JvmStatic
import org.slf4j.LoggerFactory

import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback

object MidnightAssisitClient : ClientModInitializer {
    private val logger = LoggerFactory.getLogger("MidnightAssist")
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
    private var modCausingAttack = false
    private var ticksSinceTargetAcquired = 0
    private var pendingTargetId: Int = -1
    private var prevAttackPressed: Boolean = false

    override fun onInitializeClient() {
        logger.info("MidnightAssistClient initialized")

        UseBlockCallback.EVENT.register { player, _, hand, _ ->
            if (shouldCancelItemUse()) ActionResult.FAIL else ActionResult.PASS
        }
        UseItemCallback.EVENT.register { player, _, hand ->
            if (shouldCancelItemUse()) ActionResult.FAIL else ActionResult.PASS
        }
        UseEntityCallback.EVENT.register { player, _, hand, entity, _ ->
            if (shouldCancelItemUse()) ActionResult.FAIL else ActionResult.PASS
        }

        AttackEntityCallback.EVENT.register { player, world, hand, entity, hitResult ->
            if (modCausingAttack || !MidnightAssisitConfig.data.globalEnabled) return@register ActionResult.PASS
            if (entity.id == meleeLockOnTargetId || entity.id == lockedTargetId) return@register ActionResult.FAIL
            ActionResult.PASS
        }

        ClientTickEvents.START_CLIENT_TICK.register(ClientTickEvents.StartTick { client: MinecraftClient ->
            val player = client.player ?: return@StartTick
            val world = client.world ?: return@StartTick

            if (!MidnightAssisitConfig.data.globalEnabled) {
                resetCameraState()
                return@StartTick
            }

            recentlyAttacked.entries.removeAll { System.currentTimeMillis() - it.value > RECENTLY_ATTACKED_TIMEOUT }

            meleeCameraRunning = false

            val entityReach = if (player.abilities.creativeMode) 5.0 else 3.0

            // --- Phase 1: Melee lock-on (RMB) ---
            if (MidnightAssisitConfig.data.meleeLockOnEnabled) {
                if (isUsePressed(client)) {
                    if (meleeLockOnTargetId == -1 && !isHunting) {
                        val lockOnTarget = findTarget(client, 5.0, ignoreFov = false)
                        if (lockOnTarget != null) {
                            meleeLockOnTargetId = lockOnTarget.id
                            logger.info("Melee lock-on target: {} (id={})", lockOnTarget.name.string, lockOnTarget.id)
                        }
                    }
                    if (meleeLockOnTargetId != -1) {
                        val target = world.getEntityById(meleeLockOnTargetId)
                        if (target == null || !target.isAlive || target.distanceTo(player) > entityReach || !player.canSee(target)) {
                            meleeLockOnTargetId = -1
                            lockOnCamera = null
                            lastLockTargetId = -1
                            camInitialized = false
                            logger.info("Melee lock-on target lost")
                        }
                    }
                } else if (meleeLockOnTargetId != -1) {
                    logger.info("Melee lock-on released")
                    meleeLockOnTargetId = -1
                    lockedTargetId = -1
                    lockOnCamera = null
                    lastLockTargetId = -1
                    isHunting = false
                    pendingAttack = false
                    pendingTargetId = -1
                }
            }

            // --- Phase 2: Determine active attack target ---
            val meleeTarget = if (MidnightAssisitConfig.data.meleeLockOnEnabled && meleeLockOnTargetId != -1)
                world.getEntityById(meleeLockOnTargetId) else null

            var currentTarget: Entity? = null

            if (meleeTarget != null && meleeTarget.isAlive) {
                currentTarget = meleeTarget
                lockedTargetId = meleeTarget.id
                isHunting = true
            } else if (lockedTargetId != -1) {
                val entity = world.getEntityById(lockedTargetId)
                if (entity != null && entity.isAlive && entity.distanceTo(player) <= entityReach && player.canSee(entity)) {
                    currentTarget = entity
                } else {
                    lockedTargetId = -1
                    isHunting = false
                    pendingAttack = false
                }
            }

            if (pendingTargetId != -1 && currentTarget == null) {
                val target = world.getEntityById(pendingTargetId)
                if (target != null && target.isAlive && target.distanceTo(player) <= 5.0 && player.canSee(target)) {
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
                val crosshair = client.crosshairTarget
                var hasEntity = false
                if (crosshair != null && crosshair.type == net.minecraft.util.hit.HitResult.Type.ENTITY) {
                    val hitEntity = (crosshair as EntityHitResult).entity
                    if (hitEntity != null && hitEntity.isAlive && MidnightAssisitConfig.isEntityEnabled(hitEntity) && hitEntity !is PlayerEntity) {
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

            // --- Phase 3: Camera rotation (UNIFIED - only one path runs) ---
            if (MidnightAssisitConfig.data.meleeLockOnEnabled && meleeLockOnTargetId != -1) {
                val target = world.getEntityById(meleeLockOnTargetId)
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

            if (isHunting) {
                client.options.attackKey.setPressed(false)
                ticksSinceTargetAcquired++
            }
        })

        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client: MinecraftClient ->
            val player = client.player ?: return@EndTick
            val interactionManager = client.interactionManager ?: return@EndTick

            val entityReach = if (player.abilities.creativeMode) 5.0 else 3.0

            if (isHunting && lockedTargetId != -1 && pendingAttack && !interactionManager.isBreakingBlock) {
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
                        logger.info("ATTACK! target={} onTarget={} cameraAligned={} cooldown={} elapsed={} ticks={}", target.name.string, onTarget, cameraAligned, cooldown, elapsed, ticksSinceTargetAcquired)
                        
                        client.getNetworkHandler()?.sendPacket(net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.LookAndOnGround(curCamYaw, curCamPitch, player.isOnGround(), player.horizontalCollision))
                        
                        modCausingAttack = true
                        interactionManager.attackEntity(player, target)
                        modCausingAttack = false
                        recentlyAttacked[target.id] = System.currentTimeMillis()
                        attackStartTime = System.currentTimeMillis()
                        pendingAttack = false

                        val stillAttacking = isAttackPressed(client) || pendingAttack

                        if (!stillAttacking) {
                            isHunting = false
                            lockedTargetId = -1
                            lockOnCamera = null
                            lastLockTargetId = -1
                            camInitialized = false
                            pendingAttack = false
                            pendingTargetId = -1
                            ticksSinceTargetAcquired = 0
                        }
                    } else if (shouldRelease) {
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

        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client: MinecraftClient ->
            val player = client.player ?: return@EndTick
            val interactionManager = client.interactionManager ?: return@EndTick

            if (!MidnightAssisitConfig.data.globalEnabled) return@EndTick
            if (isHunting || lockedTargetId != -1 || meleeLockOnTargetId != -1) return@EndTick

            val realPressed = isAttackPressed(client)
            if (!realPressed) return@EndTick

            val crosshair = client.crosshairTarget
            if (crosshair != null && crosshair.type == net.minecraft.util.hit.HitResult.Type.ENTITY) {
                val hitEntity = (crosshair as EntityHitResult).entity
                if (hitEntity != null && hitEntity.isAlive && hitEntity !is PlayerEntity) {
                    val cooldown = player.getAttackCooldownProgress(0.0f)
                    if (cooldown >= 1.0f && !interactionManager.isBreakingBlock) {
                        interactionManager.attackEntity(player, hitEntity)
                        player.swingHand(Hand.MAIN_HAND)
                    }
                }
            }
        })
    }

    private fun isUsePressed(client: MinecraftClient): Boolean {
        if (client.currentScreen != null) return false
        return client.options.useKey.isPressed()
    }

    private fun isAttackPressed(client: MinecraftClient): Boolean {
        return try {
            val method = KeyBinding::class.java.getMethod("updatePressedState")
            method.invoke(client.options.attackKey)
            client.options.attackKey.isPressed
        } catch (_: Exception) {
            client.options.attackKey.isPressed
        }
    }

    private fun isTargetUnderCrosshair(client: MinecraftClient, target: Entity): Boolean {
        val player = client.player ?: return false
        val reach = 5.0
        val cameraPos = player.getCameraPosVec(1.0f)
        val rotation = player.getRotationVec(1.0f)
        val endPos = cameraPos.add(rotation.x * reach, rotation.y * reach, rotation.z * reach)
        val box = target.boundingBox.expand(target.targetingMargin.toDouble() + 0.1)
        val hit = box.raycast(cameraPos, endPos)
        return hit.isPresent
    }

    private fun isCameraAligned(player: PlayerEntity, target: Entity): Boolean {
        val playerPos = player.getCameraPosVec(1.0f)
        val targetPos = target.boundingBox.center
        val dirToTarget = targetPos.subtract(playerPos).normalize()
        val playerRotation = player.getRotationVec(1.0f)
        val dot = playerRotation.dotProduct(dirToTarget)
        return dot > 0.98
    }

    private fun findTarget(client: MinecraftClient, range: Double, ignoreFov: Boolean = false): Entity? {
        val player = client.player ?: return null
        val world = client.world ?: return null
        val entities = world.getOtherEntities(player, player.boundingBox.expand(range))

        val priority = MidnightAssisitConfig.data.targetPriority
        val accuracy = MidnightAssisitConfig.data.aimAccuracy
        val now = System.currentTimeMillis()

        var bestTarget: Entity? = null
        var bestScore = Double.MAX_VALUE

        for (entity in entities) {
            if (entity is PlayerEntity || !entity.isAlive) continue
            if (!MidnightAssisitConfig.isEntityEnabled(entity)) continue
            val dist = entity.distanceTo(player).toDouble()
            if (dist > range) continue
            if (!player.canSee(entity)) continue
            if (!ignoreFov && !isLookingAt(player, entity, accuracy)) continue

            val score = when (priority) {
                MidnightAssisitConfig.TargetPriority.NEAREST -> dist
                MidnightAssisitConfig.TargetPriority.FARTHEST -> -dist
                MidnightAssisitConfig.TargetPriority.WEAKEST -> {
                    if (entity is LivingEntity) entity.health.toDouble() + dist * 0.01 else dist
                }
                MidnightAssisitConfig.TargetPriority.STRONGEST -> {
                    if (entity is LivingEntity) -(entity.health.toDouble()) + dist * 0.01 else -dist
                }
                MidnightAssisitConfig.TargetPriority.LOOKING_AT_YOU -> {
                    val isLooking = if (entity is MobEntity) entity.target == player else false
                    if (isLooking) dist * 0.5 else dist * 2.0
                }
                MidnightAssisitConfig.TargetPriority.RECENTLY_ATTACKED -> {
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

    private fun isLookingAt(player: PlayerEntity, entity: Entity, accuracy: Double): Boolean {
        val playerPos = player.getCameraPosVec(1.0f)
        val targetPos = entity.boundingBox.center
        val dirToTarget = targetPos.subtract(playerPos).normalize()
        val playerRotation = player.getRotationVec(1.0f)
        val threshold = 1.0 - accuracy
        return playerRotation.dotProduct(dirToTarget) > threshold
    }

    private fun lockedCamera(player: PlayerEntity, target: Entity) {
        val playerPos = Vec3(player.x.toFloat(), player.eyeY.toFloat(), player.z.toFloat())
        val bb = target.boundingBox.center
        val targetPos = Vec3(bb.x.toFloat(), bb.y.toFloat(), bb.z.toFloat())
        val vel = target.velocity
        val targetVel = Vec3(vel.x.toFloat() * 20f, vel.y.toFloat() * 20f, vel.z.toFloat() * 20f)
        val aimSpeed = MidnightAssisitConfig.data.aimSpeed.toFloat()
        if (lockOnCamera == null || target.id != lastLockTargetId) {
            val predicted = LockOnCamera.predictTarget(targetPos, targetVel)
            lockOnCamera = LockOnCamera(predicted, player.yaw, player.pitch, aimSpeed)
            lastLockTargetId = target.id
            prevCamYaw = player.yaw
            prevCamPitch = player.pitch
            curCamYaw = player.yaw
            curCamPitch = player.pitch
        }
        val cam = lockOnCamera ?: return
        cam.update(playerPos, targetPos, targetVel, 0.05f)
        prevCamYaw = curCamYaw
        prevCamPitch = curCamPitch
        curCamYaw = cam.yaw
        curCamPitch = cam.pitch
        player.setYaw(curCamYaw)
        player.setPitch(curCamPitch)
    }

    private fun lockedCameraSoft(player: PlayerEntity, target: Entity) {
        val playerPos = Vec3(player.x.toFloat(), player.eyeY.toFloat(), player.z.toFloat())
        val bb = target.boundingBox.center
        val targetPos = Vec3(bb.x.toFloat(), bb.y.toFloat(), bb.z.toFloat())
        val vel = target.velocity
        val targetVel = Vec3(vel.x.toFloat() * 20f, vel.y.toFloat() * 20f, vel.z.toFloat() * 20f)
        val aimSpeed = MidnightAssisitConfig.data.aimSpeed.toFloat()
        if (lockOnCamera == null || target.id != lastLockTargetId || !camInitialized) {
            val predicted = LockOnCamera.predictTarget(targetPos, targetVel)
            lockOnCamera = LockOnCamera(predicted, player.yaw, player.pitch, aimSpeed)
            lastLockTargetId = target.id
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
        prevCamYaw = player.yaw
        prevCamPitch = player.pitch
        curCamYaw = cam.yaw
        curCamPitch = cam.pitch
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
    }

    @JvmStatic
    fun tryInterceptAttack(client: MinecraftClient): Boolean {
        if (!MidnightAssisitConfig.data.globalEnabled) return false
        val player = client.player ?: return false
        val world = client.world ?: return false
        if (modCausingAttack) return false
        if (meleeLockOnTargetId != -1) {
            val target = world.getEntityById(meleeLockOnTargetId)
            if (target != null && target.isAlive) {
                lockedTargetId = target.id
                isHunting = true
                pendingAttack = true
                meleeLockOnTargetId = -1
                lockOnCamera = null
                lastLockTargetId = -1
                camInitialized = false
                attackStartTime = System.currentTimeMillis()
                ticksSinceTargetAcquired = 0
                logger.info("Attack during melee lock-on, hunting: {} (id={})", target.name.string, target.id)
                return true
            }
        }
        if (isHunting) return true
        if (client.crosshairTarget is EntityHitResult) return false
        val target = findTarget(client, 5.0) ?: return false
        lockedTargetId = target.id
        isHunting = true
        pendingAttack = true
        attackStartTime = System.currentTimeMillis()
        ticksSinceTargetAcquired = 0
        logger.info("Intercepted attack, hunting: {} (id={})", target.name.string, target.id)
        return true
    }

    @JvmStatic
    fun shouldCancelItemUse(): Boolean {
        val instance = MinecraftClient.getInstance()
        val player = instance.player ?: return false
        if (instance.currentScreen != null) return false
        if (!MidnightAssisitConfig.data.meleeLockOnEnabled || !MidnightAssisitConfig.data.globalEnabled) return false
        if (meleeLockOnTargetId == -1) return false
        if (!instance.options.useKey.isPressed()) return false
        if (player.mainHandStack.`isOf`(net.minecraft.item.Items.SHIELD) || player.offHandStack.`isOf`(net.minecraft.item.Items.SHIELD)) return false
        return true
    }

    @JvmStatic
    fun applyPartialTicks(partialTicks: Float) {
        val player = MinecraftClient.getInstance().player ?: return
        if (!camInitialized || !MidnightAssisitConfig.data.globalEnabled) return
        val yawDiff = MathHelper.wrapDegrees(curCamYaw - prevCamYaw)
        player.setYaw(prevCamYaw + yawDiff * partialTicks)
        player.setPitch(prevCamPitch + (curCamPitch - prevCamPitch) * partialTicks)
    }
}

private data class Vec3(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f) {
    fun add(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    fun sub(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    fun mul(s: Float) = Vec3(x * s, y * s, z * s)
}

private class CameraSpring(start: Vec3, aimSpeed: Float = 0.5f) {
    var position = start
        private set
    private var velocity = Vec3()
    private val stiffness = aimSpeed * 400f + 1f
    private val damping = 2f * sqrt(stiffness) * 0.7f

    fun update(target: Vec3, dt: Float) {
        val toTarget = target.sub(position)
        val accel = toTarget.mul(stiffness).sub(velocity.mul(damping))
        velocity = velocity.add(accel.mul(dt))
        position = position.add(velocity.mul(dt))
    }
}

private class LockOnCamera(start: Vec3, startYaw: Float = 0f, startPitch: Float = 0f, aimSpeed: Float = 0.5f) {
    private val camera = CameraSpring(start, aimSpeed)
    var yaw = startYaw
        private set
    var pitch = startPitch
        private set
    private val rotSpeed = (aimSpeed * 120f).coerceAtLeast(0.01f)

    fun update(playerPos: Vec3, targetPos: Vec3, targetVel: Vec3, dt: Float) {
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
        private const val predictionTime = 0.15f
        fun predictTarget(targetPos: Vec3, targetVel: Vec3) = targetPos.add(targetVel.mul(predictionTime))
    }

    private fun dampAngle(current: Float, target: Float, dt: Float): Float {
        var diff = target - current
        while (diff > 180f) diff -= 360f
        while (diff < -180f) diff += 360f
        return current + diff * (1f - exp((-rotSpeed * dt).toDouble()).toFloat())
    }
}
