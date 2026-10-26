# OpenDraft

OpenDraft is a Fabric mod for Minecraft 26.3 that plays lockout bingo with all 316 [Draftout](https://draftoutmc.com) goals.

Every player plays **their own singleplayer world** on the same seed, saved on their own device. Nobody joins
anyone's world, so there is no shared-server lag. A small **synchronizer** only tracks the board: when your game
sees that you finished a goal, it sends one line ("claim goal 7") and the synchronizer tells everyone who got it
first.

No Minecraft or Microsoft account is involved. Players are known by a display name, so dev clients and offline
setups work.

OpenDraft is **completely free**. There are no paid features and no servers to rent, and the code is open source
under the MIT license.

## Features

- **All 316 Draftout goals**, on a random or drafted board of 3 to 25 goals.
- **1v1 or a free-for-all** with 3, 4 or more players.
- **Seed filtering**: pick what a random seed must have near spawn, and how near. The options are Village,
  Shipwreck, Outpost, Witch Hut, Desert Temple, Jungle Temple, Igloo, Mansion, Ruined Portal, Trail Ruins,
  Abandoned Camp, Ancient City, Trial Chambers and Pale Garden. See [Seed filter](#seed-filter).
- **A lobby on the title screen** with the player list, chat and settings. Every match starts in a fresh world.
- **Invite codes**: copy the code, your friends paste it, and it works from any network. Nobody has to set up port
  forwarding.
- **Time limit and overtime**, a lookout from the air before the clock starts, and forfeit and draw votes.
- **Shared advancement messages**, and your progress on each goal when you hover it.
- **Goal pictures** from Draftout on the board, so you can tell goals apart at a glance.
- **Fair on laggy connections**: close claims are judged by when they were made, not when they arrived. See
  [Ping](#ping).
- **Old match worlds are cleaned up**: keep the newest few and the rest are deleted. See
  [Old match worlds](#old-match-worlds).

## Installing

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 26.3 (loader 0.19.3 or newer).
2. Download [Fabric API](https://modrinth.com/mod/fabric-api) for 26.3.
3. Download `opendraft-<version>.jar` from the [Releases](../../releases) page.
4. Put both jars in your `mods` folder:
   - macOS: `~/Library/Application Support/minecraft/mods`
   - Windows: `%appdata%\.minecraft\mods`
   - Linux: `~/.minecraft/mods`
5. Start Minecraft with the Fabric profile.

Everyone in a game needs the **same OpenDraft version**. A lobby turns away other versions and says who needs to
update. To build the jar yourself instead, see
[Building from source](#building-from-source).

## Playing

1. On the title screen, press **OpenDraft** (under Multiplayer). This opens the lobby screen.
2. One player types a name and presses **Create Lobby**. After a few seconds the lobby shows an **invite code**,
   hidden as `XXXX-XXXX` so it's safe on stream. **Copy** copies it even while it's hidden, and **Show** reveals it.
   Send it to your friends.
3. Everyone else types their name, pastes the code and presses **Join**. Names don't need a Minecraft account, and
   duplicate names get a number added.
4. The lobby lists everyone who has joined, the settings and a chat. The first player in the lobby is the leader
   (marked with a star). Only the leader can press **Edit Settings** and **Start**.
5. **Start** makes everyone's game create a new singleplayer world with the same seed. Until the slowest computer
   has finished loading and drawing the terrain, everyone who is already in sees a "Waiting for other players..."
   screen instead of the world, so nobody gets extra time to look around. Then the board is dealt (or drafted) and
   the lookout begins.
6. After the game, press **Back to Lobby** on the full-screen board to leave the world and return to the lobby for
   the next match.

The first claim to reach the synchronizer wins the goal. The game ends once the leader can't be caught, when every
goal is claimed, when the time limit runs out, or when everyone else has forfeited. The winner sees **VICTORY**
and everyone else sees **DEFEAT** (or **DRAW**). With three or more players, everyone also sees where they placed
and the final scores.

If the game can't create the world or leave a world by itself, it tells you to do that step by hand: create a
world with the seed shown in the lobby, or use Save and Quit to Title.

### Lobby settings

The leader's **Edit Settings** has two tabs. They're saved in `config/opendraft.properties`.

| Tab | Setting | Default |
| --- | --- | --- |
| Match | Board: Random or Drafted | Random |
| Match | Size (3 to 25 goals) | 25 |
| Match | Time limit (minutes, none = play until the leader can't be caught) | none |
| Match | Overtime on a tie (minutes, off = draw) | off |
| Match | Lookout (seconds) | 30 |
| Match | Draft turn length (seconds) | 30 |
| World | Seed (blank = random) | blank |
| World | Structures and biomes a random seed must have near spawn | none |
| World | How near (blocks from spawn) | 300 |

### Old match worlds

Every match is played in a new world, so the lobby has an **Old worlds** button (under the settings, or above
**Back** before you join). It sets how many OpenDraft match worlds to keep: 1, 2, 3, 5 (the default), 10, 20, 50 or
all. When you get back to the title screen, the oldest match worlds past that number are deleted. Only folders that
OpenDraft itself made (named like `OpenDraft 2026-10-26 14_30`) are ever touched, never your other worlds, and a world
that's open is skipped. Each player sets their own, since it's about their own saves folder. It's saved as
`keepWorlds` in `config/opendraft.properties`, and `/opendraft settings keepworlds <n>` sets it too (0 keeps them
all). To keep a match world for good, rename its folder or copy it somewhere else.

On the steppers, shift-click changes the time limit, lookout and turn length by 1 instead of 5. When time runs out
the leader wins. On a tie for the lead, overtime starts and whoever pulls ahead first wins. If overtime also runs
out, or every goal is claimed while it's still tied, it's a draw.

### Seed filter

On the World tab, turn on what a random seed must have near spawn: Village, Shipwreck, Outpost, Witch Hut,
Desert Temple, Jungle Temple, Igloo, Mansion, Ruined Portal, Trail Ruins, Abandoned Camp, Ancient City, Trial
Chambers or a Pale Garden, all within the distance you set. When the leader presses **Start**, their game tries random
seeds for up to a minute and then starts the match with the first one that has everything. The lobby chat says how far
each thing is from spawn. If no seed turns up, the lobby says how often each one appeared, so you can tell which to
turn off. A typed seed is used as it is, without the filter.

The search doesn't generate any terrain. For each seed it works out where spawn is and where the game would try to
place each structure, then checks the biome there, which is the same first test the game makes. That is quick and
right almost every time, but a structure can still occasionally fail to appear for reasons the search can't see. To check it in a world with cheats on, compare `/opendraft seed` (where the search thinks
spawn and each structure are) with `/locate structure`.

### Forfeit and draw

Open the full-screen board during a match:

- **Forfeit** (press it twice to confirm) gives up. Your claims stay on the board, but you can't claim more and they
  stop counting. When only one player is left, they win; with more players left, the game goes on without you.
- **Offer Draw** asks everyone else. When every player still in the game has accepted, the game ends in a draw.
  Any goal claimed cancels the offer.

`/opendraft forfeit`, `/opendraft draw` and `/opendraft draw decline` do the same from chat.

### Fair play

Match worlds are created with commands off, on Normal difficulty. During a match, **Open to LAN**, **Allow Commands**,
the game mode buttons and **Edit Game Rules** are greyed out, in the pause menu and in World Options. If your world
gets opened to other players or commands get turned on some other way, OpenDraft turns them straight back off. You can
still switch between Easy, Normal and Hard, but picking Peaceful puts the world on Easy instead.

### Lookout

When a board starts, everyone is put in spectator mode 20 blocks above the highest ground nearby, so nobody is
stuck inside a mountain. You're held there for the lookout (30 seconds by default) and can look around but not
move. Hold right-click to zoom like a spyglass. Press **sneak** to drop to where you're standing for the ground view,
and **jump** to go back up to the sky view. For the last 5 seconds you're put on the ground and can't go back up,
while they count down in the middle of the screen. Then you get your old game mode back and the clock starts.
Nothing counts during the lookout.

### Fullbright

Press **Alt+2** in a world to turn fullbright on or off. It sets the game's brightness to 1500%, so caves and nights
are lit, without touching your Brightness setting. It's off again each time the game starts.

### Draft

With the board set to Drafted, the synchronizer deals a pool of twice as many goals as the board needs. Players pick
in join order and then back the other way (A B C, C B A, A B C...), so whoever ends a round picks twice in a row
and nobody gets ahead. With two players, that's 1 goal on the first turn and 2 on every turn after. The board opens
on your turn; click a goal to pick it, or use `/opendraft pick <n>` with the numbers from `/opendraft board`. A goal
can only be picked once, and when a turn runs out of time the remaining picks are made at random. The board is
shuffled once it's full.

### Chat

Advancements anyone earns appear in everyone's chat, like on a shared server. Death messages and other chat stay in
your own world. The lobby has its own chat.

### The board

A small grid of goal pictures sits in the top-right corner (it moves down while you have status effects, so it doesn't cover
them). Your claims are always cyan on your screen. Everyone else gets red, yellow, green and so on in the order they
joined, and keeps that colour for the whole game on every other player's screen too. Unclaimed goals are grey.
The pictures show the goal's number where it has one. The goals with colour variants (wool, concrete, glazed
terracotta, harnesses, cushions, leather armour and sheep) show the item in that colour instead, with the number in
the corner. The scores and the clock sit under the grid: time left when there's a
limit, time played otherwise.

Press **B** (rebind it under Controls, in the OpenDraft category) for the big board, a panel in the middle of the
screen with Forfeit, Offer Draw and Close Board under it. Hover a goal to see its
name, who claimed it and, for goals that need several things, your progress (for example "Your progress: 3/5").
The game keeps running while it's open.

### Commands and the standalone synchronizer

The lobby screen covers everything, and these commands also work inside a world:

- `/opendraft host` and `/opendraft join <invite code or address> [name]` start or join a game for the world
  you're in. `/opendraft host <port>` uses that port instead of picking one.
- `/opendraft start [size] [minutes] [overtime]` deals a board and `/opendraft draft` starts a draft.
- `/opendraft settings` shows the settings, and `/opendraft settings size|time|overtime|lookout|turn|mode ...` changes
  them.
- `/opendraft board` lists the goals in chat, `/opendraft goals` shows which goals this version can deal,
  `/opendraft hud` toggles the small board and `/opendraft leave` leaves the game.

The synchronizer can also run on its own, so the lobby keeps running when the player who created it closes Minecraft.
Run the same jar from Releases with `java -jar opendraft-<version>.jar [--port 25570] [--seed <seed>]`. This needs
Java 25 but not Minecraft. It prints an invite code, and players join with that code or the machine's address.

### Playing over the internet

When you create a lobby, your game gets a short code like `K7QM-2D9X` from the OpenDraft relay, a small free service
that passes the game's messages between players (see [`relay/README.md`](relay/README.md)). Friends anywhere join
with it, and nobody opens any ports. The synchronizer still runs in your game.

If the relay can't be reached, your game asks your router to open the port instead (TCP 25570, or another free port
if that one is taken), using UPnP the way game consoles do. The code is longer then: it holds your home-network address and your internet
address, and joining tries both, so friends in the same house and friends elsewhere use the same code. The port is
closed again when you leave the lobby.

If the router doesn't open the port either, the lobby says **Only people on your network can join** under the code,
and names the port. Either turn on UPnP in your router's settings, or forward that port by hand: open the router's
page (often `192.168.1.1` or `192.168.0.1`), find Port Forwarding, and forward the TCP port to this computer. The code
already includes your internet address, so it works once the port is open. If your connection is behind your internet
provider's network (common on phone hotspots, dorms and some providers), forwarding won't help; use a tunnel such as
playit.gg, Tailscale or ZeroTier instead.

On macOS, allow Minecraft to find devices on your local network if it asks, so it can reach the router. When the
router doesn't answer, the game looks up your internet address with `api.ipify.org`.

To use your own relays, add `relay=<address>` (or several, separated by commas) to `config/opendraft.properties`.
Everyone in the lobby needs the same ones.

### Panorama

The lobby and title screen use the "Sinkhole" panorama by sumone_lol, from
[Main Menu Panoramas](https://modrinth.com/resourcepack/noice-panoramas), in
`assets/minecraft/textures/gui/title/background`. It's licensed CC BY-NC 4.0, so it's for non-commercial use only.
Delete those six images to get vanilla's panorama back.

## Building from source

Most players should use the jar from [Releases](../../releases). To build it yourself, you need JDK 25:

```
./gradlew build
```

The jar is written to `build/libs/`; install it the same way as in [Installing](#installing). On GitHub, the Build
workflow in `.github/workflows/build.yml` runs this on every push and attaches the jar to the run (under Artifacts).
If Gradle can't resolve the Loom or loader versions in `gradle.properties`, copy the current values for 26.3 from
<https://fabricmc.net/develop/>.

## How goals are detected

`src/main/resources/opendraft/goals.json` defines every goal. Rules follow the
[Draftout wiki](https://github.com/draftout/draftout-wiki/tree/main/content/docs/goals). Each goal has a type:

| Type | Meaning |
| --- | --- |
| `item`, `items_all`, `items_unique`, `stack_any`, `inventory_full`, `item_check` | What's in your inventory right now |
| `wear`, `wear_any`, `wear_special` | Your armor slots |
| `stat`, `stat_unique` | Vanilla statistics, counted from the moment the board starts |
| `advancement`, `advancement_count` | Vanilla advancements earned after the start |
| `flag`, `set`, `counter`, `counter_sum` | Facts the mod records during the game (kills, deaths, items consumed, mobs spied on, biomes visited...) |
| `dimension`, `position`, `xp_level`, `hunger_empty` | Where you are and your current state |

A goal whose items, mobs or advancements don't exist in the running version is never dealt. `/opendraft goals` lists
those goals and the reason.

### Approximations

A few Draftout rules need hooks that vanilla doesn't expose. These are close but not exact:

- **Ride a Geyser** and **Sit on Colored Cushion** recognise those blocks by name (`*geyser*`, `<color>_cushion`).
- **Construct a Copper Golem** counts a copper golem that appears within 10 blocks of you.
- **Give a Diamond to a Fox** counts any fox near you holding a diamond.
- **Teleport through Nether Portal using Ender Pearl** counts a dimension change that lands you where your pearl went.
- **Feed Golden Dandelion to any Baby Mob** counts using a golden dandelion on a mob.

## Ping

The synchronizer sends every player a `PING` every 2 seconds and times the `PONG` that comes back, so it knows how
long each player's messages take to reach it (half the round trip, smoothed). When a claim arrives, it works out when
it was probably made: the time it arrived minus that player's lag. If someone with more lag could still have a claim
on the way that was made earlier, the claim waits for exactly that long (the difference in lag, at most 300 ms)
before it counts. Then the goal goes to whoever probably finished first, and everyone sees a line like "Bob got there
63 ms before Alice once ping is taken into account" when the order changed. With everyone on similar connections a
claim counts straight away. Claims that were made before the time limit ran out still count at the end, even if they
were still waiting.

Lag is measured by the mod, so a modified copy could pretend to have more lag than it does. The 300 ms cap limits
what that could gain.

## Limits

- Lag is made up for up to 300 ms each way (see [Ping](#ping)); past that, being closer to the synchronizer is
  still a slight edge.
- State isn't saved. If the synchronizer closes, the game ends; rejoining while it runs replays the current board.

## Credits

- Goals, goal rules and goal pictures: [Draftout](https://draftoutmc.com) and the
  [Draftout wiki](https://github.com/draftout/draftout-wiki).
- Lobby and title screen panorama: "Sinkhole" from [Main Menu Panoramas](https://modrinth.com/resourcepack/noice-panoramas)
  by sumone_lol, under [CC BY-NC 4.0](https://creativecommons.org/licenses/by-nc/4.0/) (resized to 1024x1024). It's
  for non-commercial use only.
- The mod's code is under the MIT license (see `LICENSE`). That doesn't cover the Draftout content or the panorama;
  see `NOTICE.md`.
