package dev.opendraft;

import dev.opendraft.update.Release;
import dev.opendraft.update.UpdateHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Keeps OpenDraft up to date. When the game starts it looks up the newest release in the background. On an older
 * version the OpenDraft button offers the update instead of the lobby, since lobbies only take players on the same
 * version anyway. The new jar is downloaded and checked against the release key, and {@link UpdateHelper} swaps it in
 * once the game has closed.
 *
 * <p>If the check can't be made at all (offline, or GitHub can't be reached), the lobby opens as usual. Dev runs and
 * {@code -Dopendraft.noUpdate=true} never check.
 */
public final class Updater {
    enum State {
        /** Not checking (a dev run), or the check didn't work: the lobby opens. */
        OFF,
        CHECKING,
        /** This is the newest version. */
        CURRENT,
        AVAILABLE,
        DOWNLOADING,
        /** The download didn't work; {@link #problem} says why. */
        FAILED,
        /** Downloaded: it goes in when the game closes. */
        READY,
        /** Like READY, but the swap didn't work when the game last closed, so the screen also says how to do it by hand. */
        STUCK
    }

    /** Written when the game closes with an update waiting; if it's still there next time, the swap didn't work. */
    private static final String TRIED = "swap-tried";
    private static final String HELPER_JAR = "opendraft-updater.jar";
    private static final String LOG = "update.log";

    static volatile State state = State.OFF;
    static volatile Release release;
    static volatile long downloaded;
    static volatile String problem;
    /** This version's jar in the mods folder. */
    static Path ownJar;
    /** Where downloads wait: config/opendraft-update. */
    static Path dir;
    private static boolean swapOnExit;

    private Updater() {
    }

    static void start() {
        FabricLoader loader = FabricLoader.getInstance();
        if (loader.isDevelopmentEnvironment() || Boolean.getBoolean("opendraft.noUpdate")) return;
        ownJar = loader.getModContainer("opendraft")
                .filter(c -> c.getOrigin().getKind() == ModOrigin.Kind.PATH)
                .map(c -> c.getOrigin().getPaths())
                .filter(paths -> paths.size() == 1)
                .map(List::getFirst)
                .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar"))
                .orElse(null);
        if (ownJar == null) return; // not a jar in the mods folder (a dev build, or bundled some other way)
        dir = loader.getConfigDir().resolve("opendraft-update");
        state = State.CHECKING;
        Thread t = new Thread(Updater::check, "OpenDraft update check");
        t.setDaemon(true);
        t.start();
    }

    private static void check() {
        String current = OpenDraftClient.modVersion();
        Release r;
        try {
            r = Release.latest();
        } catch (IOException | RuntimeException e) {
            OpenDraftClient.LOG.info("Couldn't check for an OpenDraft update: {}", e.getMessage());
            state = State.OFF;
            return;
        }
        if (!r.newerThan(current)) {
            OpenDraftClient.LOG.info("OpenDraft {} is the newest version", current);
            cleanUp();
            state = State.CURRENT;
            return;
        }
        OpenDraftClient.LOG.info("OpenDraft {} is out (this is {})", r.version(), current);
        release = r;
        Path waiting = dir.resolve(r.jar());
        if (Files.isRegularFile(waiting) && r.matches(waiting)) {
            // Downloaded before. It's tried again when the game closes, but the update screen also says how to do it by
            // hand if it didn't work last time.
            ready();
            if (state == State.READY && Files.exists(dir.resolve(TRIED))) state = State.STUCK;
        } else {
            state = State.AVAILABLE;
        }
    }

    /** The OpenDraft button: the lobby, or the update screen while there's an update to get. */
    static void openLobby(Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        mc.gui.setScreen(blocking() ? new UpdateScreen(parent) : new LobbyScreen());
    }

    static boolean blocking() {
        return state != State.OFF && state != State.CURRENT;
    }

    /** Starts the download, in the background. */
    static void download() {
        Release r = release;
        if (r == null || state == State.DOWNLOADING) return;
        downloaded = 0;
        problem = null;
        state = State.DOWNLOADING;
        Thread t = new Thread(() -> {
            try {
                Files.createDirectories(dir);
                r.download(dir.resolve(r.jar()), n -> downloaded = n);
                ready();
            } catch (IOException | RuntimeException e) {
                OpenDraftClient.LOG.warn("Downloading OpenDraft {} failed", r.version(), e);
                problem = e.getMessage();
                state = State.FAILED;
            }
        }, "OpenDraft update download");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Gets the swap ready for when the game closes. The helper runs from a copy of this jar: the one in the mods folder
     * is the file being replaced.
     */
    private static void ready() {
        try {
            Files.createDirectories(dir);
            Files.copy(ownJar, dir.resolve(HELPER_JAR), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            OpenDraftClient.LOG.warn("Couldn't get the update ready", e);
            state = State.STUCK;
            return;
        }
        synchronized (Updater.class) {
            if (!swapOnExit) {
                swapOnExit = true;
                Runtime.getRuntime().addShutdownHook(new Thread(Updater::swapAfterExit, "OpenDraft update"));
            }
        }
        state = State.READY;
    }

    /** Runs as the game closes: starts the helper, which waits for this process to end. */
    private static void swapAfterExit() {
        Release r = release;
        if (r == null || state != State.READY && state != State.STUCK) return;
        try {
            Files.writeString(dir.resolve(TRIED), r.version());
            Path target = ownJar.resolveSibling(r.jar());
            new ProcessBuilder(java(), "-cp", dir.resolve(HELPER_JAR).toString(), UpdateHelper.class.getName(),
                    Long.toString(ProcessHandle.current().pid()), ownJar.toString(), dir.resolve(r.jar()).toString(), target.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(dir.resolve(LOG).toFile()))
                    .start();
        } catch (IOException | RuntimeException e) {
            OpenDraftClient.LOG.warn("Couldn't start the OpenDraft update", e);
        }
    }

    /** This game's Java; javaw on Windows, so no console window opens. */
    private static String java() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path javaw = bin.resolve("javaw.exe");
        if (Files.isRegularFile(javaw)) return javaw.toString();
        Path exe = bin.resolve("java.exe");
        return Files.isRegularFile(exe) ? exe.toString() : bin.resolve("java").toString();
    }

    /** Up to date: whatever an earlier update left in the update folder can go. The log stays. */
    private static void cleanUp() {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (var files = Files.list(dir)) {
            for (Path p : files.toList()) {
                String name = p.getFileName().toString();
                if (name.equals(TRIED) || name.startsWith("opendraft-") && (name.endsWith(".jar") || name.endsWith(".jar.part"))) {
                    Files.deleteIfExists(p);
                }
            }
        } catch (IOException e) {
            OpenDraftClient.LOG.debug("Couldn't tidy the update folder", e);
        }
    }
}
