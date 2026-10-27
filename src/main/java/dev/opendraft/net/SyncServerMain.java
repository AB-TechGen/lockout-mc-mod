package dev.opendraft.net;

import dev.opendraft.update.Release;

import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Runs the synchronizer on its own, with no Minecraft:
 * {@code java -jar opendraft.jar [--port 25570] [--seed <seed>]}.
 */
public final class SyncServerMain {
    private SyncServerMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = Protocol.DEFAULT_PORT;
        Long seed = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--relay" -> Relay.url = args[++i];
                case "--no-relay" -> Relay.url = "";
                case "--help", "-h" -> {
                    System.out.println("Usage: java -jar opendraft.jar [--port 25570] [--seed <world seed>] [--relay <url,...> | --no-relay]");
                    System.out.println("Without --seed, the lobby's Start button (or the first player to join from a world) sets it.");
                    return;
                }
                default -> {
                    System.err.println("Unknown option " + args[i] + " (try --help)");
                    System.exit(2);
                }
            }
        }
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm:ss");
        OpenDraftHost host = new OpenDraftHost(port, modVersion(), seed,
                msg -> System.out.println("[" + LocalTime.now().format(fmt) + "] " + msg));
        host.open();
        System.out.println("OpenDraft synchronizer " + modVersion() + " listening on port " + port
                + (seed == null ? "" : " (seed " + seed + ")"));
        System.out.println("Players join from the OpenDraft button on the title screen, using this machine's address and port " + port);
        int finalPort = port;
        host.shareOnline(reach -> {
            if (reach.code() == null) return;
            System.out.println("Invite code: " + reach.code());
            if (!reach.anywhere()) {
                System.out.println("If players on other networks can't connect, check that TCP port " + finalPort
                        + " is open in this machine's firewall and forwarded by its router.");
            }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(host::close));
        // No menu to update from here: just say so. Players on the new version can't join until this one is updated.
        Thread check = new Thread(() -> {
            try {
                Release r = Release.latest();
                if (r.newerThan(modVersion())) {
                    System.out.println("OpenDraft " + r.version() + " is out. Players on it can't join this " + modVersion()
                            + " lobby; get it from " + Release.PAGE);
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }, "OpenDraft update check");
        check.setDaemon(true);
        check.start();
        Thread.currentThread().join();
    }

    /** The jar's Implementation-Version, which the build sets to the mod version. */
    public static String modVersion() {
        String v = SyncServerMain.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }
}
