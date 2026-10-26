package dev.opendraft.mixin;

import dev.opendraft.ServerHooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A natural spawn needs a non-spectator player nearby; while the player is held for the lookout
 * (a spectator), they count, so mobs spawn for them to see. See also {@link ChunkMapSpawningMixin}.
 * Optional, in case the method moves.
 */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {
    @Redirect(method = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V",
            require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getNearestPlayer(DDDDZ)Lnet/minecraft/world/entity/player/Player;"))
    private static Player opendraft$heldPlayerNearby(ServerLevel level, double x, double y, double z, double range, boolean creative) {
        Player nearest = level.getNearestPlayer(x, y, z, range, creative);
        return nearest != null ? nearest : ServerHooks.heldPlayerIn(level);
    }
}
