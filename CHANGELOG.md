# Changelog

Every player and the synchronizer need the same version of the mod.

## 0.6.0

### Renamed to OpenDraft
- The mod is now called OpenDraft. Commands start with `/opendraft` instead of `/lockout`, the title-screen button
  says **OpenDraft**, and the jar is `opendraft-<version>.jar`.
- Settings carry over from the old `config/lockout.properties`. The board key needs to be set again if you changed it.

### Seed filter
- The lobby's World tab has toggles for what a random seed must have near spawn, and how near: Village, Shipwreck,
  Outpost, Witch Hut, Desert Temple, Jungle Temple, Igloo, Mansion, Ruined Portal, Trail Ruins, Abandoned Camp,
  Ancient City, Trial Chambers and Pale Garden.
- When the leader presses Start, their game checks random seeds for up to a minute and starts the match with the
  first one that has everything. The lobby chat says how far each thing is from spawn. Stop Search cancels it.
- If nothing turns up, the lobby shows how often each choice appeared, so you know which one to turn off or how far
  to raise the distance.
- The search works out where spawn is and where the game would place each structure, then checks the biome there,
  without generating any terrain. A typed seed skips the filter.
- `/opendraft seed` prints where the search thinks spawn and each structure are in the world you're in, to compare
  with `/locate`.

## 0.5.0

### Lobby
- A button next to Singleplayer on the title screen opens the lobby, where you can create a lobby or join one by
  address.
- The lobby lists everyone who has joined, marks the leader, shows an invite address with a Copy button, and has its
  own chat.
- The leader's **Edit Settings** screen has a Match tab (random or drafted board, size, time limit, overtime,
  lookout, draft turn length) and a World tab (seed). Everyone sees the leader's settings.
- **Start** makes everyone's game create a fresh world with the same seed. The board is dealt once every world has
  loaded, and players are held in the air until then.
- **Back to Lobby** on the full-screen board saves and quits to the lobby for the next match.

### Forfeit and draw
- **Forfeit** on the full-screen board (press it twice) gives up. You keep your claims but can't claim more. When
  only one player is left, they win.
- **Offer Draw** asks everyone else. When everyone still playing accepts, the game is a draw. A new claim cancels
  the offer.

### Other changes
- Hovering a goal on the full-screen board shows your progress on goals that need several things, such as
  "Your progress: 3/5".
- The end of the game shows **VICTORY**, **DEFEAT** or **DRAW** in large text, with the score underneath.
- The lookout puts players in spectator mode 20 blocks above the highest ground nearby, so nobody is stuck inside a
  mountain. Hold right-click to zoom. Your game mode is restored when the clock starts.
- Chat hints name the key you bound for the full-screen board.
- The settings command shows one labelled line per setting.
- The lobby and title screen use the "Sinkhole" panorama by sumone_lol (Main Menu Panoramas, CC BY-NC 4.0).

## 0.4.0

### Board
- The small board sits in the top-right corner and moves down while you have status effects.
- A key (B by default, under Controls) opens a full-screen board. Hovering a goal shows its name and who claimed it.
- Goals with a number, such as "Obtain 64 Arrows", show that number on the icon.

### Chat
- When anyone earns an advancement, everyone sees the usual message. Death messages and other chat stay in your own
  world.

### Time limit and overtime
- Settings for board size, time limit, overtime, lookout length, board mode and draft turn length, saved between
  games.
- When time runs out, the leader wins. On a tie, overtime starts and the next goal claimed wins. If overtime runs out
  too, it's a draw. The clock turns red in overtime.

### Lookout
- At the start, everyone is held in the air for 30 seconds to look around. The last 5 seconds count down on screen,
  and nothing counts towards goals until the clock starts.

### Draft
- Players can pick the board in turns: the first turn picks 1 goal and every turn after picks 2. A goal can't be
  picked twice, and a turn that runs out of time is finished with random picks.

## 0.3.0

- All 316 Draftout goals, defined in `goals.json` from the rules on the Draftout wiki. Counting variants that's 463
  board entries; each board deals one random variant of a goal, and tiers of the same goal never share a board.
- Statistics and advancements count from the start of the board.
- A goal whose items, mobs or advancements don't exist in the running version is never dealt.
- The synchronizer can run on its own: `java -jar opendraft-<version>.jar --port 25570 [--seed N]`.
- Players are identified only by display name; duplicate names get a number added. No Minecraft account is needed.
- Late joiners get the board, the claims and the timer.
- Claims carry a board number, so a late claim from an old board can't land on a new one.
- Lines are capped in length and the handshake times out, so a bad connection can't hang the synchronizer.
- `tools/fetch_icons.py` packs Draftout's goal icons into the mod; goals without one use an item icon.

## 0.2.0

- A 5x5 icon board on screen. Your claims are cyan, each other player gets their own colour, and unclaimed goals are
  grey. Scores and the match timer sit under the grid.

## 0.1.0

- Every player plays their own singleplayer world on the same seed. One player's game runs a small synchronizer over
  plain TCP (port 25570 by default), and the first claim to reach it wins the goal.
- The synchronizer checks that everyone has the same seed and the same mod version.
- No permissions are needed, so the commands work with cheats off.
