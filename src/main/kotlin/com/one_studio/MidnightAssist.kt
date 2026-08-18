package com.one_studio

import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory

object MidnightAssist : ModInitializer {
private val logger = LoggerFactory.getLogger("MidnightAssist")

override fun onInitialize() {
logger.info("MidnightAssist initialized")
MidnightAssistConfig.load()
}
}

