package com.one_studio;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MidnightAssistModMenu implements ModMenuApi {
@Override
public ConfigScreenFactory<?> getModConfigScreenFactory() {
return parent -> {
ConfigBuilder builder = ConfigBuilder.create()
.setParentScreen(parent)
.setTitle(Component.translatable("title.midnight-assist.config"))
.setSavingRunnable(MidnightAssistConfig.INSTANCE::save);

var entryBuilder = builder.entryBuilder();
var general = builder.getOrCreateCategory(Component.translatable("category.midnight-assist.general"));

general.addEntry(entryBuilder.startBooleanToggle(Component.translatable("option.midnight-assist.global_enabled"), MidnightAssistConfig.INSTANCE.getData().getGlobalEnabled())
.setDefaultValue(true)
.setSaveConsumer(MidnightAssistConfig.INSTANCE.getData()::setGlobalEnabled)
.build());

general.addEntry(entryBuilder.startDoubleField(Component.translatable("option.midnight-assist.aim_accuracy"), MidnightAssistConfig.INSTANCE.getData().getAimAccuracy())
.setDefaultValue(0.2)
.setMin(0.0)
.setMax(1.0)
.setTooltip(Component.translatable("option.midnight-assist.aim_accuracy.tooltip"))
.setSaveConsumer(MidnightAssistConfig.INSTANCE.getData()::setAimAccuracy)
.build());

general.addEntry(entryBuilder.startDoubleField(Component.translatable("option.midnight-assist.aim_speed"), MidnightAssistConfig.INSTANCE.getData().getAimSpeed())
.setDefaultValue(0.5)
.setMin(0.0)
.setMax(1.0)
.setTooltip(Component.translatable("option.midnight-assist.aim_speed.tooltip"))
.setSaveConsumer(MidnightAssistConfig.INSTANCE.getData()::setAimSpeed)
.build());

general.addEntry(entryBuilder.startEnumSelector(
Component.translatable("option.midnight-assist.target_priority"),
MidnightAssistConfig.TargetPriority.class,
MidnightAssistConfig.INSTANCE.getData().getTargetPriority()
).setDefaultValue(MidnightAssistConfig.TargetPriority.NEAREST)
.setTooltip(Component.translatable("option.midnight-assist.target_priority.tooltip"))
.setSaveConsumer(MidnightAssistConfig.INSTANCE.getData()::setTargetPriority)
.build());

general.addEntry(entryBuilder.startBooleanToggle(Component.translatable("option.midnight-assist.melee_lock_on"), MidnightAssistConfig.INSTANCE.getData().getMeleeLockOnEnabled())
.setDefaultValue(true)
.setTooltip(Component.translatable("option.midnight-assist.melee_lock_on.tooltip"))
.setSaveConsumer(MidnightAssistConfig.INSTANCE.getData()::setMeleeLockOnEnabled)
.build());

general.addEntry(entryBuilder.startEnumSelector(Component.translatable("option.midnight-assist.preset"), MidnightAssistConfig.Preset.class, MidnightAssistConfig.Preset.NONE)
.setSaveConsumer(MidnightAssistConfig.INSTANCE.getData()::setLastAppliedPreset)
.setDefaultValue(MidnightAssistConfig.Preset.NONE)
.setTooltip(Component.translatable("option.midnight-assist.preset.tooltip"))
.build());

var entities = builder.getOrCreateCategory(Component.translatable("category.midnight-assist.entities"));

List<String> sortedEntities = new ArrayList<>(MidnightAssistConfig.INSTANCE.getData().getEnabledEntities().keySet());
Collections.sort(sortedEntities);

for (String id : sortedEntities) {
var entityType = MidnightAssistConfig.INSTANCE.getEntityType(id);

if (entityType == null) continue;

var entityName = Component.translatable(entityType.getDescriptionId());
boolean smartDefault = MidnightAssistConfig.INSTANCE.isSmartDefault(entityType, id);

entities.addEntry(entryBuilder.startBooleanToggle(entityName, MidnightAssistConfig.INSTANCE.getData().getEnabledEntities().getOrDefault(id, smartDefault))
.setDefaultValue(smartDefault)
.setSaveConsumer(value -> MidnightAssistConfig.INSTANCE.getData().getEnabledEntities().put(id, value))
.build());
}

return builder.build();
};
}
}

