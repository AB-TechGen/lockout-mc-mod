package dev.opendraft.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.opendraft.SharedRandom;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** An iron golem's damage on players, by hit. require = 0: otherwise vanilla. */
@Mixin(IronGolem.class)
public abstract class IronGolemMixin {
    @Redirect(method = "doHurtTarget", require = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;nextInt(I)I"))
    private int opendraft$sharedDamage(RandomSource random, int bound, @Local(argsOnly = true) ServerLevel level,
                                       @Local(argsOnly = true) Entity target) {
        return SharedRandom.golemRoll(level, target, random, bound);
    }
}
