package com.one_studio;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(MidnightAssistNeoForge.MOD_ID)
public final class MidnightAssistNeoForge {
    public static final String MOD_ID = "midnight_assist";

    public MidnightAssistNeoForge() {
        MidnightAssist.INSTANCE.init();
        if (isClient()) {
            IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
            MidnightAssistNeoForgeClient.register(modBus);
        }
    }

    private static boolean isClient() {
        try {
            Class<?> environment = Class.forName("net.minecraftforge.fml.loading.FMLEnvironment");
            Object dist;
            try {
                Method getter = environment.getMethod("getDist");
                dist = getter.invoke(null);
            } catch (NoSuchMethodException ignored) {
                Field field = environment.getField("dist");
                dist = field.get(null);
            }
            return "CLIENT".equals(String.valueOf(dist));
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }
}
