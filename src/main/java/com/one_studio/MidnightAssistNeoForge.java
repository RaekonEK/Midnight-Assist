package com.one_studio;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(MidnightAssistNeoForge.MOD_ID)
public final class MidnightAssistNeoForge {
    public static final String MOD_ID = "midnight_assist";

    public MidnightAssistNeoForge(IEventBus modEventBus) {
        MidnightAssist.INSTANCE.init();
        if (isClient()) {
            MidnightAssistNeoForgeClient.register(modEventBus);
        }
    }

    private static boolean isClient() {
        try {
            Class<?> environment = Class.forName("net.neoforged.fml.loading.FMLEnvironment");
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
