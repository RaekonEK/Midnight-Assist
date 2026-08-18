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

var md = false
if (data.configVersion < 2) {
data.targetPriority = TargetPriority.NEAREST
data.meleeLockOnEnabled = true
data.configVersion = 2
md = true
}

data.enabledEntities.remove("minecraft:player")

var ch = false
BuiltInRegistries.ENTITY_TYPE.forEach { ty ->
val id = BuiltInRegistries.ENTITY_TYPE.getKey(ty).toString()

if (id != "minecraft:player" && !data.enabledEntities.containsKey(id)) {
data.enabledEntities[id] = isSmartDefault(ty, id)
ch = true
}
}

if (ch || md || !configFile.exists()) {
save()
}
}

fun isSmartDefault(ty: EntityType<*>, id: String): Boolean {
if (id == "minecraft:player") return false

val ca = ty.category
val ha = ca != MobCategory.MISC

val im = id.contains("golem") || id.contains("villager")

return ha || im
}

fun applyPreset(preset: Preset) {
if (preset == Preset.NONE) return

BuiltInRegistries.ENTITY_TYPE.forEach { ty ->
val id = BuiltInRegistries.ENTITY_TYPE.getKey(ty).toString()
if (id == "minecraft:player") {
data.enabledEntities.remove(id)
return@forEach
}

val ca = ty.category

val ho = ca == MobCategory.MONSTER
val pa = ca == MobCategory.CREATURE || ca == MobCategory.AMBIENT ||
ca == MobCategory.WATER_CREATURE || ca == MobCategory.WATER_AMBIENT ||
ca == MobCategory.AXOLOTLS || ca == MobCategory.UNDERGROUND_WATER_CREATURE

val neutralKeywords = listOf("spider", "enderman", "piglin", "wolf", "bee", "golem", "llama", "bear", "dolphin", "goat", "panda", "warden")
val nu = neutralKeywords.any { id.contains(it) }

val dm = detectDimensions(ty, id)
val ow = dm.contains("overworld")
val nh = dm.contains("nether")
val ed = dm.contains("end")

val en = when (preset) {
Preset.PASSIVE_ONLY -> pa && !nu
Preset.HOSTILE_ONLY -> ho && !nu
Preset.PASSIVE_AND_HOSTILE -> (pa || ho) && !nu
Preset.NEUTRAL_ONLY -> nu
Preset.NEUTRAL_AND_HOSTILE -> nu || ho
Preset.NEUTRAL_AND_PASSIVE -> nu || pa
Preset.ALL_MOBS -> pa || ho || nu
Preset.ALL_ENTITIES -> true
Preset.OVERWORLD -> ow
Preset.NETHER -> nh
Preset.END -> ed
else -> data.enabledEntities[id] ?: isSmartDefault(ty, id)
}

data.enabledEntities[id] = en
}
}

private fun detectDimensions(ty: EntityType<*>, id: String): Set<String> {
val dm = mutableSetOf<String>()

if (id.contains("enderman")) {
return setOf("overworld", "nether", "end")
}

val nk = listOf("piglin", "hoglin", "blaze", "ghast", "strider", "magma_cube", "wither_skeleton")
if (nk.any { id.contains(it) }) {
dm.add("nether")
}

val ek = listOf("shulker", "ender_dragon", "endermite")
if (ek.any { id.contains(it) }) {
dm.add("end")
}

val nx = listOf("blaze", "ghast", "strider", "magma_cube", "wither_skeleton").any { id.contains(it) }
val ex = listOf("shulker", "ender_dragon").any { id.contains(it) }

if (!nx && !ex) {
dm.add("overworld")
}

if (id.contains("skeleton") && !id.contains("wither")) {
dm.add("nether")
}
if (id.contains("chicken") || id.contains("ghast")) {
if (id.contains("chicken")) dm.add("nether")
}

return dm
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
for (ty in BuiltInRegistries.ENTITY_TYPE) {
if (BuiltInRegistries.ENTITY_TYPE.getKey(ty).toString() == id) {
return ty
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

