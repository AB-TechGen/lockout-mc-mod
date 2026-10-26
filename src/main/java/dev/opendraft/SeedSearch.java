package dev.opendraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressStructure;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Finds a random seed whose spawn has the structures and biomes the lobby leader asked for nearby, and
 * (with the Nether rule on) a bastion and a fortress near 0, 0 in the Nether with nether wart in one of
 * them. Nothing is generated: for each structure it works out where the game would try to place one and
 * checks the biome there, which is the test the game itself makes first. The worldgen data comes
 * from the game's own code rather than a world, so this runs from the lobby.
 */
public final class SeedSearch {
    /** Height to read surface biomes at. Above the ground the game reports the surface biome. */
    private static final int SURFACE_Y = 256;
    private static final long TIME_LIMIT_MS = 60_000;
    /** The Nether rule: a bastion and a fortress within this many blocks of 0, 0 in the Nether. */
    public static final int NETHER_RADIUS = 250;

    /** Something the leader can ask for near spawn: a structure set, or a biome. */
    public record Target(String key, String label, boolean biome, String id, int sampleY) {
    }

    public static final List<Target> TARGETS = List.of(
            new Target("village", "Village", false, "minecraft:villages", SURFACE_Y),
            new Target("shipwreck", "Shipwreck", false, "minecraft:shipwrecks", SURFACE_Y),
            new Target("outpost", "Outpost", false, "minecraft:pillager_outposts", SURFACE_Y),
            new Target("witch_hut", "Witch Hut", false, "minecraft:swamp_huts", SURFACE_Y),
            new Target("desert_temple", "Desert Temple", false, "minecraft:desert_pyramids", SURFACE_Y),
            new Target("jungle_temple", "Jungle Temple", false, "minecraft:jungle_temples", SURFACE_Y),
            new Target("igloo", "Igloo", false, "minecraft:igloos", SURFACE_Y),
            new Target("mansion", "Mansion", false, "minecraft:woodland_mansions", SURFACE_Y),
            new Target("ruined_portal", "Ruined Portal", false, "minecraft:ruined_portals", SURFACE_Y),
            new Target("trail_ruins", "Trail Ruins", false, "minecraft:trail_ruins", SURFACE_Y),
            new Target("abandoned_camp", "Abandoned Camp", false, "minecraft:abandoned_camp", SURFACE_Y),
            new Target("ancient_city", "Ancient City", false, "minecraft:ancient_cities", -27),
            new Target("trial_chambers", "Trial Chambers", false, "minecraft:trial_chambers", -30),
            new Target("pale_garden", "Pale Garden", true, "minecraft:pale_garden", SURFACE_Y));

    public static Target target(String key) {
        for (Target t : TARGETS) if (t.key().equals(key)) return t;
        return null;
    }

    private static volatile Job job;
    /** Why the last search found nothing, for the lobby to show until the next one. */
    public static volatile String lastProblem;
    private static Gen gen;

    private SeedSearch() {
    }

    public static boolean running() {
        Job j = job;
        return j != null && !j.done;
    }

    public static void cancel() {
        Job j = job;
        if (j != null) j.cancelled = true;
    }

    /** What the lobby shows while a search runs, or null. */
    public static String status() {
        Job j = job;
        if (j == null || j.done) return null;
        long secs = (System.currentTimeMillis() - j.startedAt) / 1000;
        if (j.checked.get() == 0) return "Getting the seed search ready...";
        return "Looking for a seed with " + wanted(j.targets, j.radius, j.nether) + ": "
                + String.format("%,d", j.checked.get()) + " seeds checked (" + secs + "s).";
    }

    public static String labels(List<Target> targets) {
        List<String> names = new ArrayList<>();
        for (Target t : targets) names.add(t.label());
        if (names.size() <= 1) return String.join("", names);
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }

    /** "Village within 300 blocks of spawn, and a bastion and fortress near 0, 0 in the Nether". */
    public static String wanted(List<Target> targets, int radius, boolean nether) {
        String near = targets.isEmpty() ? "" : labels(targets) + " within " + radius + " blocks of spawn";
        String below = nether ? "a bastion and fortress near 0, 0 in the Nether" : "";
        return near.isEmpty() ? below : below.isEmpty() ? near : near + ", and " + below;
    }

    /**
     * Starts searching on background threads. found gets the seed and a line saying how far each
     * thing is from spawn; failed gets why there's no seed. Both run on the game thread.
     */
    public static void start(List<Target> targets, int radius, boolean nether, Consumer<Found> found, Consumer<String> failed) {
        if (running()) return;
        lastProblem = null;
        Job j = new Job(targets, radius, nether, found, failed);
        job = j;
        Thread lead = new Thread(j::run, "opendraft-seed-search");
        lead.setDaemon(true);
        lead.setPriority(Thread.MIN_PRIORITY);
        lead.start();
    }

    public record Found(long seed, String summary) {
    }

    private static final class Job {
        final List<Target> targets;
        final int radius;
        final boolean nether;
        final Consumer<Found> found;
        final Consumer<String> failed;
        final long startedAt = System.currentTimeMillis();
        final AtomicLong checked = new AtomicLong();
        /** How many seeds had each target, and the Nether rule last. */
        final long[] hits;
        volatile boolean cancelled;
        volatile boolean done;
        private volatile Found result;

        Job(List<Target> targets, int radius, boolean nether, Consumer<Found> found, Consumer<String> failed) {
            this.targets = targets;
            this.radius = radius;
            this.nether = nether;
            this.found = found;
            this.failed = failed;
            this.hits = new long[targets.size() + 1];
        }

        void run() {
            String error = null;
            try {
                Gen g = gen();
                List<Check> checks = new ArrayList<>();
                List<String> missing = new ArrayList<>();
                for (Target t : targets) {
                    Check c = g.check(t);
                    if (c == null) missing.add(t.label());
                    else checks.add(c);
                }
                if (nether && g.netherComplexes == null) {
                    error = "The seed search can't find bastions and fortresses in this version of the game. Turn the Nether"
                            + " rule off to search for the rest.";
                } else if (!missing.isEmpty()) {
                    String them = missing.size() == 1 ? "it" : "them";
                    error = "The seed search can't find " + String.join(", ", missing) + " in this version of the game. Turn "
                            + them + " off to search for the rest.";
                } else {
                    int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 2));
                    List<Thread> workers = new ArrayList<>();
                    for (int i = 1; i < threads; i++) {
                        Thread w = new Thread(() -> work(g, checks), "opendraft-seed-search-" + i);
                        w.setDaemon(true);
                        w.setPriority(Thread.MIN_PRIORITY);
                        w.start();
                        workers.add(w);
                    }
                    work(g, checks);
                    for (Thread w : workers) w.join();
                }
            } catch (Throwable e) {
                cancelled = true; // stops the other threads
                OpenDraftClient.LOG.warn("Seed search failed", e);
                error = "The seed search doesn't work in this version of the game (" + e + "). Turn off the structure filter to start.";
            }
            done = true;
            Found r = result;
            String why = error != null || r != null || cancelled ? error : noSeedMessage();
            Minecraft mc = Minecraft.getInstance();
            if (r != null) {
                mc.execute(() -> found.accept(r));
            } else if (why != null) {
                lastProblem = why;
                mc.execute(() -> failed.accept(why));
            }
        }

        private void work(Gen g, List<Check> checks) {
            double[] best = new double[checks.size()];
            while (!cancelled && result == null && System.currentTimeMillis() - startedAt < TIME_LIMIT_MS) {
                long seed = ThreadLocalRandom.current().nextLong();
                World w = g.world(seed);
                boolean all = true;
                for (int i = 0; i < checks.size(); i++) {
                    Hit h = checks.get(i).nearest(w, radius);
                    best[i] = h == null ? -1 : h.distance();
                    if (h == null) all = false;
                    else synchronized (hits) {
                        hits[i]++;
                    }
                }
                Nether below = nether ? g.nether(seed) : null;
                if (below != null && !below.ok()) all = false;
                else if (below != null) synchronized (hits) {
                    hits[targets.size()]++;
                }
                checked.incrementAndGet();
                if (all) {
                    synchronized (this) {
                        if (result == null) result = new Found(seed, summary(checks, best, below));
                    }
                }
            }
        }

        private String summary(List<Check> checks, double[] best, Nether below) {
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < checks.size(); i++) {
                parts.add(checks.get(i).target.label() + " " + Math.round(best[i]) + " blocks");
            }
            String near = parts.isEmpty() ? "" : " " + String.join(", ", parts) + " from spawn.";
            return "Seed found after " + String.format("%,d", checked.get()) + " tries:" + near
                    + (below == null ? "" : " Nether: " + below.summary() + ".");
        }

        private String noSeedMessage() {
            long n = Math.max(1, checked.get());
            List<String> rates = new ArrayList<>();
            synchronized (hits) {
                for (int i = 0; i < targets.size(); i++) {
                    rates.add(targets.get(i).label() + " " + Math.round(100.0 * hits[i] / n) + "%");
                }
                if (nether) rates.add("Nether rule " + Math.round(100.0 * hits[targets.size()] / n) + "%");
            }
            return "No seed out of " + String.format("%,d", checked.get()) + " had " + wanted(targets, radius, nether)
                    + ". How often each turned up: " + String.join(", ", rates)
                    + ". Turn some off or raise the distance.";
        }
    }

    /**
     * Where the search thinks spawn and the nearest of everything it knows about are for one
     * seed. /opendraft seed prints this, so it can be compared with /locate in a world.
     */
    public static List<String> report(long seed, int radius) {
        Gen g = gen();
        World w = g.world(seed);
        List<String> lines = new ArrayList<>();
        lines.add("Seed " + seed + ": spawn near " + w.spawnX() + ", " + w.spawnZ()
                + (w.state() == null ? " (spacing rules skipped)" : ""));
        for (Target t : TARGETS) {
            Check c = g.check(t);
            if (c == null) {
                lines.add(t.label() + ": not found in this version");
                continue;
            }
            Hit h = c.nearest(w, radius);
            lines.add(t.label() + ": " + (h == null ? "none within " + radius + " blocks"
                    : Math.round(h.distance()) + " blocks, at " + h.x() + ", " + h.z()));
        }
        if (g.netherComplexes == null) {
            lines.add("Nether: bastions and fortresses not found in this version");
        } else {
            Nether below = g.nether(seed);
            lines.add("Nether, within " + NETHER_RADIUS + " blocks of 0, 0: " + (below.ok() ? "meets the rule" : "doesn't meet the rule"));
            for (NetherHit h : below.hits()) lines.add("  " + h.describe());
        }
        return lines;
    }

    private static synchronized Gen gen() {
        if (gen == null) gen = new Gen(VanillaRegistries.createWorldLookup());
        return gen;
    }

    /** One seed's world, as far as the search needs it: where spawn is, its biomes and its structure rules. */
    private record World(long seed, int spawnX, int spawnZ, BiomeResolver biomes, ChunkGeneratorStructureState state) {
    }

    /** The overworld's worldgen data, and the Nether's, shared by every seed. */
    private static final class Gen {
        final NoiseGeneratorSettings settings;
        final HolderGetter<NormalNoise> noises;
        final BiomeSource biomes;
        final Holder<NoiseGeneratorSettings> overworld;
        final HolderLookup.RegistryLookup<StructureSet> sets;
        final NoiseGeneratorSettings netherSettings;
        final BiomeSource netherBiomes;
        /** Bastions and fortresses share one set, so each spot gets one or the other. Null if this version lacks it. */
        final StructureSet netherComplexes;
        private volatile Constructor<?> stateConstructor;
        private volatile boolean stateFailed;
        private List<String> bastionStarts;
        private int bastionY;
        private final Map<String, int[]> templateSizes = new ConcurrentHashMap<>();

        Gen(HolderLookup.Provider lookup) {
            overworld = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
            settings = overworld.value();
            noises = lookup.lookupOrThrow(Registries.NOISE);
            biomes = MultiNoiseBiomeSource.createFromPreset(lookup.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                    .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
            sets = lookup.lookupOrThrow(Registries.STRUCTURE_SET);
            netherSettings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.NETHER).value();
            netherBiomes = MultiNoiseBiomeSource.createFromPreset(lookup.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                    .getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER));
            netherComplexes = sets.get(BuiltinStructureSets.NETHER_COMPLEXES).map(Holder::value)
                    .filter(s -> s.placement() instanceof RandomSpreadStructurePlacement).orElse(null);
            for (Constructor<?> c : ChunkGeneratorStructureState.class.getDeclaredConstructors()) {
                Class<?>[] t = c.getParameterTypes();
                if (t.length == 6 && t[0] == RandomState.class && t[1] == BiomeSource.class && t[2] == long.class
                        && t[3] == ChunkPos.class && t[4] == long.class && t[5] == List.class) {
                    c.setAccessible(true);
                    stateConstructor = c;
                }
            }
        }

        World world(long seed) {
            RandomState random = RandomState.create(noises, seed, settings);
            // Spawn is the generator's origin, found from the climate. A generator per seed, in case it remembers.
            ChunkPos origin = new NoiseBasedChunkGenerator(biomes, overworld).getOrigin(random);
            return new World(seed, origin.x() * 16 + 8, origin.z() * 16 + 8, biomes.createUncachedResolver(random),
                    state(random, seed, origin));
        }

        /**
         * What placements use for spacing rules, such as outposts keeping away from villages.
         * The game's own factory reads biome tags, which this data doesn't have loaded, so the
         * state is made directly with no structure list; placement checks don't need one.
         */
        ChunkGeneratorStructureState state(RandomState random, long seed, ChunkPos origin) {
            if (stateConstructor != null) {
                try {
                    return (ChunkGeneratorStructureState) stateConstructor.newInstance(random, biomes, seed, origin, seed, List.of());
                } catch (ReflectiveOperationException | RuntimeException e) {
                    OpenDraftClient.LOG.warn("Making a structure state directly failed", e);
                    stateConstructor = null;
                }
            }
            if (stateFailed) return null;
            try {
                return ChunkGeneratorStructureState.createForNormal(random, seed, origin, biomes, sets);
            } catch (RuntimeException e) {
                OpenDraftClient.LOG.warn("No structure state; outpost and other spacing rules are skipped", e);
                stateFailed = true;
                return null;
            }
        }

        /**
         * How to look for a target, or null if this version doesn't have it. Structure sets are
         * found by id, or failing that (for structures newer than this list) by any structure
         * whose id contains the target's key.
         */
        Check check(Target t) {
            if (t.biome()) return new Check(t, List.of(), Set.of(t.id()));
            List<Placed> placed = new ArrayList<>();
            Set<String> allBiomes = new HashSet<>();
            for (boolean byName : new boolean[]{false, true}) {
                sets.listElements().forEach(ref -> {
                    if (!byName && !ref.getRegisteredName().equals(t.id())) return;
                    StructureSet set = ref.value();
                    if (!(set.placement() instanceof RandomSpreadStructurePlacement spread)) return;
                    Set<String> biomesHere = new HashSet<>();
                    for (StructureSet.StructureSelectionEntry e : set.structures()) {
                        if (byName && !e.structure().getRegisteredName().contains(t.key())) continue;
                        biomesHere.addAll(biomesOf(e.structure().value()));
                    }
                    if (biomesHere.isEmpty()) return;
                    placed.add(new Placed(spread, biomesHere));
                    allBiomes.addAll(biomesHere);
                });
                if (!placed.isEmpty()) break;
            }
            return placed.isEmpty() ? null : new Check(t, placed, allBiomes);
        }

        /**
         * The bastions and fortresses within NETHER_RADIUS of 0, 0 in this seed's Nether. Each region of
         * the set has one spot, and which of the two goes there is decided the way the game does it: a
         * weighted pick, and if a bastion's biome is wrong (basalt deltas) a fortress instead.
         */
        Nether nether(long seed) {
            BiomeResolver biomes = netherBiomes.createUncachedResolver(RandomState.create(noises, seed, netherSettings));
            RandomSpreadStructurePlacement placement = (RandomSpreadStructurePlacement) netherComplexes.placement();
            int spacing = placement.spacing();
            int min = Math.floorDiv(Math.floorDiv(-NETHER_RADIUS, 16), spacing), max = Math.floorDiv(Math.floorDiv(NETHER_RADIUS, 16), spacing);
            List<NetherHit> hits = new ArrayList<>();
            for (int rx = min; rx <= max; rx++) {
                for (int rz = min; rz <= max; rz++) {
                    ChunkPos c = placement.getPotentialStructureChunk(seed, rx * spacing, rz * spacing);
                    int bx = c.getMinBlockX() + 8, bz = c.getMinBlockZ() + 8;
                    double d = Math.sqrt((double) bx * bx + (double) bz * bz);
                    if (d > NETHER_RADIUS) continue;
                    NetherHit h = netherStructure(seed, biomes, c, new Hit(d, bx, bz));
                    if (h != null) hits.add(h);
                }
            }
            return new Nether(hits);
        }

        private NetherHit netherStructure(long seed, BiomeResolver biomes, ChunkPos c, Hit at) {
            WorldgenRandom pick = structureRandom(seed, c);
            List<StructureSet.StructureSelectionEntry> left = new ArrayList<>(netherComplexes.structures());
            int total = 0;
            for (StructureSet.StructureSelectionEntry e : left) total += e.weight();
            while (!left.isEmpty()) {
                int roll = pick.nextInt(total), i = 0;
                for (StructureSet.StructureSelectionEntry e : left) {
                    roll -= e.weight();
                    if (roll < 0) break;
                    i++;
                }
                StructureSet.StructureSelectionEntry e = left.get(i);
                Structure s = e.structure().value();
                NetherHit h = s instanceof NetherFortressStructure ? fortress(seed, biomes, c, s, at) : bastion(seed, biomes, c, e, at);
                if (h != null) return h;
                left.remove(i);
                total -= e.weight();
            }
            return null;
        }

        /** A fortress, if the biome lets it be there, and whether its corridors lead to a nether wart room. */
        private NetherHit fortress(long seed, BiomeResolver biomes, ChunkPos c, Structure s, Hit at) {
            if (!biomeAt(biomes, c.getMinBlockX(), 64, c.getMinBlockZ(), biomesOf(s))) return null;
            // The layout only depends on the seed, so it's built here the way the game builds it, without blocks.
            WorldgenRandom random = structureRandom(seed, c);
            NetherFortressPieces.StartPiece start = new NetherFortressPieces.StartPiece(random, c.getBlockX(2), c.getBlockZ(2));
            StructurePiecesBuilder builder = new StructurePiecesBuilder();
            builder.addPiece(start);
            start.addChildren(start, builder, random);
            List<StructurePiece> pending = start.pendingChildren;
            while (!pending.isEmpty()) pending.remove(random.nextInt(pending.size())).addChildren(start, builder, random);
            boolean wart = builder.build().pieces().stream().anyMatch(p -> p instanceof NetherFortressPieces.CastleStalkRoom);
            return new NetherHit("Fortress", true, wart, at);
        }

        /**
         * A bastion, if the biome in the middle of its first piece lets it be there. The first piece also
         * says which kind it is, and only housing bastions have nether wart (in every one of their centres).
         */
        private NetherHit bastion(long seed, BiomeResolver biomes, ChunkPos c, StructureSet.StructureSelectionEntry e, Hit at) {
            List<String> starts = bastionStarts(e.structure().getRegisteredName());
            WorldgenRandom random = structureRandom(seed, c);
            Rotation rotation = Rotation.getRandom(random);
            String start = starts.get(random.nextInt(starts.size()));
            int x = c.getMinBlockX(), z = c.getMinBlockZ();
            int[] size = templateSize(start);
            if (size.length == 3) {
                // The piece turns about the chunk's corner; the game takes the middle of where it ends up.
                BlockPos far = new BlockPos(size[0] - 1, size[1] - 1, size[2] - 1).rotate(rotation);
                x = (2 * x + far.getX()) / 2;
                z = (2 * z + far.getZ()) / 2;
            }
            if (!biomeAt(biomes, x, bastionY, z, biomesOf(e.structure().value()))) return null;
            String kind = start.contains("/units/") ? "housing" : start.contains("/hoglin_stable/") ? "hoglin stable"
                    : start.contains("/treasure/") ? "treasure" : start.contains("/bridge/") ? "bridge" : "other";
            return new NetherHit("Bastion (" + kind + ")", false, kind.equals("housing"), at);
        }

        /** The bastion's possible first pieces, from its start pool (repeated by weight, as the game picks them), and its height. */
        private synchronized List<String> bastionStarts(String structure) {
            if (bastionStarts != null) return bastionStarts;
            JsonObject s = json(dataFile(structure, "worldgen/structure"));
            bastionY = s.getAsJsonObject("start_height").get("absolute").getAsInt();
            List<String> out = new ArrayList<>();
            for (JsonElement el : json(dataFile(s.get("start_pool").getAsString(), "worldgen/template_pool")).getAsJsonArray("elements")) {
                JsonObject o = el.getAsJsonObject();
                String location = o.getAsJsonObject("element").get("location").getAsString();
                for (int i = 0; i < o.get("weight").getAsInt(); i++) out.add(location);
            }
            bastionStarts = out;
            return out;
        }

        /** A structure template's size from its file in the game's data, or an empty array if it can't be read. */
        private int[] templateSize(String id) {
            return templateSizes.computeIfAbsent(id, k -> {
                String file = dataFile(k, "structure").replace(".json", ".nbt");
                try (InputStream in = open(file)) {
                    if (in == null) return new int[0];
                    ListTag size = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap()).getListOrEmpty("size");
                    return size.size() == 3 ? new int[]{size.getIntOr(0, 1), size.getIntOr(1, 1), size.getIntOr(2, 1)} : new int[0];
                } catch (Exception e) {
                    OpenDraftClient.LOG.warn("Couldn't read the size of {}", k, e);
                    return new int[0];
                }
            });
        }
    }

    /** The random numbers a structure (and the pick between structures) gets for a chunk. */
    private static WorldgenRandom structureRandom(long seed, ChunkPos c) {
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        random.setLargeFeatureSeed(seed, c.x(), c.z());
        return random;
    }

    /** One bastion or fortress near 0, 0 in the Nether, and whether nether wart grows in it. */
    private record NetherHit(String kind, boolean fortress, boolean wart, Hit at) {
        String describe() {
            return kind + " at " + at.x() + ", " + at.z() + " (" + Math.round(at.distance()) + " blocks)"
                    + (wart ? fortress ? ", with a nether wart room" : ", with nether wart" : "");
        }
    }

    /** The Nether rule for one seed: a bastion and a fortress near 0, 0, and nether wart in one of them. */
    private record Nether(List<NetherHit> hits) {
        boolean ok() {
            return nearest(h -> !h.fortress()) != null && nearest(NetherHit::fortress) != null && nearest(NetherHit::wart) != null;
        }

        String summary() {
            NetherHit wart = nearest(NetherHit::wart);
            return "bastion " + Math.round(nearest(h -> !h.fortress()).at().distance()) + " and fortress "
                    + Math.round(nearest(NetherHit::fortress).at().distance()) + " blocks from 0, 0, nether wart in the "
                    + (wart.fortress() ? "fortress" : "housing bastion");
        }

        private NetherHit nearest(java.util.function.Predicate<NetherHit> which) {
            NetherHit best = null;
            for (NetherHit h : hits) if (which.test(h) && (best == null || h.at().distance() < best.at().distance())) best = h;
            return best;
        }
    }

    private record Placed(RandomSpreadStructurePlacement placement, Set<String> biomes) {
    }

    /** Where the nearest one is (the middle of its chunk, for structures) and how far from spawn. */
    private record Hit(double distance, int x, int z) {
    }

    private record Check(Target target, List<Placed> placed, Set<String> biomes) {
        /** The nearest one within radius of spawn, or null. */
        Hit nearest(World w, int radius) {
            int sx = w.spawnX(), sz = w.spawnZ();
            Hit best = null;
            if (target.biome()) {
                int step = radius > 400 ? 32 : 24;
                for (int dx = -radius; dx <= radius; dx += step) {
                    for (int dz = -radius; dz <= radius; dz += step) {
                        double d = Math.sqrt((double) dx * dx + (double) dz * dz);
                        if (d > radius || (best != null && d >= best.distance())) continue;
                        if (biomeAt(w.biomes(), sx + dx, target.sampleY(), sz + dz, biomes)) best = new Hit(d, sx + dx, sz + dz);
                    }
                }
                return best;
            }
            for (Placed p : placed) {
                StructurePlacement placement = p.placement();
                int spacing = p.placement().spacing();
                int minRx = Math.floorDiv(Math.floorDiv(sx - radius, 16), spacing), maxRx = Math.floorDiv(Math.floorDiv(sx + radius, 16), spacing);
                int minRz = Math.floorDiv(Math.floorDiv(sz - radius, 16), spacing), maxRz = Math.floorDiv(Math.floorDiv(sz + radius, 16), spacing);
                for (int rx = minRx; rx <= maxRx; rx++) {
                    for (int rz = minRz; rz <= maxRz; rz++) {
                        ChunkPos c = p.placement().getPotentialStructureChunk(w.seed(), rx * spacing, rz * spacing);
                        int bx = c.x() * 16 + 8, bz = c.z() * 16 + 8;
                        double d = Math.sqrt((double) (bx - sx) * (bx - sx) + (double) (bz - sz) * (bz - sz));
                        if (d > radius || (best != null && d >= best.distance())) continue;
                        if (w.state() != null && !placement.isStructureChunk(w.state(), c.x(), c.z())) continue;
                        if (biomeAt(w.biomes(), bx, target.sampleY(), bz, p.biomes())) best = new Hit(d, bx, bz);
                    }
                }
            }
            return best;
        }
    }

    private static boolean biomeAt(BiomeResolver biomes, int x, int y, int z, Set<String> wanted) {
        return wanted.contains(biomes.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z))
                .getRegisteredName());
    }

    // Structures name their biomes with a tag. The game's data files list each tag's biomes.
    private static final Map<String, Set<String>> TAGS = new LinkedHashMap<>();

    private static Set<String> biomesOf(Structure s) {
        HolderSet<Biome> set = s.biomes();
        if (set.unwrapKey().isPresent()) return biomeTag(set.unwrapKey().get().location().toString(), new HashSet<>());
        Set<String> out = new HashSet<>();
        set.stream().forEach(h -> out.add(h.getRegisteredName()));
        return out;
    }

    private static synchronized Set<String> biomeTag(String id, Set<String> visiting) {
        Set<String> cached = TAGS.get(id);
        if (cached != null) return cached;
        Set<String> out = new HashSet<>();
        if (!visiting.add(id)) return out;
        int colon = id.indexOf(':');
        String ns = colon < 0 ? "minecraft" : id.substring(0, colon), path = id.substring(colon + 1);
        String file = "data/" + ns + "/tags/worldgen/biome/" + path + ".json";
        try (InputStream in = open(file)) {
            if (in == null) {
                OpenDraftClient.LOG.warn("Biome tag {} not found", id);
            } else {
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                for (JsonElement el : root.getAsJsonArray("values")) {
                    String v = el.isJsonObject() ? el.getAsJsonObject().get("id").getAsString() : el.getAsString();
                    if (v.startsWith("#")) out.addAll(biomeTag(v.substring(1), visiting));
                    else out.add(v.contains(":") ? v : "minecraft:" + v);
                }
            }
        } catch (Exception e) {
            OpenDraftClient.LOG.warn("Couldn't read biome tag {}", id, e);
        }
        TAGS.put(id, out);
        return out;
    }

    /** "minecraft:bastion/starts" in "worldgen/template_pool" is data/minecraft/worldgen/template_pool/bastion/starts.json. */
    private static String dataFile(String id, String folder) {
        int colon = id.indexOf(':');
        String ns = colon < 0 ? "minecraft" : id.substring(0, colon), path = id.substring(colon + 1);
        return "data/" + ns + "/" + folder + "/" + path + ".json";
    }

    private static JsonObject json(String file) {
        try (InputStream in = open(file)) {
            if (in == null) throw new IllegalStateException(file + " is missing");
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Couldn't read " + file, e);
        }
    }

    private static InputStream open(String file) {
        for (ClassLoader cl : new ClassLoader[]{SeedSearch.class.getClassLoader(), Minecraft.class.getClassLoader(),
                Thread.currentThread().getContextClassLoader()}) {
            if (cl == null) continue;
            InputStream in = cl.getResourceAsStream(file);
            if (in != null) return in;
        }
        return null;
    }
}
