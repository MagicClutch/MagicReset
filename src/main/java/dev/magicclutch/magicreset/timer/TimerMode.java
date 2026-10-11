package dev.magicclutch.magicreset.timer;

public enum TimerMode {
    /** Real time attack: wall clock time, keeps running even when nobody is online. */
    RTA,
    /** In-game time: counts server ticks of actual play time (pauses on lag freezes and, optionally, when empty). */
    IGT
}
