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
Registries.ENTITY_TYPE.forEach { ty ->
val id = Registries.ENTITY_TYPE.getId(ty).toString()

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

val gp = ty.spawnGroup
val ha = gp != SpawnGroup.MISC

val im = id.contains("golem") || id.contains("villager")

return ha || im
}

fun applyPreset(preset: Preset) {
if (preset == Preset.NONE) return

Registries.ENTITY_TYPE.forEach { ty ->
val id = Registries.ENTITY_TYPE.getId(ty).toString()
if (id == "minecraft:player") {
data.enabledEntities.remove(id)
return@forEach
}

val gp = ty.spawnGroup

val ho = gp == SpawnGroup.MONSTER
val pa = gp == SpawnGroup.CREATURE || gp == SpawnGroup.AMBIENT ||
gp == SpawnGroup.WATER_CREATURE || gp == SpawnGroup.WATER_AMBIENT ||
gp == SpawnGroup.AXOLOTLS || gp == SpawnGroup.UNDERGROUND_WATER_CREATURE

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
Preset.NONE -> data.enabledEntities[id] ?: isSmartDefault(ty, id)
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
for (ty in Registries.ENTITY_TYPE) {
if (Registries.ENTITY_TYPE.getId(ty).toString() == id) {
return ty
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

