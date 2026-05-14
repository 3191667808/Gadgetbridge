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
package nodomain.freeyourgadget.gadgetbridge.test.oura;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol.OuraOpcode;

/** Wire-value regression: pins every Oura opcode/event-tag/feature-ID constant to its
 *  on-the-wire byte. A rename that accidentally changes a value would corrupt every
 *  outbound frame; this test makes such a mistake impossible to land silently. */
public class OuraOpcodeConstantsTest {

    @Test
    public void topLevelOpcodes_areWireStable() {
        assertEquals(0x06, OuraOpcode.OP_SET_REALTIME_MEAS);
        assertEquals(0x07, OuraOpcode.OP_SET_REALTIME_MEAS_RESP);
        assertEquals(0x08, OuraOpcode.OP_GET_FW);
        assertEquals(0x09, OuraOpcode.OP_GET_FW_RESP);
        assertEquals(0x0C, OuraOpcode.OP_GET_BATTERY);
        assertEquals(0x0D, OuraOpcode.OP_GET_BATTERY_RESP);
        assertEquals(0x10, OuraOpcode.OP_GET_EVENTS);
        assertEquals(0x11, OuraOpcode.OP_GET_EVENTS_SUMMARY);
        assertEquals(0x12, OuraOpcode.OP_SYNC_TIME);
        assertEquals(0x13, OuraOpcode.OP_SYNC_TIME_RESP);
        assertEquals(0x16, OuraOpcode.OP_SET_BLE_MODE);
        assertEquals(0x17, OuraOpcode.OP_SET_BLE_MODE_RESP);
        assertEquals(0x18, OuraOpcode.OP_GET_PRODUCT_INFO);
        assertEquals(0x19, OuraOpcode.OP_GET_PRODUCT_INFO_RESP);
        assertEquals(0x1A, OuraOpcode.OP_RESET_MEMORY);
        assertEquals(0x1C, OuraOpcode.OP_SET_NOTIFICATION);
        assertEquals(0x1D, OuraOpcode.OP_SET_NOTIFICATION_RESP);
        assertEquals(0x24, OuraOpcode.OP_SET_AUTH_KEY);
        assertEquals(0x25, OuraOpcode.OP_SET_AUTH_KEY_RESP);
        assertEquals(0x26, OuraOpcode.OP_ENABLE_FLIGHT_MODE);
        assertEquals(0x28, OuraOpcode.OP_CHECK_SLEEP_ANALYSIS);
        assertEquals(0x29, OuraOpcode.OP_CHECK_SLEEP_ANALYSIS_RESP);
        assertEquals(0x2F, OuraOpcode.OP_EXT);
    }

    @Test
    public void extendedSubtags_areWireStable() {
        assertEquals(0x01, OuraOpcode.EXT_GET_CAPS);
        assertEquals(0x02, OuraOpcode.EXT_CAPS_RESP);
        assertEquals(0x03, OuraOpcode.EXT_SET_BUNDLING);
        assertEquals(0x04, OuraOpcode.EXT_SET_BUNDLING_RESP);
        assertEquals(0x20, OuraOpcode.EXT_GET_FEATURE_STATUS);
        assertEquals(0x21, OuraOpcode.EXT_FEATURE_STATUS_RESP);
        assertEquals(0x22, OuraOpcode.EXT_SET_FEATURE_MODE);
        assertEquals(0x23, OuraOpcode.EXT_SET_FEATURE_MODE_RESP);
        assertEquals(0x26, OuraOpcode.EXT_SET_FEATURE_SUBSCRIPTION);
        assertEquals(0x27, OuraOpcode.EXT_SET_FEATURE_SUBSCRIPTION_RESP);
        assertEquals(0x29, OuraOpcode.EXT_SET_FEATURE_PARAMETERS);
        assertEquals(0x2A, OuraOpcode.EXT_SET_FEATURE_PARAMETERS_RESP);
        assertEquals(0x2B, OuraOpcode.EXT_GET_NONCE);
        assertEquals(0x2C, OuraOpcode.EXT_NONCE_RESP);
        assertEquals(0x2D, OuraOpcode.EXT_AUTH);
        assertEquals(0x2E, OuraOpcode.EXT_AUTH_RESP);
    }

    @Test
    public void featureIds_areWireStable() {
        assertEquals(0x00, OuraOpcode.FEATURE_BACKGROUND_DFU);
        assertEquals(0x01, OuraOpcode.FEATURE_RESEARCH_DATA);
        assertEquals(0x02, OuraOpcode.FEATURE_DAYTIME_HR);
        assertEquals(0x03, OuraOpcode.FEATURE_EXERCISE_HR);
        assertEquals(0x04, OuraOpcode.FEATURE_SPO2);
        assertEquals(0x05, OuraOpcode.FEATURE_BUNDLING);
        assertEquals(0x06, OuraOpcode.FEATURE_ENCRYPTED_API);
        assertEquals(0x07, OuraOpcode.FEATURE_TAP_TO_TAG);
        assertEquals(0x08, OuraOpcode.FEATURE_RESTING_HR);
        assertEquals(0x09, OuraOpcode.FEATURE_APP_AUTH);
        assertEquals(0x0A, OuraOpcode.FEATURE_BLE_MODE);
        assertEquals(0x0B, OuraOpcode.FEATURE_REAL_STEPS);
        assertEquals(0x0C, OuraOpcode.FEATURE_EXPERIMENTAL);
        assertEquals(0x0D, OuraOpcode.FEATURE_CVA_PPG);
        assertEquals(0x0E, OuraOpcode.FEATURE_CHARGING_CONTROL);
        assertEquals(0x10, OuraOpcode.FEATURE_AMBIENT_LIGHT);
        assertEquals(0x11, OuraOpcode.FEATURE_SPECIAL_FEATURE);
        assertEquals(0x12, OuraOpcode.FEATURE_RAW_DATA_SAMPLER);
        assertEquals(0x15, OuraOpcode.FEATURE_ATLAS);
        assertEquals(0x16, OuraOpcode.FEATURE_LONG_EVENTS);
    }

    @Test
    public void eventTags_areWireStable() {
        assertEquals(0x42, OuraOpcode.EVT_TIME_SYNC);
        assertEquals(0x43, OuraOpcode.EVT_LOG_TEXT);
        assertEquals(0x44, OuraOpcode.EVT_IBI);
        assertEquals(0x45, OuraOpcode.EVT_LOG_CHG);
        assertEquals(0x46, OuraOpcode.EVT_TEMP);
        assertEquals(0x47, OuraOpcode.EVT_MOTION);
        assertEquals(0x4B, OuraOpcode.EVT_SLEEP_PHASE);
        assertEquals(0x4E, OuraOpcode.EVT_SLEEP_PHASE_DETAIL);
        assertEquals(0x50, OuraOpcode.EVT_ACTIVITY);
        assertEquals(0x54, OuraOpcode.EVT_RECOVERY);
        assertEquals(0x55, OuraOpcode.EVT_SLEEP_HR);
        assertEquals(0x59, OuraOpcode.EVT_STRESS);
        assertEquals(0x5B, OuraOpcode.EVT_INTERNAL_5B);
        assertEquals(0x5D, OuraOpcode.EVT_HRV);
        assertEquals(0x60, OuraOpcode.EVT_IBI_AMP);
        assertEquals(0x61, OuraOpcode.EVT_METRIC);
        assertEquals(0x69, OuraOpcode.EVT_TEMP_PERIOD);
        assertEquals(0x6B, OuraOpcode.EVT_RAW_PPG_6B);
        assertEquals(0x6C, OuraOpcode.EVT_STATE_6C);
        assertEquals(0x6E, OuraOpcode.EVT_SENSOR_6E);
        assertEquals(0x6F, OuraOpcode.EVT_SPO2);
        assertEquals(0x70, OuraOpcode.EVT_SPO2_SMOOTH);
        assertEquals(0x72, OuraOpcode.EVT_METRIC_72);
        assertEquals(0x75, OuraOpcode.EVT_SLEEP_TEMP);
        assertEquals(0x76, OuraOpcode.EVT_BEDTIME);
        assertEquals(0x77, OuraOpcode.EVT_STREAM_77);
        assertEquals(0x7A, OuraOpcode.EVT_TAP);
        assertEquals(0x7E, OuraOpcode.EVT_PPG_ENV_A);
        assertEquals(0x7F, OuraOpcode.EVT_PPG_ENV_B);
        assertEquals(0x80, OuraOpcode.EVT_PPG_ENV_C);
        assertEquals(0x81, OuraOpcode.EVT_PPG_DELTA_81);
        assertEquals(0x82, OuraOpcode.EVT_STATUS_82);
        assertEquals(0x83, OuraOpcode.EVT_PERIOD_83);
    }
}
