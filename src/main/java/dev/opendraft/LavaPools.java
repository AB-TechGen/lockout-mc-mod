package dev.opendraft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.LakeFeature;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RarityFilter;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.StructureSet;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Lava pools in lobby match worlds, the way Draftout has them: surface lava pools come twice as often as
 * in vanilla, and there are {@link #WANTED} of them {@link #NEAREST} to {@link #FARTHEST} blocks from
 * spawn. Natural ones in that ring count towards the {@link #WANTED}, and the rest are added. Everything
 * is worked out from the seed and the pools are made with the terrain, as each chunk is generated, so
 * every player's world gets the same ones. Runs on the integrated server's worldgen threads.
 */
public final class LavaPools {
    static final int WANTED = 3, NEAREST = 50, FARTHEST = 200;
    /** Spots in a ring pool's chunk to try before giving up on it, when water or a cliff is in the way. */
    private static final int TRIES = 16;
    /** Height to read surface biomes at. Above the ground the game reports the surface biome. */
    private static final int SURFACE_Y = 256;
    // Mixed into the seed so the extra tries, the added pools' spots and the retries don't repeat vanilla's numbers.
    private static final long EXTRA_SALT = 0x6F70656E64726166L, SITE_SALT = 0x6C617661706F6F6CL, RETRY_SALT = 0x7265747279696E67L;
    private static final ResourceKey<PlacedFeature> SURFACE_POOL = ResourceKey.create(Registries.PLACED_FEATURE,
            Identifier.withDefaultNamespace("lake_lava_surface"));
    private static final int LAKES = GenerationStep.Decoration.LAKES.ordinal();
    private static final ThreadLocal<Boolean> BUSY = ThreadLocal.withInitial(() -> false);
    private static volatile Plan plan;

    /** The ring's pools for one seed, by chunk: the natural ones (so they're made sure of) and the added ones. */
    private record Plan(long seed, PlacedFeature pool, Map<Long, BlockPos> sites) {
    }

    private LavaPools() {
    }

    /**
     * Called each time a chunk's surface lava pool has been tried, with whether one was made. In a match
     * world the chunk gets one more try, which doubles the rate, and a chunk the plan picked for the ring
     * that still has no pool gets one wherever it fits.
     */
    public static void afterTry(FeaturePlacer placer, WorldGenLevel level, ChunkGenerator generator, PlacedFeature feature,
                                BlockPos origin, boolean placed) {
        if (BUSY.get() || !(feature.feature().value() instanceof LakeFeature)) return;
        ServerLevel server = level.getLevel();
        if (server.dimension() != Level.OVERWORLD
                || server.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE).getValue(SURFACE_POOL) != feature
                || !WorldMaker.isMatchWorld(server)) return;
        BUSY.set(true);
        try {
            long seed = level.getSeed();
            // With its own random numbers, so vanilla's pools stay where they'd be and this one is extra.
            placed |= placer.placeWithBiomeCheck(feature, extraRandom(seed, origin), origin);
            BlockPos site = plan(server, generator, feature, seed).sites().get(ChunkPos.pack(origin));
            if (site != null && !placed) placeNear(level, generator, feature, site, seed);
        } finally {
            BUSY.set(false);
        }
    }

    private static synchronized Plan plan(ServerLevel server, ChunkGenerator generator, PlacedFeature pool, long seed) {
        Plan p = plan;
        if (p != null && p.seed() == seed && p.pool() == pool) return p;
        p = makePlan(server, generator, pool, seed);
        plan = p;
        return p;
    }

    /**
     * Where the ring's pools go. The natural ones are where a chunk's try (vanilla's, or the extra one)
     * rolls a pool in the ring in a biome that has them; the added ones go at random spots in the ring,
     * on dry land, away from the others and from villages.
     */
    private static Plan makePlan(ServerLevel server, ChunkGenerator generator, PlacedFeature pool, long seed) {
        // Spawn is picked in the generator's origin chunk (or next to it), so the ring is measured from there.
        ChunkPos origin = generator.getOrigin(server.getChunkSource().randomState());
        int sx = origin.getMinBlockX() + 8, sz = origin.getMinBlockZ() + 8;
        int index = poolIndex(generator, pool);
        PlacementContext context = new PlacementContext(server, generator, Optional.of(pool));
        Map<Long, BlockPos> sites = new HashMap<>();
        List<BlockPos> taken = new ArrayList<>();
        for (int cx = (sx - FARTHEST) >> 4; cx <= (sx + FARTHEST) >> 4; cx++) {
            for (int cz = (sz - FARTHEST) >> 4; cz <= (sz + FARTHEST) >> 4; cz++) {
                BlockPos corner = new BlockPos(cx << 4, server.getMinY(), cz << 4);
                List<RandomSource> tries = index < 0 ? List.of(extraRandom(seed, corner))
                        : List.of(vanillaRandom(seed, corner, index), extraRandom(seed, corner));
                for (RandomSource random : tries) {
                    BlockPos at = roll(context, pool, random, corner);
                    if (at == null || !inRing(at, sx, sz) || !poolBiome(server, generator, pool, at, false)) continue;
                    sites.putIfAbsent(ChunkPos.pack(at), at);
                    taken.add(at);
                }
            }
        }
        int natural = sites.size();
        RandomSource random = RandomSource.create(seed ^ SITE_SALT);
        for (int i = 0; i < 400 && sites.size() < WANTED; i++) {
            double angle = random.nextDouble() * Math.PI * 2, distance = NEAREST + 16 + random.nextDouble() * (FARTHEST - NEAREST - 32);
            BlockPos at = new BlockPos(sx + (int) Math.round(Math.cos(angle) * distance), 0, sz + (int) Math.round(Math.sin(angle) * distance));
            if (sites.containsKey(ChunkPos.pack(at)) || near(at, taken) || !poolBiome(server, generator, pool, at, true)
                    || nearVillage(server, at)) continue;
            sites.put(ChunkPos.pack(at), at);
            taken.add(at);
        }
        OpenDraftClient.LOG.info("Lava pools {}-{} blocks from spawn ({}, {}): {} natural, {} added",
                NEAREST, FARTHEST, sx, sz, natural, sites.size() - natural);
        return new Plan(seed, pool, Map.copyOf(sites));
    }

    /** The random numbers vanilla gives this chunk's surface lava pool. */
    private static WorldgenRandom vanillaRandom(long seed, BlockPos corner, int index) {
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
        random.setFeatureSeed(random.setDecorationSeed(seed, corner.getX(), corner.getZ()), index, LAKES);
        return random;
    }

    /** The random numbers for this chunk's extra try at a surface lava pool. */
    private static WorldgenRandom extraRandom(long seed, BlockPos corner) {
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
        random.setDecorationSeed(seed ^ EXTRA_SALT, corner.getX(), corner.getZ());
        return random;
    }

    /**
     * Where a try puts its pool if its rarity roll comes up, or null. These first steps of the pool's
     * placement (the roll, then a spot in the chunk) only use the random numbers, so they're run as they
     * are; the ones after need the terrain.
     */
    private static BlockPos roll(PlacementContext context, PlacedFeature pool, RandomSource random, BlockPos corner) {
        BlockPos at = corner;
        boolean rolled = false;
        for (PlacementModifier m : pool.placement()) {
            if (!(m instanceof RarityFilter) && !(m instanceof InSquarePlacement)) break;
            BlockPos[] next = {null};
            m.modify(context, random, at, p -> next[0] = p);
            if (next[0] == null) return null;
            at = next[0];
            rolled |= m instanceof RarityFilter;
        }
        return rolled ? at : null;
    }

    /** Where vanilla's surface lava pool comes in its step, which seeds its random numbers; -1 if that can't be read. */
    @SuppressWarnings("unchecked")
    private static int poolIndex(ChunkGenerator generator, PlacedFeature pool) {
        try {
            Field f = ChunkGenerator.class.getDeclaredField("featuresPerStep");
            f.setAccessible(true);
            List<FeatureSorter.StepFeatureData> steps = ((Supplier<List<FeatureSorter.StepFeatureData>>) f.get(generator)).get();
            int index = steps.get(LAKES).indexMapping().applyAsInt(pool);
            if (index >= 0) return index;
        } catch (ReflectiveOperationException | RuntimeException e) {
            OpenDraftClient.LOG.warn("Couldn't work out vanilla's lava pool order", e);
        }
        OpenDraftClient.LOG.warn("Natural lava pools near spawn won't be counted, so up to {} are added", WANTED);
        return -1;
    }

    private static boolean inRing(BlockPos at, int sx, int sz) {
        double d = Math.hypot(at.getX() - sx, at.getZ() - sz);
        return d >= NEAREST && d <= FARTHEST;
    }

    private static boolean near(BlockPos at, List<BlockPos> taken) {
        for (BlockPos t : taken) if (Math.hypot(at.getX() - t.getX(), at.getZ() - t.getZ()) < 48) return true;
        return false;
    }

    /** Whether the biome there has surface lava pools. An added one also needs dry land, as water stops a pool forming. */
    private static boolean poolBiome(ServerLevel server, ChunkGenerator generator, PlacedFeature pool, BlockPos at, boolean dry) {
        Holder<Biome> biome = server.getUncachedNoiseBiome(QuartPos.fromBlock(at.getX()), QuartPos.fromBlock(SURFACE_Y), QuartPos.fromBlock(at.getZ()));
        if (!generator.getBiomeGenerationSettings(biome).hasFeature(pool)) return false;
        return !dry || !(biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER) || biome.is(BiomeTags.IS_BEACH));
    }

    /** Whether a village might start within 4 chunks; an added pool there would set it on fire. */
    private static boolean nearVillage(ServerLevel server, BlockPos at) {
        StructureSet villages = server.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET).getValue(BuiltinStructureSets.VILLAGES);
        if (villages == null) return false;
        ChunkGeneratorStructureState state = server.getChunkSource().getGeneratorState();
        int cx = at.getX() >> 4, cz = at.getZ() >> 4;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (villages.placement().isStructureChunk(state, cx + dx, cz + dz)) return true;
            }
        }
        return false;
    }

    /** A pool in the site's chunk: at the site, or failing that wherever in the chunk the terrain lets one form. */
    private static void placeNear(WorldGenLevel level, ChunkGenerator generator, PlacedFeature pool, BlockPos site, long seed) {
        RandomSource random = RandomSource.create(seed ^ RETRY_SALT ^ ChunkPos.pack(site));
        int x0 = site.getX() & ~15, z0 = site.getZ() & ~15;
        for (int i = 0; i < TRIES; i++) {
            int x = i == 0 ? site.getX() : x0 + random.nextInt(16), z = i == 0 ? site.getZ() : z0 + random.nextInt(16);
            BlockPos at = new BlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z), z);
            if (pool.feature().value().place(level, generator, random, at)) return;
        }
    }
}
