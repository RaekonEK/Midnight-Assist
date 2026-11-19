package com.one_studio

import net.fabricmc.api.ModInitializer


object MidnightAssist : ModInitializer {


    override fun onInitialize() {
        println("MidnightAssist initialized")
        MidnightAssistConfig.load()
    }
}
