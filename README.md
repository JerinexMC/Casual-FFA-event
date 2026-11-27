# CasualFFA 1.0.0

Paper 1.21.11 / Java 21 FFA tournament plugin project.

## Build

Requires Gradle 8+ and Java 21:

```bash
gradle clean build
```

The jar is written to `build/libs/CasualFFA-1.0.0.jar`.

## Setup

1. Put the built jar in `plugins/` and restart the server.
2. Stand in the FFA waiting area and run `/ffa setlobby`.
3. Stand at the center of the battle arena and run `/ffa setarena` (or `/ffa setspawn`).
4. Set the random spread with `spawn-radius` in `plugins/CasualFFA/config.yml`.
5. Put your desired kit in your inventory and run `/ffa kit create`.

## Commands

- `/ffa start` - open registration.
- `/ffa join` - register and teleport to the lobby.
- `/ffa leave` - leave before the battle begins.
- `/ffa kit create` - save your current inventory as the kit.
- `/ffa kit distribute` - give the kit to current lobby players and automatically to later joiners in this registration.
- `/ffa kit delete` - delete the saved kit.
- `/ffa begin` - teleport registered players to randomized positions around the arena center and start the battle.
- `/ffa pause` and `/ffa resume` - pause/resume tournament state.
- `/ffa stop` - end the event and restore online participants.
- `/ffa clearinv true|false` - choose whether to clear player inventory on joining.
- `/ffa exclude <player>` and `/ffa include <player>` - manage non-participants/spectators.
- `/ffa list`, `/ffa excluded`, `/ffa status` - show event information.

Admin commands require `casualffa.admin` (default: op). `casualffa.bypasslock` lets staff use commands/teleport while participating (default: op).

## Inventory safety

The plugin saves inventory, armor, offhand, XP, food, health, game mode, flight state, and potion effects to `plugins/CasualFFA/data.yml` before clearing anything. Backups remain on disk until restored, including if a player disconnects or the server restarts. Ender chests are not modified.

`clearinv` defaults to true. The kit is added after the inventory is optionally cleared. With `clearinv false`, players keep their inventory and receive the kit alongside it.

## Notes

- `/ffa setarena` sets an arena center, not a WorldGuard cuboid. Players are randomly spread within the configured radius; they spawn at the highest block at each sampled X/Z.
- PvP is left to the world's/server's normal PvP setting. The plugin does not disable combat in the lobby.
- The project source is provided. A compiled JAR is not included because this build environment does not have Gradle/Maven or the Paper API dependency cached; build it with Gradle on your machine or in GitHub Actions.
