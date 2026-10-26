package dev.opendraft.mixin;

import dev.opendraft.MatchLock;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * During a match, Peaceful becomes Easy the moment it's picked. Even one tick of Peaceful would clear
 * every hostile mob, so this can't wait for MatchLock's check. Easy, Normal and Hard are left alone.
 * require = 0: if this version renamed the method, MatchLock's check still catches it a moment later.
 */
@Mixin(MinecraftServer.class)
public abstract class DifficultyMixin {
    @ModifyVariable(method = "setDifficulty", at = @At("HEAD"), argsOnly = true, require = 0)
    private Difficulty opendraft$noPeaceful(Difficulty difficulty) {
        return MatchLock.replacePeaceful((MinecraftServer) (Object) this, difficulty);
    }
}
