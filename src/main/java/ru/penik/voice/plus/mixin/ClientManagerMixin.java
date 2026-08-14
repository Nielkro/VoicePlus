package ru.penik.voice.plus.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "de.maxhenkel.voicechat.voice.client.ClientManager")
public class ClientManagerMixin {
    @Inject(method = "authenticate", at = @At("HEAD"), remap = false)
    private void onAuthenticate(Object secretPacket, CallbackInfo ci) {
        ru.penik.voice.plus.plugins.SvcConnectionManager.onAuthenticate(secretPacket);
    }
}
