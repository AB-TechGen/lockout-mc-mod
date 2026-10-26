package dev.opendraft;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.opendraft.goal.Goal;
import dev.opendraft.goal.Goals;
import dev.opendraft.net.OpenDraftHost;
import dev.opendraft.net.Protocol;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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
    /** GLFW's key code for B (LWJGL isn't on the compile classpath). */
    private static final int KEY_B = 66;

    public static String modVersion() {
        return FabricLoader.getInstance().getModContainer("opendraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
    }

    @Override
    public void onInitializeClient() {
        Goals.load();
        Settings.load();
        ServerHooks.register();
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("opendraft", "board"), BoardHud::extractRenderState);
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("opendraft", "opendraft"));
        BOARD_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.opendraft.board", KEY_B, category));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (BOARD_KEY.consumeClick()) {
                if (mc.player == null || mc.gui.screen() != null) continue;
                if (Session.current() == null) {
                    chat(Component.literal("Not in an OpenDraft game. Use the OpenDraft button on the title screen, or /opendraft join.")
                            .withStyle(ChatFormatting.GRAY));
                } else {
                    mc.gui.setScreen(new BoardScreen());
                }
            }
            WorldMaker.tick();
        });
        // The lobby button on the title screen, and filling in Create World for lobby matches.
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> WorldMaker.afterInit(screen));
        // Leaving a world keeps you in the lobby; the title screen takes you back to it.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (Session.current() != null) WorldMaker.returnToLobby = true;
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                literal("opendraft")
                        .executes(ctx -> help(ctx.getSource()))
                        .then(literal("host")
                                .executes(ctx -> host(ctx.getSource(), Protocol.DEFAULT_PORT))
                                .then(argument("port", IntegerArgumentType.integer(1, 65535))
                                        .executes(ctx -> host(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "port")))))
                        .then(literal("join")
                                .then(argument("address_and_name", StringArgumentType.greedyString())
                                        .executes(OpenDraftClient::join)))
                        .then(literal("start")
                                .executes(ctx -> start(ctx.getSource(), Settings.size, Settings.minutes, Settings.overtime, Settings.mode.equals("draft")))
                                .then(argument("size", IntegerArgumentType.integer(Protocol.MIN_BOARD, Protocol.MAX_BOARD))
                                        .executes(ctx -> start(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "size"),
                                                Settings.minutes, Settings.overtime, Settings.mode.equals("draft")))
                                        .then(argument("minutes", IntegerArgumentType.integer(0, 600))
                                                .executes(ctx -> start(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "size"),
                                                        IntegerArgumentType.getInteger(ctx, "minutes"), Settings.overtime, Settings.mode.equals("draft")))
                                                .then(argument("overtime", IntegerArgumentType.integer(0, 120))
                                                        .executes(ctx -> start(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "size"),
                                                                IntegerArgumentType.getInteger(ctx, "minutes"), IntegerArgumentType.getInteger(ctx, "overtime"),
                                                                Settings.mode.equals("draft")))))))
                        .then(literal("draft")
                                .executes(ctx -> start(ctx.getSource(), Settings.size, Settings.minutes, Settings.overtime, true))
                                .then(argument("size", IntegerArgumentType.integer(Protocol.MIN_BOARD, Protocol.MAX_BOARD))
                                        .executes(ctx -> start(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "size"),
                                                Settings.minutes, Settings.overtime, true))))
                        .then(literal("forfeit").executes(ctx -> vote(ctx.getSource(), "FORFEIT")))
                        .then(literal("draw").executes(ctx -> vote(ctx.getSource(), "DRAW"))
                                .then(literal("decline").executes(ctx -> vote(ctx.getSource(), "NODRAW"))))
                        .then(literal("pick")
                                .then(argument("number", IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> pick(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "number")))))
                        .then(settingsCommand())
                        .then(literal("board").executes(ctx -> board(ctx.getSource())))
                        .then(literal("goals").executes(ctx -> goals(ctx.getSource())))
                        .then(literal("seed")
                                .executes(ctx -> seedReport(ctx.getSource(), 1000))
                                .then(argument("blocks", IntegerArgumentType.integer(50, 5000))
                                        .executes(ctx -> seedReport(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "blocks")))))
                        .then(literal("ids")
                                .then(argument("word", StringArgumentType.word())
                                        .executes(ctx -> ids(ctx.getSource(), StringArgumentType.getString(ctx, "word")))))
                        .then(literal("hud").executes(ctx -> {
                            BoardHud.visible = !BoardHud.visible;
                            ctx.getSource().sendFeedback(Component.literal("OpenDraft board " + (BoardHud.visible ? "shown." : "hidden.")));
                            return 1;
                        }))
                        .then(literal("leave").executes(ctx -> {
                            Session.end();
                            ctx.getSource().sendFeedback(Component.literal("Left the OpenDraft game."));
                            return 1;
                        }))));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> settingsCommand() {
        return literal("settings")
                .executes(ctx -> showSettings(ctx.getSource()))
                .then(literal("size").then(argument("goals", IntegerArgumentType.integer(Protocol.MIN_BOARD, Protocol.MAX_BOARD))
                        .executes(ctx -> setting(ctx.getSource(), () -> Settings.size = IntegerArgumentType.getInteger(ctx, "goals")))))
                .then(literal("time").then(argument("minutes", IntegerArgumentType.integer(0, 600))
                        .executes(ctx -> setting(ctx.getSource(), () -> Settings.minutes = IntegerArgumentType.getInteger(ctx, "minutes")))))
                .then(literal("overtime").then(argument("minutes", IntegerArgumentType.integer(0, 120))
                        .executes(ctx -> setting(ctx.getSource(), () -> Settings.overtime = IntegerArgumentType.getInteger(ctx, "minutes")))))
                .then(literal("lookout").then(argument("seconds", IntegerArgumentType.integer(0, 120))
                        .executes(ctx -> setting(ctx.getSource(), () -> Settings.lookout = IntegerArgumentType.getInteger(ctx, "seconds")))))
                .then(literal("turn").then(argument("seconds", IntegerArgumentType.integer(5, 120))
                        .executes(ctx -> setting(ctx.getSource(), () -> Settings.turnSeconds = IntegerArgumentType.getInteger(ctx, "seconds")))))
                .then(literal("mode")
                        .then(literal("random").executes(ctx -> setting(ctx.getSource(), () -> Settings.mode = "random")))
                        .then(literal("draft").executes(ctx -> setting(ctx.getSource(), () -> Settings.mode = "draft"))));
    }

    // ---- commands -------------------------------------------------------------------------

    private static int help(FabricClientCommandSource src) {
        src.sendFeedback(Component.literal(String.join("\n",
                "OpenDraft " + modVersion(),
                "The OpenDraft button on the title screen opens the lobby. In a world you can also use:",
                "/opendraft host [port] - run the synchronizer in this game and join it",
                "/opendraft join <invite code or address> [name] - join a synchronizer",
                "/opendraft start [size] [minutes] [overtime] - deal a new board (uses /opendraft settings when left out)",
                "/opendraft draft [size] - pick the board's goals in turns",
                "/opendraft pick <n> - pick goal n in a draft (or click it on the board)",
                "/opendraft settings - board size, time limit, overtime, lookout, mode",
                "/opendraft board - list this board's goals (press " + boardKeyName() + " for the big board)",
                "/opendraft forfeit, /opendraft draw [decline] - give up, or vote for a draw",
                "/opendraft goals - which goals this version supports",
                "/opendraft ids <word> - list block, item and entity ids containing a word",
                "/opendraft seed [blocks] - where the seed filter thinks spawn and each structure are in this world",
                "/opendraft hud - show or hide the board",
                "/opendraft leave")));
        return 1;
    }

    private static MinecraftServer world(FabricClientCommandSource src) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            src.sendError(Component.literal("Open your singleplayer world first. Everyone plays their own world on the same seed."));
        }
        return server;
    }

    private static int host(FabricClientCommandSource src, int port) {
        MinecraftServer server = world(src);
        if (server == null) return 0;
        Session.end(); // frees the port if this game was already hosting
        long seed = server.overworld().getSeed();
        OpenDraftHost host = new OpenDraftHost(port, modVersion(), seed, msg -> LOG.info("[sync] {}", msg));
        try {
            host.open();
        } catch (Exception e) {
            src.sendError(Component.literal("Could not open port " + port + ": " + e.getMessage()));
            return 0;
        }
        src.sendFeedback(Component.literal("Synchronizer running on port " + port + " with seed " + seed
                + ". Getting your invite code...").withStyle(ChatFormatting.GREEN));
        connect(src, host, "127.0.0.1:" + port, null);
        host.shareOnline(reach -> chat(Component.literal(reach.code() == null ? "OpenDraft: no invite code, since this computer isn't on a network."
                : "OpenDraft: others join with /opendraft join " + reach.code() + ". " + reach.note())
                .withStyle(reach.anywhere() ? ChatFormatting.GREEN : ChatFormatting.GOLD)));
        return 1;
    }

    private static int join(CommandContext<FabricClientCommandSource> ctx) {
        if (world(ctx.getSource()) == null) return 0;
        String[] parts = StringArgumentType.getString(ctx, "address_and_name").trim().split("\\s+");
        connect(ctx.getSource(), null, parts[0], parts.length > 1 ? parts[1] : null);
        return 1;
    }

    /** Joins from inside a world, which has to have the game's seed. */
    private static void connect(FabricClientCommandSource src, OpenDraftHost host, String address, String name) {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return;
        // No accounts involved: the display name is the only identity, so dev clients work too.
        String display = name != null ? name : !Settings.name.isEmpty() ? Settings.name : mc.getUser().getName();
        Session.connect(host, address, display, String.valueOf(server.overworld().getSeed()));
        src.sendFeedback(Component.literal("Connecting to " + address + "...").withStyle(ChatFormatting.GRAY));
    }

    private static int start(FabricClientCommandSource src, int size, int minutes, int overtime, boolean draft) {
        Session s = Session.current();
        if (s == null) {
            src.sendError(Component.literal("Join a game first: the OpenDraft button on the title screen, or /opendraft host or /opendraft join."));
            return 0;
        }
        if (world(src) == null) return 0;
        String error = deal(s, size, minutes, overtime, draft);
        if (error != null) {
            src.sendError(Component.literal(error));
            return 0;
        }
        return 1;
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

    /** The lobby's Start button: shares the settings and sends everyone into a new world. */
    static void startLobbyMatch() {
        Session s = Session.current();
        if (s == null || !s.isLeader() || SeedSearch.running()) return;
        s.send("SETTINGS " + Settings.encode());
        List<SeedSearch.Target> near = Settings.filterTargets();
        if (!Settings.seed.isEmpty() || near.isEmpty()) {
            SeedSearch.lastProblem = null;
            s.send("MATCH " + Settings.matchSeed());
            return;
        }
        int radius = Settings.radius;
        sayInLobby(s, "Looking for a seed with " + SeedSearch.labels(near) + " within " + radius + " blocks of spawn...");
        SeedSearch.start(near, radius, found -> {
            // Only start if nothing changed while searching.
            if (Session.current() != s || !s.isLeader() || s.matchOn()) return;
            sayInLobby(s, found.summary());
            s.send("MATCH " + found.seed());
        }, why -> {
            if (Session.current() != s) return;
            sayInLobby(s, "Couldn't find a seed with all of that near spawn.");
        });
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

    private static int vote(FabricClientCommandSource src, String line) {
        Session s = Session.current();
        if (s == null || s.goals == null || s.ended) {
            src.sendError(Component.literal("No game is running."));
            return 0;
        }
        s.send(line);
        return 1;
    }

    private static int pick(FabricClientCommandSource src, int number) {
        Session s = Session.current();
        if (s == null || !s.drafting()) {
            src.sendError(Component.literal("No draft is running."));
            return 0;
        }
        if (!s.myTurn()) {
            src.sendError(Component.literal("It's " + s.turnPlayer + "'s turn."));
            return 0;
        }
        int idx = number - 1;
        String[] picks = s.draftPicks;
        if (idx >= picks.length || picks[idx] != null) {
            src.sendError(Component.literal("That goal isn't available."));
            return 0;
        }
        s.send("PICK " + s.draftId + " " + idx);
        return 1;
    }

    private static int showSettings(FabricClientCommandSource src) {
        src.sendFeedback(Component.literal("OpenDraft settings").withStyle(ChatFormatting.GOLD));
        for (String line : Settings.describe()) src.sendFeedback(Component.literal("  " + line));
        src.sendFeedback(Component.literal("Change them in the lobby (Edit Settings), or with /opendraft settings followed by "
                + "size <3-25>, time <minutes>, overtime <minutes>, lookout <seconds>, turn <seconds> or mode random|draft. "
                + "Time 0 means no limit.").withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static int setting(FabricClientCommandSource src, Runnable change) {
        change.run();
        Settings.save();
        Session s = Session.current();
        if (s != null && s.isLeader()) s.send("SETTINGS " + Settings.encode());
        src.sendFeedback(Component.literal("Saved. " + String.join(" | ", Settings.describe())).withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static int board(FabricClientCommandSource src) {
        Session s = Session.current();
        boolean draft = s != null && s.drafting();
        List<Goal> goals = s == null ? null : draft ? s.draftPool : s.goals;
        if (goals == null) {
            src.sendError(Component.literal("No board yet."));
            return 0;
        }
        MutableComponent out = Component.literal(draft ? "Draft pool (/opendraft pick <n> on your turn):" : "OpenDraft board:")
                .withStyle(ChatFormatting.GOLD);
        String[] owners = draft ? s.draftPicks : s.owners;
        for (int i = 0; i < goals.size(); i++) {
            Goal g = goals.get(i);
            MutableComponent line = Component.literal("\n" + (i + 1) + ". " + (g == null ? "?" : g.name));
            String owner = i < owners.length ? owners[i] : null;
            if (owner != null) {
                line.withStyle(ChatFormatting.STRIKETHROUGH).withColor(colorOf(owner) & 0xFFFFFF);
                line.append(Component.literal(" (" + (owner.equals(s.me) ? "you" : owner) + ")")
                        .withStyle(s2 -> s2.withStrikethrough(false)));
            }
            out.append(line);
        }
        src.sendFeedback(out);
        return 1;
    }

    private static int goals(FabricClientCommandSource src) {
        MinecraftServer server = world(src);
        if (server == null) return 0;
        Map<String, String> missing = Goals.unavailable(server);
        int total = Goals.all().size();
        src.sendFeedback(Component.literal((total - missing.size()) + " of " + total + " goals can be dealt in this version.")
                .withStyle(ChatFormatting.GOLD));
        missing.forEach((id, why) -> src.sendFeedback(Component.literal(" - " + id + ": " + why).withStyle(ChatFormatting.GRAY)));
        return 1;
    }

    /** Lists block, item and entity ids containing a word, to find what new content is called. */
    private static int ids(FabricClientCommandSource src, String word) {
        String w = word.toLowerCase(java.util.Locale.ROOT);
        java.util.List<String> found = new java.util.ArrayList<>();
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

    /** You are always cyan; everyone else gets red, yellow, green... in join order. */
    public static int colorOf(String name) {
        Session s = Session.current();
        if (s == null || name == null || name.equals(s.me)) return ME;
        int i = 0;
        for (String p : s.players) {
            if (p.equals(s.me)) continue;
            if (p.equals(name)) return OTHERS[i % OTHERS.length];
            i++;
        }
        // Someone who left: a stable colour from their name.
        return OTHERS[Math.floorMod(name.hashCode(), OTHERS.length)];
    }

    public static void chat(Component c) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.gui.hud.getChat().addClientSystemMessage(c));
    }
}
