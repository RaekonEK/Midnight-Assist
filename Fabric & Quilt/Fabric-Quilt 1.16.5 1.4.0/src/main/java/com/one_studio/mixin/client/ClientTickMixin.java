package com.one_studio.mixin.client;

import com.one_studio.MidnightAssistClient;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client tick hook. Some Fabric API builds for 1.16 (for example 0.13.1, the
 * newest one that targets 1.16.0) do not ship ClientTickEvents, so the mod
 * drives its own tick dispatch instead of depending on that event.
 */
@Mixin(MinecraftClient.class)
public class ClientTickMixin {
    @Inject(at = @At("HEAD"), method = "tick")
    private void onTickHead(CallbackInfo ci) {
        MidnightAssistClient.onClientTickStart((MinecraftClient) (Object) this);
    }

    @Inject(at = @At("TAIL"), method = "tick")
    private void onTickTail(CallbackInfo ci) {
        MidnightAssistClient.onClientTickEnd((MinecraftClient) (Object) this);
    }
}
