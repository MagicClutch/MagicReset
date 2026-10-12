package dev.magicclutch.magicreset;

import dev.magicclutch.magicreset.command.MagicResetCommand;
import dev.magicclutch.magicreset.config.Messages;
import dev.magicclutch.magicreset.config.Settings;
import dev.magicclutch.magicreset.game.GameListener;
import dev.magicclutch.magicreset.game.GameManager;
import dev.magicclutch.magicreset.goal.GoalService;
import dev.magicclutch.magicreset.hud.HudService;
import dev.magicclutch.magicreset.manhunt.ManhuntService;
import dev.magicclutch.magicreset.menu.DialogMenu;
import dev.magicclutch.magicreset.schedule.AutoResetScheduler;
import dev.magicclutch.magicreset.stats.Leaderboard;
import dev.magicclutch.magicreset.timer.TimerService;
import dev.magicclutch.magicreset.world.GameWorlds;
import dev.magicclutch.magicreset.world.PortalListener;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

public final class MagicReset extends JavaPlugin {

    private static final long AUTOSAVE_TICKS = 20L * 30L;

    private Settings settings;
    private Messages messages;
    private GameWorlds worlds;
    private TimerService timers;
    private GoalService goals;
    private GameManager game;
    private HudService hud;
    private AutoResetScheduler autoReset;
    private Leaderboard leaderboard;
    private ManhuntService manhunt;
    private DialogMenu menu;
    private boolean started;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        this.loadSettings();

        this.leaderboard = new Leaderboard(this.getDataFolder(), this.getLogger());
        this.leaderboard.load();
        this.worlds = new GameWorlds(this.getLogger(), this::settings, this.settings.worlds().namespace());
        this.manhunt = new ManhuntService(this, this.worlds);
        this.timers = new TimerService(() -> {
            Settings.Timer timer = this.settings.timer();
            return new TimerService.TimerSettings(timer.mode(), timer.scope(), timer.start(), timer.igtPauseWhenEmpty(), timer.stopWhenEmpty());
        });
        this.goals = new GoalService(
            () -> this.settings.goal().goal(),
            () -> this.timers.scope(),
            this::messages,
            this.worlds,
            player -> this.game.isActiveRunner(player) && this.manhunt.mayCompleteGoal(player),
            player -> this.game.completeGoal(player)
        );
        this.game = new GameManager(this, this.worlds, this.timers, this.goals);
        this.hud = new HudService(this, this.game, this.worlds, this.timers, this.goals);
        this.autoReset = new AutoResetScheduler(this, this.game);

        this.game.load();
        ConfigurationSection extra = this.game.loadedExtra();
        if (extra != null) {
            this.hud.load(extra.getConfigurationSection("hud"));
            this.autoReset.load(extra.getConfigurationSection("auto-reset"));
            this.manhunt.load(extra.getConfigurationSection("manhunt"));
        }

        PluginManager plugins = this.getServer().getPluginManager();
        plugins.registerEvents(new GameListener(this, this.game, this.worlds, this.timers, () -> this.settings.limbo().platformHeight()), this);
        plugins.registerEvents(new PortalListener(this, this.worlds, this.game::spawn, this.game::seed), this);
        plugins.registerEvents(this.goals, this);
        plugins.registerEvents(this.hud, this);
        plugins.registerEvents(this.manhunt, this);

        this.menu = new DialogMenu(this, this.game, this.timers, this.hud);
        MagicResetCommand command = new MagicResetCommand(this, this.game, this.timers, this.hud, this.autoReset);
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> command.register(event.registrar()));

        // worlds can only be created once the server finished loading its own worlds
        this.getServer().getScheduler().runTask(this, () -> {
            boolean ok;
            try {
                ok = this.game.start();
            } catch (RuntimeException e) {
                this.getLogger().log(java.util.logging.Level.SEVERE, "MagicReset failed to start", e);
                ok = false;
            }
            if (!ok) {
                this.getLogger().severe("MagicReset is disabled because its worlds could not be prepared");
                this.getServer().getPluginManager().disablePlugin(this);
                return;
            }
            this.started = true;
            this.game.startPreloader();
            this.hud.start();
            this.autoReset.start();
            this.getServer().getScheduler().runTaskTimer(this, this.game::saveAsync, AUTOSAVE_TICKS, AUTOSAVE_TICKS);
        });
    }

    @Override
    public void onDisable() {
        if (this.autoReset != null) {
            this.autoReset.stop();
        }
        if (this.hud != null) {
            this.hud.stop();
        }
        if (this.game != null && this.started) {
            this.game.shutdown();
        }
    }

    private void loadSettings() {
        this.reloadConfig();
        this.settings = new Settings(this.getConfig(), this.getLogger());
        this.messages = new Messages(this);
    }

    /**
     * Reloads config.yml and messages.yml.
     *
     * @return a warning to show, or {@code null}
     */
    public @Nullable String reload() {
        String oldNamespace = this.settings.worlds().namespace();
        this.loadSettings();
        if (!oldNamespace.equals(this.settings.worlds().namespace())) {
            return "worlds.namespace changed: restart the server to apply it";
        }
        return null;
    }

    /**
     * Changes a value in config.yml (comments are preserved) and applies it immediately.
     */
    public void setConfigValue(String path, @Nullable Object value) {
        this.setConfigValues(java.util.Collections.singletonMap(path, value));
    }

    /** Like {@link #setConfigValue(String, Object)} for several values with a single write. */
    public void setConfigValues(Map<String, ?> values) {
        values.forEach((path, value) -> this.getConfig().set(path, value));
        this.saveConfig();
        this.settings = new Settings(this.getConfig(), this.getLogger());
    }

    /** Called while saving data.yml so other components can store their state. */
    public void saveExtra(ConfigurationSection section) {
        if (this.hud != null) {
            this.hud.save(section.createSection("hud"));
        }
        if (this.autoReset != null) {
            this.autoReset.save(section.createSection("auto-reset"));
        }
        if (this.manhunt != null) {
            this.manhunt.save(section.createSection("manhunt"));
        }
    }

    public Settings settings() {
        return this.settings;
    }

    public Messages messages() {
        return this.messages;
    }

    public GameWorlds worlds() {
        return this.worlds;
    }

    public GoalService goals() {
        return this.goals;
    }

    public AutoResetScheduler autoReset() {
        return this.autoReset;
    }

    public DialogMenu menu() {
        return this.menu;
    }

    public ManhuntService manhunt() {
        return this.manhunt;
    }

    public Leaderboard leaderboard() {
        return this.leaderboard;
    }
}
