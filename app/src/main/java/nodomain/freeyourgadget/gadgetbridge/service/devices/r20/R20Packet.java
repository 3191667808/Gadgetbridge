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
package nodomain.freeyourgadget.gadgetbridge.service.devices.r20;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.devices.r20.R20Constants;

/**
 * Builds and parses YCBT wire frames for the R20 protocol.
 *
 * <pre>
 *   [group(1)] [key(1)] [len_lo(1)] [len_hi(1)] [payload...] [crc_lo(1)] [crc_hi(1)]
 * </pre>
 * {@code len = payload + 6}. CRC-16 is the custom Yucheng variant
 * implemented in {@link #crc16(byte[], int)}.
 *
 * <p>Each {@code Get*} command requires a 2-byte "magic" prefix in the
 * payload (R20 firmware returns 0xFE otherwise) — see {@link R20Constants}.
 */
public final class R20Packet {

    private final int dataType;
    private final byte[] payload;

    public R20Packet(int dataType, byte[] payload) {
        this.dataType = dataType;
        this.payload  = payload != null ? payload : new byte[0];
    }

    public int getDataType() { return dataType; }
    public byte[] getPayload() { return payload; }

    /** Yucheng CRC-16 variant. Seed 0xFFFF, byte-swap + nibble xor + poly bits. */
    public static int crc16(byte[] data, int len) {
        int s = 0xFFFF;
        for (int i = 0; i < len; i++) {
            int a = ((s << 8) & 0xFF00) | ((s >> 8) & 0xFF);
            int b = (a ^ (data[i] & 0xFF)) & 0xFFFF;
            int c = (b ^ ((b & 0xFF) >> 4)) & 0xFFFF;
            int d = (c ^ (c << 12)) & 0xFFFF;
            s = (d ^ ((d & 0xFF) << 5)) & 0xFFFF;
        }
        return s & 0xFFFF;
    }

    public byte[] encode() {
        int total = payload.length + 6;
        byte[] out = new byte[total];
        out[0] = (byte) ((dataType >> 8) & 0xFF);
        out[1] = (byte) (dataType & 0xFF);
        out[2] = (byte) (total & 0xFF);
        out[3] = (byte) ((total >> 8) & 0xFF);
        System.arraycopy(payload, 0, out, 4, payload.length);
        int crc = crc16(out, total - 2);
        out[total - 2] = (byte) (crc & 0xFF);
        out[total - 1] = (byte) ((crc >> 8) & 0xFF);
        return out;
    }

    /** Parses an inbound frame from a notification. Returns {@code null} if malformed. */
    public static R20Packet decode(byte[] data) {
        if (data == null || data.length < 6) return null;
        int dtype = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        int tlen  = (data[2] & 0xFF) | ((data[3] & 0xFF) << 8);
        if (tlen < 6 || tlen > data.length) return null;
        byte[] p = new byte[tlen - 6];
        if (p.length > 0) System.arraycopy(data, 4, p, 0, p.length);
        return new R20Packet(dtype, p);
    }

    // -------- Static builders --------

    public static R20Packet settingTime() {
        Calendar c = Calendar.getInstance();
        int year = c.get(Calendar.YEAR);
        ByteBuffer p = ByteBuffer.allocate(8);
        p.put((byte) (year & 0xFF));
        p.put((byte) ((year >> 8) & 0xFF));
        p.put((byte) (c.get(Calendar.MONTH) + 1));
        p.put((byte) c.get(Calendar.DAY_OF_MONTH));
        p.put((byte) c.get(Calendar.HOUR_OF_DAY));
        p.put((byte) c.get(Calendar.MINUTE));
        p.put((byte) c.get(Calendar.SECOND));
        // Day-of-week: Mon=0..Sun=6 (matches TimeUtil.makeBleTime in YCBT SDK)
        int dow = c.get(Calendar.DAY_OF_WEEK); // Sun=1..Sat=7
        p.put((byte) (dow == 1 ? 6 : dow - 2));
        return new R20Packet(R20Constants.SETTING_TIME, p.array());
    }

    public static R20Packet getDeviceInfo() {
        return new R20Packet(R20Constants.GET_DEVICE_INFO, R20Constants.MAGIC_GET_DEVICE_INFO);
    }
    public static R20Packet getDeviceName() {
        return new R20Packet(R20Constants.GET_DEVICE_NAME, R20Constants.MAGIC_GET_DEVICE_NAME);
    }
    public static R20Packet getDeviceSupportFn() {
        return new R20Packet(R20Constants.GET_DEVICE_SUPPORT_FN, R20Constants.MAGIC_GET_DEVICE_SUPPORT_FN);
    }
    public static R20Packet getPowerStatistics() {
        return new R20Packet(R20Constants.GET_POWER_STATISTICS, new byte[0]);
    }
    public static R20Packet getNowStep() {
        return new R20Packet(R20Constants.GET_NOW_STEP, new byte[0]);
    }
    public static R20Packet getRealBloodOxygen() {
        return new R20Packet(R20Constants.GET_REAL_BLOOD_OXYGEN, R20Constants.MAGIC_GET_REAL_BLOOD_OXYGEN);
    }

    /** Enable the PPG / health-sensor hardware. Must precede any measurement —
     *  without it the firmware silently accepts start commands but never fires the PPG LEDs. */
    public static R20Packet enableHealthSensors(boolean on) {
        return new R20Packet(R20Constants.ENABLE_HEALTH_SENSORS, new byte[]{(byte) (on ? 1 : 0)});
    }
    /** Set background monitoring interval in minutes. */
    public static R20Packet setMonitorInterval(int minutes) {
        return new R20Packet(R20Constants.SET_MONITOR_INTERVAL, new byte[]{(byte) minutes});
    }
    public static R20Packet enableBgSpO2Monitor(boolean on) {
        return new R20Packet(R20Constants.ENABLE_BG_SPO2_MONITOR, new byte[]{(byte) (on ? 1 : 0)});
    }

    /**
     * Start or stop a manual measurement session. PPG warmup ~10–20 s.
     *
     * @param type   one of {@code R20Constants.MEASURE_*}
     *               (0=HR, 1=BP, 2=SpO2, 3=Resp, 4=Temp, 5=BloodSugar,
     *                6=UricAcid, 7=Ketone, 8=EDA, 9=Lipids, 10=HRV,
     *                11=PPG_raw, 12=BP_alt, 13=VO2max).
     *               R20 firmware (2.32) only supports HR/BP/SpO2.
     * @param start  {@code true} to start, {@code false} to stop.
     */
    public static R20Packet startMeasurement(int type, boolean start) {
        // Discovered via HCI snoop of the official companion app:
        // payload is exactly 2 bytes — [action, type] — not [0x01, type, action].
        // action 0x01 = start, 0x00 = stop.
        return new R20Packet(R20Constants.APP_START_MEASUREMENT,
                new byte[]{(byte) (start ? 1 : 0), (byte) type});
    }

    /** Pre-measurement sensor-mode toggle observed in the companion app
     *  (opcode 0x030C with payload {@code [0x01, 0x01]}). Sent immediately
     *  before {@link #startMeasurement(int, boolean)} to prime the PPG
     *  hardware. The exact semantics are firmware-internal; empirically
     *  measurements are more reliable when this precedes the start command.
     */
    public static R20Packet primeSensors() {
        return new R20Packet(0x030C, new byte[]{0x01, 0x01});
    }

    /** Empty-payload history fetch (e.g. {@code HEALTH_HISTORY_HEART}). */
    public static R20Packet healthHistory(int dataType) {
        return new R20Packet(dataType, new byte[0]);
    }

    /**
     * ACK for a received history block. The firmware streams history in
     * chunks and expects an ACK before sending the next chunk.
     */
    public static R20Packet historyAck(int streamDataType, int blockLen) {
        return new R20Packet(R20Constants.HEALTH_HISTORY_ACK, new byte[]{
                (byte) ((streamDataType >> 8) & 0xFF),
                (byte) (streamDataType & 0xFF),
                (byte) (blockLen & 0xFF),
                (byte) ((blockLen >> 8) & 0xFF),
        });
    }

    /**
     * Delete a history category from the ring after a successful sync.
     * The firmware retains records until this command is sent, otherwise
     * the same data is re-pushed on every subsequent sync. Verified by
     * HCI snoop of the companion app.
     *
     * @param deleteOpcode one of {@code R20Constants.HEALTH_DELETE_*}
     */
    public static R20Packet deleteHistory(int deleteOpcode) {
        return new R20Packet(deleteOpcode, new byte[]{0x02});
    }

    // -------- Static parsers --------

    public static int parseBatteryPercent(byte[] payload) {
        if (payload == null || payload.length < 29) return -1;
        return payload[28] & 0xFF;
    }

    public static int parseStepCount(byte[] payload) {
        if (payload == null || payload.length < 9) return -1;
        return  (payload[5] & 0xFF)
             | ((payload[6] & 0xFF) << 8)
             | ((payload[7] & 0xFF) << 16)
             | ((payload[8] & 0xFF) << 24);
    }

    public static String parseDeviceName(byte[] payload) {
        if (payload == null) return null;
        int end = payload.length;
        for (int i = 0; i < payload.length; i++) {
            if (payload[i] == 0) { end = i; break; }
        }
        return new String(payload, 0, end);
    }

    /** Firmware version "{maj}.{min}" extracted from device-info payload.
     *  Byte 2 = minor, byte 3 = major (little-endian field convention). */
    public static String parseFirmwareVersion(byte[] payload) {
        if (payload == null || payload.length < 4) return null;
        return (payload[3] & 0xFF) + "." + (payload[2] & 0xFF);
    }

    // ====================================================================
    //   History record types & parsers
    // ====================================================================

    private static long ts2k_to_unix_ms(byte[] data, int off) {
        long ts = ((long)(data[off]     & 0xFF))
                | ((long)(data[off + 1] & 0xFF) << 8)
                | ((long)(data[off + 2] & 0xFF) << 16)
                | ((long)(data[off + 3] & 0xFF) << 24);
        return (ts + R20Constants.EPOCH_2000_UNIX_SECONDS) * 1000L;
    }

    /** Single historical heart-rate reading. */
    public static final class HrRecord {
        public final long timestampMs;
        public final int  bpm;
        public HrRecord(long ts, int bpm) { this.timestampMs = ts; this.bpm = bpm; }
    }

    /** Parses an {@code HEALTH_STREAM_HEART} (0x0515) payload — 6-byte records:
     *  ts(4 LE), reserved(1), bpm(1). */
    public static List<HrRecord> parseHrRecords(byte[] payload) {
        List<HrRecord> out = new ArrayList<>();
        if (payload == null) return out;
        for (int i = 0; i + 6 <= payload.length; i += 6) {
            long ts  = ts2k_to_unix_ms(payload, i);
            int bpm  = payload[i + 5] & 0xFF;
            if (bpm > 0 && bpm < 240) out.add(new HrRecord(ts, bpm));
        }
        return out;
    }

    /** Single historical blood-pressure reading. */
    public static final class BpRecord {
        public final long timestampMs;
        public final int  systolic;
        public final int  diastolic;
        public final int  hr;
        public BpRecord(long ts, int sys, int dia, int hr) {
            this.timestampMs = ts; this.systolic = sys; this.diastolic = dia; this.hr = hr;
        }
    }

    /** Parses an {@code HEALTH_STREAM_BLOOD} (0x0517) payload — 8-byte records:
     *  ts(4 LE), valid(1), systolic(1), diastolic(1), hr(1). */
    public static List<BpRecord> parseBpRecords(byte[] payload) {
        List<BpRecord> out = new ArrayList<>();
        if (payload == null) return out;
        for (int i = 0; i + 8 <= payload.length; i += 8) {
            long ts  = ts2k_to_unix_ms(payload, i);
            int sys  = payload[i + 5] & 0xFF;
            int dia  = payload[i + 6] & 0xFF;
            int hr   = payload[i + 7] & 0xFF;
            if (sys > 0) out.add(new BpRecord(ts, sys, dia, hr));
        }
        return out;
    }

    /** A single sleep stage within a sleep session. */
    public static final class SleepStage {
        public static final int TYPE_DEEP  = 241;
        public static final int TYPE_LIGHT = 242;
        public static final int TYPE_REM   = 243;
        public static final int TYPE_AWAKE = 244;

        public final int  type;
        public final long startTimeMs;
        public final int  durationSec;
        public SleepStage(int type, long start, int dur) {
            this.type = type; this.startTimeMs = start; this.durationSec = dur;
        }
    }

    /** Decoded sleep session (one per night). */
    public static final class SleepSession {
        public long startTimeMs;
        public long endTimeMs;
        public int  deepSleepCount;
        public int  lightSleepCount;
        public int  deepSleepSec;
        public int  lightSleepSec;
        public int  remSleepSec;
        public int  wakeCount;
        public int  wakeDurationSec;
        public final List<SleepStage> stages = new ArrayList<>();
    }

    /**
     * Parses an {@code HEALTH_STREAM_SLEEP} (0x0513) payload. Each session has a
     * 20-byte header followed by N 8-byte stage records.
     * Direct port of {@code DataUnpack.unpackHealthData(..., 4)}.
     */
    public static List<SleepSession> parseSleepSessions(byte[] payload) {
        List<SleepSession> out = new ArrayList<>();
        if (payload == null || payload.length < 20) return out;
        int i = 0;
        while (i + 20 <= payload.length) {
            int sessionLen = (payload[i + 2] & 0xFF) | ((payload[i + 3] & 0xFF) << 8);
            long startMs   = ts2k_to_unix_ms(payload, i + 4);
            long endMs     = ts2k_to_unix_ms(payload, i + 8);
            int sentinel   = (payload[i + 12] & 0xFF) | ((payload[i + 13] & 0xFF) << 8);

            SleepSession session = new SleepSession();
            session.startTimeMs = startMs;
            session.endTimeMs   = endMs;

            if (sentinel == 0xFFFF) {
                session.deepSleepCount  = (payload[i + 14] & 0xFF) | ((payload[i + 15] & 0xFF) << 8);
                session.deepSleepSec    = (payload[i + 16] & 0xFF) | ((payload[i + 17] & 0xFF) << 8);
                session.lightSleepSec   = (payload[i + 18] & 0xFF) | ((payload[i + 19] & 0xFF) << 8);
            } else {
                session.deepSleepCount  = sentinel;
                session.lightSleepCount = (payload[i + 14] & 0xFF) | ((payload[i + 15] & 0xFF) << 8);
                session.deepSleepSec    = ((payload[i + 16] & 0xFF) | ((payload[i + 17] & 0xFF) << 8)) * 60;
                session.lightSleepSec   = ((payload[i + 18] & 0xFF) | ((payload[i + 19] & 0xFF) << 8)) * 60;
            }

            int stagesEnd = Math.min(i + sessionLen, payload.length);
            for (int j = i + 20; j + 8 <= stagesEnd; j += 8) {
                int  type    = payload[j] & 0xFF;
                long stageMs = ts2k_to_unix_ms(payload, j + 1);
                int  durSec  = (payload[j + 5] & 0xFF)
                             | ((payload[j + 6] & 0xFF) << 8)
                             | ((payload[j + 7] & 0xFF) << 16);
                session.stages.add(new SleepStage(type, stageMs, durSec));
                if (type == SleepStage.TYPE_AWAKE) {
                    session.wakeCount++;
                    session.wakeDurationSec += durSec;
                }
                if (type == SleepStage.TYPE_REM) {
                    session.remSleepSec += durSec;
                }
            }
            out.add(session);
            i = (sessionLen >= 28) ? i + sessionLen : i + Math.max(20, sessionLen);
            // Defensive: ensure forward progress to avoid an infinite loop
            // on malformed input.
            if (sessionLen < 20) break;
        }
        return out;
    }

    /** Composite all-metrics record (20 bytes) from {@code HEALTH_STREAM_ALL}. */
    public static final class AllRecord {
        public final long timestampMs;
        public final int  hr;        // bpm,  0 = absent
        public final int  systolic;  // mmHg, 0 = absent
        public final int  diastolic; // mmHg, 0 = absent
        public final int  spo2;      // %,    0 = absent
        public final int  steps;     // interval step count (uint16)
        public final int  respiratoryRate; // breaths/min, 0 = absent
        public final int  hrv;       // SDNN-equivalent, 0 = absent
        public final int  cvrr;      // coefficient of variation of RR (Yucheng vendor metric)
        public final double temperature; // body temp °C, 0 = absent
        public final double bodyFatPct;  // %, 0 = absent
        public final int  bloodSugar;    // mmol/L * 10 firmware-side, 0 = absent
        public AllRecord(long ts, int hr, int sys, int dia, int spo2, int steps,
                         int rr, int hrv, int cvrr, double temp, double bf, int bs) {
            this.timestampMs = ts; this.hr = hr; this.systolic = sys; this.diastolic = dia;
            this.spo2 = spo2; this.steps = steps;
            this.respiratoryRate = rr; this.hrv = hrv; this.cvrr = cvrr;
            this.temperature = temp; this.bodyFatPct = bf; this.bloodSugar = bs;
        }
    }

    /**
     * Parses an {@code HEALTH_STREAM_ALL} (0x0518) payload — 20-byte
     * composite records, layout confirmed against the YCBT SDK reference
     * (<code>com.yucheng.ycbtsdk.core.DataUnpack.unpackHealthData</code>
     * case 9, mirrored at
     * <a href="https://github.com/auroraphtgrp01/ble-sleeping">auroraphtgrp01/ble-sleeping</a>):
     * <pre>
     *   bytes  0..3   timestamp (uint32 LE, + EPOCH_2000_UNIX_SECONDS)
     *   bytes  4..5   stepValue (uint16 LE)
     *   byte   6      heartValue (bpm)
     *   byte   7      SBPValue (systolic, mmHg)
     *   byte   8      DBPValue (diastolic, mmHg)
     *   byte   9      OOValue (SpO2 %)
     *   byte  10      respiratoryRateValue (breaths/min)
     *   byte  11      hrvValue
     *   byte  12      cvrrValue
     *   byte  13      tempIntValue (°C integer part)
     *   byte  14      tempFloatValue (°C fractional part, 1/100)
     *   byte  15      bodyFatIntValue (%, integer part)
     *   byte  16      bodyFatFloatValue (%, fractional part, 1/100)
     *   byte  17      bloodSugarValue
     *   bytes 18..19  padding (zero on R20 fw 2.32)
     * </pre>
     */
    public static List<AllRecord> parseAllRecords(byte[] payload) {
        List<AllRecord> out = new ArrayList<>();
        if (payload == null) return out;
        for (int i = 0; i + 20 <= payload.length; i += 20) {
            long ts = ts2k_to_unix_ms(payload, i);
            int steps = (payload[i + 4] & 0xFF) | ((payload[i + 5] & 0xFF) << 8);
            int hr  = payload[i + 6] & 0xFF;
            int sys = payload[i + 7] & 0xFF;
            int dia = payload[i + 8] & 0xFF;
            int spo = payload[i + 9] & 0xFF;
            int rr  = payload[i + 10] & 0xFF;
            int hrv = payload[i + 11] & 0xFF;
            int cvrr = payload[i + 12] & 0xFF;
            int tInt  = payload[i + 13] & 0xFF;
            int tFrac = payload[i + 14] & 0xFF;
            int bfInt  = payload[i + 15] & 0xFF;
            int bfFrac = payload[i + 16] & 0xFF;
            int bs  = payload[i + 17] & 0xFF;
            double temp = (tInt == 0 && tFrac == 0) ? 0.0 : tInt + tFrac / 100.0;
            double bf   = (bfInt == 0 && bfFrac == 0) ? 0.0 : bfInt + bfFrac / 100.0;
            out.add(new AllRecord(ts, hr, sys, dia, spo, steps, rr, hrv, cvrr, temp, bf, bs));
        }
        return out;
    }

    /**
     * Sport-history record (14 bytes per record) from {@code HEALTH_STREAM_SPORT}
     * (0x0511). Layout cross-verified against
     * {@code com.yucheng.ycbtsdk.core.DataUnpack.unpackHealthData} case 2:
     * <pre>
     *   bytes  0..3   startTime (uint32 LE, + EPOCH_2000_UNIX_SECONDS)
     *   bytes  4..7   endTime   (uint32 LE, + EPOCH_2000_UNIX_SECONDS)
     *   bytes  8..9   stepValue (uint16 LE)
     *   bytes 10..11  sportDistance (uint16 LE, meters)
     *   bytes 12..13  sportCalorie  (uint16 LE, kcal)
     * </pre>
     * Each interval typically represents an active period detected by the
     * onboard accelerometer (the firmware bundles continuous step-active
     * windows into single records rather than per-minute samples).
     */
    public static final class SportRecord {
        public final long startTimeMs;
        public final long endTimeMs;
        public final int  steps;
        public final int  distanceMeters;
        public final int  calorieKcal;
        public SportRecord(long start, long end, int steps, int dist, int kcal) {
            this.startTimeMs = start; this.endTimeMs = end;
            this.steps = steps; this.distanceMeters = dist; this.calorieKcal = kcal;
        }
        public int durationSec() { return (int) Math.max(0L, (endTimeMs - startTimeMs) / 1000L); }
    }

    public static List<SportRecord> parseSportRecords(byte[] payload) {
        List<SportRecord> out = new ArrayList<>();
        if (payload == null) return out;
        for (int i = 0; i + 14 <= payload.length; i += 14) {
            long start = ts2k_to_unix_ms(payload, i);
            long end   = ts2k_to_unix_ms(payload, i + 4);
            int steps  = (payload[i + 8]  & 0xFF) | ((payload[i + 9]  & 0xFF) << 8);
            int dist   = (payload[i + 10] & 0xFF) | ((payload[i + 11] & 0xFF) << 8);
            int kcal   = (payload[i + 12] & 0xFF) | ((payload[i + 13] & 0xFF) << 8);
            out.add(new SportRecord(start, end, steps, dist, kcal));
        }
        return out;
    }
}
