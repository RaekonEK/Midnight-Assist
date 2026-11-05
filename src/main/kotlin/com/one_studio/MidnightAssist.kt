package com.one_studio

import net.fabricmc.api.ModInitializer

object MidnightAssist : ModInitializer {
    private val logger = Log

    override fun onInitialize() {
        logger.info("MidnightAssist initialized")
        MidnightAssistConfig.load()
    }
}
