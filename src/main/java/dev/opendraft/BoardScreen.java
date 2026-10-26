package dev.opendraft;

import dev.opendraft.goal.Goal;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
 * The board, big. Opened with the board key (or automatically on your draft turn). Hovering a goal
 * shows its name, who has it and how far along you are; during a draft, clicking a free goal on
 * your turn picks it. Forfeit and draw votes live here, and "Back to Lobby" once the game is over.
 * The game keeps running behind it, since everyone's clock is the synchronizer's.
 */
public final class BoardScreen extends Screen {
    private static final int MAX_CELL = 56;
    private static final int GAP = 3;

    /** The cell under the mouse in the last frame, or -1. */
    private int hovered = -1;
    private Button forfeitButton, drawButton, lobbyButton;
    /** Forfeit asks once more before it counts; this is when the first press happened. */
    private long forfeitArmedAt;

    public BoardScreen() {
        super(Component.literal("OpenDraft"));
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int bw = Math.min(120, (width - 24) / 3);
        forfeitButton = addRenderableWidget(Button.builder(Component.literal("Forfeit"), b -> forfeit())
                .bounds(cx - bw / 2 - 4 - bw, height - 26, bw, 20).build());
        drawButton = addRenderableWidget(Button.builder(Component.literal("Offer Draw"), b -> draw())
                .bounds(cx - bw / 2, height - 26, bw, 20).build());
        lobbyButton = addRenderableWidget(Button.builder(Component.literal("Back to Lobby"), b -> WorldMaker.leaveToLobby())
                .bounds(cx - bw / 2, height - 26, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close Board"), b -> close())
                .bounds(cx + bw / 2 + 4, height - 26, bw, 20).build());
        tick();
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
            for (String p : s.players) if (!s.forfeited.contains(p)) voters++;
            String label = votes.contains(s.me) ? "Cancel Draw (" + votes.size() + "/" + voters + ")"
                    : votes.isEmpty() ? "Offer Draw" : "Accept Draw (" + votes.size() + "/" + voters + ")";
            drawButton.setMessage(Component.literal(label));
        }
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
                    : "No board yet. Start one from the lobby, or with /opendraft start."), height / 2 - 10, 0xFFFFFFFF);
            centered(g, font, Component.literal("Players: " + String.join(", ", s.players)), height / 2 + 4, 0xFFAAAAAA);
            return;
        }
        String[] owners = draft ? s.draftPicks : s.owners;

        // Header: what's happening and the clock.
        centered(g, font, Component.literal(draft ? "Board draft" : "OpenDraft").withStyle(ChatFormatting.GOLD), 8, 0xFFFFFFFF);
        Component status = draft ? draftStatus(s) : Component.literal(BoardHud.clock(s));
        centered(g, font, status, 20, draft ? 0xFFFFFFFF : BoardHud.clockColor(s));

        // The grid, as large as fits between the header and the footer.
        int n = goals.size();
        int columns = BoardHud.columns(n);
        int rows = (n + columns - 1) / columns;
        int top = 34, bottom = height - 56;
        int cell = Math.min(MAX_CELL, Math.min((width - 40) / columns, (bottom - top) / rows) - GAP);
        cell = Math.max(18, cell);
        int gridW = columns * cell + (columns - 1) * GAP;
        int gridH = rows * cell + (rows - 1) * GAP;
        int gx = (width - gridW) / 2;
        int gy = top + Math.max(0, (bottom - top - gridH) / 2);
        float scale = (cell - 4) / 16f;
        Matrix3x2fStack pose = g.pose();
        for (int i = 0; i < n; i++) {
            int cx = gx + (i % columns) * (cell + GAP);
            int cy = gy + (i / columns) * (cell + GAP);
            String owner = i < owners.length ? owners[i] : null;
            boolean over = mouseX >= cx && mouseX < cx + cell && mouseY >= cy && mouseY < cy + cell;
            if (over) hovered = i;
            int bg = owner == null ? BoardHud.UNCLAIMED : OpenDraftClient.colorOf(owner);
            if (draft && owner != null) bg = dim(bg);
            if (over) g.fill(cx - 1, cy - 1, cx + cell + 1, cy + cell + 1, 0xFFFFFFFF);
            g.fill(cx, cy, cx + cell, cy + cell, bg);
            Goal goal = goals.get(i);
            if (goal == null) continue;
            pose.pushMatrix();
            pose.translate(cx + 2, cy + 2);
            pose.scale(scale, scale);
            BoardHud.drawIcon(g, goal, 0, 0);
            BoardHud.drawBadge(g, font, goal, 0, 0);
            pose.popMatrix();
        }

        // Footer: scores, or the draft's pick count.
        int footY = gy + gridH + 6;
        if (draft) {
            int picked = 0;
            for (String o : owners) if (o != null) picked++;
            centered(g, font, Component.literal(picked + " picked").withStyle(ChatFormatting.GRAY), footY, 0xFFFFFFFF);
        } else {
            for (Component line : BoardHud.scoreLines(s, font, width - 40)) {
                centered(g, font, line, footY, 0xFFFFFFFF);
                footY += 11;
            }
            String result = BoardHud.result(s);
            if (result != null) centered(g, font, Component.literal(result), footY,
                    s.winner.equals("-") ? 0xFFFFFFFF : OpenDraftClient.colorOf(s.winner));
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
        return Component.literal(s.turnPlayer + " is picking " + picks + " (" + BoardHud.formatCountdown(left) + ")")
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
