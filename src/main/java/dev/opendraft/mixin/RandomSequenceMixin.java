package dev.opendraft.mixin;

import dev.opendraft.SharedRandom;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Loot tables that share a sequence in match worlds (deepslate ores with stone ones, ferns with grass)
 * draw from it. require = 0: if this version moved the method, each keeps its own, as in vanilla.
 */
@Mixin(MinecraftServer.class)
public abstract class RandomSequenceMixin {
    @ModifyVariable(method = "getRandomSequence", at = @At("HEAD"), argsOnly = true, require = 0)
    private Identifier opendraft$sharedSequence(Identifier sequence) {
        return SharedRandom.sequenceFor((MinecraftServer) (Object) this, sequence);
    }
}
