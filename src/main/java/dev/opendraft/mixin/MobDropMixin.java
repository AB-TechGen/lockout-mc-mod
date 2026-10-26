package dev.opendraft.mixin;

import dev.opendraft.SharedRandom;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Whether a mob drops its gear (a pillager's crossbow, a witch's potion, piglin armour), by kill. require = 0: otherwise vanilla. */
@Mixin(Mob.class)
public abstract class MobDropMixin {
    @Redirect(method = "dropCustomDeathLoot", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;nextFloat()F", ordinal = 0))
    private float opendraft$sharedDrop(RandomSource random) {
        return SharedRandom.equipmentRoll((Mob) (Object) this, random);
    }
}
