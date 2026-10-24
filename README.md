# Mudworm Macro (Fabric, Minecraft Java 26.1.2)

A client-side Fabric mod for Hypixel SkyBlock's **Galatea**. It finds every mudworm spot (the game calls them
**Floor Drops**), gets to each one with a chain of **Aspect of the Void Ether Transmission** hops (etherwarp, no
walking), and left-clicks the spot block. It runs on its own until you stop it.

> **Warning:** automating gameplay breaks Hypixel's rules and can get your account banned. You use this at your own
> risk. The code has **not been compiled locally or tested in-game**: the sandbox it was written in could not reach
> the Fabric maven, so GitHub Actions is the only builder. Some Minecraft 26.1.2 method names are taken from the
> Fabric API sources and your other 26.1.2 mods, not checked against the game jar.

## What a mudworm spot is

- A spot is a **grass block in the Moonglade Marsh** with **three item display entities showing string** inside it
  (the "strings popping out of the ground") and green `happy_villager` sparkles above it. Skyblocker's Floor Drop
  Highlighter uses the same two signals.
- One click uses the spot. It gives a **Mudworm Shard** (`SHARD_MUDWORM`) or logs. The chat line is
  `FLOOR DROP! You found <name> Shard on the ground!`.
- Spots also exist in the Torrhus Canyon and the Critter Safari. The default patrol area is the Marsh
  (about x −708…−603, z −46…77).

The full research (sources, conflicts, open questions) is in [`docs/RESEARCH.md`](docs/RESEARCH.md). The build plan and
in-game test checklist is in [`docs/PLAN.md`](docs/PLAN.md).

## How it works

1. **Finding spots ("scanning the island").** Every half second the mod goes through *every* item display entity the
   client has loaded, groups the string ones by block, and records each block with 3 strings as a spot. That covers
   everything inside the server's entity range, not only what is close enough to send particles. Particles are also
   watched as a second trigger. The server never sends the far side of the island, so when no spot is known the
   macro waits for one to come into view; if none shows up and you do not move for 3 s it runs the stuck command (`/warp murk`).
2. **Going to the closest spot: etherwarp only, no walking, no route search.** It picks the nearest spot it can see is
   alive right now and within 120 blocks and 45 blocks up/down (`/mudworm maxdist <blocks>` sets the distance) and heads for it. Spots farther away are never
   targeted: the game keeps their strings loaded from 100+ blocks away, and hopping towards them (or towards ones on
   another level) just looks like teleporting around for no reason. **After every etherwarp it checks again** and, if another spot is now the nearest, switches
   to that one. It collects it, then picks the next nearest. (Spots it remembers but cannot see are not
   targets; they may be used up.) If it cannot find a way to a spot it gives that one up for a minute and takes the next.
   It heads straight for it. Each hop is found by looking at what real look rays do: many yaw/pitch angles around the
   direction of the spot are traced with the same ray the server uses, every ray that ends on a valid etherwarp
   target (never a wall) is a possible hop, and the one whose landing is closest to the spot wins; its angles are then
   refined in small steps. This works on flat ground (where the ray you aim at a block lands several blocks before it)
   and in forests alike. Hops are chained until a landing is in click range of the spot (4 blocks, clear view; plants do
   not block it); if a route ends short, it plans again from where it landed (up to 6 times per spot). It runs on its
   **own thread** over a terrain snapshot that the game thread fills 3 ms per tick, so planning never lags the game.
   Before it spends a hop on a spot, it clicks any spot already in reach from where it stands (also after every landing and
   every click), so clusters of spots cost no extra teleports.
   **Scoot:** a visible spot within 6 blocks (flat distance) that is not in nuke reach yet is not teleported to: the
   macro holds whatever combination of W/A/S/D points at it, relative to where you already look, so the camera never
   turns. It first checks the straight line (solid ground the whole way, nothing in the way, no hazards, so it cannot
   walk you off an edge); if it is blocked or makes no progress for 0.7 s it gives up and teleports instead. Change the
   range with `/mudworm scoot <blocks>` (0 = off). One scoot lasts at most 1.5 s (`/mudworm scoottime <seconds>`), and
   when it ends the "not moving for 3 s -> /warp murk" timer starts again from zero.
   **Unreachable spots:** if no way to a spot can be found, it is paused for 45 s, and if there is no way a second time it is blacklisted for the lobby (red box), so the
   macro never loops on it, also not after a `/warp murk`. When the live (non-blacklisted) spots drop below the minimum, the
   lobby refresh below gets a fresh lobby.
   **Lobby refresh:** if the lobby has fewer than 3 live spots (`/mudworm minspots <n>`, 0 = off), once its spots had
   6 s to load, the macro runs `/is`, waits for the island, pauses 3.5 s and runs `/warp murk`, which gives a fresh
   lobby. At least 45 s must have passed since the last lobby switch (`/mudworm refreshcooldown <seconds>`), so it never
   switches too quickly; if the cooldown has not passed it carries on with what is there. If 8 fresh lobbies in a row
   have too few spots, it stops (lower `minspots`).
   **Slower hops, less mana:** every teleport waits an extra 250 ms once it is lined up (`/mudworm hopdelay <ms>`).
   **Chained hops:** the macro stays crouched between hops and re-aims straight from the landing, so a long trip is
   a quick series of teleports.
   **Stuck:** if you have not moved 1 block in 3 s while it should be moving, it runs `/warp murk` (then waits for the
   teleport and carries on). After 3 warps in a row without finishing a spot it does the hub routine instead (`/hub`, pause, `/warp galatea`) for a fresh lobby; it only stops if 8 lobby changes in a row did not help. Change with
   `/mudworm stuckcmd <command>`, `stucktime <seconds>` or `stuckwarp` (off).
   **Walls and spots:** spots with a clear view are preferred. A spot is "walled in" when no landing within reach
   has a clear line to it; those count 25 blocks further away, so they are only taken when nothing else is around
   (grey box). The goal landing must have a clear view of the spot; only if there is none does it settle for a
   landing within reach on the other side of a wall (the nuker still breaks through it).
3. **Etherwarp, never through walls.** The server takes the *first* block the look ray stops at as the target, so a
   wall in the way becomes the target. The mod makes sure that first block is always the planned one:
   - **Target:** a full solid cube that is not interactable (no chest, door, trapdoor, gate, crafting table…) with
     *literal air* in the two blocks above it. This is the strictest rule set of the mods that predict etherwarp
     (Odin, Firmament, Skyblocker, NoFrills).
   - **Ray:** an exact voxel walk (DDA) from the sneaking eye (feet + **1.27**). Only air and blocks without collision
     (grass, flowers) let it through. Fluids, signs, banners, heads, pressure plates and tripwire stop it.
     `/mudworm strict` makes it air-only.
   - **Cone:** the centre ray must stop at the planned block, and 4 rays tilted by `coneDeg` (0.1°) must also end on a valid
     target or fly out of range (never a wall). On shallow angles over flat ground the landing can shift a few blocks,
     so after each hop the route is re-planned from where you really are.
   - **Before every click:** the mod stands still, sneaks for at least 200 ms so the server knows, and traces the
     *real* ray again from the *real* position with the *real* yaw/pitch. If it does not stop at the planned block,
     nothing is sent. It re-aims, or plans again.
   - **After the click:** it waits for the teleport, checks it landed on the planned block, then continues. Three
     warps in a row that do nothing (no mana, server refused) make it run the stuck command (`/warp murk`); after 3 of those in a row, the hub routine.
   - **Range:** 57 + the item's `tuned_transmission` (Transmission Tuners, max +4 = 61), minus a 1.5-block margin.
   **Axe:** hold your axe and run `/mudworm axe`: it takes the name of the item in your hand and turns the axe on;
   `/mudworm axe off` turns it off; `/mudworm axe name <text>` sets a shorter name by hand (which hotbar item
   (any item whose name contains the text, default `Helix Chopper`, not case sensitive). It is held while nuking; the Aspect of
   the Void is picked again for the next teleport.
   **Blatant mode (off at every start):** etherwarps normally turn the camera to the shot angle. In blatant mode the
   camera does not move at all: the use packet is sent with the shot angles and the camera is left alone. That is much
   easier for anti-cheat and other players to spot, so it needs two steps: `/mudworm blatant unlock` (shows the warning),
   then within 60 s `/mudworm blatant on I understand this is blatant`. `/mudworm blatant off` turns it off. The HUD shows
   it while on.
4. **Nuking: any spot in reach, not only the target.** A nuker runs the whole time: every 3 ticks (150 ms) it breaks
   the nearest known spot within reach (4.6 blocks), whether or not it is the one being teleported to, so spots on the
   way or next to the target go too. No aiming, no turning the head, no line-of-sight check (walls do not matter). A
   spot counts as collected when its strings vanish or the block breaks. If a spot is still there 2 seconds after the
   first break, it is **blacklisted for this lobby** (red box). The blacklist is cleared when the lobby changes (the lobby code on the sidebar), not by `/warp murk`.

## Requirements

- Minecraft Java **26.1.2**, Fabric Loader ≥ 0.18, **Fabric API 0.145.4+26.1.2**, Java 25.
- An **Aspect of the Void** (or Aspect of the End) **with Ether Transmission** (the item's `ethermerge` tag) in your
  **hotbar**. Without it the macro does not start.
- **Lots of mana.** Every hop costs 180 mana (before reductions), and there is no walking to spend the regen time. It
  waits when it says "not enough mana", so mana regeneration sets your speed.
- **Sneak must be "Hold"**, not "Toggle", in Controls. The macro holds the sneak key.
- Be on Galatea in the Moonglade Marsh (or set your own patrol area).

## Build

```
gradle build      # JDK 25, Gradle 9.4+
```

The jar lands in `build/libs/`. Every push also builds on GitHub Actions and publishes the jar as the
`latest-build` pre-release.

## Updating

On your first world join of each launch the mod quietly checks the `latest-build` release of this repo and says in
chat when a newer jar exists. `/mudworm update` downloads it into the mods folder (size and SHA-256 are checked
against the release digest, and the file must be this mod), then restart the game (Modrinth App: Stop, then Play).
The old jar is deleted automatically. `/mudworm update check` only reports. The repo must be public for the check to work.

## Use

1. Go to the Moonglade Marsh with the AOTV in your hotbar.
2. Press **K** (rebindable in Controls) or run `/mudworm start`.
3. It finds spots, teleports to them, and clicks them. It only teleports once it has selected a spot: with none in sight it waits (and runs the stuck command after 3 s without moving). Press **K** again or `/mudworm stop`.

Boxes: green = spot, yellow = current goal, red = blacklisted, magenta line and box = planned etherwarp hops and their targets.

### Commands

| Command | What it does |
|---|---|
| `/mudworm start`, `stop`, `toggle` | Run the macro (key **K** toggles) |
| `/mudworm status`, `spots` | What it is doing and which spots it sees |
| `/mudworm esp`, `hud`, `debug` | Boxes, status panel, debug chat lines |
| `/mudworm strict` | Only air lets the etherwarp ray through |
| `/mudworm stuckwarp`, `stuckcmd <command>`, `stucktime <seconds>` | Warp when not moving (default `/warp murk` after 3 s) |
| `/mudworm failsafe` | Stop when something else teleports you (on by default) |
| `/mudworm range <10-61>` | Base etherwarp range (tuners from the item are added) |
| `/mudworm scoot <blocks>` | Walk (W/A/S/D, no head turning) to spots this close instead of teleporting (default 6, 0 = off) |
| `/mudworm axe` | Takes the name of the item in your hand and uses it (an axe) for mining spots |
| `/mudworm axe off`, `axe name <text>` | Turn the axe off / set the hotbar item name by hand |
| `/mudworm blatant`, `blatant unlock`, `blatant on <phrase>`, `blatant off` | Silent etherwarp aim without moving the camera; two-step confirmation |
| `/mudworm minspots <n>`, `refreshcooldown <seconds>` | Fewer than n live spots in the lobby: `/is` then `/warp murk` for a new lobby (default 3, cooldown 45 s) |
| `/mudworm maxdist <blocks>` | Spots farther than this are never targeted (default 120; spots more than 45 blocks above/below are ignored too) |
| `/mudworm scoottime <seconds>` | Longest one scoot may last (default 1.5) |
| `/mudworm hopdelay <ms>` | Extra wait before every teleport (default 250): slower hops cost less mana |
| `/mudworm ethercost <n>` | Cost of one hop in the planner |
| `/mudworm area marsh`, `area here <radius>` | Patrol area |
| `/mudworm yband <below> <above>` | How far hops may stray up/down from the way to the spot (default 10 / 12). Hops may only land in the corridor between you and the spot (plus 30 blocks sideways), so it never wanders onto trees, cliffs or islands that are not on the way |
| `/mudworm blacklist`, `blacklist clear` | Spots given up on in this lobby (reset automatically when the lobby changes) |
| `/mudworm dev` | Copies a full diagnostic report (state, hotbar, spots, planner test, recent events) |
| `/mudworm sites clear` | Forget every remembered spot site |
| `/mudworm update`, `update check`, `version` | Download the newest `latest-build` jar / only check / show the installed version |
| `/mudworm reload` | Re-read `config/mudworm-macro.properties` |

All settings, including `coneDeg`, `rangeMargin`, `reach`, `patrolStep` and `scanMinY`/`scanMaxY`, are in
`config/mudworm-macro.properties`.

### Safety stops

- Something other than its own etherwarp moves you more than 6 blocks in a tick (staff check, `/warp`): it stops.
- The world changes (lobby swap, warp): it stops.
- A screen is open: it pauses and releases every key.

## Code map

| File | Role |
|---|---|
| `MudwormMod` | Entry point: tick, chat, key, commands, HUD, boxes |
| `SpotScanner` | Finds spots (entity scan + particles), remembers sites |
| `Etherwarp` | Etherwarp rules: item, range, target/ray block rules, DDA, aim cone, look angles |
| `path/Pathfinder` | Finds the hops to the closest spot by tracing look rays (no route search), runs on the planner thread |
| `path/WorldCache` | Terrain snapshot (one `Cell` per block) filled on the game thread, read by the planner |
| `path/Cell` | Block facts (solid, air, fluid, hazard, leaves, etherwarp target…) packed in a short |
| `Macro` | State machine: choose → plan → follow → warp → click |
| `HumanAim` | Frame-rate camera movement in whole mouse pixels (from Nablzz Addons), with an exact mode for etherwarps |
| `Hud`, `Chat`, `Config` | Status panel, messages, settings |
| `mixin/ClientPacketListenerMixin` | Particle packets (hooked at RETURN so each packet is seen once) |

## Known unknowns (check in-game)

- Whether **left-click** uses a spot. Skyblocker notes right-click on the block also removes it. If left does
  nothing, the macro will skip each spot after 3 tries. Tell me and I'll add a right-click mode (with an item that
  has no right-click ability, never the AOTV).
- Hypixel's real etherwarp block rules are not public. The macro only uses targets every known model agrees on.
- Respawn time, spots per lobby, and whether spots are per player are unknown.
- The server's entity range on Galatea decides how far the scan reaches.
