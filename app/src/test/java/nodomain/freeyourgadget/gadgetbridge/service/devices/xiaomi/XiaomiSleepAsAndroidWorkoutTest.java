/*  Copyright (C) 2026 Dany Mestas

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi;

import android.os.Looper;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.robolectric.Shadows;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services.XiaomiHealthService;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

/**
 * The band side of a Sleep as Android session: a synthetic workout carries the raw sensor stream,
 * so it has to be opened, kept alive, rebuilt when the band goes silent and closed cleanly, all
 * without disturbing the realtime stream its other consumers share or the real workout logic.
 */
public class XiaomiSleepAsAndroidWorkoutTest extends TestBase {

    private static final int COMMAND_TYPE = 8;
    private static final int CMD_WORKOUT_WATCH_STATUS = 26;
    private static final int CMD_WORKOUT_WATCH_OPEN = 30;
    private static final int CMD_REALTIME_STATS_START = 45;
    private static final int CMD_REALTIME_STATS_STOP = 46;
    private static final int CMD_WORKOUT_STATS_PHONE = 49;
    private static final int CMD_RAW_SENSOR_BATCH = 53;

    private static final int WORKOUT_STARTED = 0;
    private static final int WORKOUT_PAUSED = 1;
    private static final int WORKOUT_FINISHED = 3;

    private static final int SAA_SYNTHETIC_SPORT = 810;
    private static final int SPORT_OUTDOOR_RUNNING = 1;

    private static final long OPEN_DELAY_MS = 500L;
    private static final long KEEPALIVE_MS = 24_000L;
    private static final long FULL_INTERVAL_MS = 1_000L;
    private static final long IDLE_INTERVAL_MS = 5_000L;
    private static final long ACTIVE_WINDOW_MS = 10_000L;

    private XiaomiSupport support;
    private XiaomiHealthService health;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        support = Mockito.mock(XiaomiSupport.class);
        Mockito.when(support.getDevice()).thenReturn(createDummyGDevice("00:11:22:33:44:55"));
        health = new XiaomiHealthService(support);
    }

    // --- driving ----------------------------------------------------------------------------

    private void idle(final long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    /** Opens the synthetic workout and leaves every command it sent in the history. */
    private void openSession(final boolean withHeartRate) {
        health.startRawSensor(withHeartRate);
        idle(OPEN_DELAY_MS);
    }

    /** Opens the synthetic workout, then forgets the commands the open itself sent. */
    private void openSessionAndClearHistory(final boolean withHeartRate) {
        openSession(withHeartRate);
        Mockito.clearInvocations(support);
    }

    /** Pretend the band delivered a batch of accelerometer samples. */
    private void deliverRawBatch() {
        health.handleCommand(XiaomiProto.Command.newBuilder()
                .setType(COMMAND_TYPE)
                .setSubtype(CMD_RAW_SENSOR_BATCH)
                .setHealth(XiaomiProto.Health.newBuilder().setRawSensorBatch(
                        XiaomiProto.RawSensorBatch.newBuilder()
                                .addAccel(XiaomiProto.AxisSensor.newBuilder()
                                        .setTimestamp(1L).setX(0f).setY(0f).setZ(9.81f))))
                .build());
    }

    private void deliverWorkoutOpen(final int sport) {
        health.handleCommand(XiaomiProto.Command.newBuilder()
                .setType(COMMAND_TYPE)
                .setSubtype(CMD_WORKOUT_WATCH_OPEN)
                .setHealth(XiaomiProto.Health.newBuilder().setWorkoutOpenWatch(
                        XiaomiProto.WorkoutOpenWatch.newBuilder()
                                .setSport(sport)
                                .setSupportedVersions(2)
                                .setMainSport(sport)))
                .build());
    }

    private void deliverWorkoutStatus(final int sport, final int status) {
        health.handleCommand(XiaomiProto.Command.newBuilder()
                .setType(COMMAND_TYPE)
                .setSubtype(CMD_WORKOUT_WATCH_STATUS)
                .setHealth(XiaomiProto.Health.newBuilder().setWorkoutStatusWatch(
                        XiaomiProto.WorkoutStatusWatch.newBuilder()
                                .setSport(sport)
                                .setStatus(status)))
                .build());
    }

    // --- observing --------------------------------------------------------------------------

    private List<XiaomiProto.Command> sent() {
        final ArgumentCaptor<XiaomiProto.Command> captor = ArgumentCaptor.forClass(XiaomiProto.Command.class);
        Mockito.verify(support, Mockito.atLeast(0)).sendCommand(Mockito.anyString(), captor.capture());
        return captor.getAllValues();
    }

    private List<XiaomiProto.Command> sentOfSubtype(final int subtype) {
        final List<XiaomiProto.Command> matching = new ArrayList<>();
        for (final XiaomiProto.Command command : sent()) {
            if (command.getSubtype() == subtype) {
                matching.add(command);
            }
        }
        return matching;
    }

    private int count(final int subtype) {
        return sentOfSubtype(subtype).size();
    }

    private List<Integer> workoutStatuses() {
        final List<Integer> statuses = new ArrayList<>();
        for (final XiaomiProto.Command command : sentOfSubtype(CMD_WORKOUT_WATCH_STATUS)) {
            statuses.add(command.getHealth().getWorkoutStatusWatch().getStatus());
        }
        return statuses;
    }

    /** A rebuild goes through a full close, so a finish in the history means the workout restarted. */
    private boolean restarted() {
        return workoutStatuses().contains(WORKOUT_FINISHED);
    }

    /** How many stats packets go out over the next {@code millis} of a running session. */
    private int statsSentOver(final long millis) {
        final int before = count(CMD_WORKOUT_STATS_PHONE);
        idle(millis);
        return count(CMD_WORKOUT_STATS_PHONE) - before;
    }

    // --- realtime stream sharing ------------------------------------------------------------

    @Test
    public void firstConsumerStartsTheStream() {
        health.enableRealtimeStats(true);

        Assert.assertEquals(1, count(CMD_REALTIME_STATS_START));
        Assert.assertEquals(0, count(CMD_REALTIME_STATS_STOP));
    }

    @Test
    public void repeatedEnableDoesNotRestart() {
        health.enableRealtimeStats(true);
        health.enableRealtimeStats(true);

        Assert.assertEquals(1, count(CMD_REALTIME_STATS_START));
    }

    @Test
    public void lastConsumerStopsTheStream() {
        health.enableRealtimeStats(true);
        health.enableRealtimeStats(false);

        Assert.assertEquals(1, count(CMD_REALTIME_STATS_START));
        Assert.assertEquals(1, count(CMD_REALTIME_STATS_STOP));
    }

    @Test
    public void sleepTrackingKeepsTheStreamWhenChartsClose() {
        // Sleep as Android session running, then the user opens and closes the live chart.
        openSession(true);
        final int startsAfterSession = count(CMD_REALTIME_STATS_START);

        health.enableRealtimeStats(true);
        health.enableRealtimeStats(false);

        Assert.assertEquals("chart should not have restarted the stream",
                startsAfterSession, count(CMD_REALTIME_STATS_START));
        Assert.assertEquals("closing the chart must not stop Sleep as Android's stream",
                0, count(CMD_REALTIME_STATS_STOP));
    }

    // --- session sequencing -------------------------------------------------------------------

    @Test
    public void startClosesAnyWorkoutLeftOpenBeforeOpeningANewOne() {
        health.startRawSensor(true);

        // Immediately: only the defensive finish, so a session left open by an earlier process is
        // closed before the band is asked to open another.
        List<XiaomiProto.Command> statuses = sentOfSubtype(CMD_WORKOUT_WATCH_STATUS);
        Assert.assertEquals(1, statuses.size());
        Assert.assertEquals(WORKOUT_FINISHED, statuses.get(0).getHealth().getWorkoutStatusWatch().getStatus());

        idle(OPEN_DELAY_MS);

        statuses = sentOfSubtype(CMD_WORKOUT_WATCH_STATUS);
        Assert.assertEquals(2, statuses.size());
        Assert.assertEquals(WORKOUT_STARTED, statuses.get(1).getHealth().getWorkoutStatusWatch().getStatus());
    }

    @Test
    public void stopPausesBeforeFinishing() {
        openSessionAndClearHistory(true);

        health.stopRawSensor();

        // The finish must not follow the pause immediately, or the band keeps the workout open.
        List<XiaomiProto.Command> statuses = sentOfSubtype(CMD_WORKOUT_WATCH_STATUS);
        Assert.assertEquals(1, statuses.size());
        Assert.assertEquals(WORKOUT_PAUSED, statuses.get(0).getHealth().getWorkoutStatusWatch().getStatus());

        idle(OPEN_DELAY_MS);

        statuses = sentOfSubtype(CMD_WORKOUT_WATCH_STATUS);
        Assert.assertEquals(2, statuses.size());
        Assert.assertEquals(WORKOUT_FINISHED, statuses.get(1).getHealth().getWorkoutStatusWatch().getStatus());
    }

    @Test
    public void keepaliveRepeatsTheStartStatus() {
        openSessionAndClearHistory(true);

        idle(KEEPALIVE_MS);

        final List<XiaomiProto.Command> statuses = sentOfSubtype(CMD_WORKOUT_WATCH_STATUS);
        Assert.assertEquals(1, statuses.size());
        Assert.assertEquals(WORKOUT_STARTED, statuses.get(0).getHealth().getWorkoutStatusWatch().getStatus());
    }

    @Test
    public void disposeSilencesEveryTimer() {
        openSession(true);
        health.dispose();
        Mockito.clearInvocations(support);

        idle(60_000);

        Assert.assertTrue(sent().isEmpty());
    }

    @Test
    public void timezoneOffsetIsSentInQuarterHours() {
        final TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kathmandu")); // UTC+05:45
            health.startRawSensor(true);

            final XiaomiProto.Command status = sentOfSubtype(CMD_WORKOUT_WATCH_STATUS).get(0);
            Assert.assertEquals(23, status.getHealth().getWorkoutStatusWatch().getSportInfo().getTzOffsetQuarterHours());
        } finally {
            TimeZone.setDefault(original);
        }
    }

    // --- stats pacing ---------------------------------------------------------------------------

    @Test
    public void theFirstSecondsRunAtTheFullRate() {
        openSession(true);

        Assert.assertEquals(5, statsSentOver(5 * FULL_INTERVAL_MS));
    }

    @Test
    public void theRestOfTheSessionRunsAtTheIdleRate() {
        // The band renders these on its workout screen, which is off for almost all of a night.
        openSession(true);
        idle(ACTIVE_WINDOW_MS);

        Assert.assertEquals(6, statsSentOver(6 * IDLE_INTERVAL_MS));
    }

    @Test
    public void nothingIsSentAfterTheSessionStops() {
        openSession(true);
        idle(30_000);

        health.stopRawSensor();

        Assert.assertEquals(0, statsSentOver(Duration.ofMinutes(5).toMillis()));
    }

    // --- stall detection --------------------------------------------------------------------

    @Test
    public void steadyBatchesKeepTheSessionAlive() {
        openSessionAndClearHistory(true);

        // Batches every 10 s, comfortably inside the stall window, across three keepalive ticks.
        for (int i = 0; i < 8; i++) {
            idle(10_000);
            deliverRawBatch();
        }

        Assert.assertFalse("a healthy session must never be rebuilt", restarted());
    }

    @Test
    public void silenceRebuildsTheWorkout() {
        openSessionAndClearHistory(true);

        // First tick at 24 s still counts as healthy; the second finds 48 s of silence.
        idle(50_000);

        Assert.assertTrue("the session must rebuild itself after a stall", restarted());
    }

    @Test
    public void restartKeepsTheHeartRateRequest() {
        // Sleep as Android asked for movement only, so the rebuild must not quietly power the
        // heart rate sensor up.
        openSessionAndClearHistory(false);

        idle(50_000);
        idle(OPEN_DELAY_MS);

        Assert.assertTrue(restarted());
        Assert.assertTrue("a rebuild must not enable a sensor that was not requested",
                sentOfSubtype(CMD_REALTIME_STATS_START).isEmpty());
    }

    @Test
    public void aBandThatNeverAnswersIsGivenUpOn() {
        openSessionAndClearHistory(true);

        // Ten minutes of complete silence, far more than the restarts are allowed to cover.
        idle(10 * 60_000);
        idle(OPEN_DELAY_MS);
        Mockito.clearInvocations(support);

        idle(5 * KEEPALIVE_MS);

        Assert.assertTrue("a band that answers nothing must stop being talked to",
                workoutStatuses().isEmpty());
    }

    @Test
    public void noRestartAfterTheSessionIsStopped() {
        openSession(true);
        health.stopRawSensor();
        idle(OPEN_DELAY_MS);
        Mockito.clearInvocations(support);

        idle(5 * KEEPALIVE_MS);

        Assert.assertTrue("a stopped session must stay stopped", workoutStatuses().isEmpty());
    }

    // --- handoff with the real workout logic --------------------------------------------------

    @Test
    public void syntheticOpenIsAcknowledgedWithoutGps() {
        openSessionAndClearHistory(true);

        deliverWorkoutOpen(SAA_SYNTHETIC_SPORT);

        final List<XiaomiProto.Command> replies = sentOfSubtype(CMD_WORKOUT_WATCH_OPEN);
        Assert.assertEquals(1, replies.size());

        final XiaomiProto.WorkoutOpenReply reply = replies.get(0).getHealth().getWorkoutOpenReply();
        // The band only starts streaming raw accelerometer data once it gets this exact reply.
        Assert.assertEquals(0, reply.getCode());
        Assert.assertEquals(2, reply.getSelectedVersion());
        Assert.assertEquals(2, reply.getGpsAccuracy());
    }

    @Test
    public void syntheticStatusIsIgnoredEntirely() {
        openSessionAndClearHistory(true);

        deliverWorkoutStatus(SAA_SYNTHETIC_SPORT, WORKOUT_STARTED);
        deliverWorkoutStatus(SAA_SYNTHETIC_SPORT, WORKOUT_FINISHED);

        Assert.assertTrue("the synthetic workout must not be answered", sentOfSubtype(CMD_WORKOUT_WATCH_STATUS).isEmpty());
        // Reaching the real workout logic would require the device to read its preferences.
        Mockito.verify(support, Mockito.never()).getDevice();
    }

    @Test
    public void realWorkoutStatusStillReachesTheWorkoutLogic() {
        deliverWorkoutStatus(SPORT_OUTDOOR_RUNNING, WORKOUT_STARTED);

        // It read the device preferences, so it did not take the synthetic short circuit.
        Mockito.verify(support, Mockito.atLeastOnce()).getDevice();
    }

    @Test
    public void realWorkoutIsUnaffectedAfterASyntheticSessionEnds() {
        openSession(true);
        health.stopRawSensor();
        idle(OPEN_DELAY_MS);
        Mockito.clearInvocations(support);

        deliverWorkoutOpen(SPORT_OUTDOOR_RUNNING);

        final List<XiaomiProto.Command> replies = sentOfSubtype(CMD_WORKOUT_WATCH_OPEN);
        Assert.assertEquals(1, replies.size());
        // With phone GPS off this is the "no location" reply, not the synthetic (0, 2, 2).
        Assert.assertNotEquals("the synthetic ack must not leak into a real workout",
                0, replies.get(0).getHealth().getWorkoutOpenReply().getCode());
    }
}
