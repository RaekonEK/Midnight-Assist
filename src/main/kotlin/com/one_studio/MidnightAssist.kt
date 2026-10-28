package com.one_studio

import org.slf4j.LoggerFactory

object MidnightAssist {
    private val logger = LoggerFactory.getLogger("MidnightAssist")

    fun init() {
        logger.info("MidnightAssist initialized")
        MidnightAssistConfig.load()
    }
}
