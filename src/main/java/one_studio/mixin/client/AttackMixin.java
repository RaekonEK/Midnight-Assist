package com.one_studio.mixin.client;

import com.one_studio.MidnightAssistClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class AttackMixin {
    @Inject(at = @At("HEAD"), method = "startAttack", cancellable = true)
    private void onStartAttack(CallbackInfo ci) {
        if (MidnightAssistClient.tryInterceptAttack((Minecraft)(Object)this)) {
            ci.cancel();
        }
    }

    @Inject(at = @At("HEAD"), method = "continueAttack", cancellable = true)
    private void onContinueAttack(boolean leftClick, CallbackInfo ci) {
        if (leftClick && MidnightAssistClient.shouldSuppressBlockBreak()) {
            ci.cancel();
        }
    }
}
