package dev.opendraft.mixin;

import dev.opendraft.OpenDraftClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * During the lookout the player is a spectator, who can't use a spyglass, so holding right-click
 * counts as scoping: vanilla then draws the spyglass overlay. Optional, in case the method moves.
 */
@Mixin(Player.class)
public abstract class PlayerScopingMixin {
    @Inject(method = "isScoping", at = @At("HEAD"), cancellable = true, require = 0)
    private void opendraft$lookoutScope(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this == Minecraft.getInstance().player && OpenDraftClient.zooming()) cir.setReturnValue(true);
    }
}
