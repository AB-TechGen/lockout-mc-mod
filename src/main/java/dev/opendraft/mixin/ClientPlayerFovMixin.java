package dev.opendraft.mixin;

import dev.opendraft.OpenDraftClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The spyglass zoom (a tenth of the field of view) while holding right-click in the lookout. Optional. */
@Mixin(AbstractClientPlayer.class)
public abstract class ClientPlayerFovMixin {
    @Inject(method = "getFieldOfViewModifier", at = @At("HEAD"), cancellable = true, require = 0)
    private void opendraft$lookoutZoom(CallbackInfoReturnable<Float> cir) {
        if ((Object) this == Minecraft.getInstance().player && OpenDraftClient.zooming()) cir.setReturnValue(0.1F);
    }
}
