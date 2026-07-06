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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.devices.moyoungring.MoyoungRingConstants;

/**
 * Builds and parses MoYoung / CRRepa "Da Ring" wire frames.
 *
 * <pre>
 *   byte0=0xFD byte1=0xDA byte2=0x10 byte3=len byte4=cmd byte5=sub byte6..=payload
 *   len = payloadLength + 6   (len counts the whole frame)
 * </pre>
 * No CRC. Little-endian multi-byte integers. A logical frame may span multiple
 * BLE notifications, so use {@link Reassembler} to accumulate + split them.
 *
 * <p>All static {@code decode*}/{@code parse*} methods are pure and unit-testable.
 * The record parsers operate on the <em>payload</em> — i.e. the bytes AFTER the
 * 6-byte header ({@link MoyoungRingConstants#HEADER_LEN}).
 */
public final class MoyoungRingPacket {

    public final int cmd;
    public final int sub;
    public final byte[] payload;

    public MoyoungRingPacket(int cmd, int sub, byte[] payload) {
        this.cmd = cmd;
        this.sub = sub;
        this.payload = payload != null ? payload : new byte[0];
    }

    /** Combined opcode (cmd &lt;&lt; 8 | sub) for switch dispatch. */
    public int opcode() { return MoyoungRingConstants.op(cmd, sub); }

    // ====================================================================
    //   Frame encode
    // ====================================================================

    /** Build a frame with no payload — {@code k_b(cmd,sub)}. */
    public static byte[] encode(int cmd, int sub) {
        return encode(cmd, sub, null);
    }

    /** Build a frame — {@code k_c(cmd,sub,payload)} with {@code len=payload.length+6}. */
    public static byte[] encode(int cmd, int sub, byte[] payload) {
        int payloadLen = payload != null ? payload.length : 0;
        int len = payloadLen + MoyoungRingConstants.FRAME_OVERHEAD;
        byte[] out = new byte[len];
        out[0] = MoyoungRingConstants.HDR0;
        out[1] = MoyoungRingConstants.HDR1;
        out[2] = MoyoungRingConstants.HDR2;
        out[3] = (byte) (len & 0xFF);
        out[4] = (byte) (cmd & 0xFF);
        out[5] = (byte) (sub & 0xFF);
        if (payloadLen > 0) {
            System.arraycopy(payload, 0, out, 6, payloadLen);
        }
        return out;
    }

    /** Convenience: a live-measurement start/stop frame ({@code {0x01}}/{@code {0x00}}). */
    public static byte[] liveMeasure(int cmd, int sub, boolean start) {
        return encode(cmd, sub, new byte[]{ start
                ? MoyoungRingConstants.MEASURE_START
                : MoyoungRingConstants.MEASURE_STOP });
    }

    /** History-steps request for a given day (0=today, 1=yesterday, ...). */
    public static byte[] historySteps(int dayValue) {
        return encode(MoyoungRingConstants.CMD_HIST_STEPS,
                MoyoungRingConstants.SUB_HIST_STEPS, new byte[]{ (byte) dayValue });
    }

    /**
     * Per-slot step HISTOGRAM ("steps details") request for a given day
     * (0=today, 1=yesterday, ...). Companion app: {@code c1/a0.b(day) = k.c(2,18,{day})}.
     */
    public static byte[] historyStepsDetails(int dayValue) {
        return encode(MoyoungRingConstants.CMD_HIST_STEPS_DETAIL,
                MoyoungRingConstants.SUB_HIST_STEPS_DETAIL, new byte[]{ (byte) dayValue });
    }

    /**
     * Paged daily-timeline ("timing") request — mirrors {@code c1.q/h/r/b0.b(day,idx)}
     * which all build {@code k.c(cmd, sub, {day, pageIndex})}. {@code day} is
     * 0=today, 1=yesterday, ...; {@code pageIndex} starts at 0 and the firmware
     * replies page-by-page until the metric's terminating index.
     */
    public static byte[] timingRequest(int cmd, int sub, int day, int pageIndex) {
        return encode(cmd, sub, new byte[]{ (byte) day, (byte) pageIndex });
    }

    /**
     * Set the device clock — {@code c1/f0.b()} = {@code k.c(1,1,{epoch:uint32 LE, tzHours:int8})}.
     *
     * <p>The payload is a 4-byte little-endian <b>Unix epoch in seconds</b> followed
     * by a 1-byte signed <b>timezone offset in whole hours</b>. The ring stores
     * {@code epoch} as its internal clock and derives its local wall-clock (used for
     * display and for the day boundary of the paged 5-minute timelines) as
     * {@code epoch + tzHours*3600}.
     *
     * <p>We replicate the official app's fixed GMT+8 scheme exactly (see
     * {@code c1/f0.b}): the app formats the current <em>local wall clock</em> as
     * {@code yyyy-MM-dd HH:mm:ss} then RE-PARSES those same digits in the GMT+8
     * time zone, and always sends {@code tzHours = 8}. The firmware therefore stores
     * timestamps in a fixed GMT+8 frame regardless of the phone's real offset, and
     * {@link #deviceTimeToUtcMs(long, TimeZone)} (mirroring {@code c1/f0.a}) is used
     * to convert recorded history timestamps back to true UTC. This is robust whether
     * or not the firmware honours an arbitrary offset byte, because on-ring display
     * and day boundaries depend only on {@code epoch + 8h} matching the wall clock.
     *
     * <p>Concretely: {@code epochSec = (nowMs + tz.getOffset(nowMs))/1000 - 8*3600}.
     * The first term reinterprets the local wall-clock digits as if they were GMT+8,
     * and subtracting 8h yields the epoch the firmware pairs with {@code tz=8} to
     * recover those digits. Verified byte-for-byte against a live app capture
     * ({@code fd da 10 0b 01 01 <epochLE> 08}).
     *
     * <p>The verified frame length is {@value MoyoungRingConstants#FRAME_OVERHEAD}+5.
     *
     * @param nowMs current wall-clock time (epoch millis)
     * @param tz    the timezone whose offset (at {@code nowMs}) defines the wall clock
     */
    public static byte[] setTime(long nowMs, TimeZone tz) {
        // Reinterpret the local wall-clock digits as GMT+8 and pair with tz=8, exactly
        // like the companion app's c1/f0.b (format-then-reparse-in-GMT+8 trick).
        long epochSec = (nowMs + tz.getOffset(nowMs)) / 1000L - 8L * 3600L;
        return encode(MoyoungRingConstants.CMD_SET_TIME, MoyoungRingConstants.SUB_SET_TIME,
                new byte[]{
                        (byte) (epochSec & 0xFF),
                        (byte) ((epochSec >>> 8) & 0xFF),
                        (byte) ((epochSec >>> 16) & 0xFF),
                        (byte) ((epochSec >>> 24) & 0xFF),
                        (byte) 0x08,
                });
    }

    /**
     * Convert a device-recorded history timestamp (Unix seconds stored in the fixed
     * GMT+8 frame written by {@link #setTime(long, TimeZone)}) back to true UTC
     * millis. Mirrors the companion app {@code c1/f0.a}: the stored value is the wall
     * clock relabelled as GMT+8, so to recover real UTC we add the 8h GMT+8 offset
     * (undoing the relabel) and subtract the phone's real offset at that instant.
     *
     * @param deviceEpochSec timestamp as stored on the ring (seconds, GMT+8 frame)
     * @param tz             the local time zone whose offset the wall clock used
     */
    public static long deviceTimeToUtcMs(long deviceEpochSec, TimeZone tz) {
        long ms = deviceEpochSec * 1000L;
        return ms + 28_800_000L - tz.getOffset(ms);
    }

    /**
     * Set the user profile (companion app: {@code c1/e = k.c(1,0,{height,weight,age,gender,stepLen})}).
     * The ring's sleep + calorie/distance algorithms need this before they log meaningfully.
     */
    public static byte[] setUserInfo(int heightCm, int weightKg, int age, int gender, int stepLenCm) {
        return encode(MoyoungRingConstants.CMD_SETTINGS, MoyoungRingConstants.SUB_SET_USER_INFO,
                new byte[]{ (byte) heightCm, (byte) weightKg, (byte) age, (byte) gender, (byte) stepLenCm });
    }

    /**
     * Enable automatic/timed background logging of a metric so the ring accumulates
     * a history series (companion app: {@code c1.q/h/r/b0.c(interval)}). The ring
     * encodes the interval in <b>5-minute units</b> (1..12 = 5..60 min), so the
     * requested minutes are divided by 5 and clamped. Verified from a live capture
     * where byte {@code 6} produced ~30-minute HR slots.
     *
     * @param sub         one of {@code SUB_ENABLE_*} (HR/HRV/SpO2/stress)
     * @param intervalMin desired cadence in real minutes (&le;0 = off)
     */
    public static byte[] enableTiming(int sub, int intervalMin) {
        int units;
        if (intervalMin <= 0) {
            units = 0;
        } else {
            units = Math.round(intervalMin / (float) MoyoungRingConstants.TIMING_INTERVAL_UNIT_MIN);
            units = Math.max(1, Math.min(MoyoungRingConstants.TIMING_INTERVAL_MAX_UNITS, units));
        }
        return encode(MoyoungRingConstants.CMD_SETTINGS, sub, new byte[]{ (byte) units });
    }

    /**
     * Enable/disable a timed monitor that takes a simple on/off flag rather than an
     * interval (temperature: companion app {@code c0.c(true)} = {@code k.c(1,13,{1})}).
     */
    public static byte[] enableTimingFlag(int sub, boolean on) {
        return encode(MoyoungRingConstants.CMD_SETTINGS, sub, new byte[]{ (byte) (on ? 1 : 0) });
    }

    // ====================================================================
    //   Frame decode + reassembly
    // ====================================================================

    /**
     * Decode a single complete logical frame. Returns {@code null} if the buffer
     * is too short, has a bad header, or the declared length exceeds the buffer.
     */
    public static MoyoungRingPacket decodeFrame(byte[] frame) {
        if (frame == null || frame.length < MoyoungRingConstants.HEADER_LEN) {
            return null;
        }
        if (frame[0] != MoyoungRingConstants.HDR0 || frame[1] != MoyoungRingConstants.HDR1
                || frame[2] != MoyoungRingConstants.HDR2) {
            return null;
        }
        int len = frame[3] & 0xFF;
        if (len < MoyoungRingConstants.FRAME_OVERHEAD || len > frame.length) {
            return null;
        }
        int cmd = frame[4] & 0xFF;
        int sub = frame[5] & 0xFF;
        int payloadLen = len - MoyoungRingConstants.HEADER_LEN;
        byte[] payload = new byte[payloadLen];
        if (payloadLen > 0) {
            System.arraycopy(frame, MoyoungRingConstants.HEADER_LEN, payload, 0, payloadLen);
        }
        return new MoyoungRingPacket(cmd, sub, payload);
    }

    /**
     * Stateful reassembler: BLE notifications may deliver a fraction of a frame,
     * or several frames concatenated. Feed each notification chunk and receive
     * the complete frames extracted so far.
     *
     * <p>Uses byte3 (len) to know the full frame length. Tolerates MTU up to 512.
     * Frames whose header != {@code FD DA} are resynchronised past (dropped).
     */
    public static final class Reassembler {
        private static final int MAX_BUFFER = 4096;
        private byte[] buf = new byte[0];

        public synchronized List<MoyoungRingPacket> add(byte[] chunk) {
            final List<MoyoungRingPacket> out = new ArrayList<>();
            if (chunk == null || chunk.length == 0) {
                return out;
            }
            // Append.
            byte[] merged = new byte[buf.length + chunk.length];
            System.arraycopy(buf, 0, merged, 0, buf.length);
            System.arraycopy(chunk, 0, merged, buf.length, chunk.length);
            buf = merged;

            int pos = 0;
            while (buf.length - pos >= MoyoungRingConstants.HEADER_LEN) {
                // Resync to a valid 3-byte header start (FD DA 10). Requiring the
                // third magic byte reduces the chance of interpreting corrupted or
                // unrelated bytes as a command (frames carry no CRC).
                if (buf[pos] != MoyoungRingConstants.HDR0
                        || buf[pos + 1] != MoyoungRingConstants.HDR1
                        || buf[pos + 2] != MoyoungRingConstants.HDR2) {
                    pos++;
                    continue;
                }
                int len = buf[pos + 3] & 0xFF;
                if (len < MoyoungRingConstants.FRAME_OVERHEAD) {
                    // Corrupt length — skip this magic byte and keep looking.
                    pos++;
                    continue;
                }
                if (buf.length - pos < len) {
                    break; // Wait for more bytes.
                }
                byte[] frame = new byte[len];
                System.arraycopy(buf, pos, frame, 0, len);
                MoyoungRingPacket pkt = decodeFrame(frame);
                if (pkt != null) {
                    out.add(pkt);
                }
                pos += len;
            }
            // Retain the unconsumed tail.
            if (pos > 0) {
                int remaining = buf.length - pos;
                byte[] tail = new byte[remaining];
                System.arraycopy(buf, pos, tail, 0, remaining);
                buf = tail;
            }
            // Guard against unbounded growth on persistent garbage.
            if (buf.length > MAX_BUFFER) {
                buf = new byte[0];
            }
            return out;
        }

        public synchronized void reset() {
            buf = new byte[0];
        }
    }

    // ====================================================================
    //   Little-endian helpers
    // ====================================================================

    private static long u32le(byte[] d, int off) {
        return ((long) (d[off] & 0xFF))
                | ((long) (d[off + 1] & 0xFF) << 8)
                | ((long) (d[off + 2] & 0xFF) << 16)
                | ((long) (d[off + 3] & 0xFF) << 24);
    }

    private static int u24le(byte[] d, int off) {
        return (d[off] & 0xFF)
                | ((d[off + 1] & 0xFF) << 8)
                | ((d[off + 2] & 0xFF) << 16);
    }

    private static int u16le(byte[] d, int off) {
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8);
    }

    // ====================================================================
    //   Record types
    // ====================================================================

    public static final class HrRecord {
        public final long timestampMs;
        public final int bpm;
        public HrRecord(long ts, int bpm) { this.timestampMs = ts; this.bpm = bpm; }
    }

    public static final class Spo2Record {
        public final long timestampMs;
        public final int spo2;
        public Spo2Record(long ts, int spo2) { this.timestampMs = ts; this.spo2 = spo2; }
    }

    public static final class HrvRecord {
        public final long timestampMs;
        public final int hrv;
        public HrvRecord(long ts, int hrv) { this.timestampMs = ts; this.hrv = hrv; }
    }

    public static final class StressRecord {
        public final long timestampMs;
        public final int stress;
        public StressRecord(long ts, int stress) { this.timestampMs = ts; this.stress = stress; }
    }

    /** Daily step totals (from cmd 2/13 reply). */
    public static final class StepsInfo {
        public final int day;
        public final long steps;
        public final long distance;
        public final long calories;
        public StepsInfo(int day, long steps, long distance, long calories) {
            this.day = day; this.steps = steps; this.distance = distance; this.calories = calories;
        }
    }

    /** Live steps triple (fdd1). */
    public static final class LiveSteps {
        public final int steps;
        public final int calories;
        public final int distance;
        public LiveSteps(int steps, int calories, int distance) {
            this.steps = steps; this.calories = calories; this.distance = distance;
        }
    }

    /**
     * Per-slot step histogram (from the cmd 2/18 "steps details" reply).
     * {@link #day} is a {@code CRPHistoryDay} offset (0=today, 1=yesterday, ...);
     * {@link #slotSteps} holds one step count per 30-minute slot, index 0 = the
     * 00:00-00:30 slot of that day.
     */
    public static final class StepsHistogram {
        public final int day;
        public final int[] slotSteps;
        public StepsHistogram(int day, int[] slotSteps) {
            this.day = day;
            this.slotSteps = slotSteps != null ? slotSteps : new int[0];
        }
    }

    /** A single sleep segment. state: 0=AWAKE,1=LIGHT,2=DEEP,3=REM. */
    public static final class SleepSegment {
        public static final int STATE_AWAKE = 0;
        public static final int STATE_LIGHT = 1;
        public static final int STATE_DEEP  = 2;
        public static final int STATE_REM   = 3;

        public final int state;
        public final long startTimeMs;
        public final int durationSec;
        public SleepSegment(int state, long startMs, int durationSec) {
            this.state = state; this.startTimeMs = startMs; this.durationSec = durationSec;
        }
    }

    /** Sleep details header (>=22 bytes). */
    public static final class SleepDetails {
        public final long startTimeMs;
        public final long endTimeMs;
        public final long sleepTimeMin;
        public final long efficiency;
        public final long score;
        public SleepDetails(long startMs, long endMs, long sleepMin, long eff, long score) {
            this.startTimeMs = startMs; this.endTimeMs = endMs;
            this.sleepTimeMin = sleepMin; this.efficiency = eff; this.score = score;
        }
    }

    public static final class DeviceInfo {
        public final String model;
        public final String name;
        public DeviceInfo(String model, String name) { this.model = model; this.name = name; }
    }

    // ====================================================================
    //   Record parsers (operate on payload, i.e. bytes after the 6-byte header)
    // ====================================================================

    /** HR realtime (cmd 1/9 or 2A37): value = first payload byte; 0xFF = "no reading". */
    public static int parseRealtimeScalar(byte[] payload) {
        if (payload == null || payload.length < 1) return -1;
        int v = payload[0] & 0xFF;
        if (v == 0xFF) return -1;
        return v;
    }

    /**
     * Realtime u16 LE scalar (cmd 1/10 HRV = ms; cmd 1/32 temperature = value/10 °C):
     * little-endian first two payload bytes. Returns -1 for the 0xFF/0xFFFF "no
     * reading" sentinels or a too-short payload (falls back to the 1-byte scalar).
     */
    public static int parseRealtimeU16Le(byte[] payload) {
        if (payload == null || payload.length < 2) return parseRealtimeScalar(payload);
        int v = (payload[0] & 0xFF) | ((payload[1] & 0xFF) << 8);
        if (v == 0xFFFF || v == 0xFF) return -1;
        return v;
    }

    /**
     * HR history list (cmd 2/9): {@code length % 5 == 1}; skip {@code payload[0]};
     * then repeating 5-byte records {@code [hr:uint8][ts:uint32 LE]}.
     * Sentinels (0, 255) are dropped here; full medical clamping is applied by
     * the caller.
     */
    public static List<HrRecord> parseHrRecords(byte[] payload) {
        List<HrRecord> out = new ArrayList<>();
        if (payload == null || payload.length < 6 || (payload.length % 5) != 1) {
            return out;
        }
        for (int i = 1; i + 5 <= payload.length; i += 5) {
            int hr = payload[i] & 0xFF;
            long ts = deviceTimeToUtcMs(u32le(payload, i + 1), TimeZone.getDefault());
            if (hr == 0 || hr == 0xFF || ts <= 0) continue;
            out.add(new HrRecord(ts, hr));
        }
        return out;
    }

    /** SpO2 history list (cmd 2/11): 5-byte {@code [spo2:uint8][ts:uint32 LE]}. */
    public static List<Spo2Record> parseSpo2Records(byte[] payload) {
        List<Spo2Record> out = new ArrayList<>();
        if (payload == null || payload.length < 6 || (payload.length % 5) != 1) {
            return out;
        }
        for (int i = 1; i + 5 <= payload.length; i += 5) {
            int spo2 = payload[i] & 0xFF;
            long ts = deviceTimeToUtcMs(u32le(payload, i + 1), TimeZone.getDefault());
            if (spo2 == 0 || spo2 == 0xFF || spo2 > 100 || ts <= 0) continue;
            out.add(new Spo2Record(ts, spo2));
        }
        return out;
    }

    /** HRV history list (cmd 2/10): {@code length % 6 == 1}; 6-byte {@code [hrv:uint16 LE][ts:uint32 LE]}. */
    public static List<HrvRecord> parseHrvRecords(byte[] payload) {
        List<HrvRecord> out = new ArrayList<>();
        if (payload == null || payload.length < 7 || (payload.length % 6) != 1) {
            return out;
        }
        for (int i = 1; i + 6 <= payload.length; i += 6) {
            int hrv = u16le(payload, i);
            long ts = deviceTimeToUtcMs(u32le(payload, i + 2), TimeZone.getDefault());
            if (hrv == 0 || hrv == 0xFFFF || ts <= 0) continue;
            out.add(new HrvRecord(ts, hrv));
        }
        return out;
    }

    /** Stress history list (cmd 2/25): {@code length % 5 == 1}; 5-byte {@code [stress:uint8][ts:uint32 LE]}. */
    public static List<StressRecord> parseStressRecords(byte[] payload) {
        List<StressRecord> out = new ArrayList<>();
        if (payload == null || payload.length < 6 || (payload.length % 5) != 1) {
            return out;
        }
        for (int i = 1; i + 5 <= payload.length; i += 5) {
            int stress = payload[i] & 0xFF;
            long ts = deviceTimeToUtcMs(u32le(payload, i + 1), TimeZone.getDefault());
            if (stress == 0xFF || ts <= 0) continue;
            out.add(new StressRecord(ts, stress));
        }
        return out;
    }

    /**
     * Steps current (cmd 2/13 reply):
     * {@code [day:uint8][steps:uint32 LE][distance:uint32 LE][?:uint32][calories:uint32 LE]}
     * (&gt;=17 bytes). Preferred source for daily totals.
     */
    public static StepsInfo parseStepsInfo(byte[] payload) {
        if (payload == null || payload.length < 17) return null;
        int day = payload[0] & 0xFF;
        long steps = u32le(payload, 1);
        long distance = u32le(payload, 5);
        long calories = u32le(payload, 13);
        return new StepsInfo(day, steps, distance, calories);
    }

    /**
     * Parse the per-slot step HISTOGRAM (cmd 2/18 reply):
     * {@code [day:uint8][slot0:uint16 LE][slot1:uint16 LE]...} — one u16 step
     * count per 30-minute slot. Mirrors the companion app {@code i1/k.c(byte[])}:
     * {@code slot = z1.a.g(bArr[i+1], bArr[i]) = (bArr[i+1]<<8)|bArr[i]}.
     *
     * <p>Returns {@code null} for an empty payload. A trailing odd byte (payload
     * length not {@code 1 + 2*slots}) is ignored.
     */
    public static StepsHistogram parseStepsHistogram(byte[] payload) {
        if (payload == null || payload.length < 1) return null;
        int day = payload[0] & 0xFF;
        int slots = (payload.length - 1) / 2;
        int[] out = new int[slots];
        for (int i = 0; i < slots; i++) {
            int lo = payload[1 + i * 2] & 0xFF;
            int hi = payload[1 + i * 2 + 1] & 0xFF;
            out[i] = (hi << 8) | lo;
        }
        return new StepsHistogram(day, out);
    }

    /**
     * Zero out future slots when the histogram is for TODAY, mirroring the
     * companion-app filter {@code i1/k.c}: {@code cutoff = minutesSinceMidnight/30 + 1};
     * slots at index &gt;= cutoff are cleared (the current slot is kept). Returns a
     * copy; the input is not modified.
     */
    public static int[] filterTodayFutureSlots(int[] slotSteps, int minutesSinceMidnight) {
        if (slotSteps == null) return new int[0];
        int[] out = slotSteps.clone();
        int cutoff = (minutesSinceMidnight / MoyoungRingConstants.STEPS_SLOT_MINUTES) + 1;
        for (int i = Math.max(cutoff, 0); i < out.length; i++) {
            out[i] = 0;
        }
        return out;
    }

    /**
     * Local start-of-day (epoch millis) for {@code daysAgo} days before the day
     * containing {@code nowMs}, in the given time zone. {@code daysAgo=0} yields
     * the start of today. Mirrors the companion app {@code z1/c.b(-daysAgo)}.
     */
    public static long startOfDayMillis(long nowMs, int daysAgo, TimeZone tz) {
        Calendar cal = Calendar.getInstance(tz);
        cal.setTimeInMillis(nowMs);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.DAY_OF_MONTH, -daysAgo);
        return cal.getTimeInMillis();
    }

    /**
     * Epoch-millis timestamp of a given 30-minute step slot within a day:
     * {@code dayStartMillis + slotIndex * 30 minutes}.
     */
    public static long slotTimestampMillis(long dayStartMillis, int slotIndex) {
        return dayStartMillis
                + (long) slotIndex * MoyoungRingConstants.STEPS_SLOT_MINUTES * 60_000L;
    }

    /**
     * Live steps triple(s) from fdd1: 3-byte little-endian fields
     * {@code [steps:uint24][calories:uint24][distance:uint24]}.
     */
    public static LiveSteps parseLiveSteps(byte[] data) {
        if (data == null || data.length < 9) return null;
        int steps = u24le(data, 0);
        int calories = u24le(data, 3);
        int distance = u24le(data, 6);
        return new LiveSteps(steps, calories, distance);
    }

    /**
     * Sleep stages (part of sleep query replies): {@code length % 3 == 1};
     * skip {@code payload[0]}; 3-byte records {@code [state:uint8][hour:uint8][minute:uint8]}.
     *
     * <p>Only relative hour:minute is transmitted, so absolute segment start
     * times are chained from {@code sessionStartMs}: the first segment begins at
     * {@code sessionStartMs}, and each subsequent segment advances by the wall-clock
     * delta to the next record's hour:minute (wrapping past midnight). Each
     * segment's duration is the gap to the next record; the final segment is
     * bounded by {@code sessionEndMs} (or dropped if that is unknown / non-positive).
     */
    public static List<SleepSegment> parseSleepSegments(byte[] payload,
                                                        long sessionStartMs,
                                                        long sessionEndMs) {
        List<SleepSegment> out = new ArrayList<>();
        if (payload == null || payload.length < 4 || (payload.length % 3) != 1) {
            return out;
        }
        final List<int[]> raw = new ArrayList<>(); // {state, minuteOfDay}
        for (int i = 1; i + 3 <= payload.length; i += 3) {
            int state = payload[i] & 0xFF;
            int hour = payload[i + 1] & 0xFF;
            int minute = payload[i + 2] & 0xFF;
            if (hour > 23 || minute > 59) continue;
            raw.add(new int[]{ state, hour * 60 + minute });
        }
        if (raw.isEmpty()) return out;

        long[] absStart = new long[raw.size()];
        absStart[0] = sessionStartMs;
        for (int k = 1; k < raw.size(); k++) {
            int deltaMin = raw.get(k)[1] - raw.get(k - 1)[1];
            if (deltaMin < 0) deltaMin += 24 * 60; // wrap past midnight
            absStart[k] = absStart[k - 1] + (long) deltaMin * 60_000L;
        }
        for (int k = 0; k < raw.size(); k++) {
            long endMs = (k + 1 < raw.size()) ? absStart[k + 1] : sessionEndMs;
            if (endMs <= absStart[k]) {
                continue; // unknown / zero-length tail
            }
            int durSec = (int) ((endMs - absStart[k]) / 1000L);
            out.add(new SleepSegment(raw.get(k)[0], absStart[k], durSec));
        }
        return out;
    }

    /**
     * Sleep details record (&gt;=22 bytes):
     * {@code [id:u8][type:u8][startTs:u32 LE][endTs:u32 LE][sleepTimeMin:u32]
     *        [efficiency:u32][score:u32]} (ts in sec, converted to ms).
     */
    public static SleepDetails parseSleepDetails(byte[] payload) {
        if (payload == null || payload.length < 22) return null;
        long startMs = u32le(payload, 2) * 1000L;
        long endMs = u32le(payload, 6) * 1000L;
        long sleepMin = u32le(payload, 10);
        long eff = u32le(payload, 14);
        long score = u32le(payload, 18);
        if (startMs <= 0 || endMs <= startMs) return null;
        return new SleepDetails(startMs, endMs, sleepMin, eff, score);
    }

    /**
     * Device info (cmd 5/2): length-prefixed ascii strings, e.g.
     * {@code [03]"R26"[05]"VRing"} → model + name.
     */
    public static DeviceInfo parseDeviceInfo(byte[] payload) {
        if (payload == null || payload.length < 1) return null;
        int i = 0;
        String model = readLenPrefixedString(payload, i);
        if (model == null) return null;
        i += 1 + model.length();
        String name = i < payload.length ? readLenPrefixedString(payload, i) : null;
        return new DeviceInfo(model, name);
    }

    private static String readLenPrefixedString(byte[] payload, int off) {
        if (off < 0 || off >= payload.length) return null;
        int len = payload[off] & 0xFF;
        if (off + 1 + len > payload.length) return null;
        return new String(payload, off + 1, len, StandardCharsets.US_ASCII);
    }

    /** Firmware (cmd 3/3): ascii string, e.g. "MOY-R263-2.2.2". */
    public static String parseFirmware(byte[] payload) {
        if (payload == null || payload.length == 0) return null;
        int end = payload.length;
        for (int i = 0; i < payload.length; i++) {
            if (payload[i] == 0) { end = i; break; }
        }
        // Skip a leading length prefix if present (non-printable first byte).
        int start = 0;
        if (payload[0] < 0x20 && payload.length > 1 && (payload[0] & 0xFF) == end - 1) {
            start = 1;
        }
        return new String(payload, start, end - start, StandardCharsets.US_ASCII).trim();
    }

    // ====================================================================
    //   Paged daily-timeline ("timing") parsing + reassembly
    // ====================================================================
    //
    // The ring exposes per-day HR/SpO2/HRV/stress timelines that arrive PAGED:
    // several response frames per day. Each frame's PAYLOAD (bytes after the
    // 6-byte header) is:
    //   [day:u8][pageIndex:u8][slot0][slot1]...   (slot width per metric)
    // Pages for a day are concatenated in page order; concatenated slot index i
    // maps to startOfLocalDay(day) + i * TIMING_SLOT_MINUTES. A value of 0 means
    // "no reading" for that slot and yields no sample. This mirrors the
    // decompiled state machines i1.f/d/g/l.

    /** Per-slot value width for a timing metric. */
    public enum SlotFormat {
        /** One unsigned byte per slot (HR / SpO2 / stress). */
        U8,
        /** Two little-endian bytes per slot (HRV). */
        U16LE
    }

    /** A single parsed timing page: day, page index, and its per-slot values. */
    public static final class TimingPage {
        public final int day;
        public final int pageIndex;
        public final int[] slots;
        public TimingPage(int day, int pageIndex, int[] slots) {
            this.day = day; this.pageIndex = pageIndex; this.slots = slots;
        }
    }

    /** A fully reassembled daily timeline: day + concatenated slot values. */
    public static final class TimingTimeline {
        public final int day;
        public final int[] slots;
        public TimingTimeline(int day, int[] slots) {
            this.day = day; this.slots = slots;
        }
    }

    /** A timestamped scalar produced from a timeline slot. */
    public static final class TimedValue {
        public final long timestampMs;
        public final int value;
        public TimedValue(long timestampMs, int value) {
            this.timestampMs = timestampMs; this.value = value;
        }
    }

    /**
     * Decode a raw timing payload (bytes AFTER the 6-byte header) into a
     * {@link TimingPage}. Returns {@code null} when the payload is too short to
     * carry {@code [day][pageIndex]}. For {@link SlotFormat#U16LE} a dangling
     * final byte (odd slot region) is ignored. Slot values are returned as
     * unsigned integers WITHOUT medical clamping (0 = "no reading"); callers
     * apply the plausibility gate.
     */
    public static TimingPage parseTimingPage(byte[] payload, SlotFormat format) {
        if (payload == null || payload.length < 2) return null;
        int day = payload[0] & 0xFF;
        int pageIndex = payload[1] & 0xFF;
        final int[] slots;
        if (format == SlotFormat.U16LE) {
            int n = (payload.length - 2) / 2;
            slots = new int[n];
            for (int k = 0; k < n; k++) {
                int off = 2 + k * 2;
                slots[k] = (payload[off] & 0xFF) | ((payload[off + 1] & 0xFF) << 8);
            }
        } else {
            int n = payload.length - 2;
            slots = new int[n];
            for (int k = 0; k < n; k++) {
                slots[k] = payload[2 + k] & 0xFF;
            }
        }
        return new TimingPage(day, pageIndex, slots);
    }

    /**
     * Reassembles paged daily timelines for a single metric. Pages arrive one
     * per response frame; feed each into {@link #add}. When the terminating page
     * index for the day arrives AND all pages {@code 0..last} are present, a
     * {@link TimingTimeline} is produced. Robust to missing / out-of-order pages:
     * a premature or duplicate page never crashes, and a last page with a gap
     * simply yields no timeline (the run is dropped rather than half-assembled).
     *
     * <p>Multiple days can be walked concurrently — pages are keyed by
     * {@code day * 100 + pageIndex} so today (0) and yesterday (1) never collide.
     */
    public static final class TimingReassembler {
        private static final int DAY_STRIDE = 100;
        private final int lastPageIndex;
        private final SlotFormat format;
        private final Map<Integer, int[]> pages = new HashMap<>();

        public TimingReassembler(int lastPageIndex, SlotFormat format) {
            this.lastPageIndex = lastPageIndex;
            this.format = format;
        }

        /** Result of feeding one page. */
        public static final class Result {
            public final int day;
            public final int pageIndex;
            /** True while more pages remain — request {@link #nextPageIndex}. */
            public final boolean needNextPage;
            public final int nextPageIndex;
            /** Non-null only when this was the last page and the run is complete. */
            public final TimingTimeline timeline;
            Result(int day, int pageIndex, boolean needNextPage,
                   int nextPageIndex, TimingTimeline timeline) {
                this.day = day; this.pageIndex = pageIndex;
                this.needNextPage = needNextPage; this.nextPageIndex = nextPageIndex;
                this.timeline = timeline;
            }
        }

        /**
         * Feed one raw timing payload. Returns {@code null} if the payload is
         * malformed (too short). Otherwise returns a {@link Result} describing
         * whether to request the next page or a completed timeline.
         */
        public synchronized Result add(byte[] payload) {
            TimingPage page = parseTimingPage(payload, format);
            if (page == null) return null;
            // Bounds guard: drop malformed/out-of-protocol pages so a corrupted
            // index can never drive an unbounded or wrapping next-page request
            // (timingRequest casts to byte, so 256 would wrap to 0 on the wire).
            if (page.pageIndex < 0 || page.pageIndex > lastPageIndex
                    || page.day < 0 || page.day >= MoyoungRingConstants.TIMING_DAYS_BACK) {
                return null;
            }
            pages.put(page.day * DAY_STRIDE + page.pageIndex, page.slots);

            if (page.pageIndex < lastPageIndex) {
                // Not the terminating page: ask for the next one.
                return new Result(page.day, page.pageIndex, true, page.pageIndex + 1, null);
            }
            // Terminating page (pageIndex == lastPageIndex): assemble pages 0..last.
            TimingTimeline timeline = assemble(page.day);
            return new Result(page.day, page.pageIndex, false, -1, timeline);
        }

        private TimingTimeline assemble(int day) {
            // Gather + remove this day's pages. If any page is missing, drop the
            // partial run (return null) but still clear the stragglers so a later
            // reconnect starts clean.
            int[][] collected = new int[lastPageIndex + 1][];
            boolean complete = true;
            for (int idx = 0; idx <= lastPageIndex; idx++) {
                int[] slots = pages.remove(day * DAY_STRIDE + idx);
                if (slots == null) {
                    complete = false;
                } else {
                    collected[idx] = slots;
                }
            }
            if (!complete) return null;
            int total = 0;
            for (int[] s : collected) total += s.length;
            int[] all = new int[total];
            int pos = 0;
            for (int[] s : collected) {
                System.arraycopy(s, 0, all, pos, s.length);
                pos += s.length;
            }
            return new TimingTimeline(day, all);
        }

        public synchronized void reset() {
            pages.clear();
        }
    }

    /**
     * Turn a reassembled timeline into timestamped values. Slot {@code i} maps to
     * {@code startOfDayMs + i * slotMinutes * 60000}. A slot is emitted only when
     * its value is accepted by {@code filter} (which must reject 0 / sentinels /
     * out-of-range values); all other slots are silently skipped, preserving the
     * index → timestamp alignment of the remaining slots.
     */
    public static List<TimedValue> toSamples(TimingTimeline timeline,
                                             long startOfDayMs,
                                             int slotMinutes,
                                             SlotFilter filter) {
        List<TimedValue> out = new ArrayList<>();
        if (timeline == null || timeline.slots == null) return out;
        final long slotMs = (long) slotMinutes * 60_000L;
        for (int i = 0; i < timeline.slots.length; i++) {
            int v = timeline.slots[i];
            if (filter != null && !filter.accept(v)) continue;
            out.add(new TimedValue(startOfDayMs + i * slotMs, v));
        }
        return out;
    }

    /**
     * Turn a SINGLE timing page into timestamped values WITHOUT waiting for the
     * whole day to reassemble. Slot {@code i} of the page maps to the day-global
     * slot {@code globalIndex = page.pageIndex * slotsPerPage + i}, i.e. to
     * {@code startOfDayMs + globalIndex * slotMinutes * 60000}. A slot is emitted
     * only when its value is accepted by {@code filter} (which must reject 0 /
     * sentinels / out-of-range values). This enables PER-PAGE persistence so the
     * driver can fetch just the page(s) that still need data (incremental sync).
     */
    public static List<TimedValue> pageToSamples(TimingPage page,
                                                 long startOfDayMs,
                                                 int slotsPerPage,
                                                 int slotMinutes,
                                                 SlotFilter filter) {
        List<TimedValue> out = new ArrayList<>();
        if (page == null || page.slots == null) return out;
        final long slotMs = (long) slotMinutes * 60_000L;
        for (int i = 0; i < page.slots.length; i++) {
            int v = page.slots[i];
            if (filter != null && !filter.accept(v)) continue;
            final int globalIndex = page.pageIndex * slotsPerPage + i;
            out.add(new TimedValue(startOfDayMs + (long) globalIndex * slotMs, v));
        }
        return out;
    }

    /** Predicate gating which slot values become samples (e.g. plausibility). */
    public interface SlotFilter {
        boolean accept(int value);
    }

    /** Utility: hex-dump for logging. */
    public static String hex(byte[] d) {
        if (d == null) return "null";
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }
}
