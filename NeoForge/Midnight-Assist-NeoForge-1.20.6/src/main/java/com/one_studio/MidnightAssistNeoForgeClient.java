package com.one_studio;

import net.minecraft.client.gui.screens.Screen;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

public final class MidnightAssistNeoForgeClient {
    public static void register(IEventBus modBus) {
        modBus.addListener(RegisterKeyMappingsEvent.class, MidnightAssistNeoForgeClient::registerKeys);
        modBus.addListener(FMLClientSetupEvent.class, MidnightAssistNeoForgeClient::clientSetup);
    }

    public static void registerKeys(RegisterKeyMappingsEvent event) {
        MidnightAssistClient.INSTANCE.onInitializeClient();
        KeyMappingHelper.INSTANCE.init(event);
    }

    public static void clientSetup(FMLClientSetupEvent event) {
        ModContainer container = ModList.get()
            .getModContainerById(MidnightAssistNeoForge.MOD_ID)
            .orElseThrow();
        container.registerExtensionPoint(
            IConfigScreenFactory.class,
            (IConfigScreenFactory) (mod, parent) -> createConfigScreen(parent)
        );
    }

    private static Screen createConfigScreen(Screen parent) {
        return MidnightAssistModMenu.createConfigScreen(parent);
    }
}
