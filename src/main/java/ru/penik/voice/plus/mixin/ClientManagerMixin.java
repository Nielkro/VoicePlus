package ru.penik.voice.plus.mixin;

import de.maxhenkel.voicechat.voice.client.ClientManager;
import de.maxhenkel.voicechat.net.SecretPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientManager.class, remap = false)
public class ClientManagerMixin {
    @Inject(method = "authenticate", at = @At("HEAD"), require = 0)
    private void onAuthenticate(SecretPacket secretPacket, CallbackInfo ci) {
        ru.penik.voice.plus.plugins.SvcConnectionManager.onAuthenticate(secretPacket);
    }
}
