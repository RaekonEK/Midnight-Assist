package com.one_studio

import me.shedaniel.clothconfig2.gui.entries.BooleanListEntry
import me.shedaniel.clothconfig2.gui.entries.EnumListEntry
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.network.chat.Component

object PresetWatcher {
    private var presetEntry: EnumListEntry<Preset>? = null
    private var entityIds: List<String> = emptyList()
    private var entityToggles: List<BooleanListEntry> = emptyList()
    private var lastSeen: Preset = Preset.NONE
    private var pendingTicks: Int = -1

    fun watch(entry: EnumListEntry<Preset>, ids: List<String>, toggles: List<BooleanListEntry>) {
        presetEntry = entry
        entityIds = ids
        entityToggles = toggles
        lastSeen = entry.value
        pendingTicks = -1
    }

    fun tick() {
        val entry = presetEntry ?: return
        val current = entry.value ?: return
        if (current != lastSeen) {
            lastSeen = current
            pendingTicks = if (current == Preset.NONE) -1 else 20
            return
        }
        if (pendingTicks < 0) return
        pendingTicks--
        if (pendingTicks > 0) return
        pendingTicks = -1
        apply(current)
    }

    private fun apply(preset: Preset) {
        val data = MidnightAssistConfig.data
        MidnightAssistConfig.applyPreset(preset)
        for (i in entityIds.indices) {
            if (i >= entityToggles.size) break
            val id = entityIds[i]
            val type = MidnightAssistConfig.getEntityType(id)
            val desired = if (type != null) {
                data.enabledEntities.getOrDefault(id, MidnightAssistConfig.isSmartDefault(type, id))
            } else false
            val toggle = entityToggles[i]
            if ((toggle.value ?: false) == desired) continue
            val button = toggle.children().firstOrNull() as? AbstractButton ?: continue
            button.onPress()
        }
        Minecraft.getInstance().player?.sendOverlayMessage(Component.translatable("chat.midnight-assist.preset_applied"))
    }
}
