package com.one_studio;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.impl.builders.BooleanToggleBuilder;
import me.shedaniel.clothconfig2.impl.builders.DoubleFieldBuilder;
import me.shedaniel.clothconfig2.impl.builders.EnumSelectorBuilder;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.EntityType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * MidnightAssistModMenu - واجهة إعدادات المود في شاشة Mod Menu
 * تسمح للاعب بتعديل إعدادات التصويب التلقائي من داخل اللعبة
 */
public class MidnightAssistModMenu implements ModMenuApi {

    /**
     * جلب مصنع شاشة الإعدادات (Config Screen Factory)
     * يُستدعى عندما يضغط اللاعب على زر الإعدادات في شاشة Mod Menu
     *
     * @return مصنع شاشة الإعدادات
     */
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            // إنشاء شاشة الإعدادات مع العنوان
            ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("title.midnight-assist.config"));

            // تعيين دالة الحفظ عند إغلاق الشاشة
            builder.setSavingRunnable(MidnightAssistConfig.INSTANCE::save);

            ConfigEntryBuilder entryBuilder = builder.entryBuilder();

            // ==========================================
            // الفئة العامة (General Settings)
            // ==========================================
            ConfigCategory general = builder.getOrCreateCategory(
                Component.translatable("category.midnight-assist.general")
            );

            // خيار التفعيل العام للمود (تشغيل/إيقاف)
            BooleanToggleBuilder globalEnabledToggle = entryBuilder.startBooleanToggle(
                Component.translatable("option.midnight-assist.global_enabled"),
                MidnightAssistConfig.INSTANCE.getData().getGlobalEnabled()
            ).setDefaultValue(true);

            var generalData = MidnightAssistConfig.INSTANCE.getData();
            Objects.requireNonNull(generalData);
            general.addEntry(
                globalEnabledToggle.setSaveConsumer((Consumer<Boolean>) generalData::setGlobalEnabled).build()
            );

            // خيار دقة التصويب (Aim Accuracy)
            DoubleFieldBuilder aimAccuracyField = entryBuilder.startDoubleField(
                Component.translatable("option.midnight-assist.aim_accuracy"),
                MidnightAssistConfig.INSTANCE.getData().getAimAccuracy()
            ).setDefaultValue(0.2)
              .setMin(0.0)
              .setMax(1.0)
              .setTooltip(new Component[] {
                  Component.translatable("option.midnight-assist.aim_accuracy.tooltip")
              });

            var accuracyData = MidnightAssistConfig.INSTANCE.getData();
            Objects.requireNonNull(accuracyData);
            general.addEntry(
                aimAccuracyField.setSaveConsumer((Consumer<Double>) accuracyData::setAimAccuracy).build()
            );

            // خيار سرعة التصويب (Aim Speed)
            DoubleFieldBuilder aimSpeedField = entryBuilder.startDoubleField(
                Component.translatable("option.midnight-assist.aim_speed"),
                MidnightAssistConfig.INSTANCE.getData().getAimSpeed()
            ).setDefaultValue(0.5)
              .setMin(0.0)
              .setMax(1.0)
              .setTooltip(new Component[] {
                  Component.translatable("option.midnight-assist.aim_speed.tooltip")
              });

            var speedData = MidnightAssistConfig.INSTANCE.getData();
            Objects.requireNonNull(speedData);
            general.addEntry(
                aimSpeedField.setSaveConsumer((Consumer<Double>) speedData::setAimSpeed).build()
            );

            // خيار التعيين المسبق للكيان (Preset Selector)
            EnumSelectorBuilder presetSelector = entryBuilder.startEnumSelector(
                Component.translatable("option.midnight-assist.preset"),
                MidnightAssistConfig.Preset.class,
                MidnightAssistConfig.Preset.NONE
            );

            var presetData = MidnightAssistConfig.INSTANCE.getData();
            Objects.requireNonNull(presetData);
            general.addEntry(
                presetSelector.setSaveConsumer((Consumer<MidnightAssistConfig.Preset>) presetData::setLastAppliedPreset)
                             .setDefaultValue(MidnightAssistConfig.Preset.NONE)
                             .setTooltip(new Component[] {
                                 Component.translatable("option.midnight-assist.preset.tooltip")
                             })
                             .build()
            );

            // ==========================================
            // فئة الكيانات (Entities Settings)
            // ==========================================
            ConfigCategory entities = builder.getOrCreateCategory(
                Component.translatable("category.midnight-assist.entities")
            );

            // جلب قائمة جميع معرفات الكيانات المفعلة، مرتبة أبجدياً
            List<String> sortedEntityIds = new ArrayList<>(
                MidnightAssistConfig.INSTANCE.getData().getEnabledEntities().keySet()
            );
            Collections.sort(sortedEntityIds);

            // إنشاء خيار تفعيل/تعطيل لكل كيان
            for (String entityId : sortedEntityIds) {
                EntityType<?> entityType = MidnightAssistConfig.INSTANCE.getEntityType(entityId);
                if (entityType == null) {
                    continue;
                }

                // اسم الكيان المترجم للعرض في واجهة المستخدم
                MutableComponent entityName = Component.translatable(entityType.getDescriptionId());

                // القيمة الافتراضية الذكية لهذا الكيان
                boolean smartDefault = MidnightAssistConfig.INSTANCE.isSmartDefault(entityType, entityId);

                // قيمة التفعيل الحالية (أو الافتراضية إذا لم تحدد)
                boolean currentEnabled = MidnightAssistConfig.INSTANCE.getData()
                    .getEnabledEntities()
                    .getOrDefault(entityId, smartDefault);

                // إنشاء خيار التبديل للكيان
                entities.addEntry(
                    entryBuilder.startBooleanToggle(entityName, currentEnabled)
                             .setDefaultValue(smartDefault)
                             .setSaveConsumer((Consumer<Boolean>) value ->
                                 MidnightAssistConfig.INSTANCE.getData()
                                     .getEnabledEntities()
                                     .put(entityId, value)
                             )
                             .build()
                );
            }

            return builder.build();
        };
    }
}
