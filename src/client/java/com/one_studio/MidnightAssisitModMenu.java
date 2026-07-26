package com.one_studio;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MidnightAssisitModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Text.translatable("title.midnight-assist.config"))
                .setSavingRunnable(MidnightAssisitConfig.INSTANCE::save);

            var entryBuilder = builder.entryBuilder();
            var general = builder.getOrCreateCategory(Text.translatable("category.midnight-assist.general"));

            general.addEntry(entryBuilder.startBooleanToggle(Text.translatable("option.midnight-assist.global_enabled"), MidnightAssisitConfig.INSTANCE.getData().getGlobalEnabled())
                .setDefaultValue(true)
                .setSaveConsumer(MidnightAssisitConfig.INSTANCE.getData()::setGlobalEnabled)
                .build());

            general.addEntry(entryBuilder.startDoubleField(Text.translatable("option.midnight-assist.aim_accuracy"), MidnightAssisitConfig.INSTANCE.getData().getAimAccuracy())
                .setDefaultValue(0.2)
                .setMin(0.0)
                .setMax(1.0)
                .setTooltip(Text.translatable("option.midnight-assist.aim_accuracy.tooltip"))
                .setSaveConsumer(MidnightAssisitConfig.INSTANCE.getData()::setAimAccuracy)
                .build());

            general.addEntry(entryBuilder.startDoubleField(Text.translatable("option.midnight-assist.aim_speed"), MidnightAssisitConfig.INSTANCE.getData().getAimSpeed())
                .setDefaultValue(0.5)
                .setMin(0.0)
                .setMax(1.0)
                .setTooltip(Text.translatable("option.midnight-assist.aim_speed.tooltip"))
                .setSaveConsumer(MidnightAssisitConfig.INSTANCE.getData()::setAimSpeed)
                .build());

            // Smart Target Priority
            general.addEntry(entryBuilder.startEnumSelector(
                Text.translatable("option.midnight-assist.target_priority"),
                MidnightAssisitConfig.TargetPriority.class,
                MidnightAssisitConfig.INSTANCE.getData().getTargetPriority()
            ).setDefaultValue(MidnightAssisitConfig.TargetPriority.NEAREST)
                .setTooltip(Text.translatable("option.midnight-assist.target_priority.tooltip"))
                .setSaveConsumer(MidnightAssisitConfig.INSTANCE.getData()::setTargetPriority)
                .build());

            // Melee Lock-On
            general.addEntry(entryBuilder.startBooleanToggle(Text.translatable("option.midnight-assist.melee_lock_on"), MidnightAssisitConfig.INSTANCE.getData().getMeleeLockOnEnabled())
                .setDefaultValue(true)
                .setTooltip(Text.translatable("option.midnight-assist.melee_lock_on.tooltip"))
                .setSaveConsumer(MidnightAssisitConfig.INSTANCE.getData()::setMeleeLockOnEnabled)
                .build());

            // Presets
            general.addEntry(entryBuilder.startEnumSelector(Text.translatable("option.midnight-assist.preset"), MidnightAssisitConfig.Preset.class, MidnightAssisitConfig.Preset.NONE)
                .setSaveConsumer(MidnightAssisitConfig.INSTANCE.getData()::setLastAppliedPreset)
                .setDefaultValue(MidnightAssisitConfig.Preset.NONE)
                .setTooltip(Text.translatable("option.midnight-assist.preset.tooltip"))
                .build());

            // Entities Category
            var entities = builder.getOrCreateCategory(Text.translatable("category.midnight-assist.entities"));

            List<String> sortedEntities = new ArrayList<>(MidnightAssisitConfig.INSTANCE.getData().getEnabledEntities().keySet());
            Collections.sort(sortedEntities);

            for (String id : sortedEntities) {
                var entityType = MidnightAssisitConfig.INSTANCE.getEntityType(id);

                if (entityType == null) continue;

                var entityName = entityType.getName();
                boolean smartDefault = MidnightAssisitConfig.INSTANCE.isSmartDefault(entityType, id);

                entities.addEntry(entryBuilder.startBooleanToggle(entityName, MidnightAssisitConfig.INSTANCE.getData().getEnabledEntities().getOrDefault(id, smartDefault))
                    .setDefaultValue(smartDefault)
                    .setSaveConsumer(value -> MidnightAssisitConfig.INSTANCE.getData().getEnabledEntities().put(id, value))
                    .build());
            }

            return builder.build();
        };
    }
}
