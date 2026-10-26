package dev.opendraft.goal;

import com.google.gson.JsonElement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Goals that need something only a few biomes have (bamboo for scaffolding, parrots, powder snow...) say
 * so in goals.json with "biomes": groups of biome ids or #tags, and each group needs one of its biomes
 * within {@link #RADIUS} blocks of spawn for the goal to be dealt. The biomes come from the world's
 * climate, the way /locate biome finds them, so nothing has to be generated.
 */
public final class NearbyBiomes {
    public static final int RADIUS = 400;
    /** Height to read surface biomes at. Above the ground the game reports the surface biome. */
    private static final int SURFACE_Y = 256;
    /** Cave biomes (lush caves, dripstone caves, the deep dark...) are read at these heights too. */
    private static final int[] CAVE_YS = {40, 8, -24, -48};

    private NearbyBiomes() {
    }

    /** The biomes within RADIUS of this world's spawn: the surface every 16 blocks, the caves every 32. */
    public static Set<Holder<Biome>> around(MinecraftServer server) {
        ServerLevel level = server.overworld();
        BlockPos spawn = level.getRespawnData().pos();
        Set<Holder<Biome>> found = new HashSet<>();
        for (int dx = -RADIUS; dx <= RADIUS; dx += 16) {
            for (int dz = -RADIUS; dz <= RADIUS; dz += 16) {
                if (dx * dx + dz * dz > RADIUS * RADIUS) continue;
                int qx = QuartPos.fromBlock(spawn.getX() + dx), qz = QuartPos.fromBlock(spawn.getZ() + dz);
                found.add(level.getUncachedNoiseBiome(qx, QuartPos.fromBlock(SURFACE_Y), qz));
                if (dx % 32 != 0 || dz % 32 != 0) continue;
                for (int y : CAVE_YS) found.add(level.getUncachedNoiseBiome(qx, QuartPos.fromBlock(y), qz));
            }
        }
        return found;
    }

    /** Null when every group of the goal's biomes has one near spawn; otherwise the group that doesn't, for /opendraft goals. */
    public static String missing(Goal g, Set<Holder<Biome>> near) {
        JsonElement groups = g.p.get("biomes");
        if (groups == null || !groups.isJsonArray()) return null;
        for (JsonElement group : groups.getAsJsonArray()) {
            List<String> ids = new ArrayList<>();
            for (JsonElement id : group.getAsJsonArray()) ids.add(id.getAsString());
            if (ids.stream().noneMatch(id -> near.stream().anyMatch(b -> is(b, id)))) {
                return String.join(" or ", ids).replace("minecraft:", "");
            }
        }
        return null;
    }

    private static boolean is(Holder<Biome> biome, String id) {
        if (id.startsWith("#")) return biome.is(TagKey.create(Registries.BIOME, Identifier.parse(id.substring(1))));
        return biome.is(Identifier.parse(id));
    }
}
