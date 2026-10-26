package dev.opendraft;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Holds the player in spectator mode high above where they stand, before the clock starts: while
 * everyone's world loads, during a draft, and through the lookout. They can look around (holding
 * right-click zooms like a spyglass) but not move. Sneaking drops them to where they stand for a
 * look from the ground, and jumping takes them back up, except in the lookout's last seconds, which
 * are spent on the ground. Afterwards they get their old game mode back. Runs on the integrated server.
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

    private Lookout(ServerPlayer p) {
        this.player = p.getUUID();
        this.x = p.getX();
        this.y = p.getY();
        this.z = p.getZ();
        this.airY = Math.min(highestGround(p) + CLEARANCE, 330);
        this.mode = p.isSpectator() ? GameType.SPECTATOR : p.isCreative() ? GameType.CREATIVE : GameType.SURVIVAL;
        this.flyingSpeed = p.getAbilities().getFlyingSpeed();
    }

    static Lookout begin(ServerPlayer p) {
        Lookout l = new Lookout(p);
        p.setGameMode(GameType.SPECTATOR);
        p.teleportTo(l.x, l.airY, l.z);
        l.tick(p, false);
        return l;
    }

    /** The highest solid block within RADIUS of the player, sampled every 6 blocks, so no hill or tree is in the way. */
    private static int highestGround(ServerPlayer p) {
        Level level = p.level();
        int cx = (int) Math.floor(p.getX()), cz = (int) Math.floor(p.getZ());
        int floor = (int) Math.ceil(p.getY());
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
