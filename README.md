# ELDRITCH BATTLES v0.2.6 (experimental Paper plugin)

A source update for **Paper 1.21.11**, Java 21. Builds a single JAR on GitHub Actions. This is NOT tested on a running Paper server and needs a successful GitHub Actions compilation before deployment.

## New in v0.2.6

- **Personal kit layout editor:** Any player can use `/editkit sword` or `/eb kit edit sword` (replace `sword` with a saved kit name). A 6-row GUI previews the 27 main inventory slots and the 9 hotbar slots. Click a slot, then another slot, to swap their positions. Click **SAVE PERSONAL LAYOUT** to store it. **RESET TO KIT DEFAULT** previews the original layout; click Save afterward to keep the reset. **CANCEL** discards edits.
- **Per-player, per-kit storage:** Layouts are saved in `plugins/EldritchBattles/kit-layouts.yml`. They are 36-slot permutations of the shared template, not copies of item stacks. Everyone receives the same shared kit contents, but in their own saved slots at the start of **every round**. Other players and the shared kit are not changed. Armor and offhand remain as configured by the admin.
- **Custom-arena regeneration:** As before, all saved custom arenas reset to their saved snapshots **before each round** and **after completed matches**. Now they also reset after an **aborted match**. Admins can trigger `/eb arena reset test` to manually restore the `test` arena while it is idle. If the arena has no saved snapshot/corners, first repair it with `/eb arena inspect test` and `/eb arena save test`.
- **World (random coordinates) is the only no-regeneration arena.** Overworld changes during World mode are not automatically undone.
- Existing `kits.yml`, arena snapshots, spawn points, world config, and the `world-duels.world: unstable` setting on an existing server are **not changed** by installing the JAR. Always back up files before updating.

### Personal layout example

1. Admin creates a shared kit once: `/eb kit save sword`.
2. Player A runs `/editkit sword`, clicks hotbar slot 1, clicks hotbar slot 3, then clicks **SAVE PERSONAL LAYOUT**.
3. Player B does nothing and continues receiving the original kit slot order.
4. Both join a Sword duel: the plugin places the shared kit items in Player A's custom layout and Player B's default layout. Players can reset their preference at any time.

## Features carried over from v0.2.5

- **World arena option** in the arena picker. It selects random safe ground in the normal overworld, instead of using your saved castle arena. No pos1/pos2 or spawn markers needed for this special option. Each fight picks a random region, with teams/FFA distributed around that region.
- **No regeneration for World fights.** World duels do **not** modify the overworld's shared world border. **WARNING: world terrain changes and placed blocks can remain**, unlike saved custom arenas. Test away from builds. Generated chunks may take a moment to load.
- **Party leader head** on `/eb menu` opens a member-management GUI. Left-click other member = kick; right-click = transfer leader. Leader only, outside matches. The owner head itself is not kickable.
- **Party visibility**: sign in `/eb menu` toggles public/private. A public party can be joined using `/party join <owner>` even without an invitation; private parties require one.
- **Broadcast party** goat horn: broadcasts a pink message with a clickable green Join link, once every 30 seconds. Party must be public.
- **Clickable party invitations** in chat rather than relying on players to type the command.
- **Clickable direct duel requests** in chat with kit/mode, arena, number of rounds, Accept and Decline; **no request popup**. Existing `/duel PlayerName` or `/eb duel PlayerName` GUI lets the host choose the kit and arena first.
- **More durable arena settings**: saves positions and spawn markers in both `arenas.yml` and `plugins/EldritchBattles/arena-anchors/<id>.yml`. If location objects fail to deserialize, attempts the backup; if corners are missing and a snapshot exists, tries recovering the bounds. Does not reconstruct a deleted or corrupted snapshot or missing world blocks.
- **Faster custom-arena resetting**: instead of wiping the entire rectangular region to air and rebuilding it, it removes tracked player-placed blocks and restores baseline blocks only where they've changed. It still scans baseline blocks in batches; custom chests are compared too. Large arenas still need testing.

## Commands

| Command | Description |
|---|---|
| `/eb menu` or `/party` | Party home screen |
| `/party create` | Start a party |
| `/party invite <player>` | Invite player; clickable chat reply |
| `/party join <owner>` | Join invited party or public party |
| `/party open`, `/party close` | Make party public or private |
| `/party broadcast` | Pink public invite with clickable join |
| `/party kick <member>` | Remove member (owner only) |
| `/party promote <member>` | Transfer ownership (owner only) |
| `/party leave` | Leave/disband |
| `/duel <player>` or `/eb duel <player>` | Direct 1v1 request with kit and arena selection |
| `/eb arena list` | Show custom saved arenas plus special World option |
| `/eb arena inspect <id>` | Diagnose missing corners, spawns, snapshot |
| `/eb arena recover <id>` | Recover custom arena selection from existing snapshot |
| `/eb arena pos1 <id>`, `pos2`, `spawn <id> 1`, `spawn <id> 2` | Setup a custom arena once if missing |
| `/eb arena save <id>` | Save a custom arena snapshot |
| `/eb arena reset <id>` | Manually restore an idle saved arena from its snapshot |
| `/eb kit save <id>`, `/eb kit icon <id> [material]` | Manage saved kits and menu icons |
| `/editkit <id>` or `/eb kit edit <id>` | Open your personal layout editor (all players) |

## Installation / migration

1. **STOP SERVER, BACK UP EVERYTHING FIRST**, especially your arena world (`eldritch_test`) and `plugins/EldritchBattles/` folder. Changing plugin JARs must not delete these.
2. Extract this ZIP. Upload the files **inside** `eldritch-battles-v0.2.6/` to the root of your existing GitHub repository, replacing existing source and workflow files (but not adding another nested folder).
3. Commit. Go to **GitHub Actions → Build Eldritch Battles**. A *green* build should expose the `EldritchBattles-JAR` artifact. Download and extract it.
4. Stop your MCServerHost Paper server. Replace the **old JAR only**, not the data folder, and restart.
5. Check `/plugins`, `/eb arena list`, `/eb arena inspect test`, test a short 1v1 **with spare items and a backed-up world**.
6. If `/duel` belongs to EpicDuels, use `/eb duel <player>` for this plugin. If `/party` is owned by another plugin, use `/eb party ...`.
7. If the old version ALREADY lost the corners AND original snapshot is missing, **one-time** setup of pos1, pos2 and `/eb arena save test` is still required. v0.2.6 can't reconstruct deleted world data.
8. Disable old `eldritch_no_break.sk` if it prevents breaking player-placed shulkers.

## Important limitations

- Only the server is authoritative for parties and games. Invitations, public status, active reports, and match state are in memory, not persistent after restart.
- World duels use synchronously loaded/generated chunks. This can pause a busy server while locating safe ground. World matches are limited to one at a time; there is no per-player world border.
- Safe-spawn checks are conservative but cannot guarantee every modded/custom terrain is harmless. Test in a copy of the world.
- Faster snapshot resets restore baseline nonair blocks and tracked player placements; exotic fluid/redstone/physics or edits by other plugins can escape these protections.
- Direct duel requests expire after 60 seconds. `/eb stop` remains a host-only termination, not a proper forfeit.
- This version has not been compiled with the Paper dependency in this environment; GitHub Actions is the required compilation check. Share the first `error:` lines if it fails.
