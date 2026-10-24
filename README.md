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
   macro **patrols**: first every block a spot was ever seen on (saved in `config/mudworm-sites.txt`), then a grid over
   the patrol area.
2. **Going to the closest spot: etherwarp only, no walking, no route search.** It picks the nearest spot it can see is
   alive right now and heads for it. **Every 5 seconds it rescans** and, if another spot is now the nearest, switches
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
   **Slower hops, less mana:** every teleport waits an extra 250 ms once it is lined up (`/mudworm hopdelay <ms>`).
   **Chained hops:** the macro stays crouched between hops and re-aims straight from the landing, so a long trip is
   a quick series of teleports.
   **Stuck:** if you have not moved 1 block in 3 s while it should be moving, it runs `/warp murk` (then waits for the
   teleport and carries on). After 3 warps in a row without finishing a spot it stops. Change with
   `/mudworm stuckcmd <command>`, `stucktime <seconds>` or `stuckwarp` (off).
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
     warps that do nothing (no mana, wrong item) make it wait 10 s.
   - **Range:** 57 + the item's `tuned_transmission` (Transmission Tuners, max +4 = 61), minus a 1.5-block margin.
4. **Nuking the spot.** Like a nuker mod it breaks the spot block as soon as it is within reach (4.6 blocks): no
   aiming, no turning the head, no line-of-sight check. It keeps breaking the block every tick until it is gone. Success
   is the block breaking, the strings disappearing or the `FLOOR DROP!` line. Once it is at the final point it has
   **2 seconds** to get the spot; after that the spot is **blacklisted for this lobby** (red box) and the macro moves on.
   The blacklist is cleared when the lobby or world changes.

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
3. It finds spots, teleports to them, and clicks them. It patrols when none are in sight. Press **K** again or `/mudworm stop`.

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
