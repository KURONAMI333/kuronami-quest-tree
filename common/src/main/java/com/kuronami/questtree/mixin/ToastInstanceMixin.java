package com.kuronami.questtree.mixin;

import com.kuronami.questtree.client.ClientConfig;
import com.kuronami.questtree.client.OwnSoundToast;
import com.kuronami.questtree.client.QtSounds;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Our toasts slide in and out with the mod's own sounds; every other toast keeps vanilla's. */
@Mixin(targets = "net.minecraft.client.gui.components.toasts.ToastComponent$ToastInstance")
public abstract class ToastInstanceMixin {

    @Shadow
    @Final
    private Toast toast;

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/toasts/Toast$Visibility;playSound(Lnet/minecraft/client/sounds/SoundManager;)V"))
    private void questTree$toastSound(Toast.Visibility visibility, SoundManager sounds, Operation<Void> original) {
        if (toast instanceof OwnSoundToast) {
            if (ClientConfig.completionSounds()) {
                sounds.play(SimpleSoundInstance.forUI(visibility == Toast.Visibility.SHOW ? QtSounds.TOAST_IN : QtSounds.TOAST_OUT, 1, 0.8f));
            }
        } else {
            original.call(visibility, sounds);
        }
    }
}
