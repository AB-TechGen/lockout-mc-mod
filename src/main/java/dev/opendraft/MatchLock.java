package dev.opendraft;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

/**
 * Keeps a match fair. While you're playing one, the buttons that open your world to LAN, allow
 * commands, change game modes or edit game rules are greyed out, wherever they are (the pause menu,
 * World Options, LAN Options). If the world gets opened or commands get turned on some other way,
 * the server side turns them straight back off.
 */
public final class MatchLock {
    /** The translation keys of the locked buttons' labels. */
    private static final Set<String> LOCKED = Set.of(
            "menu.shareToLan", "menu.multiplayerOptions.button", "lanServer.start",
            "menu.multiplayerOptions.lan", "menu.multiplayerOptions.network",
            "selectWorld.allowCommands", "selectWorld.allowCommands.new",
            "options.worldOptions.game_mode", "options.worldOptions.personal_game_mode",
            "options.worldOptions.guest.command_access", "options.worldOptions.guest.force_game_mode",
            "editGamerule.inGame.button");
    private static final Component WHY = Component.literal("Turned off during an OpenDraft match.");
    private static final Object FAILED = new Object();
    private static int tick;
    private static boolean warned;

    private MatchLock() {
    }

    /** Whether a world with this seed is where the current match is being played. */
    static boolean on(Session s, long worldSeed) {
        return s != null && s.matchOn() && s.rightWorld(worldSeed);
    }

    /** Hooked to every screen's init and tick: greys out the locked buttons during a match. */
    static void lockScreen(Screen screen) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        ServerLevel overworld = server == null ? null : server.overworld(); // null while the world loads
        if (overworld == null || !on(Session.current(), overworld.getSeed())) return;
        try {
            lock(screen.children());
        } catch (RuntimeException e) {
            // A screen we don't expect must never take the game down; it just isn't locked.
            if (!warned) OpenDraftClient.LOG.warn("Couldn't lock {}", screen.getClass().getName(), e);
            warned = true;
        }
    }

    private static void lock(List<? extends GuiEventListener> children) {
        for (GuiEventListener c : children) {
            // Only touch buttons the screen has (re-)enabled, so the tooltip isn't reset every tick.
            if (c instanceof AbstractWidget w && w.active && mentionsLocked(w.getMessage())) {
                w.active = false;
                w.setTooltip(Tooltip.create(WHY));
            }
            // Options screens keep their buttons in lists, inside rows.
            if (c instanceof ContainerEventHandler box) lock(box.children());
        }
    }

    /** Labels like "Allow Commands: OFF" are built from the option's name, so look inside them too. */
    private static boolean mentionsLocked(Component c) {
        if (c.getContents() instanceof TranslatableContents t) {
            if (LOCKED.contains(t.getKey())) return true;
            for (Object arg : t.getArgs()) {
                if (arg instanceof Component a && mentionsLocked(a)) return true;
            }
        }
        for (Component sibling : c.getSiblings()) {
            if (mentionsLocked(sibling)) return true;
        }
        return false;
    }

    /**
     * On the integrated server's tick: if this match's world was opened to others or had commands
     * turned on anyway (by a command, a key or another mod), undo it. The methods for that are new
     * in 26.2, so they're looked up by name.
     */
    static void enforce(MinecraftServer server) {
        if (++tick % 10 != 0) return;
        if (Boolean.TRUE.equals(call(server, "isPublished")) && call(server, "unpublishServer") != FAILED) {
            OpenDraftClient.chat(Component.literal("OpenDraft: your world can't be opened to other players during a match.")
                    .withStyle(ChatFormatting.RED));
        }
        Object data = call(server, "getWorldData");
        if (data == FAILED || data == null) return;
        Object commands = call(data, "isAllowCommands");
        if (commands == FAILED) commands = call(data, "getAllowCommands");
        if (!Boolean.TRUE.equals(commands)) return;
        if (call(server, "setWorldAllowCommands", false) == FAILED) {
            if (call(data, "setAllowCommands", false) == FAILED) return;
            for (ServerPlayer p : server.getPlayerList().getPlayers()) server.getPlayerList().sendPlayerPermissionLevel(p);
        }
        OpenDraftClient.chat(Component.literal("OpenDraft: commands stay off during a match.").withStyle(ChatFormatting.RED));
    }

    /** Calls a public method by name, returning its result (null for void) or FAILED when it isn't there or throws. */
    private static Object call(Object target, String name, Object... args) {
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            try {
                return m.invoke(target, args);
            } catch (ReflectiveOperationException | RuntimeException e) {
                OpenDraftClient.LOG.warn("Calling {} failed", m, e);
            }
        }
        return FAILED;
    }
}
