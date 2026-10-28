# ELDRITCH BATTLES — standalone Paper plugin (experimental v0.1.0)

A **free, from-scratch alternative** to combining EpicDuels, Custom Teams and tournament Skripts. This is an **uncompiled source project**, not a plug-and-play JAR yet. Build it against Paper 1.21.11, then test on a backup/test server before using it with real players.

## Features implemented in the source

- Party invite/join/leave, up to 16 players, and clickable player-head team-swapping menu
- FFA and unequal teams (1v2, 1v3, 2v4, balanced teams, etc.)
- Named kits stored persistently, including inventory positions, armour and offhand
- Kit selection menu; arena selection menu; best-of-1/3/5/7; friendly fire; item drops
- Void-world arena creation and teleport into the builder world
- Save rectangular 3D arena templates and spawn points; reset blocks in batches between rounds and after a match
- Container inventory snapshots (including placed shulker contents) inside the saved region
- Match win detection, auto-next-round and winner announcement
- Optional 8-minute delay, then 3-minute border shrink each round
- Saved pre-match player inventory recovery across disconnects and restarts

## Limits of this FIRST test version

- Source build and live Paper-server tests **have not happened here**. An actual JAR needs compiling with Paper's API.
- **Does not hook into EpicDuels/Custom Teams** and will not import their saved kit/arena files automatically. You can build your own arena once and save each kit once in this plugin.
- GUI is a normal Minecraft chest menu; it is not the full-screen dialog UI.
- Doesn't implement tournaments, rankings, spectate selection, queue matchmaking, configurable party ready checks or leaderboards yet.
- Arena snapshot resets blocks and container items inside the cuboid. It does not preserve every complex block entity detail (e.g. sign text) or decorative entities, and may not fully handle multi-block physics/redstone structures. Try it on copies first.
- Do not run an actual fight in an arena with other players editing it. The code allows one match at a time per arena.
- Explosion attribution for friendly-fire control may not work for all explosive damage sources; test Crystal/Cart/Anchor modes.
- The snapshot save scans the selected region on the server thread; large arenas can cause a noticeable lag spike. Default maximum is 250,000 blocks; reduce if required.
- Automatic inventory restore doesn't preserve every effect/experience value. Test reconnect recovery before public events.

## Build an installable JAR for free

### Option A — GitHub Actions (no local Java setup)

1. Make a new empty GitHub repository.
2. Unzip this source archive, and upload all the **files inside `eldritch-battles`**, including `.github/workflows/build.yml`, to the new repository. Commit them on the default branch.
3. Open the repository's **Actions** tab, choose **Build Eldritch Battles**, and select **Run workflow** if it hasn't run automatically.
4. After a successful build, click the workflow run and download its `EldritchBattles-JAR` artifact. Extract the `.jar` from the artifact ZIP.
5. Put `EldritchBattles-0.1.0.jar` in your Paper 1.21.11 `plugins/` folder and restart. Verify that `/plugins` lists `EldritchBattles` in green.

If the build fails, the Actions log will show the exact Java line; share it so it can be corrected. This v0.1.0 project has not been remotely compiled.

### Option B — Gradle on your PC

Requires JDK 21 and Gradle 8.x:

```sh
gradle build
```

The JAR will be `build/libs/EldritchBattles-0.1.0.jar`.

Paper 1.21.11 uses Java 21. The build depends only on the Paper API and fetches it from Paper's Maven repository.

## IMPORTANT: test without plugin conflicts

Back up your server first. While testing, **disable EpicDuels lobby protection** and the old `/epb` or tournament Skripts, or use a **separate test server**. This new plugin has its own match and inventory handling; running two fight managers at once could overwrite kits, cancel inventory interaction or block teleportation.

## Start in 8 simple steps

1. In game as OP, run `/eb arena create test` — it creates a void world with a small builder platform.
2. Build or paste a battle arena. Do not include the builder platform in your arena selection unless you want it to reset too.
3. Stand at a **lower corner** of the whole build: `/eb arena pos1 test`. Stand at the **opposite upper corner**: `/eb arena pos2 test`. Include enough Y levels for the entire structure.
4. Stand where the first fighter should spawn: `/eb arena spawn test 1`. Repeat with `/eb arena spawn test 2`. For FFA or 1v3 add `/eb arena spawn test 3`, `/eb arena spawn test 4`, etc.
5. Run `/eb arena save test`. This saves a permanent snapshot (in `plugins/EldritchBattles/snapshots/`).
6. Put on your preferred kit and put its items into the slots you want. Run `/eb kit save sword`. Repeat for each of your seven kits. (If EpicDuels can give you its saved kit, you may use its give command and then save it here.)
7. `/eb party create`, `/eb party invite Friend`, the friend types `/eb party join YourUsername`.
8. `/eb menu`: click heads to swap sides, select FFA if you prefer, **Next**, choose a kit, settings/map, then press green **START MATCH**.

### Command cheat sheet

| Command | Purpose |
|---|---|
| `/eb help` | Show commands |
| `/eb party create` | Make a party |
| `/eb party invite Player` | Invite a friend |
| `/eb party join Host` | Accept invitation |
| `/eb party list` | See who joined and which team |
| `/eb party leave` | Leave/disband |
| `/eb menu` | Team -> kit -> match settings |
| `/eb kit save sword` | Save items, armour and exact slots as a reusable kit (OP) |
| `/eb kit list` | Show saved kits |
| `/eb arena create test` | Create new void builder world (OP) |
| `/eb arena edit test` | Teleport back to it (OP) |
| `/eb arena pos1 test` | Set lower selection corner (OP) |
| `/eb arena pos2 test` | Set upper selection corner (OP) |
| `/eb arena spawn test 1` | Set spawn 1 at current location (OP) |
| `/eb arena save test` | Save region reset template (OP) |
| `/eb arena list` | Show saved arenas (OP) |

## Game rules

Each player gets the selected saved kit at the start of each round. Last living team or last living FFA player wins that round. A BO3 requires 2 round wins, BO5 requires 3; the next round automatically prepares and restores the saved arena. All original inventories are recovered when the match finishes. Set friendly fire, item drops and shrinking border from the settings menu.

In `plugins/EldritchBattles/config.yml`, the border delay is 480 seconds (8 minutes) and the shrink takes 180 seconds (3 minutes); both can be changed after stopping and restarting the server.

**Before inviting friends:** test arena resets, kit inventories, deaths, quit/reconnect and shulker opening using only disposable test items.
