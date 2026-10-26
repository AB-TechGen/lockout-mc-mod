package dev.opendraft;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The lobby leader's settings, in two tabs: Match (board, size, time limit, overtime, lookout,
 * draft turns) and World (seed, and what a random seed must have near spawn). Edits go to a
 * working copy; Save writes them to the config and shows them to the lobby.
 */
public final class LobbySettingsScreen extends Screen {
    private static final int ROW = 26;

    // The working copy, kept while switching tabs.
    private static int size, minutes, overtime, lookout, turnSeconds, radius;
    private static String mode, seed;
    private static boolean nether;
    private static final Set<String> filter = new LinkedHashSet<>();

    private final Screen parent;
    private final boolean worldTab;
    private final List<Label> labels = new ArrayList<>();

    private record Label(int y, Supplier<String> text) {
    }

    private LobbySettingsScreen(Screen parent, boolean worldTab) {
        super(Component.literal("OpenDraft Settings"));
        this.parent = parent;
        this.worldTab = worldTab;
    }

    /** Starts editing from the saved settings. */
    static LobbySettingsScreen open(Screen parent) {
        size = Settings.size;
        minutes = Settings.minutes;
        overtime = Settings.overtime;
        lookout = Settings.lookout;
        turnSeconds = Settings.turnSeconds;
        mode = Settings.mode;
        seed = Settings.seed;
        radius = Settings.radius;
        nether = Settings.nether;
        filter.clear();
        for (SeedSearch.Target t : Settings.filterTargets()) filter.add(t.key());
        return new LobbySettingsScreen(parent, false);
    }

    @Override
    protected void init() {
        Minecraft mc = Minecraft.getInstance();
        int cx = width / 2;
        labels.clear(); // init runs again on resize
        Button match = addRenderableWidget(Button.builder(Component.literal("Match"), b -> mc.gui.setScreen(new LobbySettingsScreen(parent, false)))
                .bounds(cx - 152, 6, 150, 20).build());
        Button world = addRenderableWidget(Button.builder(Component.literal("World"), b -> mc.gui.setScreen(new LobbySettingsScreen(parent, true)))
                .bounds(cx + 2, 6, 150, 20).build());
        match.active = worldTab;
        world.active = !worldTab;

        int y = 40;
        if (!worldTab) {
            Button random = Button.builder(Component.literal(""), b -> mode = "random").bounds(cx - 102, y, 100, 20).build();
            Button drafted = Button.builder(Component.literal(""), b -> mode = "draft").bounds(cx + 2, y, 100, 20).build();
            addRenderableWidget(random);
            addRenderableWidget(drafted);
            boards = new Button[]{random, drafted};
            refreshBoardButtons();
            y += ROW + 4;
            stepper(y, () -> "Size: " + Settings.boardSize(size), () -> size, v -> size = clamp(v, 3, 25), 1);
            y += ROW;
            stepper(y, () -> "Time limit: " + (minutes == 0 ? "none" : minutes + " min"), () -> minutes, v -> minutes = clamp(v, 0, 600), 5);
            y += ROW;
            stepper(y, () -> "Overtime: " + (overtime == 0 ? "off" : overtime + " min"), () -> overtime, v -> overtime = clamp(v, 0, 120), 1);
            y += ROW;
            stepper(y, () -> "Lookout: " + lookout + "s", () -> lookout, v -> lookout = clamp(v, 0, 120), 5);
            y += ROW;
            stepper(y, () -> "Draft turn: " + turnSeconds + "s", () -> turnSeconds, v -> turnSeconds = clamp(v, 5, 120), 5);
        } else {
            EditBox seedBox = new EditBox(getFont(), cx - 100, y + 14, 200, 18, Component.literal("Seed"));
            seedBox.setMaxLength(64);
            seedBox.setValue(seed);
            seedBox.setResponder(v -> seed = v.trim());
            addRenderableWidget(seedBox);

            y = NEAR_Y;
            stepper(y, () -> "Within " + radius + " blocks of spawn", () -> radius, v -> radius = clamp(v, 50, 2000), 50, 10);
            toggles.clear();
            int col = 0, tx0 = cx - (3 * TOGGLE_W + 8) / 2;
            y += ROW + 2;
            for (SeedSearch.Target t : SeedSearch.TARGETS) {
                Button b = Button.builder(Component.literal(t.label()), btn -> {
                            if (!filter.remove(t.key())) filter.add(t.key());
                        })
                        .bounds(tx0 + col * (TOGGLE_W + 4), y, TOGGLE_W, 20).build();
                addRenderableWidget(b);
                toggles.put(t.key(), b);
                if (++col == 3) {
                    col = 0;
                    y += 22;
                }
            }
            netherToggle = addRenderableWidget(Button.builder(Component.literal(""), b -> nether = !nether)
                    .bounds(tx0 + col * (TOGGLE_W + 4), y, TOGGLE_W, 20).build());
            netherToggle.setTooltip(Tooltip.create(Component.literal("A bastion and a fortress within " + SeedSearch.NETHER_RADIUS
                    + " blocks of 0, 0 in the Nether, with nether wart in one of them (a housing bastion or the fortress's"
                    + " wart room). The distance above doesn't change this.")));
            refreshToggles();
        }

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> mc.gui.setScreen(parent))
                .bounds(cx - 102, height - 28, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Save Settings"), b -> save())
                .bounds(cx + 2, height - 28, 100, 20).build());
    }

    private Button[] boards;
    private static final int NEAR_Y = 106, TOGGLE_W = 102;
    private final Map<String, Button> toggles = new LinkedHashMap<>();
    private Button netherToggle;

    private void refreshToggles() {
        toggles.forEach((key, b) -> b.setMessage(toggleLabel(SeedSearch.target(key).label(), filter.contains(key))));
        if (netherToggle != null) netherToggle.setMessage(toggleLabel("Nether", nether));
    }

    private static Component toggleLabel(String label, boolean on) {
        return on ? Component.literal("✔ " + label).withStyle(ChatFormatting.GREEN) : Component.literal(label).withStyle(ChatFormatting.GRAY);
    }

    private void refreshBoardButtons() {
        if (boards == null) return;
        boards[0].setMessage(Component.literal(mode.equals("random") ? "> Random <" : "Random"));
        boards[1].setMessage(Component.literal(mode.equals("draft") ? "> Drafted <" : "Drafted"));
    }

    @Override
    public void tick() {
        super.tick();
        refreshBoardButtons();
        refreshToggles();
    }

    /** A value with - and + either side. Shift-click steps by 1 where the normal step is bigger. */
    private void stepper(int y, Supplier<String> text, IntSupplier get, java.util.function.IntConsumer set, int step) {
        stepper(y, text, get, set, step, 1);
    }

    /** Shift-click steps by fine instead of step. */
    private void stepper(int y, Supplier<String> text, IntSupplier get, java.util.function.IntConsumer set, int step, int fine) {
        int cx = width / 2;
        addRenderableWidget(Button.builder(Component.literal("-"), b -> set.accept(get.getAsInt() - (hasShiftDown() ? fine : step)))
                .bounds(cx - 102, y, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> set.accept(get.getAsInt() + (hasShiftDown() ? fine : step)))
                .bounds(cx + 82, y, 20, 20).build());
        labels.add(new Label(y, text));
    }

    private static boolean hasShiftDown() {
        try {
            return (boolean) Screen.class.getMethod("hasShiftDown").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private void save() {
        Settings.size = size;
        Settings.minutes = minutes;
        Settings.overtime = overtime;
        Settings.lookout = lookout;
        Settings.turnSeconds = turnSeconds;
        Settings.mode = mode;
        Settings.seed = seed;
        Settings.radius = radius;
        Settings.nether = nether;
        Settings.filter = Settings.cleanFilter(String.join(",", filter));
        Settings.save();
        Session s = Session.current();
        if (s != null && s.isLeader()) s.send("SETTINGS " + Settings.encode());
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        Font font = getFont();
        int cx = width / 2;
        for (Label l : labels) {
            g.fill(cx - 80, l.y(), cx + 80, l.y() + 20, 0xA0000000);
            Component c = Component.literal(l.text().get());
            g.text(font, c, cx - font.width(c) / 2, l.y() + 6, 0xFFFFFFFF, true);
        }
        if (!worldTab) {
            centered(g, font, Component.literal("Board").withStyle(ChatFormatting.GRAY), 30);
            String hint = minutes == 0 ? "With no time limit the game ends when the leader can't be caught."
                    : overtime == 0 ? "A tie when time runs out is a draw." : "A tie when time runs out goes to overtime: whoever pulls ahead first wins.";
            centered(g, font, Component.literal(hint).withStyle(ChatFormatting.GRAY), 40 + ROW * 6 + 8);
        } else {
            centered(g, font, Component.literal("World seed").withStyle(ChatFormatting.GRAY), 40);
            centered(g, font, Component.literal("Leave blank for a random seed. Everyone gets a new world with it.")
                    .withStyle(ChatFormatting.GRAY), 40 + 40);
            String near = !seed.isEmpty() ? "A typed seed is used as it is, so these are ignored."
                    : filter.isEmpty() && !nether ? "Pick what a random seed must have near spawn."
                    : "Start searches for a seed with these for up to a minute.";
            centered(g, font, Component.literal(near).withStyle(ChatFormatting.GRAY), NEAR_Y - 12);
        }
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    private void centered(GuiGraphicsExtractor g, Font font, Component c, int y) {
        g.text(font, c, (width - font.width(c)) / 2, y, 0xFFFFFFFF, true);
    }

    public boolean isPauseScreen() {
        return false;
    }
}
