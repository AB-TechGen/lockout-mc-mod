package dev.opendraft.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.opendraft.SharedRandom;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.monster.PatrollingMonster;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Which naturally spawned pillagers are captains, by spawn number. require = 0: otherwise vanilla. */
@Mixin(PatrollingMonster.class)
public abstract class CaptainMixin {
    @Redirect(method = "finalizeSpawn", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;nextFloat()F"))
    private float opendraft$sharedCaptain(RandomSource random, @Local(argsOnly = true) ServerLevelAccessor level) {
        return SharedRandom.captainRoll(level.getLevel(), random);
    }
}
