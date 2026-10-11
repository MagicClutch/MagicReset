package dev.magicclutch.magicreset.timer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class RunTimerTest {

    @Test
    void igtCountsTicksOnlyWhileRunning() {
        RunTimer timer = new RunTimer();
        timer.tick();
        assertEquals(0, timer.igtMillis());
        timer.start();
        for (int i = 0; i < 40; i++) {
            timer.tick();
        }
        timer.pause();
        timer.tick();
        assertEquals(2_000, timer.igtMillis());
    }

    @Test
    void stateTransitions() {
        RunTimer timer = new RunTimer();
        assertFalse(timer.finish());
        assertTrue(timer.start());
        assertFalse(timer.start());
        assertTrue(timer.pause());
        assertTrue(timer.start());
        assertTrue(timer.finish());
        assertFalse(timer.start());
        assertEquals(RunTimer.State.FINISHED, timer.state());
    }

    @Test
    void setAndPersist() {
        RunTimer timer = new RunTimer();
        timer.set(61_000);
        assertEquals(RunTimer.State.PAUSED, timer.state());
        assertEquals(61_000, timer.rtaMillis());
        assertEquals(61_000, timer.igtMillis());

        YamlConfiguration yaml = new YamlConfiguration();
        timer.save(yaml.createSection("t"));
        RunTimer loaded = RunTimer.load(yaml.getConfigurationSection("t"));
        assertEquals(RunTimer.State.PAUSED, loaded.state());
        assertEquals(61_000, loaded.rtaMillis());
        assertEquals(61_000, loaded.igtMillis());
    }

    @Test
    void rtaKeepsRunning() throws InterruptedException {
        RunTimer timer = new RunTimer();
        timer.start();
        Thread.sleep(30);
        assertTrue(timer.rtaMillis() >= 25);
    }
}
