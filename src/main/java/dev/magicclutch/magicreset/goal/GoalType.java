package dev.magicclutch.magicreset.goal;

public enum GoalType {
    NONE(false, false),
    /** Beat the game: leave the end through the exit portal (after the dragon was killed). */
    BEAT_GAME(false, false),
    /** Kill an entity type, e.g. minecraft:ender_dragon. */
    KILL_ENTITY(true, true),
    /** Travel through a portal into a dimension: nether, end or overworld. */
    ENTER_DIMENSION(true, false),
    /** Complete an advancement. */
    ADVANCEMENT(true, false),
    /** Break a block type. */
    BREAK_BLOCK(true, true),
    /** Have an item in the inventory. */
    OBTAIN_ITEM(true, true);

    private final boolean needsTarget;
    private final boolean supportsAmount;

    GoalType(boolean needsTarget, boolean supportsAmount) {
        this.needsTarget = needsTarget;
        this.supportsAmount = supportsAmount;
    }

    public boolean needsTarget() {
        return this.needsTarget;
    }

    public boolean supportsAmount() {
        return this.supportsAmount;
    }
}
