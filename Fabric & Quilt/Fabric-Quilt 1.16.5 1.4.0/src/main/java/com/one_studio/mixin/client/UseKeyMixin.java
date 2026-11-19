package com.one_studio.mixin.client;

import com.one_studio.MidnightAssistClient;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyBinding.class)
public class UseKeyMixin {
    @Inject(at = @At("HEAD"), method = "onKeyPressed")
    private static void onKeyClick(InputUtil.Key key, CallbackInfo ci) {
        MidnightAssistClient.onUseKeyClick(key);
    }
}
