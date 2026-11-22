package com.one_studio.mixin.client;

import com.one_studio.MidnightAssistClient;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client tick hook. 1.14 ships two incompatible Fabric API generations
 * (0.2.7/0.3.0 have ClientTickCallback, 0.28.5 has ClientTickEvents), so the
 * mod drives its own tick dispatch instead of depending on either one.
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
