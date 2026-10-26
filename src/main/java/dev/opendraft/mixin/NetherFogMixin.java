package dev.opendraft.mixin;

import dev.opendraft.OpenDraftClient;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Much less fog in a match's Nether, so you can see far. require = 0: otherwise vanilla's fog. */
@Mixin(AtmosphericFogEnvironment.class)
public abstract class NetherFogMixin {
    @Inject(method = "setupFog", at = @At("TAIL"), require = 0)
    private void opendraft$netherFog(FogData fog, Camera camera, ClientLevel level, float renderDistance, DeltaTracker delta, CallbackInfo ci) {
        OpenDraftClient.netherFog(fog, level);
    }
}
