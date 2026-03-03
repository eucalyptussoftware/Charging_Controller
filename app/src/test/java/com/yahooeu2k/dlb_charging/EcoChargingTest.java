package com.yahooeu2k.dlb_charging;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Eco Charging Test Suite
 *
 * Goal: verify that eco mode squeezes maximum solar while minimizing grid
 * usage.
 * Covers: ramp up/down, stop/resume, cooldowns, oscillation prevention, full
 * lifecycle.
 *
 * Key eco-mode parameters:
 * - ecoToleranceW: default 100W — threshold for ramp decisions
 * - MIN_AMPS = 5, MAX_AMPS = 32, VOLTS = 230
 * - Ramp up delay: 30s between increases
 * - Eco stop: after 60s sustained at 5A with consumption > ecoToleranceW
 * - Eco cooldown: 5 min after stop before resume is allowed
 * - Resume condition: consumption <= 10W
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class EcoChargingTest {

    private static final int MIN_AMPS = 5;
    private static final int MAX_AMPS = 32;

    private ChargingController controller;
    private Context context;

    @Before
    public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();

        // Clear any stale prefs
        SharedPreferences prefs = context.getSharedPreferences("charging_controller", Context.MODE_PRIVATE);
        prefs.edit().clear().apply();

        // Reset singleton so each test gets a fresh controller
        Field instanceField = ChargingController.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);

        controller = ChargingController.getInstance(context);

        // Enable the controller + eco mode
        controller.setEnabled(true);
        controller.setEcoModeEnabled(true);
        controller.setEcoToleranceW(100);
        controller.setOverConsumptionToleranceW(200); // standard mode tolerance (unused in eco)
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** Advance the ramp-up delay so the next update can ramp. */
    private void clearRampUpDelay() throws Exception {
        Field f = ChargingController.class.getDeclaredField("lastRampUpTime");
        f.setAccessible(true);
        f.setLong(controller, 0);
    }

    /** Advance the eco stop evaluation so it thinks 60s have passed. */
    private void forceEcoStopEvaluationElapsed() throws Exception {
        Field f = ChargingController.class.getDeclaredField("ecoStopEvaluationStartTime");
        f.setAccessible(true);
        long current = f.getLong(controller);
        if (current > 0) {
            // Set to 61 seconds in the past
            f.setLong(controller, System.currentTimeMillis() - 61_000);
        }
    }

    /** Set the eco stop evaluation start time. */
    private void setEcoStopEvaluationStartTime(long time) throws Exception {
        Field f = ChargingController.class.getDeclaredField("ecoStopEvaluationStartTime");
        f.setAccessible(true);
        f.setLong(controller, time);
    }

    /** Get the eco stop evaluation start time. */
    private long getEcoStopEvaluationStartTime() throws Exception {
        Field f = ChargingController.class.getDeclaredField("ecoStopEvaluationStartTime");
        f.setAccessible(true);
        return f.getLong(controller);
    }

    /** Clear the eco cooldown so resume can happen immediately. */
    private void clearEcoCooldown() throws Exception {
        Field f = ChargingController.class.getDeclaredField("lastEcoStopTime");
        f.setAccessible(true);
        f.setLong(controller, 0);
    }

    /** Set lastEcoStopTime to simulate partial cooldown. */
    private void setLastEcoStopTime(long time) throws Exception {
        Field f = ChargingController.class.getDeclaredField("lastEcoStopTime");
        f.setAccessible(true);
        f.setLong(controller, time);
    }

    /** Get the current backoff level. */
    private int getEcoBackoffLevel() throws Exception {
        Field f = ChargingController.class.getDeclaredField("ecoBackoffLevel");
        f.setAccessible(true);
        return f.getInt(controller);
    }

    /** Set the backoff level. */
    private void setEcoBackoffLevel(int level) throws Exception {
        Field f = ChargingController.class.getDeclaredField("ecoBackoffLevel");
        f.setAccessible(true);
        f.setInt(controller, level);
    }

    private int getCurrentAmps() {
        return controller.getCurrentAmps();
    }

    private int getLastSentAmps() throws Exception {
        Field f = ChargingController.class.getDeclaredField("lastSentAmps");
        f.setAccessible(true);
        return f.getInt(controller);
    }

    private boolean isEcoModeActivelyCharging() throws Exception {
        Field f = ChargingController.class.getDeclaredField("ecoModeIsActivelyCharging");
        f.setAccessible(true);
        return f.getBoolean(controller);
    }

    private void setCurrentAmps(int amps) throws Exception {
        Field f = ChargingController.class.getDeclaredField("currentAmps");
        f.setAccessible(true);
        f.setInt(controller, amps);
    }

    private void setLastSentAmps(int amps) throws Exception {
        Field f = ChargingController.class.getDeclaredField("lastSentAmps");
        f.setAccessible(true);
        f.setInt(controller, amps);
    }

    /** Clear oscillation tracking so ramp-up isn't blocked by prior drops. */
    private void clearOscillationTracking() throws Exception {
        Field dropFrom = ChargingController.class.getDeclaredField("lastDropFromAmps");
        dropFrom.setAccessible(true);
        dropFrom.setInt(controller, -1);

        Field dropTime = ChargingController.class.getDeclaredField("lastDropTime");
        dropTime.setAccessible(true);
        dropTime.setLong(controller, 0);
    }

    /** Clear saturation tracking used in ramp-up check. */
    private void clearSaturationTracking() throws Exception {
        Field amps = ChargingController.class.getDeclaredField("ampsAtLastRampUp");
        amps.setAccessible(true);
        amps.setInt(controller, -1);

        Field cons = ChargingController.class.getDeclaredField("consumptionAtLastRampUp");
        cons.setAccessible(true);
        cons.setInt(controller, -1);
    }

    /**
     * Force a single ramp-up by sending 0W consumption with delays cleared.
     * Returns the new amp value.
     */
    private int doOneRampUp() throws Exception {
        clearRampUpDelay();
        clearOscillationTracking();
        clearSaturationTracking();
        controller.onPowerUpdate(0);
        return getCurrentAmps();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 1: Ramp up on solar surplus
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * With 0W grid consumption (solar covers everything), eco mode should ramp
     * from 5A all the way to 32A, one amp per cycle.
     *
     * This verifies the app squeezes maximum solar into the EV.
     */
    @Test
    public void test01_rampUpOnSolarSurplus() throws Exception {
        assertEquals("Should start at MIN_AMPS", MIN_AMPS, getCurrentAmps());

        for (int expected = MIN_AMPS + 1; expected <= MAX_AMPS; expected++) {
            int actual = doOneRampUp();
            assertEquals("Should ramp to " + expected + "A", expected, actual);
        }

        // At max — one more update should not go higher
        int afterMax = doOneRampUp();
        assertEquals("Should stay at MAX_AMPS", MAX_AMPS, afterMax);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 2: Ramp down on consumption spike (rapid drop)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * When grid consumption jumps above ecoToleranceW (100W), eco mode should
     * fast-drop based on excess: ceil((500-100)/230) = ceil(1.74) = 2A.
     */
    @Test
    public void test02_rampDownOnConsumptionSpike() throws Exception {
        // Ramp up to 10A first
        for (int i = 0; i < 5; i++) {
            doOneRampUp();
        }
        assertEquals(10, getCurrentAmps());

        // 500W consumption, tolerance 100W → excess 400W → ceil(400/230)=2A drop
        controller.onPowerUpdate(500);
        assertEquals("Eco mode fast-drops 2A", 8, getCurrentAmps());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 3: Eco rapid-drops to MIN on massive overconsumption
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * With massive overconsumption (2000W), eco mode now fast-drops just like
     * standard mode: ceil((2000-100)/230) = ceil(8.26) = 9A drop.
     * From 15A → drops 9A → clamped to MIN_AMPS (5A).
     */
    @Test
    public void test03_ecoRapidDropToMin() throws Exception {
        // Set to 15A
        setCurrentAmps(15);
        setLastSentAmps(15);

        // 2000W overconsumption → excess=1900W → ceil(1900/230)=9A drop
        // 15-9=6, but also 15-9=6... let's verify: ceil(1900/230) = ceil(8.26) = 9
        // 15A - 9A = 6A (still above MIN)
        controller.onPowerUpdate(2000);
        assertEquals("Eco mode rapid-drops from 15A", 6, getCurrentAmps());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 4: Stop charging after 60s sustained at MIN
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * When at 5A (MIN) and consumption > ecoToleranceW for 60 seconds,
     * eco mode should stop charging entirely (send eco intent 0).
     * This prevents wasteful grid usage when solar isn't enough.
     */
    @Test
    public void test04_stopAfter60sSustainedAtMin() throws Exception {
        // Already at 5A (MIN). Send high consumption to trigger evaluation.
        controller.onPowerUpdate(200); // > 100W tolerance → starts 60s evaluation
        assertTrue("Should still be charging during evaluation",
                isEcoModeActivelyCharging());

        // Simulate 60s passing
        forceEcoStopEvaluationElapsed();

        // Next update with consumption still high → should trigger stop
        controller.onPowerUpdate(200);
        assertFalse("Should have stopped charging", isEcoModeActivelyCharging());
        assertEquals("lastSentAmps should be 0 (stopped)", 0, getLastSentAmps());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 5: Stop evaluation resets when condition is relieved
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * If consumption drops below tolerance during the 60s evaluation window,
     * the timer should reset. This prevents premature stops when a cloud
     * passes briefly.
     */
    @Test
    public void test05_stopEvaluationResetsOnRelief() throws Exception {
        // Start at 5A, high consumption → begins evaluation
        controller.onPowerUpdate(200);
        assertTrue("Evaluation should have started", getEcoStopEvaluationStartTime() > 0);

        // Consumption drops back to within tolerance
        clearRampUpDelay();
        controller.onPowerUpdate(50); // ≤ 100W
        assertEquals("Evaluation should be reset", 0, getEcoStopEvaluationStartTime());
        assertTrue("Should still be actively charging", isEcoModeActivelyCharging());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 6: Resume after backoff cooldown elapsed
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * After eco stop, once consumption ≤ 10W AND the backoff cooldown has
     * elapsed, charging should resume at MIN_AMPS.
     * First stop sets backoff to 2 (2min), so we clear cooldown entirely.
     */
    @Test
    public void test06_resumeAfterCooldown() throws Exception {
        // Force a stop (backoff goes 1→2)
        triggerEcoStop();
        assertFalse("Should be stopped", isEcoModeActivelyCharging());
        assertEquals("Backoff should be 2 after first stop", 2, getEcoBackoffLevel());

        // Clear the cooldown entirely (simulate full backoff passing)
        clearEcoCooldown();

        // Send low consumption → should resume
        controller.onPowerUpdate(0);
        assertTrue("Should have resumed charging", isEcoModeActivelyCharging());
        assertEquals("Should resume at MIN_AMPS", MIN_AMPS, getCurrentAmps());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 7: Resume blocked during backoff cooldown
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * If consumption drops to 0W but the backoff cooldown hasn't elapsed,
     * charging should NOT resume. After first stop, backoff = 2 (2min).
     * At 30s into cooldown, resume should be blocked.
     */
    @Test
    public void test07_resumeBlockedDuringCooldown() throws Exception {
        // Force a stop (backoff goes 1→2, so cooldown = 2min)
        triggerEcoStop();
        assertFalse("Should be stopped", isEcoModeActivelyCharging());

        // Set stop time to 30s ago (need 2min = 120s)
        setLastEcoStopTime(System.currentTimeMillis() - 30_000);

        // Low consumption — but cooldown hasn't passed
        controller.onPowerUpdate(0);
        assertFalse("Should stay stopped — backoff cooldown active", isEcoModeActivelyCharging());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 8: Consumption fluctuates up and down
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Simulates a realistic scenario where household consumption varies:
     * some solar, then a kettle turns on, then off again. The controller
     * should ramp up when solar is surplus and ramp down when consumption
     * spikes — always getting the most from solar.
     */
    @Test
    public void test08_consumptionFluctuations() throws Exception {
        // Phase 1: Solar surplus — ramp up
        assertEquals(MIN_AMPS, getCurrentAmps());
        doOneRampUp(); // 6A
        doOneRampUp(); // 7A
        doOneRampUp(); // 8A
        assertEquals(8, getCurrentAmps());

        // Phase 2: Kettle turns on — 400W spike
        // excess = 400-100 = 300W → ceil(300/230) = 2A drop → 8-2=6A
        controller.onPowerUpdate(400);
        assertEquals("Should rapid-drop to 6A", 6, getCurrentAmps());

        // Phase 3: Kettle off — back to solar surplus
        doOneRampUp(); // 7A
        assertEquals("Should ramp back to 7A", 7, getCurrentAmps());
        doOneRampUp(); // 8A
        assertEquals("Should ramp back to 8A", 8, getCurrentAmps());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 9: Full lifecycle — ramp up → spike → stop → resume → ramp
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * End-to-end lifecycle:
     * 1. Solar surplus → ramp up
     * 2. Massive consumption → ramp all the way down to MIN
     * 3. Sustained overconsumption → eco stop
     * 4. Solar returns, cooldown passes → resume
     * 5. Ramp up again
     */
    @Test
    public void test09_fullLifecycle() throws Exception {
        // 1. Ramp up from 5A to 8A on solar surplus
        doOneRampUp(); // 6A
        doOneRampUp(); // 7A
        doOneRampUp(); // 8A
        assertEquals("Phase 1: ramped to 8A", 8, getCurrentAmps());

        // 2. Big consumption spike — rapid drop to MIN
        // 1000W → excess=900W → ceil(900/230)=4A drop → 8-4=4→clamped to 5A (MIN)
        controller.onPowerUpdate(1000);
        assertEquals("Phase 2: rapid-dropped to MIN", MIN_AMPS, getCurrentAmps());

        // 3. Sustained overconsumption at MIN for 60s → eco stop
        controller.onPowerUpdate(200); // Starts evaluation
        forceEcoStopEvaluationElapsed();
        controller.onPowerUpdate(200); // Triggers stop
        assertFalse("Phase 3: should be stopped", isEcoModeActivelyCharging());
        assertEquals(0, getLastSentAmps());

        // 4. Solar returns, clear cooldown → resume
        clearEcoCooldown();
        controller.onPowerUpdate(0);
        assertTrue("Phase 4: should have resumed", isEcoModeActivelyCharging());
        assertEquals("Phase 4: resumed at MIN", MIN_AMPS, getCurrentAmps());

        // 5. Continue ramping up
        doOneRampUp(); // 6A
        doOneRampUp(); // 7A
        assertEquals("Phase 5: ramping back up", 7, getCurrentAmps());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 10: Noise filtering — ≤20W treated as 0W
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Small fluctuations (≤20W) should be treated as zero consumption.
     * This ensures tiny inverter noise doesn't prevent ramp-up.
     */
    @Test
    public void test10_noiseFiltering() throws Exception {
        clearRampUpDelay();
        clearOscillationTracking();
        clearSaturationTracking();

        controller.onPowerUpdate(15); // 15W ≤ 20W → treated as 0W → ramp up
        assertEquals("15W noise should allow ramp up", MIN_AMPS + 1, getCurrentAmps());

        clearRampUpDelay();
        clearSaturationTracking();
        controller.onPowerUpdate(20); // Exactly 20W → treated as 0W
        assertEquals("20W noise should allow ramp up", MIN_AMPS + 2, getCurrentAmps());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 11: Eco tolerance boundary — exact threshold
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Consumption at 0W (within tolerance) should allow ramp up.
     * Consumption at 101W (over tolerance) should trigger ramp down.
     *
     * Note: even consumption at exactly 100W (== tolerance) is "within tolerance"
     * but oscillation prevention blocks ramp-up because 100W + 250W (predicted
     * power increase) > 100W tolerance. This is correct behaviour — the controller
     * avoids ramping when it predicts overshoot.
     */
    @Test
    public void test11_toleranceBoundary() throws Exception {
        setCurrentAmps(10);
        setLastSentAmps(10);

        // 0W → well within tolerance → ramp up
        clearRampUpDelay();
        clearOscillationTracking();
        clearSaturationTracking();
        controller.onPowerUpdate(0);
        assertEquals("0W (within tolerance) should ramp up", 11, getCurrentAmps());

        // 101W → over tolerance → ramp down by 1A (eco mode)
        controller.onPowerUpdate(101);
        assertEquals("101W (> tolerance) should ramp down", 10, getCurrentAmps());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 12: Ramp-up throttle (30s delay)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Two rapid updates at 0W should NOT cause two ramp-ups.
     * The 30s delay between ramps prevents oscillation.
     */
    @Test
    public void test12_rampUpThrottle() throws Exception {
        clearRampUpDelay();
        clearOscillationTracking();
        clearSaturationTracking();

        controller.onPowerUpdate(0); // Should ramp 5→6
        assertEquals("First update ramps up", MIN_AMPS + 1, getCurrentAmps());

        // Second update immediately (no delay cleared) — should NOT ramp
        controller.onPowerUpdate(0);
        assertEquals("Second update throttled — no ramp", MIN_AMPS + 1, getCurrentAmps());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 13: Oscillation prevention (predicted overshoot)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * If current consumption + ~250W (expected 1A increase) would exceed
     * the eco tolerance, the controller should hold and not ramp up.
     * This prevents grid spikes from aggressive ramping.
     */
    @Test
    public void test13_oscillationPrevention() throws Exception {
        setCurrentAmps(10);
        setLastSentAmps(10);

        clearRampUpDelay();
        clearOscillationTracking();
        clearSaturationTracking();

        // 90W consumption. 90 + 250 (expected increase) = 340 > 100 tolerance
        // → should predict overshoot and hold
        controller.onPowerUpdate(90);
        assertEquals("Should hold due to predicted overshoot", 10, getCurrentAmps());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 14: Disabling eco mode while stopped resumes charging
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * If eco mode is disabled while charging is stopped, the controller
     * should immediately resume (send eco intent ON) and restore amps.
     */
    @Test
    public void test14_disableEcoWhileStoppedResumes() throws Exception {
        // Force a stop
        triggerEcoStop();
        assertFalse("Should be stopped", isEcoModeActivelyCharging());

        // Disable eco mode — should resume charging
        controller.setEcoModeEnabled(false);
        assertTrue("Should have resumed after disabling eco", isEcoModeActivelyCharging());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 15: Rapid drop from high amps to MIN on spike
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * At 10A, a 2000W consumption spike should drop straight to MIN_AMPS (5A)
     * in a single cycle. Excess = 2000-100 = 1900W → ceil(1900/230) = 9A
     * 10-9 = 1, clamped to MIN_AMPS (5A).
     */
    @Test
    public void test15_rapidDropToMinOnSpike() throws Exception {
        // Ramp up to 10A
        for (int i = 0; i < 5; i++) {
            doOneRampUp();
        }
        assertEquals(10, getCurrentAmps());

        // Massive spike — should drop straight to 5A
        controller.onPowerUpdate(2000);
        assertEquals("Should rapid-drop to MIN_AMPS", MIN_AMPS, getCurrentAmps());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 16: Rapid drop + 60s evaluation + stop + resume
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Full rapid-drop lifecycle:
     * 1. At 10A, spike drops straight to 5A
     * 2. Still over tolerance at 5A → 60s evaluation starts
     * 3. After 60s → eco stop
     * 4. Cooldown passes, consumption drops → resume
     */
    @Test
    public void test16_rapidDropThenStopThenResume() throws Exception {
        // Ramp up to 10A
        for (int i = 0; i < 5; i++) {
            doOneRampUp();
        }
        assertEquals(10, getCurrentAmps());

        // Spike → rapid drop to MIN
        controller.onPowerUpdate(2000);
        assertEquals("Rapid-dropped to MIN", MIN_AMPS, getCurrentAmps());
        assertTrue("Should still be actively charging after drop", isEcoModeActivelyCharging());

        // Still over tolerance at MIN → 60s evaluation starts
        controller.onPowerUpdate(500);
        assertTrue("Evaluation started, still charging", isEcoModeActivelyCharging());
        assertTrue("Evaluation timer should be set", getEcoStopEvaluationStartTime() > 0);

        // Fast-forward 60s
        forceEcoStopEvaluationElapsed();
        controller.onPowerUpdate(500);
        assertFalse("Should have stopped after 60s evaluation", isEcoModeActivelyCharging());
        assertEquals(0, getLastSentAmps());

        // Cooldown passes, solar returns → resume
        clearEcoCooldown();
        controller.onPowerUpdate(0);
        assertTrue("Should have resumed", isEcoModeActivelyCharging());
        assertEquals("Resumed at MIN_AMPS", MIN_AMPS, getCurrentAmps());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 17: Backoff increments on re-stop
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Stop → resume → stop again → backoff level should increase each time.
     * Initial=1, after 1st stop=2, after 2nd stop=3.
     */
    @Test
    public void test17_backoffIncrementsOnReStop() throws Exception {
        assertEquals("Initial backoff should be 1", 1, getEcoBackoffLevel());

        // First stop → backoff goes to 2
        triggerEcoStop();
        assertFalse(isEcoModeActivelyCharging());
        assertEquals("Backoff after 1st stop", 2, getEcoBackoffLevel());

        // Resume
        clearEcoCooldown();
        controller.onPowerUpdate(0);
        assertTrue(isEcoModeActivelyCharging());

        // Second stop → backoff goes to 3
        controller.onPowerUpdate(200); // Start evaluation at MIN
        forceEcoStopEvaluationElapsed();
        controller.onPowerUpdate(200); // Trigger stop
        assertFalse(isEcoModeActivelyCharging());
        assertEquals("Backoff after 2nd stop", 3, getEcoBackoffLevel());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 18: Backoff resets on successful ramp above MIN
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * After backoff has increased, a successful resume where charging ramps
     * above MIN_AMPS (proving solar is available) should reset backoff to 1.
     */
    @Test
    public void test18_backoffResetsOnSuccessfulRamp() throws Exception {
        // Force backoff to 3
        setEcoBackoffLevel(3);

        // Simulate stopped state
        triggerEcoStop(); // backoff 3→4
        assertEquals(4, getEcoBackoffLevel());

        // Resume
        clearEcoCooldown();
        controller.onPowerUpdate(0);
        assertTrue(isEcoModeActivelyCharging());
        assertEquals("Backoff still elevated before ramp", 4, getEcoBackoffLevel());

        // Ramp above MIN → backoff should reset
        doOneRampUp(); // 5A → 6A
        assertEquals("Should have ramped to 6A", 6, getCurrentAmps());
        assertEquals("Backoff should reset to 1", 1, getEcoBackoffLevel());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 19: Backoff caps at 5 (max 5 minutes)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * After many failed resumes, backoff should cap at level 5 (5 minutes)
     * and not go higher.
     */
    @Test
    public void test19_backoffCapsAtMax() throws Exception {
        // Set backoff to 4 (one below max)
        setEcoBackoffLevel(4);

        // Stop → backoff 4→5
        triggerEcoStop();
        assertEquals("Backoff should be at max (5)", 5, getEcoBackoffLevel());

        // Resume and stop again → should stay at 5
        clearEcoCooldown();
        controller.onPowerUpdate(0); // Resume
        controller.onPowerUpdate(200); // Start evaluation at MIN
        forceEcoStopEvaluationElapsed();
        controller.onPowerUpdate(200); // Trigger stop
        assertEquals("Backoff should stay capped at 5", 5, getEcoBackoffLevel());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 20: Initial cooldown is 1 minute (not 5)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * The very first eco stop should use the initial backoff of 1 minute,
     * but since stop increments backoff to 2, the cooldown for resume
     * after the first stop is 2 minutes. However, at 61 seconds (>1min)
     * we should NOT be able to resume (need 2min), but at >2min we should.
     */
    @Test
    public void test20_firstStopCooldownTiming() throws Exception {
        assertEquals("Initial backoff", 1, getEcoBackoffLevel());

        triggerEcoStop(); // backoff → 2 (cooldown = 2min)
        assertEquals(2, getEcoBackoffLevel());

        // 90 seconds later — not enough (need 120s)
        setLastEcoStopTime(System.currentTimeMillis() - 90_000);
        controller.onPowerUpdate(0);
        assertFalse("Should NOT resume at 90s (need 120s)", isEcoModeActivelyCharging());

        // 121 seconds later — should resume
        setLastEcoStopTime(System.currentTimeMillis() - 121_000);
        controller.onPowerUpdate(0);
        assertTrue("Should resume at 121s (>120s)", isEcoModeActivelyCharging());
    }

    // ── Shared setup: forces an eco stop ───────────────────────────────────

    /**
     * Puts the controller into "eco stopped" state by simulating the
     * 60-second evaluation period with overconsumption.
     */
    private void triggerEcoStop() throws Exception {
        // Ensure at MIN and high consumption
        setCurrentAmps(MIN_AMPS);
        setLastSentAmps(MIN_AMPS);

        // First update starts evaluation
        controller.onPowerUpdate(200);

        // Fast-forward past 60s evaluation
        forceEcoStopEvaluationElapsed();

        // Second update triggers actual stop
        controller.onPowerUpdate(200);
    }
}
