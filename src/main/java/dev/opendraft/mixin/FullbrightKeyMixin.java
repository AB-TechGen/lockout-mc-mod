package dev.opendraft.mixin;

import dev.opendraft.OpenDraftClient;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Alt+2 toggles fullbright. Key mappings can't take a modifier, so this is caught here, before the
 * 2 also picks hotbar slot 2. Optional, in case the method moves.
 */
@Mixin(KeyboardHandler.class)
public abstract class FullbrightKeyMixin {
    /** GLFW's key code for 2 and its press action (LWJGL isn't on the compile classpath). */
    private static final int KEY_2 = 50, PRESS = 1;

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true, require = 0)
    private void opendraft$fullbrightKey(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (action == PRESS && event.key() == KEY_2 && event.hasAltDown() && OpenDraftClient.toggleFullbright()) ci.cancel();
    }
}
