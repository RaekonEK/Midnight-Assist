package com.one_studio

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.server.command.CommandManager
import net.minecraft.text.Text
import org.slf4j.LoggerFactory

object MidnightAssisit : ModInitializer {
private val logger = LoggerFactory.getLogger("midnight-assist")

override fun onInitialize() {
logger.info("Hello Fabric world!")

MidnightAssisitConfig.load()

CommandRegistrationCallback.EVENT.register { d, _, _ ->
d.register(
CommandManager.literal("midnight")
.then(CommandManager.literal("reload")
.executes { c ->
MidnightAssisitConfig.load()
val px = Text.translatable("chat.midnight-assist.prefix")
val ms = Text.translatable("chat.midnight-assist.reloaded")
c.source.sendFeedback({ px.copy().append(ms) }, false)
1
}
)
.then(CommandManager.literal("toggle")
.executes { c ->
MidnightAssisitConfig.data.globalEnabled = !MidnightAssisitConfig.data.globalEnabled
MidnightAssisitConfig.save()
val sk = if (MidnightAssisitConfig.data.globalEnabled) "chat.midnight-assist.enabled" else "chat.midnight-assist.disabled"
val px = Text.translatable("chat.midnight-assist.prefix")
val su = Text.translatable(sk)
c.source.sendFeedback({ px.copy().append(su) }, false)
1
}
)
.executes { c ->
val px = Text.translatable("chat.midnight-assist.prefix")
val usage = Text.translatable("chat.midnight-assist.usage")
c.source.sendFeedback({ px.copy().append(usage) }, false)
1
}
)
}
}
}

