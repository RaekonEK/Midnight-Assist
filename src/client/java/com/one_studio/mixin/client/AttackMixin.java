package com.one_studio.mixin.client;

import com.one_studio.MidnightAssisitClient;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftClient.class)
public class AttackMixin {
@Inject(at = @At("HEAD"), method = "doAttack", cancellable = true)
private void onDoAttack(CallbackInfoReturnable<Boolean> cir) {
if (MidnightAssisitClient.tryInterceptAttack((MinecraftClient)(Object)this)) {
cir.setReturnValue(false);
}
}
}

