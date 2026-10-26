package dev.opendraft.mixin;

import dev.opendraft.ServerHooks;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Chunks near the player spawn mobs while they're held for the lookout. Vanilla skips spectators
 * here, so the world they look at would only have the mobs made with the terrain. See also
 * {@link NaturalSpawnerMixin}. Optional, in case the method moves.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapSpawningMixin {
    @Redirect(method = "playerIsCloseEnoughForSpawning", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;isSpectator()Z"))
    private boolean opendraft$heldPlayerSpawns(ServerPlayer p) {
        return p.isSpectator() && ServerHooks.heldPlayerIn(p.level()) != p;
    }
}
