# Eldritch Battles v0.2.2 — Health labels, axe shield stun, map protection

**Source-only experimental update for Paper 1.21.11 / Java 21.** You must build it with the repository GitHub Actions before putting the resulting `.jar` on the server. Keep a full backup first.

## Changes

- Removed the **Block Breaking** toggle from Duel Settings. In a live fight, the original arena is protected against normal player breaks and the explosion events handled by this plugin; players can break **blocks placed during the current round**, including shulker boxes. Player-placed blocks reset between rounds.
- Health indicator now displays a constantly refreshed **numeric HP label with a red heart below the player nametag / above the player's head**. Minecraft counts **20 HP as 10 full hearts** (e.g. `20 ❤`), and updates every 5 ticks. Health display is visible to other players, not usually yourself. Requires the Duel Settings health toggle ON. This is server-side scoreboard UI: **no client mod or Skript needed**. Other scoreboard plugins can replace it, so temporarily disable competing nametag/scoreboard managers if necessary.
- **Shield Stuns toggle now uses Paper's PlayerShieldDisableEvent.** With stun ON an enemy axe hit that naturally disables a blocking shield applies **100 ticks (5 seconds)** of cooldown; with OFF the shield-disable event is canceled for match player-versus-player. This is a shield disable, not player movement stun. Normal vanilla axe hit conditions still apply.
- Builders with `eldritch.admin` in **Creative** mode can edit the arena outside fights.
- Retains the v0.2.0 menus, parties, duel system, arena data and saved kits. No arena rebuild should be necessary.

## Install on your server

1. **Back up the entire server** (especially `plugins/EldritchBattles/` and the arena world, e.g. `eldritch_test`).
2. Extract the ZIP and upload the **contents** of the `eldritch-battles-v0.2.2` folder to the root of your existing GitHub repository, replacing existing paths. Do not put the folder itself inside the repository.
3. Commit, check **Actions → Build Eldritch Battles** for a green build, and download the `EldritchBattles-JAR` artifact; extract `EldritchBattles-0.2.2.jar`.
4. Stop MCServerHost. Delete **only** the old `EldritchBattles-*.jar` from `plugins/`, upload the new JAR, leave **all plugin data folders and world folders** alone, and restart.
5. **Disable** `plugins/Skript/scripts/eldritch_no_break.sk` by renaming it to `eldritch_no_break.sk.disabled`, and restart or reload Skript; otherwise it will still prevent breaking player-placed shulkers.
6. Test in a two-player duel: block an axe with a shield (stuns ON vs OFF), verify `20 ❤` under opponent's name at full health, place and mine a shulker, and try breaking the original floor. Test explosions on a **backup world**.

## Known limitations

This update has not yet been built against Paper or live-tested here. The Java syntax is checked, but the GitHub Gradle build is required. Plugin priority and other plugins (EpicDuels, Skript, WorldGuard, scoreboard/nametag plugins) can override protected actions, scoreboards, or shield behaviour. Protection covers normal break, explosion, burn and fade events in plugin-managed arena worlds. Physics changes, fluids, moving pistons, and plugins that change blocks without firing expected events can still affect map blocks: keep backups and test these mechanics before opening a public arena. Existing map structures outside the configured snapshot won't be reset automatically.
