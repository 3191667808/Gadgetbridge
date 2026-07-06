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
package nodomain.freeyourgadget.gadgetbridge.devices.moyoungring;

import java.util.UUID;

/**
 * Protocol constants for the MoYoung / CRRepa "Da Ring" optical smart ring.
 * Advertised BLE name is "VRing"; hardware model "R26"; firmware "MOY-R263-2.2.2".
 *
 * <p>Wire format (both directions), written to {@code fdd2}, received on {@code fdd3}:
 * <pre>
 *   byte0=0xFD byte1=0xDA byte2=0x10 byte3=len byte4=cmd byte5=sub byte6..=payload
 *   len = payloadLength + 6   (len counts the whole frame)
 * </pre>
 * No CRC. Little-endian multi-byte integers.
 */
public final class MoyoungRingConstants {

    private MoyoungRingConstants() { }

    // -------- BLE services & characteristics --------

    /** Vendor service 0xFDDA. */
    public static final UUID UUID_SERVICE =
            UUID.fromString("0000fdda-0000-1000-8000-00805f9b34fb");
    /** fdd1 — live steps (3-byte triples); notify + read. */
    public static final UUID UUID_CHAR_STEPS =
            UUID.fromString("0000fdd1-0000-1000-8000-00805f9b34fb");
    /** fdd2 — COMMAND channel, write-without-response. */
    public static final UUID UUID_CHAR_COMMAND =
            UUID.fromString("0000fdd2-0000-1000-8000-00805f9b34fb");
    /** fdd3 — framed RESPONSE channel, notify. */
    public static final UUID UUID_CHAR_RESPONSE =
            UUID.fromString("0000fdd3-0000-1000-8000-00805f9b34fb");
    /** fdd5 — OTA only (cmd 0xF1). Unused for now. */
    public static final UUID UUID_CHAR_OTA1 =
            UUID.fromString("0000fdd5-0000-1000-8000-00805f9b34fb");
    /** fdd6 — OTA only (cmd 0xF2). Unused for now. */
    public static final UUID UUID_CHAR_OTA2 =
            UUID.fromString("0000fdd6-0000-1000-8000-00805f9b34fb");

    // -------- Frame header --------

    public static final byte HDR0 = (byte) 0xFD;
    public static final byte HDR1 = (byte) 0xDA;
    public static final byte HDR2 = (byte) 0x10;
    /** Fixed frame overhead: 3 magic bytes + len + cmd + sub. */
    public static final int FRAME_OVERHEAD = 6;
    /** Header byte count consumed before the payload begins. */
    public static final int HEADER_LEN = 6;

    // -------- Command opcodes (cmd, sub) --------
    // A logical opcode is encoded here as (cmd << 8 | sub) for convenient switching.

    public static int op(int cmd, int sub) { return ((cmd & 0xFF) << 8) | (sub & 0xFF); }

    // Live measurement start/stop (payload {0x01}=start, {0x00}=stop).
    public static final int CMD_LIVE_HR     = 1;
    public static final int SUB_LIVE_HR     = 9;
    public static final int CMD_LIVE_SPO2   = 1;
    public static final int SUB_LIVE_SPO2   = 11;
    public static final int CMD_LIVE_BP     = 1;
    public static final int SUB_LIVE_BP     = 25;
    public static final int CMD_LIVE_HRV    = 1;
    public static final int SUB_LIVE_HRV    = 10;
    public static final int CMD_LIVE_STRESS = 1;
    public static final int SUB_LIVE_STRESS = 14;
    public static final int CMD_LIVE_TEMP   = 1;
    public static final int SUB_LIVE_TEMP   = 32;

    // Realtime activity streaming (cmd 9) — used for the live-reporting ("mode 2")
    // experience. While enabled the ring pushes live HR (cmd 1/9) and live step
    // triples (fdd1) continuously. Observed in a companion-app capture during a walk:
    // 9/1 {sportType} then 9/0 {01}=push-on / {00}=push-off (fire-and-forget, no
    // reply). HR streams from 1/9 alone, but the live-step (fdd1) push needs the
    // workout session started via 9/1, so we send the CAPTURE-VERIFIED walking type
    // (3) before 9/0. These are non-destructive control commands.
    public static final int CMD_WORKOUT        = 9;
    public static final int SUB_WORKOUT_PUSH   = 0;   // payload {01}=on, {00}=off
    public static final int SUB_WORKOUT_TYPE   = 1;   // payload {sportType}
    public static final int SPORT_TYPE_WALKING = 3;   // verified value from the companion-app capture

    // Settings / enable-automatic-monitoring (all cmd=1). Turning on timed logging
    // makes the ring accumulate a background history series that fills the charts;
    // without it the ring only keeps the latest spot reading. Interval = minutes.
    public static final int CMD_SETTINGS      = 1;
    public static final int SUB_SET_USER_INFO = 0;
    public static final int SUB_ENABLE_HR     = 6;
    public static final int SUB_ENABLE_HRV    = 7;
    public static final int SUB_ENABLE_SPO2   = 8;
    public static final int SUB_ENABLE_TEMP   = 13;
    public static final int SUB_ENABLE_STRESS = 39;
    /**
     * Default background-logging cadence, in <b>real minutes</b>. The wire value is
     * this divided by 5 (the ring encodes the interval in 5-minute units, valid
     * 1..12 = 5..60 min). Verified from a live capture where the app's byte {@code 6}
     * produced ~30-minute HR slot spacing. Denser = richer graphs, more battery.
     */
    public static final int TIMING_INTERVAL_HR_MIN    = 5;   // wire = 1 (5-min slots)
    public static final int TIMING_INTERVAL_OTHER_MIN = 15;  // wire = 3 (15-min slots)
    public static final long SPOT_MEASURE_GAP_MS = 55_000L;

    // ---- Awake-gated spot-measurement polling ------------------------------
    // The ring cannot record sleep while its PPG sensor is busy with on-demand
    // spot measurements, so background spot polling must NEVER run at night.
    // Instead we poll a small number of times per day, only while the user is
    // awake (screen unlock), the FIRST poll only after the local sunrise.
    /** Max automatic spot-measurement rounds per calendar day (awake only). */
    public static final int  AUTO_POLL_MAX_PER_DAY = 2;
    /** Minimum spacing between the daily automatic polls. */
    public static final long AUTO_POLL_MIN_INTERVAL_MS = 5L * 60L * 60L * 1000L; // 5h
    /** No automatic spot polling at/after this local hour (protects sleep). */
    public static final int  AUTO_POLL_NIGHT_CUTOFF_HOUR = 22;
    /** Earliest local hour an automatic poll may run if no GPS location is set. */
    public static final int  SUNRISE_FALLBACK_HOUR = 8;
    /** Debounce for manual (tab-refresh) spot requests, to dedupe rapid taps. */
    public static final long MANUAL_SPOT_MIN_SPACING_MS = 2L * 60L * 1000L; // 2 min
    /** Wire unit for timed-monitoring intervals: 5 minutes per step. */
    public static final int TIMING_INTERVAL_UNIT_MIN  = 5;
    public static final int TIMING_INTERVAL_MAX_UNITS = 12;  // 60 minutes

    // Set device clock (companion app time group: c1/f0.b()). Payload is a
    // 4-byte little-endian Unix epoch (seconds) followed by a 1-byte signed
    // timezone offset in whole hours. The ring's internal clock is set to this
    // epoch; display/day-boundaries are epoch + offsetHours. Sending a correctly
    // formatted clock is a PREREQUISITE for the ring to log measurements into its
    // per-day 5-minute timelines — without it the daily charts stay empty.
    public static final int CMD_SET_TIME      = 1;
    public static final int SUB_SET_TIME      = 1;

    // Query / history (device replies with framed data on fdd3).
    public static final int CMD_DEVICE_INFO   = 5;
    public static final int SUB_DEVICE_INFO   = 2;
    public static final int CMD_FIRMWARE      = 3;
    public static final int SUB_FIRMWARE      = 3;
    public static final int CMD_DAILY_GOALS   = 2;
    public static final int SUB_DAILY_GOALS   = 2;
    public static final int CMD_HIST_HR       = 2;
    public static final int SUB_HIST_HR       = 9;
    public static final int CMD_HIST_SPO2     = 2;
    public static final int SUB_HIST_SPO2     = 11;
    public static final int CMD_HIST_HRV      = 2;
    public static final int SUB_HIST_HRV      = 10;
    public static final int CMD_HIST_STRESS   = 2;
    public static final int SUB_HIST_STRESS   = 25;
    public static final int CMD_HIST_SLEEP    = 7;
    public static final int SUB_HIST_SLEEP    = 4;
    // Per-day sleep STAGE data (j.b in the decompiled c1/y.java dispatcher):
    // [dayByte][state,hour,minute]*N with len%3==1. day is 0=today, 1=yesterday...
    // This is the opcode our stage parser (handleSleepHistory) actually expects;
    // 7/4 above is a 4-byte-per-record session-timestamp LIST (j.d), not stages.
    public static final int CMD_HIST_SLEEP_DAY = 2;
    public static final int SUB_HIST_SLEEP_DAY = 14;
    public static final int CMD_HIST_STEPS    = 2;
    public static final int SUB_HIST_STEPS    = 13;
    // Per-slot step HISTOGRAM ("steps details", queryHistoryStepsDetails). The
    // reply is [day:u8] followed by one u16-LE step count per 30-minute slot.
    // Companion app: c1/a0.b(day) = k.c(2, 18, {day}); parser i1/k.c(byte[]).
    public static final int CMD_HIST_STEPS_DETAIL = 2;
    public static final int SUB_HIST_STEPS_DETAIL = 18;

    // Paged daily "timing" timelines. The ring exposes a per-day timeline (one
    // value per fixed 5-minute slot) that is delivered PAGED: multiple response
    // frames per day, each carrying [day][pageIndex][per-slot values...]. The
    // request payload is {day, pageIndex} (day: 0=today, 1=yesterday, ...).
    // Derived from the decompiled companion app (c1.q/h/r/b0 builders and the
    // i1.f/d/g/l response state machines); responses arrive under the SAME
    // (cmd, sub) as the request (m1.a dispatcher, cmd=2 switch).
    public static final int CMD_TIMING_HR     = 2;
    public static final int SUB_TIMING_HR     = 15;   // c1.q.b(day,idx)
    public static final int CMD_TIMING_HRV    = 2;
    public static final int SUB_TIMING_HRV    = 16;   // c1.r.b(day,idx)
    public static final int CMD_TIMING_SPO2   = 2;
    public static final int SUB_TIMING_SPO2   = 17;   // c1.h.b(day,idx)
    public static final int CMD_TIMING_STRESS = 2;
    public static final int SUB_TIMING_STRESS = 47;   // c1.b0.b(day,idx)
    // Temperature daily timeline (i1/m.java + c1/c0.java): cmd 2 / sub 22, payload
    // {day,page}; response [day][page][u16LE × 72/page], 4 pages. Same geometry as
    // HRV. Value = u16LE / 10.0 °C (firmware pre-zeroes out-of-range readings).
    public static final int CMD_TIMING_TEMP   = 2;
    public static final int SUB_TIMING_TEMP   = 22;   // c1.c0.b(day,idx)

    /**
     * Last (terminating) page index per metric, taken from the decompiled state
     * machines: when the page whose index == this value arrives, the day's
     * timeline is assembled; otherwise the next page is requested.
     * <ul>
     *   <li>HR     (i1.f): {@code if (1 == index) assemble} → last = 1</li>
     *   <li>HRV    (i1.g): {@code if (3 == index) assemble} → last = 3</li>
     *   <li>SpO2   (i1.d): {@code if (1 == index) assemble} → last = 1</li>
     *   <li>stress (i1.l): {@code if (1 == index) assemble} → last = 1</li>
     * </ul>
     */
    public static final int TIMING_LAST_PAGE_HR     = 1;
    public static final int TIMING_LAST_PAGE_HRV    = 3;
    public static final int TIMING_LAST_PAGE_SPO2   = 1;
    public static final int TIMING_LAST_PAGE_STRESS = 1;
    public static final int TIMING_LAST_PAGE_TEMP   = 3;   // temp: 4 pages (u16, 72/page) like HRV

    /**
     * Slot interval (minutes) for the daily timelines. Derived from the constant
     * {@code 5} used throughout the decompile ({@code z1.c.a() / 5} = current
     * slot index; {@code CRPHeartRateInfo(..., 5, ...)}). Slot i maps to
     * {@code startOfLocalDay(day) + i * 5 minutes}.
     */
    public static final int TIMING_SLOT_MINUTES = 5;

    /**
     * How many past days of history to fetch on each sync (rolling window). We
     * re-request today plus the {@code TIMING_DAYS_BACK - 1} prior days every time
     * (the loop runs {@code day = 0 .. TIMING_DAYS_BACK - 1}) and upsert the slots,
     * so the last-X-days view stays populated and accumulates without ever deleting.
     * Requests for days the ring no longer retains simply return empty (harmless).
     */
    public static final int TIMING_DAYS_BACK = 7;

    // Convenient combined opcodes for response dispatch.
    // NOTE: these must be compile-time constant expressions so they can be used
    // as switch/case labels, hence inline (cmd<<8)|sub rather than op(cmd, sub).
    public static final int OP_LIVE_HR      = (CMD_LIVE_HR << 8) | SUB_LIVE_HR;
    public static final int OP_LIVE_SPO2    = (CMD_LIVE_SPO2 << 8) | SUB_LIVE_SPO2;
    public static final int OP_LIVE_BP      = (CMD_LIVE_BP << 8) | SUB_LIVE_BP;
    public static final int OP_LIVE_HRV     = (CMD_LIVE_HRV << 8) | SUB_LIVE_HRV;
    public static final int OP_LIVE_STRESS  = (CMD_LIVE_STRESS << 8) | SUB_LIVE_STRESS;
    public static final int OP_LIVE_TEMP    = (CMD_LIVE_TEMP << 8) | SUB_LIVE_TEMP;
    public static final int OP_DEVICE_INFO  = (CMD_DEVICE_INFO << 8) | SUB_DEVICE_INFO;
    public static final int OP_FIRMWARE     = (CMD_FIRMWARE << 8) | SUB_FIRMWARE;
    public static final int OP_DAILY_GOALS  = (CMD_DAILY_GOALS << 8) | SUB_DAILY_GOALS;
    public static final int OP_HIST_HR      = (CMD_HIST_HR << 8) | SUB_HIST_HR;
    public static final int OP_HIST_SPO2    = (CMD_HIST_SPO2 << 8) | SUB_HIST_SPO2;
    public static final int OP_HIST_HRV     = (CMD_HIST_HRV << 8) | SUB_HIST_HRV;
    public static final int OP_HIST_STRESS  = (CMD_HIST_STRESS << 8) | SUB_HIST_STRESS;
    public static final int OP_HIST_SLEEP   = (CMD_HIST_SLEEP << 8) | SUB_HIST_SLEEP;
    public static final int OP_HIST_SLEEP_DAY = (CMD_HIST_SLEEP_DAY << 8) | SUB_HIST_SLEEP_DAY;
    public static final int OP_HIST_STEPS   = (CMD_HIST_STEPS << 8) | SUB_HIST_STEPS;
    public static final int OP_HIST_STEPS_DETAIL = (CMD_HIST_STEPS_DETAIL << 8) | SUB_HIST_STEPS_DETAIL;

    public static final int OP_TIMING_HR     = (CMD_TIMING_HR << 8) | SUB_TIMING_HR;
    public static final int OP_TIMING_HRV    = (CMD_TIMING_HRV << 8) | SUB_TIMING_HRV;
    public static final int OP_TIMING_SPO2   = (CMD_TIMING_SPO2 << 8) | SUB_TIMING_SPO2;
    public static final int OP_TIMING_STRESS = (CMD_TIMING_STRESS << 8) | SUB_TIMING_STRESS;
    public static final int OP_TIMING_TEMP   = (CMD_TIMING_TEMP << 8) | SUB_TIMING_TEMP;

    public static final byte MEASURE_START = 0x01;
    public static final byte MEASURE_STOP  = 0x00;

    // -------- Medical validation clamps --------
    // Values outside these ranges are discarded before persisting; sentinels
    // (0, 255=0xFF, 0xFFFF) are treated as "no data".
    //
    // CRITICAL: the ring reports a 1-byte 0xFF (255) "no-read" sentinel for HR
    // (and temperature) when off-finger, and a 2-byte 0xFFFF sentinel for wider
    // fields. 0xFF (255) falls INSIDE a naive numeric HR range, so a pure range
    // check would wrongly accept it as a heart rate. Sentinels MUST therefore be
    // rejected explicitly BEFORE range-checking. Use the plausible*() helpers
    // below everywhere before persisting a sample.

    public static final int SENTINEL_U8  = 0xFF;    // 255, 1-byte "no reading"
    public static final int SENTINEL_U16 = 0xFFFF;  // 65535, 2-byte "no reading"

    public static final int HR_MIN            = 30;
    public static final int HR_MAX            = 220;
    public static final int SPO2_MIN          = 70;
    public static final int SPO2_MAX          = 100;
    public static final int BP_SYS_MIN        = 60;
    public static final int BP_SYS_MAX        = 250;
    public static final int BP_DIA_MIN        = 30;
    public static final int BP_DIA_MAX        = 150;
    public static final int HRV_MIN           = 3;
    public static final int HRV_MAX           = 200;
    public static final int STRESS_MIN        = 1;
    public static final int STRESS_MAX        = 100;
    public static final int STEPS_PER_DAY_MAX = 80000;
    // Per-slot step histogram: the ring buckets a day into 30-minute slots
    // (48 slots/day). Each slot carries a u16 step count; clamp implausible
    // values before persisting an ActivitySample for that slot.
    public static final int STEPS_SLOT_MINUTES  = 30;
    public static final int STEPS_SLOTS_PER_DAY = 48;
    public static final int STEPS_PER_SLOT_MAX  = 20000;
    // The R26 StepsInfo (cmd 2/13) reports distance in MILLIMETRES and calories in
    // sub-kcal cal (both 1000x the human unit). Divide distance before storing metres;
    // keep calories in cal because ActivitySample active calories are charted as kcal
    // after a /1000 conversion. Verified live + against Da Rings.
    public static final int STEPS_DISTANCE_DIVISOR = 1000;
    public static final int STEPS_CALORIES_DIVISOR = 1000;
    public static final double SKIN_TEMP_MIN  = 25.0;
    public static final double SKIN_TEMP_MAX  = 40.0;
    // Temperature-timeline (2/22) plausibility range (°C). The firmware zeroes
    // out-of-range slots (valid 28..50 °C), so slot value 0 = "no reading".
    public static final double TEMP_TL_MIN    = 28.0;
    public static final double TEMP_TL_MAX    = 50.0;

    // -------- Sentinel-aware validation helpers --------
    // Each helper rejects the device's "no-read" sentinels FIRST, then applies
    // the plausible physiological range. Callers must gate every persisted
    // sample through the matching helper.

    /** Heart rate in bpm. Rejects 0 / 255 (0xFF) / 0xFFFF sentinels, then keeps {@value #HR_MIN}..{@value #HR_MAX}. */
    public static boolean plausibleHr(int v) {
        if (v == 0 || v == SENTINEL_U8 || v == SENTINEL_U16) return false;
        return v >= HR_MIN && v <= HR_MAX;
    }

    /** SpO2 percentage. Keeps {@value #SPO2_MIN}..{@value #SPO2_MAX}, which also rejects 0 / 255 / >100 naturally. */
    public static boolean plausibleSpo2(int v) {
        return v >= SPO2_MIN && v <= SPO2_MAX;
    }

    /** HRV/RMSSD in ms. Keeps {@value #HRV_MIN}..{@value #HRV_MAX}, which rejects 0 and 0xFFFF (65535) naturally. */
    public static boolean plausibleHrv(int v) {
        return v >= HRV_MIN && v <= HRV_MAX;
    }

    /**
     * Stress score. Rejects 0 / 255 sentinels, then keeps {@value #STRESS_MIN}..{@value #STRESS_MAX}.
     * NOTE: stress is a non-medical wellness ESTIMATE, not a validated measurement.
     */
    public static boolean plausibleStress(int v) {
        if (v == 0 || v == SENTINEL_U8) return false;
        return v >= STRESS_MIN && v <= STRESS_MAX;
    }

    /**
     * Blood pressure in mmHg. Rejects 0 / 0xFFFF sentinels on either value, then keeps
     * systolic {@value #BP_SYS_MIN}..{@value #BP_SYS_MAX} and diastolic {@value #BP_DIA_MIN}..{@value #BP_DIA_MAX}.
     * NOTE: the ring's BP is a non-medical wellness ESTIMATE, not a validated measurement.
     */
    public static boolean plausibleBp(int systolic, int diastolic) {
        if (systolic == 0 || systolic == SENTINEL_U16
                || diastolic == 0 || diastolic == SENTINEL_U16) return false;
        return systolic >= BP_SYS_MIN && systolic <= BP_SYS_MAX
                && diastolic >= BP_DIA_MIN && diastolic <= BP_DIA_MAX;
    }

    /** Skin temperature in Celsius. Rejects the 0xFF (255) sentinel, then keeps {@value #SKIN_TEMP_MIN}..{@value #SKIN_TEMP_MAX}. */
    public static boolean plausibleSkinTemp(int rawByte, double celsius) {
        if (rawByte == SENTINEL_U8) return false;
        return celsius >= SKIN_TEMP_MIN && celsius <= SKIN_TEMP_MAX;
    }

    /**
     * Temperature-timeline (2/22) slot in Celsius. Slot value 0 = "no reading"
     * (firmware pre-zeroes out-of-range); otherwise keep {@value #TEMP_TL_MIN}..{@value #TEMP_TL_MAX}.
     */
    public static boolean plausibleTempTimeline(double celsius) {
        return celsius >= TEMP_TL_MIN && celsius <= TEMP_TL_MAX;
    }

    // -------- High-water-mark (HWM) preference keys --------
    // Read-only-ring policy: we NEVER send any delete/clear opcode. Instead we
    // track the newest per-metric timestamp already persisted and drop records
    // at or below it, so on-ring history is preserved for other companion apps.

    private static final String PREF_HWM_PREFIX = "moyoungring_history_hwm_";
    public static final String HWM_HR     = PREF_HWM_PREFIX + "hr";
    public static final String HWM_SPO2   = PREF_HWM_PREFIX + "spo2";
    public static final String HWM_HRV    = PREF_HWM_PREFIX + "hrv";
    public static final String HWM_STRESS = PREF_HWM_PREFIX + "stress";
    public static final String HWM_SLEEP  = PREF_HWM_PREFIX + "sleep";
    public static final String HWM_STEPS  = PREF_HWM_PREFIX + "steps";
    // Separate HWM keys for the paged daily-timeline source so a newer sample from
    // the single-frame LIST source cannot advance the metric HWM past older-but-
    // unsynced timeline slots (and vice-versa), which would silently drop data.
    public static final String HWM_TIMING_HR     = PREF_HWM_PREFIX + "timing_hr";
    public static final String HWM_TIMING_SPO2   = PREF_HWM_PREFIX + "timing_spo2";
    public static final String HWM_TIMING_HRV    = PREF_HWM_PREFIX + "timing_hrv";
    public static final String HWM_TIMING_STRESS = PREF_HWM_PREFIX + "timing_stress";

    /** Reject stored HWMs (and inbound timestamps) more than this far in the future. */
    public static final long HWM_FUTURE_TOLERANCE_MS = 5L * 60L * 1000L;
    /** Hard past floor for any persisted sample (2020-01-01); rejects garbage-old timestamps. */
    public static final long TS_FLOOR_MS = 1_577_836_800_000L;

    /**
     * @deprecated No longer used. Monitoring enable (HR 1/6, HRV 1/7, etc.) is now
     * re-sent on every connect — a live capture proved re-enabling is non-destructive
     * (the ring retains its accumulated timeline slots), and the old once-ever guard
     * meant logging stopped forever after any factory-reset/unbind. Kept only so any
     * previously stored value is ignored rather than referenced.
     */
    @Deprecated
    public static final String PREF_MONITORING_ENABLED = "moyoungring_monitoring_enabled";

    public static final String MANUFACTURER = "MoYoung";
    public static final String HW_MODEL      = "R26";
    public static final String SCAN_NAME     = "VRing";
}
