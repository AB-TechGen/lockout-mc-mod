package dev.opendraft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Holds the player in spectator mode high above where they'll start, before the clock starts: while
 * everyone's world loads, during a draft, and through the lookout. They can look around (holding
 * right-click zooms like a spyglass) but not move. Sneaking drops them to the ground for a look from
 * there, and jumping takes them back up, except in the lookout's last seconds, which are spent on the
 * ground. Afterwards they get their old game mode back. Runs on the integrated server.
 * <p>
 * In a match world everyone starts on the world's spawn point itself. Vanilla puts each player at a
 * random spot up to 10 blocks from it, which can be at the bottom of a ravine for one player and on
 * the grass for another; the same seed gives everyone the same spawn point, so this way they all
 * start in the same place.
 */
final class Lookout {
    /** Blocks above the highest ground nearby, so the view is open in every direction. */
    private static final int CLEARANCE = 20;
    private static final int RADIUS = 24;

    final UUID player;
    private final double x, y, z, airY;
    private final GameType mode;
    private final float flyingSpeed;
    /** Looking from the ground instead of the sky. */
    private boolean ground;

    private Lookout(ServerPlayer p, Vec3 start) {
        this.player = p.getUUID();
        this.x = start.x;
        this.y = start.y;
        this.z = start.z;
        this.airY = Math.min(highestGround(p.level(), start) + CLEARANCE, 330);
        this.mode = p.isSpectator() ? GameType.SPECTATOR : p.isCreative() ? GameType.CREATIVE : GameType.SURVIVAL;
        this.flyingSpeed = p.getAbilities().getFlyingSpeed();
    }

    static Lookout begin(ServerPlayer p) {
        ServerLevel level = p.level();
        Vec3 start = p.position();
        if (level.dimension() == Level.OVERWORLD && WorldMaker.isMatchWorld(level)) {
            start = spawnPoint(level, p);
            // Deaths without a bed respawn on the spawn point too, not at a random spot around it.
            level.getGameRules().set(GameRules.RESPAWN_RADIUS, 0, level.getServer());
        }
        Lookout l = new Lookout(p, start);
        p.setGameMode(GameType.SPECTATOR);
        p.teleportTo(l.x, l.airY, l.z);
        l.tick(p, false);
        return l;
    }

    /**
     * Where the player stands on the world's spawn point: up out of anything solid or wet there, then down
     * onto the ground, the way vanilla places a player when the respawn radius is 0.
     */
    private static Vec3 spawnPoint(ServerLevel level, ServerPlayer p) {
        EntityDimensions size = p.getType().getDimensions();
        BlockPos.MutableBlockPos at = level.getRespawnData().pos().mutable();
        while (!fits(level, size, at) && at.getY() < level.getMaxY()) at.move(Direction.UP);
        at.move(Direction.DOWN);
        while (fits(level, size, at) && at.getY() > level.getMinY()) at.move(Direction.DOWN);
        at.move(Direction.UP);
        return Vec3.atBottomCenterOf(at);
    }

    /** Whether a player standing at {@code at} would be clear of blocks and liquid. */
    private static boolean fits(ServerLevel level, EntityDimensions size, BlockPos at) {
        return level.noCollision(null, size.makeBoundingBox(Vec3.atBottomCenterOf(at)), true);
    }

    /** The highest solid block within RADIUS of where the player starts, sampled every 6 blocks, so no hill or tree is in the way. */
    private static int highestGround(Level level, Vec3 start) {
        int cx = (int) Math.floor(start.x), cz = (int) Math.floor(start.z);
        int floor = (int) Math.ceil(start.y);
        int best = floor;
        for (int dx = -RADIUS; dx <= RADIUS; dx += 6) {
            for (int dz = -RADIUS; dz <= RADIUS; dz += 6) {
                for (int by = 319; by > best; by--) {
                    if (!level.getBlockState(new BlockPos(cx + dx, by, cz + dz)).isAir()) {
                        best = by + 1;
                        break;
                    }
                }
            }
        }
        return best;
    }

    /** @param ending the lookout's last seconds: on the ground for good, so everyone starts from where they'll play */
    void tick(ServerPlayer p, boolean ending) {
        if (!p.isSpectator()) p.setGameMode(GameType.SPECTATOR);
        Abilities a = p.getAbilities();
        if (a.getFlyingSpeed() != 0f) {
            a.setFlyingSpeed(0f); // no drifting: looking around works, moving doesn't
            p.onUpdateAbilities();
        }
        // The keys the client last reported: sneak for the ground, jump for the sky.
        Input input = p.getLastClientInput();
        if (input.shift() || ending) ground = true;
        else if (input.jump()) ground = false;
        double atY = ground ? y : airY;
        if (p.distanceToSqr(new Vec3(x, atY, z)) > 0.25) p.teleportTo(x, atY, z);
    }

    /** Back to the ground, in the game mode they had. */
    void finish(ServerPlayer p) {
        p.setGameMode(mode);
        Abilities a = p.getAbilities();
        a.setFlyingSpeed(flyingSpeed);
        p.onUpdateAbilities();
        p.teleportTo(x, y, z);
        p.fallDistance = 0;
    }
}
