package dev.opendraft.mixin;

import dev.opendraft.ServerHooks;
import dev.opendraft.goal.Tracker;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Records each food, drink and potion the player finishes consuming. */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
    @Inject(method = "finishUsingItem", at = @At("HEAD"))
    private void opendraft$onFinishUsing(Level level, LivingEntity entity, CallbackInfoReturnable<ItemStack> cir) {
        Tracker t = ServerHooks.trackerFor(entity);
        if (t != null) t.onConsumed((ServerPlayer) entity, ((ItemStack) (Object) this).copy());
    }
}
