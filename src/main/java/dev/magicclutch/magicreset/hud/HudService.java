package dev.magicclutch.magicreset.hud;

import dev.magicclutch.magicreset.MagicReset;
import dev.magicclutch.magicreset.config.Messages;
import dev.magicclutch.magicreset.config.Settings;
import dev.magicclutch.magicreset.game.GameManager;
import dev.magicclutch.magicreset.goal.Goal;
import dev.magicclutch.magicreset.goal.GoalService;
import dev.magicclutch.magicreset.stats.Leaderboard;
import dev.magicclutch.magicreset.timer.RunTimer;
import dev.magicclutch.magicreset.timer.TimerService;
import dev.magicclutch.magicreset.util.TimeFormat;
import dev.magicclutch.magicreset.world.GameWorlds;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.jspecify.annotations.Nullable;

/**
 * Ticks the timers and renders the action bar timer and the sidebar.
 */
public final class HudService implements Listener {

    private static final String OBJECTIVE = "magicreset";

    private final MagicReset plugin;
    private final GameManager game;
    private final GameWorlds worlds;
    private final TimerService timers;
    private final GoalService goals;

    private final Map<UUID, Scoreboard> sidebars = new HashMap<>();
    /** Last lines sent per player, so unchanged lines do not cause packets. */
    private final Map<UUID, List<Component>> sentLines = new HashMap<>();
    private final Set<UUID> sidebarToggled = new HashSet<>();
    private @Nullable BukkitTask task;
    private long tick;

    public HudService(MagicReset plugin, GameManager game, GameWorlds worlds, TimerService timers, GoalService goals) {
        this.plugin = plugin;
        this.game = game;
        this.worlds = worlds;
        this.timers = timers;
        this.goals = goals;
    }

    private Settings settings() {
        return this.plugin.settings();
    }

    private Messages messages() {
        return this.plugin.messages();
    }

    public void start() {
        this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.removeSidebar(player);
        }
    }

    private void tick() {
        this.tick++;
        GameManager.Phase phase = this.game.phase();
        boolean running = phase == GameManager.Phase.RUNNING || phase == GameManager.Phase.COUNTDOWN;

        if (running) {
            List<Player> inGame = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (this.worlds.isGameWorld(player.getWorld()) && player.getGameMode() != GameMode.SPECTATOR) {
                    inGame.add(player);
                }
            }
            this.timers.updatePresence(inGame);
            this.timers.tick(inGame);
            if (this.tick % 10 == 0) {
                this.goals.pollInventories();
            }
        }

        Settings.Timer timerConfig = this.settings().timer();
        if (this.tick % timerConfig.updateInterval() == 0) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                Component bar = this.actionBar(player, phase);
                if (bar != null) {
                    player.sendActionBar(bar);
                }
            }
        }
        if (this.tick % this.settings().scoreboard().updateInterval() == 0) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                this.updateSidebar(player);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Action bar
    // ---------------------------------------------------------------------------------------------------------

    private @Nullable Component actionBar(Player player, GameManager.Phase phase) {
        Messages text = this.messages();
        if (phase == GameManager.Phase.RESETTING) {
            return text.get("hud.resetting");
        }
        if (phase == GameManager.Phase.PAUSED || phase == GameManager.Phase.RESUMING) {
            return text.get("hud.paused", this.timePlaceholders(player));
        }
        if (!this.worlds.isGameWorld(player.getWorld())) {
            return null;
        }
        return this.settings().timer().actionBar() ? this.timerLine(player) : null;
    }

    private @Nullable Component timerLine(Player player) {
        RunTimer timer = this.timers.displayed(player);
        TagResolver placeholders = this.timePlaceholders(player);
        if (this.game.outcome() == GameManager.Outcome.FAILED) {
            return this.messages().get("hud.timer.failed", placeholders);
        }
        return switch (timer.state()) {
            case RUNNING -> this.messages().get("hud.timer.running", placeholders);
            case PAUSED -> this.messages().get(this.timers.isWaitingForInput(player) ? "hud.timer.paused-move" : "hud.timer.paused", placeholders);
            case FINISHED -> this.messages().get("hud.timer.finished", placeholders);
            case IDLE -> {
                if (this.timers.isWaitingForInput(player)) {
                    yield this.messages().get("hud.timer.waiting", placeholders);
                }
                yield this.settings().timer().showWhenIdle() ? this.messages().get("hud.timer.idle", placeholders) : null;
            }
        };
    }

    public TagResolver timePlaceholders(Player player) {
        RunTimer timer = this.timers.displayed(player);
        Settings.Timer config = this.settings().timer();
        String time = TimeFormat.format(timer.millis(this.timers.mode()), config.decimals(), config.style());
        return TagResolver.resolver(
            Placeholder.unparsed("time", time),
            Placeholder.unparsed("mode", this.timers.mode().name())
        );
    }


    // ---------------------------------------------------------------------------------------------------------
    // Sidebar
    // ---------------------------------------------------------------------------------------------------------

    public boolean sidebarVisible(Player player) {
        boolean byDefault = this.settings().scoreboard().enabledByDefault();
        return this.sidebarToggled.contains(player.getUniqueId()) != byDefault;
    }

    /** @return the new visibility */
    public boolean toggleSidebar(Player player) {
        if (!this.sidebarToggled.remove(player.getUniqueId())) {
            this.sidebarToggled.add(player.getUniqueId());
        }
        boolean visible = this.sidebarVisible(player);
        if (!visible) {
            this.removeSidebar(player);
        } else {
            this.updateSidebar(player);
        }
        return visible;
    }

    private void updateSidebar(Player player) {
        if (!this.sidebarVisible(player)) {
            return;
        }
        Scoreboard board = this.sidebars.computeIfAbsent(player.getUniqueId(), ignored -> Bukkit.getScoreboardManager().getNewScoreboard());
        if (player.getScoreboard() != board) {
            player.setScoreboard(board);
        }
        Objective objective = board.getObjective(OBJECTIVE);
        if (objective == null) {
            this.sentLines.remove(player.getUniqueId()); // fresh board, nothing sent yet
            objective = board.registerNewObjective(OBJECTIVE, Criteria.DUMMY, this.messages().get("scoreboard.title"));
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            objective.numberFormat(io.papermc.paper.scoreboard.numbers.NumberFormat.blank());
        }
        boolean inLimbo = this.worlds.isLimbo(player.getWorld());
        objective.displayName(this.messages().get(inLimbo ? "leaderboard.title" : "scoreboard.title"));

        List<Component> lines = inLimbo ? this.leaderboardLines() : this.messages().list("scoreboard.lines", this.sidebarPlaceholders(player));
        int size = Math.min(lines.size(), 15);
        List<Component> previous = this.sentLines.getOrDefault(player.getUniqueId(), List.of());
        for (int i = 0; i < size; i++) {
            if (i < previous.size() && previous.size() == size && previous.get(i).equals(lines.get(i))) {
                continue;
            }
            String entry = "line" + i;
            var score = objective.getScore(entry);
            score.setScore(size - i);
            score.customName(lines.get(i));
        }
        this.sentLines.put(player.getUniqueId(), List.copyOf(lines.subList(0, size)));
        for (String entry : new ArrayList<>(board.getEntries())) {
            if (entry.startsWith("line")) {
                try {
                    if (Integer.parseInt(entry.substring(4)) >= size) {
                        board.resetScores(entry);
                    }
                } catch (NumberFormatException ignored) {
                    // not ours
                }
            }
        }
    }

    /** Best times for the goal and timer mode that are selected right now. */
    public List<Component> leaderboardLines() {
        Messages text = this.messages();
        List<Component> lines = new ArrayList<>();
        lines.add(text.get("leaderboard.goal", Placeholder.component("goal", this.goals.describe(this.goals.goal())),
            Placeholder.unparsed("mode", this.timers.mode().name())));
        lines.add(Component.empty());
        List<Leaderboard.Entry> top = this.plugin.leaderboard().top(Leaderboard.category(this.goals.goal(), this.timers.mode()));
        if (top.isEmpty()) {
            lines.add(text.get("leaderboard.empty"));
            return lines;
        }
        DateTimeFormatter dates = DateTimeFormatter.ofPattern(text.raw("leaderboard.date-format"), Locale.ROOT)
            .withZone(ZoneId.systemDefault());
        Settings.Timer config = this.settings().timer();
        for (int i = 0; i < top.size() && lines.size() < 15; i++) {
            Leaderboard.Entry entry = top.get(i);
            lines.add(text.get("leaderboard.entry",
                Placeholder.unparsed("rank", Integer.toString(i + 1)),
                Placeholder.unparsed("player", entry.name()),
                Placeholder.unparsed("time", TimeFormat.format(entry.millis(), config.decimals(), config.style())),
                Placeholder.unparsed("date", dates.format(Instant.ofEpochMilli(entry.achievedAt())))));
        }
        return lines;
    }

    private TagResolver sidebarPlaceholders(Player player) {
        Goal goal = this.goals.goal();
        Settings.Deaths deaths = this.settings().deaths();
        String lives = switch (deaths.mode()) {
            case OFF -> "-";
            case HARDCORE -> "1";
            case SHARED_LIVES -> this.game.livesLeft() + "/" + deaths.sharedLives();
        };
        long players = Bukkit.getOnlinePlayers().stream().filter(online -> this.worlds.isGameWorld(online.getWorld())).count();
        return TagResolver.resolver(
            this.timePlaceholders(player),
            Placeholder.unparsed("run", Integer.toString(this.game.run())),
            this.game.seedPlaceholder(),
            Placeholder.component("goal", this.goals.describe(goal)),
            Placeholder.unparsed("progress", goal.isNone() ? "-" : this.goals.progress(player) + "/" + goal.amount()),
            Placeholder.unparsed("deaths", Integer.toString(this.game.deaths())),
            Placeholder.unparsed("lives", lives),
            Placeholder.unparsed("players", Long.toString(players)),
            Placeholder.unparsed("scope", this.timers.scope().name().toLowerCase(Locale.ROOT)),
            Placeholder.component("status", this.messages().get("scoreboard.status." + this.statusKey()))
        );
    }

    private String statusKey() {
        return switch (this.game.phase()) {
            case RESETTING -> "resetting";
            case PAUSED, RESUMING -> "paused";
            case RUNNING, COUNTDOWN -> switch (this.game.outcome()) {
                case NONE -> "running";
                case COMPLETED -> "completed";
                case FAILED -> "failed";
            };
        };
    }

    private void removeSidebar(Player player) {
        this.sentLines.remove(player.getUniqueId());
        Scoreboard board = this.sidebars.remove(player.getUniqueId());
        if (board != null && player.getScoreboard() == board) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        this.sidebars.remove(event.getPlayer().getUniqueId());
        this.sentLines.remove(event.getPlayer().getUniqueId());
    }

    public void save(ConfigurationSection section) {
        section.set("sidebar-toggled", this.sidebarToggled.stream().map(UUID::toString).toList());
    }

    public void load(@Nullable ConfigurationSection section) {
        if (section == null) {
            return;
        }
        for (String raw : section.getStringList("sidebar-toggled")) {
            try {
                this.sidebarToggled.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
                // skip malformed entries
            }
        }
    }
}
