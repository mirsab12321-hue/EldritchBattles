# Eldritch Battles v0.2.0 — Experimental source release

**For Paper 1.21.11 / Java 21.** This is SOURCE, not a ready-to-install JAR. Java source parsed successfully, but **the code has NOT been compiled against the Paper API or tested on a live server** in this workspace; review the GitHub Actions build log and test on a COPY of your server. Features may still have bugs.

## What's new since v0.1.2

- `/duel <player>` or `/eb duel <player>`: starts a direct duel setup, selects kit (one click takes you to match settings), random or chosen arena, rounds, and sends a 60-second challenge for target to accept/deny. A direct duel requires both players to leave existing parties first.
- `/duel accept <player>`, `/duel deny <player>`, `/duel cancel`.
- `/party ...` shortcut for `/eb party ...`, when another plugin has not claimed the command.
- `/eb menu` creates a party if needed and shows a **Party Menu**, owner player head, **Party Team Battle** netherite sword, and **Disband Party** barrier.
- Team picker -> **Kit** -> **Match Settings**. Clicking a kit automatically proceeds to settings.
- Clock now selects **number of rounds** rather than "best of"; **shift-click** on clock for infinite rounds, or click clock to open an interactive number-of-rounds inventory. `/eb stop` lets the match leader stop an infinite match.
- **Duel Settings** via chain icon: health objective under names, shield stuns, block breaking allowed/blocked, friendly fire, death drops, shrinking border. Placement allowed during active rounds; breaking disallowed by default.
- **MATCH FOUND** title, immediate teleport into arena while reset runs (gravity disabled during preparation), 5-second on-screen countdown, **FIGHT**, **VICTORY / DEFEAT**, **MATCH REPORT** in chat with clickable player inventory snapshots and durability/resource summaries.
- Arena border **center is now set before player teleport**, with border damage disabled during countdown; prevents spawn death due to border remaining centered at world origin.
- `/eb arena inspect <id>` explains `ready=false`; `/eb arena recover <id>` can restore missing selection corners from an existing snapshot *without changing the world blocks* (only if inferred volume is within safety limit).
- Tab completion for `/eb`, `/party`, and `/duel` subcommands and online player names.
- Existing v0.1.2 stored kits, kit icons, arena locations and `.bin.gz` block snapshots use unchanged file formats. **Do not delete the `plugins/EldritchBattles/` data folder or `eldritch_*` world folders.**

## Build & install via GitHub

1. **Stop your Paper server. Back up the full server** (world folders + `plugins/EldritchBattles/` data directory). Existing snapshots are in `plugins/EldritchBattles/snapshots/`.
2. Extract this ZIP and upload the **contents inside** `eldritch-battles-v0.2.0` into your existing GitHub repository. Replace matching `.github/`, `src/`, `README.md`, `build.gradle`, `settings.gradle`; commit.
3. GitHub > **Actions** > **Build Eldritch Battles**. Wait for a **successful** build. The workflow now uses `build/libs/*.jar` and errors when no JAR is found.
4. Download **Artifacts > EldritchBattles-JAR**, extract `EldritchBattles-0.2.0.jar`.
5. On MCServerHost while STOPPED, delete ONLY the older `EldritchBattles-*.jar` file from `plugins/` and upload v0.2.0 JAR. **Keep the plugin data folder and all worlds**. Start server; `/plugins` should show EldritchBattles in green.
6. As OP, run `/eb arena list`, `/eb arena inspect test`. It should show `ready=true` and `spawns=2`. If it says ready=false: first inspect missing data. Only if a `.bin.gz` snapshot exists and position corners were lost try `/eb arena recover test`. Do not run `/eb arena save test` until you have verified a correct selection; save would overwrite the old arena template.
7. Test with disposable kit items, both PvP players online, 1v1 and 1v2, FFA spawn safety, deaths/respawns and inventory restoration before using your live PvP server.

## Command conflicts / Skript

- If **EpicDuels** is still installed, it may own `/duel`; use **`/eb duel <player>`** reliably, or disable EpicDuels (after backup) to allow Eldritch Battles to claim plain `/duel`.
- If another plugin owns `/party`, use **`/eb party ...`**.
- If `eldritch_no_break.sk` is installed, **disable it** to allow the new in-plugin **Block Breaking** toggle to work. That Skript can still block breaks even when the new menu says ON. Rename to `eldritch_no_break.sk.disabled` and run `/sk reload all` or restart.
- Avoid running EpicDuels and Eldritch Battles matches at the same time, as lobby and inventory event handling can conflict.
- Back up before trying `/eb arena recover`, especially if old snapshot/region selection data is missing.

## Important limits

- Screens are native Minecraft inventory GUIs and vanilla title overlays, **not pixel-identical reproductions** of reference server UI. Chat usernames are clickable.
- Inventory report snapshots are retained **in memory for the last 30 matches**; lost on server restart. Kills count direct credited player kills (not all explosion/environment assists). Resource counts are selected combat consumable categories, not every inventory item.
- A duel sends an accept/deny popup **after** the challenger chooses kit/settings and clicks Start. The direct challenger and target must not already be in another party.
- Unlimited rounds continue until host types `/eb stop`.
- Existing snapshot reset deletes and recreates the selected cuboid in batches and can lag on large arenas. Max baseline region 250,000 blocks. Decorative entities and some block-entity metadata are not preserved. Test on copies.
- The arena's world border applies to **the whole arena world** while the match is active, not a separate border per player. Match reports and scoreboard health may interact with other scoreboard plugins.
- Shield stuns are approximated using the shield cooldown API; PvP plugins or mods may override behavior.
- PvP placement and breaking may still be blocked by other protection plugins/Skripts.


### Compilation hotfix (2026-10-29)

Added the missing `net.kyori.adventure.text.format.NamedTextColor` import referenced by the scoreboard and match report features. This source-only fix has not been fully compiled against Paper here; verify via GitHub Actions.
