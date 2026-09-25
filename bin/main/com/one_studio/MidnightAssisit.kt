package com.one_studio

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.server.command.CommandManager
import net.minecraft.text.Text
import org.slf4j.LoggerFactory

object MidnightAssisit : ModInitializer {
    private val logger = LoggerFactory.getLogger("midnight-assisit")

	override fun onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.
		logger.info("Hello Fabric world!")
        
        MidnightAssisitConfig.load()

		CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
			dispatcher.register(
				CommandManager.literal("midnight")
					.then(CommandManager.literal("reload")
						.executes { context ->
							MidnightAssisitConfig.load()
							val prefix = Text.translatable("chat.midnight-assisit.prefix")
							val message = Text.translatable("chat.midnight-assisit.reloaded")
							context.source.sendFeedback({ prefix.copy().append(message) }, false)
							1
						}
					)
					.then(CommandManager.literal("toggle")
						.executes { context ->
							MidnightAssisitConfig.data.globalEnabled = !MidnightAssisitConfig.data.globalEnabled
							MidnightAssisitConfig.save()
							val statusKey = if (MidnightAssisitConfig.data.globalEnabled) "chat.midnight-assisit.enabled" else "chat.midnight-assisit.disabled"
							val prefix = Text.translatable("chat.midnight-assisit.prefix")
							val status = Text.translatable(statusKey)
							context.source.sendFeedback({ prefix.copy().append(status) }, false)
							1
						}
					)
					.executes { context ->
						val prefix = Text.translatable("chat.midnight-assisit.prefix")
						val usage = Text.translatable("chat.midnight-assisit.usage")
						context.source.sendFeedback({ prefix.copy().append(usage) }, false)
						1
					}
			)
		}
	}
}
