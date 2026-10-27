package dev.opendraft.mixin;

import com.mojang.realmsclient.gui.screens.RealmsNotificationsScreen;
import dev.opendraft.WorldMaker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Realms draws its notification icons (news, invites, the "!") at a fixed place worked out from the screen size,
 * where its button is in vanilla. The OpenDraft button pushes that button down, so the icons go down with it.
 * require = 0: if the method moves, the icons just stay where vanilla puts them.
 */
@Mixin(RealmsNotificationsScreen.class)
public abstract class RealmsNotificationsMixin {
    @Inject(method = "extractIcons", at = @At("HEAD"), require = 0)
    private void opendraft$moveDown(GuiGraphicsExtractor g, CallbackInfo ci) {
        g.pose().pushMatrix();
        g.pose().translate(0, WorldMaker.realmsShift());
    }

    @Inject(method = "extractIcons", at = @At("RETURN"), require = 0)
    private void opendraft$moveBack(GuiGraphicsExtractor g, CallbackInfo ci) {
        g.pose().popMatrix();
    }
}
