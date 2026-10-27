package dev.opendraft;

import dev.opendraft.goal.Goal;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The board as a small panel in the middle of the screen. Opened with the board key (or automatically on your draft turn). Hovering a goal
 * shows its name, who has it and how far along you are; during a draft, clicking a free goal on
 * your turn picks it. Forfeit and draw votes live here, and "Back to Lobby" once the game is over.
 * The game keeps running behind it, since everyone's clock is the synchronizer's.
 */
public final class BoardScreen extends Screen {
    /** A cell at 100% size; 36 shows item icons at exactly 2x, so they stay sharp. The size slider scales it. */
    private static final int BASE_CELL = 36;
    private static final int GAP = 2;
    /** Space between the panel's edge and what's in it. */
    private static final int PAD = 8;
    private static final int BUTTON_W = 96, SLIDER_W = 160;
    /** Height kept free for the header, the score lines, the button row and the size slider under it. */
    private static final int RESERVED_H = 144;
    private static final int PANEL = 0xD0101014;
    private static final int PANEL_EDGE = 0xFF3A3A44;

    /** The cell under the mouse in the last frame, or -1. */
    private int hovered = -1;
    private Button forfeitButton, drawButton, lobbyButton, closeButton;
    private SizeSlider sizeSlider;
    /** The board size when the screen opened, so it's only saved if the slider changed it. */
    private int scaleWhenOpened;
    /** Forfeit asks once more before it counts; this is when the first press happened. */
    private long forfeitArmedAt;

    public BoardScreen() {
        super(Component.literal("OpenDraft"));
    }

    @Override
    protected void init() {
        // Where they go is worked out by placeButtons(), since it depends on how tall the board is.
        forfeitButton = addRenderableWidget(Button.builder(Component.literal("Forfeit"), b -> forfeit())
                .bounds(0, 0, BUTTON_W, 20).build());
        drawButton = addRenderableWidget(Button.builder(Component.literal("Offer Draw"), b -> draw())
                .bounds(0, 0, BUTTON_W, 20).build());
        lobbyButton = addRenderableWidget(Button.builder(Component.literal("Back to Lobby"), b -> WorldMaker.leaveToLobby())
                .bounds(0, 0, BUTTON_W, 20).build());
        closeButton = addRenderableWidget(Button.builder(Component.literal("Close Board"), b -> close())
                .bounds(0, 0, BUTTON_W, 20).build());
        scaleWhenOpened = Settings.boardScale;
        sizeSlider = addRenderableWidget(new SizeSlider());
        tick();
    }

    /** Saves the board size if the slider changed it, once the board closes rather than on every step. */
    @Override
    public void removed() {
        super.removed();
        if (Settings.boardScale != scaleWhenOpened) Settings.save();
    }

    /**
     * The board's size, 50% to 300% in steps of 10. When the board can't get that big on this screen it's as big as
     * fits, and the label says so.
     */
    private final class SizeSlider extends AbstractSliderButton {
        private static final int STEP = 10;

        SizeSlider() {
            super(0, 0, SLIDER_W, 20, Component.empty(), toValue(Settings.boardScale));
            updateMessage();
        }

        private static double toValue(int scale) {
            return (scale - Settings.MIN_BOARD_SCALE) / (double) (Settings.MAX_BOARD_SCALE - Settings.MIN_BOARD_SCALE);
        }

        @Override
        protected void applyValue() {
            int steps = (int) Math.round(value * (Settings.MAX_BOARD_SCALE - Settings.MIN_BOARD_SCALE) / STEP);
            Settings.boardScale = Settings.MIN_BOARD_SCALE + steps * STEP;
        }

        @Override
        protected void updateMessage() {
            Layout l = layout();
            setMessage(Component.literal("Board size: " + (l.capped() ? l.cell() * 100 / BASE_CELL + "% (max)" : Settings.boardScale + "%")));
        }

        /** The label, again: whether the size fits can change without the slider moving (a resize, a draft ending). */
        void refresh() {
            updateMessage();
        }
    }

    @Override
    public void tick() {
        super.tick();
        Session s = Session.current();
        boolean playing = s != null && s.goals != null && !s.ended && !s.iForfeited();
        forfeitButton.visible = playing;
        drawButton.visible = playing;
        lobbyButton.visible = s != null && s.ended && s.matchSeed != null;
        if (System.currentTimeMillis() - forfeitArmedAt > 4000) forfeitArmedAt = 0;
        forfeitButton.setMessage(Component.literal(forfeitArmedAt > 0 ? "Really forfeit?" : "Forfeit")
                .withStyle(forfeitArmedAt > 0 ? ChatFormatting.RED : ChatFormatting.RESET));
        if (s != null) {
            List<String> votes = s.drawVotes;
            int voters = 0;
            for (String p : s.players) if (!s.forfeited.contains(p) && !s.away.contains(p)) voters++;
            String label = votes.contains(s.me) ? "Cancel Draw (" + votes.size() + "/" + voters + ")"
                    : votes.isEmpty() ? "Offer Draw" : "Accept Draw (" + votes.size() + "/" + voters + ")";
            drawButton.setMessage(Component.literal(label));
        }
        placeButtons();
    }

    /** The visible buttons in one centred row just under the panel, and the size slider centred under them. */
    private void placeButtons() {
        List<Button> row = new ArrayList<>();
        if (forfeitButton.visible) row.add(forfeitButton);
        if (drawButton.visible) row.add(drawButton);
        if (lobbyButton.visible) row.add(lobbyButton);
        row.add(closeButton);
        int x = (width - (row.size() * BUTTON_W + (row.size() - 1) * 4)) / 2;
        int y = layout().buttonsY();
        for (Button b : row) {
            b.setX(x);
            b.setY(y);
            x += BUTTON_W + 4;
        }
        sizeSlider.setX((width - SLIDER_W) / 2);
        sizeSlider.setY(y + 24);
        sizeSlider.refresh();
    }

    /**
     * Where everything goes: the panel, the grid inside it, the lines under the grid and the button row. capped is
     * true when the board is smaller than the chosen size, because that wouldn't fit on the screen.
     */
    private record Layout(int px, int py, int pw, int ph, int gx, int gy, int cell, int columns, int gridH,
                          List<Component> footer, int buttonsY, boolean capped) {
    }

    private Layout layout() {
        Font font = getFont();
        Session s = Session.current();
        boolean draft = s != null && s.drafting();
        List<Goal> goals = s == null ? null : draft ? s.draftPool : s.goals;
        int n = goals == null ? 0 : goals.size();
        int columns = BoardHud.columns(Math.max(n, 1));
        int rows = Math.max(1, (n + columns - 1) / columns);
        // The chosen size, or as big as fits if that's smaller, leaving room for the header, the scores, the buttons
        // and the slider.
        int fits = Math.min((width - 2 * PAD - 20) / columns, (height - RESERVED_H) / rows) - GAP;
        int wanted = Math.round(BASE_CELL * Settings.boardScale / 100f);
        int cell = Math.max(8, Math.min(wanted, fits));
        int gridW = columns * cell + (columns - 1) * GAP;
        int gridH = rows * cell + (rows - 1) * GAP;
        // As wide as the grid, or as the header's text if that's wider, so the panel shrinks with the board.
        int textW = 0;
        if (s != null && goals != null) {
            textW = Math.max(font.width(draft ? "Board draft" : "OpenDraft"),
                    font.width(draft ? draftStatus(s) : Component.literal(BoardHud.clock(s))));
        }
        int pw = Math.max(gridW, textW) + 2 * PAD;

        List<Component> footer = new ArrayList<>();
        if (s != null && goals != null) {
            if (draft) {
                int picked = 0;
                for (String o : s.draftPicks) if (o != null) picked++;
                footer.add(Component.literal(picked + " picked").withStyle(ChatFormatting.GRAY));
            } else {
                footer.addAll(BoardHud.scoreLines(s, font, pw - 2 * PAD));
                String result = BoardHud.result(s);
                if (result != null) {
                    footer.add(Component.literal(result).withColor(s.winner.equals("-") ? 0xFFFFFF
                            : OpenDraftClient.colorOf(s.winner) & 0xFFFFFF));
                }
            }
        }
        for (Component line : footer) pw = Math.max(pw, font.width(line) + 2 * PAD);
        int headerH = 26;
        int ph = PAD + headerH + gridH + (footer.isEmpty() ? 0 : 6 + footer.size() * 11) + PAD;
        int py = Math.max(4, (height - (ph + 4 + 20 + 4 + 20)) / 2);
        return new Layout((width - pw) / 2, py, pw, ph, (width - gridW) / 2, py + PAD + headerH, cell, columns, gridH,
                footer, py + ph + 4, wanted > cell);
    }

    private void forfeit() {
        Session s = Session.current();
        if (s == null) return;
        if (forfeitArmedAt == 0) {
            forfeitArmedAt = System.currentTimeMillis();
            return;
        }
        forfeitArmedAt = 0;
        s.send("FORFEIT");
    }

    private static void draw() {
        Session s = Session.current();
        if (s == null) return;
        s.send(s.drawVotes.contains(s.me) ? "NODRAW" : "DRAW");
    }

    private static void close() {
        Minecraft.getInstance().gui.setScreen(null);
    }

    /** Lets the game keep running underneath; the method's name has moved between versions, hence no @Override. */
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (OpenDraftClient.BOARD_KEY != null && event.key() == KeyMappingHelper.getBoundKeyOf(OpenDraftClient.BOARD_KEY).getValue()) {
            close();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        Session s = Session.current();
        if (s == null || !s.myTurn() || hovered < 0) return false;
        String[] picks = s.draftPicks;
        if (hovered < picks.length && picks[hovered] == null) {
            s.send("PICK " + s.draftId + " " + hovered);
            return true;
        }
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        placeButtons(); // the board can change size between ticks (a draft finishing, a score line wrapping)
        super.extractRenderState(g, mouseX, mouseY, delta);
        Font font = getFont();
        hovered = -1;
        Session s = Session.current();
        if (s == null) {
            centered(g, font, Component.literal("Not in an OpenDraft game. Use the OpenDraft button on the title screen."), height / 2, 0xFFFFFFFF);
            return;
        }
        boolean draft = s.drafting();
        List<Goal> goals = draft ? s.draftPool : s.goals;
        if (goals == null) {
            centered(g, font, Component.literal("OpenDraft lobby").withStyle(ChatFormatting.GOLD), 14, 0xFFFFFFFF);
            centered(g, font, Component.literal(s.waitingForStart ? "Waiting for everyone's world to load..."
                    : "No board yet. Start a match from the lobby."), height / 2 - 10, 0xFFFFFFFF);
            centered(g, font, Component.literal("Players: " + String.join(", ", s.players)), height / 2 + 4, 0xFFAAAAAA);
            return;
        }
        String[] owners = draft ? s.draftPicks : s.owners;
        Layout l = layout();

        // The panel, with a thin edge.
        g.fill(l.px() - 1, l.py() - 1, l.px() + l.pw() + 1, l.py() + l.ph() + 1, PANEL_EDGE);
        g.fill(l.px(), l.py(), l.px() + l.pw(), l.py() + l.ph(), PANEL);

        // Header: what's happening and the clock.
        centered(g, font, Component.literal(draft ? "Board draft" : "OpenDraft").withStyle(ChatFormatting.GOLD), l.py() + PAD, 0xFFFFFFFF);
        Component status = draft ? draftStatus(s) : Component.literal(BoardHud.clock(s));
        centered(g, font, status, l.py() + PAD + 11, draft ? 0xFFFFFFFF : BoardHud.clockColor(s));

        // The grid.
        int cell = l.cell();
        float scale = (cell - 4) / 16f;
        Matrix3x2fStack pose = g.pose();
        for (int i = 0; i < goals.size(); i++) {
            int cx = l.gx() + (i % l.columns()) * (cell + GAP);
            int cy = l.gy() + (i / l.columns()) * (cell + GAP);
            String owner = i < owners.length ? owners[i] : null;
            boolean over = mouseX >= cx && mouseX < cx + cell && mouseY >= cy && mouseY < cy + cell;
            if (over) hovered = i;
            int bg = owner == null ? BoardHud.UNCLAIMED : OpenDraftClient.colorOf(owner);
            if (draft && owner != null) bg = dim(bg);
            if (over) {
                // A white frame round the cell, drawn as edges since unclaimed cells are see-through.
                g.fill(cx - 1, cy - 1, cx + cell + 1, cy, 0xFFFFFFFF);
                g.fill(cx - 1, cy + cell, cx + cell + 1, cy + cell + 1, 0xFFFFFFFF);
                g.fill(cx - 1, cy, cx, cy + cell, 0xFFFFFFFF);
                g.fill(cx + cell, cy, cx + cell + 1, cy + cell, 0xFFFFFFFF);
            }
            if (owner != null) g.fill(cx, cy, cx + cell, cy + cell, bg);
            Goal goal = goals.get(i);
            if (goal == null) continue;
            if (GoalIcons.draw(g, goal, cx + 2, cy + 2, cell - 4)) continue; // its picture, number included
            // No picture (the colour-variant goals): the item scaled to the cell, with the number badge.
            pose.pushMatrix();
            pose.translate(cx + 2, cy + 2);
            pose.scale(scale, scale);
            BoardHud.drawIcon(g, goal, 0, 0);
            BoardHud.drawBadge(g, font, goal, 0, 0);
            pose.popMatrix();
        }

        // Under the grid: scores, or the draft's pick count.
        int footY = l.gy() + l.gridH() + 6;
        for (Component line : l.footer()) {
            centered(g, font, line, footY, 0xFFFFFFFF);
            footY += 11;
        }

        if (hovered >= 0 && goals.get(hovered) != null) {
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(goals.get(hovered).name));
            String owner = hovered < owners.length ? owners[hovered] : null;
            if (owner != null) {
                String who = owner.equals(s.me) ? "you" : owner;
                tip.add(Component.literal((draft ? "Picked by " : "Claimed by ") + who)
                        .withColor(OpenDraftClient.colorOf(owner) & 0xFFFFFF));
            } else if (draft && s.myTurn()) {
                tip.add(Component.literal("Click to pick").withStyle(ChatFormatting.AQUA));
            } else if (!draft) {
                String progress = ServerHooks.progress(s.boardId, hovered);
                if (progress != null) tip.add(Component.literal("Your progress: " + progress).withStyle(ChatFormatting.GRAY));
            }
            g.setTooltipForNextFrame(font, tip, Optional.empty(), mouseX, mouseY);
        }
    }

    private static Component draftStatus(Session s) {
        long left = Math.max(0, s.turnDeadline - System.currentTimeMillis());
        if (s.turnPlayer == null) return Component.literal("Starting...");
        String picks = s.turnPicks + (s.turnPicks == 1 ? " goal" : " goals");
        if (s.myTurn()) {
            return Component.literal("Your turn: pick " + picks + " (" + BoardHud.formatCountdown(left) + ")").withStyle(ChatFormatting.AQUA);
        }
        return Component.literal(BoardHud.shortName(s.turnPlayer) + " is picking " + picks + " (" + BoardHud.formatCountdown(left) + ")")
                .withColor(OpenDraftClient.colorOf(s.turnPlayer) & 0xFFFFFF);
    }

    private void centered(GuiGraphicsExtractor g, Font font, Component c, int y, int color) {
        g.text(font, c, (width - font.width(c)) / 2, y, color, true);
    }

    /** Picked draft goals keep the picker's colour but darker, so the free ones stand out. */
    private static int dim(int argb) {
        int r = (argb >> 16 & 0xFF) / 2, gr = (argb >> 8 & 0xFF) / 2, b = (argb & 0xFF) / 2;
        return 0xFF000000 | r << 16 | gr << 8 | b;
    }
}
