package com.one_studio

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.registries.BuiltInRegistries

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import java.io.File

object MidnightAssistConfig {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val configFile: File = FabricLoader.getInstance().configDir.resolve("midnight-assist.json").toFile()

    enum class TargetPriority {
        NEAREST,
        FARTHEST,
        WEAKEST,
        STRONGEST,
        LOOKING_AT_YOU,
        RECENTLY_ATTACKED;

        fun getTranslationKey(): String {
            return "option.midnight-assist.target_priority." + this.name.lowercase()
        }
    }

    enum class Preset {
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

        fun getTranslationKey(): String {
            return "option.midnight-assist.preset." + this.name.lowercase()
        }
    }

    data class ConfigData(
        var configVersion: Int = 2,
        var globalEnabled: Boolean = true,
        var aimAccuracy: Double = 0.2,
        var aimSpeed: Double = 0.5,
        var targetPriority: TargetPriority = TargetPriority.NEAREST,
        var meleeLockOnEnabled: Boolean = true,
        var lastAppliedPreset: Preset = Preset.NONE,
        val enabledEntities: MutableMap<String, Boolean> = mutableMapOf()
    )

    var data = ConfigData()

    fun load() {
        if (configFile.exists()) {
            try {
                data = gson.fromJson(configFile.readText(), ConfigData::class.java)
            } catch (e: Exception) {
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

        data.enabledEntities.remove("minecraft:player")

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

    fun isSmartDefault(type: EntityType<*>, id: String): Boolean {
        if (id == "minecraft:player") return false

        val category = type.category
        val hasAI = category != MobCategory.MISC

        val isSpecialMob = id.contains("golem") || id.contains("villager")

        return hasAI || isSpecialMob
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

            val dims = detectDimensions(type, id)
            val isOverworld = dims.contains("overworld")
            val isNether = dims.contains("nether")
            val isEnd = dims.contains("end")

            val enabled = when (preset) {
                Preset.PASSIVE_ONLY -> isPassive && !isNeutral
                Preset.HOSTILE_ONLY -> isHostile && !isNeutral
                Preset.PASSIVE_AND_HOSTILE -> (isPassive || isHostile) && !isNeutral
                Preset.NEUTRAL_ONLY -> isNeutral
                Preset.NEUTRAL_AND_HOSTILE -> isNeutral || isHostile
                Preset.NEUTRAL_AND_PASSIVE -> isNeutral || isPassive
                Preset.ALL_MOBS -> isPassive || isHostile || isNeutral
                Preset.ALL_ENTITIES -> true
                Preset.OVERWORLD -> isOverworld
                Preset.NETHER -> isNether
                Preset.END -> isEnd
                else -> data.enabledEntities[id] ?: isSmartDefault(type, id)
            }

            data.enabledEntities[id] = enabled
        }
    }

    private fun detectDimensions(type: EntityType<*>, id: String): Set<String> {
        val dims = mutableSetOf<String>()

        if (id.contains("enderman")) {
            return setOf("overworld", "nether", "end")
        }

        val netherKeywords = listOf("piglin", "hoglin", "blaze", "ghast", "strider", "magma_cube", "wither_skeleton")
        if (netherKeywords.any { id.contains(it) }) {
            dims.add("nether")
        }

        val endKeywords = listOf("shulker", "ender_dragon", "endermite")
        if (endKeywords.any { id.contains(it) }) {
            dims.add("end")
        }

        val isNetherExclusive = listOf("blaze", "ghast", "strider", "magma_cube", "wither_skeleton").any { id.contains(it) }
        val isEndExclusive = listOf("shulker", "ender_dragon").any { id.contains(it) }

        if (!isNetherExclusive && !isEndExclusive) {
            dims.add("overworld")
        }

        if (id.contains("skeleton") && !id.contains("wither")) {
            dims.add("nether")
        }
        if (id.contains("chicken") || id.contains("ghast")) {
            if (id.contains("chicken")) dims.add("nether")
        }

        return dims
    }

    fun save() {
        try {
            if (data.lastAppliedPreset != Preset.NONE) {
                applyPreset(data.lastAppliedPreset)
                data.lastAppliedPreset = Preset.NONE
            }

            data.enabledEntities.remove("minecraft:player")
            configFile.writeText(gson.toJson(data))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getEntityType(id: String): EntityType<*>? {
        for (type in BuiltInRegistries.ENTITY_TYPE) {
            if (BuiltInRegistries.ENTITY_TYPE.getKey(type).toString() == id) {
                return type
            }
        }
        return null
    }

    fun isEntityEnabled(entity: Entity): Boolean {
        val id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).toString()
        if (id == "minecraft:player") return false
        return data.enabledEntities[id] ?: isSmartDefault(entity.type, id)
    }
}
