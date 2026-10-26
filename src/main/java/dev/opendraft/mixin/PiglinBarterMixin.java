package dev.opendraft.mixin;

import dev.opendraft.SharedRandom;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** The ender pearl guarantee for the first gold bartered in a match world. require = 0: otherwise barters stay vanilla. */
@Mixin(PiglinAi.class)
public abstract class PiglinBarterMixin {
    @Inject(method = "getBarterResponseItems", at = @At("RETURN"), cancellable = true, require = 0)
    private static void opendraft$pearls(Piglin piglin, CallbackInfoReturnable<List<ItemStack>> cir) {
        cir.setReturnValue(SharedRandom.barter(piglin, cir.getReturnValue()));
    }
}
