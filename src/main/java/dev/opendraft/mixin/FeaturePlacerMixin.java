package dev.opendraft.mixin;

import dev.opendraft.LavaPools;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Each chunk's surface lava pool, as the chunk is decorated, goes past {@link LavaPools}: twice the pools
 * in match worlds, and some near spawn. require = 0: if this version moved the method, match worlds just
 * get vanilla's pools.
 */
@Mixin(FeaturePlacer.class)
public abstract class FeaturePlacerMixin {
    @Shadow @Final private WorldGenLevel level;
    @Shadow @Final private ChunkGenerator generator;

    @Inject(method = "placeWithBiomeCheck", at = @At("RETURN"), require = 0)
    private void opendraft$lavaPools(PlacedFeature feature, RandomSource random, BlockPos origin, CallbackInfoReturnable<Boolean> cir) {
        LavaPools.afterTry((FeaturePlacer) (Object) this, level, generator, feature, origin, cir.getReturnValueZ());
    }
}
