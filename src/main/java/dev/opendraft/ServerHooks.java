package dev.opendraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opendraft.goal.Checks;
import dev.opendraft.goal.Goal;
import dev.opendraft.goal.Tracker;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Runs on the integrated server of this player's singleplayer world. It holds the player in the
 * air until the clock starts (while everyone loads, during a draft and the lookout), then watches
 * them, checks the board every half second and sends a claim the first time a goal is done. It
 * also tells the others about advancements. (Saying this world is ready for the match is done on
 * the client, by {@link OpenDraftClient}, once the terrain has been drawn.)
 */
public final class ServerHooks {
    private static final int CHECK_EVERY = 10;
    private static final int ADVANCEMENTS_EVERY = 20;
    /** The tracker is saved this often (and when the world closes), in case the game doesn't close cleanly. */
    private static final int SAVE_EVERY = 600;
    /** In the world's folder: the board's tracker, so leaving and rejoining the match carries on from where it was. */
    private static final String TRACKER_FILE = "opendraft_tracker.json";
    private static volatile Tracker tracker;
    private static volatile ServerPlayer trackedPlayer;
    /** Set on the server thread, read on the client thread to tell when the player is up in the air. */
    private static volatile Lookout lookout;
    private static int tick;
    /** Progress text ("3/5") per board cell, for the board's hover text. Replaced, never edited. */
    private static volatile String[] progress = new String[0];
    private static volatile int progressBoard;

    // Advancements already done (or already told), so only new ones are sent.
    private static Set<String> advancementsSeen;
    private static Object advancementsFor;
    /** The match key this world was last marked with, so the file is only written when it changes. */
    private static String markedKey;
    /** The player made a spectator when the match ended, and the game mode they had, given back before the world saves. */
    private static volatile UUID endSpectator;
    private static GameType modeBeforeEnd;

    private ServerHooks() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(ServerHooks::onTick);
        // Quitting mid-lookout must not save the player in spectator, up in the air.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (server != Minecraft.getInstance().getSingleplayerServer()) return;
            endLookout(server);
            endSpectating(server);
            saveTracker(server);
            advancementsSeen = null;
            markedKey = null;
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
        // After the block is gone but before the tool takes damage, so a pickaxe that breaks on it still counts.
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            Tracker t = trackerFor(player);
            if (t != null) t.onBreakBlock(player.getMainHandItem(), state);
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
            SharedRandom.changedDimension(player, origin);
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

    /** The player is being held for the lookout (or while everyone loads), so the view they'll get is in place. */
    public static boolean holdingPlayer() {
        return lookout != null;
    }

    /**
     * The player held for the lookout, if they're in {@code level}. They're a spectator, which vanilla
     * leaves out of mob spawning, so without this nothing would spawn for them to see. Used by the mixins.
     */
    public static ServerPlayer heldPlayerIn(ServerLevel level) {
        Lookout l = lookout;
        ServerPlayer p = l == null ? null : level.getServer().getPlayerList().getPlayer(l.player);
        return p != null && p.level() == level ? p : null;
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
        if (endSpectator != null && (s == null || !s.ended)) endSpectating(server); // a new match in this world
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
        String key = s.matchKey;
        Long matchSeed = s.matchSeed;
        if (key != null && matchSeed != null && matchSeed == worldSeed && !key.equals(markedKey)) {
            markedKey = key;
            WorldCleaner.markMatchWorld(server.getWorldPath(LevelResource.ROOT), key);
        }

        List<Goal> goals = s.goals;
        if (hold) {
            tracker = null;
            if (lookout == null) lookout = Lookout.begin(p);
            else lookout.tick(p, s.lookoutEnding());
            return;
        }
        if (goals == null || !s.running() && !s.ended || s.iForfeited() || !s.rightWorld(worldSeed)) {
            tracker = null;
            trackedPlayer = null;
            return;
        }
        Tracker t = tracker;
        if (t == null || t.boardId != s.boardId || !t.player.equals(p.getUUID())) {
            // Carries on from the saved tracker if this world was closed during the board (left and rejoined).
            // Otherwise baselines are taken when the clock starts, so nothing from before (or the lookout) counts.
            t = loadTracker(server, s, p.getUUID());
            if (t == null) {
                t = new Tracker(s.boardId, p.getUUID());
                for (Goal g : goals) {
                    if (g != null) t.baselines.put(g.key, Checks.baseline(g, p));
                }
            }
            tracker = t;
        }
        trackedPlayer = p; // the object changes after respawn
        t.tick(p);
        if (tick % SAVE_EVERY == 0) saveTracker(server);
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
        String[] before = progressBoard == s.boardId ? progress : null;
        for (int i = 0; before != null && i < prog.length && i < before.length; i++) {
            if (prog[i] != null && before[i] != null && !prog[i].equals(before[i]) && announced(goals.get(i))) {
                OpenDraftClient.overlay(Component.literal(goals.get(i).name + ": " + prog[i]).withStyle(ChatFormatting.GRAY));
            }
        }
        progress = prog;
        progressBoard = s.boardId;
    }

    /** Writes the current tracker to the world's folder, with the match and board it's for. */
    private static void saveTracker(MinecraftServer server) {
        Tracker t = tracker;
        Session s = Session.current();
        String key = s == null ? null : s.matchKey;
        if (t == null || key == null || t.boardId != s.boardId) return;
        JsonObject o = new JsonObject();
        o.addProperty("match", key);
        o.addProperty("board", t.boardId);
        o.addProperty("player", t.player.toString());
        o.add("tracker", t.save());
        Path file = server.getWorldPath(LevelResource.ROOT).resolve(TRACKER_FILE);
        Path temp = file.resolveSibling(TRACKER_FILE + ".tmp");
        try {
            Files.writeString(temp, o.toString());
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            OpenDraftClient.LOG.warn("Saving goal progress failed", e);
        }
    }

    /** The saved tracker, if it's for this match's current board and this player; otherwise null. */
    private static Tracker loadTracker(MinecraftServer server, Session s, UUID player) {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve(TRACKER_FILE);
        if (s.matchKey == null || !Files.isRegularFile(file)) return null;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (!s.matchKey.equals(o.get("match").getAsString()) || o.get("board").getAsInt() != s.boardId
                    || !player.toString().equals(o.get("player").getAsString())) {
                return null;
            }
            OpenDraftClient.LOG.info("Carrying on with saved goal progress for board {}", s.boardId);
            return Tracker.load(o.getAsJsonObject("tracker"), s.boardId, player);
        } catch (IOException | RuntimeException e) {
            OpenDraftClient.LOG.warn("Reading saved goal progress failed", e);
            return null;
        }
    }

    /** Goals whose progress is shown above the hotbar as it goes up: unique foods, unique breeds and mob kills. */
    private static boolean announced(Goal g) {
        return g.type.equals("set") && List.of("consumed", "bred", "hostile_kills").contains(g.str("set", ""))
                || g.type.equals("counter_sum") || g.type.equals("stat") && g.list("keys").contains("minecraft:mob_kills");
    }

    /**
     * The match is over: the player watches as a spectator until they leave for the lobby. Called from the client
     * thread, which ticks on while the integrated server is paused, so it's done as a task the server runs anyway.
     */
    static void spectateAfterEnd(MinecraftServer server) {
        server.execute(() -> {
            if (endSpectator != null) return;
            endLookout(server); // ended during the lookout: back to their own game mode first
            ServerPlayer p = localPlayer(server);
            if (p == null || p.isSpectator()) return;
            modeBeforeEnd = p.isCreative() ? GameType.CREATIVE : GameType.SURVIVAL;
            endSpectator = p.getUUID();
            p.setGameMode(GameType.SPECTATOR);
        });
    }

    /** Gives the player made a spectator at the end their game mode back, so the world isn't saved with them in spectator. */
    private static void endSpectating(MinecraftServer server) {
        UUID id = endSpectator;
        endSpectator = null;
        ServerPlayer p = id == null ? null : server.getPlayerList().getPlayer(id);
        if (p != null) p.setGameMode(modeBeforeEnd);
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
