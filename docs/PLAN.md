# Plan

## Goal

An autonomous Fabric 26.1.2 client mod for Galatea that:

1. finds every mudworm spot (Floor Drop) the client can know about, and patrols for the rest;
2. plans a path to each spot that walks short distances and etherwarps (AOTV, Ether Transmission) long ones;
3. never tries an etherwarp whose look ray would stop on anything but the planned block (no walls);
4. left-clicks the spot block, confirms it was used, and moves on.

## Phases

| # | Phase | Status |
|---|---|---|
| 1 | Research: spot mechanics, etherwarp rules, 26.1.2 APIs, pathfinding (see `RESEARCH.md`) | done |
| 2 | Project skeleton: Gradle/Loom 1.15, Fabric API 0.145.4+26.1.2, CI that builds and publishes the jar | done |
| 3 | Spot detection: entity scan of string item displays (3 per block) + `happy_villager` particles, remembered sites | done |
| 4 | Etherwarp model: item check (`ethermerge`, `tuned_transmission`), strict target rules, DDA ray, aim cone | done |
| 5 | Pathfinder: time-sliced A* over standing cells, walking moves, lazy etherwarp moves from a per-chunk landing index | done |
| 6 | Macro: state machine, walking control, sneak + verify + click warps, left-click spot, retries, skip list | done |
| 7 | Patrol: remembered sites first, then a grid over the patrol area | done |
| 8 | HUD, boxes, commands, settings file, safety stops | done |
| 9 | Build green on CI | after push |
| 10 | In-game testing (you): checklist below | to do |
| 11 | Tuning from the test results: `etherCost`, `jitterDeg`, click type, patrol step | to do |

## Design decisions

- **Detection by entities, not just particles.** Particles only arrive within a short range. The string displays are
  real entities and are kept as long as the server tracks them, so a full scan of the loaded entities each half
  second finds more spots and gives a clean "used" signal (the strings vanish).
- **"Never through walls" is enforced twice.** At planning time every warp must pass the aim-cone test. At click
  time the real ray is traced again from the real position and rotation, and the click is only sent if it matches.
- **Conservative block rules.** Odin, Firmament, Skyblocker and NoFrills disagree on carpets, slabs, heads, ladders and
  plants. The macro only lands on full non-interactable cubes with air above, and only lets the ray pass air and
  collision-free non-fluid blocks. This loses a few possible hops but never fails a warp because of a block rule.
- **Sneaking eye 1.27.** Galatea is a modern-only island. The macro sneaks for 200 ms and stands still before
  clicking, so the server raycasts from the same place the client did.
- **Left-click by default.** You asked for it, and the AOTV has no left-click ability.
- **Stop instead of fight.** Any teleport the macro did not cause stops it, because that is what a staff check looks like.

## In-game test checklist

Test in a quiet lobby first. Run `/mudworm debug` to see every planning step.

1. `/mudworm spots` next to a spot lists it, and a green box shows on it. Walk 40+ blocks away: the box stays while
   the strings are still loaded.
2. Stand within 4 blocks of a spot and `/mudworm start`: it should click without moving. Check the spot disappears
   and whether `FLOOR DROP!` appears. **If left-click does nothing, report it** (see README "Known unknowns").
3. A spot 10–15 blocks away on flat ground: it should walk (no warp).
4. A spot 40+ blocks away: magenta line, it sneaks, settles, teleports, lands on the box, then walks the rest.
5. Put a spot behind a wall or tree: it must never warp into the wall. It should walk around, or warp to a block
   with a clear line.
6. Set `/mudworm range 30` to force more hops and watch for any warp that lands somewhere unexpected.
7. Run out of mana: after 3 failed warps it says it is walking for 20 s.
8. `/warp hub` while running: it must stop ("the world changed").
9. With no spots around: it patrols the Marsh grid and the remembered sites.

Report the debug lines for anything wrong: the status in the HUD, "Re-planning: …", "No path (…)".
