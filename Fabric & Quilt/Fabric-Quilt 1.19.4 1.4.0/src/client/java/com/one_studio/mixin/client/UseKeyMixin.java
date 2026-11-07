package com.one_studio.mixin.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.one_studio.MidnightAssistClient;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyMapping.class)
public class UseKeyMixin {
    @Inject(at = @At("HEAD"), method = "click")
    private static void onKeyClick(InputConstants.Key key, CallbackInfo ci) {
        MidnightAssistClient.onUseKeyClick(key);
    }
}
