package dev.opendraft.net;

/**
 * Line protocol between each player's game and the synchronizer.
 * Fields are separated by single spaces; names and goal keys never contain spaces.
 *
 * <pre>
 * client -> host   HELLO &lt;protocol&gt; &lt;modVersion&gt; &lt;seed | -&gt; &lt;name&gt;   ("-" from the title-screen lobby)
 * client -> host   SETTINGS &lt;key=value&gt;...    (leader only; relayed as-is so the lobby can show them)
 * client -> host   MATCH &lt;seed&gt;                (leader: everyone makes a new world with this seed)
 * client -> host   READY &lt;matchId&gt;             (my world for that match has loaded)
 * client -> host   FORFEIT
 * client -> host   DRAW | NODRAW               (vote for a draw, or turn it down)
 * client -> host   WHERE &lt;overworld|nether|end|lobby&gt;   (where my player is, for the tab list)
 * client -> host   CHAT &lt;text...&gt;
 * client -> host   NEWBOARD &lt;limitSec&gt; &lt;overtimeSec&gt; &lt;lookoutSec&gt; &lt;key&gt;...
 * client -> host   DRAFT &lt;limitSec&gt; &lt;overtimeSec&gt; &lt;lookoutSec&gt; &lt;size&gt; &lt;turnSec&gt; &lt;poolKey&gt;...
 * client -> host   PICK &lt;draftId&gt; &lt;poolIndex&gt;
 * client -> host   CLAIM &lt;boardId&gt; &lt;index&gt;
 * client -> host   ADV &lt;task|goal|challenge&gt; &lt;title...&gt;
 * client -> host   PONG &lt;number&gt;               (the answer to PING, with the same number)
 * host -> client   WELCOME &lt;assignedName&gt;
 * host -> client   REJECT &lt;reason...&gt;
 * host -> client   PLAYERS &lt;name&gt;:&lt;colour&gt;...  (join order; the first is the leader. Colours are 0, 1, 2... and stay put)
 * host -> client   SETTINGS &lt;key=value&gt;...
 * host -> client   MATCH &lt;matchId&gt; &lt;seed&gt;
 * host -> client   GO &lt;matchId&gt;                (everyone's world is ready; the leader deals the board)
 * host -> client   FORFEITED &lt;name&gt;
 * host -> client   DRAWVOTE &lt;name&gt;...          (who has voted for a draw; empty when it lapses)
 * host -> client   WHERE &lt;name&gt; &lt;overworld|nether|end|lobby&gt;
 * host -> client   CHAT &lt;name&gt; &lt;text...&gt;
 * host -> client   DRAFTPOOL &lt;draftId&gt; &lt;key&gt;...
 * host -> client   TURN &lt;name&gt; &lt;picks&gt; &lt;millisLeft&gt;
 * host -> client   PICKED &lt;poolIndex&gt; &lt;name&gt;
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
    public static final int VERSION = 8;
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
}
