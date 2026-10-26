package dev.opendraft.net;

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
                case "--help", "-h" -> {
                    System.out.println("Usage: java -jar opendraft.jar [--port 25570] [--seed <world seed>]");
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
        Runtime.getRuntime().addShutdownHook(new Thread(host::close));
        Thread.currentThread().join();
    }

    /** The jar's Implementation-Version, which the build sets to the mod version. */
    public static String modVersion() {
        String v = SyncServerMain.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }
}
