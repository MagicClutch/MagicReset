package dev.magicclutch.magicreset.timer;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
    /** {@code true} once timers were started for this run (needed to start late joiners in MANUAL mode). */
    private boolean started;
    /** {@code true} while the timers are paused by an admin or the limbo pause. */
    private boolean paused;
    /** {@code true} once the run has ended (goal completed or failed); no new timers start. */
    private boolean ended;

    public record TimerSettings(TimerMode mode, TimerScope scope, StartTrigger trigger, boolean igtPauseWhenEmpty, boolean stopWhenEmpty) {
    }

    /** Global timer stopped because nobody was playing (timer.stop-when-empty). */
    private boolean globalAutoPaused;
    /** Individual timers stopped because their player left (timer.stop-when-empty). */
    private final Set<UUID> autoPaused = new HashSet<>();

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
        this.globalAutoPaused = false;
        this.autoPaused.clear();
        this.started = false;
        this.paused = false;
        this.ended = false;
    }

    /** Called when the players are released into a fresh world. */
    public void begin(Collection<? extends Player> participants) {
        this.armed = true;
        if (this.settings().trigger() != StartTrigger.AUTO) {
            return;
        }
        this.started = true;
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
        if (!this.armed || this.ended || this.scope() != TimerScope.INDIVIDUAL) {
            return;
        }
        StartTrigger trigger = this.settings().trigger();
        if (trigger == StartTrigger.FIRST_INPUT || (trigger == StartTrigger.MANUAL && !this.started)) {
            return;
        }
        RunTimer timer = this.timer(player.getUniqueId());
        if (timer.state() != RunTimer.State.IDLE) {
            return;
        }
        if (this.paused) {
            timer.set(0L); // joins paused, continues with everybody else
        } else {
            timer.start();
        }
    }

    /** A participant moved or interacted for the first time. */
    public void onInput(Player player) {
        if (!this.armed || this.settings().trigger() != StartTrigger.FIRST_INPUT) {
            return;
        }
        boolean global = this.scope() == TimerScope.GLOBAL;
        RunTimer timer = global ? this.global : this.timer(player.getUniqueId());
        if (!this.ended && timer.state() == RunTimer.State.IDLE) {
            this.started = true;
            timer.start();
        } else if (global ? this.globalAutoPaused : this.autoPaused.contains(player.getUniqueId())) {
            // back after everybody had left: continue on the first movement, like at the start
            this.clearAutoPause(player.getUniqueId());
            timer.start();
        }
    }

    public boolean isWaitingForInput(Player player) {
        if (!this.armed || this.settings().trigger() != StartTrigger.FIRST_INPUT) {
            return false;
        }
        boolean autoPausedNow = this.scope() == TimerScope.GLOBAL ? this.globalAutoPaused : this.autoPaused.contains(player.getUniqueId());
        return this.displayed(player).state() == RunTimer.State.IDLE || autoPausedNow;
    }

    /**
     * timer.stop-when-empty: stops the timer while nobody (global) or the player (individual) is playing and
     * continues it when they are back - immediately, or on the first movement with the FIRST_INPUT start.
     *
     * @param inGame players currently playing in the game worlds
     */
    public void updatePresence(Collection<? extends Player> inGame) {
        TimerSettings config = this.settings();
        if (!config.stopWhenEmpty()) {
            // switched off while something was stopped: continue it
            if (this.globalAutoPaused) {
                this.globalAutoPaused = false;
                this.global.start();
            }
            this.autoPaused.forEach(uuid -> this.timer(uuid).start());
            this.autoPaused.clear();
            return;
        }
        boolean resumeOnJoin = config.trigger() != StartTrigger.FIRST_INPUT;
        if (config.scope() == TimerScope.GLOBAL) {
            if (inGame.isEmpty() && this.global.isRunning()) {
                this.global.pause();
                this.globalAutoPaused = true;
            } else if (!inGame.isEmpty() && this.globalAutoPaused && resumeOnJoin) {
                this.globalAutoPaused = false;
                this.global.start();
            }
            return;
        }
        Set<UUID> present = new HashSet<>();
        inGame.forEach(player -> present.add(player.getUniqueId()));
        this.individual.forEach((uuid, timer) -> {
            if (!present.contains(uuid) && timer.isRunning()) {
                timer.pause();
                this.autoPaused.add(uuid);
            } else if (present.contains(uuid) && resumeOnJoin && this.autoPaused.remove(uuid)) {
                timer.start();
            }
        });
    }

    private void clearAutoPause(UUID player) {
        if (this.scope() == TimerScope.GLOBAL) {
            this.globalAutoPaused = false;
        } else {
            this.autoPaused.remove(player);
        }
    }

    /** {@code true} while the timer shown to this player is stopped because nobody was playing. */
    public boolean isAutoPaused(Player player) {
        return this.scope() == TimerScope.GLOBAL ? this.globalAutoPaused : this.autoPaused.contains(player.getUniqueId());
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
        this.started = true;
        this.paused = false;
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
        this.paused = true;
        // an explicit pause replaces the automatic one, only an explicit resume continues
        this.globalAutoPaused = false;
        this.autoPaused.clear();
        this.global.pause();
        this.individual.values().forEach(RunTimer::pause);
    }

    public void resumeAll() {
        this.paused = false;
        this.globalAutoPaused = false;
        this.autoPaused.clear();
        if (this.global.state() == RunTimer.State.PAUSED) {
            this.global.start();
        }
        this.individual.values().forEach(timer -> {
            if (timer.state() == RunTimer.State.PAUSED) {
                timer.start();
            }
        });
    }

    /** Marks the run as over; timers of players joining later no longer start. */
    public void endRun() {
        this.ended = true;
    }

    public void finishAll() {
        this.ended = true;
        this.global.finish();
        this.individual.values().forEach(RunTimer::finish);
    }

    /**
     * The start setting (auto / first movement / manual) was changed during a run. In the first minute the timer
     * starts over with the new setting, e.g. it waits for the first movement; a run that is already going on
     * keeps its time and the setting applies from the next run on.
     */
    public void onTriggerChanged(Collection<? extends Player> participants) {
        if (!this.armed || this.ended) {
            return;
        }
        long longest = this.global.millis(TimerMode.RTA);
        for (RunTimer timer : this.individual.values()) {
            longest = Math.max(longest, timer.millis(TimerMode.RTA));
        }
        if (longest > 60_000L) {
            return;
        }
        this.resetAll();
        this.globalAutoPaused = false;
        this.autoPaused.clear();
        this.started = false;
        this.paused = false;
        this.begin(participants);
    }

    public void resetAll() {
        this.global.reset();
        this.individual.clear();
    }

    public void setAll(long millis, Collection<? extends Player> participants) {
        this.global.set(millis);
        participants.forEach(player -> this.timer(player.getUniqueId()));
        this.individual.values().forEach(timer -> timer.set(millis));
    }

    public boolean isArmed() {
        return this.armed;
    }

    public void save(ConfigurationSection section) {
        section.set("armed", this.armed);
        section.set("started", this.started);
        section.set("paused", this.paused);
        section.set("ended", this.ended);
        section.set("auto-paused-global", this.globalAutoPaused);
        section.set("auto-paused-players", this.autoPaused.stream().map(UUID::toString).toList());
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
        this.started = section.getBoolean("started", this.armed);
        this.paused = section.getBoolean("paused");
        this.ended = section.getBoolean("ended");
        this.globalAutoPaused = section.getBoolean("auto-paused-global");
        for (String raw : section.getStringList("auto-paused-players")) {
            try {
                this.autoPaused.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
                // skip malformed entries
            }
        }
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
