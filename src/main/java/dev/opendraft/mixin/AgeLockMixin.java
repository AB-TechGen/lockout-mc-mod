package dev.opendraft.mixin;

import dev.opendraft.ServerHooks;
import dev.opendraft.goal.Tracker;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** The player fed a golden dandelion to a baby mob (tadpoles too), which awards no statistic in vanilla. */
@Mixin(AgeableMob.class)
public abstract class AgeLockMixin {
    @Inject(method = "setAgeLocked(Lnet/minecraft/world/entity/Mob;Ljava/util/function/Supplier;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;Ljava/util/function/Consumer;)V",
            at = @At("HEAD"))
    private static void opendraft$onAgeLocked(Mob mob, Supplier<Boolean> locked, Player player, ItemStack dandelion,
                                              Consumer<Mob> apply, CallbackInfo ci) {
        Tracker t = ServerHooks.trackerFor(player);
        if (t != null) t.onFedGoldenDandelion();
    }
}
