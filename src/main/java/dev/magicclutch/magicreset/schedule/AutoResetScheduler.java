package dev.magicclutch.magicreset.schedule;

import dev.magicclutch.magicreset.MagicReset;
import dev.magicclutch.magicreset.game.GameManager;
import dev.magicclutch.magicreset.util.TimeFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * Scheduled resets: after a duration ("in 2h") or at a time of day ("at 18:00"), once or repeating.
 * Uses the server's local time zone and survives restarts.
 */
public final class AutoResetScheduler {

    public enum Kind {
        OFF,
        INTERVAL,
        DAILY
    }

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private final MagicReset plugin;
    private final GameManager game;

    private Kind kind = Kind.OFF;
    private Duration interval = Duration.ZERO;
    private @Nullable LocalTime time;
    private boolean loop;
    /** Epoch millis of the next reset, 0 when off. */
    private long next;
    /** Smallest warning already announced for the current schedule. */
    private long lastAnnounced = Long.MAX_VALUE;
    private @Nullable BukkitTask task;

    public AutoResetScheduler(MagicReset plugin, GameManager game) {
        this.plugin = plugin;
        this.game = game;
    }

    public void start() {
        this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
    }

    public void scheduleIn(Duration delay, boolean repeat) {
        this.kind = Kind.INTERVAL;
        this.interval = delay;
        this.time = null;
        this.loop = repeat;
        this.next = System.currentTimeMillis() + delay.toMillis();
        this.lastAnnounced = Long.MAX_VALUE;
        this.game.saveAsync();
    }

    public void scheduleAt(LocalTime at, boolean repeat) {
        this.kind = Kind.DAILY;
        this.time = at;
        this.interval = Duration.ZERO;
        this.loop = repeat;
        this.next = nextOccurrence(at);
        this.lastAnnounced = Long.MAX_VALUE;
        this.game.saveAsync();
    }

    public void disable() {
        this.kind = Kind.OFF;
        this.next = 0;
        this.game.saveAsync();
    }

    private static long nextOccurrence(LocalTime at) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        ZonedDateTime candidate = now.with(at).withSecond(0).withNano(0);
        if (!candidate.isAfter(now)) {
            candidate = candidate.plusDays(1);
        }
        return candidate.toInstant().toEpochMilli();
    }

    private void tick() {
        if (this.kind == Kind.OFF) {
            return;
        }
        long remainingSeconds = this.secondsRemaining();
        if (remainingSeconds > 0) {
            // the smallest warning threshold that has been reached and not announced yet; lag cannot skip one
            long threshold = this.plugin.settings().autoReset().warnings().stream()
                .mapToLong(Integer::longValue)
                .filter(seconds -> seconds >= remainingSeconds && seconds < this.lastAnnounced)
                .min().orElse(-1L);
            if (threshold > 0) {
                this.lastAnnounced = threshold;
                this.plugin.messages().send(Bukkit.getServer(), "auto-reset.warning",
                    Placeholder.unparsed("time", TimeFormat.compact(remainingSeconds)));
            }
            return;
        }
        // due: reschedule first so a failing reset cannot trigger again every second
        switch (this.kind) {
            case INTERVAL -> {
                if (this.loop) {
                    this.next = System.currentTimeMillis() + this.interval.toMillis();
                } else {
                    this.kind = Kind.OFF;
                }
            }
            case DAILY -> {
                if (this.loop && this.time != null) {
                    this.next = nextOccurrence(this.time);
                } else {
                    this.kind = Kind.OFF;
                }
            }
            case OFF -> {
                return;
            }
        }
        this.lastAnnounced = Long.MAX_VALUE;
        if (this.game.phase() == GameManager.Phase.RESETTING) {
            this.game.saveAsync();
            return;
        }
        this.plugin.messages().send(Bukkit.getServer(), "auto-reset.now");
        this.game.requestReset(null, 0, this.plugin.settings().reset().limboCountdown());
    }

    public Kind kind() {
        return this.kind;
    }

    public boolean loop() {
        return this.loop;
    }

    public long secondsRemaining() {
        return this.kind == Kind.OFF ? 0L : Math.max(0L, (this.next - System.currentTimeMillis() + 999L) / 1000L);
    }

    public String describe() {
        return switch (this.kind) {
            case OFF -> "off";
            case INTERVAL -> (this.loop ? "every " : "in ") + TimeFormat.compact(this.interval.toSeconds());
            case DAILY -> "at " + (this.time == null ? "?" : CLOCK.format(this.time));
        };
    }

    public void save(ConfigurationSection section) {
        section.set("kind", this.kind.name());
        section.set("interval-seconds", this.interval.toSeconds());
        section.set("time", this.time == null ? null : CLOCK.format(this.time));
        section.set("loop", this.loop);
        section.set("next", this.next);
    }

    public void load(@Nullable ConfigurationSection section) {
        if (section == null) {
            return;
        }
        try {
            this.kind = Kind.valueOf(section.getString("kind", Kind.OFF.name()));
        } catch (IllegalArgumentException e) {
            this.kind = Kind.OFF;
        }
        this.interval = Duration.ofSeconds(Math.max(1L, section.getLong("interval-seconds", 1L)));
        String rawTime = section.getString("time");
        try {
            this.time = rawTime == null ? null : LocalTime.parse(rawTime, CLOCK);
        } catch (RuntimeException e) {
            this.time = null;
        }
        this.loop = section.getBoolean("loop");
        this.next = section.getLong("next");
        if (this.kind == Kind.DAILY && this.time == null) {
            this.kind = Kind.OFF;
        }
        long overdueBy = System.currentTimeMillis() - this.next;
        if (this.kind != Kind.OFF && overdueBy > 0) {
            // missed while the server was offline: give players a minute after startup
            this.next = System.currentTimeMillis() + 60_000L;
            this.plugin.getLogger().info("A scheduled reset was missed while the server was offline, it runs in 60 seconds");
        }
    }

    /** Parses "HH:mm". */
    public static @Nullable LocalTime parseClock(String raw) {
        try {
            return LocalTime.parse(raw.length() == 4 ? "0" + raw : raw, CLOCK);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** For status output. */
    public String nextAsText() {
        if (this.kind == Kind.OFF) {
            return "-";
        }
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(this.next), ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT));
    }
}
