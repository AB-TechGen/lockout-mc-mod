package dev.opendraft.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.opendraft.SharedRandom;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Whether a food's chance effect happens (rotten flesh, raw chicken, poisonous potato...), by meal. require = 0: otherwise vanilla. */
@Mixin(ApplyStatusEffectsConsumeEffect.class)
public abstract class FoodEffectMixin {
    @Redirect(method = "apply", require = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;nextFloat()F"))
    private float opendraft$sharedEffect(RandomSource random, @Local(argsOnly = true) Level level,
                                         @Local(argsOnly = true) ItemStack food, @Local(argsOnly = true) LivingEntity eater) {
        return SharedRandom.foodRoll(level, food, eater, random);
    }
}
