# MagicReset

A Paper plugin for **Minecraft 26.2** that resets the game worlds **without restarting the server**.
While the new world is generated, everybody waits in a **limbo**. Built for speedruns, challenges and manhunts:
seed selection, a real-time / in-game timer in the action bar, goals, death rules, scheduled resets,
a sidebar and a locator bar switch.

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
- **Preloaded next world** (optional, off by default) – with `reset.preload: true` the next overworld is generated in
  the background, so a reset moves everybody straight into it without the limbo. Changed seed or world settings regenerate it automatically.
- **Locator bar** – `worlds.locator-bar` / `/mr locatorbar on|off` switches the vanilla locator bar in the game worlds.
- All texts are in English and fully editable in `messages.yml` (MiniMessage).

## Magic Manhunt

`/manhunt` (alias `/mm`, permission `magicreset.manhunt`) turns a run into a manhunt:

- `/mm target set <players>` chooses one or more targets (runners), everybody else hunts them
  (`/mm hunter|spectator <players>` to set roles by hand).
- `/mm start [seed]` switches manhunt mode on and resets into a new world. The hunters are frozen during the
  head start (`/mm headstart <seconds>`).
- **Locator bar:** the hunters see only the targets, the targets see nobody. Hunters can also right-click a
  compass to point it at the nearest target (`/mm compass on|off`).
- **Targets win** by reaching the goal first (`/mm goal ...` – nether, end, dragon, Beat Minecraft, item,
  advancement, ...). **Hunters win** when every target lost all lives (`/mm lives <n>`).
- The end shows a big title with the winner and the time; `/mm resetafter <seconds>` resets automatically.
- Manhunt times have their own leaderboard.

## Modes

The **Mode** button in the menu switches between three modes with one click:

- **Speedrun** – timer on and a goal to reach.
- **Free play** – no timer, no goal, no death rules: just play and reset the worlds whenever you like.
- **Manhunt** – targets run, hunters chase them (see below).

The timer can also be switched off on its own (`timer.enabled`), and the sidebar for everybody (`scoreboard.enabled`).
A reset can delete only some worlds: without the overworld the run goes on and only the nether/end are regenerated.
Goals also include finding a biome or a structure first.

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
│       ├── overworld/    <- game overworld (alternates with overworld_b, the preloaded next one)
│       ├── overworld_b/
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

## Menu

`/magicreset` (or `/mr`) without arguments opens an in-game menu built with the server-side dialog screens
(Minecraft 1.21.6+, no client mod needed). The first page has the things you need all the time, each with one click:

- **⟳ Reset** – asks "Reset now?"; *Options…* picks the worlds, the seed and the countdowns.
- **Mode** – click to switch Speedrun → Free play → Manhunt.
- **⚑ Goal** (not in free play) and **⚔ Targets** (manhunt only).
- **⌚ Timer: On/Off** with Pause/Resume, **▤ Sidebar: On/Off** and **★ Leaderboard**.
- **⚙ More settings** – every other setting, sorted by topic.

Every button needs the same permission as the matching command. Players without admin permissions see an info
view with the run status and the leaderboard.

Every setting of `config.yml` can be changed in the menu: switches, sliders and choices, and text fields that
name an example of what to type. The goal is chosen with buttons; mobs, blocks, items and advancements are
picked from a search (with popular choices before typing anything), dimensions with one click. `/manhunt` (`/mm`) opens the
manhunt section directly.

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
| `/mr timer format <pattern\|auto>` | Time format, e.g. `HH:mm:ss`, `mm:ss.SSS`, `H:mm:ss.SS`. |
| `/mr timer start-on-move on\|off` | Start the timer only on the first movement (same as trigger first-input). |
| `/mr timer stop-when-empty on\|off` | Stop the timer while nobody is playing, continue when someone is back. |
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
| `/mr locatorbar [on\|off]` | Switch the vanilla locator bar in the game worlds. |
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
| `magicreset.manhunt` | op | Magic Manhunt (`/manhunt`, `/mm`). |
| `magicreset.command.backup` | op | Switch world backups. |
| `magicreset.command.leaderboard` | op | Clear the leaderboard (viewing is allowed for everyone). |
| `magicreset.command.autoreset` | op | Scheduled resets. |
| `magicreset.command.locatorbar` | op | Switch the locator bar. |
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
- A numbered release is published by pushing a tag like `v1.0.0`, or in GitHub under *Actions → Build →
  Run workflow* with a version number.
