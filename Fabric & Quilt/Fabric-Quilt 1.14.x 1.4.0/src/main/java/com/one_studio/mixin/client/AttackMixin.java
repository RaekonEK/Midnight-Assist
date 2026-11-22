package com.one_studio.mixin.client;

import com.one_studio.MidnightAssistClient;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops the vanilla left-click attack from firing into the air while the aim
 * assist is busy acquiring a target, so the assisted hit is the only one.
 * 1.14 has no startAttack/continueAttack; doAttack is the equivalent entry point.
 */
@Mixin(MinecraftClient.class)
public class AttackMixin {
    @Inject(at = @At("HEAD"), method = "doAttack", cancellable = true)
    private void onDoAttack(CallbackInfo ci) {
        if (MidnightAssistClient.tryInterceptAttack((MinecraftClient) (Object) this)) {
            ci.cancel();
        }
    }
}
