package dev.opendraft.mixin;

import dev.opendraft.SharedRandom;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.function.Consumer;

/** Leather from cows, horses, donkeys and mules comes from one sequence in match worlds. require = 0: otherwise vanilla. */
@Mixin(LootTable.class)
public abstract class LootTableMixin {
    @Shadow @Final private Optional<Identifier> randomSequence;

    @Shadow
    public abstract void getRandomItemsRaw(LootContext context, Consumer<ItemStack> output);

    @Inject(method = "getRandomItemsRaw(Lnet/minecraft/world/level/storage/loot/LootContext;Ljava/util/function/Consumer;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void opendraft$sharedLeather(LootContext context, Consumer<ItemStack> output, CallbackInfo ci) {
        if (SharedRandom.leather(randomSequence, context, output, this::getRandomItemsRaw)) ci.cancel();
    }
}
