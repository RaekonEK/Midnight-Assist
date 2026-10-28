package com.one_studio;

import io.github.prospector.modmenu.api.ConfigScreenFactory;
import io.github.prospector.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.gui.entries.BooleanListEntry;
import me.shedaniel.clothconfig2.gui.entries.EnumListEntry;
import me.shedaniel.clothconfig2.gui.entries.SubCategoryListEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MidnightAssistModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return MidnightAssistModMenu::createConfigScreen;
    }

    public static Screen createConfigScreen(Screen parent) {
        ConfigData data = MidnightAssistConfig.getConfigData();

        ConfigBuilder builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(I18n.get("title.midnight-assist.config"))
            .setSavingRunnable(MidnightAssistConfig.INSTANCE::save);

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        ConfigCategory general = builder.getOrCreateCategory(I18n.get("category.midnight-assist.general"));

        general.addEntry(entryBuilder.startBooleanToggle(I18n.get("option.midnight-assist.global_enabled"), data.globalEnabled)
            .setDefaultValue(true)
            .setSaveConsumer(v -> data.globalEnabled = v)
            .setTooltip(I18n.get("option.midnight-assist.global_enabled.tooltip"))
            .build());

        general.addEntry(entryBuilder.startEnumSelector(
                I18n.get("option.midnight-assist.gameplay_preset"),
                GameplayPreset.class,
                data.gameplayPreset)
            .setDefaultValue(GameplayPreset.BALANCED)
            .setEnumNameProvider(e -> I18n.get(((GameplayPreset) e).getTranslationKey()))
            .setTooltip(I18n.get("option.midnight-assist.gameplay_preset.tooltip"))
            .setSaveConsumer(v -> MidnightAssistConfig.INSTANCE.applyGameplayPreset(v))
            .build());

        ConfigCategory movement = builder.getOrCreateCategory(I18n.get("category.midnight-assist.camera_movement"));

        movement.addEntry(entryBuilder.startBooleanToggle(I18n.get("option.midnight-assist.melee_lock_on_enabled"), data.meleeLockOnEnabled)
            .setDefaultValue(true)
            .setTooltip(I18n.get("option.midnight-assist.melee_lock_on_enabled.tooltip"))
            .setSaveConsumer(v -> data.meleeLockOnEnabled = v)
            .build());

        movement.addEntry(entryBuilder.startBooleanToggle(I18n.get("option.midnight-assist.melee_lock_on_block_use"), data.meleeLockOnBlockUse)
            .setDefaultValue(false)
            .setTooltip(I18n.get("option.midnight-assist.melee_lock_on_block_use.tooltip"))
            .setSaveConsumer(v -> data.meleeLockOnBlockUse = v)
            .build());

        movement.addEntry(entryBuilder.startSelector(
                I18n.get("option.midnight-assist.melee_lock_mode"),
                new MeleeLockMode[]{MeleeLockMode.HOLD, MeleeLockMode.TOGGLE},
                data.meleeLockMode == MeleeLockMode.TOGGLE ? MeleeLockMode.TOGGLE : MeleeLockMode.HOLD)
            .setDefaultValue(MeleeLockMode.HOLD)
            .setNameProvider(e -> I18n.get(((MeleeLockMode) e).getTranslationKey()))
            .setTooltip(I18n.get("option.midnight-assist.melee_lock_mode.tooltip"))
            .setSaveConsumer(v -> data.meleeLockMode = v)
            .build());

        movement.addEntry(entryBuilder.startEnumSelector(
                I18n.get("option.midnight-assist.melee_retarget"),
                MeleeRetarget.class,
                data.meleeRetarget)
            .setDefaultValue(MeleeRetarget.STOP_AFTER_DEATH)
            .setEnumNameProvider(e -> I18n.get(((MeleeRetarget) e).getTranslationKey()))
            .setTooltip(I18n.get("option.midnight-assist.melee_retarget.tooltip"))
            .setSaveConsumer(v -> data.meleeRetarget = v)
            .build());

        ConfigCategory targeting = builder.getOrCreateCategory(I18n.get("category.midnight-assist.targeting"));

        targeting.addEntry(entryBuilder.startIntSlider(I18n.get("option.midnight-assist.aim_speed"), (int) Math.round(data.aimSpeed * 100.0), 10, 100)
            .setDefaultValue(50)
            .setTextGetter(v -> String.format(Locale.ROOT, "%.2f", v / 100.0))
            .setTooltip(I18n.get("option.midnight-assist.aim_speed.tooltip"))
            .setSaveConsumer(v -> data.aimSpeed = v / 100.0)
            .build());

        targeting.addEntry(entryBuilder.startEnumSelector(
                I18n.get("option.midnight-assist.aim_point"),
                AimPoint.class,
                data.aimPoint)
            .setDefaultValue(AimPoint.CENTER)
            .setEnumNameProvider(e -> I18n.get(((AimPoint) e).getTranslationKey()))
            .setTooltip(I18n.get("option.midnight-assist.aim_point.tooltip"))
            .setSaveConsumer(v -> data.aimPoint = v)
            .build());

        targeting.addEntry(entryBuilder.startEnumSelector(
                I18n.get("option.midnight-assist.target_priority"),
                TargetPriority.class,
                data.targetPriority)
            .setDefaultValue(TargetPriority.CROSSHAIR)
            .setEnumNameProvider(e -> I18n.get(((TargetPriority) e).getTranslationKey()))
            .setTooltip(I18n.get("option.midnight-assist.target_priority.tooltip"))
            .setSaveConsumer(v -> data.targetPriority = v)
            .build());

        targeting.addEntry(entryBuilder.startIntSlider(I18n.get("option.midnight-assist.aim_assist_distance"), data.aimAssistDistance, 1, 360)
            .setDefaultValue(180)
            .setTooltip(I18n.get("option.midnight-assist.aim_assist_distance.tooltip"))
            .setSaveConsumer(v -> data.aimAssistDistance = v)
            .build());

        EnumListEntry<Preset> presetEntry = entryBuilder.startEnumSelector(
                I18n.get("option.midnight-assist.preset"),
                Preset.class,
                data.lastAppliedPreset)
            .setDefaultValue(Preset.NONE)
            .setEnumNameProvider(e -> I18n.get(((Preset) e).getTranslationKey()))
            .setTooltip(I18n.get("option.midnight-assist.preset.tooltip"))
            .setSaveConsumer(v -> data.lastAppliedPreset = v)
            .build();
        targeting.addEntry(presetEntry);

        List<String> sortedEntities = new ArrayList<>(data.enabledEntities.keySet());
        Collections.sort(sortedEntities);

        List<AbstractConfigListEntry> entityEntries = new ArrayList<>();
        List<String> entityIds = new ArrayList<>();
        List<BooleanListEntry> entityToggles = new ArrayList<>();

        for (String id : sortedEntities) {
            EntityType<?> entityType = MidnightAssistConfig.getEntityType(id);
            if (entityType == null) continue;

            String entityName = I18n.get(entityType.getDescriptionId());
            boolean smartDefault = MidnightAssistConfig.isSmartDefault(entityType, id);

            BooleanListEntry toggle = entryBuilder.startBooleanToggle(entityName, data.enabledEntities.getOrDefault(id, smartDefault))
                .setDefaultValue(smartDefault)
                .setSaveConsumer(value -> data.enabledEntities.put(id, value))
                .build();
            entityIds.add(id);
            entityToggles.add(toggle);
            entityEntries.add(toggle);
        }

        targeting.addEntry(new SubCategoryListEntry(
            I18n.get("option.midnight-assist.entity_section"),
            entityEntries,
            false));

        PresetWatcher.INSTANCE.watch(presetEntry, entityIds, entityToggles);

        ConfigCategory weapons = builder.getOrCreateCategory(I18n.get("category.midnight-assist.weapons"));

        weapons.addEntry(entryBuilder.startTextDescription(I18n.get("option.midnight-assist.per_weapon_customization")).build());

        weapons.addEntry(new ActionEntry(I18n.get("button.midnight-assist.add_weapon"), () -> {
            Screen current = Minecraft.getInstance().screen;
            Minecraft.getInstance().setScreen(new WeaponSelectScreen(current, parent));
        }));

        for (WeaponProfile profile : data.weaponProfiles) {
            weapons.addEntry(new ActionEntry("\u25B8 " + profileDisplayName(profile), weaponProfileIcon(profile), () -> {
                Screen current = Minecraft.getInstance().screen;
                Minecraft.getInstance().setScreen(createWeaponProfileScreen(profile, current, parent));
            }));
        }

        return builder.build();
    }

    public static Screen createWeaponProfileScreen(WeaponProfile profile, Screen configScreen, Screen configParent) {
        ConfigBuilder builder = ConfigBuilder.create()
            .setParentScreen(configScreen)
            .setTitle(I18n.get("title.midnight-assist.weapon_profile", profileDisplayName(profile)))
            .setSavingRunnable(MidnightAssistConfig.INSTANCE::save);

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        ConfigCategory category = builder.getOrCreateCategory(I18n.get("category.midnight-assist.weapons"));

        category.addEntry(entryBuilder.startBooleanToggle(I18n.get("option.midnight-assist.weapon_enabled"), profile.enabled)
            .setDefaultValue(true)
            .setTooltip(I18n.get("option.midnight-assist.weapon_enabled.tooltip"))
            .setSaveConsumer(v -> profile.enabled = v)
            .build());

        category.addEntry(entryBuilder.startIntSlider(I18n.get("option.midnight-assist.aim_speed"), (int) Math.round(profile.aimSpeed * 100.0), 10, 100)
            .setDefaultValue(50)
            .setTextGetter(v -> String.format(Locale.ROOT, "%.2f", v / 100.0))
            .setTooltip(I18n.get("option.midnight-assist.aim_speed.tooltip"))
            .setSaveConsumer(v -> profile.aimSpeed = v / 100.0)
            .build());

        category.addEntry(entryBuilder.startEnumSelector(
                I18n.get("option.midnight-assist.aim_point"),
                AimPoint.class,
                profile.aimPoint)
            .setDefaultValue(AimPoint.CENTER)
            .setEnumNameProvider(e -> I18n.get(((AimPoint) e).getTranslationKey()))
            .setTooltip(I18n.get("option.midnight-assist.aim_point.tooltip"))
            .setSaveConsumer(v -> profile.aimPoint = v)
            .build());

        category.addEntry(entryBuilder.startEnumSelector(
                I18n.get("option.midnight-assist.target_priority"),
                TargetPriority.class,
                profile.targetPriority)
            .setDefaultValue(TargetPriority.CROSSHAIR)
            .setEnumNameProvider(e -> I18n.get(((TargetPriority) e).getTranslationKey()))
            .setTooltip(I18n.get("option.midnight-assist.target_priority.tooltip"))
            .setSaveConsumer(v -> profile.targetPriority = v)
            .build());

        category.addEntry(entryBuilder.startIntSlider(I18n.get("option.midnight-assist.aim_assist_distance"), profile.aimAssistDistance, 1, 360)
            .setDefaultValue(180)
            .setTooltip(I18n.get("option.midnight-assist.aim_assist_distance.tooltip"))
            .setSaveConsumer(v -> profile.aimAssistDistance = v)
            .build());

        category.addEntry(entryBuilder.startBooleanToggle(I18n.get("option.midnight-assist.melee_lock_on_enabled"), profile.meleeLockOnEnabled)
            .setDefaultValue(true)
            .setTooltip(I18n.get("option.midnight-assist.melee_lock_on_enabled.tooltip"))
            .setSaveConsumer(v -> profile.meleeLockOnEnabled = v)
            .build());

        category.addEntry(entryBuilder.startBooleanToggle(I18n.get("option.midnight-assist.melee_lock_on_block_use"), profile.meleeLockOnBlockUse)
            .setDefaultValue(false)
            .setTooltip(I18n.get("option.midnight-assist.melee_lock_on_block_use.tooltip"))
            .setSaveConsumer(v -> profile.meleeLockOnBlockUse = v)
            .build());

        category.addEntry(entryBuilder.startEnumSelector(
                I18n.get("option.midnight-assist.melee_lock_mode"),
                MeleeLockMode.class,
                profile.meleeLockMode)
            .setDefaultValue(MeleeLockMode.GLOBAL)
            .setEnumNameProvider(e -> I18n.get(((MeleeLockMode) e).getTranslationKey()))
            .setTooltip(I18n.get("option.midnight-assist.melee_lock_mode.tooltip"))
            .setSaveConsumer(v -> profile.meleeLockMode = v)
            .build());

        category.addEntry(entryBuilder.startEnumSelector(
                I18n.get("option.midnight-assist.melee_retarget"),
                MeleeRetarget.class,
                profile.meleeRetarget)
            .setDefaultValue(MeleeRetarget.STOP_AFTER_DEATH)
            .setEnumNameProvider(e -> I18n.get(((MeleeRetarget) e).getTranslationKey()))
            .setTooltip(I18n.get("option.midnight-assist.melee_retarget.tooltip"))
            .setSaveConsumer(v -> profile.meleeRetarget = v)
            .build());

        category.addEntry(new ActionEntry(I18n.get("button.midnight-assist.delete_profile"), () -> {
            MidnightAssistConfig.getConfigData().weaponProfiles.remove(profile);
            MidnightAssistConfig.INSTANCE.save();
            Minecraft.getInstance().setScreen(createConfigScreen(configParent));
        }));

        return builder.build();
    }

    public static String profileDisplayName(WeaponProfile profile) {
        if ("category".equals(profile.kind)) {
            return I18n.get("option.midnight-assist.weapon_category." + profile.key);
        }
        if ("big".equals(profile.kind)) {
            return I18n.get("option.midnight-assist.big_category." + profile.key);
        }
        for (Item item : Registry.ITEM) {
            if (Registry.ITEM.getKey(item).toString().equals(profile.key)) {
                return I18n.get(item.getDescriptionId());
            }
        }
        return profile.key;
    }

    public static ItemStack weaponProfileIcon(WeaponProfile profile) {
        if ("big".equals(profile.kind)) {
            return new ItemStack("block".equals(profile.key) ? Items.GRASS_BLOCK : Items.IRON_INGOT);
        }
        if ("category".equals(profile.kind)) {
            Item item;
            switch (profile.key) {
                case "swords": item = Items.IRON_SWORD; break;
                case "axes": item = Items.IRON_AXE; break;
                case "pickaxes": item = Items.IRON_PICKAXE; break;
                case "shovels": item = Items.IRON_SHOVEL; break;
                case "hoes": item = Items.IRON_HOE; break;
                case "tridents": item = Items.TRIDENT; break;
                case "fishing_rods": item = Items.FISHING_ROD; break;
                case "empty_hand": item = Items.PLAYER_HEAD; break;
                default: item = Items.AIR; break;
            }
            return new ItemStack(item);
        }
        for (Item item : Registry.ITEM) {
            if (Registry.ITEM.getKey(item).toString().equals(profile.key)) {
                return new ItemStack(item);
            }
        }
        return ItemStack.EMPTY;
    }
}
