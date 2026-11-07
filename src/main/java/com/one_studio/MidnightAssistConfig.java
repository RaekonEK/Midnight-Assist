package com.one_studio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.DefaultedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.charset.Charset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * MidnightAssistConfig - مدير إعدادات مود Midnight Assist
 * يدير تحميل وحفظ الإعدادات، تفعيل/تعطيل الكيانات، والتعيينات المسبقة (Presets)
 */
public class MidnightAssistConfig {

    // المثال الوحيد (Singleton) لإدارة الإعدادات
    public static final MidnightAssistConfig INSTANCE = new MidnightAssistConfig();

    // أداة تحويل JSON للقراءة والكتابة
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ملف الإعدادات في مجلد config الخاص بـ Fabric Loader
    private static final File CONFIG_FILE = FabricLoader.getInstance()
        .getConfigDir()
        .resolve("midnight-assist.json")
        .toFile();

    // بيانات الإعدادات الحالية
    private static ConfigData data = new ConfigData();

    // منشئ خاص لمنع إنشاء نسخ متعددة
    private MidnightAssistConfig() {
    }

    /**
     * جلب بيانات الإعدادات الحالية
     *
     * @return كائن ConfigData يحتوي على جميع الإعدادات
     */
    @NotNull
    public final ConfigData getData() {
        return data;
    }

    /**
     * تعيين بيانات الإعدادات الجديدة
     *
     * @param newData بيانات الإعدادات الجديدة
     */
    public final void setData(@NotNull ConfigData newData) {
        data = newData;
    }

    /**
     * تحميل الإعدادات من ملف config
     * إذا لم يوجد الملف، يتم إنشاء إعدادات افتراضية
     */
    public final void load() {
        if (CONFIG_FILE.exists()) {
            try {
                // قراءة محتوى الملف وتحويله إلى كائن ConfigData
                String jsonContent = kotlin.io.FilesKt.readText$default(CONFIG_FILE, null, 1, null);
                Object parsed = GSON.fromJson(jsonContent, ConfigData.class);
                data = (ConfigData) parsed;
            } catch (Exception e) {
                // في حالة فشل القراءة، احفظ إعدادات افتراضية
                save();
            }
        }

        // تأكد من إزالة اللاعب من قائمة الكيانات المفعلة (لا نضرب اللاعبين)
        data.getEnabledEntities().remove("minecraft:player");

        boolean changed = false;

        //遍历 جميع أنواع الكيانات المسجلة في اللعبة وإضافة الجديدة إلى الإعدادات
        DefaultedRegistry<EntityType<?>> entityTypeRegistry = BuiltInRegistries.ENTITY_TYPE;
        for (Object obj : entityTypeRegistry) {
            EntityType<?> entityType = (EntityType<?>) obj;
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(entityType).toString();

            // تخطي اللاعب
            if (id.equals("minecraft:player")) {
                continue;
            }

            // إذا كان النوع غير موجود في الإعدادات، أضفه بالقيمة الافتراضية الذكية
            if (!data.getEnabledEntities().containsKey(id)) {
                boolean smartDefault = isSmartDefault(entityType, id);
                data.getEnabledEntities().put(id, smartDefault);
                changed = true;
            }
        }

        // حفظ إذا كانت هناك تغييرات أو إذا كان الملف غير موجود
        if (changed || !CONFIG_FILE.exists()) {
            save();
        }
    }

    /**
     * تحديد ما إذا كان نوع الكيان مفعلاً افتراضياً (Smart Default)
     *
     * @param type نوع الكيان
     * @param id معرّف الكيان (مثل "minecraft:zombie")
     * @return صحيح إذا كان الكيان مفعلاً افتراضياً
     */
    public final boolean isSmartDefault(@NotNull EntityType<?> type, @NotNull String id) {
        // اللاعب أبداً لا يكون هدفاً
        if (id.equals("minecraft:player")) {
            return false;
        }

        MobCategory category = type.getCategory();

        // الكيانات التي لها ذكاء اصطناعي (ليست Misc)
        boolean hasAI = category != MobCategory.MISC;

        // الميكانيكيين والقرّادين يعتبرون كيانات خاصة
        boolean isSpecialMob = id.contains("golem") || id.contains("villager");

        return hasAI || isSpecialMob;
    }

    /**
     * تطبيق تعيين مسبق (Preset) على جميع الكيانات
     *
     * @param preset التعيين المسبق المراد تطبيقه
     */
    public final void applyPreset(@NotNull Preset preset) {
        if (preset == Preset.NONE) {
            return;
        }

        DefaultedRegistry<EntityType<?>> entityTypeRegistry = BuiltInRegistries.ENTITY_TYPE;

        for (Object obj : entityTypeRegistry) {
            EntityType<?> type = (EntityType<?>) obj;
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();

            // اللاعب لا يضاف أبداً
            if (id.equals("minecraft:player")) {
                data.getEnabledEntities().remove(id);
                continue;
            }

            MobCategory category = type.getCategory();

            // تصنيف الكيانات
            boolean isHostile = category == MobCategory.MONSTER;
            boolean isPassive = category == MobCategory.CREATURE
                || category == MobCategory.AMBIENT
                || category == MobCategory.WATER_CREATURE
                || category == MobCategory.WATER_AMBIENT
                || category == MobCategory.AXOLOTLS
                || category == MobCategory.UNDERGROUND_WATER_CREATURE;

            // قائمة الكيانات المحايدة (قد تهاجم إذا تم مهاجمتها)
            List<String> neutralKeywords = List.of(
                "spider", "enderman", "piglin", "wolf", "bee",
                "golem", "llama", "bear", "dolphin", "goat",
                "panda", "warden"
            );

            boolean isNeutral = neutralKeywords.stream().anyMatch(id::contains);

            // الأبعاد التي يظهر فيها الكيان
            Set<String> dimensions = detectDimensions(type, id);
            boolean isOverworld = dimensions.contains("overworld");
            boolean isNether = dimensions.contains("nether");
            boolean isEnd = dimensions.contains("end");

            // تحديد حالة التفعيل حسب التعيين المسبق
            boolean enabled = switch (preset) {
                case PASSIVE_ONLY -> isPassive && !isNeutral;
                case HOSTILE_ONLY -> isHostile && !isNeutral;
                case PASSIVE_AND_HOSTILE -> (isPassive || isHostile) && !isNeutral;
                case NEUTRAL_ONLY -> isNeutral;
                case NEUTRAL_AND_HOSTILE -> isNeutral || isHostile;
                case NEUTRAL_AND_PASSIVE -> isNeutral || isPassive;
                case ALL_MOBS -> isPassive || isHostile || isNeutral;
                case ALL_ENTITIES -> true;
                case OVERWORLD -> isOverworld;
                case NETHER -> isNether;
                case END -> isEnd;
                case NONE -> data.getEnabledEntities().getOrDefault(
                    id, isSmartDefault(type, id)
                );
            };

            data.getEnabledEntities().put(id, enabled);
        }
    }

    /**
     * اكتشاف الأبعاد التي يظهر فيها نوع الكيان المحدد
     *
     * @param type نوع الكيان
     * @param id معرّف الكيان
     * @return مجموعة من أسماء الأبعاد (overworld, nether, end)
     */
    private Set<String> detectDimensions(@NotNull EntityType<?> type, @NotNull String id) {
        Set<String> dimensions = new LinkedHashSet<>();

        // эндерمان يظهر في جميع الأبعاد
        if (id.contains("enderman")) {
            return Set.of("overworld", "nether", "end");
        }

        // كيانات نيثر الحصرية
        List<String> netherKeywords = List.of(
            "piglin", "hoglin", "blaze", "ghast", "strider",
            "magma_cube", "wither_skeleton"
        );
        if (netherKeywords.stream().anyMatch(id::contains)) {
            dimensions.add("nether");
        }

        // كيانات إند الحصرية
        List<String> endKeywords = List.of("shulker", "ender_dragon", "endermite");
        if (endKeywords.stream().anyMatch(id::contains)) {
            dimensions.add("end");
        }

        // إذا لم يكن نيثر أو إند حصراً، أضف overworld
        List<String> netherExclusive = List.of(
            "blaze", "ghast", "strider", "magma_cube", "wither_skeleton"
        );
        boolean isNetherExclusive = netherExclusive.stream().anyMatch(id::contains);

        List<String> endExclusive = List.of("shulker", "ender_dragon");
        boolean isEndExclusive = endExclusive.stream().anyMatch(id::contains);

        if (!isNetherExclusive && !isEndExclusive) {
            dimensions.add("overworld");
        }

        // هيكلي يظهر في overworld و nether
        if (id.contains("skeleton") && !id.contains("wither")) {
            dimensions.add("nether");
        }

        // الدجاج يظهر في overworld و nether
        if ((id.contains("chicken") || id.contains("ghast")) && id.contains("chicken")) {
            dimensions.add("nether");
        }

        return dimensions;
    }

    /**
     * حفظ الإعدادات الحالية إلى ملف config
     */
    public final void save() {
        try {
            // إذا كان هناك تعيين مسبق أخير، طبّقه قبل الحفظ
            if (data.getLastAppliedPreset() != Preset.NONE) {
                applyPreset(data.getLastAppliedPreset());
                data.setLastAppliedPreset(Preset.NONE);
            }

            // تأكد من إزالة اللاعب من قائمة الكيانات المفعلة
            data.getEnabledEntities().remove("minecraft:player");

            // تحويل البيانات إلى JSON وكتابتها إلى الملف
            String json = GSON.toJson(data);
            kotlin.io.FilesKt.writeText$default(CONFIG_FILE, json, null, 2, null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * جلب نوع الكيان بناءً على معرفه النصي
     *
     * @param id معرّف الكيان (مثل "minecraft:zombie")
     * @return نوع الكيان أو null إذا لم يوجد
     */
    @Nullable
    public final EntityType<?> getEntityType(@NotNull String id) {
        for (Object obj : BuiltInRegistries.ENTITY_TYPE) {
            EntityType<?> type = (EntityType<?>) obj;
            String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
            if (typeId.equals(id)) {
                return type;
            }
        }
        return null;
    }

    /**
     * التحقق مما إذا كان الكيان مفعّلاً في الإعدادات
     *
     * @param entity الكيان المراد فحصه
     * @return صحيح إذا كان الكيان مفعّلاً
     */
    public final boolean isEntityEnabled(@NotNull Entity entity) {
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();

        // اللاعب nunca يكون هدفاً
        if (id.equals("minecraft:player")) {
            return false;
        }

        Boolean enabled = data.getEnabledEntities().get(id);
        if (enabled != null) {
            return enabled;
        }

        // إذا لم يكن موجوداً، استخدم القيمة الافتراضية الذكية
        EntityType<?> type = entity.getType();
        return isSmartDefault(type, id);
    }

    // ==========================================
    // التعيينات المسبقة (Presets)
    // ==========================================
    public enum Preset {
        NONE,
        PASSIVE_ONLY,
        HOSTILE_ONLY,
        PASSIVE_AND_HOSTILE,
        NEUTRAL_ONLY,
        NEUTRAL_AND_HOSTILE,
        NEUTRAL_AND_PASSIVE,
        ALL_MOBS,
        ALL_ENTITIES,
        OVERWORLD,
        NETHER,
        END;

        /**
         * جلب مفتاح الترجمة لهذا التعيين المسبق
         *
         * @return مفتاح الترجمة المستخدم في ملفات اللغة
         */
        @NotNull
        public final String getTranslationKey() {
            String key = this.name().toLowerCase(Locale.ROOT);
            return "option.midnight-assist.preset." + key;
        }
    }

    /**
     * فئة بيانات الإعدادات (Data Class)
     * تحتوي على جميع القيم القابلة للتخصيص من قبل المستخدم
     */
    public static class ConfigData {
        // تفعيل/تعطيل المود بشكل عام
        private boolean globalEnabled = false;

        // دقة التصويب (0.0 إلى 1.0، كلما زادت كانت الدقة أعلى)
        private double aimAccuracy = 0.0;

        // سرعة التصويب التلقائي (0.0 إلى 1.0+، كلما زادت كانت السرعة أعلى)
        private double aimSpeed = 0.0;

        // آخر تعيين مسبق تم تطبيقه
        @NotNull
        private Preset lastAppliedPreset = Preset.NONE;

        // خريطة معرّفات الكيانات إلى حالة التفعيل
        @NotNull
        private final Map<String, Boolean> enabledEntities = new java.util.LinkedHashMap<>();

        /**
         * منشئ بيانات الإعدادات
         */
        public ConfigData(boolean globalEnabled, double aimAccuracy, double aimSpeed,
                          @NotNull Preset lastAppliedPreset,
                          @NotNull Map<String, Boolean> enabledEntities) {
            this.globalEnabled = globalEnabled;
            this.aimAccuracy = aimAccuracy;
            this.aimSpeed = aimSpeed;
            this.lastAppliedPreset = lastAppliedPreset;
            this.enabledEntities = enabledEntities;
        }

        // منشئ افتراضي بقيم ابتدائية
        public ConfigData() {
            this(false, 0.0, 0.0, Preset.NONE, new java.util.LinkedHashMap<>());
        }

        // Getters and Setters
        public final boolean getGlobalEnabled() {
            return globalEnabled;
        }

        public final void setGlobalEnabled(boolean globalEnabled) {
            this.globalEnabled = globalEnabled;
        }

        public final double getAimAccuracy() {
            return aimAccuracy;
        }

        public final void setAimAccuracy(double aimAccuracy) {
            this.aimAccuracy = aimAccuracy;
        }

        public final double getAimSpeed() {
            return aimSpeed;
        }

        public final void setAimSpeed(double aimSpeed) {
            this.aimSpeed = aimSpeed;
        }

        @NotNull
        public final Preset getLastAppliedPreset() {
            return lastAppliedPreset;
        }

        public final void setLastAppliedPreset(@NotNull Preset lastAppliedPreset) {
            this.lastAppliedPreset = lastAppliedPreset;
        }

        @NotNull
        public final Map<String, Boolean> getEnabledEntities() {
            return enabledEntities;
        }
    }
}
