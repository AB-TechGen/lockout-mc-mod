package dev.opendraft;

import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Draftout's changes to vanilla luck, in match worlds. The random outcomes that decide a race come from
 * the game's own random sequences, which are seeded by the world seed and saved with the world, so
 * players on the same seed get the same results in the same order: the 3rd diamond ore mined drops the
 * same for everyone. Vanilla already gives each loot table one (bartering included); this groups some
 * of them and adds sequences for equipment drops, pillager captains, food effects and iron golem hits.
 * Also the bartering guarantee and the first Nether exit. Runs on the integrated server.
 */
public final class SharedRandom {
    /** Barters (gold ingots) that are guaranteed {@link #PEARL_TRADES} ender pearl trades: 16 gold blocks. */
    static final int GUARANTEED_BARTERS = 144, PEARL_TRADES = 4;
    /** Players standing at least this high in the Nether get their first Overworld portal on the surface. */
    static final int SURFACE_PORTAL_Y = 55;

    /** Loot tables that share one sequence: each deepslate ore with its stone ore, and ferns with grass. */
    private static final Map<Identifier, Identifier> SHARED = new HashMap<>();
    /** Mobs whose leather comes from one sequence, so it doesn't matter which of them you kill in what order. */
    private static final Set<Identifier> LEATHER = Set.of(Identifier.withDefaultNamespace("entities/cow"),
            Identifier.withDefaultNamespace("entities/horse"), Identifier.withDefaultNamespace("entities/donkey"),
            Identifier.withDefaultNamespace("entities/mule"));
    private static final ThreadLocal<Boolean> ROLLING = ThreadLocal.withInitial(() -> false);

    static {
        for (String ore : List.of("coal", "copper", "iron", "gold", "redstone", "lapis", "diamond", "emerald")) {
            SHARED.put(Identifier.withDefaultNamespace("blocks/deepslate_" + ore + "_ore"), Identifier.withDefaultNamespace("blocks/" + ore + "_ore"));
        }
        SHARED.put(Identifier.withDefaultNamespace("blocks/fern"), Identifier.withDefaultNamespace("blocks/short_grass"));
        SHARED.put(Identifier.withDefaultNamespace("blocks/large_fern"), Identifier.withDefaultNamespace("blocks/tall_grass"));
    }

    /** Barters made in this world and how many gave ender pearls, saved with it. */
    private static final AttachmentType<List<Integer>> BARTERS = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath("opendraft", "barters"), b -> b.persistent(Codec.INT.listOf()));
    /** The player has left the Nether before, so a new portal goes wherever vanilla puts it. */
    private static final AttachmentType<Boolean> LEFT_NETHER = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath("opendraft", "left_nether"), b -> b.persistent(Codec.BOOL).copyOnDeath());

    private SharedRandom() {
    }

    /** Registers the saved data. Called at startup, before any world loads, so saved values are read back. */
    static void register() {
    }

    private static boolean on(Level level) {
        return level instanceof ServerLevel s && WorldMaker.isMatchWorld(s);
    }

    /** One of this mod's own sequences, such as "captain" or "food/rotten_flesh". */
    private static RandomSource sequence(ServerLevel level, String name) {
        return level.getServer().getRandomSequence(Identifier.fromNamespaceAndPath("opendraft", name));
    }

    /** The sequence a loot table draws from: its own, or the one it shares in match worlds. */
    public static Identifier sequenceFor(MinecraftServer server, Identifier table) {
        Identifier shared = SHARED.get(table);
        ServerLevel overworld = shared == null ? null : server.overworld();
        return overworld != null && WorldMaker.isMatchWorld(overworld) ? shared : table;
    }

    /**
     * Leather from cows, horses, donkeys and mules comes from one sequence of its own: the table is rolled as
     * usual for everything else, and the leather (0-2, plus up to the Looting level) is drawn separately.
     * Returns whether it handled the roll.
     */
    public static boolean leather(Optional<Identifier> table, LootContext ctx, Consumer<ItemStack> out, BiConsumer<LootContext, Consumer<ItemStack>> roll) {
        if (ROLLING.get() || table.isEmpty() || !LEATHER.contains(table.get()) || !on(ctx.getLevel())) return false;
        ROLLING.set(true);
        try {
            roll.accept(ctx, stack -> {
                if (!stack.is(Items.LEATHER)) out.accept(stack);
            });
        } finally {
            ROLLING.set(false);
        }
        RandomSource random = sequence(ctx.getLevel(), "leather");
        int count = random.nextInt(3), looting = looting(ctx);
        if (looting > 0) count += random.nextInt(looting + 1);
        if (count > 0) out.accept(new ItemStack(Items.LEATHER, count));
        return true;
    }

    private static int looting(LootContext ctx) {
        if (!(ctx.getOptional(LootContextParams.ATTACKING_ENTITY) instanceof LivingEntity killer)) return 0;
        return ctx.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(Enchantments.LOOTING)
                .map(looting -> EnchantmentHelper.getItemEnchantmentLevel(looting, killer.getMainHandItem())).orElse(0);
    }

    /**
     * A barter's result. The barters themselves follow vanilla's bartering sequence; on top of that, a player
     * who is behind on ender pearl trades after each 36 barters (4 gold blocks) gets one there, so the first
     * {@link #GUARANTEED_BARTERS} give at least {@link #PEARL_TRADES}. Since the sequence is the same for
     * everyone, so are the barters this changes.
     */
    public static List<ItemStack> barter(Piglin piglin, List<ItemStack> items) {
        if (!(piglin.level() instanceof ServerLevel level) || !on(level)) return items;
        ServerLevel world = level.getServer().overworld();
        List<Integer> counts = world.getAttachedOrElse(BARTERS, List.of(0, 0));
        int barters = counts.get(0) + 1, pearls = counts.get(1);
        boolean pearl = items.stream().anyMatch(s -> s.is(Items.ENDER_PEARL));
        int every = GUARANTEED_BARTERS / PEARL_TRADES;
        if (!pearl && barters <= GUARANTEED_BARTERS && barters % every == 0 && pearls < barters / every) {
            items = new ArrayList<>(List.of(new ItemStack(Items.ENDER_PEARL, 2 + sequence(level, "barter_pearls").nextInt(3))));
            pearl = true;
        }
        world.setAttached(BARTERS, List.of(barters, pearls + (pearl ? 1 : 0)));
        return items;
    }

    /** Whether a mob drops what it's holding or wearing: one sequence per kind of mob, by kill. */
    public static float equipmentRoll(Entity mob, RandomSource vanilla) {
        if (!on(mob.level())) return vanilla.nextFloat();
        return sequence((ServerLevel) mob.level(), "equipment/" + BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath()).nextFloat();
    }

    /** Whether a naturally spawned pillager (or other patrolling mob) is a captain: by spawn number. */
    public static float captainRoll(Level level, RandomSource vanilla) {
        return on(level) ? sequence((ServerLevel) level, "captain").nextFloat() : vanilla.nextFloat();
    }

    /** Whether a food's effect (hunger from rotten flesh, poison from a poisonous potato...) happens: one sequence per food. */
    public static float foodRoll(Level level, ItemStack food, LivingEntity eater, RandomSource vanilla) {
        if (!(eater instanceof ServerPlayer) || !on(level)) return vanilla.nextFloat();
        return sequence((ServerLevel) level, "food/" + BuiltInRegistries.ITEM.getKey(food.getItem()).getPath()).nextFloat();
    }

    /** An iron golem's extra damage on a player, which in vanilla varies a lot. */
    public static int golemRoll(Level level, Entity target, RandomSource vanilla, int bound) {
        if (!(target instanceof ServerPlayer) || !on(level)) return vanilla.nextInt(bound);
        return sequence((ServerLevel) level, "iron_golem").nextInt(bound);
    }

    /**
     * Where a new Overworld portal goes. A player's first Nether exit from y 55 or higher gets one on the
     * surface, instead of wherever near that height vanilla finds room (often a cave).
     */
    public static BlockPos exitPortal(ServerLevel destination, Entity entity, BlockPos pos) {
        if (!(entity instanceof ServerPlayer player) || destination.dimension() != Level.OVERWORLD
                || player.level().dimension() != Level.NETHER || player.getY() < SURFACE_PORTAL_Y
                || player.getAttachedOrElse(LEFT_NETHER, false) || !on(destination)) return pos;
        // The chunk is loaded (made if need be) and asked directly: Level.getHeight says the bottom of the world for a
        // chunk that isn't loaded yet, which it usually isn't on a first exit, and the portal would go as deep as it can.
        int surface = destination.getChunk(pos.getX() >> 4, pos.getZ() >> 4)
                .getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX() & 15, pos.getZ() & 15) + 1;
        return new BlockPos(pos.getX(), surface, pos.getZ());
    }

    /** After a player changes dimension: leaving the Nether uses up the surface portal. */
    static void changedDimension(ServerPlayer player, ServerLevel from) {
        if (from.dimension() == Level.NETHER) player.setAttached(LEFT_NETHER, true);
    }
}
