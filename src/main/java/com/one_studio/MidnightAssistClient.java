package com.one_studio;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.util.Mth;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import java.util.Optional;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.client.Minecraft;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.api.ClientModInitializer;

/**
 * MidnightAssistClient - المنظم الرئيسي لخاصية التوجيه التلقائي (Aim Assist)
 * يدير اكتشاف الأهداف تحت المسدس، التثبيت على الهدف، وتصويب الهجوم التلقائي
 */
public class MidnightAssistClient implements ClientModInitializer {

    // المثال الوحيد (Singleton) لهذه الفئة
    public static final MidnightAssistClient INSTANCE = new MidnightAssistClient();

    // معرف الهدف المقفل حالياً (-1 يعني لا يوجد هدف مقفل)
    private static int lockedTargetId = -1;

    // هل زر الهجوم مضغوط فعلاً من اللاعب
    private static boolean realAttackPressed = false;

    // هل النظام في حالة تتبع هدف نشط
    private static boolean isHunting = false;

    // منشئ خاص لمنع إنشاء نسخ متعددة
    private MidnightAssistClient() {
    }

    /**
     * تهيئة العميل عند بدء اللعبة
     * تسجيل أحداث Tic العميل للتحديث المستمر للتصويب التلقائي
     */
    @Override
    public void onInitializeClient() {
        // حدث بداية Tic العميل - يُستدعى كل إطار قبل تحديث اللعبة
        ClientTickEvents.START_CLIENT_TICK.register(client -> onClientStartTick(client));

        // حدث نهاية Tic العميل - يُستدعى كل إطار بعد تحديث اللعبة
        ClientTickEvents.END_CLIENT_TICK.register(client -> onClientEndTick(client));
    }

    /**
     * التحقق مما إذا كان الهدف موجوداً تحت متقاطع التصويب (Crosshair)
     *
     * @param client مثال لعبة Minecraft
     * @param target الكيان المستهدف للفحص
     * @return صحيح إذا كان الهدف تحت المتقاطع
     */
    private boolean isTargetUnderCrosshair(Minecraft client, Entity target) {
        LocalPlayer player = client.player;
        if (player == null) {
            return false;
        }

        // مسافة الوصول القصوى للتصويب (5 بلوك)
        double reachDistance = 5.0;

        // موقع عيني اللاعب في العالم
        Vec3 eyePosition = player.getEyePosition(1.0f);

        // اتجاه نظر اللاعب (متجه الوحدة)
        Vec3 lookAngle = player.getLookAngle();

        // نقطة نهاية خط رؤية اللاعب
        Vec3 endPosition = eyePosition.add(
            lookAngle.x * reachDistance,
            lookAngle.y * reachDistance,
            lookAngle.z * reachDistance
        );

        // صندوق hitbox الهدف مع هامش إضافي لسهولة الاستهداف
        AABB targetBox = target.getBoundingBox().inflate(target.getPickRadius() + 0.1);

        // فحص تقاطع خط الرؤية مع صندوق الهدف
        Optional<Vec3> hitResult = targetBox.clip(eyePosition, endPosition);

        return hitResult.isPresent();
    }

    /**
     * البحث عن أفضل هدف قريب ضمن المدى المحدد
     *
     * @param client مثال لعبة Minecraft
     * @param range مدى البحث عن الأهداف (بالبلوكات)
     * @return أفضل هدف مكتشف أو null إذا لم يوجد
     */
    private Entity findTarget(Minecraft client, double range) {
        LocalPlayer player = client.player;
        if (player == null) {
            return null;
        }

        ClientLevel level = client.level;
        if (level == null) {
            return null;
        }

        // جلب جميع الكيانات ضمن صندوق البحث حول اللاعب
        List<Entity> entities = level.getEntities(
            player,
            player.getBoundingBox().inflate(range)
        );

        Entity bestTarget = null;
        double closestDistance = Double.MAX_VALUE;

        for (Object obj : entities) {
            Entity entity = (Entity) obj;

            // تخطي اللاعبين (لا نصوّب على اللاعبين)
            if (entity instanceof Player) {
                continue;
            }

            // تخطي الكيانات الميتة
            if (!entity.isAlive()) {
                continue;
            }

            // تخطي الكيانات غير المفعلة في الإعدادات
            if (!MidnightAssistConfig.INSTANCE.isEntityEnabled(entity)) {
                continue;
            }

            // حساب المسافة إلى الكيان
            double distanceToEntity = entity.distanceTo(player);

            // التحقق من أن الكيان ضمن المدى، وليس مخفياً، ومقتبلاً تحت المتقاطع، وأقرب من الهدف الحالي
            if (distanceToEntity > range
                || !player.hasLineOfSight(entity)
                || !isLookingAt(player, entity, MidnightAssistConfig.INSTANCE.getData().getAimAccuracy())
                || distanceToEntity >= closestDistance) {
                continue;
            }

            closestDistance = distanceToEntity;
            bestTarget = entity;
        }

        return bestTarget;
    }

    /**
     * التحقق مما إذا كان اللاعب ينظر نحو الكيان المستهدف
     *
     * @param player لاعب Minecraft
     * @param entity الكيان المستهدف
     * @param accuracy دقة التصويب (0.0 إلى 1.0)
     * @return صحيح إذا كان اللاعب ينظر نحو الكيان ضمن حدود الدقة
     */
    private boolean isLookingAt(Player player, Entity entity, double accuracy) {
        Vec3 eyePosition = player.getEyePosition(1.0f);

        // مركز صندوق hitbox الكيان المستهدف
        Vec3 targetCenter = entity.getBoundingBox().getCenter();

        // اتجاه من عيني اللاعب نحو مركز الكيان
        Vec3 directionToTarget = targetCenter.subtract(eyePosition).normalize();

        // اتجاه نظر اللاعب الحالي
        Vec3 playerLookAngle = player.getLookAngle();

        // عتبة الاقتراب - كلما زاد accuracy، صغر العتبة
        double threshold = 1.0 - accuracy;

        // صحيح إذا كان looking direction قريباً من اتجاه الهدف
        return playerLookAngle.dot(directionToTarget) > threshold;
    }

    /**
     * تحريك نظر اللاعب نحو الكيان المستهدف بسرعة محددة
     *
     * @param player لاعب Minecraft
     * @param entity الكيان المستهدف
     * @param speed سرعة التصويب التلقائي (0.0 إلى 1.0+)
     */
    private void snapToEntity(Player player, Entity entity, double speed) {
        Vec3 targetCenter = entity.getBoundingBox().getCenter();

        // الفروقات الأفقية والرأسية بين اللاعب والهدف
        double deltaX = targetCenter.x - player.getX();
        double deltaY = targetCenter.y - player.getEyeY();
        double deltaZ = targetCenter.z - player.getZ();

        // المسافة الأفقية إلى الهدف
        double horizontalDistance = Mth.sqrt((float) (deltaX * deltaX + deltaZ * deltaZ));

        // حساب زاويتي التجديف (yaw) والانحدار (pitch) المطلوبتين
        float targetYaw = (float) (Mth.atan2(deltaZ, deltaX) * 57.295776f) - 90.0f;
        float targetPitch = (float) (-(Mth.atan2(deltaY, horizontalDistance) * 57.295776f));

        // إذا كانت السرعة >= 1.0، نضبط النظر فوراً للهدف
        if (speed >= 1.0) {
            player.setYRot(targetYaw);
            player.setXRot(targetPitch);
            return;
        }

        // حساب الفرق بين الزاوية الحالية والمستهدفة
        float yawDifference = Mth.wrapDegrees(targetYaw - player.getYRot());
        float pitchDifference = targetPitch - player.getXRot();

        // حساب خطوة التحرك حسب السرعة المحددة
        float stepYaw = yawDifference * (float) speed;
        float stepPitch = pitchDifference * (float) speed;

        // تطبيق الخطوة تدريجياً لتجنب الاهتزاز
        player.setYRot(player.getYRot() + stepYaw);
        player.setXRot(player.getXRot() + stepPitch);
    }

    /**
     * معالج حدث بداية Tic العميل
     * يُستدعى كل إطار قبل تحديث حالة اللعبة
     */
    private static void onClientStartTick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }

        ClientLevel level = client.level;
        if (level == null) {
            return;
        }

        // تحديث حالة زر الهجوم الحقيقي
        realAttackPressed = client.options.keyAttack.isDown();

        // إذا كانت الخاصية معطلة، أعد تعيين حالة التتبع
        if (!MidnightAssistConfig.INSTANCE.getData().getGlobalEnabled()) {
            lockedTargetId = -1;
            isHunting = false;
            return;
        }

        Entity currentTarget = null;

        // إذا كان هناك هدف مقفل سابقاً، تحقق من صحته
        if (lockedTargetId != -1) {
            Entity entity = level.getEntity(lockedTargetId);
            if (entity != null && entity.isAlive()
                && entity.distanceTo(player) <= 5.0
                && player.hasLineOfSight(entity)) {
                currentTarget = entity;
            } else {
                // الهدف غير صالح، أعد التعيين
                lockedTargetId = -1;
            }
        }

        // إذا لم يكن هناك هدف والضغط على زر الهجوم، ابحث عن هدف جديد
        if (currentTarget == null && realAttackPressed) {
            currentTarget = INSTANCE.findTarget(client, 5.0);
        }

        if (currentTarget != null) {
            // بدء تتبع الهدف
            isHunting = true;
            lockedTargetId = currentTarget.getId();

            // حساب سرعة التصويب من الإعدادات
            double aimSpeed = MidnightAssistConfig.INSTANCE.getData().getAimSpeed();
            double speedFactor = (aimSpeed <= 0.0) ? 1.0 : aimSpeed;

            // تحريك نظر اللاعب نحو الهدف
            INSTANCE.snapToEntity(player, currentTarget, speedFactor);

            // إلغاء ضغط زر الهجوم التلقائي واستهلاك النقرات المعلقة
            client.options.keyAttack.setDown(false);
            while (client.options.keyAttack.consumeClick()) {
                // استهلاك النقرات لمنع الهجوم المزدوج
            }
        } else {
            // لا يوجد هدف، أوقف حالة التتبع
            isHunting = false;
            lockedTargetId = -1;
        }
    }

    /**
     * معالج حدث نهاية Tic العميل
     * يُستدعى كل إطار بعد تحديث حالة اللعبة
     */
    private static void onClientEndTick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }

        MultiPlayerGameMode gameMode = client.gameMode;
        if (gameMode == null) {
            return;
        }

        // إذا لم يكن هناك تتبع نشط، استعادة زر الهجوم للطبيعي
        if (!isHunting || lockedTargetId == -1 || gameMode.isDestroying()) {
            client.options.keyAttack.setDown(realAttackPressed);
            return;
        }

        ClientLevel level = client.level;
        Entity target = (level != null) ? level.getEntity(lockedTargetId) : null;

        if (target == null) {
            // الهدف اختفى، أوقف التتبع
            isHunting = false;
            lockedTargetId = -1;
            client.options.keyAttack.setDown(realAttackPressed);
            return;
        }

        // التحقق مما إذا كان الهدف تحت متقاطع التصويب
        boolean isTouchingHitbox = INSTANCE.isTargetUnderCrosshair(client, target);

        // إذا كان الهدف تحت المتقاطع وقوة الهجوم كاملة، نفذ الهجوم
        if (isTouchingHitbox && player.getAttackStrengthScale(0.0f) >= 1.0f) {
            gameMode.attack(player, target);
            player.swing(InteractionHand.MAIN_HAND);

            // إذا لم يكن اللاعب يضغط على زر الهجوم، أوقف التتبع بعد الضربة
            if (!realAttackPressed) {
                isHunting = false;
                lockedTargetId = -1;
            }
        }

        // إدارة حالة زر الهجوم أثناء التتبع
        if (isHunting) {
            client.options.keyAttack.setDown(false);
        } else {
            client.options.keyAttack.setDown(realAttackPressed);
        }
    }
}
