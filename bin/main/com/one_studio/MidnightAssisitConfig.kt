package com.one_studio

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityType
import net.minecraft.entity.SpawnGroup
import net.minecraft.registry.Registries
import net.minecraft.util.Identifier
import java.io.File

object MidnightAssisitConfig {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val configFile: File = FabricLoader.getInstance().configDir.resolve("midnight-assisit.json").toFile()

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
            return "option.midnight-assisit.preset." + this.name.lowercase()
        }
    }

    data class ConfigData(
        var globalEnabled: Boolean = true,
        var aimAccuracy: Double = 0.2,
        var aimSpeed: Double = 0.5,
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
        
        // Ensure player is never enabled
        data.enabledEntities.remove("minecraft:player")
        
        // Populate missing entities from registry with smart AI detection
        var changed = false
        Registries.ENTITY_TYPE.forEach { type ->
            val id = Registries.ENTITY_TYPE.getId(type).toString()
            
            if (id != "minecraft:player" && !data.enabledEntities.containsKey(id)) {
                data.enabledEntities[id] = isSmartDefault(type, id)
                changed = true
            }
        }
        
        if (changed || !configFile.exists()) {
            save()
        }
    }

    fun isSmartDefault(type: EntityType<*>, id: String): Boolean {
        if (id == "minecraft:player") return false
        
        // الكيانات التي لها AI تنتمي عادة لمجموعات غير MISC
        // مجموعات الـ AI تشمل: MONSTER, CREATURE, AMBIENT, AXOLOTLS, etc.
        val group = type.spawnGroup
        val hasAI = group != SpawnGroup.MISC
        
        // استثناءات لبعض الكيانات التي قد تكون MISC ولكن نريد استهدافها (مثل الجولم والقرويين)
        val isSpecialMob = id.contains("golem") || id.contains("villager")
        
        // إذا كان الكيان ليس له AI وليس من الاستثناءات، يكون OFF افتراضياً
        return hasAI || isSpecialMob
    }

    fun applyPreset(preset: Preset) {
        if (preset == Preset.NONE) return

        Registries.ENTITY_TYPE.forEach { type ->
            val id = Registries.ENTITY_TYPE.getId(type).toString()
            if (id == "minecraft:player") {
                data.enabledEntities.remove(id)
                return@forEach
            }

            val group = type.spawnGroup
            
            // --- 1. تحليل السلوك الذكي (Behavior) ---
            val isHostile = group == SpawnGroup.MONSTER
            val isPassive = group == SpawnGroup.CREATURE || group == SpawnGroup.AMBIENT || 
                            group == SpawnGroup.WATER_CREATURE || group == SpawnGroup.WATER_AMBIENT || 
                            group == SpawnGroup.AXOLOTLS || group == SpawnGroup.UNDERGROUND_WATER_CREATURE
            
            // الكشف عن الكيانات المحايدة (Neutral) - الكائنات التي تهاجم بشرط
            val neutralKeywords = listOf("spider", "enderman", "piglin", "wolf", "bee", "golem", "llama", "bear", "dolphin", "goat", "panda", "warden")
            val isNeutral = neutralKeywords.any { id.contains(it) }

            // --- 2. تحليل الأبعاد الذكي (Dimensions) ---
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
        
        // 1. الكيانات العابرة للأبعاد (مثل الأندرمان)
        if (id.contains("enderman")) {
            return setOf("overworld", "nether", "end")
        }

        // 2. النذر (Nether) - بناءً على المعرفات المميزة لسكان النذر الأصليين
        val netherKeywords = listOf("piglin", "hoglin", "blaze", "ghast", "strider", "magma_cube", "wither_skeleton")
        if (netherKeywords.any { id.contains(it) }) {
            dims.add("nether")
        }

        // 3. الإند (End) - بناءً على المعرفات المميزة
        val endKeywords = listOf("shulker", "ender_dragon", "endermite")
        if (endKeywords.any { id.contains(it) }) {
            dims.add("end")
        }

        // 4. العالم العادي (Overworld)
        // الكيانات التي لا تنتمي حصرياً للنذر أو الإند
        val isNetherExclusive = listOf("blaze", "ghast", "strider", "magma_cube", "wither_skeleton").any { id.contains(it) }
        val isEndExclusive = listOf("shulker", "ender_dragon").any { id.contains(it) }
        
        if (!isNetherExclusive && !isEndExclusive) {
            dims.add("overworld")
        }
        
        // استثناءات خاصة للتواجد المتعدد
        if (id.contains("skeleton") && !id.contains("wither")) {
            dims.add("nether") // السكيليتون العادي يظهر في Soul Sand Valley
        }
        if (id.contains("chicken") || id.contains("ghast")) {
            // الغاست حصري للنذر، الدجاج قد يظهر كـ Chicken Jockey في النذر
            if (id.contains("chicken")) dims.add("nether")
        }

        return dims
    }

    fun save() {
        try {
            // Apply preset if one was selected in ModMenu
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
        for (type in Registries.ENTITY_TYPE) {
            if (Registries.ENTITY_TYPE.getId(type).toString() == id) {
                return type
            }
        }
        return null
    }

    fun isEntityEnabled(entity: Entity): Boolean {
        val id = Registries.ENTITY_TYPE.getId(entity.type).toString()
        if (id == "minecraft:player") return false
        return data.enabledEntities[id] ?: isSmartDefault(entity.type, id)
    }
}
