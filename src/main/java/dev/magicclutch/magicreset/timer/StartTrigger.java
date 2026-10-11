package dev.magicclutch.magicreset.timer;

public enum StartTrigger {
    /** Starts as soon as the players are released from limbo into the new world. */
    AUTO,
    /** Starts on the first movement or interaction. */
    FIRST_INPUT,
    /** Only starts with /magicreset timer start. */
    MANUAL
}
