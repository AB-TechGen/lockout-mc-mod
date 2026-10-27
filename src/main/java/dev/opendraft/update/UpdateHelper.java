package dev.opendraft.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * Finishes an update once the game has closed. While it runs, Windows keeps the mod's jar locked, and Fabric won't start
 * with two copies of OpenDraft in the mods folder, so the old jar can only go once the game is gone.
 *
 * <p>{@code java -cp <copy of the old jar> dev.opendraft.update.UpdateHelper <game pid> <old jar> <downloaded jar> <new jar>}
 *
 * <p>It runs from a copy of the old jar, so it doesn't depend on what the new version contains. The new jar goes in
 * under a name Fabric ignores until the old one is deleted, so a failure never leaves the mods folder without OpenDraft.
 */
public final class UpdateHelper {
    /** How long a file can stay locked after the game has closed (antivirus scans, say). */
    private static final int TRIES = 60;

    private UpdateHelper() {
    }

    public static void main(String[] args) throws Exception {
        long pid = Long.parseLong(args[0]);
        Path old = Path.of(args[1]), downloaded = Path.of(args[2]), target = Path.of(args[3]);
        log("waiting for the game (" + pid + ") to close");
        ProcessHandle game = ProcessHandle.of(pid).orElse(null);
        if (game != null) game.onExit().get(10, TimeUnit.MINUTES);

        Path incoming = target.resolveSibling(target.getFileName() + ".part");
        Files.copy(downloaded, incoming, StandardCopyOption.REPLACE_EXISTING);
        for (int i = 0; i < TRIES; i++) {
            try {
                if (!old.equals(target)) Files.deleteIfExists(old);
                Files.move(incoming, target, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(downloaded);
                log("updated: " + target);
                return;
            } catch (IOException e) {
                if (i == 0) log("still locked, retrying: " + e);
                Thread.sleep(1000);
            }
        }
        // The old jar is still there and works; the game offers to finish by hand next time.
        if (Files.exists(old)) Files.deleteIfExists(incoming);
        log("gave up: " + old + " stayed locked");
    }

    private static void log(String message) {
        System.out.println(LocalDateTime.now().withNano(0) + " " + message);
    }
}
