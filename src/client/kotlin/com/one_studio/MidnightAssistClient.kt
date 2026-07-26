package com.one_studio

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Items
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import org.slf4j.LoggerFactory
import kotlin.jvm.JvmStatic
import kotlin.math.exp
import kotlin.math.sqrt

object MidnightAssistClient : ClientModInitializer {
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
    private var meleeUseReleaseTicks: Int = 0
    private val MELEE_LOCKON_GRACE_TICKS = 5

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

        AttackEntityCallback.EVENT.register { _, _, _, entity, _ ->
            if (modCausingAttack || !MidnightAssistConfig.data.globalEnabled) return@register InteractionResult.PASS
            if (entity.id == meleeLockOnTargetId || entity.id == lockedTargetId) return@register InteractionResult.FAIL
            InteractionResult.PASS
        }

        ClientTickEvents.START_CLIENT_TICK.register(ClientTickEvents.StartTick { client: Minecraft ->
            val player = client.player ?: return@StartTick
            val level = client.level ?: return@StartTick

            if (!MidnightAssistConfig.data.globalEnabled) {
                resetCameraState()
                return@StartTick
            }

            recentlyAttacked.entries.removeAll { System.currentTimeMillis() - it.value > RECENTLY_ATTACKED_TIMEOUT }
            meleeCameraRunning = false

            val entityReach = entityReach(player)

            if (MidnightAssistConfig.data.meleeLockOnEnabled) {
                if (isUsePressed(client)) {
                    meleeUseReleaseTicks = 0
                    if (meleeLockOnTargetId == -1 && !isHunting) {
                        val lockOnTarget = findTarget(client, 5.0, ignoreFov = false)
                        if (lockOnTarget != null) {
                            meleeLockOnTargetId = lockOnTarget.id
                        }
                    }
                    if (meleeLockOnTargetId != -1) {
                        val target = level.getEntity(meleeLockOnTargetId)
                        if (target == null || !target.isAlive || target.distanceTo(player) > entityReach || !player.hasLineOfSight(target)) {
                            meleeLockOnTargetId = -1
                            lockOnCamera = null
                            lastLockTargetId = -1
                            camInitialized = false
                        }
                    }
                } else if (meleeLockOnTargetId != -1) {
                    meleeUseReleaseTicks++
                    if (meleeUseReleaseTicks >= MELEE_LOCKON_GRACE_TICKS) {
                        meleeLockOnTargetId = -1
                        lockedTargetId = -1
                        lockOnCamera = null
                        lastLockTargetId = -1
                        isHunting = false
                        pendingAttack = false
                        pendingTargetId = -1
                    }
                }
            }

            val meleeTarget = if (MidnightAssistConfig.data.meleeLockOnEnabled && meleeLockOnTargetId != -1)
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
                val crosshair = client.hitResult
                var hasEntity = false
                if (crosshair != null && crosshair.type == HitResult.Type.ENTITY) {
                    val hitEntity = (crosshair as EntityHitResult).entity
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

            if (MidnightAssistConfig.data.meleeLockOnEnabled && meleeLockOnTargetId != -1) {
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

            if (isHunting) {
                client.options.keyAttack.isDown = false
                ticksSinceTargetAcquired++
            }
        })

        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client: Minecraft ->
            val player = client.player ?: return@EndTick
            val gameMode = client.gameMode ?: return@EndTick

            val entityReach = entityReach(player)

            if (isHunting && lockedTargetId != -1 && pendingAttack && !gameMode.isDestroying()) {
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
                        modCausingAttack = true
                        gameMode.attack(player, target)
                        modCausingAttack = false
                        player.swing(InteractionHand.MAIN_HAND)
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
                        isHunting = false
                        lockedTargetId = -1
                        lockOnCamera = null
                        lastLockTargetId = -1
                        camInitialized = false
                        pendingAttack = false
                        pendingTargetId = -1
                        ticksSinceTargetAcquired = 0
                    }
                } else {
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
            if (isHunting || lockedTargetId != -1 || meleeLockOnTargetId != -1) return@EndTick
            if (!isAttackPressed(client)) return@EndTick

            val crosshair = client.hitResult
            if (crosshair != null && crosshair.type == HitResult.Type.ENTITY) {
                val hitEntity = (crosshair as EntityHitResult).entity
                if (hitEntity.isAlive && hitEntity !is Player) {
                    val cooldown = player.getAttackStrengthScale(0.0f)
                    if (cooldown >= 1.0f && !gameMode.isDestroying()) {
                        gameMode.attack(player, hitEntity)
                        player.swing(InteractionHand.MAIN_HAND)
                    }
                }
            }
        })
    }

    private fun isUsePressed(client: Minecraft): Boolean {
        if (client.screen != null) return false
        return client.options.keyUse.isDown
    }

    private fun isAttackPressed(client: Minecraft): Boolean {
        return client.options.keyAttack.isDown
    }

    private fun entityReach(player: Player): Double {
        return if (player.abilities.instabuild) 5.0 else 3.0
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
        val targetPos = target.boundingBox.center
        val dirToTarget = targetPos.subtract(playerPos).normalize()
        val playerRotation = player.lookAngle
        val dot = playerRotation.dot(dirToTarget)
        return dot > 0.98
    }

    private fun findTarget(client: Minecraft, range: Double, ignoreFov: Boolean = false): Entity? {
        val player = client.player ?: return null
        val level = client.level ?: return null
        val entities = level.getEntities(player, player.boundingBox.inflate(range))

        val priority = MidnightAssistConfig.data.targetPriority
        val accuracy = MidnightAssistConfig.data.aimAccuracy
        val now = System.currentTimeMillis()

        var bestTarget: Entity? = null
        var bestScore = Double.MAX_VALUE

        for (entity in entities) {
            if (entity is Player || !entity.isAlive) continue
            if (!MidnightAssistConfig.isEntityEnabled(entity)) continue
            val dist = entity.distanceTo(player).toDouble()
            if (dist > range) continue
            if (!player.hasLineOfSight(entity)) continue
            if (!ignoreFov && !isLookingAt(player, entity, accuracy)) continue

            val score = when (priority) {
                MidnightAssistConfig.TargetPriority.NEAREST -> dist
                MidnightAssistConfig.TargetPriority.FARTHEST -> -dist
                MidnightAssistConfig.TargetPriority.WEAKEST -> {
                    if (entity is LivingEntity) entity.health.toDouble() + dist * 0.01 else dist
                }
                MidnightAssistConfig.TargetPriority.STRONGEST -> {
                    if (entity is LivingEntity) -(entity.health.toDouble()) + dist * 0.01 else -dist
                }
                MidnightAssistConfig.TargetPriority.LOOKING_AT_YOU -> {
                    val isLooking = if (entity is Mob) entity.target == player else false
                    if (isLooking) dist * 0.5 else dist * 2.0
                }
                MidnightAssistConfig.TargetPriority.RECENTLY_ATTACKED -> {
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

    private fun isLookingAt(player: Player, entity: Entity, accuracy: Double): Boolean {
        val playerPos = player.getEyePosition(1.0f)
        val targetPos = entity.boundingBox.center
        val dirToTarget = targetPos.subtract(playerPos).normalize()
        val playerRotation = player.lookAngle
        val threshold = 1.0 - accuracy
        return playerRotation.dot(dirToTarget) > threshold
    }

    private fun lockedCameraSoft(player: Player, target: Entity) {
        val playerPos = Vec3f(player.x.toFloat(), player.eyeY.toFloat(), player.z.toFloat())
        val bb = target.boundingBox.center
        val targetPos = Vec3f(bb.x.toFloat(), bb.y.toFloat(), bb.z.toFloat())
        val vel = target.deltaMovement
        val targetVel = Vec3f(vel.x.toFloat() * 20f, vel.y.toFloat() * 20f, vel.z.toFloat() * 20f)
        val aimSpeed = MidnightAssistConfig.data.aimSpeed.toFloat()
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
        prevCamYaw = player.yRot
        prevCamPitch = player.xRot
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
        meleeUseReleaseTicks = 0
    }

    @JvmStatic
    fun shouldCancelItemUse(): Boolean {
        val instance = Minecraft.getInstance()
        val player = instance.player ?: return false
        if (instance.screen != null) return false
        val mainHand = player.mainHandItem
        val offHand = player.offhandItem
        if (mainHand.`is`(Items.SHIELD) || offHand.`is`(Items.SHIELD)) return false
        return instance.options.keyUse.isDown && meleeLockOnTargetId != -1 && MidnightAssistConfig.data.meleeLockOnEnabled && MidnightAssistConfig.data.globalEnabled
    }

    @JvmStatic
    fun tryInterceptAttack(client: Minecraft): Boolean {
        if (!MidnightAssistConfig.data.globalEnabled) return false
        if (modCausingAttack) return false
        if (meleeLockOnTargetId != -1) {
            val target = client.level?.getEntity(meleeLockOnTargetId)
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
                return true
            }
        }
        if (isHunting) return true
        if (client.hitResult is EntityHitResult) return false
        val target = findTarget(client, 5.0) ?: return false
        lockedTargetId = target.id
        isHunting = true
        pendingAttack = true
        attackStartTime = System.currentTimeMillis()
        ticksSinceTargetAcquired = 0
        return true
    }

    @JvmStatic
    fun applyPartialTicks(partialTicks: Float) {
        val player = Minecraft.getInstance().player ?: return
        if (!camInitialized || !MidnightAssistConfig.data.globalEnabled) return
        val yawDiff = Mth.wrapDegrees(curCamYaw - prevCamYaw)
        player.setYRot(prevCamYaw + yawDiff * partialTicks)
        player.setXRot(prevCamPitch + (curCamPitch - prevCamPitch) * partialTicks)
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
    private val stiffness = aimSpeed * 400f + 1f
    private val damping = 2f * sqrt(stiffness) * 0.7f

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
    private val rotSpeed = (aimSpeed * 120f).coerceAtLeast(0.01f)

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
        private const val predictionTime = 0.15f
        fun predictTarget(targetPos: Vec3f, targetVel: Vec3f) = targetPos.add(targetVel.mul(predictionTime))
    }

    private fun dampAngle(current: Float, target: Float, dt: Float): Float {
        var diff = target - current
        while (diff > 180f) diff -= 360f
        while (diff < -180f) diff += 360f
        return current + diff * (1f - exp((-rotSpeed * dt).toDouble()).toFloat())
    }
}
