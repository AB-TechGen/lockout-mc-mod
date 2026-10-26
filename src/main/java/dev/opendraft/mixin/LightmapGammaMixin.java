package dev.opendraft.mixin;

import dev.opendraft.OpenDraftClient;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;

/**
 * Fullbright: the lightmap reads gamma as 15 (1500%) instead of the Brightness option, which is
 * capped at 100% and stays as the player set it. Optional, in case the method moves.
 */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class LightmapGammaMixin {
    @Redirect(method = "extract", require = 0,
            slice = @Slice(from = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;gamma()Lnet/minecraft/client/OptionInstance;")),
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;", ordinal = 0))
    private Object opendraft$fullbrightGamma(OptionInstance<?> gamma) {
        return OpenDraftClient.fullbright ? (Object) OpenDraftClient.FULLBRIGHT_GAMMA : gamma.get();
    }
}
