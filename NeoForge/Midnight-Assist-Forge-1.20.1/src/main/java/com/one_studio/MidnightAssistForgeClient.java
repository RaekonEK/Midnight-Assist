package com.one_studio;

import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.ConfigScreenHandler;

public final class MidnightAssistForgeClient {
    public static void register(IEventBus modBus) {
        modBus.addListener(MidnightAssistForgeClient::registerKeys);
        modBus.addListener(MidnightAssistForgeClient::clientSetup);
    }

    public static void registerKeys(RegisterKeyMappingsEvent event) {
        MidnightAssistClient.INSTANCE.onInitializeClient();
        KeyMappingHelper.INSTANCE.init(event);
    }

    public static void clientSetup(FMLClientSetupEvent event) {
        ModContainer container = ModList.get()
            .getModContainerById("midnight_assist")
            .orElseThrow();
        container.registerExtensionPoint(
            ConfigScreenHandler.ConfigScreenFactory.class,
            () -> new ConfigScreenHandler.ConfigScreenFactory((mc, screen) -> createConfigScreen(screen))
        );
    }

    private static Screen createConfigScreen(Screen parent) {
        return MidnightAssistModMenu.createConfigScreen(parent);
    }
}
