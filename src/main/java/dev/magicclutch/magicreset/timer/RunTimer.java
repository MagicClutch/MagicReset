package dev.magicclutch.magicreset.timer;

import org.bukkit.configuration.ConfigurationSection;

/**
 * A stopwatch that tracks real time (RTA) and in-game ticks (IGT) side by side, so switching the display mode
 * never loses information.
 */
public final class RunTimer {

    public enum State {
        IDLE,
        RUNNING,
        PAUSED,
        FINISHED
    }

    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long MILLIS_PER_TICK = 50L;

    private State state = State.IDLE;
    private long rtaAccumulatedMillis;
    private long runningSinceNanos;
    private long ticks;

    public State state() {
        return this.state;
    }

    public boolean isRunning() {
        return this.state == State.RUNNING;
    }

    /** Starts an idle timer or resumes a paused one. */
    public boolean start() {
        if (this.state != State.IDLE && this.state != State.PAUSED) {
            return false;
        }
        this.state = State.RUNNING;
        this.runningSinceNanos = System.nanoTime();
        return true;
    }

    public boolean pause() {
        if (this.state != State.RUNNING) {
            return false;
        }
        this.checkpoint();
        this.state = State.PAUSED;
        return true;
    }

    public boolean finish() {
        if (this.state == State.FINISHED || this.state == State.IDLE) {
            return false;
        }
        this.checkpoint();
        this.state = State.FINISHED;
        return true;
    }

    public void reset() {
        this.state = State.IDLE;
        this.rtaAccumulatedMillis = 0L;
        this.ticks = 0L;
    }

    /** Sets both clocks to the given time, keeping the current state. */
    public void set(long millis) {
        long value = Math.max(0L, millis);
        this.rtaAccumulatedMillis = value;
        this.runningSinceNanos = System.nanoTime();
        this.ticks = value / MILLIS_PER_TICK;
        if (this.state == State.IDLE) {
            this.state = State.PAUSED;
        }
    }

    /** Takes over the time and state of another timer. */
    public void copyFrom(RunTimer other) {
        this.state = other.state;
        this.rtaAccumulatedMillis = other.rtaMillis();
        this.runningSinceNanos = System.nanoTime();
        this.ticks = other.ticks;
    }

    /** Called once per server tick while the clock is allowed to count in-game time. */
    public void tick() {
        if (this.state == State.RUNNING) {
            this.ticks++;
        }
    }

    public long rtaMillis() {
        if (this.state == State.RUNNING) {
            return this.rtaAccumulatedMillis + (System.nanoTime() - this.runningSinceNanos) / NANOS_PER_MILLI;
        }
        return this.rtaAccumulatedMillis;
    }

    public long igtMillis() {
        return this.ticks * MILLIS_PER_TICK;
    }

    public long millis(TimerMode mode) {
        return mode == TimerMode.IGT ? this.igtMillis() : this.rtaMillis();
    }

    private void checkpoint() {
        if (this.state == State.RUNNING) {
            long now = System.nanoTime();
            this.rtaAccumulatedMillis += (now - this.runningSinceNanos) / NANOS_PER_MILLI;
            this.runningSinceNanos = now;
        }
    }

    public void save(ConfigurationSection section) {
        section.set("state", this.state.name());
        section.set("rta-millis", this.rtaMillis());
        section.set("igt-ticks", this.ticks);
    }

    /**
     * Restores a saved timer. Time the server was offline is not counted, a running timer continues from the
     * value it had at shutdown.
     */
    public static RunTimer load(ConfigurationSection section) {
        RunTimer timer = new RunTimer();
        if (section == null) {
            return timer;
        }
        try {
            timer.state = State.valueOf(section.getString("state", State.IDLE.name()));
        } catch (IllegalArgumentException e) {
            timer.state = State.IDLE;
        }
        timer.rtaAccumulatedMillis = Math.max(0L, section.getLong("rta-millis"));
        timer.ticks = Math.max(0L, section.getLong("igt-ticks"));
        timer.runningSinceNanos = System.nanoTime();
        return timer;
    }
}
