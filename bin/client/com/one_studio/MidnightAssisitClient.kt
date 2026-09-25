package com.one_studio

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text
import net.minecraft.entity.Entity
import net.minecraft.entity.mob.MobEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.decoration.ArmorStandEntity
import net.minecraft.entity.vehicle.BoatEntity
import net.minecraft.entity.vehicle.AbstractMinecartEntity
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d

import net.minecraft.util.Hand
import net.minecraft.util.hit.EntityHitResult
import net.minecraft.util.hit.HitResult

object MidnightAssisitClient : ClientModInitializer {
    private var lockedTargetId: Int = -1
    private var realAttackPressed: Boolean = false
    private var isHunting: Boolean = false
    
    override fun onInitializeClient() {
        // 1. التصويب في بداية الـ Tick
        ClientTickEvents.START_CLIENT_TICK.register(ClientTickEvents.StartTick { client: MinecraftClient ->
            val player = client.player ?: return@StartTick
            val world = client.world ?: return@StartTick

            // حفظ الحالة الحقيقية للزر
            realAttackPressed = client.options.attackKey.isPressed

            if (!MidnightAssisitConfig.data.globalEnabled) {
                lockedTargetId = -1
                isHunting = false
                return@StartTick
            }

            // البحث عن هدف
            var currentTarget: Entity? = null
            
            // التمسك بالهدف الحالي أولاً
            if (lockedTargetId != -1) {
                val entity = world.getEntityById(lockedTargetId)
                if (entity != null && entity.isAlive && entity.distanceTo(player) <= 5.0 && player.canSee(entity)) {
                    currentTarget = entity
                } else {
                    lockedTargetId = -1
                }
            }

            // إذا لم يوجد هدف مقفل واللاعب يضغط الزر، نبحث عن هدف جديد
            if (currentTarget == null && realAttackPressed) {
                currentTarget = findTarget(client, 5.0)
            }

            // تحديث الحالة بناءً على وجود الهدف
            if (currentTarget != null) {
                isHunting = true
                lockedTargetId = currentTarget.id
                
                // تنفيذ التصويب
                val aimSpeed = MidnightAssisitConfig.data.aimSpeed
                val factor = if (aimSpeed <= 0) 1.0 else aimSpeed
                snapToEntity(player, currentTarget, factor)

                // --- حجب الزر عن اللعبة فقط إذا وجدنا هدفاً ---
                // هذا يمنع اللعبة من تنفيذ ضربة عشوائية بينما المود يصوب
                client.options.attackKey.isPressed = false
                while (client.options.attackKey.wasPressed()) {}
            } else {
                // لا يوجد هدف، نلغي حالة المطاردة ونترك الزر للعبة لتعمل بشكل طبيعي
                isHunting = false
                lockedTargetId = -1
            }
        })

        // 2. الهجوم في نهاية الـ Tick
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client: MinecraftClient ->
            val player = client.player ?: return@EndTick
            val interactionManager = client.interactionManager ?: return@EndTick
            
            // إذا لم نكن في حالة مطاردة أو كنا نكسر بلوكات، نترك اللعبة تتصرف طبيعياً
            if (!isHunting || lockedTargetId == -1 || interactionManager.isBreakingBlock) {
                client.options.attackKey.isPressed = realAttackPressed
                return@EndTick
            }
            
            val target = client.world?.getEntityById(lockedTargetId)
            if (target == null) {
                isHunting = false
                lockedTargetId = -1
                client.options.attackKey.isPressed = realAttackPressed
                return@EndTick
            }
            
            // فحص ملامسة الـ Hitbox
            val isTouchingHitbox = isTargetUnderCrosshair(client, target)

            // الهجوم اليدوي الحصري (يتم فقط عندما يكون المؤشر فوق الهدف والـ Cooldown جاهز)
            if (isTouchingHitbox && player.getAttackCooldownProgress(0.0f) >= 1.0f) {
                interactionManager.attackEntity(player, target)
                player.swingHand(Hand.MAIN_HAND)
                
                // إذا كان اللاعب قد رفع إصبعه عن الزر، نتوقف بعد الضربة الناجحة
                if (!realAttackPressed) {
                    isHunting = false
                    lockedTargetId = -1
                }
            }
            
            // طالما أننا نطارد هدفاً، نبقي الزر محجوباً عن اللعبة لمنع "الضربة المزدوجة"
            if (isHunting) {
                client.options.attackKey.isPressed = false
            } else {
                client.options.attackKey.isPressed = realAttackPressed
            }
        })
    }

    private fun isTargetUnderCrosshair(client: MinecraftClient, target: Entity): Boolean {
        val player = client.player ?: return false
        val reach = 5.0 // نطاق البحث
        val cameraPos = player.getCameraPosVec(1.0f)
        val rotation = player.getRotationVec(1.0f)
        val endPos = cameraPos.add(rotation.x * reach, rotation.y * reach, rotation.z * reach)
        
        // فحص تقاطع الشعاع مع الـ Hitbox الخاص بالهدف
        val box = target.boundingBox.expand(target.targetingMargin.toDouble() + 0.1)
        val hit = box.raycast(cameraPos, endPos)
        
        return hit.isPresent
    }

    private fun findTarget(client: MinecraftClient, range: Double): Entity? {
        val player = client.player ?: return null
        val world = client.world ?: return null
        
        // البحث في نطاق دائري كامل حول اللاعب لضمان عدم فوات أي هدف
        val entities = world.getOtherEntities(player, player.boundingBox.expand(range))

        var bestTarget: Entity? = null
        var closestDist = Double.MAX_VALUE

        for (entity in entities) {
            if (entity is PlayerEntity || !entity.isAlive) continue
            if (!MidnightAssisitConfig.isEntityEnabled(entity)) continue

            val dist = entity.distanceTo(player).toDouble()
            if (dist <= range) {
                // فحص الرؤية
                if (!player.canSee(entity)) continue

                // فحص الدقة (FOV)
                if (isLookingAt(player, entity, MidnightAssisitConfig.data.aimAccuracy)) {
                    if (dist < closestDist) {
                        closestDist = dist
                        bestTarget = entity
                    }
                }
            }
        }
        return bestTarget
    }

    private fun isLookingAt(player: PlayerEntity, entity: Entity, accuracy: Double): Boolean {
        val playerPos = player.getCameraPosVec(1.0f)
        val targetPos = entity.boundingBox.center
        val dirToTarget = targetPos.subtract(playerPos).normalize()
        val playerRotation = player.getRotationVec(1.0f)
        
        // accuracy هنا تعبر عن المسافة المسموح بها من المركز
        // كلما زاد الرقم زاد المدى المسموح به (FOV)
        val threshold = 1.0 - accuracy
        return playerRotation.dotProduct(dirToTarget) > threshold
    }

    private fun snapToEntity(player: PlayerEntity, entity: Entity, speed: Double) {
        val targetPos = entity.boundingBox.center
        val dx = targetPos.x - player.getX()
        val dy = targetPos.y - player.getEyeY()
        val dz = targetPos.z - player.getZ()
        val dh = MathHelper.sqrt((dx * dx + dz * dz).toFloat()).toDouble()

        val targetYaw = (MathHelper.atan2(dz, dx) * MathHelper.DEGREES_PER_RADIAN).toFloat() - 90.0f
        val targetPitch = (-(MathHelper.atan2(dy, dh) * MathHelper.DEGREES_PER_RADIAN)).toFloat()

        // إذا كانت السرعة 1.0 نقوم بالقفز الفوري
        if (speed >= 1.0) {
            player.setYaw(targetYaw)
            player.setPitch(targetPitch)
            return
        }

        // تحسين السلاسة: استخدام سرعة ثابتة (Constant Speed) لتقليل التقطع
        // السرعة هنا تعبر عن مدى القرب من الهدف في كل Tick
        val yawDiff = MathHelper.wrapDegrees(targetYaw - player.yaw)
        val pitchDiff = targetPitch - player.pitch

        // كلما زادت السرعة زاد الجزء الذي نتحركه في كل Tick
        val stepYaw = yawDiff * speed.toFloat()
        val stepPitch = pitchDiff * speed.toFloat()

        player.setYaw(player.yaw + stepYaw)
        player.setPitch(player.pitch + stepPitch)
    }
}