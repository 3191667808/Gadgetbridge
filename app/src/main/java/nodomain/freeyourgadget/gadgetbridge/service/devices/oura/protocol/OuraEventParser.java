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
package nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol;

import nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol.OuraTimeSync.BootClock;

public final class OuraEventParser {

    public interface EventSink {
        void onUnknown(int tag, long wallClockMs, byte[] payload);

        /** Called for every record in a bundle BEFORE the per-tag dispatch, with the raw u32LE
         *  bootTs value read straight from the record header. Used by the drain loop to track
         *  the highest bootTs seen — required because converting wallClockMs back to bootTicks
         *  via BootClock.bootTicksOf is lossy (10 Hz wall-time = 1 sec → up to 9 tick truncation
         *  on roundtrip). With small batches near the cursor, the roundtripped maxBootTs can
         *  equal iterationStartCursor → handleEventsSummary's cursorAdvanced check fires
         *  cursorStuck and halts the drain mid-backlog. Feeding raw bootTs here avoids that. */
        default void onRecordBootTs(long bootTs) {
        }

        default void onIbi(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_IBI, wallClockMs, payload);
        }

        default void onIbiAmp(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_IBI_AMP, wallClockMs, payload);
        }

        default void onTemp(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_TEMP, wallClockMs, payload);
        }

        default void onTempPeriod(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_TEMP_PERIOD, wallClockMs, payload);
        }

        default void onMotion(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_MOTION, wallClockMs, payload);
        }

        default void onTimeSyncEcho(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_TIME_SYNC, wallClockMs, payload);
        }

        default void onSleepPhase(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_SLEEP_PHASE, wallClockMs, payload);
        }

        default void onSleepPhaseDetail(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_SLEEP_PHASE_DETAIL, wallClockMs, payload);
        }

        default void onBedtime(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_BEDTIME, wallClockMs, payload);
        }

        default void onActivity(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_ACTIVITY, wallClockMs, payload);
        }

        default void onRecovery(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_RECOVERY, wallClockMs, payload);
        }

        default void onSleepHr(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_SLEEP_HR, wallClockMs, payload);
        }

        default void onHrv(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_HRV, wallClockMs, payload);
        }

        default void onSpo2(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_SPO2, wallClockMs, payload);
        }

        default void onSpo2Smoothed(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_SPO2_SMOOTH, wallClockMs, payload);
        }

        default void onSleepTemp(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_SLEEP_TEMP, wallClockMs, payload);
        }

        default void onMetric(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_METRIC, wallClockMs, payload);
        }

        default void onLogText(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_LOG_TEXT, wallClockMs, payload);
        }

        default void onLogChg(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_LOG_CHG, wallClockMs, payload);
        }

        default void onPpgEnvelopeA(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_PPG_ENV_A, wallClockMs, payload);
        }

        default void onPpgEnvelopeB(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_PPG_ENV_B, wallClockMs, payload);
        }

        default void onPpgEnvelopeC(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_PPG_ENV_C, wallClockMs, payload);
        }

        default void onPpgDelta(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_PPG_DELTA_81, wallClockMs, payload);
        }

        default void onSensor6E(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_SENSOR_6E, wallClockMs, payload);
        }

        default void onMetric72(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_METRIC_72, wallClockMs, payload);
        }

        default void onStream77(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_STREAM_77, wallClockMs, payload);
        }

        default void onTap(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_TAP, wallClockMs, payload);
        }

        default void onStress(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_STRESS, wallClockMs, payload);
        }

        default void onRawPpg6B(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_RAW_PPG_6B, wallClockMs, payload);
        }

        default void onState6C(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_STATE_6C, wallClockMs, payload);
        }

        default void onStatus82(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_STATUS_82, wallClockMs, payload);
        }

        default void onPeriod83(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_PERIOD_83, wallClockMs, payload);
        }

        default void onInternal5B(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_INTERNAL_5B, wallClockMs, payload);
        }

        /** Tag 0x71 API_GREEN_IBI_AND_AMP_EVENT — same proto schema as 0x60 IbiAndAmplitudeEvent
         *  but sourced from the green-LED PPG channel. Wire layout assumed identical:
         *  7× u8 IBI codes (×8ms) + 7× u8 amplitude. */
        default void onGreenIbiAmp(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_GREEN_IBI_AMP, wallClockMs, payload);
        }

        /** Tag 0x6E API_SPO2_IBI_AND_AMPLITUDE_EVENT — IBI+amp captured during SpO2 measurement
         *  window. Proto schema identical to 0x60. */
        default void onSpo2IbiAmp(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_SPO2_IBI_AMP, wallClockMs, payload);
        }

        /** Tag 0x82 API_AOHR_EVENT — always-on HR sampled continuously during wake hours.
         *  Proto: timestamp, bpm[], algorithmType, delaySamples, quality, sampleCount, sampleIntervalMs.
         *  Body layout TBD — 4–5B "no-data" records observed when sampleCount=0. */
        default void onAohr(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_AOHR, wallClockMs, payload);
        }

        /** Tag 0x7A / 0x7B API_REAL_STEP_EVENT_FEATURE_ONE/TWO — step-detection features.
         *  Not raw step counts — input to the step-detection algorithm. Steps live in
         *  0x50 API_ACTIVITY_INFO (proto: timestamp[], stepCount[]). */
        default void onRealStepsF1(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_REAL_STEPS_F1, wallClockMs, payload);
        }

        default void onRealStepsF2(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_REAL_STEPS_F2, wallClockMs, payload);
        }

        /** Tag 0x53 API_WEAR_EVENT — on-finger / off-finger detection. */
        default void onWearEvent(long wallClockMs, byte[] payload) {
            onUnknown(OuraOpcode.EVT_WEAR_EVENT, wallClockMs, payload);
        }
    }

    public static boolean isEventFrame(final byte[] frame) {
        if (frame == null || frame.length == 0) {
            return false;
        }
        return (frame[0] & 0xff) >= 0x41;
    }

    public static int parseFrame(final byte[] frame, final EventSink sink, final BootClock clock) {
        if (frame == null) {
            return 0;
        }
        int count = 0;
        int off = 0;
        while (off + 2 <= frame.length) {
            final int tag = frame[off] & 0xff;
            if (tag < 0x41) {
                break;
            }
            final int length = frame[off + 1] & 0xff;
            if (length < 4 || off + 2 + length > frame.length) {
                break;
            }
            final long bootTs = OuraPacket.readU32LE(frame, off + 2);
            final byte[] payload = new byte[length - 4];
            System.arraycopy(frame, off + 6, payload, 0, length - 4);
            final long wallSeconds = clock != null ? clock.wallClockOf(bootTs) : bootTs;
            final long wallMs = wallSeconds * 1000L;
            sink.onRecordBootTs(bootTs);
            dispatch(tag, wallMs, payload, sink);
            off += 2 + length;
            count++;
        }
        return count;
    }

    private static void dispatch(final int tag, final long wallMs, final byte[] payload, final EventSink sink) {
        switch (tag) {
            case OuraOpcode.EVT_TIME_SYNC:
                sink.onTimeSyncEcho(wallMs, payload);
                break;
            case OuraOpcode.EVT_LOG_TEXT:
                sink.onLogText(wallMs, payload);
                break;
            case OuraOpcode.EVT_IBI:
                sink.onIbi(wallMs, payload);
                break;
            case OuraOpcode.EVT_LOG_CHG:
                sink.onLogChg(wallMs, payload);
                break;
            case OuraOpcode.EVT_TEMP:
                sink.onTemp(wallMs, payload);
                break;
            case OuraOpcode.EVT_MOTION:
                sink.onMotion(wallMs, payload);
                break;
            case OuraOpcode.EVT_SLEEP_PHASE:
                sink.onSleepPhase(wallMs, payload);
                break;
            case OuraOpcode.EVT_SLEEP_PHASE_DETAIL:
                sink.onSleepPhaseDetail(wallMs, payload);
                break;
            case OuraOpcode.EVT_ACTIVITY:
                sink.onActivity(wallMs, payload);
                break;
            case OuraOpcode.EVT_RECOVERY:
                sink.onRecovery(wallMs, payload);
                break;
            case OuraOpcode.EVT_SLEEP_HR:
                sink.onSleepHr(wallMs, payload);
                break;
            case OuraOpcode.EVT_STRESS:
                sink.onStress(wallMs, payload);
                break;
            case OuraOpcode.EVT_INTERNAL_5B:
                sink.onInternal5B(wallMs, payload);
                break;
            case OuraOpcode.EVT_HRV:
                sink.onHrv(wallMs, payload);
                break;
            case OuraOpcode.EVT_IBI_AMP:
                sink.onIbiAmp(wallMs, payload);
                break;
            case OuraOpcode.EVT_METRIC:
                sink.onMetric(wallMs, payload);
                break;
            case OuraOpcode.EVT_RAW_PPG_6B:
                sink.onRawPpg6B(wallMs, payload);
                break;
            case OuraOpcode.EVT_STATE_6C:
                sink.onState6C(wallMs, payload);
                break;
            case OuraOpcode.EVT_SPO2_IBI_AMP:  // 0x6E — was misnamed EVT_SENSOR_6E
                sink.onSpo2IbiAmp(wallMs, payload);
                break;
            case OuraOpcode.EVT_TEMP_PERIOD:
                sink.onTempPeriod(wallMs, payload);
                break;
            case OuraOpcode.EVT_SPO2:
                sink.onSpo2(wallMs, payload);
                break;
            case OuraOpcode.EVT_SPO2_SMOOTH:
                sink.onSpo2Smoothed(wallMs, payload);
                break;
            case OuraOpcode.EVT_METRIC_72:
                sink.onMetric72(wallMs, payload);
                break;
            case OuraOpcode.EVT_SLEEP_TEMP:
                sink.onSleepTemp(wallMs, payload);
                break;
            case OuraOpcode.EVT_BEDTIME:
                sink.onBedtime(wallMs, payload);
                break;
            case OuraOpcode.EVT_STREAM_77:
                sink.onStream77(wallMs, payload);
                break;
            case OuraOpcode.EVT_TAP:
                sink.onTap(wallMs, payload);
                break;
            case OuraOpcode.EVT_PPG_ENV_A:
                sink.onPpgEnvelopeA(wallMs, payload);
                break;
            case OuraOpcode.EVT_PPG_ENV_B:
                sink.onPpgEnvelopeB(wallMs, payload);
                break;
            case OuraOpcode.EVT_PPG_ENV_C:
                sink.onPpgEnvelopeC(wallMs, payload);
                break;
            case OuraOpcode.EVT_PPG_DELTA_81:
                sink.onPpgDelta(wallMs, payload);
                break;
            case OuraOpcode.EVT_STATUS_82:
                sink.onStatus82(wallMs, payload);
                break;
            case OuraOpcode.EVT_PERIOD_83:
                sink.onPeriod83(wallMs, payload);
                break;
            case OuraOpcode.EVT_GREEN_IBI_AMP:
                sink.onGreenIbiAmp(wallMs, payload);
                break;
            case OuraOpcode.EVT_WEAR_EVENT:
                sink.onWearEvent(wallMs, payload);
                break;
            case OuraOpcode.EVT_REAL_STEPS_F2:
                sink.onRealStepsF2(wallMs, payload);
                break;
            default:
                sink.onUnknown(tag, wallMs, payload);
                break;
        }
    }

    private OuraEventParser() {
    }
}
