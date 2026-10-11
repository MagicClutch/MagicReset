# MagicReset

A Paper plugin for **Minecraft 26.2** that resets the game worlds **without restarting the server**.
While the new world is generated, everybody waits in a **limbo**. Built for speedruns, challenges and manhunts:
seed selection, a real-time / in-game timer in the action bar, goals, death rules, scheduled resets,
a sidebar and a player radar.

## Features

- **Instant reset without restart** – players are moved to a void limbo world, the old overworld, nether and end
  are unloaded and deleted, new ones are generated, a dry spawn point is searched and preloaded, and everyone is
  released with a countdown.
- **Seed selection** – random seed, a fixed seed, or a seed just for the next reset (`/mr reset <seed>`).
  `/mr seed copy` turns the current seed into the fixed seed.
- **Timer in the action bar** – `RTA` (real time, wall clock) or `IGT` (in-game play time, counted in server ticks).
  `GLOBAL` (one shared timer, the first one to finish stops it) or `INDIVIDUAL` (every player has an own timer).
  Starts automatically, on the first input or manually. Correct formatting (`02:03.45`, `1:02:03.45` or `1h 02m 03.45s`).
- **Goals** – kill an entity (Ender Dragon by default), enter a dimension (go through a portal), get an advancement,
  mine a block or collect an item, each with an optional amount. Completing the goal stops the timer and shows
  the final time.
- **Limbo pause** – `/mr limbo` saves the full state of every player (position, inventory, health, hunger, XP,
  effects, ...), pauses the timer and moves everybody to limbo; `/mr limbo` (or `/mr resume`) brings them back
  with a countdown. The saved state survives a server restart.
- **Death rules** – off, hardcore (first death fails the run) or shared lives; optional automatic reset after a
  failed or completed run.
- **Auto reset** – after a duration (`/mr autoreset in 2h loop`) or at a time of day (`/mr autoreset at 18:00 loop`)
  with warnings.
- **Backups** – before a reset deletes a world, its files are moved to `plugins/MagicReset/backups/<date>_run-<n>/`
  (one folder per world plus `info.yml` with run and seed). The last 5 are kept (`backups.keep`), switch with
  `/mr backup on|off`.
- **Hidden seed** – `seed.show: false` (or `/mr seed show off`) hides the seed from players; admins still see it.
- **Leaderboard** – the fastest times per goal and timer mode with name and date, shown as the sidebar in the limbo
  (`/mr leaderboard`). Stored in `plugins/MagicReset/leaderboard.yml`.
- **Sidebar** with run number, time, goal progress, deaths and lives (toggle per player with `/mr scoreboard`).
- **Radar** – shows the nearest player with distance and direction while holding a compass.
- All texts are in English and fully editable in `messages.yml` (MiniMessage).

## Requirements

- Paper (or a Paper fork) **26.2**
- Java **25**

## Installation

1. Download `MagicReset-<version>.jar` from the [releases](../../releases) ("Latest build" is always the newest
   version of `main`).
2. Put it into `plugins/` and start the server. The first world is generated automatically.

## How the worlds are stored

Since 26.1 every dimension lives inside the main level folder (`level-name` in `server.properties`):

```
world/
├── dimensions/
│   ├── minecraft/
│   │   ├── overworld/    <- server's main overworld: cannot be unloaded while the server runs, never touched
│   │   ├── the_nether/   <- game nether: deleted and regenerated on every reset
│   │   └── the_end/      <- game end:    deleted and regenerated on every reset (dragon fight starts fresh)
│   └── magicreset/
│       ├── overworld/    <- game overworld: deleted and regenerated on every reset
│       └── limbo/        <- waiting room, kept
├── players/              <- player data, never deleted (players are reset via the API)
└── data/                 <- shared data, never deleted
```

- **Nether and End are the server's real dimensions** (`minecraft:the_nether`, `minecraft:the_end`) and are really
  deleted on every reset (`worlds.use-main-nether-end: true`, the default). Set it to `false` to use separate
  plugin dimensions instead.
- **The overworld is a plugin world** (`magicreset:overworld`). The server's main overworld can never be unloaded
  while the server runs, so it cannot be reset without a restart. Players never see it.
- `/magicreset worlds` lists exactly which worlds a reset deletes (with their folders) and where you are;
  `/magicreset worlds nether|end on|off` chooses whether nether/end are part of the game.
- A `paper-world.yml` inside a game dimension folder is kept, so per-world Paper settings survive resets.

Nether portals and end portals of the game worlds are linked to each other (coordinate scaling, portal
search/creation and the obsidian platform work like in vanilla).

## Commands

Main command `/magicreset`, alias `/mr`. `/reset` is registered as a shortcut for `/magicreset reset`
(disable with `reset.shortcut-command`).

| Command | Description |
| --- | --- |
| `/mr reset [seed\|random\|same] [countdown] [limbo-countdown]` | Reset the worlds. |
| `/mr cancel` | Cancel a running countdown. |
| `/mr limbo [countdown]` | Pause the run in limbo, or continue it if it is paused. |
| `/mr resume [countdown]` | Continue a paused run. |
| `/mr seed` | Show the current seed (click to copy). |
| `/mr seed random` / `fixed <seed>` / `copy` | Choose the seed for the next resets. |
| `/mr timer` | Show the timer (RTA and IGT). |
| `/mr timer start\|pause\|resume\|reset` | Control the timer. |
| `/mr timer set <time>` | Set the time, e.g. `1:30:00` or `1h30m`. |
| `/mr timer mode rta\|igt` | Real time or in-game time. |
| `/mr timer scope global\|individual` | Shared or personal timers. |
| `/mr timer trigger auto\|first-input\|manual` | When the timer starts. |
| `/mr goal` | Show the goal and progress. |
| `/mr goal none\|beat\|dragon` | No goal / Beat Minecraft (leave the End through the exit portal) / kill the Ender Dragon. |
| `/mr worlds [nether\|end on\|off]` | Show which worlds a reset deletes, choose nether/end. |
| `/mr goal kill <entity> [amount]` | Kill an entity. |
| `/mr goal dimension nether\|end\|overworld` | Travel through a portal into a dimension. |
| `/mr goal advancement <advancement>` | Get an advancement. |
| `/mr goal break <block> [amount]` | Mine a block. |
| `/mr goal item <item> [amount]` | Collect an item. |
| `/mr deaths off\|hardcore\|lives <n>` | Death rules. |
| `/mr autoreset [off \| in <duration> [loop] \| at <HH:mm> [loop]]` | Scheduled resets. |
| `/mr scoreboard` | Toggle your sidebar. |
| `/mr radar` | Toggle the player radar. |
| `/mr leaderboard [clear]` | Best times of the current goal and timer mode (also shown as the sidebar in the limbo). |
| `/mr backup [on\|off]` | Show or switch world backups. |
| `/mr seed show on\|off` | Show or hide the seed for players. |
| `/mr status` | Overview of the current run. |
| `/mr reload` | Reload `config.yml` and `messages.yml`. |

Settings changed by commands are written back into `config.yml` (comments are kept).

## Permissions

| Permission | Default | Description |
| --- | --- | --- |
| `magicreset.admin` | op | Everything below. |
| `magicreset.command.use` | everyone | `/mr`, help and status. |
| `magicreset.command.scoreboard` | everyone | Toggle the own sidebar. |
| `magicreset.command.reset` | op | Reset and cancel. |
| `magicreset.command.limbo` | op | Limbo pause / resume. |
| `magicreset.command.seed` | op | Seed settings. |
| `magicreset.command.timer` | op | Timer control. |
| `magicreset.command.goal` | op | Goal settings. |
| `magicreset.command.deaths` | op | Death rules. |
| `magicreset.command.worlds` | op | Show and choose the reset worlds. |
| `magicreset.command.backup` | op | Switch world backups. |
| `magicreset.command.leaderboard` | op | Clear the leaderboard (viewing is allowed for everyone). |
| `magicreset.command.autoreset` | op | Scheduled resets. |
| `magicreset.command.radar` | op | Toggle the radar. |
| `magicreset.command.reload` | op | Reload. |
| `magicreset.limbo.bypass` | op | Build and interact in the limbo. |

## Timer details

- **RTA** measures wall-clock time from the start of the run. It keeps running while nobody is online. Only an
  explicit pause (`/mr limbo`, `/mr timer pause`) stops it. Time while the server is offline is not counted.
- **IGT** counts server ticks (20 per second) while the run is active, so lag and server freezes are not
  counted. With `timer.igt-pause-when-empty` it also stops while nobody is in the game worlds. In `INDIVIDUAL`
  scope it only counts while that player is online.
- Both clocks always run side by side, so you can switch the displayed mode at any time without losing time.

## Building

```bash
./gradlew build
```

The jar is written to `build/libs/`. Every push and pull request is built by GitHub Actions
(`.github/workflows/build.yml`) and the jar is attached to the run as the artifact `MagicReset`.

- Every push to `main` replaces the pre-release **Latest build** (tag `latest`) with the new jar.
- Pushing a tag like `v1.0.0` publishes a numbered release.
