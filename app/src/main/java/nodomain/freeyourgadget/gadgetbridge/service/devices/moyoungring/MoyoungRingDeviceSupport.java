/*  Copyright (C) 2026 The Gadgetbridge Project

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.moyoungring;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import net.e175.klaus.solarpositioning.DeltaT;
import net.e175.klaus.solarpositioning.SPA;
import net.e175.klaus.solarpositioning.SunriseTransitSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericBloodPressureSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHeartRateSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHrvValueSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSleepStageSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSpo2SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericStressSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.MoyoungSleepStageSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.moyoung.MoyoungConstants;
import nodomain.freeyourgadget.gadgetbridge.devices.moyoung.samples.MoyoungActivitySampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.moyoungring.MoyoungRingConstants;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummaryDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericBloodPressureSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHrvValueSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSleepStageSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSpo2Sample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericStressSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericTemperatureSample;
import nodomain.freeyourgadget.gadgetbridge.entities.MoyoungActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.MoyoungSleepStageSample;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericTemperatureSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.model.TemperatureSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService;
import nodomain.freeyourgadget.gadgetbridge.model.RecordedDataTypes;
import nodomain.freeyourgadget.gadgetbridge.model.derivedmetrics.DerivedHealthMetrics;
import nodomain.freeyourgadget.gadgetbridge.model.derivedmetrics.SleepSession;
import nodomain.freeyourgadget.gadgetbridge.model.derivedmetrics.SleepStage;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattCharacteristic;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;

/**
 * Gadgetbridge driver for the MoYoung / CRRepa "Da Ring" optical smart ring
 * (advertised "VRing", model "R26", firmware "MOY-R263-2.2.2").
 *
 * <p>Fully wires HR, SpO2, steps
 * and sleep history sync, plus HRV / stress history and live BP. Data is
 * persisted through Gadgetbridge's generic sample providers so it exports to
 * Health Connect and renders full graphs.
 *
 * <p><b>Read-only-ring policy (CRITICAL):</b> this driver NEVER sends any
 * delete/clear opcode — there is none in the command set and it must stay that
 * way. On-ring history is preserved for other companion apps. To avoid
 * re-persisting the same records on every reconnect, a per-metric client-side
 * high-water-mark (HWM) is tracked in the device-scoped SharedPreferences with
 * a 5-minute future-clock-skew tolerance (see {@link #getHwm}).
 */
public class MoyoungRingDeviceSupport extends AbstractBTLESingleDeviceSupport {

    private static final Logger LOG = LoggerFactory.getLogger(MoyoungRingDeviceSupport.class);

    /** Flush the real-time HR buffer every N samples. */
    private static final int HR_BUFFER_FLUSH_THRESHOLD = 5;
    /** Force-flush the HR buffer at least this often even if the threshold isn't reached. */
    private static final long HR_BUFFER_FLUSH_INTERVAL_MS = 30_000L;

    private final MoyoungRingPacket.Reassembler reassembler = new MoyoungRingPacket.Reassembler();

    /** Per-metric paged daily-timeline reassemblers. */
    private final MoyoungRingPacket.TimingReassembler hrTiming =
            new MoyoungRingPacket.TimingReassembler(
                    MoyoungRingConstants.TIMING_LAST_PAGE_HR, MoyoungRingPacket.SlotFormat.U8);
    private final MoyoungRingPacket.TimingReassembler spo2Timing =
            new MoyoungRingPacket.TimingReassembler(
                    MoyoungRingConstants.TIMING_LAST_PAGE_SPO2, MoyoungRingPacket.SlotFormat.U8);
    private final MoyoungRingPacket.TimingReassembler hrvTiming =
            new MoyoungRingPacket.TimingReassembler(
                    MoyoungRingConstants.TIMING_LAST_PAGE_HRV, MoyoungRingPacket.SlotFormat.U16LE);
    private final MoyoungRingPacket.TimingReassembler stressTiming =
            new MoyoungRingPacket.TimingReassembler(
                    MoyoungRingConstants.TIMING_LAST_PAGE_STRESS, MoyoungRingPacket.SlotFormat.U8);

    private final List<GenericHeartRateSample> hrBuffer = new ArrayList<>();
    private final Object hrBufferLock = new Object();
    private long hrBufferLastFlushMs = 0L;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // ---- Paced "sync dispatcher": send read requests ONE AT A TIME, waiting for
    // each response (or a timeout) before dispatching the next. The ring drops
    // multi-frame paged-timeline requests when they arrive in a dense burst, so
    // history/timeline reads MUST be serialized (proven by a read tool that spaced
    // requests ~1.6s apart and successfully pulled every timeline). ----
    private final java.util.ArrayDeque<byte[]> syncQueue = new java.util.ArrayDeque<>();
    private volatile boolean syncInFlight = false;
    /**
     * Monotonic generation for the in-flight sync step. Each dispatched request bumps
     * it and its timeout is keyed to that generation, so a LATE response or a stale
     * timeout for an already-advanced step can never advance the pacer twice (which
     * would put two requests in flight and re-create the burst that drops timelines).
     */
    private int syncGen = 0;
    /** Combined opcode the in-flight sync step expects back; only this advances the pacer. */
    private volatile int syncExpectedOpcode = -1;
    /**
     * For paged/daily requests whose CONSECUTIVE requests share one opcode (timeline
     * pages, and steps/histogram/sleep across days), also correlate the reply's day and
     * (timeline only) page, so a LATE reply for an already-timed-out step can never
     * advance the next same-opcode step. -1 = don't-care (spot LISTs have no day/page).
     */
    private volatile int syncExpectedDay = -1;
    private volatile int syncExpectedPage = -1;
    /** Timing opcodes that have yielded >=1 slot this connection; never skip these on a timeout. */
    private final java.util.Set<Integer> timingMetricsWithData = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final long SYNC_STEP_TIMEOUT_MS = 10000L; // real latency is 2.3–6.7s; paged is slower
    private static final long SYNC_GAP_MS = 200L;            // small gap between steps
    /** Largest paged reply observed is 190 bytes; +3 ATT header => need MTU >= 193 to receive it. */
    private static final int MIN_USABLE_MTU = 193;
    /** If onMtuChanged never fires, start the deferred history sync anyway after this long. */
    private static final long MTU_GATE_FALLBACK_MS = 2500L;
    /** History frames built at init, held until the MTU is negotiated (see startInitialSyncIfPending). */
    private java.util.List<byte[]> pendingInitialSyncFrames = null;
    private boolean initialSyncStarted = false;
    /**
     * BLE/hardware efficiency: SpO2 (2/17) and stress (2/47) timelines are never logged by
     * this ring's firmware — their requests just burn a 10s timeout each sync. After this
     * many consecutive dead syncs (no data ever), we PERSIST that they're unsupported and
     * stop both re-requesting the timeline AND enabling their background auto-monitor
     * (which would otherwise power the PPG for data we can't read). Re-probed on fw change.
     */
    private static final int UNSUPPORTED_TIMELINE_THRESHOLD = 2;
    /** Last-seen firmware, used to invalidate learned capability flags on a fw update. */
    private volatile String lastFirmware = null;

    private void enqueueSync(java.util.List<byte[]> frames) {
        if (frames == null || frames.isEmpty()) return;
        synchronized (syncQueue) { syncQueue.addAll(frames); }
        handler.post(this::maybeSendNextSync);
    }

    /** Enqueue a follow-up (next timeline page) at the FRONT so it stays with its metric-day. */
    private void enqueueSyncFront(byte[] frame) {
        synchronized (syncQueue) { syncQueue.addFirst(frame); }
    }

    private void maybeSendNextSync() {
        if (syncInFlight) return;
        if (getDevice() == null || !getDevice().isConnected()) { synchronized (syncQueue) { syncQueue.clear(); } return; }
        byte[] frame;
        synchronized (syncQueue) { frame = syncQueue.poll(); }
        if (frame == null) return;
        syncInFlight = true;
        syncGen++;
        final int gen = syncGen;
        // Frame layout: [0..3]=header, [4]=cmd, [5]=sub. Correlate the expected reply.
        syncExpectedOpcode = MoyoungRingConstants.op(frame[4] & 0xFF, frame[5] & 0xFF);
        // day = byte 6 (paged/daily requests); page = byte 7 (8-byte timeline requests).
        syncExpectedDay = frame.length >= 7 ? (frame[6] & 0xFF) : -1;
        syncExpectedPage = frame.length >= 8 ? (frame[7] & 0xFF) : -1;
        try {
            TransactionBuilder b = createTransactionBuilder("MoyoungRing sync step");
            writeCommand(b, frame);
            b.queue();
        } catch (Exception e) {
            // A local send failure must not strand the pacer for a full 10s: release the
            // in-flight latch and advance to the next step promptly.
            LOG.warn("MoyoungRing sync step failed", e);
            syncInFlight = false;
            syncExpectedOpcode = -1; syncExpectedDay = -1; syncExpectedPage = -1;
            handler.postDelayed(this::maybeSendNextSync, SYNC_GAP_MS);
            return;
        }
        handler.postDelayed(() -> onSyncTimeout(gen), SYNC_STEP_TIMEOUT_MS);
    }

    /**
     * A response arrived. Only the CURRENT in-flight request's expected opcode advances
     * the pacer; late/duplicate/wrong-opcode responses are ignored here (they may still
     * be parsed/persisted by their handler, they just don't advance the queue).
     */
    private void syncResponseArrived(int opcode) { syncResponseArrived(opcode, -1, -1); }

    /**
     * A response arrived — called from the BLE callback thread. Marshal onto the single
     * dispatcher thread (the main-looper handler) so that ALL pacer state
     * (syncGen/syncInFlight/syncExpected*) is read and written from exactly ONE thread.
     * This removes the cross-thread race with {@link #onSyncTimeout}/{@link #maybeSendNextSync}
     * that could otherwise double-advance the queue (two requests in flight -> burst ->
     * dropped timelines) or mis-handle a response that lands right on the timeout boundary.
     * day/page (when >=0) additionally correlate paged/daily replies.
     */
    private void syncResponseArrived(int opcode, int day, int page) {
        handler.post(() -> onSyncResponse(opcode, day, page));
    }

    /** Handler-thread only: advance the pacer iff this reply matches the in-flight step. */
    private void onSyncResponse(int opcode, int day, int page) {
        if (!syncInFlight || opcode != syncExpectedOpcode) return;
        if (syncExpectedDay >= 0 && day >= 0 && day != syncExpectedDay) return;     // stale/mismatched day
        if (syncExpectedPage >= 0 && page >= 0 && page != syncExpectedPage) return; // stale/mismatched page
        syncGen++; // invalidate this step's pending gen-keyed timeout (cancel)
        syncExpectedOpcode = -1;
        syncExpectedDay = -1;
        syncExpectedPage = -1;
        syncInFlight = false;
        handler.postDelayed(this::maybeSendNextSync, SYNC_GAP_MS);
    }

    /** The in-flight step timed out (response lost). Advance only if still the current gen. */
    private void onSyncTimeout(int gen) {
        if (gen != syncGen) return; // stale: response already advanced, or reset happened
        LOG.debug("MoyoungRing sync step timeout (op={}), advancing", syncExpectedOpcode);
        // A dropped paged-timeline request may leave a half-assembled page; reset that
        // metric's reassembler so a stale partial can't corrupt the next day's timeline.
        final int op = syncExpectedOpcode;
        if (isTimingOpcode(op)) {
            resetTimingReassembler(op);
            // OPTIMIZATION: a TIMED-OUT timeline means the ring didn't answer this metric.
            // If it has NEVER produced data this connection, it isn't logging that metric,
            // so requesting the remaining days would just burn another full timeout each —
            // drop this metric's still-queued days. But if the metric HAS produced data,
            // treat the timeout as a transient hiccup and keep its other days queued (so a
            // one-off drop can't lose a working metric's history).
            if (!timingMetricsWithData.contains(op)) {
                markTimelineDead(op);   // persist across syncs so we stop probing dead metrics
                int dropped = purgeSyncQueueForOpcode(op);
                if (dropped > 0) {
                    LOG.info("MoyoungRing timeline {} never answered; skipped its {} remaining day(s)", op, dropped);
                }
            }
        }
        syncExpectedOpcode = -1;
        syncExpectedDay = -1;
        syncExpectedPage = -1;
        syncInFlight = false;
        handler.postDelayed(this::maybeSendNextSync, SYNC_GAP_MS);
    }

    private static boolean isTimingOpcode(int op) {
        return op == MoyoungRingConstants.OP_TIMING_HR
                || op == MoyoungRingConstants.OP_TIMING_SPO2
                || op == MoyoungRingConstants.OP_TIMING_HRV
                || op == MoyoungRingConstants.OP_TIMING_STRESS
                || op == MoyoungRingConstants.OP_TIMING_TEMP;
    }

    private void resetTimingReassembler(int op) {
        if (op == MoyoungRingConstants.OP_TIMING_HR) hrTiming.reset();
        else if (op == MoyoungRingConstants.OP_TIMING_SPO2) spo2Timing.reset();
        else if (op == MoyoungRingConstants.OP_TIMING_HRV) hrvTiming.reset();
        else if (op == MoyoungRingConstants.OP_TIMING_STRESS) stressTiming.reset();
        // OP_TIMING_TEMP has no reassembler (persisted purely per-page); no-op.
    }

    /** Remove every still-queued sync request whose (cmd,sub) opcode equals {@code op}. */
    private int purgeSyncQueueForOpcode(int op) {
        int removed = 0;
        synchronized (syncQueue) {
            java.util.Iterator<byte[]> it = syncQueue.iterator();
            while (it.hasNext()) {
                byte[] f = it.next();
                if (f.length >= 6 && MoyoungRingConstants.op(f[4] & 0xFF, f[5] & 0xFF) == op) {
                    it.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    /**
     * Debounce for the derived "Daily Health Insights" recompute. History
     * responses stream in asynchronously with no explicit "fetch complete"
     * callback, so we coalesce all of them: every history frame (re)schedules a
     * single recompute this many ms after the stream goes quiet.
     */
    private static final long INSIGHTS_DEBOUNCE_MS = 4_000L;
    /** Recompute today + this many prior local days (covers the timing sync window). */
    private static final int INSIGHTS_DAYS_BACK = MoyoungRingConstants.TIMING_DAYS_BACK + 1;
    /** Trailing window (days) for personal baselines. */
    private static final int INSIGHTS_BASELINE_DAYS = 7;
    /** Minimum prior days with data before daily-baseline comparisons are shown. */
    private static final int INSIGHTS_MIN_BASELINE_DAYS = 3;

    private final Runnable insightsRunnable = this::computeDailyInsights;

    /** Anchor from the most recent sleep-details frame, used to place stage records. */
    private long lastSleepStartMs = 0L;
    private long lastSleepEndMs = 0L;

    /**
     * Latest per-day daily-step totals from cmd 2/13 (StepsInfo), keyed by the local
     * start-of-day epoch millis of that day. Used by {@link #handleStepsHistogram}
     * to pro-rate the ring's real distance/calories across the per-30-min step slots
     * so the Activity chart matches the Da Rings app. Small (a few recent days).
     */
    private final Map<Long, MoyoungRingPacket.StepsInfo> stepsTotalsByDayStart =
            new ConcurrentHashMap<>();

    private final Runnable stopManualHeartRateRunnable = () -> {
        try {
            TransactionBuilder b = createTransactionBuilder("MoyoungRing manual HR stop");
            writeCommand(b, MoyoungRingPacket.liveMeasure(
                    MoyoungRingConstants.CMD_LIVE_HR, MoyoungRingConstants.SUB_LIVE_HR, false));
            b.queue();
        } catch (Exception e) {
            LOG.warn("MoyoungRing manual HR stop failed", e);
        }
    };

    // ---- Live-reporting ("mode 2") state: continuous HR + step streaming during
    // an activity, driven by Gadgetbridge's Live Activity screen (start/stop). ----
    private volatile boolean realtimeHrEnabled = false;
    private volatile boolean realtimeStepsEnabled = false;
    /** Whether the ring's realtime push (cmd 9) is currently on for THIS connection. */
    private volatile boolean realtimePushActive = false;
    /** Serializes the realtime push on/off decision (touched from binder + main + BLE threads). */
    private final Object realtimeLock = new Object();
    /** Cumulative-daily step total from the previous live packet, for delta emission (-1 = re-baseline). */
    private volatile int lastRealtimeSteps = -1;
    /**
     * Last time the Live Activity UI asked us to keep measuring. The UI re-invokes
     * onEnableRealtimeHeartRateMeasurement(true) on every pulse (~1 s), so a stale
     * timestamp means the screen was closed/backgrounded/killed WITHOUT a clean
     * disable — a safety net so we never keep the sensor running (battery) forever.
     */
    private volatile long lastRealtimeRequestMs = 0L;
    private static final long REALTIME_INACTIVITY_TIMEOUT_MS = 30_000L;
    /** The ring only streams live HR for a bounded window per trigger, so re-arm it. */
    private static final long REALTIME_HR_KEEPALIVE_MS = 20_000L;
    /** Grace period for transient Live Activity off->on pulses before tearing down cmd 9 push. */
    private static final long REALTIME_PUSH_STOP_DEBOUNCE_MS = 8_000L;
    private final Runnable stopRealtimePushRunnable = this::stopRealtimePushIfStillIdle;

    private final Runnable realtimeHrKeepAlive = new Runnable() {
        @Override public void run() {
            if (!realtimeHrEnabled) return;
            // If the UI stopped requesting live data (screen gone, no clean disable),
            // auto-stop so we don't keep the optical sensor + BLE busy indefinitely.
            if (System.currentTimeMillis() - lastRealtimeRequestMs > REALTIME_INACTIVITY_TIMEOUT_MS) {
                LOG.info("MoyoungRing realtime HR: no UI heartbeat for >{}ms, auto-stopping",
                        REALTIME_INACTIVITY_TIMEOUT_MS);
                onEnableRealtimeHeartRateMeasurement(false);
                onEnableRealtimeSteps(false);
                return;
            }
            // Only queue a re-trigger while connected (avoids BLE-queue buildup during
            // a drop); the write resumes after reconnect via initializeDevice().
            if (getDevice() != null && getDevice().isConnected()) {
                try {
                    TransactionBuilder b = createTransactionBuilder("MoyoungRing realtime HR keepalive");
                    writeCommand(b, MoyoungRingPacket.liveMeasure(
                            MoyoungRingConstants.CMD_LIVE_HR, MoyoungRingConstants.SUB_LIVE_HR, true));
                    b.queue();
                } catch (Exception e) {
                    LOG.debug("MoyoungRing realtime HR keepalive failed", e);
                }
            }
            handler.postDelayed(this, REALTIME_HR_KEEPALIVE_MS);
        }
    };

    private static final int[][] SPOT_MEASURE_ROTATION = new int[][] {
            { MoyoungRingConstants.CMD_LIVE_SPO2, MoyoungRingConstants.SUB_LIVE_SPO2 },
            { MoyoungRingConstants.CMD_LIVE_HRV, MoyoungRingConstants.SUB_LIVE_HRV },
            { MoyoungRingConstants.CMD_LIVE_STRESS, MoyoungRingConstants.SUB_LIVE_STRESS },
            { MoyoungRingConstants.CMD_LIVE_BP, MoyoungRingConstants.SUB_LIVE_BP },
            { MoyoungRingConstants.CMD_LIVE_TEMP, MoyoungRingConstants.SUB_LIVE_TEMP },
    };
    private int spotMeasurementIndex = 0;
    /**
     * The rotation currently being executed by {@link #serialSpotMeasurementRunnable}.
     * A full awake poll uses {@link #SPOT_MEASURE_ROTATION} (all metrics); a tab-refresh
     * uses a single-metric array. One-shot: the rotation runs once and stops (no repeat).
     */
    private int[][] activeSpotRotation = SPOT_MEASURE_ROTATION;
    /** Retry budget while the shared PPG is busy (live HR / in-flight sync). */
    private int spotBusyRetries = 0;
    private static final int MAX_SPOT_BUSY_RETRIES = 8; // ~4 min of 30s retries
    /** Wall-clock of the last rotation start, for manual-request debounce. */
    private long lastSpotStartMs = 0L;

    // Awake-gated auto-poll bookkeeping (persisted, survives reconnect/restart).
    private static final String PREF_AUTOPOLL_DAY     = "moyoungring_autopoll_day";
    private static final String PREF_AUTOPOLL_COUNT   = "moyoungring_autopoll_count";
    private static final String PREF_AUTOPOLL_LAST_MS = "moyoungring_autopoll_last_ms";

    /**
     * Master enable flag for the spot-measurement state machine, checked at the top of
     * the serial runnable so that a callback which was already dequeued (and thus can't be
     * cancelled by {@code removeCallbacks}) becomes a harmless no-op once polling has been
     * stopped (disconnect/dispose/live-HR). Volatile: toggled from start/cancel which may
     * run on the BLE callback thread, read on the main looper.
     */
    private volatile boolean spotPollingEnabled = false;

    /**
     * ONE-SHOT serialized spot-measurement runner. Fires each metric in
     * {@link #activeSpotRotation} exactly once, {@link MoyoungRingConstants#SPOT_MEASURE_GAP_MS}
     * apart, then STOPS. There is no self-rescheduling repeat: continuous background polling
     * is what prevented the ring from recording sleep, so rotations are now started only by
     * {@link #maybeAutoPoll} (awake, daytime, ≤N/day) and by explicit refresh requests.
     */
    private final Runnable serialSpotMeasurementRunnable = new Runnable() {
        @Override public void run() {
            if (!spotPollingEnabled || getDevice() == null || !getDevice().isConnected()) {
                return;
            }
            // AUTHORITATIVE SLEEP GUARD: re-check the clock before EVERY metric. This aborts
            // any rotation that crosses into the night window — whether it started just
            // before the cutoff, spent time on busy-retries, or was scheduled by an external
            // (Intent API) single-type fetch. Live-HR streaming is a separate path and is not
            // affected by this runnable.
            if (isNightNow()) {
                spotPollingEnabled = false;
                return;
            }
            final int[][] rotation = activeSpotRotation;
            if (spotMeasurementIndex >= rotation.length) {
                spotPollingEnabled = false; // rotation complete — one-shot, no repeat
                return;
            }
            final boolean syncBusy;
            synchronized (syncQueue) { syncBusy = syncInFlight || !syncQueue.isEmpty(); }
            if (syncBusy || realtimeHrEnabled) {
                // A live-HR stream or an in-flight history sync would abort this spot on the
                // shared PPG. RETRY THE CURRENT METRIC (preserve spotMeasurementIndex), but
                // give up after a bounded budget so a persistent live stream can't leave the
                // state machine spinning forever.
                if (++spotBusyRetries > MAX_SPOT_BUSY_RETRIES) {
                    spotPollingEnabled = false;
                    return;
                }
                handler.removeCallbacks(this);
                handler.postDelayed(this, 30_000L);
                return;
            }
            spotBusyRetries = 0;
            final int[] measurement = rotation[spotMeasurementIndex++];
            writeSpotMeasurement(measurement[0], measurement[1]);
            if (spotMeasurementIndex < rotation.length) {
                handler.postDelayed(this, MoyoungRingConstants.SPOT_MEASURE_GAP_MS);
            } else {
                // Last metric fired; nothing more to schedule. Clear the flag immediately
                // (a delayed clear could otherwise fire during a later rotation and abort it).
                spotPollingEnabled = false;
            }
        }
    };

    public MoyoungRingDeviceSupport() {
        super(LOG);
        addSupportedService(MoyoungRingConstants.UUID_SERVICE);
        addSupportedService(GattService.UUID_SERVICE_HEART_RATE);
        addSupportedService(GattService.UUID_SERVICE_BATTERY_SERVICE);
    }

    @Override
    public boolean useAutoConnect() {
        return false;
    }

    @Override
    public boolean getImplicitCallbackModify() {
        return true;
    }

    @Override
    protected TransactionBuilder initializeDevice(final TransactionBuilder builder) {
        LOG.debug("MoyoungRing initializeDevice");
        builder.setDeviceState(GBDevice.State.INITIALIZING);

        // CRITICAL: negotiate a large ATT MTU FIRST. The ring's paged history/timeline
        // responses are 100-190 bytes each (HR/HRV/SpO2/temp pages, steps histogram,
        // sleep). A BLE notification can carry at most MTU-3 bytes, so at the default
        // MTU of 23 the ring simply never emits those large frames -> every timeline
        // request times out and all daily graphs stay empty (only the tiny <=17-byte
        // spot-LIST responses get through). A live capture of the official app shows it
        // negotiates a large MTU and then receives 152/188-byte notifications, and the
        // sibling MoYoung watch driver does the same. Request 247 (>= the largest
        // observed 190-byte payload) before subscribing/sending anything.
        builder.requestMtu(247);

        reassembler.reset();
        hrTiming.reset();
        spo2Timing.reset();
        hrvTiming.reset();
        stressTiming.reset();
        // This support instance is reused across a link drop; wipe any sync state left
        // over from the previous connection so a stale in-flight step / timeout / queued
        // frame can't leak into (or double-advance) the new connection's paced sync.
        synchronized (syncQueue) { syncQueue.clear(); }
        syncInFlight = false;
        syncExpectedOpcode = -1;
        syncGen++; // invalidate any pending gen-keyed timeout from the prior connection
        timingMetricsWithData.clear();
        // Drop any stale sleep-details anchor so per-day 2/14 frames anchor cleanly.
        lastSleepStartMs = 0L;
        lastSleepEndMs = 0L;

        // Subscribe: framed responses (fdd3), live steps (fdd1), standard HR (2A37),
        // battery level (2A19).
        builder.notify(MoyoungRingConstants.UUID_CHAR_RESPONSE, true);
        builder.notify(MoyoungRingConstants.UUID_CHAR_STEPS, true);
        builder.notify(GattCharacteristic.UUID_CHARACTERISTIC_HEART_RATE_MEASUREMENT, true);
        builder.notify(GattCharacteristic.UUID_CHARACTERISTIC_BATTERY_LEVEL, true);

        // Read battery once up-front.
        builder.read(GattCharacteristic.UUID_CHARACTERISTIC_BATTERY_LEVEL);

        // Mirror the official app init order, but do ALL read-only history/timeline
        // reads BEFORE any enable/config writes (defense-in-depth). set-time MUST be
        // first: it is the prerequisite for the ring to log into its per-day
        // timelines. None of these send a delete/clear/reset (there is none here).
        writeCommand(builder, buildSetTime());
        writeCommand(builder, MoyoungRingPacket.encode(
                MoyoungRingConstants.CMD_DEVICE_INFO, MoyoungRingConstants.SUB_DEVICE_INFO));
        writeCommand(builder, MoyoungRingPacket.encode(
                MoyoungRingConstants.CMD_FIRMWARE, MoyoungRingConstants.SUB_FIRMWARE));

        // NOTE: history/timeline reads are NOT queued into this builder anymore.
        // They are dispatched one-at-a-time through the paced sync dispatcher after
        // init (see below), because the ring drops paged-timeline requests that
        // arrive in a dense burst.

        // Set the user profile and turn ON automatic background monitoring on EVERY
        // connect. A live capture proved this is non-destructive: the official app
        // re-sends enable HR (1/6) and HRV (1/7) each connect and the ring RETAINS its
        // accumulated timeline slots. Re-sending unconditionally (no once-ever guard)
        // means logging can never silently stop after a factory-reset/unbind.
        queueEnableMonitoring(builder);

        // SpO2, stress, blood pressure and skin temperature are SPOT measurements that
        // activate the ring's single PPG sensor (~37-45s each). Running them at night would
        // prevent the ring from recording sleep, so we NO LONGER poll continuously. Instead
        // we register the screen-unlock ("awake") signal and let maybeAutoPoll() run at most
        // a couple of daytime rounds/day (first only after sunrise). Explicit refresh / live
        // mode can still measure on demand. Verified rotation values: SpO2 96%, Stress 38,
        // BP 111/69, Temp 35.9C; off-finger sentinels (0xFF/0xFFFF) are filtered.
        registerUnlockReceiver();
        handler.postDelayed(() -> maybeAutoPoll("connect"), 20_000L);
        handler.postDelayed(() -> maybeAutoPoll("connect-late"), 180_000L);

        builder.setDeviceState(GBDevice.State.INITIALIZED);

        // Kick off the paced read sync for a SHORT window (today + yesterday) so connects
        // stay fast. It is DEFERRED until the ATT MTU is negotiated (see onMtuChanged):
        // the ring only emits its 100-190 byte paged history/timeline frames over a large
        // MTU, so starting sync before MTU is raised would just time everything out. A
        // fallback timer starts it best-effort if the MTU callback never arrives.
        initialSyncStarted = false;
        pendingInitialSyncFrames = buildHistoryRequestFrames(2);
        handler.postDelayed(this::startInitialSyncIfPending, MTU_GATE_FALLBACK_MS);

        // Reconnect handling: this support instance can be reused across a link
        // drop (canReconnect), so the ring's realtime push does not survive. Reset
        // the per-connection push flag + step baseline, and if the Live Activity
        // screen is still requesting live data, re-establish streaming after init.
        handler.removeCallbacks(stopRealtimePushRunnable);
        realtimePushActive = false;
        lastRealtimeSteps = -1;
        if (realtimeHrEnabled || realtimeStepsEnabled) {
            handler.post(() -> {
                updateRealtimePush();
                if (realtimeHrEnabled) {
                    try {
                        TransactionBuilder b = createTransactionBuilder("MoyoungRing realtime HR re-apply");
                        writeCommand(b, MoyoungRingPacket.liveMeasure(
                                MoyoungRingConstants.CMD_LIVE_HR, MoyoungRingConstants.SUB_LIVE_HR, true));
                        b.queue();
                    } catch (Exception e) {
                        LOG.warn("MoyoungRing realtime HR re-apply failed", e);
                    }
                    handler.removeCallbacks(realtimeHrKeepAlive);
                    handler.postDelayed(realtimeHrKeepAlive, REALTIME_HR_KEEPALIVE_MS);
                }
            });
        }
        return builder;
    }

    /**
     * Start ONE spot-measurement rotation (all metrics or a single-metric subset) and stop
     * when it finishes. Never starts while a live-HR stream owns the shared PPG.
     */
    private void startSpotRotationOnce(final int[][] rotation) {
        if (getDevice() == null || !getDevice().isConnected() || realtimeHrEnabled) {
            return;
        }
        activeSpotRotation = (rotation != null && rotation.length > 0) ? rotation : SPOT_MEASURE_ROTATION;
        spotMeasurementIndex = 0;
        spotBusyRetries = 0;
        spotPollingEnabled = true;
        lastSpotStartMs = System.currentTimeMillis();
        handler.removeCallbacks(serialSpotMeasurementRunnable);
        handler.postDelayed(serialSpotMeasurementRunnable, 1_500L);
    }

    private void cancelSpotMeasurements() {
        spotPollingEnabled = false;
        handler.removeCallbacks(serialSpotMeasurementRunnable);
        spotMeasurementIndex = 0;
    }

    /**
     * Awake-gated automatic spot poll. Runs a full rotation only when: connected, not
     * streaming live HR, within the daytime window (never at night, so the ring is free to
     * record sleep), the FIRST poll of the day only after the local sunrise, at most
     * {@link MoyoungRingConstants#AUTO_POLL_MAX_PER_DAY} rounds/day spaced by
     * {@link MoyoungRingConstants#AUTO_POLL_MIN_INTERVAL_MS}. Triggered on screen unlock,
     * on connect, when live HR stops, and by the "sync all" fetch button / auto-fetch.
     */
    private void maybeAutoPoll(final String trigger) {
        try {
            if (getDevice() == null || !getDevice().isConnected() || realtimeHrEnabled) {
                return;
            }
            final long now = System.currentTimeMillis();
            final Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(now);
            final int hour = cal.get(Calendar.HOUR_OF_DAY);
            // NIGHT PROTECTION: no automatic PPG polling late evening / pre-dawn.
            if (isNightNow()) {
                return;
            }
            final int dayKey = localDayKey(now);
            final android.content.SharedPreferences prefs = getDevicePrefs().getPreferences();
            final int savedDay = prefs.getInt(PREF_AUTOPOLL_DAY, -1);
            int count = (savedDay == dayKey) ? prefs.getInt(PREF_AUTOPOLL_COUNT, 0) : 0;
            final long lastMs = prefs.getLong(PREF_AUTOPOLL_LAST_MS, 0L);

            if (count >= MoyoungRingConstants.AUTO_POLL_MAX_PER_DAY) {
                return;
            }
            if (count == 0) {
                // First poll of the day: only after the local sunrise.
                if (now < todaySunriseMillis(now)) {
                    return;
                }
            } else if (now - lastMs < MoyoungRingConstants.AUTO_POLL_MIN_INTERVAL_MS) {
                return; // space the daily polls apart
            }

            prefs.edit()
                    .putInt(PREF_AUTOPOLL_DAY, dayKey)
                    .putInt(PREF_AUTOPOLL_COUNT, count + 1)
                    .putLong(PREF_AUTOPOLL_LAST_MS, now)
                    .apply();
            LOG.info("MoyoungRing auto-poll #{}/{} ({}) hour={} — starting full spot rotation",
                    count + 1, MoyoungRingConstants.AUTO_POLL_MAX_PER_DAY, trigger, hour);
            startSpotRotationOnce(SPOT_MEASURE_ROTATION);
        } catch (Exception e) {
            LOG.warn("MoyoungRing maybeAutoPoll failed", e);
        }
    }

    /**
     * Fire a fresh spot for a specific metric on explicit user request (a per-tab refresh).
     * Debounced, and NIGHT-GATED: although this is normally a foreground tab refresh, the
     * generic {@code onFetchRecordedData} entry point can also be driven by the Intent API /
     * automation with a single data type, so we must not let it activate the PPG at night and
     * disturb sleep. Daytime requests still yield to the shared PPG (the serial runner waits
     * out any live HR / in-flight sync). A deliberate night reading is still possible via
     * live mode (the device-card heart button).
     */
    private void requestManualSpots(final int[][] rotation) {
        if (rotation == null || rotation.length == 0) {
            return;
        }
        if (isNightNow()) {
            LOG.info("MoyoungRing per-metric refresh suppressed at night (sleep protection); "
                    + "use live mode for an on-demand night reading");
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - lastSpotStartMs < MoyoungRingConstants.MANUAL_SPOT_MIN_SPACING_MS) {
            return; // dedupe rapid taps / duplicate fetches
        }
        // Let the just-enqueued history sync go first; the serial runner also self-defers.
        handler.postDelayed(() -> startSpotRotationOnce(rotation), 1_500L);
    }

    /**
     * True during the sleep-protection window (at/after the night cutoff hour, or before
     * 04:00). Automatic AND refresh-driven spot measurements are suppressed while true, so
     * the ring's PPG is free to record sleep. Only user-initiated live mode may run then.
     */
    private static boolean isNightNow() {
        final int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hour >= MoyoungRingConstants.AUTO_POLL_NIGHT_CUTOFF_HOUR || hour < 4;
    }

    /** Days since the Unix epoch in the LOCAL timezone (calendar-day key). */
    private static int localDayKey(final long ms) {
        final TimeZone tz = TimeZone.getDefault();
        return (int) ((ms + tz.getOffset(ms)) / 86_400_000L);
    }

    /**
     * Epoch millis of today's local sunrise from the Gadgetbridge-configured location.
     * Falls back to {@link MoyoungRingConstants#SUNRISE_FALLBACK_HOUR} if no location is set
     * or the sun does not rise (polar day/night).
     */
    private long todaySunriseMillis(final long now) {
        try {
            final GBPrefs gbPrefs = GBApplication.getPrefs();
            final float[] longlat = gbPrefs.getLongLat(getContext());
            final float lon = longlat[0];
            final float lat = longlat[1];
            if (lat == 0f && lon == 0f) {
                return fallbackSunriseMillis(now);
            }
            final java.util.GregorianCalendar cal = new java.util.GregorianCalendar();
            cal.setTimeInMillis(now);
            final java.time.ZonedDateTime zdt = cal.toZonedDateTime();
            final SunriseTransitSet s = SPA.calculateSunriseTransitSet(
                    zdt, lat, lon, DeltaT.estimate(zdt.toLocalDate()));
            if (s.getSunrise() != null) {
                return s.getSunrise().toInstant().toEpochMilli();
            }
            return fallbackSunriseMillis(now);
        } catch (Exception e) {
            return fallbackSunriseMillis(now);
        }
    }

    private static long fallbackSunriseMillis(final long now) {
        final Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, MoyoungRingConstants.SUNRISE_FALLBACK_HOUR);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    // Screen-unlock (ACTION_USER_PRESENT) is our "user is awake" signal: the first unlock
    // after sunrise triggers the day's first poll; later unlocks top it up (bounded).
    private android.content.BroadcastReceiver unlockReceiver;
    private boolean unlockReceiverRegistered = false;

    private void registerUnlockReceiver() {
        if (unlockReceiverRegistered) {
            return;
        }
        try {
            unlockReceiver = new android.content.BroadcastReceiver() {
                @Override public void onReceive(final android.content.Context c, final Intent i) {
                    handler.post(() -> maybeAutoPoll("unlock"));
                }
            };
            ContextCompat.registerReceiver(getContext(), unlockReceiver,
                    new android.content.IntentFilter(Intent.ACTION_USER_PRESENT),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            unlockReceiverRegistered = true;
        } catch (Exception e) {
            LOG.warn("MoyoungRing unlock receiver register failed", e);
        }
    }

    private void unregisterUnlockReceiver() {
        if (!unlockReceiverRegistered) {
            return;
        }
        try {
            getContext().unregisterReceiver(unlockReceiver);
        } catch (Exception e) {
            LOG.debug("MoyoungRing unlock receiver already unregistered");
        }
        unlockReceiverRegistered = false;
        unlockReceiver = null;
    }

    /**
     * Fire one on-demand spot measurement. The serial scheduler is responsible for spacing
     * calls far enough apart for the ring's single sensor to finish each async result.
     */
    private void writeSpotMeasurement(final int cmd, final int sub) {
        if (getDevice() == null || !getDevice().isConnected()) {
            return;
        }
        try {
            TransactionBuilder b = createTransactionBuilder("MoyoungRing spot measure");
            writeCommand(b, MoyoungRingPacket.encode(cmd, sub,
                    new byte[]{ MoyoungRingConstants.MEASURE_START }));
            b.queue();
        } catch (Exception e) {
            LOG.warn("MoyoungRing spot measurement {}/{} failed", cmd, sub, e);
        }
    }

    /**
     * Start the deferred initial history sync EXACTLY ONCE — invoked either from
     * {@link #onMtuChanged} (preferred: MTU is known) or from the fallback timer armed in
     * {@link #initializeDevice} (in case the MTU callback never fires). Synchronized so the
     * two callers (BLE thread vs main looper) can't both start it.
     */
    private synchronized void startInitialSyncIfPending() {
        if (initialSyncStarted || pendingInitialSyncFrames == null) {
            return;
        }
        initialSyncStarted = true;
        final java.util.List<byte[]> frames = pendingInitialSyncFrames;
        pendingInitialSyncFrames = null;
        LOG.info("MoyoungRing starting history sync (MTU={})", getMTU());
        enqueueSync(frames);
    }

    @Override
    public void onMtuChanged(final BluetoothGatt gatt, final int mtu, final int status) {
        super.onMtuChanged(gatt, mtu, status);
        LOG.info("MoyoungRing MTU negotiated: mtu={} status={}", mtu, status);
        if (status == BluetoothGatt.GATT_SUCCESS && mtu < MIN_USABLE_MTU) {
            LOG.warn("MoyoungRing negotiated MTU {} < {} — the ring's 100-190 byte paged "
                    + "history/timeline frames may not fit, so daily graphs could stay sparse.",
                    mtu, MIN_USABLE_MTU);
        }
        // MTU is now settled (success or failure): release the deferred history sync. Even
        // on a small/failed MTU we still try — spot data always works and the warning above
        // explains any sparse timeline result.
        startInitialSyncIfPending();
    }

    /**
     * Set the user profile and enable automatic/timed background monitoring so the
     * ring logs a history series (HR/SpO2/HRV/stress/temp) that populates the charts.
     * Sending these is safe (settings only; no deletes).
     */
    private void queueEnableMonitoring(TransactionBuilder builder) {
        try {
            final ActivityUser user = new ActivityUser();
            writeCommand(builder, MoyoungRingPacket.setUserInfo(
                    user.getHeightCm(), user.getWeightKg(), user.getAge(),
                    user.getGender(), user.getStepLengthCm()));
        } catch (Exception e) {
            LOG.warn("MoyoungRing: could not set user info", e);
        }
        writeCommand(builder, MoyoungRingPacket.enableTiming(
                MoyoungRingConstants.SUB_ENABLE_HR, MoyoungRingConstants.TIMING_INTERVAL_HR_MIN));
        // SpO2 (1/8) and stress (1/39) background auto-monitoring: only keep enabling these
        // while their timeline is still believed readable. Once learned unsupported (this
        // ring's fw never logs SpO2/stress timelines), STOP enabling them — otherwise we'd
        // power the PPG for background samples we can never read back as history. The spot
        // rotation (1/11/1/14) is what actually populates those charts. HR/HRV/temp stay on
        // (their timelines ARE readable).
        if (!isTimelineUnsupported(MoyoungRingConstants.OP_TIMING_SPO2)) {
            writeCommand(builder, MoyoungRingPacket.enableTiming(
                    MoyoungRingConstants.SUB_ENABLE_SPO2, MoyoungRingConstants.TIMING_INTERVAL_OTHER_MIN));
        }
        writeCommand(builder, MoyoungRingPacket.enableTiming(
                MoyoungRingConstants.SUB_ENABLE_HRV, MoyoungRingConstants.TIMING_INTERVAL_OTHER_MIN));
        if (!isTimelineUnsupported(MoyoungRingConstants.OP_TIMING_STRESS)) {
            writeCommand(builder, MoyoungRingPacket.enableTiming(
                    MoyoungRingConstants.SUB_ENABLE_STRESS, MoyoungRingConstants.TIMING_INTERVAL_OTHER_MIN));
        }
        writeCommand(builder, MoyoungRingPacket.enableTimingFlag(
                MoyoungRingConstants.SUB_ENABLE_TEMP, true)); // temp = on/off flag
    }

    /**
     * Build the incremental history/timeline read frames for {@code daysBack} days
     * (clamped to {@link MoyoungRingConstants#TIMING_DAYS_BACK}). Already-loaded,
     * immutable data is NEVER re-requested — at the finest granularity the protocol
     * allows: PER PAGE for timelines (only the first page still needing data; the
     * handler chains forward), PER DAY for sleep/steps (a past day is requested only
     * if not already marked synced; today is always requested). The cheap LIST reads
     * (latest spot values) are always included. Dispatched ONE AT A TIME by the pacer.
     */
    private java.util.List<byte[]> buildHistoryRequestFrames(int daysBack) {
        if (daysBack > MoyoungRingConstants.TIMING_DAYS_BACK) {
            daysBack = MoyoungRingConstants.TIMING_DAYS_BACK;
        }
        if (daysBack < 1) daysBack = 1;
        final long now = System.currentTimeMillis();
        final TimeZone tz = TimeZone.getDefault();
        final java.util.List<byte[]> f = new java.util.ArrayList<>();
        // Latest-spot LIST reads (single-frame, cheap) — always fetched.
        f.add(MoyoungRingPacket.encode(
                MoyoungRingConstants.CMD_HIST_HR, MoyoungRingConstants.SUB_HIST_HR));
        f.add(MoyoungRingPacket.encode(
                MoyoungRingConstants.CMD_HIST_SPO2, MoyoungRingConstants.SUB_HIST_SPO2));
        f.add(MoyoungRingPacket.encode(
                MoyoungRingConstants.CMD_HIST_HRV, MoyoungRingConstants.SUB_HIST_HRV));
        f.add(MoyoungRingPacket.encode(
                MoyoungRingConstants.CMD_HIST_STRESS, MoyoungRingConstants.SUB_HIST_STRESS));
        appendSleepFrames(f, daysBack, now, tz);
        appendStepsFrames(f, daysBack, now, tz);
        appendTimelineFrames(f, MoyoungRingConstants.OP_TIMING_HR,
                MoyoungRingConstants.CMD_TIMING_HR, MoyoungRingConstants.SUB_TIMING_HR, daysBack, now, tz);
        appendTimelineFrames(f, MoyoungRingConstants.OP_TIMING_SPO2,
                MoyoungRingConstants.CMD_TIMING_SPO2, MoyoungRingConstants.SUB_TIMING_SPO2, daysBack, now, tz);
        appendTimelineFrames(f, MoyoungRingConstants.OP_TIMING_HRV,
                MoyoungRingConstants.CMD_TIMING_HRV, MoyoungRingConstants.SUB_TIMING_HRV, daysBack, now, tz);
        appendTimelineFrames(f, MoyoungRingConstants.OP_TIMING_STRESS,
                MoyoungRingConstants.CMD_TIMING_STRESS, MoyoungRingConstants.SUB_TIMING_STRESS, daysBack, now, tz);
        appendTimelineFrames(f, MoyoungRingConstants.OP_TIMING_TEMP,
                MoyoungRingConstants.CMD_TIMING_TEMP, MoyoungRingConstants.SUB_TIMING_TEMP, daysBack, now, tz);
        return f;
    }

    /** Append the first still-needed page of {@code op}'s timeline for each day (incremental). */
    private void appendTimelineFrames(java.util.List<byte[]> f, int op, int cmd, int sub,
                                      int daysBack, long now, TimeZone tz) {
        // BLE/battery win: once a metric's timeline is learned unsupported (fw never logs
        // it, e.g. SpO2/stress on this ring), stop issuing its requests entirely — they'd
        // only burn a 10s timeout each. Re-probed automatically on a firmware change.
        if (isTimelineUnsupported(op)) {
            return;
        }
        for (int day = 0; day < daysBack; day++) {
            final long dayStart = MoyoungRingPacket.startOfDayMillis(now, day, tz);
            final int p = firstNeededPage(op, dayStart, now);
            if (p >= 0) f.add(MoyoungRingPacket.timingRequest(cmd, sub, day, p));
        }
    }

    /** Append per-day sleep STAGE (2/14) requests; skip PAST days already synced. */
    private void appendSleepFrames(java.util.List<byte[]> f, int daysBack, long now, TimeZone tz) {
        for (int day = 0; day < daysBack; day++) {
            final long dayStart = MoyoungRingPacket.startOfDayMillis(now, day, tz);
            if (day == 0 || !isDaySynced("sleep", dayStart)) {
                f.add(MoyoungRingPacket.encode(
                        MoyoungRingConstants.CMD_HIST_SLEEP_DAY, MoyoungRingConstants.SUB_HIST_SLEEP_DAY,
                        new byte[]{ (byte) day }));
            }
        }
    }

    /** Append per-day steps total (2/13) + histogram (2/18); skip PAST days already synced. */
    private void appendStepsFrames(java.util.List<byte[]> f, int daysBack, long now, TimeZone tz) {
        for (int day = 0; day < daysBack; day++) {
            final long dayStart = MoyoungRingPacket.startOfDayMillis(now, day, tz);
            if (day == 0 || !isDaySynced("steps", dayStart)) {
                f.add(MoyoungRingPacket.historySteps(day));
                f.add(MoyoungRingPacket.historyStepsDetails(day));
            }
        }
    }

    /**
     * Build the set-clock frame (cmd 1/1). Delegates to
     * {@link MoyoungRingPacket#setTime(long, TimeZone)} which emits the verified
     * wire format — a 4-byte LE Unix epoch plus a 1-byte timezone-hours offset.
     * This MUST be a correctly formatted clock: it is the prerequisite for the
     * ring to log measurements into its per-day timelines (empty charts otherwise).
     */
    private static byte[] buildSetTime() {
        return MoyoungRingPacket.setTime(System.currentTimeMillis(), TimeZone.getDefault());
    }

    private void writeCommand(TransactionBuilder builder, byte[] frame) {
        BluetoothGattCharacteristic cmdChar = getCharacteristic(MoyoungRingConstants.UUID_CHAR_COMMAND);
        if (cmdChar != null) {
            cmdChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            builder.write(cmdChar, frame);
        } else {
            builder.write(MoyoungRingConstants.UUID_CHAR_COMMAND, frame);
        }
    }

    @Override
    public boolean onCharacteristicChanged(BluetoothGatt gatt,
                                           BluetoothGattCharacteristic ch,
                                           byte[] data) {
        if (super.onCharacteristicChanged(gatt, ch, data)) {
            return true;
        }
        final UUID uuid = ch.getUuid();
        if (data == null || data.length == 0) {
            return true;
        }

        if (MoyoungRingConstants.UUID_CHAR_RESPONSE.equals(uuid)) {
            for (MoyoungRingPacket pkt : reassembler.add(data)) {
                handlePacket(pkt);
            }
            return true;
        }
        if (MoyoungRingConstants.UUID_CHAR_STEPS.equals(uuid)) {
            handleLiveSteps(data);
            return true;
        }
        if (GattCharacteristic.UUID_CHARACTERISTIC_HEART_RATE_MEASUREMENT.equals(uuid)) {
            handleStandardHeartRate(data);
            return true;
        }
        if (GattCharacteristic.UUID_CHARACTERISTIC_BATTERY_LEVEL.equals(uuid)) {
            handleBattery(data);
            return true;
        }
        return false;
    }

    @Override
    public boolean onCharacteristicRead(BluetoothGatt gatt,
                                        BluetoothGattCharacteristic ch,
                                        byte[] data,
                                        int status) {
        if (super.onCharacteristicRead(gatt, ch, data, status)) {
            return true;
        }
        if (GattCharacteristic.UUID_CHARACTERISTIC_BATTERY_LEVEL.equals(ch.getUuid())) {
            handleBattery(data);
            return true;
        }
        return false;
    }

    // -------- Dispatch --------

    private void handlePacket(MoyoungRingPacket pkt) {
        final byte[] p = pkt.payload;
        switch (pkt.opcode()) {
            case MoyoungRingConstants.OP_DEVICE_INFO: {
                MoyoungRingPacket.DeviceInfo info = MoyoungRingPacket.parseDeviceInfo(p);
                if (info != null) {
                    LOG.info("MoyoungRing device info: model={} name={}", info.model, info.name);
                }
                break;
            }
            case MoyoungRingConstants.OP_FIRMWARE: {
                String fw = MoyoungRingPacket.parseFirmware(p);
                if (fw != null && !fw.isEmpty()) {
                    GBDeviceEventVersionInfo ev = new GBDeviceEventVersionInfo();
                    ev.fwVersion = fw;
                    evaluateGBDeviceEvent(ev);
                    LOG.info("MoyoungRing fw version: {}", fw);
                    invalidateCapabilitiesIfFirmwareChanged(fw);
                }
                break;
            }
            case MoyoungRingConstants.OP_LIVE_HR: {
                int hr = MoyoungRingPacket.parseRealtimeScalar(p);
                if (hr >= 0) persistHr(System.currentTimeMillis(), hr);
                break;
            }
            case MoyoungRingConstants.OP_LIVE_SPO2: {
                int spo2 = MoyoungRingPacket.parseRealtimeScalar(p);
                if (spo2 >= 0) persistSpo2(System.currentTimeMillis(), spo2);
                break;
            }
            case MoyoungRingConstants.OP_LIVE_BP: {
                // Ring returns {systolic, diastolic} (2 bytes; off-finger sentinel is
                // 0xFF/0xFF). The MoYoung *watch* variant prefixes a status byte ->
                // {status, systolic, diastolic}, so both layouts are handled. Verified
                // live on a worn ring (e.g. 111/69); sentinel and physiologically
                // implausible readings are dropped rather than exported.
                int sys = -1, dia = -1;
                if (p.length == 2) {
                    sys = p[0] & 0xFF; dia = p[1] & 0xFF;
                } else if (p.length >= 3) {
                    sys = p[1] & 0xFF; dia = p[2] & 0xFF;
                }
                if (isPlausibleBloodPressure(sys, dia)) {
                    persistBp(System.currentTimeMillis(), sys, dia, 0);
                }
                break;
            }
            case MoyoungRingConstants.OP_LIVE_HRV: {
                int hrv = MoyoungRingPacket.parseRealtimeU16Le(p);
                if (hrv >= 0) persistHrv(System.currentTimeMillis(), hrv);
                break;
            }
            case MoyoungRingConstants.OP_LIVE_STRESS: {
                int stress = MoyoungRingPacket.parseRealtimeScalar(p);
                if (stress >= 0) persistStress(System.currentTimeMillis(), stress);
                break;
            }
            case MoyoungRingConstants.OP_LIVE_TEMP: {
                // Skin temperature: u16 LE value = deci-degrees Celsius (verified
                // live: 0x0166 = 358 -> 35.8 °C). Persist as a skin-temp sample.
                int raw = MoyoungRingPacket.parseRealtimeU16Le(p);
                if (raw >= 0) persistSkinTemp(System.currentTimeMillis(), raw / 10.0);
                break;
            }
            case MoyoungRingConstants.OP_HIST_HR:
                handleHrHistory(p);
                syncResponseArrived(pkt.opcode());
                break;
            case MoyoungRingConstants.OP_HIST_SPO2:
                handleSpo2History(p);
                syncResponseArrived(pkt.opcode());
                break;
            case MoyoungRingConstants.OP_HIST_HRV:
                handleHrvHistory(p);
                syncResponseArrived(pkt.opcode());
                break;
            case MoyoungRingConstants.OP_HIST_STRESS:
                handleStressHistory(p);
                syncResponseArrived(pkt.opcode());
                break;
            case MoyoungRingConstants.OP_HIST_SLEEP:
            case MoyoungRingConstants.OP_HIST_SLEEP_DAY:
                handleSleepHistory(p, pkt.opcode());
                syncResponseArrived(pkt.opcode(), p.length > 0 ? (p[0] & 0xFF) : -1, -1);
                break;
            case MoyoungRingConstants.OP_HIST_STEPS:
                handleStepsReply(p);
                syncResponseArrived(pkt.opcode(), p.length > 0 ? (p[0] & 0xFF) : -1, -1);
                break;
            case MoyoungRingConstants.OP_HIST_STEPS_DETAIL:
                handleStepsHistogram(p);
                syncResponseArrived(pkt.opcode(), p.length > 0 ? (p[0] & 0xFF) : -1, -1);
                break;
            case MoyoungRingConstants.OP_TIMING_HR:
                handleTimingHr(p);
                break;
            case MoyoungRingConstants.OP_TIMING_SPO2:
                handleTimingSpo2(p);
                break;
            case MoyoungRingConstants.OP_TIMING_HRV:
                handleTimingHrv(p);
                break;
            case MoyoungRingConstants.OP_TIMING_STRESS:
                handleTimingStress(p);
                break;
            case MoyoungRingConstants.OP_TIMING_TEMP:
                handleTimingTemp(p);
                break;
            default:
                LOG.debug("MoyoungRing unhandled frame cmd={}/{} payload={}",
                        pkt.cmd, pkt.sub, MoyoungRingPacket.hex(p));
        }
        // Any history/timing frame may have added samples for a day; coalesce a
        // single derived-insights recompute once the response stream goes quiet.
        if (isHistoryOpcode(pkt.opcode())) {
            scheduleInsights();
        }
    }

    /** True for the history/timing opcodes that persist per-day samples. */
    private static boolean isHistoryOpcode(final int opcode) {
        return opcode == MoyoungRingConstants.OP_HIST_HR
                || opcode == MoyoungRingConstants.OP_HIST_SPO2
                || opcode == MoyoungRingConstants.OP_HIST_HRV
                || opcode == MoyoungRingConstants.OP_HIST_STRESS
                || opcode == MoyoungRingConstants.OP_HIST_SLEEP
                || opcode == MoyoungRingConstants.OP_HIST_SLEEP_DAY
                || opcode == MoyoungRingConstants.OP_HIST_STEPS
                || opcode == MoyoungRingConstants.OP_HIST_STEPS_DETAIL
                || opcode == MoyoungRingConstants.OP_TIMING_HR
                || opcode == MoyoungRingConstants.OP_TIMING_SPO2
                || opcode == MoyoungRingConstants.OP_TIMING_HRV
                || opcode == MoyoungRingConstants.OP_TIMING_STRESS
                || opcode == MoyoungRingConstants.OP_TIMING_TEMP;
    }

    /** Debounced trigger: recompute the derived daily insights once history is quiet. */
    private void scheduleInsights() {
        handler.removeCallbacks(insightsRunnable);
        handler.postDelayed(insightsRunnable, INSIGHTS_DEBOUNCE_MS);
    }

    private void handleStandardHeartRate(byte[] data) {
        if (data == null || data.length < 2) {
            return;
        }
        int flags = data[0] & 0xFF;
        if ((flags & 1) != 0 && data.length < 3) {
            return;
        }
        int bpm = (flags & 1) == 0
                ? data[1] & 0xFF
                : (data[1] & 0xFF) | ((data[2] & 0xFF) << 8);
        // Sensor contact supported but not detected -> stale value.
        if ((flags & 0x06) == 0x04) return;
        persistHr(System.currentTimeMillis(), bpm);
    }

    private void handleBattery(byte[] data) {
        if (data == null || data.length < 1) return;
        int pct = data[0] & 0xFF;
        if (pct < 0 || pct > 100) return;
        GBDeviceEventBatteryInfo ev = new GBDeviceEventBatteryInfo();
        ev.level = pct;
        evaluateGBDeviceEvent(ev);
        LOG.debug("MoyoungRing battery: {}%", pct);
    }

    private void handleLiveSteps(byte[] data) {
        MoyoungRingPacket.LiveSteps s = MoyoungRingPacket.parseLiveSteps(data);
        if (s != null) {
            LOG.debug("MoyoungRing live steps: steps={} cal={} dist={}", s.steps, s.calories, s.distance);
            // During a live activity (mode 2), surface the running step count to the
            // Live Activity screen. fdd1 reports the CUMULATIVE daily total, but the UI
            // expects a per-sample DELTA (it accumulates), so convert here.
            if (realtimeStepsEnabled && s.steps >= 0) {
                if (lastRealtimeSteps < 0 || s.steps < lastRealtimeSteps) {
                    // First packet of the session, or a daily rollover/reset -> re-baseline
                    // and emit nothing (the UI would ignore a 0 delta anyway).
                    lastRealtimeSteps = s.steps;
                } else {
                    int delta = s.steps - lastRealtimeSteps;
                    lastRealtimeSteps = s.steps;
                    if (delta > 0) emitRealtimeSteps(System.currentTimeMillis(), delta);
                }
            }
        }
    }

    private void emitRealtimeSteps(long timestampMs, int steps) {
        try {
            GenericActivitySample sample = new RealtimeHrSample();
            sample.setTimestamp((int) (timestampMs / 1000L));
            sample.setSteps(steps);
            Intent intent = new Intent(DeviceService.ACTION_REALTIME_SAMPLES)
                    .putExtra(GBDevice.EXTRA_DEVICE, getDevice())
                    .putExtra(DeviceService.EXTRA_REALTIME_SAMPLE, (java.io.Serializable) sample)
                    .putExtra(DeviceService.EXTRA_TIMESTAMP, sample.getTimestamp());
            LocalBroadcastManager.getInstance(getContext()).sendBroadcast(intent);
        } catch (Exception e) {
            LOG.debug("MoyoungRing realtime steps broadcast failed", e);
        }
    }

    private void handleStepsReply(byte[] payload) {
        MoyoungRingPacket.StepsInfo info = MoyoungRingPacket.parseStepsInfo(payload);
        if (info == null) return;
        if (info.steps < 0 || info.steps > MoyoungRingConstants.STEPS_PER_DAY_MAX) {
            LOG.debug("MoyoungRing steps reply out of range: {}", info.steps);
            return;
        }
        // Cache the day's real totals (distance + calories) so the per-slot histogram
        // (cmd 2/18) can pro-rate them across its 30-minute slots. Key by the same
        // local start-of-day used by the histogram so the two commands line up.
        final long dayStartMs = MoyoungRingPacket.startOfDayMillis(
                System.currentTimeMillis(), info.day, TimeZone.getDefault());
        stepsTotalsByDayStart.put(dayStartMs, info);
        LOG.info("MoyoungRing daily steps (day {}): steps={} dist={} cal={}",
                info.day, info.steps, info.distance, info.calories);
    }

    /**
     * Handle the per-slot step HISTOGRAM (cmd 2/18 reply). Each non-zero 30-minute
     * slot is persisted as an ActivitySample carrying that slot's step count so steps
     * render as a proper per-interval chart. UPSERTS every plausible slot (the
     * provider's insert-or-replace dedups by timestamp+device); it does NOT gate on a
     * forward HWM, so the full rolling window (day 0..N-1) backfills rather than being
     * blocked once day 0 advances the mark. Never issues a delete.
     */
    private void handleStepsHistogram(byte[] payload) {
        final MoyoungRingPacket.StepsHistogram hist = MoyoungRingPacket.parseStepsHistogram(payload);
        if (hist == null || hist.slotSteps.length == 0) {
            LOG.debug("MoyoungRing steps histogram: empty payload");
            return;
        }

        // The day marker is a CRPHistoryDay offset (0=today, 1=yesterday, ...).
        // Zero out future slots for today, matching the companion app filter.
        int[] slots = hist.slotSteps;
        if (hist.day == 0) {
            final Calendar now = Calendar.getInstance();
            final int minutesSinceMidnight = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
            slots = MoyoungRingPacket.filterTodayFutureSlots(slots, minutesSinceMidnight);
        }

        final long dayStartMs = MoyoungRingPacket.startOfDayMillis(
                System.currentTimeMillis(), hist.day, TimeZone.getDefault());

        // Incremental: a PAST day's histogram is immutable, so record it as synced
        // once processed; day 0 (today) is never marked (always re-fetched).
        if (hist.day >= 1 && hist.day < MoyoungRingConstants.TIMING_DAYS_BACK) {
            markDaySynced("steps", dayStartMs);
        }

        final List<MoyoungActivitySample> batch = new ArrayList<>();
        int daySteps = 0;
        for (int slotSteps : slots) {
            if (slotSteps > 0 && slotSteps <= MoyoungRingConstants.STEPS_PER_SLOT_MAX) {
                daySteps += slotSteps;
            }
        }

        // Distance + calories: prefer the ring's real per-day totals (cmd 2/13) and
        // pro-rate them across slots by each slot's share of the day's total steps
        // (slotX = totalX * slotSteps / daySteps), so per-slot values sum back to the
        // ring's totals and the Activity chart matches Da Rings. If the 2/13 total for
        // this day hasn't arrived yet, fall back to a per-step estimate so distance /
        // calories are never left at 0 when there are steps.
        //
        // UNIT FIX: the R26 StepsInfo reports distance in MILLIMETRES and calories in
        // (sub-kcal) cal, i.e. both are 1000x the human unit. Decoded live: day0
        // steps=1750 distance=725918 calories=120750 -> 725918 mm = 0.73 km and
        // 120750 cal = 120.75 kcal (cross-checked against Da Rings' 211 kCal @ 4444
        // steps = 211000/1000). Store distance as metres, but keep calories in cal
        // because ActivitySample active calories are later divided by 1000 by the
        // calories charts. Storing kcal here makes week/day chart bars round to 0.
        final MoyoungRingPacket.StepsInfo dayTotals = stepsTotalsByDayStart.get(dayStartMs);
        final boolean haveRealTotals = dayTotals != null && daySteps > 0
                && dayTotals.distance > 0 && dayTotals.calories > 0;
        final double totalDistanceM = haveRealTotals
                ? dayTotals.distance / (double) MoyoungRingConstants.STEPS_DISTANCE_DIVISOR : 0d;
        final double totalCaloriesCal = haveRealTotals ? dayTotals.calories : 0d;
        // Estimate fallbacks: stride from the user profile; ~0.04 kcal/step.
        final double strideMeters = Math.max(0.3, new ActivityUser().getStepLengthCm() / 100.0);
        final double kcalPerStep = 0.04;

        for (int i = 0; i < slots.length; i++) {
            final int slotSteps = slots[i];
            if (slotSteps <= 0 || slotSteps > MoyoungRingConstants.STEPS_PER_SLOT_MAX) {
                continue; // skip empty / implausible slots
            }
            final long slotTsMs = MoyoungRingPacket.slotTimestampMillis(dayStartMs, i);
            final MoyoungActivitySample sample = new MoyoungActivitySample();
            sample.setTimestamp((int) (slotTsMs / 1000L));
            sample.setSteps(slotSteps);
            sample.setRawKind(ActivityKind.ACTIVITY.getCode());
            sample.setRawIntensity(ActivitySample.NOT_MEASURED);
            sample.setHeartRate(ActivitySample.NOT_MEASURED);
            sample.setDataSource(MoyoungActivitySampleProvider.SOURCE_STEPS_SUMMARY);

            final int slotDistanceM;
            final int slotCalories;
            if (haveRealTotals) {
                final double share = (double) slotSteps / (double) daySteps;
                slotDistanceM = (int) Math.round(totalDistanceM * share);
                slotCalories = (int) Math.round(totalCaloriesCal * share);
            } else {
                slotDistanceM = (int) Math.round(slotSteps * strideMeters);
                slotCalories = (int) Math.round(slotSteps * kcalPerStep
                        * MoyoungRingConstants.STEPS_CALORIES_DIVISOR);
            }
            sample.setDistanceMeters(slotDistanceM);
            sample.setCaloriesBurnt(slotCalories);
            batch.add(sample);
        }

        LOG.info("MoyoungRing steps histogram (day {}): {} slots, {} persisted (upsert), {} total steps",
                hist.day, slots.length, batch.size(), daySteps);
        if (batch.isEmpty()) return;

        withDb((session, deviceId, userId) -> {
            final MoyoungActivitySampleProvider provider =
                    new MoyoungActivitySampleProvider(getDevice(), session);
            for (final MoyoungActivitySample s : batch) {
                s.setDeviceId(deviceId);
                s.setUserId(userId);
                s.setProvider(provider);
            }
            provider.addGBActivitySamples(batch);
        });
    }

    // -------- HWM (client-side, no-delete) --------

    /**
     * High-water-mark gate: returns {@code true} when a record is strictly newer
     * than the persisted HWM. Callers advance the HWM after a batch is persisted.
     */
    static boolean isNewRecord(long recordTsMs, long hwmTsMs) {
        // Reject non-positive, absurd FUTURE, and absurd PAST timestamps (frames carry
        // no CRC; e.g. the HR LIST record can decode to 1971 with a wrong layout). A
        // hard floor stops garbage-old samples persisting even on a first sync (hwm=0).
        final long maxFuture = System.currentTimeMillis() + MoyoungRingConstants.HWM_FUTURE_TOLERANCE_MS;
        return recordTsMs > MoyoungRingConstants.TS_FLOOR_MS
                && recordTsMs <= maxFuture && recordTsMs > hwmTsMs;
    }

    /**
     * Read the HWM for a metric. Returns 0 if none stored. A stored HWM more than
     * 5 minutes in the future (from stale clock skew) is reset to 0 so we start
     * fresh rather than dropping every real record.
     */
    private long getHwm(String key) {
        long raw;
        try {
            raw = getDevicePrefs().getLong(key, 0L);
        } catch (Exception e) {
            try {
                raw = Long.parseLong(getDevicePrefs().getString(key, "0"));
            } catch (Exception e2) {
                raw = 0L;
            }
        }
        long now = System.currentTimeMillis();
        if (raw > now + MoyoungRingConstants.HWM_FUTURE_TOLERANCE_MS) {
            LOG.warn("MoyoungRing HWM {} is >5 min in the future, resetting to 0", key);
            try {
                getDevicePrefs().getPreferences().edit().remove(key).apply();
            } catch (Exception ignored) {
            }
            return 0L;
        }
        return raw;
    }

    private void advanceHwm(String key, long candidateMs) {
        if (candidateMs <= 0L) return;
        long cur = getHwm(key);
        if (candidateMs > cur) {
            try {
                getDevicePrefs().getPreferences().edit().putLong(key, candidateMs).apply();
                LOG.debug("MoyoungRing HWM {} -> {}", key, candidateMs);
            } catch (Exception e) {
                LOG.warn("MoyoungRing failed to persist HWM {}", key, e);
            }
        }
    }

    // -------- Incremental-sync store (persisted pages + synced days) --------
    // These survive reconnects/restarts in the device-scoped SharedPreferences so
    // immutable history is NEVER re-fetched: a sealed timeline page (whole window
    // elapsed) is recorded per op, and a fully-processed PAST day is recorded per
    // kind (sleep/steps). Day 0 (today) is intentionally never stored/skipped — it
    // is always re-fetched. Entries older than 60 days are pruned on write.

    /** Retention horizon for the incremental-sync bookkeeping sets. */
    private static final long PREF_PRUNE_MS = 60L * 24L * 3600_000L;

    private String pagesPrefKey(int op) { return "moyoungring_pages_" + op; }
    private String daysPrefKey(String kind) { return "moyoungring_days_" + kind; }

    /** Record that {@code page} of {@code op} for local day {@code dayStartMs} is sealed+stored. */
    private void markPagePersisted(int op, long dayStartMs, int page) {
        final String key = pagesPrefKey(op);
        try {
            final android.content.SharedPreferences prefs = getDevicePrefs().getPreferences();
            final java.util.Set<String> cur = new java.util.HashSet<>(
                    prefs.getStringSet(key, java.util.Collections.<String>emptySet()));
            if (cur.add(dayStartMs + ":" + page)) {
                pruneDayKeyedSet(cur);
                prefs.edit().putStringSet(key, cur).apply();
            }
        } catch (Exception e) {
            LOG.debug("MoyoungRing markPagePersisted failed", e);
        }
    }

    private boolean isPagePersisted(int op, long dayStartMs, int page) {
        try {
            final java.util.Set<String> cur = getDevicePrefs().getPreferences()
                    .getStringSet(pagesPrefKey(op), java.util.Collections.<String>emptySet());
            return cur != null && cur.contains(dayStartMs + ":" + page);
        } catch (Exception e) {
            return false;
        }
    }

    /** Record that a PAST local day was fully fetched for {@code kind} (sleep|steps). */
    private void markDaySynced(String kind, long dayStartMs) {
        final String key = daysPrefKey(kind);
        try {
            final android.content.SharedPreferences prefs = getDevicePrefs().getPreferences();
            final java.util.Set<String> cur = new java.util.HashSet<>(
                    prefs.getStringSet(key, java.util.Collections.<String>emptySet()));
            if (cur.add(Long.toString(dayStartMs))) {
                pruneDayKeyedSet(cur);
                prefs.edit().putStringSet(key, cur).apply();
            }
        } catch (Exception e) {
            LOG.debug("MoyoungRing markDaySynced failed", e);
        }
    }

    private boolean isDaySynced(String kind, long dayStartMs) {
        try {
            final java.util.Set<String> cur = getDevicePrefs().getPreferences()
                    .getStringSet(daysPrefKey(kind), java.util.Collections.<String>emptySet());
            return cur != null && cur.contains(Long.toString(dayStartMs));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Drop entries older than {@link #PREF_PRUNE_MS}. Entries are either
     * {@code "dayStartMs:page"} (pages) or {@code "dayStartMs"} (days); both start
     * with the day's epoch millis, so we parse the leading long before any ':'.
     */
    private static void pruneDayKeyedSet(java.util.Set<String> set) {
        final long cutoff = System.currentTimeMillis() - PREF_PRUNE_MS;
        final java.util.Iterator<String> it = set.iterator();
        while (it.hasNext()) {
            final String e = it.next();
            long day;
            try {
                final int colon = e.indexOf(':');
                day = Long.parseLong(colon >= 0 ? e.substring(0, colon) : e);
            } catch (Exception ex) {
                it.remove();
                continue;
            }
            if (day < cutoff) it.remove();
        }
    }

    // -------- Learned "unsupported timeline" capability (BLE/battery efficiency) --------

    /** True once this op's timeline has been confirmed dead (>= threshold consecutive dead syncs). */
    private boolean isTimelineUnsupported(int op) {
        try {
            return getDevicePrefs().getPreferences()
                    .getInt("moyoungring_deadtl_" + op, 0) >= UNSUPPORTED_TIMELINE_THRESHOLD;
        } catch (Exception e) {
            return false;
        }
    }

    /** A timeline op produced data this connection -> it IS supported; clear its dead counter. */
    private void markTimelineAlive(int op) {
        try {
            final android.content.SharedPreferences prefs = getDevicePrefs().getPreferences();
            if (prefs.getInt("moyoungring_deadtl_" + op, 0) != 0) {
                prefs.edit().putInt("moyoungring_deadtl_" + op, 0).apply();
            }
        } catch (Exception e) {
            LOG.debug("MoyoungRing markTimelineAlive failed", e);
        }
    }

    /** A timeline op timed out with no data this connection -> bump its dead counter. */
    private void markTimelineDead(int op) {
        try {
            final android.content.SharedPreferences prefs = getDevicePrefs().getPreferences();
            final int n = prefs.getInt("moyoungring_deadtl_" + op, 0);
            if (n < UNSUPPORTED_TIMELINE_THRESHOLD) {
                prefs.edit().putInt("moyoungring_deadtl_" + op, n + 1).apply();
                if (n + 1 >= UNSUPPORTED_TIMELINE_THRESHOLD) {
                    LOG.info("MoyoungRing timeline op={} learned UNSUPPORTED (fw doesn't log it); "
                            + "will stop requesting it + its auto-monitor to save BLE/battery", op);
                }
            }
        } catch (Exception e) {
            LOG.debug("MoyoungRing markTimelineDead failed", e);
        }
    }

    /** On a firmware change, wipe learned capability flags so a fw update can re-enable metrics. */
    private void invalidateCapabilitiesIfFirmwareChanged(final String fw) {
        if (fw == null || fw.isEmpty()) return;
        if (fw.equals(lastFirmware)) return;
        lastFirmware = fw;
        try {
            final android.content.SharedPreferences prefs = getDevicePrefs().getPreferences();
            final String prev = prefs.getString("moyoungring_fw", null);
            if (!fw.equals(prev)) {
                final android.content.SharedPreferences.Editor ed = prefs.edit();
                ed.putString("moyoungring_fw", fw);
                for (int op : new int[]{ MoyoungRingConstants.OP_TIMING_SPO2,
                        MoyoungRingConstants.OP_TIMING_STRESS, MoyoungRingConstants.OP_TIMING_HR,
                        MoyoungRingConstants.OP_TIMING_HRV, MoyoungRingConstants.OP_TIMING_TEMP }) {
                    ed.remove("moyoungring_deadtl_" + op);
                }
                ed.apply();
                LOG.info("MoyoungRing firmware changed ({} -> {}); re-probing metric capabilities", prev, fw);
            }
        } catch (Exception e) {
            LOG.debug("MoyoungRing capability invalidation failed", e);
        }
    }

    // -------- History handlers --------

    private void handleHrHistory(byte[] payload) {
        final List<MoyoungRingPacket.HrRecord> all = MoyoungRingPacket.parseHrRecords(payload);
        final long hwm = getHwm(MoyoungRingConstants.HWM_HR);
        final List<MoyoungRingPacket.HrRecord> records = new ArrayList<>(all.size());
        for (MoyoungRingPacket.HrRecord r : all) {
            if (isNewRecord(r.timestampMs, hwm) && validHr(r.bpm)) records.add(r);
        }
        LOG.info("MoyoungRing HR history: {} records ({} new), hwm={}", all.size(), records.size(), hwm);
        if (records.isEmpty()) return;
        boolean persisted = withDb((session, deviceId, userId) -> {
            GenericHeartRateSampleProvider provider =
                    new GenericHeartRateSampleProvider(getDevice(), session);
            List<GenericHeartRateSample> batch = new ArrayList<>(records.size());
            for (MoyoungRingPacket.HrRecord r : records) {
                batch.add(new GenericHeartRateSample(r.timestampMs, deviceId, userId, r.bpm));
            }
            provider.addSamples(batch);
        });
        long newest = 0L;
        for (MoyoungRingPacket.HrRecord r : records) if (r.timestampMs > newest) newest = r.timestampMs;
        if (persisted) advanceHwm(MoyoungRingConstants.HWM_HR, newest);
    }

    private void handleSpo2History(byte[] payload) {
        final List<MoyoungRingPacket.Spo2Record> all = MoyoungRingPacket.parseSpo2Records(payload);
        final long hwm = getHwm(MoyoungRingConstants.HWM_SPO2);
        final List<MoyoungRingPacket.Spo2Record> records = new ArrayList<>(all.size());
        for (MoyoungRingPacket.Spo2Record r : all) {
            if (isNewRecord(r.timestampMs, hwm) && validSpo2(r.spo2)) records.add(r);
        }
        LOG.info("MoyoungRing SpO2 history: {} records ({} new), hwm={}", all.size(), records.size(), hwm);
        if (records.isEmpty()) return;
        boolean persisted = withDb((session, deviceId, userId) -> {
            GenericSpo2SampleProvider provider =
                    new GenericSpo2SampleProvider(getDevice(), session);
            List<GenericSpo2Sample> batch = new ArrayList<>(records.size());
            for (MoyoungRingPacket.Spo2Record r : records) {
                batch.add(new GenericSpo2Sample(r.timestampMs, deviceId, userId, r.spo2));
            }
            provider.addSamples(batch);
        });
        long newest = 0L;
        for (MoyoungRingPacket.Spo2Record r : records) if (r.timestampMs > newest) newest = r.timestampMs;
        if (persisted) advanceHwm(MoyoungRingConstants.HWM_SPO2, newest);
    }

    private void handleHrvHistory(byte[] payload) {
        final List<MoyoungRingPacket.HrvRecord> all = MoyoungRingPacket.parseHrvRecords(payload);
        final long hwm = getHwm(MoyoungRingConstants.HWM_HRV);
        final List<MoyoungRingPacket.HrvRecord> records = new ArrayList<>(all.size());
        for (MoyoungRingPacket.HrvRecord r : all) {
            if (isNewRecord(r.timestampMs, hwm) && validHrv(r.hrv)) records.add(r);
        }
        LOG.info("MoyoungRing HRV history: {} records ({} new), hwm={}", all.size(), records.size(), hwm);
        if (records.isEmpty()) return;
        boolean persisted = withDb((session, deviceId, userId) -> {
            GenericHrvValueSampleProvider provider =
                    new GenericHrvValueSampleProvider(getDevice(), session);
            List<GenericHrvValueSample> batch = new ArrayList<>(records.size());
            for (MoyoungRingPacket.HrvRecord r : records) {
                batch.add(new GenericHrvValueSample(r.timestampMs, deviceId, userId, r.hrv));
            }
            provider.addSamples(batch);
        });
        long newest = 0L;
        for (MoyoungRingPacket.HrvRecord r : records) if (r.timestampMs > newest) newest = r.timestampMs;
        if (persisted) advanceHwm(MoyoungRingConstants.HWM_HRV, newest);
    }

    private void handleStressHistory(byte[] payload) {
        final List<MoyoungRingPacket.StressRecord> all = MoyoungRingPacket.parseStressRecords(payload);
        final long hwm = getHwm(MoyoungRingConstants.HWM_STRESS);
        final List<MoyoungRingPacket.StressRecord> records = new ArrayList<>(all.size());
        for (MoyoungRingPacket.StressRecord r : all) {
            // Gate on plausibility HERE (like HR/SpO2/HRV) so the HWM only ever
            // advances over records we actually persist.
            if (isNewRecord(r.timestampMs, hwm) && MoyoungRingConstants.plausibleStress(r.stress)) {
                records.add(r);
            }
        }
        LOG.info("MoyoungRing stress history: {} records ({} new), hwm={}", all.size(), records.size(), hwm);
        if (records.isEmpty()) return;
        boolean persisted = withDb((session, deviceId, userId) -> {
            GenericStressSampleProvider provider =
                    new GenericStressSampleProvider(getDevice(), session);
            List<GenericStressSample> batch = new ArrayList<>(records.size());
            for (MoyoungRingPacket.StressRecord r : records) {
                batch.add(new GenericStressSample(r.timestampMs, deviceId, userId, r.stress));
            }
            if (!batch.isEmpty()) provider.addSamples(batch);
        });
        long newest = 0L;
        for (MoyoungRingPacket.StressRecord r : records) if (r.timestampMs > newest) newest = r.timestampMs;
        if (persisted) advanceHwm(MoyoungRingConstants.HWM_STRESS, newest);
    }

    // -------- Paged daily-timeline (timing) handlers --------
    // Each metric's per-day timeline is delivered PAGED, and we persist PER PAGE
    // (not per whole day) so the driver can fetch just the page(s) that still need
    // data — truly INCREMENTAL sync. A page is IMMUTABLE ("sealed") once real time
    // passes the end of its window; a sealed page that is already persisted is never
    // re-requested (see needFetch / isPagePersisted). We upsert every plausible slot
    // (the generic DAO's insert-or-replace dedups by timestamp+device) and NEVER send
    // any delete/clear. The reassembler above is retained (and unit-tested) but is no
    // longer used to gate persistence.

    /** Local midnight for (today - dayOffset). dayOffset 0=today, 1=yesterday. */
    private static long startOfLocalDay(int dayOffset) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, -dayOffset);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Slots carried by a single page of this metric's timeline (HRV/temp=72, else 144). */
    private static int slotsPerPage(int op) {
        return (op == MoyoungRingConstants.OP_TIMING_HRV
                || op == MoyoungRingConstants.OP_TIMING_TEMP) ? 72 : 144;
    }

    /** Terminating page index for this metric's timeline. */
    private static int lastPage(int op) {
        if (op == MoyoungRingConstants.OP_TIMING_HRV) return MoyoungRingConstants.TIMING_LAST_PAGE_HRV;
        if (op == MoyoungRingConstants.OP_TIMING_SPO2) return MoyoungRingConstants.TIMING_LAST_PAGE_SPO2;
        if (op == MoyoungRingConstants.OP_TIMING_STRESS) return MoyoungRingConstants.TIMING_LAST_PAGE_STRESS;
        if (op == MoyoungRingConstants.OP_TIMING_TEMP) return MoyoungRingConstants.TIMING_LAST_PAGE_TEMP;
        return MoyoungRingConstants.TIMING_LAST_PAGE_HR;
    }

    /** Wall-clock span (ms) a single page of this metric covers within its day. */
    private static long pageSpanMs(int op) {
        return (long) slotsPerPage(op) * MoyoungRingConstants.TIMING_SLOT_MINUTES * 60_000L;
    }

    /** End (exclusive) of the given page's window, in epoch millis. */
    private static long pageEndMs(long dayStartMs, int page, int op) {
        return dayStartMs + (long) (page + 1) * pageSpanMs(op);
    }

    /**
     * True when this page still needs to be fetched:
     * its window has begun (not a future page) AND it is not already sealed+stored.
     */
    private boolean needFetch(int op, long dayStartMs, int page, long now) {
        final long span = pageSpanMs(op);
        final long pageStart = dayStartMs + (long) page * span;
        final long pageEnd = dayStartMs + (long) (page + 1) * span;
        if (pageStart > now) return false;                              // window not begun
        if (pageEnd <= now && isPagePersisted(op, dayStartMs, page)) return false; // sealed+stored
        return true;
    }

    /** First page index (0..lastPage) that still needs fetching, or -1 if none. */
    private int firstNeededPage(int op, long dayStartMs, long now) {
        return firstNeededPageFrom(op, dayStartMs, 0, now);
    }

    private int firstNeededPageFrom(int op, long dayStartMs, int fromPage, long now) {
        for (int p = Math.max(0, fromPage); p <= lastPage(op); p++) {
            if (needFetch(op, dayStartMs, p, now)) return p;
        }
        return -1;
    }

    private void requestTimingPage(int cmd, int sub, int day, int pageIndex) {
        // Dispatch the next page through the pacer, at the FRONT so it stays with
        // its metric-day; the pacer sends it after the current step completes.
        enqueueSyncFront(MoyoungRingPacket.timingRequest(cmd, sub, day, pageIndex));
    }

    private void handleTimingHr(byte[] payload) {
        handleTimingPage(payload, MoyoungRingConstants.OP_TIMING_HR,
                MoyoungRingConstants.CMD_TIMING_HR, MoyoungRingConstants.SUB_TIMING_HR,
                MoyoungRingPacket.SlotFormat.U8, MoyoungRingConstants::plausibleHr);
    }

    private void handleTimingSpo2(byte[] payload) {
        handleTimingPage(payload, MoyoungRingConstants.OP_TIMING_SPO2,
                MoyoungRingConstants.CMD_TIMING_SPO2, MoyoungRingConstants.SUB_TIMING_SPO2,
                MoyoungRingPacket.SlotFormat.U8, MoyoungRingConstants::plausibleSpo2);
    }

    private void handleTimingHrv(byte[] payload) {
        handleTimingPage(payload, MoyoungRingConstants.OP_TIMING_HRV,
                MoyoungRingConstants.CMD_TIMING_HRV, MoyoungRingConstants.SUB_TIMING_HRV,
                MoyoungRingPacket.SlotFormat.U16LE, MoyoungRingConstants::plausibleHrv);
    }

    private void handleTimingStress(byte[] payload) {
        handleTimingPage(payload, MoyoungRingConstants.OP_TIMING_STRESS,
                MoyoungRingConstants.CMD_TIMING_STRESS, MoyoungRingConstants.SUB_TIMING_STRESS,
                MoyoungRingPacket.SlotFormat.U8, MoyoungRingConstants::plausibleStress);
    }

    /**
     * Temperature daily-timeline (2/22) per-page handler. Structured identically to
     * {@link #handleTimingPage} but persists a FLOAT °C to the temperature provider
     * (u16LE slot / 10.0), so it cannot reuse the int-based generic path. Slot 0 is
     * "no reading". Same per-page incremental seal/chain and single pacer advance.
     */
    private void handleTimingTemp(byte[] payload) {
        final int op = MoyoungRingConstants.OP_TIMING_TEMP;
        final MoyoungRingPacket.TimingPage page = MoyoungRingPacket.parseTimingPage(
                payload, MoyoungRingPacket.SlotFormat.U16LE);
        if (page == null) { syncResponseArrived(op); return; }
        if (page.pageIndex < 0 || page.pageIndex > lastPage(op)
                || page.day < 0 || page.day >= MoyoungRingConstants.TIMING_DAYS_BACK) {
            syncResponseArrived(op);
            return;
        }
        final long now = System.currentTimeMillis();
        final long dayStartMs = startOfLocalDay(page.day);
        final long slotMs = (long) MoyoungRingConstants.TIMING_SLOT_MINUTES * 60_000L;
        final int slotsPer = slotsPerPage(op);
        final List<MoyoungRingPacket.TimedValue> raw = new ArrayList<>();
        // Reuse TimedValue as a (timestamp, deci-degrees) carrier so persistence can
        // rebuild the float; celsius = value / 10.0.
        for (int i = 0; i < page.slots.length; i++) {
            final int deci = page.slots[i];
            if (deci <= 0) continue;                       // 0 = no reading
            final double celsius = deci / 10.0;
            if (!MoyoungRingConstants.plausibleTempTimeline(celsius)) continue;
            final int globalIndex = page.pageIndex * slotsPer + i;
            raw.add(new MoyoungRingPacket.TimedValue(dayStartMs + (long) globalIndex * slotMs, deci));
        }
        if (!raw.isEmpty()) {
            timingMetricsWithData.add(op);
            markTimelineAlive(op);
            LOG.info("MoyoungRing timing op={} (temp) day {} page {}: {} slots persisted (upsert)",
                    op, page.day, page.pageIndex, raw.size());
            withDb((session, deviceId, userId) -> {
                GenericTemperatureSampleProvider provider =
                        new GenericTemperatureSampleProvider(getDevice(), session);
                List<GenericTemperatureSample> batch = new ArrayList<>(raw.size());
                for (MoyoungRingPacket.TimedValue v : raw) {
                    batch.add(new GenericTemperatureSample(
                            v.timestampMs, deviceId, userId, (float) (v.value / 10.0),
                            TemperatureSample.TYPE_SKIN, TemperatureSample.LOCATION_FINGER));
                }
                provider.addSamples(batch);
            });
        }
        // Seal an already-elapsed page so it is never re-fetched.
        if (pageEndMs(dayStartMs, page.pageIndex, op) <= now) {
            markPagePersisted(op, dayStartMs, page.pageIndex);
        }
        // Chain to the next page that still needs data (skips sealed / future pages).
        final int nextNeeded = firstNeededPageFrom(op, dayStartMs, page.pageIndex + 1, now);
        if (nextNeeded >= 0) {
            requestTimingPage(MoyoungRingConstants.CMD_TIMING_TEMP,
                    MoyoungRingConstants.SUB_TIMING_TEMP, page.day, nextNeeded);
        }
        syncResponseArrived(op, page.day, page.pageIndex);
    }

    /**
     * Per-page timeline handler shared by all four metrics. Parses ONE page, upserts
     * its plausible slots immediately (no whole-day reassembly), seals the page once
     * its window has elapsed, then chains forward to the next page that still needs
     * data (skipping sealed and future pages). Advances the pacer EXACTLY ONCE per
     * response via {@link #syncResponseArrived}.
     */
    private void handleTimingPage(byte[] payload, int op, int cmd, int sub,
                                  MoyoungRingPacket.SlotFormat format,
                                  MoyoungRingPacket.SlotFilter filter) {
        final MoyoungRingPacket.TimingPage page = MoyoungRingPacket.parseTimingPage(payload, format);
        if (page == null) { syncResponseArrived(op); return; }
        if (page.pageIndex < 0 || page.pageIndex > lastPage(op)
                || page.day < 0 || page.day >= MoyoungRingConstants.TIMING_DAYS_BACK) {
            syncResponseArrived(op);
            return;
        }
        final long now = System.currentTimeMillis();
        final long dayStartMs = startOfLocalDay(page.day);
        final List<MoyoungRingPacket.TimedValue> vals = MoyoungRingPacket.pageToSamples(
                page, dayStartMs, slotsPerPage(op), MoyoungRingConstants.TIMING_SLOT_MINUTES, filter);
        if (!vals.isEmpty()) {
            timingMetricsWithData.add(op);
            markTimelineAlive(op);
            LOG.info("MoyoungRing timing op={} day {} page {}: {} slots persisted (upsert)",
                    op, page.day, page.pageIndex, vals.size());
            persistTimingSamples(op, vals);
        }
        // Seal an already-elapsed page so it is never re-fetched; a still-accumulating
        // page (window not yet passed) is deliberately NOT marked so it refreshes.
        if (pageEndMs(dayStartMs, page.pageIndex, op) <= now) {
            markPagePersisted(op, dayStartMs, page.pageIndex);
        }
        // Chain to the next page that still needs data (skips sealed / future pages).
        final int nextNeeded = firstNeededPageFrom(op, dayStartMs, page.pageIndex + 1, now);
        if (nextNeeded >= 0) {
            requestTimingPage(cmd, sub, page.day, nextNeeded);
        }
        syncResponseArrived(op, page.day, page.pageIndex);
    }

    /** Upsert a page's timestamped slots into the appropriate generic provider. */
    private void persistTimingSamples(int op, List<MoyoungRingPacket.TimedValue> vals) {
        withDb((session, deviceId, userId) -> {
            if (op == MoyoungRingConstants.OP_TIMING_HR) {
                GenericHeartRateSampleProvider provider =
                        new GenericHeartRateSampleProvider(getDevice(), session);
                List<GenericHeartRateSample> batch = new ArrayList<>(vals.size());
                for (MoyoungRingPacket.TimedValue v : vals) {
                    batch.add(new GenericHeartRateSample(v.timestampMs, deviceId, userId, v.value));
                }
                provider.addSamples(batch);
            } else if (op == MoyoungRingConstants.OP_TIMING_SPO2) {
                GenericSpo2SampleProvider provider =
                        new GenericSpo2SampleProvider(getDevice(), session);
                List<GenericSpo2Sample> batch = new ArrayList<>(vals.size());
                for (MoyoungRingPacket.TimedValue v : vals) {
                    batch.add(new GenericSpo2Sample(v.timestampMs, deviceId, userId, v.value));
                }
                provider.addSamples(batch);
            } else if (op == MoyoungRingConstants.OP_TIMING_HRV) {
                GenericHrvValueSampleProvider provider =
                        new GenericHrvValueSampleProvider(getDevice(), session);
                List<GenericHrvValueSample> batch = new ArrayList<>(vals.size());
                for (MoyoungRingPacket.TimedValue v : vals) {
                    batch.add(new GenericHrvValueSample(v.timestampMs, deviceId, userId, v.value));
                }
                provider.addSamples(batch);
            } else if (op == MoyoungRingConstants.OP_TIMING_STRESS) {
                GenericStressSampleProvider provider =
                        new GenericStressSampleProvider(getDevice(), session);
                List<GenericStressSample> batch = new ArrayList<>(vals.size());
                for (MoyoungRingPacket.TimedValue v : vals) {
                    batch.add(new GenericStressSample(v.timestampMs, deviceId, userId, v.value));
                }
                provider.addSamples(batch);
            }
        });
    }

    /**
     * Sleep history reply. The per-day stage response (cmd 2/14) arrives either as a
     * session-details header (&gt;=22 bytes) or as a stage list ({@code len % 3 == 1})
     * whose leading byte is the echoed day index. Details set the absolute anchor used
     * to place subsequent stage records; if no details anchor is known, stages are
     * anchored to the local calendar day {@code today - dayIndex} using the day byte.
     *
     * <p>For the per-day {@code OP_HIST_SLEEP_DAY} (2/14) path we ALWAYS anchor from
     * the echoed day byte and never consult a prior sleep-details anchor (a global
     * {@code lastSleepStartMs} would mis-time a later day's frames), and we UPSERT
     * every plausible segment (no forward HWM gate — that would let day 0 advance the
     * high-water-mark and drop the older days fetched afterwards). The generic
     * provider dedups by timestamp+device, so every fetched night backfills.
     */
    private void handleSleepHistory(byte[] payload, int opcode) {
        final boolean perDay = (opcode == MoyoungRingConstants.OP_HIST_SLEEP_DAY);
        // Mark a PAST day synced as soon as its 2/14 frame is processed (even if it
        // carries no plausible stages), so incremental sync never re-requests it. The
        // day byte (payload[0]) is echoed from our per-day request; day 0 (today) is
        // never marked (always re-fetched).
        if (perDay && payload != null && payload.length >= 1) {
            final int di = payload[0] & 0xFF;
            if (di >= 1 && di < MoyoungRingConstants.TIMING_DAYS_BACK) {
                markDaySynced("sleep", startOfLocalDay(di));
            }
        }
        // Frame types under the sleep path collide by length, so DISAMBIGUATE by
        // structure and PRIORITISE the stage list (it holds the graph data). A
        // normal 7-13 segment night is `1 + N*3` bytes (len%3==1) which also falls
        // inside the 22..40 details band — parsing it as details would silently drop
        // the stages and poison the anchor. Stage parsing therefore wins on collision.
        final boolean looksLikeStageList = (payload.length % 3 == 1) && payload.length >= 4;

        if (!looksLikeStageList && payload.length >= 22) {
            MoyoungRingPacket.SleepDetails d = MoyoungRingPacket.parseSleepDetails(payload);
            // Only accept (and anchor from) a details record whose window is sane,
            // so a corrupted/misread frame can never poison the stage anchor.
            if (d != null && isPlausibleSleepWindow(d.startTimeMs, d.endTimeMs)) {
                lastSleepStartMs = d.startTimeMs;
                lastSleepEndMs = d.endTimeMs;
                LOG.info("MoyoungRing sleep session: {}..{} sleepMin={} eff={} score={}",
                        d.startTimeMs, d.endTimeMs, d.sleepTimeMin, d.efficiency, d.score);
                return;
            }
            // The ring exposes sleep detail only within the stage frame in this firmware;
            // a dedicated sleep-details sub-opcode has not been observed, so we log and
            // fall through rather than guess at an unconfirmed layout.
            LOG.debug("MoyoungRing sleep: {}-byte frame not a plausible details record", payload.length);
            return;
        }
        // Per-day 2/14: force day-byte anchoring by ignoring any stale global anchor.
        long anchorStart = perDay ? 0L : lastSleepStartMs;
        long anchorEnd = perDay ? 0L : lastSleepEndMs;
        if (anchorStart <= 0L) {
            // No sleep-details anchor is known. Prefer the DAY BYTE (payload[0], echoed
            // from our 2/14 per-day request) to place the night DETERMINISTICALLY: the
            // stage records belong to the local calendar day `today - dayIndex`. Anchor
            // the onset at that day's start + the first stage record's local hh:mm, and
            // chain the end from the last record's hh:mm (the midnight wrap for the
            // records after onset is already applied by parseSleepSegments). This beats
            // inferring a date from the current clock time, which is ambiguous.
            final int firstMinuteOfDay = firstStageMinuteOfDay(payload);
            final int lastMinuteOfDay = lastStageMinuteOfDay(payload);
            if (payload != null && payload.length >= 4 && firstMinuteOfDay >= 0) {
                final int dayIndex = payload[0] & 0xFF;
                final long dayStart = MoyoungRingPacket.startOfDayMillis(
                        System.currentTimeMillis(), dayIndex, TimeZone.getDefault());
                anchorStart = dayStart + (long) firstMinuteOfDay * 60_000L;
                int spanMin = (lastMinuteOfDay >= 0)
                        ? ((lastMinuteOfDay - firstMinuteOfDay + 24 * 60) % (24 * 60))
                        : 0;
                if (spanMin <= 0) {
                    anchorEnd = anchorStart + 8L * 3600_000L; // defensive default span
                } else {
                    anchorEnd = anchorStart
                            + (long) spanMin * 60_000L
                            + (long) MoyoungRingConstants.TIMING_SLOT_MINUTES * 60_000L;
                }
            } else {
                // DK-7 SECONDARY fallback: payload too short to carry a day byte + first
                // stage record. Derive the onset from the first record's own hour:minute
                // rather than a fixed offset. Place that hh:mm on the most recent local
                // occurrence of that clock time that is not in the future (maps an
                // evening onset to the previous calendar day when synced the next
                // morning), else anchor loosely around the previous local night.
                Calendar c = Calendar.getInstance();
                if (firstMinuteOfDay >= 0) {
                    c.set(Calendar.HOUR_OF_DAY, firstMinuteOfDay / 60);
                    c.set(Calendar.MINUTE, firstMinuteOfDay % 60);
                    c.set(Calendar.SECOND, 0);
                    c.set(Calendar.MILLISECOND, 0);
                    if (c.getTimeInMillis() > System.currentTimeMillis()
                            + MoyoungRingConstants.HWM_FUTURE_TOLERANCE_MS) {
                        c.add(Calendar.DAY_OF_MONTH, -1);
                    }
                    anchorStart = c.getTimeInMillis();
                    int spanMin = (lastMinuteOfDay >= 0)
                            ? ((lastMinuteOfDay - firstMinuteOfDay + 24 * 60) % (24 * 60))
                            : 0;
                    if (spanMin <= 0) {
                        anchorEnd = anchorStart + 8L * 3600_000L; // defensive default span
                    } else {
                        anchorEnd = anchorStart
                                + (long) spanMin * 60_000L
                                + (long) MoyoungRingConstants.TIMING_SLOT_MINUTES * 60_000L;
                    }
                } else {
                    // No usable first record (all hh:mm out of range): keep the previous
                    // defensive behaviour of anchoring around the previous local night.
                    c.set(Calendar.HOUR_OF_DAY, 0);
                    c.set(Calendar.MINUTE, 0);
                    c.set(Calendar.SECOND, 0);
                    c.set(Calendar.MILLISECOND, 0);
                    anchorStart = c.getTimeInMillis() - 6L * 3600_000L; // ~previous night onset
                    anchorEnd = c.getTimeInMillis() + 9L * 3600_000L;
                }
            }
            // FIX D: bound the fallback window so we never persist a future/oversized
            // fake segment: clamp the end to now (+ tolerance), and skip entirely if
            // the resulting window is implausible.
            anchorEnd = Math.min(anchorEnd,
                    System.currentTimeMillis() + MoyoungRingConstants.HWM_FUTURE_TOLERANCE_MS);
            if (!isPlausibleSleepWindow(anchorStart, anchorEnd)) {
                LOG.debug("MoyoungRing sleep: implausible fallback window {}..{}, skipping",
                        anchorStart, anchorEnd);
                return;
            }
            if (isLikelyAwakeDaytimeWindow(anchorStart, anchorEnd)) {
                LOG.debug("MoyoungRing sleep: daytime fallback window {}..{} looks awake, skipping",
                        anchorStart, anchorEnd);
                return;
            }
        }

        final List<MoyoungRingPacket.SleepSegment> all =
                MoyoungRingPacket.parseSleepSegments(payload, anchorStart, anchorEnd);
        // Per-day 2/14 is fetched newest-first (day 0..N-1). A forward HWM would let
        // day 0 advance it and drop every older day, so UPSERT ALL segments and do NOT
        // gate on / advance HWM_SLEEP; the generic provider dedups by timestamp+device.
        final long hwm = perDay ? 0L : getHwm(MoyoungRingConstants.HWM_SLEEP);
        final List<MoyoungRingPacket.SleepSegment> segments;
        if (perDay) {
            segments = all;
        } else {
            segments = new ArrayList<>(all.size());
            for (MoyoungRingPacket.SleepSegment s : all) {
                if (isNewRecord(s.startTimeMs, hwm)) segments.add(s);
            }
        }
        LOG.info("MoyoungRing sleep stages: {} segments ({} persisted), hwm={}", all.size(), segments.size(), hwm);
        if (segments.isEmpty()) return;
        boolean persisted = withDb((session, deviceId, userId) -> {
            GenericSleepStageSampleProvider genericProvider =
                    new GenericSleepStageSampleProvider(getDevice(), session);
            MoyoungSleepStageSampleProvider moyoungProvider =
                    new MoyoungSleepStageSampleProvider(getDevice(), session);
            List<GenericSleepStageSample> genericBatch = new ArrayList<>(segments.size());
            List<MoyoungSleepStageSample> moyoungBatch = new ArrayList<>(segments.size() + 1);
            long lastSegmentEndMs = 0L;
            int lastMoyoungStage = MoyoungConstants.SLEEP_SOBER;
            for (MoyoungRingPacket.SleepSegment s : segments) {
                genericBatch.add(new GenericSleepStageSample(
                        s.startTimeMs, deviceId, userId, s.durationSec, mapSleepStage(s.state)));
                lastMoyoungStage = mapMoyoungSleepStage(s.state);
                moyoungBatch.add(new MoyoungSleepStageSample(
                        s.startTimeMs, deviceId, userId, lastMoyoungStage));
                final long endMs = s.startTimeMs + (long) s.durationSec * 1000L;
                if (endMs > lastSegmentEndMs) lastSegmentEndMs = endMs;
            }
            if (lastSegmentEndMs > 0L && lastMoyoungStage != MoyoungConstants.SLEEP_SOBER) {
                moyoungBatch.add(new MoyoungSleepStageSample(
                        lastSegmentEndMs, deviceId, userId, MoyoungConstants.SLEEP_SOBER));
            }
            genericProvider.addSamples(genericBatch);
            moyoungProvider.addSamples(moyoungBatch);
        });
        if (!perDay) {
            long newest = 0L;
            for (MoyoungRingPacket.SleepSegment s : segments) if (s.startTimeMs > newest) newest = s.startTimeMs;
            if (persisted) advanceHwm(MoyoungRingConstants.HWM_SLEEP, newest);
        }
    }

    /**
     * Minute-of-day (0..1439) of the first stage record with a valid hh:mm, or
     * {@code -1} if none. Stage frames are {@code [count][state,hour,minute]...};
     * the first triplet is the onset. Used by the DK-7 fallback anchor.
     */
    private static int firstStageMinuteOfDay(byte[] payload) {
        if (payload == null) return -1;
        for (int i = 1; i + 3 <= payload.length; i += 3) {
            int hour = payload[i + 1] & 0xFF;
            int minute = payload[i + 2] & 0xFF;
            if (hour <= 23 && minute <= 59) return hour * 60 + minute;
        }
        return -1;
    }

    /**
     * Minute-of-day (0..1439) of the LAST stage record with a valid hh:mm, or
     * {@code -1} if none. Used to bound the fallback session end.
     */
    private static int lastStageMinuteOfDay(byte[] payload) {
        if (payload == null) return -1;
        int last = -1;
        for (int i = 1; i + 3 <= payload.length; i += 3) {
            int hour = payload[i + 1] & 0xFF;
            int minute = payload[i + 2] & 0xFF;
            if (hour <= 23 && minute <= 59) last = hour * 60 + minute;
        }
        return last;
    }

    /** True when a sleep window is sane (positive, forward, &lt;24h, not far future). */
    private static boolean isPlausibleSleepWindow(long startMs, long endMs) {
        if (startMs <= 0L || endMs <= startMs) return false;
        if (endMs - startMs > 24L * 3600_000L) return false;
        return endMs <= System.currentTimeMillis() + MoyoungRingConstants.HWM_FUTURE_TOLERANCE_MS;
    }

    /** Drop off-finger sentinels (0xFF) and physiologically implausible BP readings. */
    private static boolean isPlausibleBloodPressure(final int systolic, final int diastolic) {
        return systolic >= 60 && systolic <= 260
                && diastolic >= 30 && diastolic <= 200
                && systolic > diastolic;
    }

    /**
     * Stage-only fallback anchors can misread an evening awake fragment as sleep.
     * Without a real sleep-details anchor, skip short windows starting during the
     * usual local daytime/evening awake band while preserving overnight/early sleep.
     */
    private static boolean isLikelyAwakeDaytimeWindow(long startMs, long endMs) {
        if (startMs <= 0L || endMs <= startMs) return false;
        if (endMs - startMs >= 3L * 3600_000L) return false;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(startMs);
        final int hour = c.get(Calendar.HOUR_OF_DAY);
        return hour >= 10 && hour < 20;
    }

    @Override
    public void onConnectionStateChange(final BluetoothGatt gatt, final int status, final int newState) {
        super.onConnectionStateChange(gatt, status, newState);
        if (newState != BluetoothGatt.STATE_CONNECTED) {
            cancelSpotMeasurements();
        }
    }

    private static int mapSleepStage(int state) {
        switch (state) {
            case MoyoungRingPacket.SleepSegment.STATE_DEEP:  return ActivityKind.DEEP_SLEEP.getCode();
            case MoyoungRingPacket.SleepSegment.STATE_LIGHT: return ActivityKind.LIGHT_SLEEP.getCode();
            case MoyoungRingPacket.SleepSegment.STATE_REM:   return ActivityKind.REM_SLEEP.getCode();
            case MoyoungRingPacket.SleepSegment.STATE_AWAKE: return ActivityKind.AWAKE_SLEEP.getCode();
            default: return ActivityKind.UNKNOWN.getCode();
        }
    }

    private static int mapMoyoungSleepStage(int state) {
        switch (state) {
            case MoyoungRingPacket.SleepSegment.STATE_DEEP:  return MoyoungConstants.SLEEP_RESTFUL;
            case MoyoungRingPacket.SleepSegment.STATE_LIGHT: return MoyoungConstants.SLEEP_LIGHT;
            case MoyoungRingPacket.SleepSegment.STATE_REM:   return MoyoungConstants.SLEEP_REM;
            case MoyoungRingPacket.SleepSegment.STATE_AWAKE: return MoyoungConstants.SLEEP_SOBER;
            default: return MoyoungConstants.SLEEP_SOBER;
        }
    }

    // -------- Medical validation --------
    // All gating delegates to MoyoungRingConstants.plausible*(), which reject the
    // ring's off-finger sentinels (0 / 0xFF / 0xFFFF) BEFORE range-checking.

    private static boolean validHr(int bpm) {
        return MoyoungRingConstants.plausibleHr(bpm);
    }

    private static boolean validSpo2(int spo2) {
        return MoyoungRingConstants.plausibleSpo2(spo2);
    }

    private static boolean validHrv(int hrv) {
        return MoyoungRingConstants.plausibleHrv(hrv);
    }

    // -------- Derived daily insights --------

    /**
     * Compute and persist the evidence-based "Daily Health Insights" summary for
     * each recently-synced local day. Runs (debounced) after the history/timing
     * responses have persisted the day's raw samples via the generic providers.
     *
     * <p>For each day it reads that day's HR/HRV/SpO2/stress/sleep samples back
     * out of the DB, derives the wellness metrics via {@link MoyoungRingDailyInsights}
     * (which delegates all maths to the shared {@code DerivedHealthMetrics} engine),
     * and writes a single idempotent {@link BaseActivitySummary} keyed by
     * (deviceId, userId, dayStart, dayEnd). No schema changes; nothing is ever sent
     * to the ring.
     */
    private void computeDailyInsights() {
        LOG.info("MoyoungRing daily insights: starting compute for {} day(s)", INSIGHTS_DAYS_BACK);
        final ActivityUser user = new ActivityUser();
        final int ageYears = user.getAge();
        final int gender = user.getGender();
        final int[] daysComputed = new int[1];
        final int[] summariesWritten = new int[1];

        withDb((session, deviceId, userId) -> {
            final GenericHeartRateSampleProvider hrProvider =
                    new GenericHeartRateSampleProvider(getDevice(), session);
            final GenericHrvValueSampleProvider hrvProvider =
                    new GenericHrvValueSampleProvider(getDevice(), session);
            final GenericSpo2SampleProvider spo2Provider =
                    new GenericSpo2SampleProvider(getDevice(), session);
            final GenericStressSampleProvider stressProvider =
                    new GenericStressSampleProvider(getDevice(), session);
            final GenericTemperatureSampleProvider tempProvider =
                    new GenericTemperatureSampleProvider(getDevice(), session);
            final GenericSleepStageSampleProvider sleepProvider =
                    new GenericSleepStageSampleProvider(getDevice(), session);
            final MoyoungActivitySampleProvider stepProvider =
                    new MoyoungActivitySampleProvider(getDevice(), session);
            final BaseActivitySummaryDao summaryDao = session.getBaseActivitySummaryDao();

            for (int back = 0; back < INSIGHTS_DAYS_BACK; back++) {
                final Calendar c = Calendar.getInstance();
                c.set(Calendar.HOUR_OF_DAY, 0);
                c.set(Calendar.MINUTE, 0);
                c.set(Calendar.SECOND, 0);
                c.set(Calendar.MILLISECOND, 0);
                c.add(Calendar.DAY_OF_YEAR, -back);
                final long dayStart = c.getTimeInMillis();
                final long dayEnd = dayStart + 24L * 3600_000L;
                // Widen the read window before midnight so the prior night's sleep
                // (and its nocturnal HR/SpO2/HRV) is captured for this day.
                final long readStart = dayStart - 12L * 3600_000L;

                final List<GenericHeartRateSample> hr = hrProvider.getAllSamples(readStart, dayEnd);
                if (hr == null || hr.isEmpty()) {
                    continue; // no HR for the day -> nothing worth deriving
                }
                final List<GenericHrvValueSample> hrvSamples = hrvProvider.getAllSamples(readStart, dayEnd);
                final List<GenericSpo2Sample> spo2Samples = spo2Provider.getAllSamples(readStart, dayEnd);
                final List<GenericStressSample> stressSamples = stressProvider.getAllSamples(dayStart, dayEnd);
                final List<GenericTemperatureSample> tempSamples = tempProvider.getAllSamples(readStart, dayEnd);
                final List<GenericSleepStageSample> sleepSamples = sleepProvider.getAllSamples(readStart, dayEnd);

                // Adapt DB rows to the derived-metric input types.
                final List<MoyoungRingPacket.HrvRecord> hrv = new ArrayList<>();
                if (hrvSamples != null) {
                    for (GenericHrvValueSample s : hrvSamples) {
                        hrv.add(new MoyoungRingPacket.HrvRecord(s.getTimestamp(), s.getValue()));
                    }
                }
                final List<MoyoungRingPacket.Spo2Record> spo2 = new ArrayList<>();
                if (spo2Samples != null) {
                    for (GenericSpo2Sample s : spo2Samples) {
                        spo2.add(new MoyoungRingPacket.Spo2Record(s.getTimestamp(), s.getSpo2()));
                    }
                }
                final List<Integer> stress = new ArrayList<>();
                if (stressSamples != null) {
                    for (GenericStressSample s : stressSamples) stress.add(s.getStress());
                }
                final List<SleepSession> sleepSessions = toSleepSessions(sleepSamples, readStart, dayEnd);

                // Per-30-min step histogram for the day (from the persisted step
                // samples) + the day's total, for the MET-min and percentile insights.
                final int[] stepSlots = new int[MoyoungRingConstants.STEPS_SLOTS_PER_DAY];
                int totalDaySteps = 0;
                final List<MoyoungActivitySample> stepSamples = stepProvider.getAllActivitySamples(
                        (int) (dayStart / 1000L), (int) (dayEnd / 1000L));
                if (stepSamples != null) {
                    for (final MoyoungActivitySample s : stepSamples) {
                        final int st = s.getSteps();
                        if (st <= 0) continue;
                        final long tsMs = (long) s.getTimestamp() * 1000L;
                        final int slot = (int) ((tsMs - dayStart)
                                / (MoyoungRingConstants.STEPS_SLOT_MINUTES * 60_000L));
                        if (slot >= 0 && slot < stepSlots.length) {
                            stepSlots[slot] += st;
                        }
                        totalDaySteps += st;
                    }
                }

                // Trailing personal baselines: average the per-day values across the prior 7 days.
                final long baselineStart = dayStart - (long) INSIGHTS_BASELINE_DAYS * 24L * 3600_000L;
                final DailyInsightBaseline baseline = computeInsightBaseline(
                        hrProvider, hrvProvider, spo2Provider, stressProvider, tempProvider,
                        sleepProvider, stepProvider, baselineStart, dayStart);
                final double hrvBaseline = baseline.hrvMs;
                final int restingBaseline = Double.isFinite(baseline.restingHr)
                        ? (int) Math.round(baseline.restingHr) : -1;

                daysComputed[0]++;
                final ActivitySummaryData data = MoyoungRingDailyInsights.compute(
                        hr, sleepSessions, hrv, hrvBaseline, spo2, stress, ageYears, restingBaseline,
                        stepSlots, totalDaySteps, gender);
                final double todayHrv = meanHrvMs(hrvSamples);
                final double todaySpo2 = meanSpo2(spo2Samples);
                final double todayStress = meanStress(stressSamples);
                final double todayTemp = meanTemperature(tempSamples);
                final double todaySleepMinutes = sleepMinutes(sleepSessions);
                final double todayRespiration = DerivedHealthMetrics.respiratoryRateProxy(hr);
                MoyoungRingDailyInsights.buildDeviationInsights(
                        data,
                        meanHr(hr, dayStart, dayEnd), baseline.meanHr,
                        percentile(hr, 0.05), baseline.restingHr,
                        todayHrv, baseline.hrvMs,
                        todayTemp, baseline.skinTempC,
                        todaySpo2, baseline.spo2Pct,
                        todaySleepMinutes, baseline.sleepMinutes,
                        totalDaySteps, baseline.steps,
                        todayStress, baseline.stress,
                        todayRespiration, baseline.respiration,
                        baseline.isReady());
                if (data.getKeys().isEmpty()) {
                    continue; // insufficient data for any derived metric
                }

                if (persistDailyInsight(summaryDao, deviceId, userId, dayStart, dayEnd, data)) {
                    summariesWritten[0]++;
                }
            }
        });
        LOG.info("MoyoungRing daily insights: computed {} day(s), wrote {} summary(ies)",
                daysComputed[0], summariesWritten[0]);
    }

    /** Insert-or-update the single per-day insights summary (idempotent by window). */
    private boolean persistDailyInsight(final BaseActivitySummaryDao summaryDao,
                                        final long deviceId, final long userId,
                                        final long dayStart, final long dayEnd,
                                        final ActivitySummaryData data) {
        final Date startTime = new Date(dayStart);
        final Date endTime = new Date(dayEnd);
        final List<BaseActivitySummary> existing = summaryDao.queryBuilder()
                .where(BaseActivitySummaryDao.Properties.DeviceId.eq(deviceId),
                        BaseActivitySummaryDao.Properties.UserId.eq(userId),
                        BaseActivitySummaryDao.Properties.StartTime.eq(startTime),
                        BaseActivitySummaryDao.Properties.EndTime.eq(endTime))
                .list();
        final BaseActivitySummary summary = (existing != null && !existing.isEmpty())
                ? existing.get(0) : new BaseActivitySummary();
        summary.setDeviceId(deviceId);
        summary.setUserId(userId);
        summary.setName("Daily Health Insights");
        summary.setActivityKind(ActivityKind.UNKNOWN.getCode());
        summary.setStartTime(startTime);
        summary.setEndTime(endTime);
        summary.setSummaryData(data.toString());
        summaryDao.insertOrReplace(summary);
        LOG.info("MoyoungRing daily insight: day={} {}={} {}={}", startTime,
                ActivitySummaryEntries.MET_MINUTES,
                data.getNumber(ActivitySummaryEntries.MET_MINUTES, null),
                ActivitySummaryEntries.STEPS_AGE_PERCENTILE,
                data.getNumber(ActivitySummaryEntries.STEPS_AGE_PERCENTILE, null));
        return true;
    }

    /** Group time-ordered sleep-stage rows within [start,end) into sleep sessions. */
    private static List<SleepSession> toSleepSessions(final List<GenericSleepStageSample> samples,
                                                      final long start, final long end) {
        final List<SleepSession> out = new ArrayList<>();
        if (samples == null || samples.isEmpty()) return out;
        SleepSession current = null;
        long lastEnd = 0L;
        for (GenericSleepStageSample s : samples) {
            final long ts = s.getTimestamp();
            if (ts < start || ts >= end) continue;
            final int durSec = s.getDuration();
            // Start a new session if there's a >2h gap from the previous stage.
            if (current == null || ts - lastEnd > 2L * 3600_000L) {
                current = new SleepSession();
                current.startTimeMs = ts;
                out.add(current);
            }
            final int stageType = mapDbStageToDerived(s.getStage());
            current.stages.add(new SleepStage(stageType, ts, durSec));
            switch (stageType) {
                case SleepStage.TYPE_DEEP:  current.deepSleepSec += durSec; current.deepSleepCount++; break;
                case SleepStage.TYPE_LIGHT: current.lightSleepSec += durSec; current.lightSleepCount++; break;
                case SleepStage.TYPE_REM:   current.remSleepSec += durSec; break;
                default:                    current.wakeDurationSec += durSec; current.wakeCount++; break;
            }
            lastEnd = ts + (long) durSec * 1000L;
            current.endTimeMs = lastEnd;
        }
        return out;
    }

    /** Map a persisted {@link ActivityKind} sleep-stage code to a neutral {@link SleepStage} type. */
    private static int mapDbStageToDerived(final int activityKindCode) {
        if (activityKindCode == ActivityKind.DEEP_SLEEP.getCode())  return SleepStage.TYPE_DEEP;
        if (activityKindCode == ActivityKind.LIGHT_SLEEP.getCode()) return SleepStage.TYPE_LIGHT;
        if (activityKindCode == ActivityKind.REM_SLEEP.getCode())   return SleepStage.TYPE_REM;
        return SleepStage.TYPE_AWAKE;
    }

    /** Compute per-metric 7-day baseline averages from daily values, requiring 3+ days per metric. */
    private static DailyInsightBaseline computeInsightBaseline(
            final GenericHeartRateSampleProvider hrProvider,
            final GenericHrvValueSampleProvider hrvProvider,
            final GenericSpo2SampleProvider spo2Provider,
            final GenericStressSampleProvider stressProvider,
            final GenericTemperatureSampleProvider tempProvider,
            final GenericSleepStageSampleProvider sleepProvider,
            final MoyoungActivitySampleProvider stepProvider,
            final long baselineStart,
            final long baselineEnd) {
        final DailyInsightBaseline baseline = new DailyInsightBaseline();
        final long dayMs = 24L * 3600_000L;
        for (long dayStart = baselineStart; dayStart < baselineEnd; dayStart += dayMs) {
            final long dayEnd = dayStart + dayMs;
            final long readStart = dayStart - 12L * 3600_000L;
            final List<GenericHeartRateSample> hr = hrProvider.getAllSamples(readStart, dayEnd);
            baseline.accMeanHr.add(meanHr(hr, dayStart, dayEnd));
            baseline.accRestingHr.add(percentile(hr, 0.05));
            baseline.accHrvMs.add(meanHrvMs(hrvProvider.getAllSamples(readStart, dayEnd)));
            baseline.accSpo2Pct.add(meanSpo2(spo2Provider.getAllSamples(readStart, dayEnd)));
            baseline.accStress.add(meanStress(stressProvider.getAllSamples(dayStart, dayEnd)));
            baseline.accSkinTempC.add(meanTemperature(tempProvider.getAllSamples(readStart, dayEnd)));
            baseline.accSleepMinutes.add(sleepMinutes(toSleepSessions(
                    sleepProvider.getAllSamples(readStart, dayEnd), readStart, dayEnd)));
            baseline.accSteps.add(totalSteps(stepProvider.getAllActivitySamples(
                    (int) (dayStart / 1000L), (int) (dayEnd / 1000L))));
            baseline.accRespiration.add(DerivedHealthMetrics.respiratoryRateProxy(hr));
        }
        baseline.finish();
        return baseline;
    }

    /** Mean of plausible HRV values (ms); 0 when none. */
    private static double meanHrvMs(final List<GenericHrvValueSample> samples) {
        if (samples == null || samples.isEmpty()) return 0d;
        long sum = 0; int n = 0;
        for (GenericHrvValueSample s : samples) {
            final int v = s.getValue();
            if (v >= 3 && v <= 200) { sum += v; n++; }
        }
        return n == 0 ? 0d : (double) sum / n;
    }

    /** Mean of plausible HR samples inside [start,end); NaN when none. */
    private static double meanHr(final List<GenericHeartRateSample> samples, final long start, final long end) {
        if (samples == null || samples.isEmpty()) return Double.NaN;
        long sum = 0; int n = 0;
        for (GenericHeartRateSample s : samples) {
            final long ts = s.getTimestamp();
            final int bpm = s.getHeartRate();
            if (ts >= start && ts < end && MoyoungRingConstants.plausibleHr(bpm)) {
                sum += bpm;
                n++;
            }
        }
        return n == 0 ? Double.NaN : (double) sum / n;
    }

    /** Mean of plausible SpO2 samples; NaN when none. */
    private static double meanSpo2(final List<GenericSpo2Sample> samples) {
        if (samples == null || samples.isEmpty()) return Double.NaN;
        long sum = 0; int n = 0;
        for (GenericSpo2Sample s : samples) {
            final int v = s.getSpo2();
            if (MoyoungRingConstants.plausibleSpo2(v)) {
                sum += v;
                n++;
            }
        }
        return n == 0 ? Double.NaN : (double) sum / n;
    }

    /** Mean of plausible skin-temperature samples in Celsius; NaN when none. */
    private static double meanTemperature(final List<GenericTemperatureSample> samples) {
        if (samples == null || samples.isEmpty()) return Double.NaN;
        double sum = 0d; int n = 0;
        for (GenericTemperatureSample s : samples) {
            final double v = s.getTemperature();
            if (v >= MoyoungRingConstants.SKIN_TEMP_MIN && v <= MoyoungRingConstants.SKIN_TEMP_MAX) {
                sum += v;
                n++;
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    /** Mean of plausible stress samples; NaN when none. */
    private static double meanStress(final List<GenericStressSample> samples) {
        if (samples == null || samples.isEmpty()) return Double.NaN;
        long sum = 0; int n = 0;
        for (GenericStressSample s : samples) {
            final int v = s.getStress();
            if (v >= 1 && v <= 100) {
                sum += v;
                n++;
            }
        }
        return n == 0 ? Double.NaN : (double) sum / n;
    }

    /** Total asleep minutes across sleep sessions; NaN when no sleep data. */
    private static double sleepMinutes(final List<SleepSession> sessions) {
        if (sessions == null || sessions.isEmpty()) return Double.NaN;
        long seconds = 0L;
        for (SleepSession s : sessions) {
            seconds += s.deepSleepSec + s.lightSleepSec + s.remSleepSec;
        }
        return seconds > 0L ? seconds / 60d : Double.NaN;
    }

    /** Total steps from daily step samples; NaN when no step samples. */
    private static double totalSteps(final List<MoyoungActivitySample> samples) {
        if (samples == null || samples.isEmpty()) return Double.NaN;
        long steps = 0L;
        for (MoyoungActivitySample s : samples) {
            final int v = s.getSteps();
            if (v > 0) steps += v;
        }
        return steps;
    }

    /** p-th percentile of HR samples (bpm); -1 when empty. */
    private static int percentile(final List<GenericHeartRateSample> samples, final double p) {
        if (samples == null || samples.isEmpty()) return -1;
        final List<Integer> bpms = new ArrayList<>(samples.size());
        for (GenericHeartRateSample s : samples) {
            if (MoyoungRingConstants.plausibleHr(s.getHeartRate())) bpms.add(s.getHeartRate());
        }
        if (bpms.isEmpty()) return -1;
        java.util.Collections.sort(bpms);
        final int idx = Math.max(0, Math.min(bpms.size() - 1, (int) Math.floor(bpms.size() * p)));
        return bpms.get(idx);
    }

    private static final class DailyInsightBaseline {
        private final BaselineAccumulator accMeanHr = new BaselineAccumulator();
        private final BaselineAccumulator accRestingHr = new BaselineAccumulator();
        private final BaselineAccumulator accHrvMs = new BaselineAccumulator();
        private final BaselineAccumulator accSpo2Pct = new BaselineAccumulator();
        private final BaselineAccumulator accSkinTempC = new BaselineAccumulator();
        private final BaselineAccumulator accStress = new BaselineAccumulator();
        private final BaselineAccumulator accSleepMinutes = new BaselineAccumulator();
        private final BaselineAccumulator accSteps = new BaselineAccumulator();
        private final BaselineAccumulator accRespiration = new BaselineAccumulator();

        private double meanHr;
        private double restingHr;
        private double hrvMs;
        private double spo2Pct;
        private double skinTempC;
        private double stress;
        private double sleepMinutes;
        private double steps;
        private double respiration;
        private boolean ready;

        private void finish() {
            meanHr = accMeanHr.averageIfReady();
            restingHr = accRestingHr.averageIfReady();
            hrvMs = accHrvMs.averageIfReady();
            spo2Pct = accSpo2Pct.averageIfReady();
            skinTempC = accSkinTempC.averageIfReady();
            stress = accStress.averageIfReady();
            sleepMinutes = accSleepMinutes.averageIfReady();
            steps = accSteps.averageIfReady();
            respiration = accRespiration.averageIfReady();
            ready = Double.isFinite(meanHr)
                    || Double.isFinite(restingHr)
                    || Double.isFinite(hrvMs)
                    || Double.isFinite(spo2Pct)
                    || Double.isFinite(skinTempC)
                    || Double.isFinite(stress)
                    || Double.isFinite(sleepMinutes)
                    || Double.isFinite(steps)
                    || Double.isFinite(respiration);
        }

        private boolean isReady() {
            return ready;
        }
    }

    private static final class BaselineAccumulator {
        private double sum;
        private int count;

        private void add(final double value) {
            if (Double.isFinite(value) && value > 0d) {
                sum += value;
                count++;
            }
        }

        private double averageIfReady() {
            return count >= INSIGHTS_MIN_BASELINE_DAYS ? sum / count : Double.NaN;
        }
    }

    // -------- Persistence helpers --------

    private boolean withDb(DbAction action) {
        try (DBHandler db = GBApplication.acquireDB()) {
            DaoSession session = db.getDaoSession();
            Long userId = DBHelper.getUser(session).getId();
            Long deviceId = DBHelper.getDevice(getDevice(), session).getId();
            action.run(session, deviceId, userId);
            signalNewDataDebounced();
            return true;
        } catch (Exception e) {
            LOG.error("MoyoungRing DB action failed", e);
            return false;
        }
    }

    private final Runnable newDataSignalRunnable = new Runnable() {
        @Override public void run() {
            try {
                if (getDevice() != null) {
                    GB.signalActivityDataFinish(getDevice());
                }
            } catch (Exception e) {
                LOG.warn("MoyoungRing signalActivityDataFinish failed", e);
            }
        }
    };

    /**
     * Notify the rest of Gadgetbridge that fresh samples were stored. This broadcasts
     * ACTION_NEW_DATA (with the device), which refreshes the dashboard/widgets AND — the
     * important part — triggers the Health Connect sync worker (see {@code NewDataReceiver}).
     * Without this signal the ring's history and spot samples never reach Health Connect.
     * Debounced so a large history sync or a full spot-measurement round coalesces into one
     * broadcast instead of hundreds.
     */
    private void signalNewDataDebounced() {
        handler.removeCallbacks(newDataSignalRunnable);
        handler.postDelayed(newDataSignalRunnable, 2500L);
    }

    @FunctionalInterface
    private interface DbAction {
        void run(DaoSession session, long deviceId, long userId) throws Exception;
    }

    private void persistHr(long timestampMs, int bpm) {
        if (!validHr(bpm)) {
            LOG.debug("MoyoungRing persistHr: rejected bpm={}", bpm);
            return;
        }
        emitRealtimeHr(timestampMs, bpm);
        List<GenericHeartRateSample> toFlush = null;
        synchronized (hrBufferLock) {
            hrBuffer.add(new GenericHeartRateSample(timestampMs, 0L, 0L, bpm));
            long now = System.currentTimeMillis();
            boolean sizeReady = hrBuffer.size() >= HR_BUFFER_FLUSH_THRESHOLD;
            boolean timeReady = hrBufferLastFlushMs > 0 && (now - hrBufferLastFlushMs) >= HR_BUFFER_FLUSH_INTERVAL_MS;
            if (sizeReady || timeReady) {
                toFlush = new ArrayList<>(hrBuffer);
                hrBuffer.clear();
                hrBufferLastFlushMs = now;
            } else if (hrBufferLastFlushMs == 0L) {
                hrBufferLastFlushMs = now;
            }
        }
        if (toFlush != null) flushHrBuffer(toFlush);
    }

    private void flushHrBuffer(final List<GenericHeartRateSample> buf) {
        if (buf.isEmpty()) return;
        withDb((session, deviceId, userId) -> {
            GenericHeartRateSampleProvider provider =
                    new GenericHeartRateSampleProvider(getDevice(), session);
            List<GenericHeartRateSample> renumbered = new ArrayList<>(buf.size());
            for (GenericHeartRateSample s : buf) {
                renumbered.add(new GenericHeartRateSample(
                        s.getTimestamp(), deviceId, userId, s.getHeartRate()));
            }
            provider.addSamples(renumbered);
        });
    }

    private void emitRealtimeHr(long timestampMs, int bpm) {
        try {
            GenericActivitySample sample = new RealtimeHrSample();
            sample.setTimestamp((int) (timestampMs / 1000L));
            sample.setHeartRate(bpm);
            Intent intent = new Intent(DeviceService.ACTION_REALTIME_SAMPLES)
                    .putExtra(GBDevice.EXTRA_DEVICE, getDevice())
                    .putExtra(DeviceService.EXTRA_REALTIME_SAMPLE, (java.io.Serializable) sample)
                    .putExtra(DeviceService.EXTRA_TIMESTAMP, sample.getTimestamp());
            LocalBroadcastManager.getInstance(getContext()).sendBroadcast(intent);
        } catch (Exception e) {
            LOG.debug("MoyoungRing realtime HR broadcast failed", e);
        }
    }

    private static class RealtimeHrSample extends GenericActivitySample implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
    }

    private void persistSpo2(long timestampMs, int pct) {
        if (!validSpo2(pct)) {
            LOG.debug("MoyoungRing persistSpo2: rejected pct={}", pct);
            return;
        }
        withDb((session, deviceId, userId) ->
                new GenericSpo2SampleProvider(getDevice(), session)
                        .addSample(new GenericSpo2Sample(timestampMs, deviceId, userId, pct)));
    }

    private void persistHrv(long timestampMs, int hrv) {
        if (!validHrv(hrv)) {
            LOG.debug("MoyoungRing persistHrv: rejected hrv={}", hrv);
            return;
        }
        withDb((session, deviceId, userId) ->
                new GenericHrvValueSampleProvider(getDevice(), session)
                        .addSample(new GenericHrvValueSample(timestampMs, deviceId, userId, hrv)));
    }

    /**
     * Persist a stress reading. NOTE: the ring's stress score is a non-medical
     * wellness ESTIMATE, not a validated measurement. Rejects 0 / 255 sentinels
     * before persisting (see {@link MoyoungRingConstants#plausibleStress(int)}).
     */
    private void persistStress(long timestampMs, int stress) {
        if (!MoyoungRingConstants.plausibleStress(stress)) {
            LOG.debug("MoyoungRing persistStress: rejected stress={}", stress);
            return;
        }
        withDb((session, deviceId, userId) ->
                new GenericStressSampleProvider(getDevice(), session)
                        .addSample(new GenericStressSample(timestampMs, deviceId, userId, stress)));
    }

    /**
     * Persist a blood-pressure reading. NOTE: the ring's BP is a non-medical
     * wellness ESTIMATE, not a validated measurement.
     */
    private void persistBp(long timestampMs, int systolic, int diastolic, int hr) {
        if (!MoyoungRingConstants.plausibleBp(systolic, diastolic)) {
            LOG.debug("MoyoungRing persistBp: rejected sys={} dia={}", systolic, diastolic);
            return;
        }
        final Integer hrOrNull = hr > 0 ? hr : null;
        withDb((session, deviceId, userId) ->
                new GenericBloodPressureSampleProvider(getDevice(), session)
                        .addSample(new GenericBloodPressureSample(
                                timestampMs, deviceId, userId,
                                systolic, diastolic, null, null, hrOrNull, 0)));
        if (hr > 0) persistHr(timestampMs, hr);
    }

    /**
     * Persist a skin-temperature reading (°C). Rejects the ring's sentinels and
     * keeps a plausible finger-skin range; exports to Health Connect as a
     * SkinTemperatureRecord.
     */
    private void persistSkinTemp(long timestampMs, double celsius) {
        if (!(celsius >= MoyoungRingConstants.SKIN_TEMP_MIN
                && celsius <= MoyoungRingConstants.SKIN_TEMP_MAX)) {
            LOG.debug("MoyoungRing persistSkinTemp: rejected {}C", celsius);
            return;
        }
        withDb((session, deviceId, userId) ->
                new GenericTemperatureSampleProvider(getDevice(), session)
                        .addSample(new GenericTemperatureSample(
                                timestampMs, deviceId, userId, (float) celsius,
                                TemperatureSample.TYPE_SKIN, TemperatureSample.LOCATION_FINGER)));
    }

    // -------- Public APIs --------

    @Override
    public void onSetTime() {
        try {
            TransactionBuilder b = createTransactionBuilder("MoyoungRing set time");
            writeCommand(b, buildSetTime());
            b.queue();
        } catch (Exception e) {
            LOG.warn("MoyoungRing onSetTime failed", e);
        }
    }

    @Override
    public void onHeartRateTest() {
        try {
            TransactionBuilder b = createTransactionBuilder("MoyoungRing manual HR");
            handler.removeCallbacks(stopManualHeartRateRunnable);
            writeCommand(b, MoyoungRingPacket.liveMeasure(
                    MoyoungRingConstants.CMD_LIVE_HR, MoyoungRingConstants.SUB_LIVE_HR, true));
            b.queue();
            handler.postDelayed(stopManualHeartRateRunnable, 30_000L);
        } catch (Exception e) {
            LOG.warn("MoyoungRing onHeartRateTest failed", e);
        }
    }

    /**
     * Live-reporting mode ("mode 2"): stream heart rate in real time during an
     * activity. Driven by Gadgetbridge's Live Activity screen, which supplies the
     * start (enable=true) and stop (enable=false). Enabling puts the ring into
     * realtime push (cmd 9) and starts continuous HR (cmd 1/9); every reading is
     * broadcast to the Live Activity UI via {@link #emitRealtimeHr} (see the
     * {@code OP_LIVE_HR}/{@code 2A37} handlers). The ring only streams HR for a
     * bounded window per trigger, so a keep-alive re-arms it until the user stops.
     */
    @Override
    public void onEnableRealtimeHeartRateMeasurement(boolean enable) {
        // Refresh the UI heartbeat on EVERY request (the Live Activity screen pulses
        // enable=true ~1x/s); this is what lets the keep-alive detect a dead screen.
        if (enable) lastRealtimeRequestMs = System.currentTimeMillis();
        if (realtimeHrEnabled == enable) return;
        realtimeHrEnabled = enable;
        // The spot measurements (SpO2/stress/BP/temp) share the SAME PPG sensor as the
        // live-HR stream. PAUSE any spot rotation while live HR ("live mode") is on; when
        // live HR stops, offer a gated auto-poll (runs only if awake/daytime and due) so we
        // never resume continuous background polling.
        if (enable) {
            cancelSpotMeasurements();
        } else {
            maybeAutoPoll("livehr-stop");
        }
        handler.removeCallbacks(realtimeHrKeepAlive);
        if (enable) {
            // A live activity overrides the one-shot HR test's 30 s auto-stop.
            handler.removeCallbacks(stopManualHeartRateRunnable);
        }
        // Manage ONLY the workout push here; then send the continuous-HR command
        // (1/9) EXACTLY ONCE reflecting the new state. This decouples HR from step
        // toggles so a step toggle never restarts the optical sensor (which would
        // thrash it into returning the 0xFF "no reading" sentinel).
        updateRealtimePush();
        try {
            TransactionBuilder b = createTransactionBuilder("MoyoungRing realtime HR");
            writeCommand(b, MoyoungRingPacket.liveMeasure(
                    MoyoungRingConstants.CMD_LIVE_HR, MoyoungRingConstants.SUB_LIVE_HR, enable));
            b.queue();
        } catch (Exception e) {
            LOG.warn("MoyoungRing onEnableRealtimeHeartRateMeasurement failed", e);
        }
        if (enable) {
            handler.postDelayed(realtimeHrKeepAlive, REALTIME_HR_KEEPALIVE_MS);
        }
    }

    /**
     * Live step streaming during an activity (paired with the HR live-report). The
     * ring pushes live step/calorie/distance triples on fdd1 while realtime push
     * (cmd 9) is on; see {@link #handleLiveSteps}.
     */
    @Override
    public void onEnableRealtimeSteps(boolean enable) {
        if (realtimeStepsEnabled == enable) return;
        realtimeStepsEnabled = enable;
        lastRealtimeSteps = -1; // re-baseline the cumulative->delta conversion
        // ONLY manage the workout push; NEVER touch the continuous-HR command (1/9),
        // so toggling steps can't restart/stop the HR sensor.
        updateRealtimePush();
    }

    /**
     * Manage the ring's realtime push (cmd 9) ONLY: turn it on when either live HR
     * or live steps is requested and off when neither is. This does NOT send the
     * continuous-HR command (cmd 1/9) — that is managed exclusively by
     * {@link #onEnableRealtimeHeartRateMeasurement} so step toggles never disturb
     * the HR sensor. Idempotent — safe to call on every toggle and after reconnect.
     */
    private void updateRealtimePush() {
        synchronized (realtimeLock) {
            try {
                final boolean anyOn = realtimeHrEnabled || realtimeStepsEnabled;
                if (anyOn) {
                    handler.removeCallbacks(stopRealtimePushRunnable);
                    if (!realtimePushActive) {
                        // Start the workout session (9/1 {walking}) then enable realtime push
                        // (9/0 {01}). HR streams from 1/9 alone, but the live-step (fdd1) push
                        // needs the session; both values are verified from the app capture.
                        TransactionBuilder b = createTransactionBuilder("MoyoungRing realtime push on");
                        writeCommand(b, MoyoungRingPacket.encode(MoyoungRingConstants.CMD_WORKOUT,
                                MoyoungRingConstants.SUB_WORKOUT_TYPE,
                                new byte[]{ (byte) MoyoungRingConstants.SPORT_TYPE_WALKING }));
                        writeCommand(b, MoyoungRingPacket.encode(MoyoungRingConstants.CMD_WORKOUT,
                                MoyoungRingConstants.SUB_WORKOUT_PUSH, new byte[]{ 0x01 }));
                        b.queue();
                        realtimePushActive = true;
                    }
                } else if (realtimePushActive) {
                    handler.removeCallbacks(stopRealtimePushRunnable);
                    handler.postDelayed(stopRealtimePushRunnable, REALTIME_PUSH_STOP_DEBOUNCE_MS);
                }
            } catch (Exception e) {
                LOG.warn("MoyoungRing updateRealtimePush failed", e);
            }
        }
    }

    private void stopRealtimePushIfStillIdle() {
        synchronized (realtimeLock) {
            if (realtimeHrEnabled || realtimeStepsEnabled || !realtimePushActive) {
                return;
            }
            try {
                TransactionBuilder b = createTransactionBuilder("MoyoungRing realtime push off");
                writeCommand(b, MoyoungRingPacket.encode(MoyoungRingConstants.CMD_WORKOUT,
                        MoyoungRingConstants.SUB_WORKOUT_PUSH, new byte[]{ 0x00 }));
                b.queue();
                realtimePushActive = false;
            } catch (Exception e) {
                LOG.warn("MoyoungRing realtime push stop failed", e);
            }
        }
    }

    @Override
    public void onFetchRecordedData(int dataTypes) {
        try {
            // Collect the requested read frames and dispatch them ONE AT A TIME via
            // the paced sync dispatcher (the ring drops burst paged-timeline reads).
            // INCREMENTAL: timelines request only the first page still needing data
            // (the handler chains forward), sleep/steps skip PAST days already synced.
            final long now = System.currentTimeMillis();
            final TimeZone tz = TimeZone.getDefault();
            final int daysBack = MoyoungRingConstants.TIMING_DAYS_BACK;
            final java.util.List<byte[]> frames = new java.util.ArrayList<>();
            if ((dataTypes & RecordedDataTypes.TYPE_HEART_RATE) != 0) {
                frames.add(MoyoungRingPacket.encode(
                        MoyoungRingConstants.CMD_HIST_HR, MoyoungRingConstants.SUB_HIST_HR));
                appendTimelineFrames(frames, MoyoungRingConstants.OP_TIMING_HR,
                        MoyoungRingConstants.CMD_TIMING_HR, MoyoungRingConstants.SUB_TIMING_HR,
                        daysBack, now, tz);
            }
            if ((dataTypes & RecordedDataTypes.TYPE_HRV) != 0) {
                // HRV is its own recorded-data type; fetch its LIST (2/10) + paged
                // timeline (2/16) so the HRV chart fills on an HRV-only sync too.
                frames.add(MoyoungRingPacket.encode(
                        MoyoungRingConstants.CMD_HIST_HRV, MoyoungRingConstants.SUB_HIST_HRV));
                appendTimelineFrames(frames, MoyoungRingConstants.OP_TIMING_HRV,
                        MoyoungRingConstants.CMD_TIMING_HRV, MoyoungRingConstants.SUB_TIMING_HRV,
                        daysBack, now, tz);
            }
            if ((dataTypes & RecordedDataTypes.TYPE_SPO2) != 0) {
                frames.add(MoyoungRingPacket.encode(
                        MoyoungRingConstants.CMD_HIST_SPO2, MoyoungRingConstants.SUB_HIST_SPO2));
                appendTimelineFrames(frames, MoyoungRingConstants.OP_TIMING_SPO2,
                        MoyoungRingConstants.CMD_TIMING_SPO2, MoyoungRingConstants.SUB_TIMING_SPO2,
                        daysBack, now, tz);
            }
            if ((dataTypes & RecordedDataTypes.TYPE_STRESS) != 0) {
                frames.add(MoyoungRingPacket.encode(
                        MoyoungRingConstants.CMD_HIST_STRESS, MoyoungRingConstants.SUB_HIST_STRESS));
                appendTimelineFrames(frames, MoyoungRingConstants.OP_TIMING_STRESS,
                        MoyoungRingConstants.CMD_TIMING_STRESS, MoyoungRingConstants.SUB_TIMING_STRESS,
                        daysBack, now, tz);
            }
            if ((dataTypes & RecordedDataTypes.TYPE_ACTIVITY) != 0) {
                // Daily total (2/13) + per-slot histogram (2/18); skip PAST days synced.
                appendStepsFrames(frames, daysBack, now, tz);
            }
            if ((dataTypes & RecordedDataTypes.TYPE_TEMPERATURE) != 0) {
                // Temperature daily timeline (2/22): per-page incremental, u16/10 °C.
                appendTimelineFrames(frames, MoyoungRingConstants.OP_TIMING_TEMP,
                        MoyoungRingConstants.CMD_TIMING_TEMP, MoyoungRingConstants.SUB_TIMING_TEMP,
                        daysBack, now, tz);
            }
            if ((dataTypes & RecordedDataTypes.TYPE_SLEEP) != 0) {
                // Per-day sleep STAGE requests (2/14); skip PAST days already synced.
                appendSleepFrames(frames, daysBack, now, tz);
            }
            if (!frames.isEmpty()) {
                enqueueSync(frames);
            } else {
                LOG.debug("MoyoungRing onFetchRecordedData: bitmask 0x{} matched nothing (or all synced)",
                        Integer.toHexString(dataTypes));
            }

            // Refresh the SPOT metrics (SpO2/HRV/stress/BP/temp) in addition to reading
            // stored history. History reads don't touch the PPG sensor, but spot measurements
            // do — so they must respect the sleep-protection policy.
            if (dataTypes == RecordedDataTypes.TYPE_SYNC || dataTypes == RecordedDataTypes.TYPE_ALL) {
                // "Sync all" (device-card button / background auto-fetch). Indistinguishable
                // from an automatic fetch, so route through the awake/night/quota gate: this
                // guarantees a background sync never spins up the PPG at night.
                handler.postDelayed(() -> maybeAutoPoll("fetch-sync"), 1_500L);
            } else {
                // A per-tab refresh (only ever user-initiated) — fire ONE fresh spot for the
                // specific metric so tapping refresh on e.g. the SpO2 tab measures SpO2.
                final java.util.List<int[]> spots = new java.util.ArrayList<>();
                if ((dataTypes & RecordedDataTypes.TYPE_SPO2) != 0) {
                    spots.add(new int[]{ MoyoungRingConstants.CMD_LIVE_SPO2, MoyoungRingConstants.SUB_LIVE_SPO2 });
                }
                if ((dataTypes & RecordedDataTypes.TYPE_HRV) != 0) {
                    spots.add(new int[]{ MoyoungRingConstants.CMD_LIVE_HRV, MoyoungRingConstants.SUB_LIVE_HRV });
                }
                if ((dataTypes & RecordedDataTypes.TYPE_STRESS) != 0) {
                    spots.add(new int[]{ MoyoungRingConstants.CMD_LIVE_STRESS, MoyoungRingConstants.SUB_LIVE_STRESS });
                }
                if ((dataTypes & RecordedDataTypes.TYPE_TEMPERATURE) != 0) {
                    spots.add(new int[]{ MoyoungRingConstants.CMD_LIVE_TEMP, MoyoungRingConstants.SUB_LIVE_TEMP });
                }
                if (!spots.isEmpty()) {
                    requestManualSpots(spots.toArray(new int[0][]));
                }
            }
        } catch (Exception e) {
            LOG.warn("MoyoungRing onFetchRecordedData failed", e);
        }
    }

    @Override
    public void dispose() {
        List<GenericHeartRateSample> pending;
        synchronized (hrBufferLock) {
            pending = new ArrayList<>(hrBuffer);
            hrBuffer.clear();
        }
        if (!pending.isEmpty()) {
            flushHrBuffer(pending);
        }
        handler.removeCallbacks(stopRealtimePushRunnable);
        realtimeHrEnabled = false;
        realtimeStepsEnabled = false;
        realtimePushActive = false;
        lastRealtimeSteps = -1;
        lastRealtimeRequestMs = 0L;
        cancelSpotMeasurements();
        unregisterUnlockReceiver();
        synchronized (syncQueue) { syncQueue.clear(); }
        syncInFlight = false;
        syncExpectedOpcode = -1;
        syncExpectedDay = -1;
        syncExpectedPage = -1;
        pendingInitialSyncFrames = null;
        initialSyncStarted = false;
        syncGen++; // invalidate any pending gen-keyed sync timeout
        handler.removeCallbacksAndMessages(null);
        super.dispose();
    }
}
