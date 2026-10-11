package dev.magicclutch.magicreset.game;

public enum DeathMode {
    /** Deaths have no special effect. */
    OFF,
    /** The first death of any player fails the run. */
    HARDCORE,
    /** All players share a pool of lives, the run fails when it is used up. */
    SHARED_LIVES
}
