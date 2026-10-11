package dev.magicclutch.magicreset.timer;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * Owns the global timer and the per-player timers and decides which one counts and which one is shown.
 */
public final class TimerService {

    private final Supplier<TimerSettings> settings;
    private RunTimer global = new RunTimer();
    private final Map<UUID, RunTimer> individual = new HashMap<>();

    /** {@code true} once the run has been released and timers may start. */
    private boolean armed;

    public record TimerSettings(TimerMode mode, TimerScope scope, StartTrigger trigger, boolean igtPauseWhenEmpty) {
    }

    public TimerService(Supplier<TimerSettings> settings) {
        this.settings = settings;
    }

    private TimerSettings settings() {
        return this.settings.get();
    }

    public TimerMode mode() {
        return this.settings().mode();
    }

    public TimerScope scope() {
        return this.settings().scope();
    }

    /** Clears every timer for a new run. */
    public void prepareNewRun() {
        this.global = new RunTimer();
        this.individual.clear();
        this.armed = false;
    }

    /** Called when the players are released into a fresh world. */
    public void begin(Collection<? extends Player> participants) {
        this.armed = true;
        if (this.settings().trigger() != StartTrigger.AUTO) {
            return;
        }
        if (this.scope() == TimerScope.GLOBAL) {
            this.global.start();
        } else {
            for (Player player : participants) {
                this.timer(player.getUniqueId()).start();
            }
        }
    }

    /** A player joined the run after it started (late join or reconnect). */
    public void onParticipantJoin(Player player) {
        if (!this.armed || this.scope() != TimerScope.INDIVIDUAL || this.settings().trigger() != StartTrigger.AUTO) {
            return;
        }
        RunTimer timer = this.timer(player.getUniqueId());
        if (timer.state() == RunTimer.State.IDLE) {
            timer.start();
        }
    }

    /** A participant moved or interacted for the first time. */
    public void onInput(Player player) {
        if (!this.armed || this.settings().trigger() != StartTrigger.FIRST_INPUT) {
            return;
        }
        RunTimer timer = this.scope() == TimerScope.GLOBAL ? this.global : this.timer(player.getUniqueId());
        if (timer.state() == RunTimer.State.IDLE) {
            timer.start();
        }
    }

    public boolean isWaitingForInput(Player player) {
        return this.armed && this.settings().trigger() == StartTrigger.FIRST_INPUT && this.displayed(player).state() == RunTimer.State.IDLE;
    }

    /**
     * Advances in-game time. {@code inGame} contains the players that are currently playing in a game world.
     */
    public void tick(Collection<? extends Player> inGame) {
        if (!inGame.isEmpty() || !this.settings().igtPauseWhenEmpty()) {
            this.global.tick();
        }
        for (Player player : inGame) {
            RunTimer timer = this.individual.get(player.getUniqueId());
            if (timer != null) {
                timer.tick();
            }
        }
    }

    /** The timer a player should see. */
    public RunTimer displayed(Player player) {
        if (this.scope() == TimerScope.GLOBAL) {
            return this.global;
        }
        RunTimer timer = this.individual.get(player.getUniqueId());
        return timer != null ? timer : new RunTimer();
    }

    public RunTimer global() {
        return this.global;
    }

    public RunTimer timer(UUID player) {
        return this.individual.computeIfAbsent(player, ignored -> new RunTimer());
    }

    public Map<UUID, RunTimer> individualTimers() {
        return this.individual;
    }

    /** Starts (or resumes) the timer for the current scope. */
    public void startAll(Collection<? extends Player> participants) {
        this.armed = true;
        if (this.scope() == TimerScope.GLOBAL) {
            this.global.start();
        } else {
            for (Player player : participants) {
                this.timer(player.getUniqueId()).start();
            }
            this.individual.values().forEach(timer -> {
                if (timer.state() == RunTimer.State.PAUSED) {
                    timer.start();
                }
            });
        }
    }

    public void pauseAll() {
        this.global.pause();
        this.individual.values().forEach(RunTimer::pause);
    }

    public void resumeAll() {
        if (this.global.state() == RunTimer.State.PAUSED) {
            this.global.start();
        }
        this.individual.values().forEach(timer -> {
            if (timer.state() == RunTimer.State.PAUSED) {
                timer.start();
            }
        });
    }

    public void finishAll() {
        this.global.finish();
        this.individual.values().forEach(RunTimer::finish);
    }

    public void resetAll() {
        this.global.reset();
        this.individual.clear();
    }

    public void setAll(long millis) {
        this.global.set(millis);
        this.individual.values().forEach(timer -> timer.set(millis));
    }

    public boolean isArmed() {
        return this.armed;
    }

    public void save(ConfigurationSection section) {
        section.set("armed", this.armed);
        this.global.save(section.createSection("global"));
        ConfigurationSection players = section.createSection("players");
        this.individual.forEach((uuid, timer) -> timer.save(players.createSection(uuid.toString())));
    }

    public void load(ConfigurationSection section) {
        this.prepareNewRun();
        if (section == null) {
            return;
        }
        this.armed = section.getBoolean("armed");
        this.global = RunTimer.load(section.getConfigurationSection("global"));
        ConfigurationSection players = section.getConfigurationSection("players");
        if (players != null) {
            for (String key : players.getKeys(false)) {
                try {
                    this.individual.put(UUID.fromString(key), RunTimer.load(players.getConfigurationSection(key)));
                } catch (IllegalArgumentException ignored) {
                    // skip malformed entries
                }
            }
        }
    }
}
