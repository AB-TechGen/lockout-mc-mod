package dev.opendraft;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Every lobby match is played in a fresh world, so they pile up in the saves folder. This deletes the
 * oldest once there are more than {@link Settings#keepWorlds}. It only ever touches folders that
 * WorldMaker named ("OpenDraft 2026-10-26 14.30"), always keeps the newest {@code keepWorlds} of them,
 * and skips any world that is open. It also finds a match's world again, by the key written into it, for rejoining.
 */
final class WorldCleaner {
    /**
     * The folder names of match worlds. Vanilla turns the "." in WorldMaker's name into "_" when it makes
     * the folder, and adds " (1)" when two worlds share a name.
     */
    static final Pattern MATCH_WORLD = Pattern.compile("OpenDraft \\d{4}-\\d{2}-\\d{2} \\d{2}[._]\\d{2}( \\(\\d+\\))?");
    /** In a match world's folder: the key of the match played in it, so rejoining the match reopens it. */
    static final String MATCH_KEY_FILE = "opendraft_match.txt";
    private static final AtomicBoolean running = new AtomicBoolean();

    private WorldCleaner() {
    }

    private static Path saves() {
        return FabricLoader.getInstance().getGameDir().resolve("saves");
    }

    /** Marks a world as the one this game plays the match with {@code key} in. */
    static void markMatchWorld(Path world, String key) {
        try {
            Files.writeString(world.resolve(MATCH_KEY_FILE), key);
        } catch (IOException e) {
            OpenDraftClient.LOG.warn("Couldn't mark {} as this match's world", world.getFileName(), e);
        }
    }

    /** The folder name of the world marked with this match key (the newest, if somehow several), or null. */
    static String findMatchWorld(String key) {
        if (key == null || key.isEmpty() || !Files.isDirectory(saves())) return null;
        Path best = null;
        try (Stream<Path> list = Files.list(saves())) {
            for (Path p : (Iterable<Path>) list::iterator) {
                Path marker = p.resolve(MATCH_KEY_FILE);
                if (!MATCH_WORLD.matcher(p.getFileName().toString()).matches() || !Files.exists(p.resolve("level.dat"))
                        || !Files.isRegularFile(marker) || !key.equals(Files.readString(marker).trim())) {
                    continue;
                }
                if (best == null || Files.getLastModifiedTime(p).compareTo(Files.getLastModifiedTime(best)) > 0) best = p;
            }
        } catch (IOException | RuntimeException e) {
            OpenDraftClient.LOG.warn("Looking for this match's world failed", e);
        }
        return best == null ? null : best.getFileName().toString();
    }

    /** Deletes old match worlds in the background. Does nothing when the setting is 0 (keep them all). */
    static void run() {
        int keep = Settings.keepWorlds;
        if (keep <= 0 || !running.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try {
                deleteOld(saves(), keep);
            } catch (IOException | RuntimeException e) {
                OpenDraftClient.LOG.warn("Cleaning up old OpenDraft worlds failed", e);
            } finally {
                running.set(false);
            }
        }, "OpenDraft world cleanup");
        t.setDaemon(true);
        t.start();
    }

    static int deleteOld(Path saves, int keep) throws IOException {
        if (!Files.isDirectory(saves)) return 0;
        List<Path> worlds = new ArrayList<>();
        try (Stream<Path> list = Files.list(saves)) {
            list.filter(p -> MATCH_WORLD.matcher(p.getFileName().toString()).matches()
                    && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
                    && Files.exists(p.resolve("level.dat"))).forEach(worlds::add);
        }
        // The names start with the date and time the match began, so newest first is reverse name order.
        worlds.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        int deleted = 0;
        for (int i = keep; i < worlds.size(); i++) {
            Path world = worlds.get(i);
            if (inUse(world)) continue;
            try {
                deleteTree(world);
                deleted++;
            } catch (IOException e) {
                OpenDraftClient.LOG.warn("Couldn't delete old OpenDraft world {}", world.getFileName(), e);
            }
        }
        if (deleted > 0) OpenDraftClient.LOG.info("Deleted {} old OpenDraft world(s), keeping the newest {}", deleted, keep);
        return deleted;
    }

    /** An open world holds a lock on its session.lock file; if it can't be taken, the world is in use. */
    private static boolean inUse(Path world) {
        Path lock = world.resolve("session.lock");
        if (!Files.exists(lock)) return false;
        try (FileChannel ch = FileChannel.open(lock, StandardOpenOption.WRITE)) {
            FileLock l = ch.tryLock();
            if (l == null) return true;
            l.release();
            return false;
        } catch (OverlappingFileLockException | IOException e) {
            return true; // held by this game, or not ours to look at: leave it alone
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        List<Path> all;
        try (Stream<Path> walk = Files.walk(dir)) { // doesn't follow links, so nothing outside the world is touched
            all = walk.sorted(Comparator.reverseOrder()).toList(); // files before the folders holding them
        }
        for (Path p : all) Files.delete(p);
    }
}
