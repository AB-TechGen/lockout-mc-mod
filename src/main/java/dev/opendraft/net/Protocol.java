package dev.opendraft.net;

/**
 * Line protocol between each player's game and the synchronizer.
 * Fields are separated by single spaces; names and goal keys never contain spaces.
 *
 * <pre>
 * client -> host   HELLO &lt;protocol&gt; &lt;modVersion&gt; &lt;seed | -&gt; &lt;name&gt; &lt;token&gt;   ("-" from the title-screen lobby;
 *                  the token is the player's secret: the same name and token get a dropped player's place back)
 * client -> host   LEAVE                       (leaving on purpose, so the place isn't kept for a reconnect)
 * client -> host   SETTINGS &lt;key=value&gt;...    (leader only; relayed as-is so the lobby can show them)
 * client -> host   SEARCHING &lt;1 | 0&gt;           (leader: my game started or stopped looking for a seed)
 * client -> host   MATCH &lt;seed&gt;                (leader: everyone makes a new world with this seed)
 * client -> host   READY &lt;matchId&gt;             (my world for that match has loaded)
 * client -> host   STARTANYWAY &lt;matchId&gt;       (leader: stop waiting for worlds still loading)
 * client -> host   FORFEIT
 * client -> host   DRAW | NODRAW               (vote for a draw, or turn it down)
 * client -> host   WHERE &lt;overworld|nether|end|lobby&gt;   (where my player is, for the tab list)
 * client -> host   CHAT &lt;text...&gt;
 * client -> host   NEWBOARD &lt;limitSec&gt; &lt;overtimeSec&gt; &lt;lookoutSec&gt; &lt;key&gt;...
 * client -> host   DRAFT &lt;limitSec&gt; &lt;overtimeSec&gt; &lt;lookoutSec&gt; &lt;size&gt; &lt;turnSec&gt; &lt;poolKey&gt;...
 * client -> host   PICK &lt;draftId&gt; &lt;poolIndex&gt;
 * client -> host   PAUSEDRAFT &lt;draftId&gt;        (leader: looking for a new seed; no picks, turn clock stopped)
 * client -> host   RESUMEDRAFT &lt;draftId&gt;       (no new seed after all; MATCH ends the paused draft instead)
 * client -> host   CLAIM &lt;boardId&gt; &lt;index&gt;
 * client -> host   ADV &lt;task|goal|challenge&gt; &lt;title...&gt;
 * client -> host   PONG &lt;number&gt;               (the answer to PING, with the same number)
 * host -> client   WELCOME &lt;assignedName&gt;
 * host -> client   REJECT &lt;reason...&gt;
 * host -> client   PLAYERS &lt;name&gt;:&lt;colour&gt;...  (join order; the first who isn't away is the leader. Colours are 0, 1, 2...
 *                  and stay put)
 * host -> client   AWAY &lt;name&gt;...              (players whose connection dropped; their place is kept for HOLD_SECONDS)
 * host -> client   CLOSED                      (the lobby is closing; don't try to reconnect)
 * host -> client   SETTINGS &lt;key=value&gt;...
 * host -> client   SEARCHING &lt;name | -&gt;        (who is looking for a seed; MATCH also ends it)
 * host -> client   MATCH &lt;matchId&gt; &lt;seed&gt; &lt;key&gt;  (the key tells this match's world apart when rejoining it)
 * host -> client   LOADING &lt;name&gt;...           (whose world everyone is still waiting for; empty once all are in)
 * host -> client   GO &lt;matchId&gt;                (everyone's world is ready; the leader deals the board)
 * host -> client   FORFEITED &lt;name&gt;
 * host -> client   DRAWVOTE &lt;name&gt;...          (who has voted for a draw; empty when it lapses)
 * host -> client   DRAWDECLINED                (the draw on offer wasn't accepted in DRAW_OFFER_SECONDS)
 * host -> client   WHERE &lt;name&gt; &lt;overworld|nether|end|lobby&gt;
 * host -> client   CHAT &lt;name&gt; &lt;text...&gt;
 * host -> client   DRAFTPOOL &lt;draftId&gt; &lt;key&gt;...
 * host -> client   TURN &lt;name&gt; &lt;picks&gt; &lt;millisLeft&gt;
 * host -> client   PICKED &lt;poolIndex&gt; &lt;name&gt;
 * host -> client   DRAFTPAUSED &lt;draftId&gt; | DRAFTRESUMED &lt;draftId&gt;  (TURN's millisLeft stays put while paused)
 * host -> client   BOARD &lt;boardId&gt; &lt;key&gt;...    (a new board; clears all claims)
 * host -> client   START &lt;elapsedMillis&gt; &lt;limitSec&gt; &lt;overtimeSec&gt;  (negative elapsed = lookout left)
 * host -> client   OVERTIME
 * host -> client   CLAIMED &lt;index&gt; &lt;name&gt;
 * host -> client   END &lt;winnerName | -&gt;          ("-" is a draw)
 * host -> client   ADV &lt;name&gt; &lt;frame&gt; &lt;title...&gt;
 * host -> client   INFO &lt;text...&gt;
 * host -> client   PING &lt;number&gt;               (every 2 seconds; the reply's round trip is the player's lag)
 * </pre>
 */
public final class Protocol {
    public static final int VERSION = 12;
    /** A draw offer that not everyone has accepted after this long is declined. */
    public static final int DRAW_OFFER_SECONDS = 30;
    /** How long a dropped player's place is kept, and how long their game (and the relay) keep trying to get back. */
    public static final int HOLD_SECONDS = 300;
    /**
     * A connection that hears nothing for this long is treated as dropped. The synchronizer pings every 2 seconds,
     * so this only happens when the connection died without closing, which is common through the relay.
     */
    public static final int SILENT_SECONDS = 20;
    public static final int DEFAULT_PORT = 25570;
    public static final int MIN_BOARD = 3;
    public static final int MAX_BOARD = 25;

    private Protocol() {
    }

    /** Keeps names safe to send: letters, digits and underscore, 1 to 16 characters. */
    public static String sanitizeName(String raw) {
        String s = raw == null ? "" : raw.replaceAll("[^A-Za-z0-9_]", "");
        if (s.isEmpty()) s = "Player";
        return s.length() > 16 ? s.substring(0, 16) : s;
    }

    /** A player's secret for HELLO: 32 random hex digits. */
    public static String newToken() {
        byte[] b = new byte[16];
        new java.security.SecureRandom().nextBytes(b);
        return java.util.HexFormat.of().formatHex(b);
    }

    public static boolean validToken(String token) {
        return token != null && token.matches("[0-9a-f]{32}");
    }
}
