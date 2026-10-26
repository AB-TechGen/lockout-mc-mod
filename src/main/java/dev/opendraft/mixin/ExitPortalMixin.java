package dev.opendraft.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.opendraft.SharedRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.NetherPortalBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** A player's first Nether exit from y 55 or higher makes its Overworld portal on the surface. require = 0: otherwise vanilla. */
@Mixin(NetherPortalBlock.class)
public abstract class ExitPortalMixin {
    @ModifyArg(method = "getExitPortal", index = 0, require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/portal/PortalForcer;createPortal(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction$Axis;)Ljava/util/Optional;"))
    private BlockPos opendraft$surfacePortal(BlockPos pos, @Local(argsOnly = true) ServerLevel destination,
                                             @Local(argsOnly = true) Entity entity) {
        return SharedRandom.exitPortal(destination, entity, pos);
    }
}
