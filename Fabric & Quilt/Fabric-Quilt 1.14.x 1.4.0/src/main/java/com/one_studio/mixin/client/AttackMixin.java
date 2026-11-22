package com.one_studio.mixin.client;

import com.one_studio.MidnightAssistClient;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.14 has no startAttack/continueAttack; doAttack is the equivalent entry
 * point, and method_1590(boolean) is the block-breaking tick that runs while
 * the attack key is held. Both are stopped while the aim assist is acquiring a
 * target, otherwise vanilla would swing/break into empty air and the assisted
 * hit would never land cleanly.
 */
@Mixin(MinecraftClient.class)
public class AttackMixin {
    @Inject(at = @At("HEAD"), method = "doAttack", cancellable = true)
    private void onDoAttack(CallbackInfo ci) {
        if (MidnightAssistClient.tryInterceptAttack((MinecraftClient) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(at = @At("HEAD"), method = "method_1590", cancellable = true)
    private void onHandleBlockBreaking(boolean bl, CallbackInfo ci) {
        if (MidnightAssistClient.shouldSuppressBlockBreak()) {
            ci.cancel();
        }
    }
}
