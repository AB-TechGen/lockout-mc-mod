# OpenDraft

OpenDraft is a Fabric mod for Minecraft 26.3 that plays lockout bingo with [Draftout](https://draftoutmc.com) goals.
Not affiliated with Draftout.

Every player plays **their own singleplayer world** on the same seed, saved on their own device. Nobody joins
anyone's world, so there is no shared-server lag. A small **synchronizer** only tracks the board: when your game
sees that you finished a goal, it sends one line ("claim goal 7") and the synchronizer tells everyone who got it
first.

No Minecraft or Microsoft account is involved. Players are known by a display name, so dev clients and offline
setups work.

OpenDraft is **completely free**. There are no paid features and no servers to rent, and the code is open source
under the MIT license.

## Features

- **Draftout goals**, on a random or drafted board of 3 to 25 goals.  
- **Everyone starts on the same block**: the world's spawn point, instead of vanilla's random spot around it. See
  [Lookout](#lookout).
- **1v1 or a free-for-all** with 3, 4 or more players.
- **Seed filtering**: pick what a random seed must have near spawn, and how near. The options are Village,
  Shipwreck, Outpost, Witch Hut, Desert Temple, Jungle Temple, Igloo, Mansion, Ruined Portal, Trail Ruins,
  Abandoned Camp, Ancient City, Trial Chambers and Pale Garden. See [Seed filter](#seed-filter).
- **A bastion and a fortress near 0, 0 in the Nether**, with nether wart guaranteed in one of them. See
  [Seed filter](#seed-filter).
- **Lava pools near spawn**: 3 pools 50 to 200 blocks from spawn, and surface lava pools twice as common. See
  [Match worlds](#match-worlds).
- **Goals that fit the world**: a goal that needs a biome (scaffolding needs a jungle) is only dealt when that biome
  is within 400 blocks of spawn. See [Match worlds](#match-worlds).
- **RNG Standardization**: drops, bartering, captains, food effects and golem damage follow sequences from the
  world seed, so players on the same seed get the same outcomes in the same order. See
  [RNG Standardization](#rng-standardization-same-luck-for-everyone).
- **Changes to vanilla**: at least 4 pearl trades from the first 16 gold blocks, far less Nether fog, and a first
  Nether exit from up high puts the portal on the surface. See [Match worlds](#match-worlds).
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

### Updates

From 0.16.0, OpenDraft keeps itself up to date:

1. When the game starts, it checks for a newer release on GitHub.
2. If there is one, the **OpenDraft** button opens an update screen instead of the lobby. Press **Update** to
   download it.
3. Once it's downloaded, press **Quit Game** and start the game again. The new version is swapped in when the game
   closes.

Until you update, the button keeps showing the update screen, since a lobby only takes players on the same
version anyway. If the check can't be made (no internet, or GitHub can't be reached), the lobby opens as usual.

- **Signed releases:** every update is signed, and OpenDraft won't install a jar that isn't, even one uploaded to
  this repo's releases page.
- **If the swap fails:** if a launcher stops it, the update screen tells you to close the game, delete the old jar
  from your mods folder and move the new one in from `config/opendraft-update`. It has buttons that open both
  folders.
- **Older versions:** 0.15.2 and earlier don't have the updater, so install 0.16.0 by hand once.

## Playing

1. On the title screen, press **OpenDraft** (under Multiplayer). This opens the lobby screen.
2. One player types a name and presses **Create Lobby**. After a few seconds the lobby shows an **invite code**,
   hidden as `XXXX-XXXX` so it's safe on stream. **Copy** copies it even while it's hidden, and **Show** reveals it.
   Send it to your friends.
3. Everyone else types their name, pastes the code and presses **Join**. Names don't need a Minecraft account, and
   duplicate names get a number added.
4. The lobby lists everyone who has joined, the settings and a chat. The first player in the lobby is the leader
   (marked with a star). Only the leader can press **Edit Settings** and **Start**.
5. **Start** makes everyone's game create a new singleplayer world with the same seed. Each game covers the world
   with "Loading terrain..." until the land around you has loaded (30 seconds at most), then with "Waiting for
   other players..." until the slowest computer has caught up, so nobody gets extra time to look around. Then the
   board is dealt (or drafted) and the lookout begins.
6. When the game ends you're put in spectator mode, and 5 seconds later (counted down above the hotbar) your game
   saves the world and goes back to the lobby for the next match. **Back to Lobby** on the full-screen board goes
   straight away. Your old game mode is saved with the world, so reopening it later from **Old worlds** doesn't
   leave you in spectator.

The first claim to reach the synchronizer wins the goal. The game ends once the leader can't be caught, when every
goal is claimed, when the time limit runs out, or when everyone else has forfeited or left the game (see
[Leaving and rejoining](#leaving-and-rejoining)); then the one still playing wins, whatever the score. The winner sees **VICTORY**
and everyone else sees **DEFEAT** (or **DRAW**). With three or more players, everyone also sees where they placed
and the final scores.

If the game can't create the world or leave a world by itself, it tells you to do that step by hand: create a
world with the seed shown in the lobby, or use Save and Quit to Title.

### Leaving and rejoining

- **Dropped connection:** if your connection to the lobby drops (patchy Wi-Fi, say), you keep playing in your world
  while your game reconnects by itself, usually within seconds. Chat says when it's lost and when it's back.
  Goals you complete meanwhile are sent once you're back, but they count from when they arrive, so a close race
  during a drop can go to the other player.
- **Your place is kept for 5 minutes:** your name, colour, claims and draft turn. Others see you as *(away)*. You
  don't hold up loading or a draw vote meanwhile, and if your draft turn comes up, its time runs out as usual and the
  picks are made at random. After 5 minutes you've left for good.
- **Last one in wins:** once everyone else has left a match for good (Leave Lobby, or 5 minutes away) or forfeited,
  the one player still in it wins, whatever the score. With two players, that's as soon as the other one leaves.
  Someone who's only away doesn't count as gone yet.
- **Leaving the world mid-match:** after Save and Quit, **Join Match** in the lobby takes you back into the same
  world, with your inventory and goal progress as you left them.
- **Closing the game:** if your game closes or crashes, join the same lobby again within 5 minutes, with the same
  name, and you get your place and your world back.
- **Leave Lobby** leaves straight away, so your place isn't kept.
- **If the host's game closes,** the lobby closes for everyone, since the synchronizer runs in it.

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

- Every match is played in a new world, so the lobby has an **Old worlds** button (under the settings, or above
**Back** before you join). It sets how many OpenDraft match worlds to keep: 1, 2, 3, 5 (the default), 10, 20, 50 or
all. 

- When you get back to the title screen, the oldest match worlds past that number are deleted. Only folders that
OpenDraft itself made (named like `OpenDraft 2026-10-26 14_30`) are ever touched, never your other worlds, and a
world that's open is skipped. 

- Each player sets their own, since it's about their own saves folder. It's saved as
`keepWorlds` in `config/opendraft.properties`; any other number can be typed there (0 keeps them all). To keep a
match world for good, rename its folder or copy it somewhere else.

- On the steppers, shift-click changes the time limit, lookout and turn length by 1 instead of 5. When time runs out
the leader wins. On a tie for the lead, overtime starts and whoever pulls ahead first wins. If overtime also runs
out, or every goal is claimed while it's still tied, it's a draw.

### Keybinds

OpenDraft registers three keybinds of its own. A few more features reuse vanilla keybinds or use fixed keys.

### OpenDraft's own keybinds

*Controls → Key Binds → OpenDraft*

| Keybind | Default | What it does | Customizable |
|---|---|---|---|
| Open the board | B | Opens the full-screen board. Pressing it again on the board closes it. | Yes |
| Toggle the small board | Unbound | Hides or shows the small board in the corner. It comes back each match. | Yes (you have to set it first) |
| Toggle fullbright | G | Turns fullbright on or off. | Yes |

### Vanilla keybinds the mod also uses

*Customizable, under their usual vanilla names*

| Vanilla keybind | Default | What it does in OpenDraft |
|---|---|---|
| Use Item / Place Block | Right-click | Hold to zoom like a spyglass during the lookout. |
| Sneak | Shift | During the lookout, drops you to the ground view. |
| Jump | Space | During the lookout, takes you back up to the sky view. |
| List Players | Tab | Shows the lobby list with dimension icons and scores. |

The lookout keys follow your Sneak and Jump keybinds, since the game reads your actual movement input.

### Fixed keys

*Can't be changed*

| Key | Where | What it does |
|---|---|---|
| Enter | Lobby screen | Sends a chat message, or joins when the invite-code box is selected. |
| Shift + click | Lobby settings | Changes time limit, lookout and turn length by 1 instead of 5, and the distance by 10 instead of 50. |

### Seed filter

 - On the World tab, you can select your seed type along with approx distance from spawn: Village, Shipwreck, Outpost, Witch Hut,
Desert Temple, Jungle Temple, Igloo, Mansion, Ruined Portal, Trail Ruins, Abandoned Camp, Ancient City, Trial
Chambers or a Pale Garden, all within the distance you set. 

- When the leader presses **Start**, their game tries random seeds for up to a minute and then starts the match with the first one that has everything.

- The lobby chat says how far
each thing is from spawn. If no seed turns up, the lobby says how often each one appeared, so you can tell which to
turn off. 

- *A typed seed is used as it is, without the filter

- Every random seed also has a bastion and a fortress within 250 blocks of 0, 0 in the Nether (always; it isn't a
setting), with nether wart in one of them: either the bastion is a housing bastion (every one has wart in its
centre) or the fortress has a nether wart room. The distance setting doesn't change the 250. 

- With nothing else asked for, a seed turns up in about a second (roughly one seed in seven
passes).

- The search doesn't generate any terrain. For each seed it works out where spawn is and where the game would try to
place each structure, then checks the biome there, which is the same first test the game makes. That is quick and
right almost every time, but a structure can still occasionally fail to appear for reasons the search can't see. To check it in a world with cheats on, compare `/opendraft seed` (a dev-only command, see below; where the search thinks
spawn and each structure are) with `/locate structure`. In the Nether, use
`/execute in minecraft:the_nether positioned 0 64 0 run locate structure minecraft:fortress` (or
`minecraft:bastion_remnant`); `/locate` gives the corner of the structure's chunk, 8 blocks off from
`/opendraft seed`'s numbers.

### Changes to Vanilla Gameplay

Every lobby match world (made by the lobby, or by hand with the match's seed) has these changes from vanilla, the
same on every player's computer. Random outcomes are also standardized; see
[RNG Standardization](#rng-standardization-same-luck-for-everyone).

- **Lava pools.** Surface lava pools are twice as common as in vanilla, and there are 3 pools 50 to 200 blocks from
  spawn. Natural pools in that ring count towards the 3, and the rest are added on dry land away from villages, as
  part of the terrain. Very occasionally water or cliffs leave no room for one, so it can be 2.

- **Bartering.** The first 144 gold ingots (16 gold blocks) bartered with piglins give at least 4 ender pearl
  trades of 2 to 4 pearls: if you're behind after each 36, that barter gives pearls. More can come; which ones
  depends on the seed.

- **Spawn point.** Everyone starts on the world's spawn point instead of a random spot up to 10 blocks from it
  (see [Lookout](#lookout)), and dying without a bed respawns you there too: the `respawn_radius` game rule is 0.

- **Nether fog** starts and ends much further away, so you can see across the Nether.

- **First Nether exit.** If leaving the Nether for the first time makes a new Overworld portal and you were standing
  at y 55 or higher, the portal is made on the surface instead of in a cave.
  
- **Biome goals.** Goals that need something only a few biomes have are only dealt when one of those biomes is
  within 400 blocks of spawn: scaffolding and bamboo mosaic need a jungle, parrots a jungle, glow berries lush caves,
  powder snow a grove or snowy slopes, and so on. The list is the `biomes` field in
  `src/main/resources/opendraft/goals.json` (each group needs one of its biomes nearby). `/opendraft goals` in a
  world lists which goals that world leaves out and why.

### RNG standardization (Same luck for everyone)

- In vanilla, two players on the same seed still get different luck: one gets a pearl from their first barter, the
other needs ten.  

- In match worlds, the random outcomes that decide a race come from **sequences seeded by the world
seed** instead, so every player gets the same results in the same order. 

- The 3rd diamond ore you mine drops the
same as the 3rd diamond ore your opponent mines, and your 5th barter gives what theirs does. 

- The sequences are saved
with the world, so leaving and coming back doesn't reset them.

Each of these has a sequence of its own:

| What | How it's counted |
| --- | --- |
| Block drops (ores, gravel, leaves...) | Per block type. Each deepslate ore shares its stone ore's sequence (Lapis Ore and Deepslate Lapis Ore count as one), grass shares with fern, and tall grass with large fern. |
| Mob drops | Per mob type. |
| Leather | Cows, horses, donkeys and mules share one sequence just for leather, so killing the same number of them in any order and any mix gives the same leather. Looting still adds more. |
| Piglin bartering | One sequence, by barter. On top of it, the first 144 gold ingots (16 gold blocks) are guaranteed at least 4 ender pearl trades of 2 to 4 pearls (see [Match worlds](#match-worlds)). Any further pearl trades come from the seed. |
| Mob equipment drops | Per mob type, by kill: whether a pillager drops its crossbow, a witch its potion, a piglin its armour, and so on. |
| Pillager captains | By spawn number: if the 2nd and 15th naturally spawned pillagers are captains for one player, they are for everyone. It doesn't promise a captain at a set time or place. |
| Food effects | Per food, by meal: hunger from rotten flesh and raw chicken, poison from a poisonous potato. |
| Iron golem damage | By hit, on players only. In vanilla it varies a lot (7.5 to 21.5 before difficulty). |

- The order is what's shared, not the world around it: your 3rd zombie gives the same drop as theirs, but which zombie is
your 3rd depends on how you play. 

- *Taming (wolves, cats, parrots, horses) isn't standardized yet. Chest loot already
comes from the world seed in vanilla, so the same chest has the same loot for everyone.

### Forfeit and draw

Open the full-screen board during a match:

- **Forfeit** (press it twice to confirm) gives up. Your claims stay on the board, but you can't claim more and they
  stop counting. When only one player is left, they win; with more players left, the game goes on without you.
    
- **Offer Draw** asks everyone else. When every player still in the game has accepted, the game ends in a draw.
  If not everyone has accepted within 30 seconds, the offer is declined (everyone sees it in yellow in chat). Any goal
  claimed also cancels it, and **Cancel Draw** takes your vote back.

### Fair play

Match worlds are created with commands off, on Normal difficulty. 
- During a match, **Open to LAN**, **Allow Commands**,
the game mode buttons and **Edit Game Rules** are greyed out, in the pause menu and in World Options.

- If your world
gets opened to other players or commands get turned on some other way, OpenDraft turns them straight back off. 

- You can
still switch between Easy, Normal and Hard, but picking Peaceful puts the world back on Easy silently. It may still appear as Peaceful in the GUI/Settings menu

### Lookout

Everyone starts on the world's spawn point itself. Vanilla puts each player at a random spot up to 10 blocks
from it, which could be the bottom of a ravine for one player and open grass for another.  
The mod gives
everyone the same spawn point, so in a match world everyone starts on the same block as to be fair.

When a board starts, everyone is put in spectator mode 20 blocks above the highest ground nearby, so nobody is
stuck inside a mountain. You're held there for the lookout (30 seconds by default) and can look around but not
move.  

Hold right-click to zoom like a spyglass. Press **sneak** to drop to where you're standing for the ground view,
and **jump** to go back up to the sky view.   

For the last 5 seconds you're put on the ground and can't go back up,
while they count down in the middle of the screen. Then you get your old game mode back and the clock starts.  

Nothing counts during the lookout.

### Fullbright

Press **G** in a world to turn fullbright on or off (rebind it as **Toggle fullbright** under Controls, in the
OpenDraft category). It sets the game's brightness to 1500%, so caves and nights
are lit, without touching your Brightness setting. It's off again each time the game starts.

### Draft

With the board set to **Drafted**, the synchronizer deals a pool of twice as many goals as the board needs.  

Players pick
in join order and then back the other way (A B C, C B A, A B C...), so whoever ends a round picks twice in a row
and nobody gets ahead.   

With two players, that's 1 goal on the first turn and 2 on every turn after. The board opens
on your turn; click a goal to pick it.  

A goal
can only be picked once, and when a turn runs out of time the remaining picks are made at random. 

The board is
shuffled once it's full.

### Chat

What you type in chat in a world goes to everyone in the lobby, like on a shared server: it shows as `<name>
message`, with the name in that player's board colour and the message in white. The lobby screen's chat is the same
conversation. Advancements anyone earns also appear in everyone's chat. Death messages stay in your own world.

The **Player chat** button in the lobby (under Old worlds) turns this off for you: your chat stays in your own world,
and other players' messages only show on the lobby screen. Each player sets their own; it's saved as `playerChat` in
`config/opendraft.properties`.

### The board

A small grid of goal pictures sits in the top-right corner (it moves down while you have status effects, so it doesn't cover
them). 
- Your claims are always cyan on your screen. Everyone else gets red, yellow, green and so on in the order they
joined, and keeps that colour for the whole game on every other player's screen too. 
- Unclaimed goals are grey.
- The pictures show the goal's number where it has one. 
- The goals with colour variants (wool, concrete, glazed
terracotta, harnesses, cushions, leather armour and sheep) show the item in that colour instead, with the number in
the corner. 
- The scores and the clock sit under the grid: time left when there's a
limit, time played otherwise.
---
**Toggle the small board** (unbound at first; set it under Controls, in the OpenDraft category) hides or shows the
small board; it's shown again at the start of every match. 
- Hold the player list key (**Tab**) to see everyone in the
lobby, like on a server, with an icon for the dimension they're in and their score.

- Your progress on goals that count
unique foods, unique breeds or mob kills shows above the hotbar for a few seconds as it goes up, without filling chat.

- Press **B** (rebind it under Controls, in the OpenDraft category) for the big board, a panel in the middle of the
screen with Forfeit, Offer Draw and Close Board under it. 
- Hover a goal to see its
name, who claimed it and, for goals that need several things, your progress (for example "Your progress: 3/5").
The game keeps running while it's open. 
- The **Board size** slider under the buttons makes the board 50% to 300% of
its normal size; if that won't fit on your screen it's as big as fits, and the slider says "(max)". Each player sets
their own, and it's saved as `boardScale` in `config/opendraft.properties`.

### Commands and the standalone synchronizer

Everything you do in a game has a screen or a key: the lobby, the board and the keybinds. Each match gets one board,
dealt once everyone's world has loaded; to start over, go back to the lobby and press **Start** for a new match. The
only commands are for checking a world:

***For Players***
- `/opendraft goals` lists which goals this world can't deal, and why (content this version lacks, or a biome that
  isn't within 400 blocks of spawn).

- `/opendraft ids <word>` lists block, item and entity ids containing a word, for updating goals.json.  

***For Devs***
- `/opendraft seed [blocks]` shows where the seed search thinks spawn, each structure and the Nether bastion and
  fortress are, to compare with `/locate`. That would spoil exploring, so it's only there in a dev run (`runClient`)
  or with `-Dopendraft.dev=true` in the game's JVM arguments.

The synchronizer can also run on its own, so the lobby keeps running when the player who created it closes Minecraft.
Run the same jar from Releases with `java -jar opendraft-<version>.jar [--port 25570] [--seed <seed>]`. This needs
Java 25 but not Minecraft. It prints an invite code, and players join with that code or the machine's address.

### Playing over the internet

The synchronizer always runs in the game of whoever created the lobby. When you create one, your game tries these in
order to work out how friends can reach it, and gives you the code for the first one that works:

1. ***Relay (main method)***: your game gets a short code like `K7QM-2D9X` from the OpenDraft relay, a small free
service that passes the game's messages between players (see [`relay/README.md`](relay/README.md)). Friends anywhere
join with it, and nobody opens any ports.

2. ***Opening a port on your router***: if the relay can't be reached, your game asks your router to open the port
instead (TCP 25570, or another free port if that one is taken), using UPnP the way game consoles do. **The code is
longer**: it holds your home-network address and your internet address, and joining tries both, so friends in the same
house and friends elsewhere use the same code. ***The port is closed again when you leave the lobby.***

3. ***Same network only***: if the router doesn't open the port either, you still get the long code, but the lobby
says *Only people on your network can join* under it, and names the port. Friends on the same Wi-Fi or hotspot as you
can still join with it, since it holds your home-network address. Some college and public Wi-Fi networks don't let
devices reach each other, so there even that may not work.

- If the host's connection to the relay drops (on a patchy network), the host's game gets the same short code back,
  and the players who joined through it reconnect by themselves (see [Leaving and rejoining](#leaving-and-rejoining)).
  Only the host's game can get the code back: the relay gives it a secret key for that, so another player who knows
  the code can't take the lobby over. If the code can't be got back within 5 minutes, it stops working; leave the
  lobby and create it again for a new code.

---
***Fixes (if the relay doesn't work and the port doesn't open, but you want people outside your network)***

- Either turn on UPnP in your router's settings

- Or forward that port by hand: open the router's page (often `192.168.1.1` or `192.168.0.1`), find Port Forwarding,
  and forward the TCP port to this computer. The code already includes your internet address, so it works once the
  port is open.

- If your connection is behind your internet provider's network (common on phone hotspots, dorms and some
  providers), UPnP and forwarding won't help, and the lobby says *Only people on your network can join* even when the
  router opened the port. Use a tunnel such as playit.gg, Tailscale or ZeroTier instead: friends type your tunnel
  address and the lobby's port (for example `100.64.1.2:25570`) into the join box instead of a code.

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

## Releasing a new version

Only for whoever holds the release key.

1. Raise `mod_version` in `gradle.properties`.
2. Run:
   ```
   ./gradlew releaseFiles
   ```
   This builds the jar and signs it. Both files to upload end up in `build/release/<version>/`:
   - `opendraft-<version>.jar`
   - `opendraft-update.properties`
3. On GitHub, create a release, upload **both** files, and publish it.
   - **Not a pre-release:** don't mark it as a pre-release unless it shouldn't reach players yet. Pre-releases and
     drafts are never offered as updates.
   - **Missing update file:** a release without `opendraft-update.properties` isn't offered either. Players keep
     the version they have.
4. **Taking a bad release back:** delete it. "Latest" then points at the one before. Players who already updated
   stay on the bad one until a newer release comes out.

**The release key** is in `~/.opendraft/release-key.properties`, outside the repo.

- **Back it up somewhere safe**, like a password manager or a USB stick.
- **Never commit it or share it.** Anyone with it can sign an update.
- **If it's lost,** no update can be signed again. A new key (`./gradlew generateReleaseKey`, with the new public
  key put in `Release.java`) only works from the next version on, and everyone would have to install that one by
  hand.
- **On another computer,** copy the key file to the same place, or pass `-Popendraft.releaseKey=<path to it>`.

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
