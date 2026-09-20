package com.one_studio.mixin.client;

import com.one_studio.MidnightAssistClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
public class AttackMixin {
    @Inject(at = @At("HEAD"), method = "attack", cancellable = true)
    private void onAttack(Player player, Entity target, CallbackInfo ci) {
        if (MidnightAssistClient.tryInterceptAttack(Minecraft.getInstance())) {
            ci.cancel();
        }
    }
}
