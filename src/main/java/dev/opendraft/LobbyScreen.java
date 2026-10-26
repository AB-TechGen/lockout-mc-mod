package dev.opendraft;

import dev.opendraft.net.Invite;
import dev.opendraft.net.OpenDraftHost;
import dev.opendraft.net.Protocol;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The lobby, opened from the title screen. Before joining it offers to host a lobby (this game runs
 * the synchronizer) or join one with an invite code. Inside, it lists the players, shows the leader's
 * settings, has a little chat, and the leader's Start button sends everyone into a new world.
 */
public final class LobbyScreen extends Screen {
    private static final int PANEL = 0xA0101014;
    private static final int PANEL_W = 176;
    private static final int GOLD = 0xFFFFD54F;

    // Not connected
    private EditBox nameBox, portBox, addressBox;
    private Button createButton, joinButton;
    // Connected
    private EditBox chatBox;
    private Button copyButton, editButton, startButton, joinMatchButton, leaveButton;
    private Button backButton;

    public LobbyScreen() {
        super(Component.literal("OpenDraft Lobby"));
    }

    @Override
    protected void init() {
        Font font = getFont();
        int cx = width / 2;
        Minecraft mc = Minecraft.getInstance();

        nameBox = new EditBox(font, cx - 100, 50, 200, 18, Component.literal("Name"));
        nameBox.setMaxLength(16);
        nameBox.setValue(Settings.name.isEmpty() ? mc.getUser().getName() : Settings.name);
        addRenderableWidget(nameBox);
        portBox = new EditBox(font, cx - 100, 92, 50, 18, Component.literal("Port"));
        portBox.setMaxLength(5);
        portBox.setValue(String.valueOf(Settings.port));
        addRenderableWidget(portBox);
        createButton = addRenderableWidget(Button.builder(Component.literal("Create Lobby"), b -> create())
                .bounds(cx - 46, 91, 146, 20).build());
        addressBox = new EditBox(font, cx - 100, 134, 140, 18, Component.literal("Address"));
        addressBox.setMaxLength(128);
        addressBox.setValue(Settings.address);
        addRenderableWidget(addressBox);
        joinButton = addRenderableWidget(Button.builder(Component.literal("Join"), b -> join())
                .bounds(cx + 44, 133, 56, 20).build());

        int chatW = Math.max(120, Math.min(240, width - 230));
        chatBox = new EditBox(font, 8, height - 27, chatW, 18, Component.literal("Chat"));
        chatBox.setMaxLength(200);
        addRenderableWidget(chatBox);
        copyButton = addRenderableWidget(Button.builder(Component.literal("Copy"), b -> copyInvite())
                .bounds(8 + PANEL_W - 44, 10, 40, 16).build());
        editButton = addRenderableWidget(Button.builder(Component.literal("Edit Settings"), b ->
                        mc.gui.setScreen(LobbySettingsScreen.open(this)))
                .bounds(width - 8 - PANEL_W + 4, 104, PANEL_W - 8, 20).build());
        startButton = addRenderableWidget(Button.builder(Component.literal("Start"), b -> {
                    if (SeedSearch.running()) SeedSearch.cancel();
                    else OpenDraftClient.startLobbyMatch();
                })
                .bounds(width - 108, height - 28, 100, 20).build());
        joinMatchButton = addRenderableWidget(Button.builder(Component.literal("Join Match"), b -> joinMatch())
                .bounds(width - 108, height - 28, 100, 20).build());
        leaveButton = addRenderableWidget(Button.builder(Component.literal("Leave Lobby"), b -> {
                    SeedSearch.cancel();
                    Session.end();
                    Session.lastStatus = null;
                }).bounds(width - 212, height - 28, 100, 20).build());
        backButton = addRenderableWidget(Button.builder(Component.literal("Back"), b -> back())
                .bounds(cx - 50, height - 28, 100, 20).build());
        updateWidgets();
    }

    private static void back() {
        Minecraft mc = Minecraft.getInstance();
        mc.gui.setScreen(mc.level == null ? new TitleScreen() : null);
    }

    /** Which half of the screen is in use depends on whether we're in a lobby, so widgets come and go. */
    private void updateWidgets() {
        Session s = Session.current();
        boolean in = s != null;
        for (var w : new net.minecraft.client.gui.components.AbstractWidget[]{nameBox, portBox, createButton, addressBox, joinButton}) {
            w.visible = !in;
        }
        backButton.visible = !in;
        chatBox.visible = in;
        leaveButton.visible = in;
        copyButton.visible = in && s.host != null && s.host.reach() != null && s.host.reach().code() != null;
        editButton.visible = in && s.isLeader();
        boolean matchOn = in && s.matchOn();
        startButton.visible = in && s.isLeader() && !matchOn;
        startButton.active = in && s.welcomed;
        startButton.setMessage(Component.literal(SeedSearch.running() ? "Stop Search" : "Start"));
        joinMatchButton.visible = in && matchOn && Minecraft.getInstance().level == null && !startButton.visible;
    }

    @Override
    public void tick() {
        super.tick();
        updateWidgets();
    }

    private void create() {
        int port;
        try {
            port = Integer.parseInt(portBox.getValue().trim());
        } catch (NumberFormatException e) {
            Session.lastStatus = "The port must be a number.";
            return;
        }
        remember();
        Settings.port = port;
        OpenDraftHost host = new OpenDraftHost(port, OpenDraftClient.modVersion(), null, msg -> OpenDraftClient.LOG.info("[sync] {}", msg));
        try {
            host.open();
        } catch (Exception e) {
            Session.lastStatus = "Could not open port " + port + ": " + e.getMessage();
            return;
        }
        Session.connect(host, "127.0.0.1:" + port, nameBox.getValue(), "-");
        host.shareOnline(reach -> {
            Session s = Session.current();
            if (s != null && s.host == host && reach.code() != null) s.addChatLine("Press Copy to share the invite code.");
        });
    }

    private void join() {
        String address = addressBox.getValue().trim();
        if (address.isEmpty()) {
            Session.lastStatus = "Paste the invite code you were given.";
            return;
        }
        remember();
        Settings.address = address;
        Settings.save();
        Session.connect(null, address, nameBox.getValue(), "-");
    }

    private void remember() {
        Settings.name = Protocol.sanitizeName(nameBox.getValue());
        Settings.save();
    }

    private static void joinMatch() {
        Session s = Session.current();
        Long seed = s == null ? null : s.matchSeed;
        if (seed != null) WorldMaker.createMatchWorld(s.matchId, seed);
    }

    private void copyInvite() {
        Session s = Session.current();
        OpenDraftHost.Reach reach = s == null || s.host == null ? null : s.host.reach();
        if (reach == null || reach.code() == null) return;
        Minecraft.getInstance().keyboardHandler.setClipboard(reach.code());
        copyButton.setMessage(Component.literal("Copied"));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        boolean enter = event.key() == 257 || event.key() == 335;
        Session s = Session.current();
        if (enter && s != null && chatBox.isFocused()) {
            String text = chatBox.getValue().trim();
            if (!text.isEmpty()) s.send("CHAT " + text);
            chatBox.setValue("");
            return true;
        }
        if (enter && s == null && addressBox.isFocused()) {
            join();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        Font font = getFont();
        Session s = Session.current();
        if (s == null) {
            int cx = width / 2;
            centered(g, font, Component.literal("OpenDraft Lobby").withStyle(ChatFormatting.GOLD), 16, 0xFFFFFFFF);
            g.text(font, Component.literal("Your name"), cx - 100, 40, 0xFFAAAAAA, true);
            g.text(font, Component.literal("Host a lobby (others join you on this port)"), cx - 100, 82, 0xFFAAAAAA, true);
            g.text(font, Component.literal("Join a lobby (invite code or address)"), cx - 100, 124, 0xFFAAAAAA, true);
            String status = Session.lastStatus;
            if (status != null) wrapped(g, font, status, cx - 120, 162, 240, 0xFFEF5350);
            super.extractRenderState(g, mouseX, mouseY, delta);
            return;
        }

        // Players, top left.
        int x0 = 8, y0 = 8;
        List<String> players = s.players;
        int rows = Math.max(players.size(), 1);
        int top = s.host != null ? 42 : 32; // the host has a second line, about who can join
        g.fill(x0, y0, x0 + PANEL_W, y0 + top + 2 + rows * 14, PANEL);
        g.text(font, Component.literal("Lobby (" + players.size() + ")").withStyle(ChatFormatting.BOLD), x0 + 5, y0 + 5, 0xFFFFFFFF, true);
        OpenDraftHost.Reach reach = s.host != null ? s.host.reach() : null;
        String where;
        if (s.host != null) {
            where = reach == null ? "Getting your invite code..." : reach.code() == null ? "No invite code (no network found)" : "Code: " + reach.code();
            String who = reach == null ? "Opening your router..." : reach.anywhere() ? "Works from any network" : "Same network only";
            int whoColor = reach == null ? 0xFF888888 : reach.anywhere() ? 0xFF81C784 : 0xFFFFB74D;
            g.text(font, Component.literal(who), x0 + 5, y0 + 28, whoColor, true);
        } else if (!s.welcomed) {
            where = "Connecting...";
        } else {
            where = "Connected to " + (Invite.decode(s.address) != null ? "the lobby" : s.address);
        }
        g.text(font, Component.literal(where), x0 + 5, y0 + 18, s.host != null && reach != null ? 0xFFFFFFFF : 0xFFAAAAAA, true);
        int py = y0 + top;
        String leader = s.leader();
        for (String name : players) {
            int color = OpenDraftClient.colorOf(name);
            g.fill(x0 + 5, py + 1, x0 + 12, py + 8, color);
            Component label = Component.literal(name + (name.equals(s.me) ? " (you)" : ""));
            g.text(font, label, x0 + 16, py, 0xFFFFFFFF, true);
            if (name.equals(leader)) g.text(font, Component.literal("★ leader"), x0 + PANEL_W - 5 - font.width("★ leader"), py, GOLD, true);
            py += 14;
        }

        // Settings, top right.
        int sx = width - 8 - PANEL_W;
        List<String> lines = s.lobbySettings.isEmpty() ? List.of("Waiting for the leader...") : Settings.describe(s.lobbySettings);
        g.fill(sx, y0, sx + PANEL_W, y0 + 120, PANEL);
        g.text(font, Component.literal("Settings").withStyle(ChatFormatting.BOLD), sx + 5, y0 + 5, 0xFFFFFFFF, true);
        int ly = y0 + 22;
        for (String line : lines) {
            ly = wrapped(g, font, line, sx + 5, ly, PANEL_W - 10, 0xFFDDDDDD) + 3;
        }
        if (!s.isLeader() && leader != null) g.text(font, Component.literal(leader + " picks these."), sx + 5, y0 + 104, 0xFF888888, true);

        // What's happening, top middle.
        String state = matchState(s);
        if (state != null) wrapped(g, font, state, width / 2 - 100, y0 + 4, 200, GOLD);

        // Chat, bottom left, above the input.
        List<String> chat = s.chatLines;
        int cy = height - 32;
        for (int i = chat.size() - 1; i >= 0 && cy > y0 + top + 8 + rows * 14; i--) {
            cy -= 10;
            g.text(font, Component.literal(font.plainSubstrByWidth(chat.get(i), Math.max(120, Math.min(240, width - 230)))), 10, cy, 0xFFDDDDDD, true);
        }
        if (chatBox.getValue().isEmpty() && !chatBox.isFocused()) {
            g.text(font, Component.literal("Say something..."), 12, height - 22, 0xFF777777, false);
        }
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    private static String matchState(Session s) {
        String search = SeedSearch.status();
        if (search == null && s.isLeader()) search = SeedSearch.lastProblem;
        if (search != null && !s.matchOn()) return search;
        if (!s.matchOn()) {
            if (s.ended && s.winner != null) {
                return s.winner.equals("-") ? "Last match: a draw." : "Last match: " + (s.winner.equals(s.me) ? "you won!" : s.winner + " won.");
            }
            OpenDraftHost.Reach reach = s.host != null ? s.host.reach() : null;
            if (reach != null && !reach.anywhere()) return reach.note();
            return s.isLeader() ? "Press Start when everyone's here." : null;
        }
        Long pending = WorldMaker.pendingSeed();
        if (pending != null) return "Make a new world with seed " + pending + " to join (Join Match does it for you).";
        return "A match is running.";
    }

    private void centered(GuiGraphicsExtractor g, Font font, Component c, int y, int color) {
        g.text(font, c, (width - font.width(c)) / 2, y, color, true);
    }

    /** Draws text wrapped to a width; returns the y below it. */
    private static int wrapped(GuiGraphicsExtractor g, Font font, String text, int x, int y, int w, int color) {
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (font.width(next) > w && !line.isEmpty()) {
                g.text(font, Component.literal(line.toString()), x, y, color, true);
                y += 10;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (!line.isEmpty()) {
            g.text(font, Component.literal(line.toString()), x, y, color, true);
            y += 10;
        }
        return y;
    }

    /** The game keeps running behind the lobby when it's opened in a world. */
    public boolean isPauseScreen() {
        return false;
    }
}
