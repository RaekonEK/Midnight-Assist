package com.one_studio.mixin.client;

import com.one_studio.MidnightAssisitClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class CameraMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void onRender(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        float partialTicks = tickCounter.getTickDelta(tick);
        MidnightAssisitClient.applyPartialTicks(partialTicks);
    }
}
