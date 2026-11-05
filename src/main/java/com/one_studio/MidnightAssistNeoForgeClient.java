package com.one_studio;

import java.util.function.Consumer;

import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

public final class MidnightAssistNeoForgeClient {
    public static void register(IEventBus modBus) {
        modBus.addListener((Consumer<RegisterKeyMappingsEvent>) MidnightAssistNeoForgeClient::registerKeys);
        modBus.addListener((Consumer<FMLClientSetupEvent>) MidnightAssistNeoForgeClient::clientSetup);
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
            ConfigScreenHandler.ConfigScreenFactory.class,
            () -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) -> createConfigScreen(parent))
        );
    }

    private static Screen createConfigScreen(Screen parent) {
        return MidnightAssistModMenu.createConfigScreen(parent);
    }
}
