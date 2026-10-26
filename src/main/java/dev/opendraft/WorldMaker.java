package dev.opendraft;

import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Moves players between the lobby and match worlds using vanilla's own screens: "Create New
 * World" with the match seed filled in and pressed for you, and "Save and Quit" afterwards. The
 * names of those vanilla methods move between versions, so they're looked up by reflection; if
 * one can't be found the player is asked to do that step by hand.
 */
public final class WorldMaker {
    private static volatile Long pendingSeed;
    private static volatile String pendingName;
    /** The Create World screen to press "Create" on, once it has settled. */
    private static Screen pressOn;
    private static int pressIn;
    private static boolean openCreateSoon;
    private static boolean openLobbySoon;
    /** Show the lobby the next time the title screen appears (after leaving a match world). */
    static volatile boolean returnToLobby;

    private WorldMaker() {
    }

    /** The seed a world is about to be created with, for the lobby to show. */
    public static Long pendingSeed() {
        return pendingSeed;
    }

    /** Leaves the current world, if any, and creates a fresh one with the match's seed. */
    static void createMatchWorld(int matchId, long seed) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            MinecraftServer server = mc.getSingleplayerServer();
            if (server != null && server.overworld().getSeed() == seed) return; // already in a world with this seed
            pendingSeed = seed;
            pendingName = "OpenDraft " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm", Locale.ROOT));
            if (mc.level != null) {
                // Leaving lands on the title screen, whose hook opens Create World.
                if (!leaveWorld()) {
                    OpenDraftClient.chat(Component.literal("A new OpenDraft match is starting. Save and quit to the title screen to join it.")
                            .withStyle(ChatFormatting.GOLD));
                }
                return;
            }
            openCreateScreen();
        });
    }

    /** "Back to lobby" after a match. */
    static void leaveToLobby() {
        returnToLobby = true;
        if (!leaveWorld()) {
            OpenDraftClient.chat(Component.literal("Use Save and Quit to Title; the lobby opens from there.").withStyle(ChatFormatting.GRAY));
        }
    }

    /** Hooked to every screen's init: adds the OpenDraft button to the title screen and fills in Create World. */
    static void afterInit(Screen screen) {
        if (screen instanceof TitleScreen) {
            addTitleButton(screen);
            if (pendingSeed != null) {
                openCreateSoon = true;
            } else if (returnToLobby && Session.current() != null) {
                openLobbySoon = true;
            }
            returnToLobby = false;
        } else if (screen instanceof CreateWorldScreen && pendingSeed != null) {
            fillIn(screen);
        }
    }

    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (openCreateSoon) {
            openCreateSoon = false;
            openCreateScreen();
        } else if (openLobbySoon) {
            openLobbySoon = false;
            mc.gui.setScreen(new LobbyScreen());
        }
        if (pressOn != null && --pressIn <= 0) {
            Screen s = pressOn;
            pressOn = null;
            if (mc.gui.screen() == s) {
                if (press(s)) pendingSeed = null;
                else OpenDraftClient.LOG.warn("Couldn't press Create New World; the player has to.");
            }
        }
    }

    /**
     * Puts the OpenDraft button in the menu, right under Multiplayer and the same size. To make room,
     * the buttons above it move up half a row and the ones below move down half a row, so the menu
     * stays centred and clear of the copyright line.
     */
    private static void addTitleButton(Screen screen) {
        Minecraft mc = Minecraft.getInstance();
        List<AbstractWidget> widgets = Screens.getWidgets(screen);
        AbstractWidget singleplayer = labelled(widgets, "menu.singleplayer");
        AbstractWidget multiplayer = labelled(widgets, "menu.multiplayer");
        Button.OnPress open = b -> mc.gui.setScreen(new LobbyScreen());
        if (multiplayer == null) {
            widgets.add(Button.builder(Component.literal("OpenDraft"), open).bounds(4, 4, 98, 20).build());
            return;
        }
        int step = singleplayer != null && multiplayer.getY() > singleplayer.getY()
                ? multiplayer.getY() - singleplayer.getY() : multiplayer.getHeight() + 4;
        int up = step / 2, down = step - up;
        int left = multiplayer.getX() - 30, right = multiplayer.getX() + multiplayer.getWidth() + 30;
        int row = multiplayer.getY();
        for (AbstractWidget w : widgets) {
            int middle = w.getX() + w.getWidth() / 2;
            // Only the menu column (with the small buttons beside it), not corner buttons or the copyright line.
            if (middle < left || middle > right || w.getY() >= screen.height - 20) continue;
            w.setY(w.getY() > row ? w.getY() + down : w.getY() - up);
        }
        widgets.add(Button.builder(Component.literal("OpenDraft"), open)
                .bounds(multiplayer.getX(), multiplayer.getY() + step, multiplayer.getWidth(), multiplayer.getHeight()).build());
    }

    private static AbstractWidget labelled(List<AbstractWidget> widgets, String key) {
        String label = Component.translatable(key).getString();
        for (AbstractWidget w : widgets) {
            if (w.getMessage().getString().equals(label)) return w;
        }
        return null;
    }

    /** CreateWorldScreen.openFresh(Minecraft, Runnable or Screen). Also the lobby's "Create match world" button. */
    static void openCreateScreen() {
        Minecraft mc = Minecraft.getInstance();
        for (Method m : CreateWorldScreen.class.getMethods()) {
            if (!m.getName().equals("openFresh") || !Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] t = m.getParameterTypes();
            if (t.length != 2 || t[0] != Minecraft.class) continue;
            Object back;
            if (t[1] == Runnable.class) back = (Runnable) () -> mc.gui.setScreen(new LobbyScreen());
            else if (t[1].isAssignableFrom(LobbyScreen.class)) back = new LobbyScreen();
            else continue;
            try {
                m.invoke(null, mc, back);
                return;
            } catch (ReflectiveOperationException | RuntimeException e) {
                OpenDraftClient.LOG.warn("Opening Create World failed", e);
            }
        }
        // Couldn't open it: the lobby tells the player to create the world themselves.
        OpenDraftClient.LOG.warn("No usable CreateWorldScreen.openFresh found");
        mc.gui.setScreen(new LobbyScreen());
    }

    private static void fillIn(Screen screen) {
        Object ui = call(screen, "getUiState");
        if (ui == null) return;
        call(ui, "setName", pendingName);
        call(ui, "setSeed", Long.toString(pendingSeed));
        call(ui, "setAllowCommands", false);
        pressOn = screen;
        pressIn = 3; // give the screen a moment to settle
    }

    /** Presses "Create New World": its private handler if it's there, otherwise the button itself. */
    private static boolean press(Screen screen) {
        try {
            Method m = CreateWorldScreen.class.getDeclaredMethod("onCreate");
            m.setAccessible(true);
            m.invoke(screen);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        String label = Component.translatable("selectWorld.create").getString();
        for (AbstractWidget w : Screens.getWidgets(screen)) {
            if (!w.getMessage().getString().equals(label)) continue;
            for (Method m : w.getClass().getMethods()) {
                if (!m.getName().equals("onPress")) continue;
                try {
                    m.invoke(w, new Object[m.getParameterCount()]);
                    return true;
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        }
        return false;
    }

    /** Save and quit to the title screen, the way the pause menu does it. */
    static boolean leaveWorld() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return true;
        Component reason = Component.literal("OpenDraft");
        // Newer versions keep the pause menu's quit logic in a static helper.
        for (Method m : PauseScreen.class.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers()) || !m.getName().toLowerCase(Locale.ROOT).contains("disconnect")) continue;
            Object[] args = argsFor(m, mc, reason);
            if (args == null) continue;
            try {
                m.setAccessible(true);
                m.invoke(null, args);
                if (mc.level == null) {
                    if (!(mc.gui.screen() instanceof TitleScreen)) mc.gui.setScreen(new TitleScreen());
                    return true;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                OpenDraftClient.LOG.warn("Leaving the world via {} failed", m, e);
            }
        }
        // Older ones: level.disconnect(reason), then disconnectWithSavingScreen().
        try {
            Method d = mc.level.getClass().getMethod("disconnect", Component.class);
            d.invoke(mc.level, reason);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        for (String name : new String[]{"disconnectWithSavingScreen", "disconnectFromWorld", "disconnect"}) {
            for (Method m : Minecraft.class.getMethods()) {
                if (!m.getName().equals(name)) continue;
                Object[] args = argsFor(m, mc, reason);
                if (args == null) continue;
                try {
                    m.invoke(mc, args);
                    if (mc.level == null) {
                        mc.gui.setScreen(new TitleScreen());
                        return true;
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    OpenDraftClient.LOG.warn("Leaving the world via {} failed", m, e);
                }
            }
        }
        return mc.level == null;
    }

    /** Fills a method's parameters with what we have (Minecraft, a reason, a screen, a flag), or null if it wants more. */
    private static Object[] argsFor(Method m, Minecraft mc, Component reason) {
        Class<?>[] t = m.getParameterTypes();
        Object[] args = new Object[t.length];
        for (int i = 0; i < t.length; i++) {
            if (t[i] == Minecraft.class) args[i] = mc;
            else if (t[i].isAssignableFrom(Component.class) && t[i] != Object.class) args[i] = reason;
            else if (Screen.class.isAssignableFrom(t[i]) && t[i].isAssignableFrom(TitleScreen.class)) args[i] = new TitleScreen();
            else if (t[i] == boolean.class) args[i] = false;
            else return null;
        }
        return args;
    }

    private static Object call(Object target, String name, Object... args) {
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            try {
                return m.invoke(target, args);
            } catch (ReflectiveOperationException | RuntimeException e) {
                OpenDraftClient.LOG.warn("Calling {} failed", m, e);
            }
        }
        return null;
    }
}
