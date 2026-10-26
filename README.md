# OpenDraft

OpenDraft is a Fabric mod for Minecraft 26.3 that plays lockout bingo with all 316 [Draftout](https://draftoutmc.com) goals.

Every player plays **their own singleplayer world** on the same seed, saved on their own device. Nobody joins
anyone's world, so there is no shared-server lag. A small **synchronizer** only tracks the board: when your game
sees that you finished a goal, it sends one line ("claim goal 7") and the synchronizer tells everyone who got it
first.

No Minecraft or Microsoft account is involved. Players are known by a display name, so dev clients and offline
setups work.

## Installing

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 26.3 (loader 0.19.3 or newer).
2. Download [Fabric API](https://modrinth.com/mod/fabric-api) for 26.3.
3. Download `opendraft-<version>.jar` from the [Releases](../../releases) page.
4. Put both jars in your `mods` folder:
   - macOS: `~/Library/Application Support/minecraft/mods`
   - Windows: `%appdata%\.minecraft\mods`
   - Linux: `~/.minecraft/mods`
5. Start Minecraft with the Fabric profile.

Everyone in a game needs the **same OpenDraft version**. To build the jar yourself instead, see
[Building from source](#building-from-source).

## Playing

1. On the title screen, press **OpenDraft** (next to Singleplayer). This opens the lobby screen.
2. One player types a name and presses **Create Lobby**. Their game runs the synchronizer, and the lobby shows an
   invite address with a **Copy** button.
3. Everyone else types their name and that address and presses **Join**. Names don't need a Minecraft account, and
   duplicate names get a number added.
4. The lobby lists everyone who has joined, the settings and a chat. The first player in the lobby is the leader
   (marked with a star). Only the leader can press **Edit Settings** and **Start**.
5. **Start** makes everyone's game create a new singleplayer world with the same seed. When every world has loaded,
   the board is dealt (or drafted) and the lookout begins.
6. After the game, press **Back to Lobby** on the full-screen board to leave the world and return to the lobby for
   the next match.

The first claim to reach the synchronizer wins the goal. The game ends once the leader can't be caught, when every
goal is claimed, when the time limit runs out, or when everyone else has forfeited. The winner sees **VICTORY**
and everyone else sees **DEFEAT** (or **DRAW**).

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

On the steppers, shift-click changes the time limit, lookout and turn length by 1 instead of 5. When time runs out
the leader wins; on a tie, overtime starts and the next goal claimed wins. If overtime also runs out, it's a draw.

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

- **Forfeit** (press it twice to confirm) gives up. Your claims stay on the board, but you can't claim more. When
  only one player is left, they win.
- **Offer Draw** asks everyone else. When every player still in the game has accepted, the game ends in a draw.
  Any goal claimed cancels the offer.

`/opendraft forfeit`, `/opendraft draw` and `/opendraft draw decline` do the same from chat.

### Lookout

When a board starts, everyone is put in spectator mode 20 blocks above the highest ground nearby, so nobody is
stuck inside a mountain. You're held there for the lookout (30 seconds by default) and can look around but not
move. Hold right-click to zoom like a spyglass. The last 5 seconds count down in the middle of the screen, then
you're put back where you were in your old game mode and the clock starts. Nothing counts during the lookout.

### Draft

With the board set to Drafted, the synchronizer deals a pool of twice as many goals as the board needs. Players take
turns in join order: the first turn picks 1 goal and every turn after picks 2. The board opens on your turn; click a
goal to pick it, or use `/opendraft pick <n>` with the numbers from `/opendraft board`. A goal can only be picked once,
and when a turn runs out of time the remaining picks are made at random. The board is shuffled once it's full.

### Chat

Advancements anyone earns appear in everyone's chat, like on a shared server. Death messages and other chat stay in
your own world. The lobby has its own chat.

### The board

A small icon grid sits in the top-right corner (it moves down while you have status effects, so it doesn't cover
them). Your claims are cyan; other players get red, yellow, green and so on in join order. Unclaimed goals are grey.
Goals with a number show it on the icon. The scores and the clock sit under the grid: time left when there's a
limit, time played otherwise.

Press **B** (rebind it under Controls, in the OpenDraft category) for the full-screen board. Hover a goal to see its
name, who claimed it and, for goals that need several things, your progress (for example "Your progress: 3/5").
The game keeps running while it's open.

### Commands and the standalone synchronizer

The lobby screen covers everything, but the old commands still work inside a world:

- `/opendraft host [port]` and `/opendraft join <address>[:port] [name]` start or join a game for the world you're in.
- `/opendraft start [size] [minutes] [overtime]` deals a board and `/opendraft draft` starts a draft.
- `/opendraft settings` shows the settings, and `/opendraft settings size|time|overtime|lookout|turn|mode ...` changes
  them.
- `/opendraft board` lists the goals in chat, `/opendraft goals` shows which goals this version can deal,
  `/opendraft hud` toggles the small board and `/opendraft leave` leaves the game.

The synchronizer can also run on its own, so the lobby keeps running when the player who created it closes Minecraft.
Run the same jar from Releases with `java -jar opendraft-<version>.jar [--port 25570] [--seed <seed>]`. This needs
Java 25 but not Minecraft.
Players then press **Join** with its address.

### Reaching the synchronizer

On the same network, use the address the lobby shows. Across the internet, forward the port (TCP 25570 by default)
or use a tunnel such as Tailscale, ZeroTier or playit.gg.

### Panorama

The lobby and title screen use the "Sinkhole" panorama by sumone_lol, from
[Main Menu Panoramas](https://modrinth.com/resourcepack/noice-panoramas), in
`assets/minecraft/textures/gui/title/background`. It's licensed CC BY-NC 4.0, so it's for non-commercial use only.
Delete those six images to get vanilla's panorama back.

## Goal icons

The board uses Draftout's goal art when it's packed into the mod and falls back to an item icon otherwise. To pack
the art, run this before building:

```
pip install pillow
python3 tools/fetch_icons.py
```

The icons belong to Draftout. Ask them before you share a jar that includes them. They're listed in `.gitignore`,
so they never get committed.

## Building from source

Most players should use the jar from [Releases](../../releases). To build it yourself, you need JDK 25:

```
./gradlew build
```

The jar is written to `build/libs/`; install it the same way as in [Installing](#installing). On GitHub, the Build
workflow in `.github/workflows/build.yml` runs this on every push and attaches the jar to the run (under Artifacts).
That jar has no Draftout icons, so goals show item icons. If Gradle can't resolve the Loom or loader versions in
`gradle.properties`, copy the current values for 26.3 from <https://fabricmc.net/develop/>.

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

## Limits

- Ties go to whichever claim reaches the synchronizer first, so being closer to it is a slight edge.
- State isn't saved. If the synchronizer closes, the game ends; rejoining while it runs replays the current board.

## Credits

- Goals and goal rules: [Draftout](https://draftoutmc.com) and the
  [Draftout wiki](https://github.com/draftout/draftout-wiki). The icons are Draftout's and aren't included here.
- Lobby and title screen panorama: "Sinkhole" from [Main Menu Panoramas](https://modrinth.com/resourcepack/noice-panoramas)
  by sumone_lol, under [CC BY-NC 4.0](https://creativecommons.org/licenses/by-nc/4.0/) (resized to 1024x1024). It's
  for non-commercial use only.
- The mod's code is under the MIT license (see `LICENSE`). That doesn't cover the Draftout content or the panorama;
  see `NOTICE.md`.
