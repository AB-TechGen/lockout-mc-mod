package dev.opendraft;

import dev.opendraft.goal.Goal;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The small icon board in the top-right corner, plus the lookout countdown in the middle of
 * the screen. Claimed cells take the owner's colour (you are cyan). B opens {@link BoardScreen}.
 */
public final class BoardHud {
    static final int CELL = 18;
    static final int GAP = 1;
    static final int PAD = 3;
    /** Unclaimed cells have no fill, so the icon sits straight on the panel. */
    static final int UNCLAIMED = 0x00000000;
    static final int PANEL = 0xA0101014;
    /** Scores wider than this wrap onto another line, so four players don't stretch the panel across the screen. */
    static final int SCORE_WIDTH = 150;
    /** Longer names are cut to this many characters plus "...", so they don't stretch the panels. */
    static final int NAME_CHARS = 8;

    private static final Map<String, ItemStack> ICON_ITEMS = new ConcurrentHashMap<>();

    public static volatile boolean visible = true;

    private BoardHud() {
    }

    public static void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        Session s = Session.current();
        if (s == null || mc.player == null) return;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        lookoutCountdown(g, mc.font, s, sw, sh);
        resultBanner(g, mc.font, s, sw, sh);
        if (!visible || mc.gui.screen() instanceof BoardScreen) return;
        // Keep clear of the status effect icons, which vanilla draws in the same corner.
        int top = mc.player.getActiveEffects().isEmpty() ? 4 : 54;
        if (s.drafting()) {
            draftPanel(g, mc.font, s, sw, top);
        } else if (s.goals != null) {
            boardPanel(g, mc.font, s, sw, top);
        }
    }

    private static void boardPanel(GuiGraphicsExtractor g, Font font, Session s, int sw, int y0) {
        List<Goal> goals = s.goals;
        String[] owners = s.owners;
        // Square boards: 9 goals is 3x3, 25 is 5x5; other sizes fill rows of the nearest square's width.
        int columns = columns(goals.size());
        int rows = (goals.size() + columns - 1) / columns;
        int gridW = columns * CELL + (columns - 1) * GAP;
        int gridH = rows * CELL + (rows - 1) * GAP;

        List<Component> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        for (Component c : scoreLines(s, font, Math.max(gridW, SCORE_WIDTH))) {
            lines.add(c);
            colors.add(0xFFFFFFFF);
        }
        lines.add(Component.literal(clock(s)));
        colors.add(clockColor(s));
        String result = result(s);
        if (result != null) {
            lines.add(Component.literal(result));
            colors.add(s.winner == null || s.winner.equals("-") ? 0xFFFFFFFF : OpenDraftClient.colorOf(s.winner));
        }
        int textW = 0;
        for (Component c : lines) textW = Math.max(textW, font.width(c));
        int innerW = Math.max(gridW, textW);
        int panelW = innerW + PAD * 2;
        int panelH = PAD + gridH + 3 + lines.size() * (font.lineHeight + 1) + PAD - 1;
        int x0 = sw - panelW - 4;
        g.fill(x0, y0, x0 + panelW, y0 + panelH, PANEL);

        int gx = x0 + panelW - PAD - gridW;
        int gy = y0 + PAD;
        for (int i = 0; i < goals.size(); i++) {
            int cx = gx + (i % columns) * (CELL + GAP);
            int cy = gy + (i / columns) * (CELL + GAP);
            String owner = i < owners.length ? owners[i] : null;
            if (owner != null) g.fill(cx, cy, cx + CELL, cy + CELL, OpenDraftClient.colorOf(owner));
            Goal goal = goals.get(i);
            if (goal != null) {
                drawIcon(g, goal, cx + 1, cy + 1);
                drawBadge(g, font, goal, cx + 1, cy + 1);
            }
        }
        int ty = gy + gridH + 3;
        for (int i = 0; i < lines.size(); i++) {
            Component c = lines.get(i);
            g.text(font, c, x0 + panelW - PAD - font.width(c), ty, colors.get(i), true);
            ty += font.lineHeight + 1;
        }
    }

    private static void draftPanel(GuiGraphicsExtractor g, Font font, Session s, int sw, int y0) {
        long left = Math.max(0, s.turnDeadline - System.currentTimeMillis());
        Component a = Component.literal("Board draft").withStyle(ChatFormatting.GOLD);
        Component b = s.turnPlayer == null ? Component.literal("Starting...")
                : s.myTurn() ? Component.literal("Your pick! " + formatCountdown(left)).withStyle(ChatFormatting.AQUA)
                : Component.literal(shortName(s.turnPlayer) + " is picking " + formatCountdown(left));
        Component c = Component.literal("Press " + OpenDraftClient.boardKeyName() + " to open the board").withStyle(ChatFormatting.GRAY);
        int w = Math.max(font.width(a), Math.max(font.width(b), font.width(c))) + PAD * 2;
        int x0 = sw - w - 4;
        int lh = font.lineHeight + 1;
        g.fill(x0, y0, x0 + w, y0 + PAD * 2 + lh * 3 - 1, PANEL);
        g.text(font, a, x0 + PAD, y0 + PAD, 0xFFFFFFFF, true);
        g.text(font, b, x0 + PAD, y0 + PAD + lh, 0xFFFFFFFF, true);
        g.text(font, c, x0 + PAD, y0 + PAD + lh * 2, 0xFFFFFFFF, true);
    }

    /** The last five seconds of the lookout as a big number in the middle of the screen, then "GO!". */
    private static void lookoutCountdown(GuiGraphicsExtractor g, Font font, Session s, int sw, int sh) {
        long start = s.startMillis;
        if (s.goals == null || start == Session.NOT_STARTED || !s.hadLookout) return;
        long left = start - System.currentTimeMillis();
        String text;
        int color;
        if (left > 0 && left <= 5000) {
            text = String.valueOf((left + 999) / 1000);
            color = 0xFFFFD54F;
        } else if (left <= 0 && left > -1500) {
            text = "GO!";
            color = 0xFF66BB6A;
        } else {
            return;
        }
        Component c = Component.literal(text);
        float scale = 4f;
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(sw / 2f, sh / 2f - 40);
        pose.scale(scale, scale);
        g.text(font, c, -font.width(c) / 2, -font.lineHeight / 2, color, true);
        pose.popMatrix();
    }

    /** VICTORY, DEFEAT or DRAW across the screen for a few seconds when the game ends. */
    private static void resultBanner(GuiGraphicsExtractor g, Font font, Session s, int sw, int sh) {
        if (!s.ended || s.winner == null || s.goals == null) return;
        long age = System.currentTimeMillis() - s.endMillis;
        if (age > 7000) return;
        boolean draw = s.winner.equals("-");
        boolean won = s.winner.equals(s.me);
        String title = draw ? "DRAW" : won ? "VICTORY" : "DEFEAT";
        int color = draw ? 0xFFFFFFFF : won ? 0xFFFFD54F : 0xFFEF5350;
        // Fade out over the last second.
        int alpha = age < 6000 ? 0xFF : (int) (0xFF * (7000 - age) / 1000);
        color = (alpha << 24) | (color & 0xFFFFFF);
        String sub = draw ? "Nobody wins this one" : (won ? "You won " : shortName(s.winner) + " won ") + scoreText(s);
        if (!draw && !won && s.participants().size() > 2 && !s.iForfeited()) sub += ", " + yourPlace(s);
        g.fill(0, sh / 2 - 46, sw, sh / 2 + 10, (alpha * 0x90 / 0xFF) << 24);
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(sw / 2f, sh / 2f - 30);
        pose.scale(4f, 4f);
        Component c = Component.literal(title);
        g.text(font, c, -font.width(c) / 2, -font.lineHeight / 2, color, true);
        pose.popMatrix();
        Component subC = Component.literal(sub);
        g.text(font, subC, (sw - font.width(subC)) / 2, sh / 2 - 6, (alpha << 24) | 0xDDDDDD, true);
    }

    /** "13-9" style, or "9-6-4" with more players: the winner's score, then the rest, most first. */
    private static String scoreText(Session s) {
        List<Integer> rest = new ArrayList<>(), forfeits = new ArrayList<>();
        for (String p : s.participants()) {
            if (!p.equals(s.winner)) (s.forfeited.contains(p) ? forfeits : rest).add(s.score(p));
        }
        if (rest.isEmpty()) rest = forfeits; // won because everyone else forfeited
        rest.sort((a, b) -> b - a);
        StringBuilder b = new StringBuilder().append(s.score(s.winner));
        for (int n : rest) b.append('-').append(n);
        return b.toString();
    }

    /** "you came 2nd", or "you tied for 2nd". */
    private static String yourPlace(Session s) {
        int place = s.place(s.me);
        boolean tied = false;
        for (String p : s.participants()) {
            if (!p.equals(s.me) && !s.forfeited.contains(p) && s.score(p) == s.score(s.me)) tied = true;
        }
        String suffix = place % 100 / 10 == 1 ? "th" : switch (place % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return (tied ? "you tied for " : "you came ") + place + suffix;
    }

    // ---- shared with BoardScreen --------------------------------------------------------------------

    /** "GamingGangster_" becomes "GamingGa...". */
    static String shortName(String name) {
        return name.length() <= NAME_CHARS ? name : name.substring(0, NAME_CHARS) + "...";
    }

    static int columns(int n) {
        return Math.max(1, (int) Math.ceil(Math.sqrt(n)));
    }

    /** "You 3  Alex 2  Sam 4", you first, then in join order, wrapped onto more lines past maxWidth. */
    static List<Component> scoreLines(Session s, Font font, int maxWidth) {
        String me = s.me;
        List<String> order = new ArrayList<>();
        order.add(me);
        for (String name : s.participants()) if (!name.equals(me)) order.add(name);
        List<Component> lines = new ArrayList<>();
        MutableComponent line = null;
        for (String name : order) {
            Component part = Component.literal((name.equals(me) ? "You" : shortName(name)) + " " + s.score(name)
                    + (s.forfeited.contains(name) ? " (forfeit)" : "")).withColor(OpenDraftClient.colorOf(name) & 0xFFFFFF);
            if (line != null && font.width(line) + font.width("  ") + font.width(part) > maxWidth) {
                lines.add(line);
                line = null;
            }
            if (line == null) {
                line = Component.literal("").append(part);
            } else {
                line.append(Component.literal("  ")).append(part);
            }
        }
        if (line != null) lines.add(line);
        return lines;
    }

    /** Time left when there is a limit (counting overtime), otherwise time played. */
    static String clock(Session s) {
        long start = s.startMillis;
        if (start == Session.NOT_STARTED) return "--:--";
        long now = s.ended ? s.endMillis : System.currentTimeMillis();
        long elapsed = now - start;
        if (elapsed < 0) return "Lookout " + formatCountdown(-elapsed);
        if (s.limitSec <= 0) return formatTime(elapsed);
        long limit = s.limitSec * 1000L;
        if (s.overtime) return "Overtime " + formatCountdown(Math.max(0, limit + s.overtimeSec * 1000L - elapsed));
        return formatCountdown(Math.max(0, limit - elapsed));
    }

    static int clockColor(Session s) {
        if (s.overtime && !s.ended) return 0xFFEF5350;
        long start = s.startMillis;
        if (s.ended || start == Session.NOT_STARTED || s.limitSec <= 0) return 0xFFFFFFFF;
        long left = start + s.limitSec * 1000L - System.currentTimeMillis();
        return left < 60_000 && left >= 0 ? 0xFFFFD54F : 0xFFFFFFFF;
    }

    static String result(Session s) {
        if (!s.ended || s.winner == null) return null;
        return s.winner.equals("-") ? "Draw" : s.winner.equals(s.me) ? "You win!" : shortName(s.winner) + " wins!";
    }

    /** The goal's Draftout picture when it has one, otherwise its item, 16x16 at x, y. */
    static void drawIcon(GuiGraphicsExtractor g, Goal goal, int x, int y) {
        if (GoalIcons.draw(g, goal, x, y, 16)) return;
        g.fakeItem(ICON_ITEMS.computeIfAbsent(goal.key, k -> iconItem(goal)), x, y);
    }

    /** The goal's count in the icon's corner, like a stack size ("Obtain 64 Arrows" shows 64). */
    static void drawBadge(GuiGraphicsExtractor g, Font font, Goal goal, int x, int y) {
        if (GoalIcons.has(goal)) return; // the picture already has its number drawn on it
        int n = goal.integer("count", 0);
        if (n <= 1 || !goal.name.contains(String.valueOf(n))) return;
        String t = String.valueOf(n);
        g.text(font, Component.literal(t), x + 17 - font.width(t), y + 9, 0xFFFFFFFF, true);
    }

    private static ItemStack iconItem(Goal goal) {
        Identifier id = Identifier.tryParse(goal.str("icon", "minecraft:paper"));
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return new ItemStack(Items.PAPER);
        return new ItemStack(BuiltInRegistries.ITEM.getValue(id));
    }

    static String formatTime(long millis) {
        long secs = millis / 1000;
        long h = secs / 3600, m = (secs / 60) % 60, sec = secs % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%02d:%02d", m, sec);
    }

    /** Rounds up, so a countdown shows 15:00 at the start and 00:01 in its last second. */
    static String formatCountdown(long millis) {
        return formatTime(millis + 999);
    }
}
