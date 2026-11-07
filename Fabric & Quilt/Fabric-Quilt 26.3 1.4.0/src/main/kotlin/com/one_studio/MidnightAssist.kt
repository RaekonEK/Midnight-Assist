package com.one_studio

import me.shedaniel.autoconfig.AutoConfig
import me.shedaniel.autoconfig.annotation.Config
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer
import me.shedaniel.autoconfig.serializer.ConfigSerializer
import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory

object MidnightAssist : ModInitializer {
    private val logger = LoggerFactory.getLogger("MidnightAssist")

    override fun onInitialize() {
        logger.info("MidnightAssist initialized")
        AutoConfig.register(
            ConfigData::class.java,
            object : ConfigSerializer.Factory<ConfigData> {
                override fun create(annotation: Config, clazz: Class<ConfigData>): ConfigSerializer<ConfigData> {
                    return GsonConfigSerializer(annotation, clazz)
                }
            }
        )
        MidnightAssistConfig.load()
    }
}
