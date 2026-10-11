package dev.magicclutch.magicreset.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.magicclutch.magicreset.MagicReset;
import dev.magicclutch.magicreset.config.Messages;
import dev.magicclutch.magicreset.config.Settings;
import dev.magicclutch.magicreset.game.DeathMode;
import dev.magicclutch.magicreset.game.GameManager;
import dev.magicclutch.magicreset.game.SeedMode;
import dev.magicclutch.magicreset.goal.Goal;
import dev.magicclutch.magicreset.goal.GoalService;
import dev.magicclutch.magicreset.goal.GoalType;
import dev.magicclutch.magicreset.hud.HudService;
import dev.magicclutch.magicreset.schedule.AutoResetScheduler;
import dev.magicclutch.magicreset.stats.Leaderboard;
import dev.magicclutch.magicreset.timer.RunTimer;
import dev.magicclutch.magicreset.timer.StartTrigger;
import dev.magicclutch.magicreset.timer.TimerMode;
import dev.magicclutch.magicreset.timer.TimerScope;
import dev.magicclutch.magicreset.timer.TimerService;
import dev.magicclutch.magicreset.util.Durations;
import dev.magicclutch.magicreset.util.TimeFormat;
import dev.magicclutch.magicreset.world.GameWorlds;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /magicreset} (alias {@code /mr}) and the optional {@code /reset} shortcut.
 */
public final class MagicResetCommand {

    private static final String PERM = "magicreset.command.";
    private static final int MAX_COUNTDOWN = 3600;

    private final MagicReset plugin;
    private final GameManager game;
    private final TimerService timers;
    private final HudService hud;
    private final AutoResetScheduler autoReset;

    public MagicResetCommand(MagicReset plugin, GameManager game, TimerService timers, HudService hud, AutoResetScheduler autoReset) {
        this.plugin = plugin;
        this.game = game;
        this.timers = timers;
        this.hud = hud;
        this.autoReset = autoReset;
    }

    private Messages messages() {
        return this.plugin.messages();
    }

    private Settings settings() {
        return this.plugin.settings();
    }

    public void register(Commands registrar) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("magicreset")
            .requires(source -> source.getSender().hasPermission(PERM + "use"))
            .executes(this::help)
            .then(Commands.literal("help").executes(this::help))
            .then(this.resetNode("reset"))
            .then(Commands.literal("cancel").requires(permission("reset")).executes(this::cancel))
            .then(this.limboNode())
            .then(Commands.literal("resume").requires(permission("limbo"))
                .executes(ctx -> this.resume(ctx, this.settings().reset().limboCountdown()))
                .then(Commands.argument("countdown", IntegerArgumentType.integer(0, MAX_COUNTDOWN))
                    .executes(ctx -> this.resume(ctx, IntegerArgumentType.getInteger(ctx, "countdown")))))
            .then(this.seedNode())
            .then(this.timerNode())
            .then(this.goalNode())
            .then(this.deathsNode())
            .then(this.worldsNode())
            .then(Commands.literal("backup").requires(permission("backup"))
                .executes(this::backupStatus)
                .then(Commands.literal("on").executes(ctx -> this.setBackups(ctx, true)))
                .then(Commands.literal("off").executes(ctx -> this.setBackups(ctx, false))))
            .then(Commands.literal("leaderboard")
                .executes(this::showLeaderboard)
                .then(Commands.literal("clear").requires(permission("leaderboard")).executes(ctx -> {
                    this.plugin.leaderboard().clear(Leaderboard.category(this.plugin.goals().goal(), this.timers.mode()));
                    this.messages().send(sender(ctx), "leaderboard.cleared", this.game.goalPlaceholder());
                    return Command.SINGLE_SUCCESS;
                })))
            .then(this.autoResetNode())
            .then(Commands.literal("scoreboard").requires(permission("scoreboard")).executes(this::toggleScoreboard))
            .then(Commands.literal("radar").requires(permission("radar")).executes(this::toggleRadar))
            .then(Commands.literal("status").executes(this::status))
            .then(Commands.literal("reload").requires(permission("reload")).executes(this::reload));

        registrar.register(root.build(), "World reset with limbo, timer and goals", List.of("mr"));
        if (this.settings().reset().shortcutCommand()) {
            registrar.register(this.resetNode("reset").build(), "Reset the game worlds (MagicReset)");
        }
    }

    private static java.util.function.Predicate<CommandSourceStack> permission(String node) {
        return source -> source.getSender().hasPermission(PERM + node);
    }

    private static CommandSender sender(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getSender();
    }

    // ---------------------------------------------------------------------------------------------------------
    // reset / cancel / limbo
    // ---------------------------------------------------------------------------------------------------------

    private LiteralArgumentBuilder<CommandSourceStack> resetNode(String name) {
        return Commands.literal(name)
            .requires(permission("reset"))
            .executes(ctx -> this.reset(ctx, null, this.settings().reset().countdown(), this.settings().reset().limboCountdown()))
            .then(Commands.argument("seed", StringArgumentType.word())
                .suggests(this.suggest(() -> Stream.of("random", "same", Long.toString(this.game.seed()))))
                .executes(ctx -> this.reset(ctx, StringArgumentType.getString(ctx, "seed"),
                    this.settings().reset().countdown(), this.settings().reset().limboCountdown()))
                .then(Commands.argument("countdown", IntegerArgumentType.integer(0, MAX_COUNTDOWN))
                    .executes(ctx -> this.reset(ctx, StringArgumentType.getString(ctx, "seed"),
                        IntegerArgumentType.getInteger(ctx, "countdown"), this.settings().reset().limboCountdown()))
                    .then(Commands.argument("limbo-countdown", IntegerArgumentType.integer(0, MAX_COUNTDOWN))
                        .executes(ctx -> this.reset(ctx, StringArgumentType.getString(ctx, "seed"),
                            IntegerArgumentType.getInteger(ctx, "countdown"), IntegerArgumentType.getInteger(ctx, "limbo-countdown"))))));
    }

    private int reset(CommandContext<CommandSourceStack> ctx, String seed, int countdown, int limboCountdown) {
        String error = this.game.requestReset(seed, countdown, limboCountdown);
        if (error != null) {
            this.messages().send(sender(ctx), error);
            return 0;
        }
        if (countdown > 0) {
            this.messages().send(sender(ctx), "reset.countdown-started", Placeholder.unparsed("seconds", Integer.toString(countdown)));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int cancel(CommandContext<CommandSourceStack> ctx) {
        if (!this.game.cancelCountdown(true)) {
            this.messages().send(sender(ctx), "countdown.none");
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    private LiteralArgumentBuilder<CommandSourceStack> limboNode() {
        return Commands.literal("limbo").requires(permission("limbo"))
            .executes(ctx -> this.limbo(ctx, -1))
            .then(Commands.argument("countdown", IntegerArgumentType.integer(0, MAX_COUNTDOWN))
                .executes(ctx -> this.limbo(ctx, IntegerArgumentType.getInteger(ctx, "countdown"))));
    }

    /** Toggles the limbo pause. */
    private int limbo(CommandContext<CommandSourceStack> ctx, int countdown) {
        if (this.game.phase() == GameManager.Phase.PAUSED) {
            return this.resume(ctx, countdown < 0 ? this.settings().reset().limboCountdown() : countdown);
        }
        String error = this.game.requestPause(Math.max(0, countdown));
        if (error != null) {
            this.messages().send(sender(ctx), error);
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    private int resume(CommandContext<CommandSourceStack> ctx, int countdown) {
        String error = this.game.requestResume(countdown);
        if (error != null) {
            this.messages().send(sender(ctx), error);
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------------------------------------------------
    // seed
    // ---------------------------------------------------------------------------------------------------------

    private LiteralArgumentBuilder<CommandSourceStack> seedNode() {
        return Commands.literal("seed").requires(permission("seed"))
            .executes(this::showSeed)
            .then(Commands.literal("random").executes(ctx -> {
                this.plugin.setConfigValue("seed.mode", SeedMode.RANDOM.name());
                this.messages().send(sender(ctx), "seed.mode-random");
                return Command.SINGLE_SUCCESS;
            }))
            .then(Commands.literal("fixed")
                .then(Commands.argument("seed", StringArgumentType.greedyString()).executes(ctx -> {
                    String seed = StringArgumentType.getString(ctx, "seed").trim();
                    this.plugin.setConfigValues(Map.of("seed.fixed", seed, "seed.mode", SeedMode.FIXED.name()));
                    this.messages().send(sender(ctx), "seed.mode-fixed", Placeholder.unparsed("seed", seed));
                    return Command.SINGLE_SUCCESS;
                })))
            .then(Commands.literal("show")
                .then(Commands.literal("on").executes(ctx -> this.setShowSeed(ctx, true)))
                .then(Commands.literal("off").executes(ctx -> this.setShowSeed(ctx, false))))
            .then(Commands.literal("copy").executes(ctx -> {
                String seed = Long.toString(this.game.seed());
                this.plugin.setConfigValues(Map.of("seed.fixed", seed, "seed.mode", SeedMode.FIXED.name()));
                this.messages().send(sender(ctx), "seed.copied", Placeholder.unparsed("seed", seed));
                return Command.SINGLE_SUCCESS;
            }));
    }

    private int setShowSeed(CommandContext<CommandSourceStack> ctx, boolean show) {
        this.plugin.setConfigValue("seed.show", show);
        this.messages().send(sender(ctx), show ? "seed.show-on" : "seed.show-off");
        return Command.SINGLE_SUCCESS;
    }

    private int showSeed(CommandContext<CommandSourceStack> ctx) {
        String seed = Long.toString(this.game.seed());
        Component clickable = Component.text(seed)
            .clickEvent(ClickEvent.copyToClipboard(seed))
            .hoverEvent(HoverEvent.showText(this.messages().get("seed.click-to-copy")));
        String mode;
        if (this.settings().seedMode() != SeedMode.FIXED) {
            mode = "random";
        } else if (this.settings().fixedSeed().isBlank()) {
            mode = "random, the fixed seed is empty";
        } else {
            mode = "fixed (" + this.settings().fixedSeed() + ")";
        }
        this.messages().send(sender(ctx), "seed.current",
            Placeholder.component("seed", clickable),
            Placeholder.unparsed("mode", mode));
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------------------------------------------------
    // timer
    // ---------------------------------------------------------------------------------------------------------

    private LiteralArgumentBuilder<CommandSourceStack> timerNode() {
        return Commands.literal("timer").requires(permission("timer"))
            .executes(this::timerStatus)
            .then(Commands.literal("start").executes(ctx -> {
                this.timers.startAll(this.participants());
                return this.timerFeedback(ctx, "timer.started");
            }))
            .then(Commands.literal("pause").executes(ctx -> {
                this.timers.pauseAll();
                return this.timerFeedback(ctx, "timer.paused");
            }))
            .then(Commands.literal("resume").executes(ctx -> {
                this.timers.resumeAll();
                return this.timerFeedback(ctx, "timer.resumed");
            }))
            .then(Commands.literal("reset").executes(ctx -> {
                this.timers.resetAll();
                return this.timerFeedback(ctx, "timer.reset");
            }))
            .then(Commands.literal("set")
                // greedy: "1:30:00" contains ':' which is not allowed in an unquoted word
                .then(Commands.argument("time", StringArgumentType.greedyString()).executes(ctx -> {
                    Duration time = Durations.parse(StringArgumentType.getString(ctx, "time"));
                    if (time == null) {
                        this.messages().send(sender(ctx), "invalid-duration");
                        return 0;
                    }
                    this.timers.setAll(time.toMillis(), this.participants());
                    this.game.saveAsync();
                    this.messages().send(sender(ctx), "timer.set", Placeholder.unparsed("time", this.game.formatPrecise(time.toMillis())));
                    return Command.SINGLE_SUCCESS;
                })))
            .then(Commands.literal("mode")
                .then(Commands.literal("rta").executes(ctx -> this.setTimerMode(ctx, TimerMode.RTA)))
                .then(Commands.literal("igt").executes(ctx -> this.setTimerMode(ctx, TimerMode.IGT))))
            .then(Commands.literal("scope")
                .then(Commands.literal("global").executes(ctx -> this.setTimerScope(ctx, TimerScope.GLOBAL)))
                .then(Commands.literal("individual").executes(ctx -> this.setTimerScope(ctx, TimerScope.INDIVIDUAL))))
            .then(Commands.literal("trigger")
                .then(Commands.literal("auto").executes(ctx -> this.setTrigger(ctx, StartTrigger.AUTO)))
                .then(Commands.literal("first-input").executes(ctx -> this.setTrigger(ctx, StartTrigger.FIRST_INPUT)))
                .then(Commands.literal("manual").executes(ctx -> this.setTrigger(ctx, StartTrigger.MANUAL))));
    }

    private List<Player> participants() {
        List<Player> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (this.plugin.worlds().isGameWorld(player.getWorld())) {
                players.add(player);
            }
        }
        return players;
    }

    private int timerFeedback(CommandContext<CommandSourceStack> ctx, String key) {
        this.game.saveAsync();
        this.messages().send(sender(ctx), key);
        return Command.SINGLE_SUCCESS;
    }

    private int setTimerMode(CommandContext<CommandSourceStack> ctx, TimerMode mode) {
        this.plugin.setConfigValue("timer.mode", mode.name());
        this.messages().send(sender(ctx), "timer.mode-changed", Placeholder.unparsed("mode", mode.name()));
        return Command.SINGLE_SUCCESS;
    }

    private int setTimerScope(CommandContext<CommandSourceStack> ctx, TimerScope scope) {
        TimerScope previous = this.timers.scope();
        if (previous != scope) {
            // carry the current time over so switching mid-run does not lose it
            if (scope == TimerScope.INDIVIDUAL) {
                for (Player player : this.participants()) {
                    this.timers.timer(player.getUniqueId()).copyFrom(this.timers.global());
                }
            } else {
                RunTimer longest = null;
                for (RunTimer timer : this.timers.individualTimers().values()) {
                    if (longest == null || timer.rtaMillis() > longest.rtaMillis()) {
                        longest = timer;
                    }
                }
                if (longest != null) {
                    this.timers.global().copyFrom(longest);
                }
            }
            this.plugin.goals().migrateProgress(scope, this.participants());
        }
        this.plugin.setConfigValue("timer.scope", scope.name());
        this.game.saveAsync();
        this.messages().send(sender(ctx), "timer.scope-changed", Placeholder.unparsed("scope", scope.name()));
        return Command.SINGLE_SUCCESS;
    }

    private int setTrigger(CommandContext<CommandSourceStack> ctx, StartTrigger trigger) {
        this.plugin.setConfigValue("timer.start", trigger.name());
        this.messages().send(sender(ctx), "timer.trigger-changed", Placeholder.unparsed("trigger", trigger.name()));
        return Command.SINGLE_SUCCESS;
    }

    private int timerStatus(CommandContext<CommandSourceStack> ctx) {
        RunTimer timer = sender(ctx) instanceof Player player ? this.timers.displayed(player) : this.timers.global();
        Settings.Timer config = this.settings().timer();
        this.messages().send(sender(ctx), "timer.status",
            Placeholder.unparsed("rta", TimeFormat.format(timer.rtaMillis(), 3, config.style())),
            Placeholder.unparsed("igt", TimeFormat.format(timer.igtMillis(), 3, config.style())),
            Placeholder.unparsed("state", timer.state().name().toLowerCase(Locale.ROOT)),
            Placeholder.unparsed("mode", config.mode().name()),
            Placeholder.unparsed("scope", config.scope().name()),
            Placeholder.unparsed("trigger", config.start().name()));
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------------------------------------------------
    // goal
    // ---------------------------------------------------------------------------------------------------------

    private LiteralArgumentBuilder<CommandSourceStack> goalNode() {
        SuggestionProvider<CommandSourceStack> entities = this.suggest(() -> Registry.ENTITY_TYPE.stream()
            .filter(type -> type.isAlive()).map(type -> type.getKey().asString()));
        SuggestionProvider<CommandSourceStack> blocks = this.suggest(() -> Registry.BLOCK.keyStream().map(NamespacedKey::asString));
        SuggestionProvider<CommandSourceStack> items = this.suggest(() -> Registry.ITEM.keyStream().map(NamespacedKey::asString));
        SuggestionProvider<CommandSourceStack> advancements = this.suggest(() -> {
            List<String> keys = new ArrayList<>();
            Iterator<Advancement> iterator = Bukkit.advancementIterator();
            while (iterator.hasNext()) {
                Advancement advancement = iterator.next();
                if (advancement.getDisplay() != null) {
                    keys.add(advancement.getKey().asString());
                }
            }
            return keys.stream();
        });

        return Commands.literal("goal").requires(permission("goal"))
            .executes(this::showGoal)
            .then(Commands.literal("none").executes(ctx -> this.setGoal(ctx, Goal.NONE)))
            .then(Commands.literal("beat").executes(ctx -> this.setGoal(ctx, new Goal(GoalType.BEAT_GAME, null, 1))))
            .then(Commands.literal("dragon").executes(ctx ->
                this.setGoal(ctx, new Goal(GoalType.KILL_ENTITY, NamespacedKey.minecraft("ender_dragon"), 1))))
            .then(this.targetGoal("kill", GoalType.KILL_ENTITY, entities))
            .then(Commands.literal("dimension")
                .then(Commands.literal("nether").executes(ctx -> this.setGoal(ctx, new Goal(GoalType.ENTER_DIMENSION, GoalService.NETHER, 1))))
                .then(Commands.literal("end").executes(ctx -> this.setGoal(ctx, new Goal(GoalType.ENTER_DIMENSION, GoalService.END, 1))))
                .then(Commands.literal("overworld").executes(ctx -> this.setGoal(ctx, new Goal(GoalType.ENTER_DIMENSION, GoalService.OVERWORLD, 1)))))
            .then(this.targetGoal("advancement", GoalType.ADVANCEMENT, advancements))
            .then(this.targetGoal("break", GoalType.BREAK_BLOCK, blocks))
            .then(this.targetGoal("item", GoalType.OBTAIN_ITEM, items));
    }

    private LiteralArgumentBuilder<CommandSourceStack> targetGoal(String name, GoalType type, SuggestionProvider<CommandSourceStack> suggestions) {
        var target = Commands.argument("target", ArgumentTypes.namespacedKey())
            .suggests(suggestions)
            .executes(ctx -> this.setGoal(ctx, type, ctx.getArgument("target", NamespacedKey.class), 1));
        if (type.supportsAmount()) {
            target.then(Commands.argument("amount", IntegerArgumentType.integer(1, 100_000))
                .executes(ctx -> this.setGoal(ctx, type, ctx.getArgument("target", NamespacedKey.class), IntegerArgumentType.getInteger(ctx, "amount"))));
        }
        return Commands.literal(name).then(target);
    }

    private int setGoal(CommandContext<CommandSourceStack> ctx, GoalType type, NamespacedKey target, int amount) {
        String error = GoalService.validate(type, target);
        if (error != null) {
            this.messages().send(sender(ctx), error, Placeholder.unparsed("target", target.asString()));
            return 0;
        }
        return this.setGoal(ctx, new Goal(type, target, amount));
    }

    private int setGoal(CommandContext<CommandSourceStack> ctx, Goal goal) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("goal.type", goal.type().name());
        values.put("goal.target", goal.target() == null ? "" : goal.target().asString());
        values.put("goal.amount", goal.amount());
        this.plugin.setConfigValues(values);
        this.plugin.goals().resetProgress();
        this.game.saveAsync();
        this.messages().send(sender(ctx), "goal.changed", this.game.goalPlaceholder());
        return Command.SINGLE_SUCCESS;
    }

    private int showGoal(CommandContext<CommandSourceStack> ctx) {
        Goal goal = this.plugin.goals().goal();
        Player player = sender(ctx) instanceof Player p ? p : null;
        this.messages().send(sender(ctx), "goal.current", this.game.goalPlaceholder(),
            Placeholder.unparsed("progress", goal.isNone() ? "-" : this.plugin.goals().progress(player) + "/" + goal.amount()));
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------------------------------------------------
    // deaths
    // ---------------------------------------------------------------------------------------------------------

    private int setBackups(CommandContext<CommandSourceStack> ctx, boolean enabled) {
        this.plugin.setConfigValue("backups.enabled", enabled);
        return this.backupStatus(ctx);
    }

    private int backupStatus(CommandContext<CommandSourceStack> ctx) {
        Settings.Backups backups = this.settings().backups();
        Path root = this.game.backupRoot().toAbsolutePath().normalize();
        Path serverRoot = Path.of("").toAbsolutePath();
        this.messages().send(sender(ctx), backups.enabled() ? "backup.status-on" : "backup.status-off",
            Placeholder.unparsed("folder", (root.startsWith(serverRoot) ? serverRoot.relativize(root) : root).toString().replace('\\', '/')),
            Placeholder.unparsed("keep", backups.keep() == 0 ? "all" : Integer.toString(backups.keep())));
        return Command.SINGLE_SUCCESS;
    }

    private int showLeaderboard(CommandContext<CommandSourceStack> ctx) {
        this.messages().send(sender(ctx), "leaderboard.chat-header");
        for (Component line : this.hud.leaderboardLines()) {
            sender(ctx).sendMessage(line);
        }
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------------------------------------------------
    // worlds
    // ---------------------------------------------------------------------------------------------------------

    private LiteralArgumentBuilder<CommandSourceStack> worldsNode() {
        return Commands.literal("worlds").requires(permission("worlds"))
            .executes(this::listWorlds)
            .then(Commands.literal("nether")
                .then(Commands.literal("on").executes(ctx -> this.selectWorld(ctx, "worlds.nether", true)))
                .then(Commands.literal("off").executes(ctx -> this.selectWorld(ctx, "worlds.nether", false))))
            .then(Commands.literal("end")
                .then(Commands.literal("on").executes(ctx -> this.selectWorld(ctx, "worlds.end", true)))
                .then(Commands.literal("off").executes(ctx -> this.selectWorld(ctx, "worlds.end", false))));
    }

    private int selectWorld(CommandContext<CommandSourceStack> ctx, String path, boolean enabled) {
        this.plugin.setConfigValue(path, enabled);
        return this.listWorlds(ctx);
    }

    /** Shows which worlds a reset deletes and where they are stored. */
    private int listWorlds(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        GameWorlds worlds = this.plugin.worlds();
        Path serverRoot = Path.of("").toAbsolutePath();
        this.messages().send(sender, "worlds.header");
        for (GameWorlds.Dimension dimension : GameWorlds.Dimension.values()) {
            String folder;
            try {
                Path absolute = worlds.folder(dimension).toAbsolutePath().normalize();
                folder = absolute.startsWith(serverRoot) ? serverRoot.relativize(absolute).toString() : absolute.toString();
            } catch (IllegalStateException e) {
                folder = "?";
            }
            boolean enabled = worlds.isEnabled(dimension);
            World loaded = worlds.world(dimension);
            this.messages().send(sender, "worlds.entry",
                Placeholder.component("dimension", this.messages().get("dimension." + dimension.path().replace('_', '-'))),
                Placeholder.unparsed("key", worlds.key(dimension).asString()),
                Placeholder.unparsed("folder", folder.replace('\\', '/')),
                Placeholder.component("status", this.messages().get(enabled ? "worlds.status-reset" : "worlds.status-kept")),
                Placeholder.component("loaded", this.messages().get(loaded != null ? "worlds.loaded" : "worlds.not-loaded")));
        }
        this.messages().send(sender, "worlds.footer", Placeholder.unparsed("key", worlds.key(GameWorlds.Dimension.OVERWORLD).asString()));
        if (sender instanceof Player player) {
            this.messages().send(player, "worlds.you-are-in", Placeholder.unparsed("key", player.getWorld().getKey().asString()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private LiteralArgumentBuilder<CommandSourceStack> deathsNode() {
        return Commands.literal("deaths").requires(permission("deaths"))
            .executes(this::showDeaths)
            .then(Commands.literal("off").executes(ctx -> this.setDeaths(ctx, DeathMode.OFF, null)))
            .then(Commands.literal("hardcore").executes(ctx -> this.setDeaths(ctx, DeathMode.HARDCORE, null)))
            .then(Commands.literal("lives")
                .then(Commands.argument("lives", IntegerArgumentType.integer(1, 1000))
                    .executes(ctx -> this.setDeaths(ctx, DeathMode.SHARED_LIVES, IntegerArgumentType.getInteger(ctx, "lives")))));
    }

    private int setDeaths(CommandContext<CommandSourceStack> ctx, DeathMode mode, Integer lives) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("deaths.mode", mode.name());
        if (lives != null) {
            values.put("deaths.shared-lives", lives);
        }
        this.plugin.setConfigValues(values);
        return this.showDeaths(ctx);
    }

    private int showDeaths(CommandContext<CommandSourceStack> ctx) {
        Settings.Deaths deaths = this.settings().deaths();
        String key = switch (deaths.mode()) {
            case OFF -> "deaths.status-off";
            case HARDCORE -> "deaths.status-hardcore";
            case SHARED_LIVES -> "deaths.status-lives";
        };
        this.messages().send(sender(ctx), key,
            Placeholder.unparsed("deaths", Integer.toString(this.game.deaths())),
            Placeholder.unparsed("lives", Integer.toString(this.game.livesLeft())),
            Placeholder.unparsed("max", Integer.toString(deaths.sharedLives())));
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------------------------------------------------
    // auto reset
    // ---------------------------------------------------------------------------------------------------------

    private LiteralArgumentBuilder<CommandSourceStack> autoResetNode() {
        return Commands.literal("autoreset").requires(permission("autoreset"))
            .executes(this::autoResetStatus)
            .then(Commands.literal("off").executes(ctx -> {
                this.autoReset.disable();
                this.messages().send(sender(ctx), "auto-reset.disabled");
                return Command.SINGLE_SUCCESS;
            }))
            .then(Commands.literal("in")
                // greedy like "at", an optional trailing "loop" is parsed by hand
                .then(Commands.argument("duration", StringArgumentType.greedyString())
                    .suggests(this.suggest(() -> Stream.of("30m", "1h loop", "2h30m", "1d loop")))
                    .executes(this::autoResetIn)))
            .then(Commands.literal("at")
                // greedy because "18:00" is not a valid unquoted word; an optional trailing "loop" is parsed by hand
                .then(Commands.argument("time", StringArgumentType.greedyString())
                    .suggests(this.suggest(() -> Stream.of("18:00", "18:00 loop", "00:00 loop")))
                    .executes(this::autoResetAt)));
    }

    private int autoResetIn(CommandContext<CommandSourceStack> ctx) {
        String[] parts = StringArgumentType.getString(ctx, "duration").trim().split("\\s+");
        boolean loop = parts.length == 2 && parts[1].equalsIgnoreCase("loop");
        Duration duration = parts.length > 2 || (parts.length == 2 && !loop) ? null : Durations.parse(parts[0]);
        if (duration == null || duration.toSeconds() < 1) {
            this.messages().send(sender(ctx), "invalid-duration");
            return 0;
        }
        this.autoReset.scheduleIn(duration, loop);
        return this.autoResetStatus(ctx);
    }

    private int autoResetAt(CommandContext<CommandSourceStack> ctx) {
        String[] parts = StringArgumentType.getString(ctx, "time").trim().split("\\s+");
        boolean loop = parts.length == 2 && parts[1].equalsIgnoreCase("loop");
        LocalTime time = parts.length > 2 || (parts.length == 2 && !loop) ? null : AutoResetScheduler.parseClock(parts[0]);
        if (time == null) {
            this.messages().send(sender(ctx), "auto-reset.invalid-time");
            return 0;
        }
        this.autoReset.scheduleAt(time, loop);
        return this.autoResetStatus(ctx);
    }

    private int autoResetStatus(CommandContext<CommandSourceStack> ctx) {
        if (this.autoReset.kind() == AutoResetScheduler.Kind.OFF) {
            this.messages().send(sender(ctx), "auto-reset.status-off");
            return Command.SINGLE_SUCCESS;
        }
        this.messages().send(sender(ctx), "auto-reset.status",
            Placeholder.unparsed("schedule", this.autoReset.describe()),
            Placeholder.unparsed("loop", this.autoReset.loop() ? "yes" : "no"),
            Placeholder.unparsed("remaining", TimeFormat.compact(this.autoReset.secondsRemaining())),
            Placeholder.unparsed("next", this.autoReset.nextAsText()));
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------------------------------------------------
    // misc
    // ---------------------------------------------------------------------------------------------------------

    private int toggleScoreboard(CommandContext<CommandSourceStack> ctx) {
        if (!(sender(ctx) instanceof Player player)) {
            this.messages().send(sender(ctx), "players-only");
            return 0;
        }
        boolean visible = this.hud.toggleSidebar(player);
        this.game.saveAsync();
        this.messages().send(player, visible ? "scoreboard.shown" : "scoreboard.hidden");
        return Command.SINGLE_SUCCESS;
    }

    private int toggleRadar(CommandContext<CommandSourceStack> ctx) {
        boolean enabled = this.hud.toggleRadar();
        this.game.saveAsync();
        this.messages().send(sender(ctx), enabled ? "radar.enabled" : "radar.disabled");
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        String warning = this.plugin.reload();
        this.messages().send(sender(ctx), "reloaded");
        if (warning != null) {
            this.messages().send(sender(ctx), "reload-warning", Placeholder.unparsed("warning", warning));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int status(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        Settings settings = this.settings();
        RunTimer timer = sender instanceof Player player ? this.timers.displayed(player) : this.timers.global();
        TagResolver resolvers = TagResolver.resolver(
            Placeholder.unparsed("run", Integer.toString(this.game.run())),
            Placeholder.unparsed("phase", this.game.phase().name().toLowerCase(Locale.ROOT)),
            Placeholder.unparsed("outcome", this.game.outcome().name().toLowerCase(Locale.ROOT)),
            // the seed is only shown to players who may see it via /mr seed
            // admins always see the seed, everyone else only when seed.show is on
            Placeholder.unparsed("seed", sender.hasPermission(PERM + "seed") || settings.showSeed()
                ? Long.toString(this.game.seed()) : this.messages().raw("seed.hidden")),
            Placeholder.unparsed("seed-mode", settings.seedMode().name().toLowerCase(Locale.ROOT)),
            Placeholder.unparsed("time", this.game.formatPrecise(timer.millis(settings.timer().mode()))),
            Placeholder.unparsed("timer-mode", settings.timer().mode().name()),
            Placeholder.unparsed("scope", settings.timer().scope().name().toLowerCase(Locale.ROOT)),
            this.game.goalPlaceholder(),
            Placeholder.unparsed("deaths", Integer.toString(this.game.deaths())),
            Placeholder.unparsed("death-mode", settings.deaths().mode().name().toLowerCase(Locale.ROOT)),
            Placeholder.unparsed("auto-reset", this.autoReset.describe()),
            Placeholder.unparsed("paused-players", Integer.toString(this.game.pausedPlayers())),
            Placeholder.unparsed("radar", this.hud.isRadarEnabled() ? "on" : "off"));
        for (Component line : this.messages().list("status", resolvers)) {
            sender.sendMessage(line);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int help(CommandContext<CommandSourceStack> ctx) {
        for (Component line : this.messages().list("help")) {
            sender(ctx).sendMessage(line);
        }
        return Command.SINGLE_SUCCESS;
    }

    private SuggestionProvider<CommandSourceStack> suggest(Supplier<Stream<String>> values) {
        return (ctx, builder) -> this.complete(builder, values.get());
    }

    private CompletableFuture<Suggestions> complete(SuggestionsBuilder builder, Stream<String> values) {
        String remaining = builder.getRemainingLowerCase();
        values.filter(value -> {
                String lower = value.toLowerCase(Locale.ROOT);
                return lower.startsWith(remaining)
                    || (lower.startsWith("minecraft:") && lower.substring("minecraft:".length()).startsWith(remaining));
            })
            .sorted()
            .limit(200)
            .forEach(builder::suggest);
        return builder.buildFuture();
    }
}
