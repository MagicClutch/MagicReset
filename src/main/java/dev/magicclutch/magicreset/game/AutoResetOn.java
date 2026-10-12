package dev.magicclutch.magicreset.game;

/** When a new run starts on its own (reset.auto). */
public enum AutoResetOn {
    /** Only by hand. */
    OFF,
    /** When the run is lost: a death (or the last shared life) ends it. */
    DEATH,
    /** When the goal is reached or the manhunt is decided. */
    GOAL,
    /** Both. */
    BOTH;

    public boolean onDeath() {
        return this == DEATH || this == BOTH;
    }

    public boolean onGoal() {
        return this == GOAL || this == BOTH;
    }
}
