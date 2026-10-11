package dev.magicclutch.magicreset.menu;

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
import dev.magicclutch.magicreset.manhunt.ManhuntService;
import dev.magicclutch.magicreset.timer.RunTimer;
import dev.magicclutch.magicreset.timer.StartTrigger;
import dev.magicclutch.magicreset.timer.TimerMode;
import dev.magicclutch.magicreset.timer.TimerScope;
import dev.magicclutch.magicreset.timer.TimerService;
import dev.magicclutch.magicreset.util.TimeFormat;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * The /magicreset and /manhunt menus built with the server-side dialog screens (Minecraft 1.21.6+).
 * Every section needs the same permission as the matching command, checked again when a button is clicked.
 */
public final class DialogMenu {

    private static final ClickCallback.Options ONCE = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(10)).build();
    private static final int WIDE = 200;
    private static final String PERM = "magicreset.command.";

    private final MagicReset plugin;
    private final GameManager game;
    private final TimerService timers;
    private final HudService hud;

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
    // Main menu
    // ---------------------------------------------------------------------------------------------------------

    /** Opens the admin menu, or the info view for players without any admin permission. */
    public void open(Player player) {
        List<ActionButton> buttons = new ArrayList<>();
        this.section(buttons, player, PERM + "reset", "reset", this::openReset);
        this.section(buttons, player, PERM + "timer", "timer", this::openTimer);
        this.section(buttons, player, PERM + "goal", "goal", this::openGoal);
        this.section(buttons, player, PERM + "worlds", "worlds", this::openWorlds);
        this.section(buttons, player, PERM + "deaths", "deaths", this::openDeaths);
        this.section(buttons, player, "magicreset.manhunt", "manhunt", this::openManhunt);
        if (buttons.isEmpty()) {
            this.openInfo(player);
            return;
        }
        buttons.add(this.button(this.text("button.leaderboard"), null, (response, audience) -> this.openInfo(player)));
        DialogBase base = DialogBase.builder(this.text("title.main"))
            .body(List.of(DialogBody.plainMessage(this.statusText(player), WIDE + 100)))
            .canCloseWithEscape(true)
            .build();
        this.show(player, base, DialogType.multiAction(buttons).columns(2).exitAction(this.closeButton()).build());
    }

    private void section(List<ActionButton> buttons, Player player, String permission, String key, BiConsumer<Player, Boolean> opener) {
        if (player.hasPermission(permission)) {
            buttons.add(this.button(this.text("button." + key), null, (response, audience) -> opener.accept(player, true)));
        }
    }

    private Component statusText(Player player) {
        RunTimer timer = this.timers.displayed(player);
        boolean showSeed = this.settings().showSeed() || player.hasPermission(PERM + "seed");
        return this.text("status",
            Placeholder.unparsed("run", Integer.toString(this.game.run())),
            Placeholder.unparsed("phase", this.game.phase().name().toLowerCase(Locale.ROOT)),
            Placeholder.unparsed("time", this.settings().timer().display(timer.millis(this.timers.mode()))),
            Placeholder.unparsed("mode", this.timers.mode().name()),
            Placeholder.unparsed("seed", showSeed ? Long.toString(this.game.seed()) : this.messages().raw("seed.hidden")),
            this.game.goalPlaceholder(),
            Placeholder.unparsed("manhunt", this.plugin.manhunt().isEnabled() ? "on" : "off"));
    }

    /** Read-only view: status and the leaderboard of the current goal. */
    public void openInfo(Player player) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(this.statusText(player), WIDE + 100));
        Component board = Component.empty();
        for (Component line : this.hud.leaderboardLines()) {
            board = board.append(line).append(Component.newline());
        }
        body.add(DialogBody.plainMessage(board, WIDE + 100));
        DialogBase base = DialogBase.builder(this.text("title.info")).body(body).canCloseWithEscape(true).build();
        this.show(player, base, DialogType.notice(this.closeButton()));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Sections
    // ---------------------------------------------------------------------------------------------------------

    private void openReset(Player player, boolean fromMain) {
        Settings.Reset reset = this.settings().reset();
        List<DialogInput> inputs = List.of(
            DialogInput.text("seed", this.text("input.seed")).initial("").maxLength(64).width(WIDE).build(),
            this.number("countdown", "input.countdown", 0, 60, 1, reset.countdown()),
            this.number("limbo", "input.limbo-countdown", 0, 60, 1, reset.limboCountdown())
        );
        this.form(player, "title.reset", this.text("body.reset", Placeholder.unparsed("worlds", this.game.resetWorldList())), inputs,
            this.text("button.reset-now"), PERM + "reset", response -> {
                String seed = response.getText("seed");
                String error = this.game.requestReset(seed == null || seed.isBlank() ? null : seed.trim(),
                    intOf(response, "countdown", reset.countdown()), intOf(response, "limbo", reset.limboCountdown()));
                if (error != null) {
                    this.messages().send(player, error);
                }
                return false; // the reset takes over the screen, do not reopen the menu
            });
    }

    private void openTimer(Player player, boolean fromMain) {
        Settings.Timer timer = this.settings().timer();
        List<DialogInput> inputs = List.of(
            this.options("mode", "input.timer-mode", TimerMode.values(), timer.mode()),
            this.options("scope", "input.timer-scope", TimerScope.values(), timer.scope()),
            this.options("start", "input.timer-start", StartTrigger.values(), timer.start()),
            this.bool("stop-empty", "input.stop-when-empty", timer.stopWhenEmpty()),
            DialogInput.text("format", this.text("input.timer-format")).initial(timer.format()).maxLength(32).width(WIDE).build(),
            this.number("decimals", "input.decimals", 0, 3, 1, timer.decimals())
        );
        this.form(player, "title.timer", this.text("body.timer"), inputs, this.text("button.save"), PERM + "timer", response -> {
            String format = response.getText("format");
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("timer.mode", option(response, "mode", timer.mode().name()));
            values.put("timer.scope", option(response, "scope", timer.scope().name()));
            values.put("timer.start", option(response, "start", timer.start().name()));
            values.put("timer.stop-when-empty", boolOf(response, "stop-empty", timer.stopWhenEmpty()));
            values.put("timer.decimals", intOf(response, "decimals", timer.decimals()));
            if (format != null && (format.equalsIgnoreCase("auto") || TimeFormat.isValidPattern(format.trim()))) {
                values.put("timer.format", format.trim());
            } else {
                this.messages().send(player, "timer.format-invalid");
            }
            this.plugin.setConfigValues(values);
            return true;
        });
    }

    private void openGoal(Player player, boolean fromMain) {
        Goal goal = this.plugin.goals().goal();
        Settings.GoalSettings config = this.settings().goal();
        List<DialogInput> inputs = List.of(
            this.options("type", "input.goal-type", GoalType.values(), goal.type()),
            DialogInput.text("target", this.text("input.goal-target"))
                .initial(goal.target() == null ? "" : goal.target().asString()).maxLength(96).width(WIDE).build(),
            this.number("amount", "input.goal-amount", 1, 64, 1, goal.amount()),
            this.bool("spectator", "input.spectator-on-finish", config.spectatorOnFinish()),
            this.number("reset-after", "input.reset-after", -1, 600, 1, config.resetAfter())
        );
        this.form(player, "title.goal", this.text("body.goal", this.game.goalPlaceholder()), inputs, this.text("button.save"), PERM + "goal", response -> {
            GoalType type = GoalType.valueOf(option(response, "type", goal.type().name()));
            String rawTarget = response.getText("target");
            NamespacedKey target = null;
            if (type.needsTarget()) {
                String normalized = rawTarget == null ? "" : rawTarget.trim().toLowerCase(Locale.ROOT);
                target = NamespacedKey.fromString(normalized.contains(":") ? normalized : "minecraft:" + normalized);
                String error = target == null ? "goal.invalid-key" : GoalService.validate(type, target);
                if (error != null) {
                    this.messages().send(player, error, Placeholder.unparsed("target", rawTarget == null ? "" : rawTarget));
                    return true;
                }
            }
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("goal.type", type.name());
            values.put("goal.target", target == null ? "" : target.asString());
            values.put("goal.amount", intOf(response, "amount", goal.amount()));
            values.put("goal.spectator-on-finish", boolOf(response, "spectator", config.spectatorOnFinish()));
            values.put("goal.reset-after", intOf(response, "reset-after", config.resetAfter()));
            this.plugin.setConfigValues(values);
            this.plugin.goals().resetProgress();
            this.messages().send(player, "goal.changed", this.game.goalPlaceholder());
            return true;
        });
    }

    private void openWorlds(Player player, boolean fromMain) {
        Settings settings = this.settings();
        List<DialogInput> inputs = List.of(
            this.bool("nether", "input.nether", settings.worlds().nether()),
            this.bool("end", "input.end", settings.worlds().end()),
            this.bool("locator-bar", "input.locator-bar", settings.worlds().locatorBar()),
            this.bool("preload", "input.preload", settings.reset().preload()),
            this.bool("backups", "input.backups", settings.backups().enabled()),
            this.number("keep", "input.backups-keep", 0, 50, 1, settings.backups().keep()),
            this.options("seed-mode", "input.seed-mode", SeedMode.values(), settings.seedMode()),
            DialogInput.text("fixed-seed", this.text("input.fixed-seed")).initial(settings.fixedSeed()).maxLength(64).width(WIDE).build(),
            this.bool("show-seed", "input.show-seed", settings.showSeed())
        );
        this.form(player, "title.worlds", this.text("body.worlds", Placeholder.unparsed("worlds", this.game.resetWorldList())), inputs,
            this.text("button.save"), PERM + "worlds", response -> {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("worlds.nether", boolOf(response, "nether", settings.worlds().nether()));
                values.put("worlds.end", boolOf(response, "end", settings.worlds().end()));
                values.put("worlds.locator-bar", boolOf(response, "locator-bar", settings.worlds().locatorBar()));
                values.put("reset.preload", boolOf(response, "preload", settings.reset().preload()));
                values.put("backups.enabled", boolOf(response, "backups", settings.backups().enabled()));
                values.put("backups.keep", intOf(response, "keep", settings.backups().keep()));
                values.put("seed.mode", option(response, "seed-mode", settings.seedMode().name()));
                String fixed = response.getText("fixed-seed");
                values.put("seed.fixed", fixed == null ? settings.fixedSeed() : fixed.trim());
                values.put("seed.show", boolOf(response, "show-seed", settings.showSeed()));
                this.plugin.setConfigValues(values);
                this.plugin.worlds().applyLocatorBar();
                return true;
            });
    }

    private void openDeaths(Player player, boolean fromMain) {
        Settings.Deaths deaths = this.settings().deaths();
        List<DialogInput> inputs = List.of(
            this.options("mode", "input.death-mode", DeathMode.values(), deaths.mode()),
            this.number("lives", "input.shared-lives", 1, 20, 1, deaths.sharedLives()),
            this.bool("spectator", "input.spectator-on-fail", deaths.spectatorOnFail()),
            this.number("reset-after", "input.reset-after", -1, 600, 1, deaths.resetAfter())
        );
        this.form(player, "title.deaths", this.text("body.deaths"), inputs, this.text("button.save"), PERM + "deaths", response -> {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("deaths.mode", option(response, "mode", deaths.mode().name()));
            values.put("deaths.shared-lives", intOf(response, "lives", deaths.sharedLives()));
            values.put("deaths.spectator-on-fail", boolOf(response, "spectator", deaths.spectatorOnFail()));
            values.put("deaths.reset-after", intOf(response, "reset-after", deaths.resetAfter()));
            this.plugin.setConfigValues(values);
            return true;
        });
    }

    public void openManhunt(Player player, boolean fromMain) {
        Settings.Manhunt config = this.settings().manhunt();
        ManhuntService manhunt = this.plugin.manhunt();
        List<DialogInput> inputs = List.of(
            this.bool("enabled", "input.manhunt-enabled", config.enabled()),
            DialogInput.text("targets", this.text("input.manhunt-targets"))
                .initial(manhunt.targets().isEmpty() ? "" : manhunt.targetNames()).maxLength(256).width(WIDE).build(),
            this.number("lives", "input.manhunt-lives", 1, 10, 1, config.targetLives()),
            this.number("head-start", "input.manhunt-head-start", 0, 300, 5, config.headStart()),
            this.bool("compass", "input.manhunt-compass", config.hunterCompass()),
            this.number("reset-after", "input.reset-after", -1, 600, 1, config.resetAfter())
        );
        this.form(player, "title.manhunt", this.text("body.manhunt", this.game.goalPlaceholder()), inputs, this.text("button.save"),
            "magicreset.manhunt", response -> {
                this.saveManhunt(player, response, config);
                return true;
            }, this.button(this.text("button.manhunt-start"), null, (response, audience) -> {
                if (!player.hasPermission("magicreset.manhunt")) {
                    return;
                }
                this.saveManhunt(player, response, config);
                if (manhunt.targets().isEmpty()) {
                    this.messages().send(player, "manhunt.no-targets");
                    return;
                }
                Settings.Reset reset = this.settings().reset();
                String error = this.game.requestReset(null, reset.countdown(), reset.limboCountdown());
                if (error != null) {
                    this.messages().send(player, error);
                }
            }));
    }

    private void saveManhunt(Player player, DialogResponseView response, Settings.Manhunt config) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("manhunt.enabled", boolOf(response, "enabled", config.enabled()));
        values.put("manhunt.target-lives", intOf(response, "lives", config.targetLives()));
        values.put("manhunt.head-start", intOf(response, "head-start", config.headStart()));
        values.put("manhunt.hunter-compass", boolOf(response, "compass", config.hunterCompass()));
        values.put("manhunt.reset-after", intOf(response, "reset-after", config.resetAfter()));
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
        /** @return {@code true} to show the main menu again */
        boolean apply(DialogResponseView response);
    }

    private void form(Player player, String titleKey, Component body, List<DialogInput> inputs, Component submitLabel,
                      String permission, Submit submit, ActionButton... extra) {
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(this.button(submitLabel, null, (response, audience) -> {
            if (!player.hasPermission(permission)) {
                this.messages().send(player, "menu.no-permission");
                return;
            }
            if (submit.apply(response)) {
                this.messages().send(player, "menu.saved");
                this.open(player);
            }
        }));
        buttons.addAll(List.of(extra));
        DialogBase base = DialogBase.builder(this.text(titleKey))
            .body(List.of(DialogBody.plainMessage(body, WIDE + 100)))
            .inputs(inputs)
            .canCloseWithEscape(true)
            .build();
        ActionButton back = this.button(this.text("button.back"), null, (response, audience) -> this.open(player));
        this.show(player, base, DialogType.multiAction(buttons).columns(2).exitAction(back).build());
    }

    private void show(Player player, DialogBase base, DialogType type) {
        Dialog dialog = Dialog.create(builder -> builder.empty().base(base).type(type));
        player.showDialog(dialog);
    }

    private ActionButton button(Component label, @Nullable Component tooltip, BiConsumer<DialogResponseView, Audience> action) {
        return ActionButton.builder(label)
            .tooltip(tooltip)
            .width(150)
            .action(DialogAction.customClick((response, audience) -> {
                // dialog callbacks may arrive off the main thread; everything here touches the game
                if (Bukkit.isPrimaryThread()) {
                    action.accept(response, audience);
                } else {
                    Bukkit.getScheduler().runTask(this.plugin, () -> action.accept(response, audience));
                }
            }, ONCE))
            .build();
    }

    private ActionButton closeButton() {
        return ActionButton.builder(this.text("button.close")).width(150).build();
    }

    private DialogInput bool(String key, String labelKey, boolean initial) {
        return DialogInput.bool(key, this.text(labelKey)).initial(initial).build();
    }

    private DialogInput number(String key, String labelKey, int start, int end, int step, int initial) {
        return DialogInput.numberRange(key, this.text(labelKey), start, end)
            .step((float) step)
            .initial((float) Math.clamp(initial, start, end))
            .width(WIDE)
            .build();
    }

    private <E extends Enum<E>> DialogInput options(String key, String labelKey, E[] values, E current) {
        List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
        for (E value : values) {
            entries.add(SingleOptionDialogInput.OptionEntry.create(value.name(),
                this.messages().get("menu.option." + value.name().toLowerCase(Locale.ROOT).replace('_', '-')), value == current));
        }
        return DialogInput.singleOption(key, this.text(labelKey), entries).width(WIDE).build();
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
