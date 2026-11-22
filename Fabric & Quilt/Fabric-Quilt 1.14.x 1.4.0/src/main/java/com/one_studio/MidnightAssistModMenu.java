package com.one_studio;

import io.github.prospector.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.registry.Registry;

import java.util.Locale;
import java.util.function.Function;

/**
 * ModMenu v1 integration (ModMenu 1.7.18 is the last build for 1.14.4).
 * Cloth Config 2.1.8 uses plain strings and the pre-MatrixStack GUI API, so all
 * labels are resolved to their translated text before being handed to Cloth.
 */
public class MidnightAssistModMenu implements ModMenuApi {
    private static String tr(String key) {
        return new TranslatableText(key).asFormattedString();
    }

    @Override
    public String getModId() {
        return "midnight-assist";
    }

    @Override
    public Function<Screen, ? extends Screen> getConfigScreenFactory() {
        return MidnightAssistModMenu::createConfigScreen;
    }

    public static Screen createConfigScreen(Screen parent) {
        ConfigData data = MidnightAssistConfig.getConfigData();

        ConfigBuilder builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(tr("title.midnight-assist.config"))
            .setSavingRunnable(MidnightAssistConfig.INSTANCE::save);

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        ConfigCategory general = builder.getOrCreateCategory(tr("category.midnight-assist.general"));

        general.addEntry(entryBuilder.startBooleanToggle(tr("option.midnight-assist.global_enabled"), data.globalEnabled)
            .setDefaultValue(true)
            .setSaveConsumer(v -> data.globalEnabled = v)
            .setTooltip(tr("option.midnight-assist.global_enabled.tooltip"))
            .build());

        general.addEntry(entryBuilder.startEnumSelector(
                tr("option.midnight-assist.gameplay_preset"),
                GameplayPreset.class,
                data.gameplayPreset)
            .setDefaultValue(GameplayPreset.BALANCED)
            .setEnumNameProvider(e -> tr(((GameplayPreset) e).getTranslationKey()))
            .setTooltip(tr("option.midnight-assist.gameplay_preset.tooltip"))
            .setSaveConsumer(v -> MidnightAssistConfig.INSTANCE.applyGameplayPreset((GameplayPreset) v))
            .build());

        ConfigCategory movement =
            builder.getOrCreateCategory(tr("category.midnight-assist.camera_movement"));

        movement.addEntry(entryBuilder.startBooleanToggle(tr("option.midnight-assist.melee_lock_on_enabled"), data.meleeLockOnEnabled)
            .setDefaultValue(true)
            .setTooltip(tr("option.midnight-assist.melee_lock_on_enabled.tooltip"))
            .setSaveConsumer(v -> data.meleeLockOnEnabled = v)
            .build());

        movement.addEntry(entryBuilder.startBooleanToggle(tr("option.midnight-assist.melee_lock_on_block_use"), data.meleeLockOnBlockUse)
            .setDefaultValue(false)
            .setTooltip(tr("option.midnight-assist.melee_lock_on_block_use.tooltip"))
            .setSaveConsumer(v -> data.meleeLockOnBlockUse = v)
            .build());

        movement.addEntry(entryBuilder.startSelector(
                tr("option.midnight-assist.melee_lock_mode"),
                new MeleeLockMode[]{MeleeLockMode.HOLD, MeleeLockMode.TOGGLE},
                data.meleeLockMode == MeleeLockMode.TOGGLE ? MeleeLockMode.TOGGLE : MeleeLockMode.HOLD)
            .setDefaultValue(MeleeLockMode.HOLD)
            .setNameProvider(e -> tr(((MeleeLockMode) e).getTranslationKey()))
            .setTooltip(tr("option.midnight-assist.melee_lock_mode.tooltip"))
            .setSaveConsumer(v -> data.meleeLockMode = (MeleeLockMode) v)
            .build());

        movement.addEntry(entryBuilder.startEnumSelector(
                tr("option.midnight-assist.melee_retarget"),
                MeleeRetarget.class,
                data.meleeRetarget)
            .setDefaultValue(MeleeRetarget.STOP_AFTER_DEATH)
            .setEnumNameProvider(e -> tr(((MeleeRetarget) e).getTranslationKey()))
            .setTooltip(tr("option.midnight-assist.melee_retarget.tooltip"))
            .setSaveConsumer(v -> data.meleeRetarget = (MeleeRetarget) v)
            .build());

        ConfigCategory targeting =
            builder.getOrCreateCategory(tr("category.midnight-assist.targeting"));

        targeting.addEntry(entryBuilder.startIntSlider(tr("option.midnight-assist.aim_speed"), (int) Math.round(data.aimSpeed * 100.0), 10, 100)
            .setDefaultValue(50)
            .setTextGetter(v -> String.format(Locale.ROOT, "%.2f", v / 100.0))
            .setTooltip(tr("option.midnight-assist.aim_speed.tooltip"))
            .setSaveConsumer(v -> data.aimSpeed = v / 100.0)
            .build());

        targeting.addEntry(entryBuilder.startEnumSelector(
                tr("option.midnight-assist.aim_point"),
                AimPoint.class,
                data.aimPoint)
            .setDefaultValue(AimPoint.CENTER)
            .setEnumNameProvider(e -> tr(((AimPoint) e).getTranslationKey()))
            .setTooltip(tr("option.midnight-assist.aim_point.tooltip"))
            .setSaveConsumer(v -> data.aimPoint = (AimPoint) v)
            .build());

        targeting.addEntry(entryBuilder.startEnumSelector(
                tr("option.midnight-assist.target_priority"),
                TargetPriority.class,
                data.targetPriority)
            .setDefaultValue(TargetPriority.CROSSHAIR)
            .setEnumNameProvider(e -> tr(((TargetPriority) e).getTranslationKey()))
            .setTooltip(tr("option.midnight-assist.target_priority.tooltip"))
            .setSaveConsumer(v -> data.targetPriority = (TargetPriority) v)
            .build());

        targeting.addEntry(entryBuilder.startIntSlider(tr("option.midnight-assist.aim_assist_distance"), data.aimAssistDistance, 1, 360)
            .setDefaultValue(180)
            .setTooltip(tr("option.midnight-assist.aim_assist_distance.tooltip"))
            .setSaveConsumer(v -> data.aimAssistDistance = v)
            .build());

        ConfigCategory weapons =
            builder.getOrCreateCategory(tr("category.midnight-assist.weapons"));

        weapons.addEntry(entryBuilder.startTextDescription(tr("option.midnight-assist.per_weapon_customization")).build());

        weapons.addEntry(new ActionEntry(tr("button.midnight-assist.add_weapon"), () -> {
            Screen current = MinecraftClient.getInstance().currentScreen;
            MinecraftClient.getInstance().openScreen(new WeaponSelectScreen(current, parent));
        }));

        for (WeaponProfile profile : data.weaponProfiles) {
            weapons.addEntry(new ActionEntry("\u25B8 " + profileDisplayName(profile), weaponProfileIcon(profile), () -> {
                Screen current = MinecraftClient.getInstance().currentScreen;
                MinecraftClient.getInstance().openScreen(createWeaponProfileScreen(profile, current, parent));
            }));
        }

        return builder.build();
    }

    public static Screen createWeaponProfileScreen(WeaponProfile profile, Screen configScreen, Screen configParent) {
        ConfigBuilder builder = ConfigBuilder.create()
            .setParentScreen(configScreen)
            .setTitle(tr("title.midnight-assist.weapon_profile"))
            .setSavingRunnable(MidnightAssistConfig.INSTANCE::save);

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        ConfigCategory category =
            builder.getOrCreateCategory(tr("category.midnight-assist.weapons"));

        category.addEntry(entryBuilder.startBooleanToggle(tr("option.midnight-assist.weapon_enabled"), profile.enabled)
            .setDefaultValue(true)
            .setSaveConsumer(v -> profile.enabled = v)
            .build());

        category.addEntry(entryBuilder.startIntSlider(tr("option.midnight-assist.aim_speed"), (int) Math.round(profile.aimSpeed * 100.0), 10, 100)
            .setDefaultValue(50)
            .setTextGetter(v -> String.format(Locale.ROOT, "%.2f", v / 100.0))
            .setTooltip(tr("option.midnight-assist.aim_speed.tooltip"))
            .setSaveConsumer(v -> profile.aimSpeed = v / 100.0)
            .build());

        category.addEntry(entryBuilder.startEnumSelector(
                tr("option.midnight-assist.aim_point"),
                AimPoint.class,
                profile.aimPoint)
            .setDefaultValue(AimPoint.CENTER)
            .setEnumNameProvider(e -> tr(((AimPoint) e).getTranslationKey()))
            .setTooltip(tr("option.midnight-assist.aim_point.tooltip"))
            .setSaveConsumer(v -> profile.aimPoint = (AimPoint) v)
            .build());

        category.addEntry(entryBuilder.startEnumSelector(
                tr("option.midnight-assist.target_priority"),
                TargetPriority.class,
                profile.targetPriority)
            .setDefaultValue(TargetPriority.CROSSHAIR)
            .setEnumNameProvider(e -> tr(((TargetPriority) e).getTranslationKey()))
            .setTooltip(tr("option.midnight-assist.target_priority.tooltip"))
            .setSaveConsumer(v -> profile.targetPriority = (TargetPriority) v)
            .build());

        category.addEntry(entryBuilder.startIntSlider(tr("option.midnight-assist.aim_assist_distance"), profile.aimAssistDistance, 1, 360)
            .setDefaultValue(180)
            .setTooltip(tr("option.midnight-assist.aim_assist_distance.tooltip"))
            .setSaveConsumer(v -> profile.aimAssistDistance = v)
            .build());

        category.addEntry(entryBuilder.startBooleanToggle(tr("option.midnight-assist.melee_lock_on_enabled"), profile.meleeLockOnEnabled)
            .setDefaultValue(true)
            .setTooltip(tr("option.midnight-assist.melee_lock_on_enabled.tooltip"))
            .setSaveConsumer(v -> profile.meleeLockOnEnabled = v)
            .build());

        category.addEntry(entryBuilder.startBooleanToggle(tr("option.midnight-assist.melee_lock_on_block_use"), profile.meleeLockOnBlockUse)
            .setDefaultValue(false)
            .setTooltip(tr("option.midnight-assist.melee_lock_on_block_use.tooltip"))
            .setSaveConsumer(v -> profile.meleeLockOnBlockUse = v)
            .build());

        category.addEntry(entryBuilder.startEnumSelector(
                tr("option.midnight-assist.melee_lock_mode"),
                MeleeLockMode.class,
                profile.meleeLockMode)
            .setDefaultValue(MeleeLockMode.GLOBAL)
            .setEnumNameProvider(e -> tr(((MeleeLockMode) e).getTranslationKey()))
            .setTooltip(tr("option.midnight-assist.melee_lock_mode.tooltip"))
            .setSaveConsumer(v -> profile.meleeLockMode = (MeleeLockMode) v)
            .build());

        category.addEntry(entryBuilder.startEnumSelector(
                tr("option.midnight-assist.melee_retarget"),
                MeleeRetarget.class,
                profile.meleeRetarget)
            .setDefaultValue(MeleeRetarget.STOP_AFTER_DEATH)
            .setEnumNameProvider(e -> tr(((MeleeRetarget) e).getTranslationKey()))
            .setTooltip(tr("option.midnight-assist.melee_retarget.tooltip"))
            .setSaveConsumer(v -> profile.meleeRetarget = (MeleeRetarget) v)
            .build());

        category.addEntry(new ActionEntry(tr("button.midnight-assist.delete_profile"), () -> {
            MidnightAssistConfig.getConfigData().weaponProfiles.remove(profile);
            MidnightAssistConfig.INSTANCE.save();
            MinecraftClient.getInstance().openScreen(createConfigScreen(configParent));
        }));

        return builder.build();
    }

    public static String profileDisplayName(WeaponProfile profile) {
        if ("category".equals(profile.kind)) {
            return tr("option.midnight-assist.weapon_category." + profile.key);
        }
        if ("big".equals(profile.kind)) {
            return tr("option.midnight-assist.big_category." + profile.key);
        }
        for (Item item : Registry.ITEM) {
            if (Registry.ITEM.getId(item).toString().equals(profile.key)) {
                return tr(item.getTranslationKey());
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
            if (Registry.ITEM.getId(item).toString().equals(profile.key)) {
                return new ItemStack(item);
            }
        }
        return ItemStack.EMPTY;
    }
}
