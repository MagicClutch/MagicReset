package dev.magicclutch.magicreset.menu;

import dev.magicclutch.magicreset.MagicReset;
import dev.magicclutch.magicreset.config.Messages;
import dev.magicclutch.magicreset.config.Settings;
import dev.magicclutch.magicreset.game.AutoResetOn;
import dev.magicclutch.magicreset.game.DeathMode;
import dev.magicclutch.magicreset.game.GameManager;
import dev.magicclutch.magicreset.game.SeedMode;
import dev.magicclutch.magicreset.goal.Goal;
import dev.magicclutch.magicreset.goal.GoalService;
import dev.magicclutch.magicreset.goal.GoalType;
import dev.magicclutch.magicreset.hud.HudService;
import dev.magicclutch.magicreset.manhunt.ManhuntService;
import dev.magicclutch.magicreset.stats.Leaderboard;
import dev.magicclutch.magicreset.timer.RunTimer;
import dev.magicclutch.magicreset.timer.StartTrigger;
import dev.magicclutch.magicreset.timer.TimerMode;
import dev.magicclutch.magicreset.timer.TimerService;
import dev.magicclutch.magicreset.util.Durations;
import dev.magicclutch.magicreset.util.TimeFormat;
import dev.magicclutch.magicreset.world.GameWorlds;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * The /magicreset and /manhunt menus built with server-side dialog screens (Minecraft 1.21.6+).
 *
 * <p>Every config.yml setting can be changed here. Plain settings are described once as {@link SettingField}s
 * per section; the screen and the saving are generated from that. Goal, manhunt, auto reset, timer control
 * and the leaderboard have their own screens. Every section needs the permission of the matching command,
 * checked again on every click.</p>
 */
public final class DialogMenu {

    private static final ClickCallback.Options ONCE = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(15)).build();
    private static final int WIDE = 300;
    private static final String PERM = "magicreset.command.";
    private static final List<String> WORLD_TYPES = List.of("NORMAL", "FLAT", "LARGE_BIOMES", "AMPLIFIED");

    /** On/off switches of the main page: config path and the permission node they need. */
    private static final List<String[]> QUICK_SWITCHES = List.of(
        new String[] {"sidebar.enabled", "reload"},
        new String[] {"worlds.locator-bar", "locatorbar"});

    private static final SettingField AUTO_RESET = SettingField.choice("reset.auto", AutoResetOn.values());
    private static final SettingField AUTO_RESET_DELAY = SettingField.number("reset.auto-delay", 0, 60, 1);
    private static final SettingField RESET_CONFIRM = SettingField.bool("reset.confirm");

    /** A screen of plain settings. */
    private record Section(String id, String permission, String parent, List<SettingField> fields) {
    }

    private final MagicReset plugin;
    private final GameManager game;
    private final TimerService timers;
    private final HudService hud;
    /** Counts opened screens, so a click can tell whether it opened one. */
    private int shown;

    public DialogMenu(MagicReset plugin, GameManager game, TimerService timers, HudService hud) {
        this.plugin = plugin;
        this.game = game;
        this.timers = timers;
        this.hud = hud;
    }

    private Messages messages() {
        return this.plugin.messages();
    }

    private Settings settings() {
        return this.plugin.settings();
    }

    private Component text(String key, TagResolver... resolvers) {
        return this.messages().get("menu." + key, resolvers);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Sections with plain settings
    // ---------------------------------------------------------------------------------------------------------

    private List<Section> sections() {
        return List.of(
            new Section("timer", PERM + "timer", "main", List.of(
                SettingField.bool("timer.enabled"),
                SettingField.choice("timer.mode", TimerMode.values()),
                SettingField.choice("timer.start", StartTrigger.values()),
                SettingField.bool("timer.stop-when-empty"),
                SettingField.text("timer.format", 32, value -> value.equalsIgnoreCase("auto") || TimeFormat.isValidPattern(value) ? null : "timer.format-invalid"))),
            new Section("deaths", PERM + "deaths", "main", List.of(
                SettingField.choice("deaths.mode", DeathMode.values()),
                SettingField.number("deaths.shared-lives", 1, 50, 1),
                SettingField.bool("timer.pause-while-dead"))),
            new Section("worlds", PERM + "worlds", "main", List.of(
                SettingField.bool("worlds.nether"),
                SettingField.bool("worlds.end"),
                SettingField.choice("worlds.type", WORLD_TYPES),
                SettingField.choice("worlds.difficulty", Difficulty.values()),
                SettingField.bool("worlds.generate-structures"),
                SettingField.text("worlds.world-border", 9, DialogMenu::validateNonNegativeNumber),
                SettingField.map("worlds.game-rules"))),
            new Section("players", PERM + "reset", "main", List.of(
                SettingField.bool("reset.player.clear-inventory"),
                SettingField.bool("reset.player.clear-ender-chest"),
                SettingField.bool("reset.player.reset-health"),
                SettingField.bool("reset.player.reset-hunger"),
                SettingField.bool("reset.player.reset-experience"),
                SettingField.bool("reset.player.clear-effects"),
                SettingField.bool("reset.player.revoke-advancements"),
                SettingField.bool("reset.player.revoke-recipes"),
                SettingField.bool("reset.player.reset-respawn-point"),
                SettingField.choice("reset.player.game-mode", GameMode.values())))
        );
    }

    private Section section(String id) {
        return this.sections().stream().filter(section -> section.id().equals(id)).findFirst().orElseThrow();
    }

    private static @Nullable String validateNonNegativeNumber(String value) {
        try {
            return Double.parseDouble(value.trim()) >= 0 ? null : "menu.invalid-number";
        } catch (NumberFormatException e) {
            return "menu.invalid-number";
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Main menu
    // ---------------------------------------------------------------------------------------------------------

    /** Opens the admin menu, or the info view for players without any admin permission. */
    public void open(Player player) {
        this.safely(player, "main menu", () -> this.openMain(player));
    }

    /** The overview: one button per topic, every setting lives on exactly one of these screens. */
    private void openMain(Player player) {
        if (!this.isAdmin(player)) {
            // the menu is for OPs and players with a MagicReset permission only
            this.messages().send(player, "menu.admins-only");
            return;
        }
        List<ActionButton> buttons = new ArrayList<>();
        if (player.hasPermission(PERM + "reset")) {
            buttons.add(this.button(this.text("button.reset"), this.text("tooltip.reset"), (response, audience) -> this.openReset(player)));
        }
        if (player.hasPermission(PERM + "goal")) {
            buttons.add(this.button(this.text("button.game", Placeholder.component("mode", this.text("mode." + this.currentMode()))),
                this.text("tooltip.game"), (response, audience) -> this.openGoal(player)));
        }
        if (player.hasPermission(PERM + "timer")) {
            buttons.add(this.button(this.text("button.timer"), this.text("tooltip.timer"), (response, audience) -> this.openTimer(player)));
        }
        this.sectionButton(buttons, player, "deaths");
        this.sectionButton(buttons, player, "worlds");
        this.sectionButton(buttons, player, "players");
        for (String[] quick : QUICK_SWITCHES) {
            if (player.hasPermission(PERM + quick[1])) {
                buttons.add(this.switchButton(player, SettingField.bool(quick[0])));
            }
        }
        buttons.add(this.button(this.text("button.leaderboard"), null, (response, audience) -> this.openInfoUnsafe(player)));

        DialogBase base = DialogBase.builder(this.text("title.main"))
            .body(List.of(this.header(this.statusText())))
            .canCloseWithEscape(true)
            .build();
        this.show(player, base, DialogType.multiAction(buttons).columns(2).exitAction(this.closeButton()).build());
    }

    /** "Sidebar: On" - a click flips it and shows the page again. */
    private ActionButton switchButton(Player player, SettingField field) {
        boolean on = this.plugin.getConfig().getBoolean(field.path());
        Component label = this.text("button.switch", Placeholder.component("name", this.text(field.labelKey())),
            Placeholder.component("value", this.text(on ? "state.enabled" : "state.disabled")));
        return this.button(label, null, (response, audience) -> {
            this.plugin.setConfigValue(field.path(), !on);
            this.plugin.worlds().applyLocatorBar();
            this.openMain(player);
        });
    }

    private void sectionButton(List<ActionButton> buttons, Player player, String id) {
        Section section = this.section(id);
        if (player.hasPermission(section.permission())) {
            buttons.add(this.button(this.text("button." + id), null, (response, audience) -> this.openSection(player, section)));
        }
    }

    private boolean isAdmin(Player player) {
        for (String node : List.of("reset", "goal", "timer", "deaths", "worlds", "seed", "backup", "autoreset", "reload")) {
            if (player.hasPermission(PERM + node)) {
                return true;
            }
        }
        return player.hasPermission("magicreset.manhunt");
    }

    /** "speedrun", "manhunt" or "free" depending on the settings. */
    private String currentMode() {
        if (this.settings().manhunt().enabled()) {
            return "manhunt";
        }
        return this.settings().timer().enabled() || !this.plugin.goals().goal().isNone() ? "speedrun" : "free";
    }

    /** One click presets: free play = no timer, no goal, no manhunt; speedrun = timer and a goal; manhunt. */
    private void switchMode(Player player, String mode) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("manhunt.enabled", mode.equals("manhunt"));
        if (mode.equals("free")) {
            values.put("timer.enabled", false);
            values.put("goal.type", GoalType.NONE.name());
            values.put("goal.target", "");
            values.put("deaths.mode", DeathMode.OFF.name());
        } else {
            values.put("timer.enabled", true);
            if (this.plugin.goals().goal().isNone()) {
                values.put("goal.type", GoalType.BEAT_GAME.name());
                values.put("goal.target", "");
            }
        }
        this.plugin.setConfigValues(values);
        this.plugin.manhunt().onModeChanged();
        this.messages().send(player, "menu.mode-set", Placeholder.component("mode", this.text("mode." + mode)));
    }

    private void openParent(Player player, String parent) {
        if (parent.equals("game")) {
            this.openGoal(player);
        } else {
            this.openMain(player);
        }
    }

    private Component statusText() {
        // no running time here: a screen is a snapshot, the time would jump on every click (the action bar has it)
        return this.text("status",
            Placeholder.unparsed("run", Integer.toString(this.game.run())),
            Placeholder.component("mode", this.text("mode." + this.currentMode())),
            this.game.goalPlaceholder());
    }

    /** Read-only view: status and the leaderboard of the current goal. */
    public void openInfo(Player player) {
        this.safely(player, "info", () -> this.openInfoUnsafe(player));
    }

    private void openInfoUnsafe(Player player) {
        Component board = Component.empty();
        for (Component line : this.hud.leaderboardLines()) {
            board = board.append(line).append(Component.newline());
        }
        List<ActionButton> buttons = new ArrayList<>();
        if (player.hasPermission(PERM + "leaderboard")) {
            buttons.add(this.button(this.text("button.leaderboard-clear"), null, (response, audience) -> {
                this.plugin.leaderboard().clear(Leaderboard.category(this.plugin.goals().goal(), this.timers.mode(), this.plugin.manhunt().isEnabled()));
                this.messages().send(player, "leaderboard.cleared", this.game.goalPlaceholder());
                this.openInfoUnsafe(player);
            }));
        }
        DialogBase base = DialogBase.builder(this.text("title.info"))
            .body(List.of(this.header(this.statusText()), this.header(board)))
            .canCloseWithEscape(true)
            .build();
        if (buttons.isEmpty()) {
            this.show(player, base, DialogType.notice(this.closeButton()));
        } else {
            this.show(player, base, DialogType.multiAction(buttons).exitAction(this.backButton(player, "main")).build());
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Generated settings screens
    // ---------------------------------------------------------------------------------------------------------

    private void openSection(Player player, Section section) {
        FileConfiguration config = this.plugin.getConfig();
        List<DialogInput> inputs = new ArrayList<>();
        for (SettingField field : section.fields()) {
            inputs.add(this.input(field, config));
        }
        this.form(player, "title." + section.id(), this.text("body." + section.id(), Placeholder.unparsed("worlds", this.game.resetWorldList())), inputs, section.permission(), section.parent(), response -> {
            Map<String, Object> values = new LinkedHashMap<>();
            for (SettingField field : section.fields()) {
                if (!this.read(player, field, response, config, values)) {
                    return false;
                }
            }
            this.plugin.setConfigValues(values);
            // settings that act on running worlds right away
            this.plugin.worlds().applyLocatorBar();
            return true;
        });
    }

    private DialogInput input(SettingField field, FileConfiguration config) {
        Component label = this.text(field.labelKey());
        return switch (field.kind()) {
            case BOOL -> DialogInput.bool(field.inputName(), label).initial(config.getBoolean(field.path())).build();
            case INT -> this.slider(field.inputName(), label, field.min(), field.max(), field.step(), config.getInt(field.path()));
            case CHOICE -> {
                String current = String.valueOf(config.getString(field.path(), field.options().getFirst())).toUpperCase(Locale.ROOT);
                List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
                for (String option : field.options()) {
                    entries.add(SingleOptionDialogInput.OptionEntry.create(option, this.text(SettingField.optionKey(option)), option.equals(current)));
                }
                yield DialogInput.singleOption(field.inputName(), label, entries).width(WIDE).build();
            }
            case TEXT -> this.textInput(field.inputName(), label, String.valueOf(config.get(field.path(), "")), field.maxLength());
            case INT_LIST -> this.textInput(field.inputName(), label, String.join(", ",
                config.getIntegerList(field.path()).stream().map(String::valueOf).toList()), field.maxLength());
            case MAP -> {
                ConfigurationSection section = config.getConfigurationSection(field.path());
                List<String> pairs = new ArrayList<>();
                if (section != null) {
                    for (String key : section.getKeys(false)) {
                        pairs.add(key.replace("minecraft:", "") + "=" + section.get(key));
                    }
                }
                yield this.textInput(field.inputName(), label, String.join(", ", pairs), field.maxLength());
            }
        };
    }

    /**
     * Reads one field from the dialog response into {@code values}.
     *
     * @return {@code false} if the value is invalid (a message was sent)
     */
    private boolean read(Player player, SettingField field, DialogResponseView response, FileConfiguration config, Map<String, Object> values) {
        String name = field.inputName();
        switch (field.kind()) {
            case BOOL -> values.put(field.path(), boolOf(response, name, config.getBoolean(field.path())));
            case INT -> values.put(field.path(), intOf(response, name, config.getInt(field.path())));
            case CHOICE -> values.put(field.path(), option(response, name, String.valueOf(config.getString(field.path()))));
            case TEXT -> {
                String value = response.getText(name);
                value = value == null ? String.valueOf(config.get(field.path(), "")) : value.trim();
                String error = field.validator() == null ? null : field.validator().apply(value);
                if (error != null) {
                    this.messages().send(player, error, Placeholder.unparsed("value", value),
                        Placeholder.component("setting", this.text(field.labelKey())));
                    return false;
                }
                // the world border is a number in the config
                values.put(field.path(), field.path().equals("worlds.world-border") ? Double.parseDouble(value) : value);
            }
            case INT_LIST -> {
                List<Integer> numbers = new ArrayList<>();
                String raw = response.getText(name);
                for (String part : (raw == null ? "" : raw).split("[,\\s]+")) {
                    if (part.isBlank()) {
                        continue;
                    }
                    try {
                        numbers.add(Integer.parseInt(part.trim()));
                    } catch (NumberFormatException e) {
                        this.messages().send(player, "menu.invalid-number", Placeholder.unparsed("value", part),
                            Placeholder.component("setting", this.text(field.labelKey())));
                        return false;
                    }
                }
                values.put(field.path(), numbers);
            }
            case MAP -> {
                Map<String, Object> rules = new LinkedHashMap<>();
                String raw = response.getText(name);
                for (String part : (raw == null ? "" : raw).split(",")) {
                    if (part.isBlank()) {
                        continue;
                    }
                    String[] pair = part.split("=", 2);
                    if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank()) {
                        this.messages().send(player, "menu.invalid-rule", Placeholder.unparsed("value", part.trim()));
                        return false;
                    }
                    String key = pair[0].trim().toLowerCase(Locale.ROOT);
                    rules.put(key.contains(":") ? key : "minecraft:" + key, parseScalar(pair[1].trim()));
                }
                values.put(field.path(), rules);
            }
        }
        return true;
    }

    private static Object parseScalar(String value) {
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(value);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return value;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Reset now
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Everything about a reset on one screen: the seed, which worlds, the countdowns and backups. "Reset now"
     * saves the settings and resets, "Save" only saves them.
     */
    private void openReset(Player player) {
        Settings.Reset reset = this.settings().reset();
        String seed = this.settings().seedMode() == SeedMode.FIXED ? this.settings().fixedSeed() : "";
        List<DialogInput> inputs = new ArrayList<>();
        inputs.add(this.textInput("seed", this.text("input.seed"), seed, 64));
        inputs.add(DialogInput.bool("show_seed", this.text("field.seed.show")).initial(this.settings().showSeed()).build());
        for (GameWorlds.Dimension dimension : GameWorlds.Dimension.values()) {
            if (this.plugin.worlds().isEnabled(dimension)) {
                inputs.add(DialogInput.bool("reset_" + dimension.name().toLowerCase(Locale.ROOT),
                    this.text("input.reset-" + dimension.name().toLowerCase(Locale.ROOT))).initial(true).build());
            }
        }
        inputs.add(this.slider("countdown", this.text("input.countdown"), 0, 60, 1, reset.countdown()));
        inputs.add(this.slider("limbo", this.text("input.limbo-countdown"), 0, 60, 1, reset.limboCountdown()));
        inputs.add(DialogInput.bool("backups", this.text("field.backups.enabled")).initial(this.settings().backups().enabled()).build());
        inputs.add(this.input(AUTO_RESET, this.plugin.getConfig()));
        inputs.add(this.input(AUTO_RESET_DELAY, this.plugin.getConfig()));
        inputs.add(this.input(RESET_CONFIRM, this.plugin.getConfig()));

        List<ActionButton> buttons = List.of(
            this.button(this.text("button.reset-now"), null, (response, audience) -> {
                if (!player.hasPermission(PERM + "reset")) {
                    return;
                }
                this.saveReset(response, reset);
                Set<GameWorlds.Dimension> selection = EnumSet.noneOf(GameWorlds.Dimension.class);
                for (GameWorlds.Dimension dimension : GameWorlds.Dimension.values()) {
                    if (this.plugin.worlds().isEnabled(dimension) && boolOf(response, "reset_" + dimension.name().toLowerCase(Locale.ROOT), true)) {
                        selection.add(dimension);
                    }
                }
                Settings.Reset saved = this.settings().reset();
                String error = this.game.requestReset(null, saved.countdown(), saved.limboCountdown(), selection);
                if (error != null) {
                    this.messages().send(player, error);
                }
            }),
            this.button(this.text("button.save"), null, (response, audience) -> {
                if (player.hasPermission(PERM + "reset")) {
                    this.saveReset(response, reset);
                    this.messages().send(player, "menu.saved");
                }
                this.openMain(player);
            }));
        DialogBase base = DialogBase.builder(this.text("title.reset"))
            .body(List.of(this.header(this.text("body.reset"))))
            .inputs(inputs)
            .canCloseWithEscape(true)
            .build();
        this.show(player, base, DialogType.multiAction(buttons).columns(2).exitAction(this.backButton(player, "main")).build());
    }

    /** An empty seed field means a random seed on every reset. */
    private void saveReset(DialogResponseView response, Settings.Reset reset) {
        String seed = response.getText("seed");
        seed = seed == null ? "" : seed.trim();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("seed.mode", seed.isEmpty() ? SeedMode.RANDOM.name() : SeedMode.FIXED.name());
        values.put("seed.fixed", seed);
        values.put("seed.show", boolOf(response, "show_seed", this.settings().showSeed()));
        values.put("reset.countdown", intOf(response, "countdown", reset.countdown()));
        values.put("reset.limbo-countdown", intOf(response, "limbo", reset.limboCountdown()));
        values.put("backups.enabled", boolOf(response, "backups", this.settings().backups().enabled()));
        values.put(AUTO_RESET.path(), option(response, AUTO_RESET.inputName(), reset.auto().name()));
        values.put(AUTO_RESET_DELAY.path(), intOf(response, AUTO_RESET_DELAY.inputName(), reset.autoDelay()));
        values.put(RESET_CONFIRM.path(), boolOf(response, RESET_CONFIRM.inputName(), reset.confirm()));
        this.plugin.setConfigValues(values);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Timer control
    // ---------------------------------------------------------------------------------------------------------

    /** One timer screen: switch it on/off, control it and change every timer setting. */
    private void openTimer(Player player) {
        Section section = this.section("timer");
        FileConfiguration config = this.plugin.getConfig();
        List<DialogInput> inputs = new ArrayList<>();
        inputs.add(this.textInput("set_time", this.text("input.timer-set"), "", 16));
        for (SettingField field : section.fields()) {
            inputs.add(this.input(field, config));
        }
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(this.button(this.text("button.save"), null, (response, audience) -> {
            if (!player.hasPermission(PERM + "timer")) {
                return;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            for (SettingField field : section.fields()) {
                if (!this.read(player, field, response, config, values)) {
                    this.openTimer(player);
                    return;
                }
            }
            this.plugin.setConfigValues(values);
            String raw = response.getText("set_time");
            if (raw != null && !raw.isBlank()) {
                Duration time = Durations.parse(raw);
                if (time == null) {
                    this.messages().send(player, "invalid-duration");
                } else {
                    this.timers.setAll(time.toMillis(), this.participants());
                    this.game.saveAsync();
                }
            }
            this.messages().send(player, "menu.saved");
            this.openMain(player);
        }));
        buttons.add(this.timerButton(player, "button.timer-start", () -> this.timers.startAll(this.participants())));
        buttons.add(this.timerButton(player, "button.timer-pause", this.timers::pauseAll));
        buttons.add(this.timerButton(player, "button.timer-resume", this.timers::resumeAll));
        buttons.add(this.timerButton(player, "button.timer-reset", this.timers::resetAll));
        RunTimer timer = this.timers.displayed(player);
        Component body = this.text(this.settings().timer().enabled() ? "body.timer" : "body.timer-off",
            Placeholder.unparsed("time", this.settings().timer().display(timer.millis(this.timers.mode()))),
            Placeholder.unparsed("state", timer.state().name().toLowerCase(Locale.ROOT)),
            Placeholder.unparsed("mode", this.timers.mode().name()));
        DialogBase base = DialogBase.builder(this.text("title.timer"))
            .body(List.of(this.header(body)))
            .inputs(inputs)
            .canCloseWithEscape(true)
            .build();
        this.show(player, base, DialogType.multiAction(buttons).columns(2).exitAction(this.backButton(player, "main")).build());
    }

    private ActionButton timerButton(Player player, String labelKey, Runnable action) {
        return this.button(this.text(labelKey), null, (response, audience) -> {
            if (player.hasPermission(PERM + "timer")) {
                action.run();
                this.game.saveAsync();
            }
            this.openTimer(player);
        });
    }

    private List<Player> participants() {
        List<Player> players = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (this.plugin.worlds().isGameWorld(online.getWorld())) {
                players.add(online);
            }
        }
        return players;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Goal with search
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Mode and goal. The goal is the same for every mode: in a speedrun everybody races for it, in a manhunt the
     * targets have to reach it while the hunters try to kill them.
     */
    private void openGoal(Player player) {
        Goal goal = this.plugin.goals().goal();
        String mode = this.currentMode();
        List<ActionButton> buttons = new ArrayList<>();
        if (player.hasPermission(PERM + "timer")) {
            for (String option : List.of("speedrun", "free", "manhunt")) {
                Component label = this.text(option.equals(mode) ? "button.mode-selected" : "button.mode-option",
                    Placeholder.component("mode", this.text("mode." + option)));
                buttons.add(this.button(label, this.text("tooltip.mode-" + option), (response, audience) -> {
                    this.saveGoalAmount(response, goal);
                    this.switchMode(player, option);
                    if (option.equals("manhunt") && player.hasPermission("magicreset.manhunt")) {
                        this.openManhuntUnsafe(player);
                    } else {
                        this.openGoal(player);
                    }
                }));
            }
            if (mode.equals("manhunt") && player.hasPermission("magicreset.manhunt")) {
                buttons.add(this.button(this.text("button.manhunt"), null, (response, audience) -> {
                    this.saveGoalAmount(response, goal);
                    this.openManhuntUnsafe(player);
                }));
            }
        }
        // goal types start on a new row
        if (buttons.size() % 2 == 1) {
            buttons.add(this.button(this.text("button.leaderboard"), null, (response, audience) -> this.openInfoUnsafe(player)));
        }
        for (GoalType type : GoalType.values()) {
            buttons.add(this.button(this.text(SettingField.optionKey(type.name())), this.text("tooltip.goal-" + type.name().toLowerCase(Locale.ROOT).replace('_', '-')),
                (response, audience) -> {
                    this.saveGoalAmount(response, goal);
                    this.chooseGoalType(player, type);
                }));
        }
        DialogBase base = DialogBase.builder(this.text("title.game"))
            .body(List.of(this.header(this.text("body.game", Placeholder.component("mode", this.text("mode." + mode)), this.game.goalPlaceholder()))))
            .inputs(List.of(this.slider("amount", this.text("input.goal-amount"), 1, 64, 1, goal.amount())))
            .canCloseWithEscape(true)
            .build();
        ActionButton back = this.button(this.text("button.back"), null, (response, audience) -> {
            this.saveGoalAmount(response, goal);
            this.openMain(player);
        });
        this.show(player, base, DialogType.multiAction(buttons).columns(2).exitAction(back).build());
    }

    private void saveGoalAmount(DialogResponseView response, Goal goal) {
        int amount = intOf(response, "amount", goal.amount());
        if (amount != goal.amount()) {
            this.plugin.setConfigValue("goal.amount", amount);
        }
    }

    private void chooseGoalType(Player player, GoalType type) {
        switch (type) {
            case NONE -> this.setGoal(player, Goal.NONE);
            case BEAT_GAME -> this.setGoal(player, new Goal(GoalType.BEAT_GAME, null, 1));
            case ENTER_DIMENSION -> this.openDimensionPicker(player);
            default -> this.openSearch(player, type, "");
        }
    }

    private void openDimensionPicker(Player player) {
        List<ActionButton> buttons = new ArrayList<>();
        for (NamespacedKey dimension : List.of(GoalService.NETHER, GoalService.END, GoalService.OVERWORLD)) {
            buttons.add(this.button(this.messages().get("dimension." + dimension.getKey().replace('_', '-')), null,
                (response, audience) -> this.setGoal(player, new Goal(GoalType.ENTER_DIMENSION, dimension, 1))));
        }
        DialogBase base = DialogBase.builder(this.text("title.goal"))
            .body(List.of(this.header(this.text("body.dimension"))))
            .canCloseWithEscape(true)
            .build();
        ActionButton back = this.button(this.text("button.back"), null, (response, audience) -> this.openGoal(player));
        this.show(player, base, DialogType.multiAction(buttons).columns(3).exitAction(back).build());
    }

    /** Search field plus the matching targets as buttons; empty search shows popular targets. */
    private void openSearch(Player player, GoalType type, String query) {
        List<NamespacedKey> all = GoalTargets.all(type);
        List<NamespacedKey> results = query.isBlank()
            ? GoalTargets.popular(type)
            : GoalTargets.search(all, query, GoalTargets.MAX_RESULTS + 1);
        boolean more = results.size() > GoalTargets.MAX_RESULTS;
        if (more) {
            results = results.subList(0, GoalTargets.MAX_RESULTS);
        }
        int amount = this.plugin.goals().goal().type().supportsAmount() ? this.plugin.goals().goal().amount() : 1;
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(this.button(this.text("button.search"), null, (response, audience) -> {
            String next = response.getText("query");
            this.openSearch(player, type, next == null ? "" : next.trim());
        }));
        for (NamespacedKey key : results) {
            buttons.add(this.button(GoalTargets.label(type, key), Component.text(key.asString()),
                (response, audience) -> this.setGoal(player, new Goal(type, key, type.supportsAmount() ? amount : 1))));
        }
        String bodyKey = query.isBlank() ? "body.search-popular" : results.isEmpty() ? "body.search-none" : more ? "body.search-more" : "body.search-found";
        Component body = this.text(bodyKey,
            Placeholder.component("type", this.text(SettingField.optionKey(type.name()))),
            Placeholder.unparsed("query", query),
            Placeholder.unparsed("count", Integer.toString(results.size())),
            Placeholder.unparsed("total", Integer.toString(all.size())));
        DialogBase base = DialogBase.builder(this.text("title.search", Placeholder.component("type", this.text(SettingField.optionKey(type.name())))))
            .body(List.of(this.header(body)))
            .inputs(List.of(this.textInput("query", this.text("input.search-" + type.name().toLowerCase(Locale.ROOT).replace('_', '-')), query, 64)))
            .canCloseWithEscape(true)
            .build();
        ActionButton back = this.button(this.text("button.back"), null, (response, audience) -> this.openGoal(player));
        this.show(player, base, DialogType.multiAction(buttons).columns(2).exitAction(back).build());
    }

    private void setGoal(Player player, Goal goal) {
        if (!player.hasPermission(PERM + "goal")) {
            this.messages().send(player, "menu.no-permission");
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("goal.type", goal.type().name());
        values.put("goal.target", goal.target() == null ? "" : goal.target().asString());
        values.put("goal.amount", goal.amount());
        this.plugin.setConfigValues(values);
        this.plugin.goals().resetProgress();
        this.game.saveAsync();
        this.messages().send(player, "goal.changed", this.game.goalPlaceholder());
        this.openGoal(player);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Manhunt
    // ---------------------------------------------------------------------------------------------------------

    public void openManhunt(Player player, boolean fromMain) {
        this.safely(player, "manhunt", () -> this.openManhuntUnsafe(player));
    }

    private void openManhuntUnsafe(Player player) {
        Settings.Manhunt config = this.settings().manhunt();
        ManhuntService manhunt = this.plugin.manhunt();
        List<DialogInput> inputs = List.of(
            this.textInput("targets", this.text("input.manhunt-targets"), manhunt.targets().isEmpty() ? "" : manhunt.targetNames(), 256),
            this.slider("lives", this.text("input.manhunt-lives"), 1, 10, 1, config.targetLives()),
            this.slider("head_start", this.text("input.manhunt-head-start"), 0, 300, 5, config.headStart()),
            DialogInput.bool("compass", this.text("input.manhunt-compass")).initial(config.hunterCompass()).build()
        );
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(this.button(this.text("button.save"), null, (response, audience) -> {
            if (player.hasPermission("magicreset.manhunt")) {
                this.saveManhunt(player, response, config);
                this.messages().send(player, "menu.saved");
            }
            this.openGoal(player);
        }));
        buttons.add(this.button(this.text("button.manhunt-start"), null, (response, audience) -> {
            if (!player.hasPermission("magicreset.manhunt")) {
                return;
            }
            this.saveManhunt(player, response, config);
            if (manhunt.targets().isEmpty()) {
                this.messages().send(player, "manhunt.no-targets");
                return;
            }
            if (!this.settings().manhunt().enabled()) {
                this.plugin.setConfigValue("manhunt.enabled", true);
                manhunt.onModeChanged();
            }
            Settings.Reset reset = this.settings().reset();
            String error = this.game.requestReset(null, reset.countdown(), reset.limboCountdown());
            if (error != null) {
                this.messages().send(player, error);
            }
        }));
        DialogBase base = DialogBase.builder(this.text("title.manhunt"))
            .body(List.of(this.header(this.text("body.manhunt", this.game.goalPlaceholder()))))
            .inputs(inputs)
            .canCloseWithEscape(true)
            .build();
        this.show(player, base, DialogType.multiAction(buttons).columns(2).exitAction(this.backButton(player, "game")).build());
    }

    private void saveManhunt(Player player, DialogResponseView response, Settings.Manhunt config) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("manhunt.target-lives", intOf(response, "lives", config.targetLives()));
        values.put("manhunt.head-start", intOf(response, "head_start", config.headStart()));
        values.put("manhunt.hunter-compass", boolOf(response, "compass", config.hunterCompass()));
        this.plugin.setConfigValues(values);
        ManhuntService manhunt = this.plugin.manhunt();
        String names = response.getText("targets");
        if (names != null) {
            manhunt.clearTargets();
            for (String name : names.split("[,\\s]+")) {
                if (name.isBlank()) {
                    continue;
                }
                Player target = Bukkit.getPlayerExact(name.trim());
                if (target == null) {
                    this.messages().send(player, "menu.unknown-player", Placeholder.unparsed("player", name.trim()));
                } else {
                    manhunt.setRole(target.getUniqueId(), ManhuntService.Role.TARGET);
                }
            }
        }
        manhunt.onModeChanged();
        this.game.saveAsync();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Building blocks
    // ---------------------------------------------------------------------------------------------------------

    @FunctionalInterface
    private interface Submit {
        /** @return {@code true} to go back to the main menu */
        boolean apply(DialogResponseView response);
    }

    private void form(Player player, String titleKey, Component body, List<DialogInput> inputs, String permission,
                      String parent, Submit submit) {
        this.form(player, titleKey, body, inputs, permission, parent, this.text("button.save"), submit);
    }

    private void form(Player player, String titleKey, Component body, List<DialogInput> inputs, String permission,
                      String parent, Component submitLabel, Submit submit) {
        // one column: the buttons are as wide as the inputs above them
        ActionButton save = this.button(submitLabel, null, WIDE, (response, audience) -> {
            if (!player.hasPermission(permission)) {
                this.messages().send(player, "menu.no-permission");
                return;
            }
            if (submit.apply(response)) {
                this.messages().send(player, "menu.saved");
                this.openParent(player, parent);
            }
        });
        DialogBase base = DialogBase.builder(this.text(titleKey))
            .body(List.of(this.header(body)))
            .inputs(inputs)
            .canCloseWithEscape(true)
            .build();
        ActionButton back = this.button(this.text("button.back"), null, WIDE, (response, audience) -> this.openParent(player, parent));
        this.show(player, base, DialogType.multiAction(List.of(save)).exitAction(back).build());
    }

    /** The centred text at the top of every screen, as wide as the inputs below it. */
    private DialogBody header(Component description) {
        return DialogBody.plainMessage(description, WIDE);
    }

    /**
     * Every screen stays open after a click until the next one replaces it, so switching screens does not flash
     * the game in between; a click that opens nothing closes the menu (see {@link #button}).
     */
    private void show(Player player, DialogBase base, DialogType type) {
        DialogBase stay = DialogBase.create(base.title(), base.externalTitle(), base.canCloseWithEscape(), false,
            DialogBase.DialogAfterAction.NONE, base.body(), base.inputs());
        Dialog dialog = Dialog.create(builder -> builder.empty().base(stay).type(type));
        this.shown++;
        player.showDialog(dialog);
    }

    /** Runs a menu action; a bug never kicks the player or breaks the command, it is reported instead. */
    private void safely(Player player, String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Menu error (" + what + ")", e);
            player.closeDialog();
            this.messages().send(player, "menu.error");
        }
    }

    private ActionButton button(Component label, @Nullable Component tooltip, BiConsumer<DialogResponseView, Audience> action) {
        return this.button(label, tooltip, 150, action);
    }

    private ActionButton button(Component label, @Nullable Component tooltip, int width, BiConsumer<DialogResponseView, Audience> action) {
        return ActionButton.builder(label)
            .tooltip(tooltip)
            .width(width)
            .action(DialogAction.customClick((response, audience) -> {
                if (!(audience instanceof Player player)) {
                    return;
                }
                // dialog callbacks may arrive off the main thread; everything here touches the game
                Runnable run = () -> {
                    int before = this.shown;
                    this.safely(player, "button", () -> action.accept(response, audience));
                    if (this.shown == before) {
                        player.closeDialog();
                    }
                };
                if (Bukkit.isPrimaryThread()) {
                    run.run();
                } else {
                    Bukkit.getScheduler().runTask(this.plugin, run);
                }
            }, ONCE))
            .build();
    }

    private ActionButton backButton(Player player, String parent) {
        return this.button(this.text("button.back"), null, (response, audience) -> this.openParent(player, parent));
    }

    private ActionButton closeButton() {
        return this.button(this.text("button.close"), null, (response, audience) -> {
        });
    }

    private DialogInput textInput(String key, Component label, String initial, int maxLength) {
        String value = initial == null ? "" : initial;
        return DialogInput.text(key, label)
            .initial(value)
            .maxLength(Math.max(maxLength, value.length()))
            .width(WIDE)
            .build();
    }

    private DialogInput slider(String key, Component label, int start, int end, int step, int initial) {
        // a value from the config outside the usual range widens the slider instead of being cut off on save
        int low = Math.min(start, initial);
        int high = Math.max(end, initial);
        return DialogInput.numberRange(key, label, low, high)
            .step((float) step)
            .initial((float) initial)
            .width(WIDE)
            .build();
    }

    private static int intOf(DialogResponseView response, String key, int fallback) {
        Float value = response.getFloat(key);
        return value == null ? fallback : Math.round(value);
    }

    private static boolean boolOf(DialogResponseView response, String key, boolean fallback) {
        Boolean value = response.getBoolean(key);
        return value == null ? fallback : value;
    }

    private static String option(DialogResponseView response, String key, String fallback) {
        String value = response.getText(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
