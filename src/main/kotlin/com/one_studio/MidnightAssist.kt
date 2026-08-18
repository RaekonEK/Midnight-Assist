package com.one_studio

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory

object MidnightAssist : ModInitializer {
private val logger = LoggerFactory.getLogger("midnight-assist")

override fun onInitialize() {
logger.info("Midnight Assist initialized!")

MidnightAssistConfig.load()

CommandRegistrationCallback.EVENT.register { d, _, _ ->
d.register(
Commands.literal("midnight")
.then(Commands.literal("reload")
.executes { c ->
MidnightAssistConfig.load()
val px = Component.translatable("chat.midnight-assist.prefix")
val ms = Component.translatable("chat.midnight-assist.reloaded")
c.source.sendSuccess({ px.copy().append(ms) }, false)
1
}
)
.then(Commands.literal("toggle")
.executes { c ->
MidnightAssistConfig.data.globalEnabled = !MidnightAssistConfig.data.globalEnabled
MidnightAssistConfig.save()
val sk = if (MidnightAssistConfig.data.globalEnabled) "chat.midnight-assist.enabled" else "chat.midnight-assist.disabled"
val px = Component.translatable("chat.midnight-assist.prefix")
val su = Component.translatable(sk)
c.source.sendSuccess({ px.copy().append(su) }, false)
1
}
)
.executes { c ->
val px = Component.translatable("chat.midnight-assist.prefix")
val usage = Component.translatable("chat.midnight-assist.usage")
c.source.sendSuccess({ px.copy().append(usage) }, false)
1
}
)
}
}
}

