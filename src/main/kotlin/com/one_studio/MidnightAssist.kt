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

		CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
			dispatcher.register(
				Commands.literal("midnight")
					.then(Commands.literal("reload")
						.executes { context ->
							MidnightAssistConfig.load()
							val prefix = Component.translatable("chat.midnight-assist.prefix")
							val message = Component.translatable("chat.midnight-assist.reloaded")
							context.source.sendSuccess({ prefix.copy().append(message) }, false)
							1
						}
					)
					.then(Commands.literal("toggle")
						.executes { context ->
							MidnightAssistConfig.data.globalEnabled = !MidnightAssistConfig.data.globalEnabled
							MidnightAssistConfig.save()
							val statusKey = if (MidnightAssistConfig.data.globalEnabled) "chat.midnight-assist.enabled" else "chat.midnight-assist.disabled"
							val prefix = Component.translatable("chat.midnight-assist.prefix")
							val status = Component.translatable(statusKey)
							context.source.sendSuccess({ prefix.copy().append(status) }, false)
							1
						}
					)
					.executes { context ->
						val prefix = Component.translatable("chat.midnight-assist.prefix")
						val usage = Component.translatable("chat.midnight-assist.usage")
						context.source.sendSuccess({ prefix.copy().append(usage) }, false)
						1
					}
			)
		}
	}
}
