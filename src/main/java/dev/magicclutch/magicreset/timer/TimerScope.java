package dev.magicclutch.magicreset.timer;

public enum TimerScope {
    /** One shared timer. The first player to reach the goal stops it for everyone. */
    GLOBAL,
    /** Every player has a personal timer that stops when they reach the goal. */
    INDIVIDUAL
}
