package dev.opendraft;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.opendraft.goal.Goals;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public final class OpenDraftClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("opendraft");

    private static final int ME = 0xFF00BCD4;
    private static final int[] OTHERS = {0xFFE53935, 0xFFFDD835, 0xFF43A047, 0xFFD81B60, 0xFFFB8C00, 0xFF8E24AA, 0xFF1E88E5, 0xFFEEEEEE};

    /** Opens the full-screen board (B by default; rebind it under Controls). */
    public static KeyMapping BOARD_KEY;
    /** Hides or shows the small board. Unbound by default; the board is shown again each match. */
    public static KeyMapping TOGGLE_BOARD_KEY;
    /** Turns fullbright on or off. G by default (vanilla doesn't use it); key mappings can't include Alt. */
    public static KeyMapping FULLBRIGHT_KEY;
    /** The longest chat message passed on; the synchronizer turns away longer lines. */
    private static final int MAX_CHAT = 250;
    /** Unbound, for a key mapping. 26.3 numbers keys by SDL scancode, so the game's own constants are used, not GLFW's. */
    private static final int NO_KEY = InputConstants.UNKNOWN.getValue();
    // What this game last told the synchronizer about where its player is, in which session and connection.
    private static Session whereFor;
    private static int whereWelcome;
    private static String whereSent;

    /** Chunks around the player, in each direction, that must have arrived before this game says it's ready. */
    private static final int NEARBY_CHUNKS = 4;
    /** Ticks those chunks must have been there before this game says it's ready, so they've been drawn too. */
    private static final int SETTLE_TICKS = 20;
    /** Ticks in the match world after which it's ready anyway, like vanilla's own loading screen gives up after 30s. */
    private static final int READY_ANYWAY_TICKS = 30 * 20;
    private static int settledTicks;
    private static int inWorldTicks;

    public static String modVersion() {
        return FabricLoader.getInstance().getModContainer("opendraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
    }

    @Override
    public void onInitializeClient() {
        Goals.load();
        Settings.load();
        Updater.start();
        ServerHooks.register();
        SharedRandom.register();
        HudElementRegistry.addFirst(Identifier.fromNamespaceAndPath("opendraft", "waiting"), BoardHud::waitingCover);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("opendraft", "board"), BoardHud::extractRenderState);
        HudElementRegistry.replaceElement(VanillaHudElements.PLAYER_LIST, BoardHud::playerList);
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("opendraft", "opendraft"));
        BOARD_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.opendraft.board", InputConstants.KEY_B, category));
        TOGGLE_BOARD_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.opendraft.toggle_board", NO_KEY, category));
        FULLBRIGHT_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.opendraft.fullbright", InputConstants.KEY_G, category));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (TOGGLE_BOARD_KEY.consumeClick()) BoardHud.visible = !BoardHud.visible;
            while (FULLBRIGHT_KEY.consumeClick()) toggleFullbright();
            reportWhere(mc);
            while (BOARD_KEY.consumeClick()) {
                if (mc.player == null || mc.gui.screen() != null) continue;
                if (Session.current() == null) {
                    chat(Component.literal("Not in an OpenDraft game. Use the OpenDraft button on the title screen.")
                            .withStyle(ChatFormatting.GRAY));
                } else {
                    mc.gui.setScreen(new BoardScreen());
                }
            }
            WorldMaker.tick();
            reportReady(mc);
            // F1 would hide the waiting cover along with the rest of the HUD.
            Session s = Session.current();
            MinecraftServer server = mc.getSingleplayerServer();
            if (s != null && server != null && mc.gui.hud.isHidden() && s.waitingForOthers(server.overworld().getSeed())) {
                mc.gui.hud.toggle();
            }
            openWantedBoard(mc, s);
            wipeChatAtStart(mc, s);
        });
        // The lobby button on the title screen, filling in Create World for lobby matches, and no LAN or cheats mid-match.
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            WorldMaker.afterInit(screen);
            MatchLock.lockScreen(screen);
            ScreenEvents.afterTick(screen).register(MatchLock::lockScreen);
        });
        // Chat typed in a world goes to everyone in the lobby, like on a server, and comes back to you from there.
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            Session s = Session.current();
            if (s == null || !s.welcomed || !Settings.playerChat) return true;
            s.send("CHAT " + (message.length() > MAX_CHAT ? message.substring(0, MAX_CHAT) : message));
            return false;
        });
        // Leaving a world keeps you in the lobby; the title screen takes you back to it.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (Session.current() != null) WorldMaker.returnToLobby = true;
        });
        // Quitting the game closes a lobby it runs, and tells everyone, so their games don't spend minutes trying to
        // reconnect to it. Players who only joined keep their place for a while, in case they come back.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            Session s = Session.current();
            if (s != null && s.host != null) s.host.close();
        });
        // Everything a player does has a screen or a key; the commands left are for checking worlds and seeds.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var root = literal("opendraft")
                    .executes(ctx -> help(ctx.getSource()))
                    .then(literal("goals").executes(ctx -> goals(ctx.getSource())))
                    .then(literal("ids")
                            .then(argument("word", StringArgumentType.word())
                                    .executes(ctx -> ids(ctx.getSource(), StringArgumentType.getString(ctx, "word")))));
            // Where every structure is would spoil exploring, so it's only for checking the seed search.
            if (devCommands()) {
                root.then(literal("seed")
                        .executes(ctx -> seedReport(ctx.getSource(), 1000))
                        .then(argument("blocks", IntegerArgumentType.integer(50, 5000))
                                .executes(ctx -> seedReport(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "blocks")))));
            }
            dispatcher.register(root);
        });
    }

    /** In a dev run (runClient), or with -Dopendraft.dev=true added to the game's JVM arguments. */
    private static boolean devCommands() {
        return FabricLoader.getInstance().isDevelopmentEnvironment() || Boolean.getBoolean("opendraft.dev");
    }

    // ---- commands -------------------------------------------------------------------------

    private static int help(FabricClientCommandSource src) {
        src.sendFeedback(Component.literal(String.join("\n",
                "OpenDraft " + modVersion(),
                "Everything is in the lobby (the OpenDraft button on the title screen) and on the board (" + boardKeyName() + ").",
                "For checking a world:",
                "/opendraft goals - which goals this world can't deal, and why",
                "/opendraft ids <word> - list block, item and entity ids containing a word")));
        if (devCommands()) {
            src.sendFeedback(Component.literal("/opendraft seed [blocks] - where the seed search thinks spawn, each structure and the Nether ones are"));
        }
        return 1;
    }

    private static MinecraftServer world(FabricClientCommandSource src) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            src.sendError(Component.literal("Open your singleplayer world first. Everyone plays their own world on the same seed."));
        }
        return server;
    }

    /** Deals a board from this world (goals that don't exist in it are left out). Returns an error, or null. */
    static String deal(Session s, int size, int minutes, int overtime, boolean draft) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return "Open your singleplayer world first.";
        // A draft offers twice as many goals as the board needs, so the last picks are still choices.
        int want = draft ? Math.min(size * 2, 50) : size;
        List<String> keys = Goals.pickBoard(want, server, new Random());
        if (keys.size() < size) return "Only " + keys.size() + " goals are available.";
        String timing = (minutes * 60) + " " + (minutes > 0 ? overtime * 60 : 0) + " " + Settings.lookout;
        if (draft) {
            s.send("DRAFT " + timing + " " + size + " " + Settings.turnSeconds + " " + String.join(" ", keys));
        } else {
            s.send("NEWBOARD " + timing + " " + String.join(" ", keys));
        }
        return null;
    }

    /** The lobby leader, once everyone's match world has loaded: deals with the lobby settings. */
    static void dealBoard() {
        Session s = Session.current();
        if (s == null || !s.isLeader()) return;
        String error = deal(s, Settings.size, Settings.minutes, Settings.overtime, Settings.mode.equals("draft"));
        if (error != null) chat(Component.literal("OpenDraft: couldn't deal the board: " + error).withStyle(ChatFormatting.RED));
    }

    /**
     * Tells the synchronizer this game's match world has loaded. That's once the loading screen is gone,
     * the player is up in the air and the chunks around them have arrived and been drawn, not when the
     * world opens: a slower computer can take a while longer to get there, and the board waits for
     * everyone. It doesn't wait for the whole render distance, which can take minutes to generate and
     * keeps the renderer busy all that time.
     */
    private static void reportReady(Minecraft mc) {
        Session s = Session.current();
        MinecraftServer server = mc.getSingleplayerServer();
        Long seed = s == null ? null : s.matchSeed;
        if (s == null || seed == null || !s.waitingForStart || s.readySent == s.matchId
                || server == null || mc.player == null || mc.level == null || server.overworld().getSeed() != seed) {
            settledTicks = 0;
            inWorldTicks = 0;
            return;
        }
        inWorldTicks++;
        boolean loaded = !(mc.gui.screen() instanceof LevelLoadingScreen) && ServerHooks.holdingPlayer() && nearbyChunksArrived(mc);
        settledTicks = loaded ? settledTicks + 1 : 0;
        if (settledTicks < SETTLE_TICKS && inWorldTicks < READY_ANYWAY_TICKS) return;
        s.readySent = s.matchId;
        s.send("READY " + s.matchId);
    }

    /**
     * Whether every chunk within {@link #NEARBY_CHUNKS} of the player has arrived. At a short render distance
     * it's fewer, since the server never sends the corners of a square as wide as the render distance.
     */
    private static boolean nearbyChunksArrived(Minecraft mc) {
        var player = mc.player;
        var level = mc.level;
        if (player == null || level == null) return false;
        int r = Math.min(NEARBY_CHUNKS, mc.options.getEffectiveRenderDistance() - 1);
        int cx = SectionPos.blockToSectionCoord(player.getBlockX());
        int cz = SectionPos.blockToSectionCoord(player.getBlockZ());
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                if (!level.getChunkSource().hasChunk(x, z)) return false;
            }
        }
        return true;
    }

    /** Tells the synchronizer which dimension this player is in (or that they're in the lobby), for everyone's tab list. */
    private static void reportWhere(Minecraft mc) {
        Session s = Session.current();
        if (s == null || !s.welcomed) return;
        String where = mc.level == null ? "lobby" : mc.level.dimension() == Level.NETHER ? "nether"
                : mc.level.dimension() == Level.END ? "end" : "overworld";
        if (s == whereFor && s.welcomes == whereWelcome && where.equals(whereSent)) return;
        whereFor = s;
        whereWelcome = s.welcomes; // told again after a reconnect, since the synchronizer may have missed it
        whereSent = where;
        s.send("WHERE " + where);
    }

    /** Fog in a match's Nether starts and ends much further out than vanilla's 10 to 96 blocks, so you can see far. */
    public static void netherFog(FogData fog, ClientLevel level) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (level.dimension() != Level.NETHER || server == null || !WorldMaker.isMatchWorld(server.overworld())) return;
        fog.environmentalStart = Math.max(fog.environmentalStart, NETHER_FOG_START);
        fog.environmentalEnd = Math.max(fog.environmentalEnd, NETHER_FOG_END);
    }

    private static final float NETHER_FOG_START = 96, NETHER_FOG_END = 512;

    /**
     * Opens the board when the draft starts or it's our turn, as soon as nothing else is on screen: a player in the
     * pause menu, chat or their inventory gets it once they close that, instead of having it pulled out from under them.
     */
    private static void openWantedBoard(Minecraft mc, Session s) {
        if (s == null || !s.boardWanted) return;
        if (!s.drafting() || mc.gui.screen() instanceof BoardScreen) {
            s.boardWanted = false; // the draft is over, or the board is open already
        } else if (mc.player != null && mc.gui.screen() == null) {
            s.boardWanted = false;
            mc.gui.setScreen(new BoardScreen());
        }
    }

    /**
     * Clears chat once, when a lookout ends and the clock starts, so the game begins with an empty chat. Only when
     * seen live: someone reconnecting into a running game keeps theirs. (Without a lookout, START does it.)
     */
    private static void wipeChatAtStart(Minecraft mc, Session s) {
        if (s == null || s.chatWiped || s.goals == null || !s.hadLookout || s.startMillis == Session.NOT_STARTED) return;
        long since = System.currentTimeMillis() - s.startMillis;
        if (since < 0) return;
        s.chatWiped = true;
        if (since < 2000 && mc.player != null) mc.gui.hud.getChat().clearMessages(false);
    }

    /** Clears what chat shows. The messages you sent stay, so the up arrow still brings them back. */
    public static void clearChat() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.gui.hud.getChat().clearMessages(false));
    }

    /** The lobby's Start button: shares the settings and sends everyone into a new world. */
    static void startLobbyMatch() {
        Session s = Session.current();
        if (s == null || !s.isLeader() || SeedSearch.running()) return;
        s.send("SETTINGS " + Settings.encode());
        List<SeedSearch.Target> near = Settings.filterTargets();
        if (!Settings.seed.isEmpty()) {
            // A typed seed is used as it is, so it can't be held to the filter or the Nether rule.
            SeedSearch.lastProblem = null;
            s.send("MATCH " + Settings.matchSeed());
            return;
        }
        // Every random seed has a bastion and a fortress near 0, 0 in the Nether, so there's always a search.
        // Only the leader sees the details (on the lobby screen); everyone else's lobby says "Searching for a seed...".
        int radius = Settings.radius;
        s.send("SEARCHING 1");
        SeedSearch.start(near, radius, true, found -> {
            if (Session.current() != s) return;
            // Only start if nothing changed while searching.
            if (!s.isLeader() || s.matchOn()) {
                s.send("SEARCHING 0");
                return;
            }
            LOG.info(found.summary());
            s.send("MATCH " + found.seed());
        }, why -> {
            if (Session.current() != s) return;
            s.send("SEARCHING 0");
            sayInLobby(s, "Couldn't find a seed with all of that.");
        });
    }

    /** Stop Search in the lobby. A cancelled search calls neither of its callbacks, so the others are told here. */
    static void stopLobbySearch() {
        SeedSearch.cancel();
        Session s = Session.current();
        if (s != null) s.send("SEARCHING 0");
    }

    /** Whether the board's New Seed button is there: the leader, during a draft, with no typed seed to reroll from. */
    static boolean canReroll(Session s) {
        return s != null && s.isLeader() && s.drafting() && Settings.seed.isEmpty();
    }

    /**
     * The board's New Seed button: pauses the draft, finds a seed like Start does and sends everyone into a new
     * world, where a new draft is dealt. If no seed is found, the draft goes on.
     */
    static void rerollSeed() {
        Session s = Session.current();
        if (!canReroll(s) || s.draftPaused || SeedSearch.running()) return;
        int draft = s.draftId;
        s.send("PAUSEDRAFT " + draft);
        SeedSearch.start(Settings.filterTargets(), Settings.radius, true, found -> {
            if (Session.current() != s) return;
            // Still ours to restart: nobody else became leader and the draft wasn't replaced meanwhile.
            if (s.isLeader() && s.drafting() && s.draftId == draft) {
                LOG.info(found.summary());
                s.send("MATCH " + found.seed());
            } else {
                s.send("RESUMEDRAFT " + draft);
            }
        }, why -> {
            if (Session.current() != s) return;
            sayInLobby(s, "Couldn't find a new seed with all of that.");
            s.send("RESUMEDRAFT " + draft);
        });
    }

    /** Stop Search on the board: the draft goes on. A cancelled search calls neither of its callbacks. */
    static void cancelReroll() {
        Session s = Session.current();
        if (!SeedSearch.running()) return;
        SeedSearch.cancel();
        if (s != null && s.drafting()) s.send("RESUMEDRAFT " + s.draftId);
    }

    /** Prints where the seed search thinks things are in this world, to check it against /locate. */
    private static int seedReport(FabricClientCommandSource src, int radius) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            src.sendError(Component.literal("Open a singleplayer world first."));
            return 0;
        }
        long seed = server.overworld().getSeed();
        src.sendFeedback(Component.literal("Working it out...").withStyle(ChatFormatting.GRAY));
        Thread t = new Thread(() -> {
            try {
                for (String line : SeedSearch.report(seed, radius)) chat(Component.literal(line));
            } catch (Throwable e) {
                LOG.warn("Seed report failed", e);
                chat(Component.literal("The seed search doesn't work in this version: " + e).withStyle(ChatFormatting.RED));
            }
        }, "opendraft-seed-report");
        t.setDaemon(true);
        t.start();
        return 1;
    }

    /** Lobby chat lines are capped in length by the synchronizer. */
    private static void sayInLobby(Session s, String text) {
        s.send("CHAT " + (text.length() > 250 ? text.substring(0, 247) + "..." : text));
    }

    private static int goals(FabricClientCommandSource src) {
        MinecraftServer server = world(src);
        if (server == null) return 0;
        Map<String, String> missing = Goals.unavailable(server);
        int total = Goals.all().size();
        src.sendFeedback(Component.literal((total - missing.size()) + " of " + total + " goals can be dealt in this world.")
                .withStyle(ChatFormatting.GOLD));
        missing.forEach((id, why) -> src.sendFeedback(Component.literal(" - " + id + ": " + why).withStyle(ChatFormatting.GRAY)));
        return 1;
    }

    /** Lists block, item and entity ids containing a word, to find what new content is called. */
    private static int ids(FabricClientCommandSource src, String word) {
        String w = word.toLowerCase(java.util.Locale.ROOT);
        List<String> found = new java.util.ArrayList<>();
        net.minecraft.core.registries.BuiltInRegistries.BLOCK.keySet().forEach(k -> { if (k.getPath().contains(w)) found.add("block " + k); });
        net.minecraft.core.registries.BuiltInRegistries.ITEM.keySet().forEach(k -> { if (k.getPath().contains(w)) found.add("item " + k); });
        net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.keySet().forEach(k -> { if (k.getPath().contains(w)) found.add("entity " + k); });
        java.util.Collections.sort(found);
        src.sendFeedback(Component.literal(found.isEmpty() ? "No ids contain \"" + w + "\"." : String.join("\n", found)));
        return 1;
    }

    // ---- shared helpers ----------------------------------------------------------------------

    /** The board key as the player has it bound, for messages ("B" unless they changed it). */
    public static String boardKeyName() {
        KeyMapping k = BOARD_KEY;
        return k == null ? "B" : KeyMappingHelper.getBoundKeyOf(k).getDisplayName().getString();
    }

    /** Right-click zooms like a spyglass while you're held in the air (spectators can't use items). */
    public static boolean zooming() {
        Minecraft mc = Minecraft.getInstance();
        Session s = Session.current();
        if (s == null || mc.player == null || mc.gui.screen() != null || !mc.options.keyUse.isDown()) return false;
        return s.inLookout() || s.waitingForStart;
    }

    /** Gamma while fullbright is on: 1500%, past the Brightness slider's 100%. */
    public static final Double FULLBRIGHT_GAMMA = 15.0;
    /** Toggled with the fullbright key; read by the lightmap. Not saved, so it's off each time the game starts. */
    public static volatile boolean fullbright;

    /** The fullbright key, in a world with no screen open. Returns whether it toggled. */
    public static boolean toggleFullbright() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.screen() != null) return false;
        fullbright = !fullbright;
        mc.gui.hud.setOverlayMessage(Component.literal("Fullbright " + (fullbright ? "on" : "off")), false);
        return true;
    }

    /**
     * You are always cyan. Everyone else gets red, yellow, green... by when they joined, and keeps it, so a
     * player has the same colour on every other player's screen.
     */
    public static int colorOf(String name) {
        Session s = Session.current();
        if (s == null || name == null || name.equals(s.me)) return ME;
        Integer c = s.colors.get(name);
        return OTHERS[Math.floorMod(c != null ? c : name.hashCode(), OTHERS.length)];
    }

    /** A line above the hotbar that fades after a few seconds, for things that shouldn't clog chat. */
    public static void overlay(Component c) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.gui.hud.setOverlayMessage(c, false));
    }

    public static void chat(Component c) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.gui.hud.getChat().addClientSystemMessage(c));
    }
}
