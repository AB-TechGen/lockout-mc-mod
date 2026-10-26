package dev.opendraft.mixin;

import dev.opendraft.ServerHooks;
import dev.opendraft.goal.Tracker;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notices when the player takes an output from a workstation or a potion from a brewing stand.
 * Taking a result always uses up the inputs, so we compare the slots before and after each click.
 * The handlers take no arguments from {@code clicked}, so a change to its parameters can't break them.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class ContainerMenuMixin {
    @Unique
    private ItemStack[] opendraft$before;

    @Unique
    private String opendraft$station() {
        Object self = this;
        if (self instanceof AnvilMenu) return "anvil";
        if (self instanceof SmithingMenu) return "smithing";
        if (self instanceof GrindstoneMenu) return "grindstone";
        if (self instanceof StonecutterMenu) return "stonecutter";
        if (self instanceof LoomMenu) return "loom";
        if (self instanceof CartographyTableMenu) return "cartography";
        if (self instanceof BrewingStandMenu) return "brewing";
        return null;
    }

    /** {inputs..., result} slot indexes for each station. */
    @Unique
    private static int[] opendraft$slots(String station) {
        return switch (station) {
            case "smithing" -> new int[]{0, 1, 2, 3};
            case "stonecutter" -> new int[]{0, 1};
            case "loom" -> new int[]{0, 1, 3};
            case "brewing" -> new int[]{0, 1, 2};
            default -> new int[]{0, 1, 2}; // anvil, grindstone, cartography
        };
    }

    @Unique
    private boolean opendraft$tracked() {
        ServerPlayer p = ServerHooks.trackedPlayer();
        return p != null && p.containerMenu == (Object) this && ServerHooks.trackerFor(p) != null;
    }

    @Inject(method = "clicked", at = @At("HEAD"))
    private void opendraft$beforeClick(CallbackInfo ci) {
        opendraft$before = null;
        String station = opendraft$station();
        if (station == null || !opendraft$tracked()) return;
        AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
        int[] slots = opendraft$slots(station);
        ItemStack[] copy = new ItemStack[slots.length];
        for (int i = 0; i < slots.length; i++) copy[i] = menu.getSlot(slots[i]).getItem().copy();
        opendraft$before = copy;
    }

    @Inject(method = "clicked", at = @At("RETURN"))
    private void opendraft$afterClick(CallbackInfo ci) {
        ItemStack[] before = opendraft$before;
        opendraft$before = null;
        if (before == null) return;
        String station = opendraft$station();
        Tracker t = ServerHooks.trackerFor(ServerHooks.trackedPlayer());
        if (station == null || t == null) return;
        AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
        int[] slots = opendraft$slots(station);
        if (station.equals("brewing")) {
            for (int i = 0; i < slots.length; i++) {
                if (!before[i].isEmpty() && menu.getSlot(slots[i]).getItem().isEmpty()) t.onTookPotion(before[i]);
            }
            return;
        }
        ItemStack result = before[before.length - 1];
        if (result.isEmpty()) return;
        for (int i = 0; i < slots.length - 1; i++) {
            ItemStack now = menu.getSlot(slots[i]).getItem();
            if (!before[i].isEmpty() && (now.isEmpty() || now.getCount() < before[i].getCount() || !ItemStack.isSameItem(now, before[i]))) {
                t.onTookResult(station, result);
                return;
            }
        }
    }
}
