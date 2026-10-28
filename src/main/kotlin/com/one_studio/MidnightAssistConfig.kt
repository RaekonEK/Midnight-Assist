package com.one_studio

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import me.shedaniel.autoconfig.ConfigData as AutoConfigData
import me.shedaniel.autoconfig.annotation.Config
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import java.io.File

@Config(name = "midnight-assist")
data class ConfigData(
    var configVersion: Int = 7,
    @JvmField var globalEnabled: Boolean = true,
    @JvmField var aimSpeed: Double = 0.5,
    @JvmField var aimAccuracy: Double = 0.2,
    @JvmField var aimPoint: AimPoint = AimPoint.CENTER,
    @JvmField var targetPriority: TargetPriority = TargetPriority.CROSSHAIR,
    @JvmField var aimAssistDistance: Int = 180,
    @JvmField var gameplayPreset: GameplayPreset = GameplayPreset.BALANCED,
    @JvmField var meleeLockOnEnabled: Boolean = true,
    @JvmField var meleeLockOnBlockUse: Boolean = false,
    @JvmField var meleeLockMode: MeleeLockMode = MeleeLockMode.HOLD,
    @JvmField var meleeRetarget: MeleeRetarget = MeleeRetarget.STOP_AFTER_DEATH,
    @JvmField var blockAimAssistEnabled: Boolean = false,
    @JvmField var blockAimAssistRange: Double = 6.0,
    @JvmField var lastAppliedPreset: Preset = Preset.NONE,
    @JvmField val enabledEntities: MutableMap<String, Boolean> = mutableMapOf(),
    @JvmField val priorityEntities: MutableList<String> = mutableListOf(),
    @JvmField val weaponProfiles: MutableList<WeaponProfile> = mutableListOf()
) : AutoConfigData

data class WeaponProfile(
    @JvmField var kind: String = "item",
    @JvmField var key: String = "",
    @JvmField var enabled: Boolean = true,
    @JvmField var aimSpeed: Double = 0.5,
    @JvmField var aimPoint: AimPoint = AimPoint.CENTER,
    @JvmField var targetPriority: TargetPriority = TargetPriority.CROSSHAIR,
    @JvmField var aimAssistDistance: Int = 180,
    @JvmField var meleeLockOnEnabled: Boolean = true,
    @JvmField var meleeLockOnBlockUse: Boolean = false,
    @JvmField var meleeLockMode: MeleeLockMode = MeleeLockMode.GLOBAL,
    @JvmField var meleeRetarget: MeleeRetarget = MeleeRetarget.STOP_AFTER_DEATH
)

enum class AimPoint {
    CENTER, UPPER_BODY, HEAD, DYNAMIC;

    fun getTranslationKey(): String {
        return "option.midnight-assist.aim_point." + this.name.lowercase()
    }
}

enum class GameplayPreset {
    BALANCED, SMOOTH, FAST_COMBAT, MELEE_ASSIST;

    fun getTranslationKey(): String {
        return "option.midnight-assist.gameplay_preset." + this.name.lowercase()
    }
}

enum class TargetPriority {
    CROSSHAIR, NEAREST, LOWEST_HEALTH, THREAT, BALANCED,
    FARTHEST, WEAKEST, STRONGEST, LOOKING_AT_YOU, RECENTLY_ATTACKED;

    fun getTranslationKey(): String {
        return "option.midnight-assist.target_priority." + this.name.lowercase()
    }
}

enum class Preset {
    NONE, PASSIVE_ONLY, NEUTRAL_AND_PASSIVE, NEUTRAL_ONLY,
    NEUTRAL_AND_HOSTILE, HOSTILE_ONLY, ALL_MOBS, ALL_ENTITIES;

    fun getTranslationKey(): String {
        return "option.midnight-assist.preset." + this.name.lowercase()
    }
}

enum class MeleeLockMode {
    GLOBAL, HOLD, TOGGLE;

    fun getTranslationKey(): String {
        return "option.midnight-assist.melee_lock_mode." + this.name.lowercase()
    }
}

enum class MeleeRetarget {
    STOP_AFTER_DEATH, SWITCH_PRIORITIZED, SWITCH_SAME_SPECIES;

    fun getTranslationKey(): String {
        return "option.midnight-assist.melee_retarget." + this.name.lowercase()
    }
}

object MidnightAssistConfig {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val configFile: File = FabricLoader.getInstance().configDir.resolve("midnight-assist.json").toFile()

    @JvmField
    var data = ConfigData()

    @JvmStatic
    @JvmName("getConfigData")
    fun getConfigData(): ConfigData = data

    @JvmStatic
    fun getEntityType(id: String): EntityType<*>? {
        for (type in BuiltInRegistries.ENTITY_TYPE) {
            if (BuiltInRegistries.ENTITY_TYPE.getKey(type).toString() == id) {
                return type
            }
        }
        return null
    }

    @JvmStatic
    fun getEntityId(type: EntityType<*>): String {
        return BuiltInRegistries.ENTITY_TYPE.getKey(type).toString()
    }

    @JvmStatic
    fun isSmartDefault(type: EntityType<*>, id: String): Boolean {
        if (id == "minecraft:player") return false

        val category = type.category
        val hasAI = category != MobCategory.MISC

        val isSpecialMob = id.contains("golem") || id.contains("villager")

        return hasAI || isSpecialMob
    }

    fun load() {
        if (configFile.exists()) {
            try {
                var raw = configFile.readText()
                try {
                    val json = JsonParser.parseString(raw).asJsonObject
                    if (json.get("gameplayPreset")?.asString == "BOW_PRACTICE") {
                        json.addProperty("gameplayPreset", GameplayPreset.BALANCED.name)
                        raw = gson.toJson(json)
                    }
                } catch (_: Exception) {
                }
                val loaded = gson.fromJson(raw, ConfigData::class.java)

                if (loaded.configVersion < 5) {
                    try {
                        val json = JsonParser.parseString(raw).asJsonObject
                        json.get("aimStrength")?.let {
                            loaded.aimSpeed = when (it.asString) {
                                "LOW" -> 0.25
                                "HIGH" -> 0.85
                                else -> 0.5
                            }
                        }
                        json.get("detectionAngle")?.let {
                            loaded.aimAssistDistance = it.asInt
                        }
                    } catch (_: Exception) {
                    }
                    loaded.configVersion = 5
                }

                data = loaded
            } catch (_: Exception) {
                save()
            }
        }

        var migrated = false
        if (data.configVersion < 2) {
            data.targetPriority = TargetPriority.NEAREST
            data.meleeLockOnEnabled = true
            data.configVersion = 2
            migrated = true
        }
        if (data.configVersion < 3) {
            data.meleeLockOnBlockUse = false
            data.blockAimAssistEnabled = false
            data.blockAimAssistRange = 6.0
            data.configVersion = 3
            migrated = true
        }
        if (data.configVersion < 4) {
            val oldPriority = data.targetPriority
            data.targetPriority = when (oldPriority) {
                TargetPriority.WEAKEST -> TargetPriority.LOWEST_HEALTH
                TargetPriority.STRONGEST -> TargetPriority.BALANCED
                TargetPriority.LOOKING_AT_YOU -> TargetPriority.THREAT
                TargetPriority.RECENTLY_ATTACKED -> TargetPriority.THREAT
                TargetPriority.FARTHEST -> TargetPriority.NEAREST
                else -> oldPriority
            }
            data.aimPoint = AimPoint.CENTER
            data.configVersion = 4
            migrated = true
        }
        if (data.configVersion < 5) {
            data.configVersion = 5
            migrated = true
        }
        if (data.configVersion < 6) {
            data.weaponProfiles.forEach { profile ->
                if (profile.meleeLockMode == MeleeLockMode.HOLD) {
                    profile.meleeLockMode = MeleeLockMode.GLOBAL
                }
            }
            data.configVersion = 6
            migrated = true
        }
        if (data.configVersion < 7) {
            data.weaponProfiles.removeAll { profile ->
                (profile.kind == "category" && (profile.key == "bows" || profile.key == "crossbows")) ||
                    (profile.kind == "item" && (profile.key == "minecraft:bow" || profile.key == "minecraft:crossbow"))
            }
            data.configVersion = 7
            migrated = true
        }

        data.enabledEntities.remove("minecraft:player")
        data.priorityEntities.remove("minecraft:player")

        var changed = false
        BuiltInRegistries.ENTITY_TYPE.forEach { type ->
            val id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString()

            if (id != "minecraft:player" && !data.enabledEntities.containsKey(id)) {
                data.enabledEntities[id] = isSmartDefault(type, id)
                changed = true
            }
        }

        if (changed || migrated || !configFile.exists()) {
            save()
        }
    }

    @JvmStatic
    fun effectiveAimSpeed(): Double {
        return data.aimSpeed.coerceIn(0.1, 1.0)
    }

    @JvmStatic
    fun findWeaponProfile(stack: ItemStack): WeaponProfile? {
        if (stack.isEmpty) {
            return data.weaponProfiles.firstOrNull { it.kind == "category" && it.key == "empty_hand" }
        }
        val id = BuiltInRegistries.ITEM.getKey(stack.item).toString()
        data.weaponProfiles.firstOrNull { it.kind == "item" && it.key == id }?.let { return it }
        val category = weaponCategoryForItem(id)
        if (category != null) {
            data.weaponProfiles.firstOrNull { it.kind == "category" && it.key == category }?.let { return it }
        }
        val bigKey = if (stack.item is BlockItem) "block" else "item"
        return data.weaponProfiles.firstOrNull { it.kind == "big" && it.key == bigKey }
    }

    @JvmStatic
    fun weaponCategoryForItem(itemId: String): String? {
        val path = itemId.substringAfter(':')
        return when {
            path.endsWith("_sword") -> "swords"
            path.endsWith("_axe") -> "axes"
            path.endsWith("_pickaxe") -> "pickaxes"
            path.endsWith("_shovel") -> "shovels"
            path.endsWith("_hoe") -> "hoes"
            path == "trident" -> "tridents"
            path == "mace" -> "maces"
            path == "fishing_rod" -> "fishing_rods"
            else -> null
        }
    }

    @JvmStatic
    fun weaponCategoryKeys(): List<String> {
        return listOf(
            "swords", "axes", "pickaxes", "shovels", "hoes",
            "tridents", "maces", "fishing_rods", "empty_hand"
        )
    }

    fun applyGameplayPreset(preset: GameplayPreset) {
        if (preset == GameplayPreset.BALANCED) {
            data.aimSpeed = 0.5
            data.aimPoint = AimPoint.CENTER
            data.targetPriority = TargetPriority.CROSSHAIR
            data.aimAssistDistance = 180
            data.meleeLockOnEnabled = true
        } else if (preset == GameplayPreset.SMOOTH) {
            data.aimSpeed = 0.25
            data.aimAssistDistance = 150
            data.aimPoint = AimPoint.UPPER_BODY
            data.targetPriority = TargetPriority.NEAREST
        } else if (preset == GameplayPreset.FAST_COMBAT) {
            data.aimSpeed = 0.85
            data.aimAssistDistance = 270
            data.aimPoint = AimPoint.DYNAMIC
            data.targetPriority = TargetPriority.THREAT
        } else if (preset == GameplayPreset.MELEE_ASSIST) {
            data.aimSpeed = 0.85
            data.aimAssistDistance = 180
            data.aimPoint = AimPoint.UPPER_BODY
            data.targetPriority = TargetPriority.NEAREST
        }
        data.gameplayPreset = preset
    }

    fun applyPreset(preset: Preset) {
        if (preset == Preset.NONE) return

        BuiltInRegistries.ENTITY_TYPE.forEach { type ->
            val id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString()
            if (id == "minecraft:player") {
                data.enabledEntities.remove(id)
                return@forEach
            }

            val category = type.category

            val isHostile = category == MobCategory.MONSTER
            val isPassive = category == MobCategory.CREATURE || category == MobCategory.AMBIENT ||
                            category == MobCategory.WATER_CREATURE || category == MobCategory.WATER_AMBIENT ||
                            category == MobCategory.AXOLOTLS || category == MobCategory.UNDERGROUND_WATER_CREATURE

            val neutralKeywords = listOf("spider", "enderman", "piglin", "wolf", "bee", "golem", "llama", "bear", "dolphin", "goat", "panda", "warden")
            val isNeutral = neutralKeywords.any { id.contains(it) }

            val enabled = when (preset) {
                Preset.PASSIVE_ONLY -> isPassive && !isNeutral
                Preset.NEUTRAL_AND_PASSIVE -> isNeutral || isPassive
                Preset.NEUTRAL_ONLY -> isNeutral
                Preset.NEUTRAL_AND_HOSTILE -> isNeutral || (isHostile && !isNeutral)
                Preset.HOSTILE_ONLY -> isHostile && !isNeutral
                Preset.ALL_MOBS -> isPassive || isHostile || isNeutral
                Preset.ALL_ENTITIES -> true
                else -> data.enabledEntities[id] ?: isSmartDefault(type, id)
            }

            data.enabledEntities[id] = enabled
        }
    }

    fun save() {
        try {
            data.enabledEntities.remove("minecraft:player")
            configFile.writeText(gson.toJson(data))
        } catch (_: Exception) {
        }
    }

    @JvmStatic
    fun isEntityEnabled(entity: Entity): Boolean {
        if (entity is Player) return false

        val id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).toString()
        return data.enabledEntities[id] ?: isSmartDefault(entity.type, id)
    }

    @JvmStatic
    fun isPriorityEntity(entity: Entity): Boolean {
        val id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).toString()
        return data.priorityEntities.contains(id)
    }

    @JvmStatic
    fun isBlockedEntity(entity: Entity): Boolean {
        val id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).toString()
        return data.enabledEntities[id] == false
    }
}
