package com.one_studio.mixin.client;

import com.one_studio.MidnightAssistClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class AttackMixin {
@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
private void onStartAttack(CallbackInfoReturnable<Boolean> cir) {
if (MidnightAssistClient.tryInterceptAttack(Minecraft.getInstance())) {
cir.setReturnValue(false);
}
}
}

