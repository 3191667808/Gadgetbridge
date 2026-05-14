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

/** Wire constants for the Oura Ring 4 BLE protocol — derived from BLE traffic
 *  observation. Wire-byte aliases (OP_*, EXT_*, EVT_*, FEATURE_*) are kept stable;
 *  see BLE.md in the protocol-research tree. */
public final class OuraOpcode {
    // Top-level opcodes (leading byte of a phone↔ring frame).
    public static final int OP_SET_REALTIME_MEAS = 0x06;          // realtime IMU/PPG stream — start/stop control
    public static final int OP_SET_REALTIME_MEAS_RESP = 0x07;     // realtime stream ack
    public static final int OP_GET_FW = 0x08;                     // firmware-version query
    public static final int OP_GET_FW_RESP = 0x09;
    public static final int OP_GET_BATTERY = 0x0C;
    public static final int OP_GET_BATTERY_RESP = 0x0D;
    public static final int OP_GET_EVENTS = 0x10;                 // event-log drain request
    public static final int OP_GET_EVENTS_SUMMARY = 0x11;
    public static final int OP_SYNC_TIME = 0x12;
    public static final int OP_SYNC_TIME_RESP = 0x13;
    public static final int OP_SET_BLE_MODE = 0x16;
    public static final int OP_SET_BLE_MODE_RESP = 0x17;
    public static final int OP_GET_PRODUCT_INFO = 0x18;
    public static final int OP_GET_PRODUCT_INFO_RESP = 0x19;
    public static final int OP_RESET_MEMORY = 0x1A;               // factory reset; invalidates auth key
    public static final int OP_SET_NOTIFICATION = 0x1C;
    public static final int OP_SET_NOTIFICATION_RESP = 0x1D;
    public static final int OP_SET_AUTH_KEY = 0x24;
    public static final int OP_SET_AUTH_KEY_RESP = 0x25;
    public static final int OP_ENABLE_FLIGHT_MODE = 0x26;         // 2-byte payload, off-payload unknown
    public static final int OP_CHECK_SLEEP_ANALYSIS = 0x28;
    public static final int OP_CHECK_SLEEP_ANALYSIS_RESP = 0x29;
    public static final int OP_EXT = 0x2F;
    // 0x41 / 0x42 / 0x43 form an alternate event-stream envelope (OPEN / CONFIRMATION /
    // DATA_PACKET) observed in BLE traffic but never used by current firmware — the
    // drain still rides 0x10. The byte values overlap with event tags EVT_TIME_SYNC /
    // EVT_LOG_TEXT inside bundled-event responses; OuraEventParser.isEventFrame()
    // handles routing.

    // Extended subtags (after OP_EXT). Wire form: `0x2F <len> <ext-tag> <payload>`.
    public static final int EXT_GET_CAPS = 0x01;                  // capability-page query
    public static final int EXT_CAPS_RESP = 0x02;
    public static final int EXT_SET_BUNDLING = 0x03;
    public static final int EXT_SET_BUNDLING_RESP = 0x04;
    public static final int EXT_GET_FEATURE_STATUS = 0x20;        // feature-state read
    public static final int EXT_FEATURE_STATUS_RESP = 0x21;
    public static final int EXT_SET_FEATURE_MODE = 0x22;          // feature on/off toggle
    public static final int EXT_SET_FEATURE_MODE_RESP = 0x23;
    public static final int EXT_SET_FEATURE_SUBSCRIPTION = 0x26;  // feature subscription toggle (distinct from
    public static final int EXT_SET_FEATURE_SUBSCRIPTION_RESP = 0x27; // the top-level 0x26 flight-mode opcode)
    public static final int EXT_SET_FEATURE_PARAMETERS = 0x29;    // per-feature config-blob write
    public static final int EXT_SET_FEATURE_PARAMETERS_RESP = 0x2A;
    public static final int EXT_GET_NONCE = 0x2B;                 // AES auth nonce challenge
    public static final int EXT_NONCE_RESP = 0x2C;
    public static final int EXT_AUTH = 0x2D;                      // AES auth response
    public static final int EXT_AUTH_RESP = 0x2E;

    public static final int AUTH_OK = 0x00;
    public static final int AUTH_FAIL = 0x01;
    public static final int AUTH_FACTORY_RESET = 0x02;
    public static final int AUTH_DIFFERENT_DEVICE = 0x03;

    public static final int BLE_MODE_NORMAL = 0x00;
    public static final int BLE_MODE_SYNC = 0x02;

    // Feature IDs (one byte each — referenced by the EXT_*_FEATURE_* opcodes above).
    public static final int FEATURE_BACKGROUND_DFU = 0x00;
    public static final int FEATURE_RESEARCH_DATA = 0x01;
    public static final int FEATURE_DAYTIME_HR = 0x02;
    public static final int FEATURE_EXERCISE_HR = 0x03;
    public static final int FEATURE_SPO2 = 0x04;
    public static final int FEATURE_BUNDLING = 0x05;
    public static final int FEATURE_ENCRYPTED_API = 0x06;         // defined; never written in observed FW
    public static final int FEATURE_TAP_TO_TAG = 0x07;
    public static final int FEATURE_RESTING_HR = 0x08;
    public static final int FEATURE_APP_AUTH = 0x09;
    public static final int FEATURE_BLE_MODE = 0x0A;
    public static final int FEATURE_REAL_STEPS = 0x0B;
    public static final int FEATURE_EXPERIMENTAL = 0x0C;
    public static final int FEATURE_CVA_PPG = 0x0D;
    public static final int FEATURE_CHARGING_CONTROL = 0x0E;
    public static final int FEATURE_AMBIENT_LIGHT = 0x10;
    public static final int FEATURE_SPECIAL_FEATURE = 0x11;
    public static final int FEATURE_RAW_DATA_SAMPLER = 0x12;
    public static final int FEATURE_ATLAS = 0x15;
    public static final int FEATURE_LONG_EVENTS = 0x16;

    public static final int FEATURE_MODE_OFF = 0x00;
    public static final int FEATURE_MODE_AUTO = 0x01;

    // Event tags — names from `com.ouraring.ringeventparser.data.RingEventType` (decompiled
    // Oura companion app). Comments document the protobuf message each tag carries after
    // JNI decode (see `oura_sources/.../ringeventparser/*EventKt.java`). Wire byte layouts
    // are not in the decompiled Java — they live in the JNI binary — and remain inferred
    // until verified against on-device captures with ground truth.
    public static final int EVT_RING_START_IND = 0x41;              // ring boot/start notification
    public static final int EVT_TIME_SYNC = 0x42;                   // API_TIME_SYNC_IND
    public static final int EVT_DEBUG_EVENT = 0x43;                 // API_DEBUG_EVENT_IND — ASCII diagnostics
    public static final int EVT_IBI = 0x44;                         // API_IBI_EVENT — single beat IBI
    public static final int EVT_STATE_CHANGE = 0x45;                // API_STATE_CHANGE_IND
    public static final int EVT_TEMP = 0x46;                        // API_TEMP_EVENT — 3 thermistor channels
    public static final int EVT_MOTION = 0x47;                      // API_MOTION_EVENT — windowed accel summary
    public static final int EVT_SLEEP_PERIOD_INFO = 0x48;           // API_SLEEP_PERIOD_INFO
    public static final int EVT_SLEEP_SUMMARY_1 = 0x49;             // API_SLEEP_SUMMARY_1
    public static final int EVT_PPG_AMPLITUDE_IND = 0x4A;           // API_PPG_AMPLITUDE_IND
    public static final int EVT_SLEEP_PHASE = 0x4B;                 // API_SLEEP_PHASE_INFO
    public static final int EVT_SLEEP_SUMMARY_2 = 0x4C;             // API_SLEEP_SUMMARY_2
    public static final int EVT_RING_SLEEP_FEATURE_INFO = 0x4D;
    public static final int EVT_SLEEP_PHASE_DETAIL = 0x4E;          // API_SLEEP_PHASE_DETAILS
    public static final int EVT_SLEEP_SUMMARY_3 = 0x4F;             // API_SLEEP_SUMMARY_3
    public static final int EVT_ACTIVITY = 0x50;                    // API_ACTIVITY_INFO — proto: timestamp[], stepCount[]
    public static final int EVT_ACTIVITY_SUMMARY_1 = 0x51;
    public static final int EVT_ACTIVITY_SUMMARY_2 = 0x52;
    public static final int EVT_WEAR_EVENT = 0x53;                  // API_WEAR_EVENT — on-finger/off detection
    public static final int EVT_RECOVERY = 0x54;                    // API_RECOVERY_SUMMARY
    public static final int EVT_SLEEP_HR = 0x55;                    // API_SLEEP_HR
    public static final int EVT_ALERT = 0x56;                       // API_ALERT_EVENT
    public static final int EVT_RING_SLEEP_FEATURE_INFO_2 = 0x57;
    public static final int EVT_SLEEP_SUMMARY_4 = 0x58;             // API_SLEEP_SUMMARY_4
    public static final int EVT_EDA = 0x59;                         // API_EDA_EVENT — electrodermal activity
    public static final int EVT_SLEEP_PHASE_DATA = 0x5A;            // API_SLEEP_PHASE_DATA
    public static final int EVT_BLE_CONNECTION_IND = 0x5B;          // API_BLE_CONNECTION_IND
    public static final int EVT_USER_INFO = 0x5C;                   // API_USER_INFO
    public static final int EVT_HRV = 0x5D;                         // API_HRV_EVENT — proto: timestamp[], averageHr5Min[], averageRmssd5Min[]
    public static final int EVT_SELFTEST = 0x5E;                    // API_SELFTEST_EVENT
    public static final int EVT_RAW_ACM = 0x5F;                     // API_RAW_ACM_EVENT — raw accelerometer
    public static final int EVT_IBI_AMP = 0x60;                     // API_IBI_AND_AMPLITUDE_EVENT — 7× (u8 IBI×8ms, u8 amp); verified against ground truth
    public static final int EVT_DEBUG_DATA = 0x61;                  // API_DEBUG_DATA — binary diagnostics, sub-typed
    public static final int EVT_ON_DEMAND_MEAS = 0x62;              // API_ON_DEMAND_MEAS — manual SpO2/HR trigger result
    public static final int EVT_PPG_PEAK = 0x63;                    // API_PPG_PEAK_EVENT
    public static final int EVT_RAW_PPG = 0x64;                     // API_RAW_PPG_EVENT
    public static final int EVT_ON_DEMAND_SESSION = 0x65;           // API_ON_DEMAND_SESSION
    public static final int EVT_ON_DEMAND_MOTION = 0x66;            // API_ON_DEMAND_MOTION
    public static final int EVT_RAW_PPG_SUMMARY = 0x67;             // API_RAW_PPG_SUMMARY
    public static final int EVT_RAW_PPG_DATA = 0x68;                // API_RAW_PPG_DATA
    public static final int EVT_TEMP_PERIOD = 0x69;                 // API_TEMP_PERIOD — single u16LE summary
    public static final int EVT_SLEEP_PERIOD_INFO_2 = 0x6A;
    public static final int EVT_MOTION_PERIOD = 0x6B;               // API_MOTION_PERIOD (was misnamed EVT_RAW_PPG_6B)
    public static final int EVT_FEATURE_SESSION = 0x6C;             // API_FEATURE_SESSION (was misnamed EVT_STATE_6C)
    public static final int EVT_MEAS_QUALITY = 0x6D;                // API_MEAS_QUALITY_EVENT
    public static final int EVT_SPO2_IBI_AMP = 0x6E;                // API_SPO2_IBI_AND_AMPLITUDE_EVENT (was misnamed EVT_SENSOR_6E)
    public static final int EVT_SPO2 = 0x6F;                        // API_SPO2_EVENT — proto: timestamp, beatIndex, beatOffset, flush (NOT SpO2 %)
    public static final int EVT_SPO2_SMOOTH = 0x70;                 // API_SPO2_SMOOTHED_EVENT
    public static final int EVT_GREEN_IBI_AMP = 0x71;               // API_GREEN_IBI_AND_AMP_EVENT — green-LED PPG IBI
    public static final int EVT_SLEEP_ACM_PERIOD = 0x72;            // API_SLEEP_ACM_PERIOD (was misnamed EVT_METRIC_72)
    public static final int EVT_EHR_TRACE = 0x73;                   // API_EHR_TRACE_EVENT — exercise HR diagnostic
    public static final int EVT_EHR_ACM_INTENSITY = 0x74;           // API_EHR_ACM_INTENSITY_EVENT
    public static final int EVT_SLEEP_TEMP = 0x75;                  // API_SLEEP_TEMP_EVENT — proto: timestamp[], temp[]; struct: temp1..temp7 (7 readings per record)
    public static final int EVT_BEDTIME = 0x76;                     // API_BEDTIME_PERIOD — proto: bedtimeStart, bedtimeEnd, timezones
    public static final int EVT_SPO2_DC = 0x77;                     // API_SPO2_DC_EVENT — raw DC photodiode (was misnamed EVT_STREAM_77)
    public static final int EVT_SELFTEST_DATA = 0x78;               // API_SELFTEST_DATA_EVENT
    public static final int EVT_TAG = 0x79;                         // API_TAG_EVENT
    public static final int EVT_REAL_STEPS_F1 = 0x7A;               // API_REAL_STEP_EVENT_FEATURE_ONE — STEPS, NOT tap (was misnamed EVT_TAP)
    public static final int EVT_REAL_STEPS_F2 = 0x7B;               // API_REAL_STEP_EVENT_FEATURE_TWO — additional steps features
    public static final int EVT_GREEN_IBI_QUALITY = 0x7C;           // API_GREEN_IBI_QUALITY_EVENT
    public static final int EVT_CVA_RAW_PPG = 0x7D;                 // API_CVA_RAW_PPG_DATA
    public static final int EVT_SCAN_START = 0x7E;                  // API_SCAN_START — measurement-window begin (was misnamed EVT_PPG_ENV_A)
    public static final int EVT_SCAN_END = 0x7F;                    // API_SCAN_END — measurement-window end (was misnamed EVT_PPG_ENV_B)
    public static final int EVT_AMBIENT = 0x80;                     // API_AMBIENT_EVENT — ambient-light sensor (was misnamed EVT_PPG_ENV_C)
    public static final int EVT_TIME_SYNC_SKIPPED = 0x81;           // API_TIME_SYNC_IND_SKIPPED (was misnamed EVT_PPG_DELTA_81)
    public static final int EVT_AOHR = 0x82;                        // API_AOHR_EVENT — always-on HR, proto: timestamp, bpm[], algorithmType, delaySamples, quality, sampleCount, sampleIntervalMs (was misnamed EVT_STATUS_82)
    public static final int EVT_ATLAS_METADATA = 0x83;              // API_ATLAS_METADATA (was misnamed EVT_PERIOD_83)
    public static final int EVT_ATLAS_RAW_BIOZ_DATA = 0x84;         // API_ATLAS_RAW_BIOZ_DATA
    public static final int EVT_SPO2_R_PI = 0x8B;                   // API_SPO2_R_PI_EVENT

    // Legacy aliases — kept temporarily for migration. TODO: remove after callers updated.
    public static final int EVT_LOG_TEXT = EVT_DEBUG_EVENT;
    public static final int EVT_LOG_CHG = EVT_STATE_CHANGE;
    public static final int EVT_STRESS = EVT_EDA;
    public static final int EVT_INTERNAL_5B = EVT_BLE_CONNECTION_IND;
    public static final int EVT_METRIC = EVT_DEBUG_DATA;
    public static final int EVT_RAW_PPG_6B = EVT_MOTION_PERIOD;
    public static final int EVT_STATE_6C = EVT_FEATURE_SESSION;
    public static final int EVT_SENSOR_6E = EVT_SPO2_IBI_AMP;
    public static final int EVT_METRIC_72 = EVT_SLEEP_ACM_PERIOD;
    public static final int EVT_STREAM_77 = EVT_SPO2_DC;
    public static final int EVT_TAP = EVT_REAL_STEPS_F1;
    public static final int EVT_PPG_ENV_A = EVT_SCAN_START;
    public static final int EVT_PPG_ENV_B = EVT_SCAN_END;
    public static final int EVT_PPG_ENV_C = EVT_AMBIENT;
    public static final int EVT_PPG_DELTA_81 = EVT_TIME_SYNC_SKIPPED;
    public static final int EVT_STATUS_82 = EVT_AOHR;
    public static final int EVT_PERIOD_83 = EVT_ATLAS_METADATA;

    private OuraOpcode() {
    }
}
