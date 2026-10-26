package dev.opendraft;

import dev.opendraft.goal.Checks;
import dev.opendraft.goal.Goal;
import dev.opendraft.goal.Tracker;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Runs on the integrated server of this player's singleplayer world. It holds the player in the
 * air until the clock starts (while everyone loads, during a draft and the lookout), then watches
 * them, checks the board every half second and sends a claim the first time a goal is done. It
 * also tells the others about advancements and says when this world is ready for the match.
 */
public final class ServerHooks {
    private static final int CHECK_EVERY = 10;
    private static final int ADVANCEMENTS_EVERY = 20;
    private static volatile Tracker tracker;
    private static volatile ServerPlayer trackedPlayer;
    private static Lookout lookout;
    private static int tick;
    /** Progress text ("3/5") per board cell, for the board's hover text. Replaced, never edited. */
    private static volatile String[] progress = new String[0];
    private static volatile int progressBoard;

    // Advancements already done (or already told), so only new ones are sent.
    private static Set<String> advancementsSeen;
    private static Object advancementsFor;

    private ServerHooks() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(ServerHooks::onTick);
        // Quitting mid-lookout must not save the player in spectator, up in the air.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (server != Minecraft.getInstance().getSingleplayerServer()) return;
            endLookout(server);
            advancementsSeen = null;
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            Tracker t = tracker;
            ServerPlayer p = trackedPlayer;
            if (t == null || p == null) return;
            if (entity == p) {
                t.onDeath(p, source);
                return;
            }
            LivingEntity credit = source.getEntity() instanceof LivingEntity l ? l : entity.getKillCredit();
            if (credit == p) t.onKill(p, entity, source);
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            Tracker t = trackerFor(player);
            if (t != null) t.onUseEntity((ServerPlayer) player, player.getItemInHand(hand), entity);
            return InteractionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            Tracker t = trackerFor(player);
            if (t != null) t.onUseBlock((ServerPlayer) player, player.getItemInHand(hand), hit.getBlockPos());
            return InteractionResult.PASS;
        });
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            Tracker t = trackerFor(player);
            if (t != null) t.onAttack((ServerPlayer) player, entity);
            return InteractionResult.PASS;
        });
        ServerEntityLevelChangeEvents.AFTER_ENTITY_CHANGE_LEVEL.register((original, moved, origin, dest) -> {
            Tracker t = tracker;
            if (t != null && trackedPlayer != null) t.onEntityChangedWorld(trackedPlayer, moved);
        });
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, dest) -> {
            Tracker t = trackerFor(player);
            if (t != null) t.onPlayerChangedWorld(player, origin, dest);
        });
    }

    /** The tracker, if {@code player} is the local player on the server side. Used by the mixins too. */
    public static Tracker trackerFor(Object player) {
        Tracker t = tracker;
        return t != null && player == trackedPlayer ? t : null;
    }

    public static ServerPlayer trackedPlayer() {
        return trackedPlayer;
    }

    /** Progress for board cell {@code i} of {@code boardId}, or null. Read by the board screen. */
    public static String progress(int boardId, int i) {
        String[] p = progress;
        return boardId == progressBoard && i < p.length ? p[i] : null;
    }

    /** The player on this game's own integrated server. */
    private static ServerPlayer localPlayer(MinecraftServer server) {
        var lp = Minecraft.getInstance().player;
        UUID id = lp == null ? null : lp.getUUID();
        return id == null ? null : server.getPlayerList().getPlayer(id);
    }

    private static void onTick(MinecraftServer server) {
        if (server != Minecraft.getInstance().getSingleplayerServer()) return;
        Session s = Session.current();
        long worldSeed = server.overworld().getSeed();
        if (MatchLock.on(s, worldSeed)) MatchLock.enforce(server);
        boolean hold = s != null && s.holding(worldSeed);
        if (lookout != null && !hold) endLookout(server);
        ServerPlayer p = s == null ? null : localPlayer(server);
        if (p == null) {
            tracker = null;
            trackedPlayer = null;
            if (s == null) advancementsSeen = null;
            return;
        }
        tick++;
        if (tick % ADVANCEMENTS_EVERY == 0 && s.rightWorld(worldSeed)) relayAdvancements(server, s, p);
        // This world is the lobby match's: tell the synchronizer it's loaded.
        Long matchSeed = s.matchSeed;
        if (s.waitingForStart && matchSeed != null && matchSeed == worldSeed && s.readySent != s.matchId) {
            s.readySent = s.matchId;
            s.send("READY " + s.matchId);
        }

        List<Goal> goals = s.goals;
        if (hold) {
            tracker = null;
            if (lookout == null) lookout = Lookout.begin(p);
            else lookout.tick(p);
            return;
        }
        if (goals == null || !s.running() && !s.ended || s.iForfeited() || !s.rightWorld(worldSeed)) {
            tracker = null;
            trackedPlayer = null;
            return;
        }
        Tracker t = tracker;
        if (t == null || t.boardId != s.boardId || !t.player.equals(p.getUUID())) {
            // Baselines are taken when the clock starts, so nothing from before (or the lookout) counts.
            t = new Tracker(s.boardId, p.getUUID());
            for (Goal g : goals) {
                if (g != null) t.baselines.put(g.key, Checks.baseline(g, p));
            }
            tracker = t;
        }
        trackedPlayer = p; // the object changes after respawn
        t.tick(p);
        if (s.ended || tick % CHECK_EVERY != 0) return;
        String[] owners = s.owners;
        String[] prog = new String[goals.size()];
        for (int i = 0; i < goals.size() && i < owners.length; i++) {
            Goal g = goals.get(i);
            if (g == null || owners[i] != null || s.sent.contains(i)) continue;
            boolean done;
            try {
                done = Checks.done(g, p, t, t.baselines.get(g.key));
                if (!done) prog[i] = Checks.progress(g, p, t, t.baselines.get(g.key));
            } catch (RuntimeException e) {
                OpenDraftClient.LOG.warn("Checking goal {} failed", g.key, e);
                done = false;
            }
            if (done) {
                s.sent.add(i);
                s.send("CLAIM " + s.boardId + " " + i);
            }
        }
        progress = prog;
        progressBoard = s.boardId;
    }

    private static void endLookout(MinecraftServer server) {
        Lookout l = lookout;
        lookout = null;
        ServerPlayer p = l == null ? null : server.getPlayerList().getPlayer(l.player);
        if (p == null) return;
        try {
            l.finish(p);
        } catch (RuntimeException e) {
            OpenDraftClient.LOG.warn("Ending the lookout failed", e);
        }
    }

    /**
     * Sends advancements that vanilla would announce in chat to everyone else. Whatever was done
     * before joining (or before this world loaded) is only remembered, not sent. Death messages
     * and other chat stay in each player's own world.
     */
    private static void relayAdvancements(MinecraftServer server, Session s, ServerPlayer p) {
        Object key = List.of(s, server, p.getUUID());
        Map<String, Object> done = Checks.announcedAdvancements(p);
        if (advancementsSeen == null || !key.equals(advancementsFor)) {
            advancementsSeen = new HashSet<>(done.keySet());
            advancementsFor = key;
            return;
        }
        for (Map.Entry<String, Object> e : done.entrySet()) {
            if (!advancementsSeen.add(e.getKey())) continue;
            String title = Checks.titleOf(e.getValue());
            if (!title.isEmpty()) s.send("ADV " + Checks.frameOf(e.getValue()) + " " + title.replace('\n', ' '));
        }
    }
}
