/*  Copyright (C) 2026 Gadgetbridge contributors

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
package nodomain.freeyourgadget.gadgetbridge.devices.ycbt;

import java.util.UUID;

public final class YcbtConstants {
    public static final String SUPPORTED_DEVICE_NAME_PATTERN = "^R(?:10|11)M(?:[ _][0-9A-F]{4})?$";
    public static final String CONFIG_MEASURE_BLOOD_PRESSURE = "ycbt_measure_blood_pressure";
    public static final String CONFIG_MEASURE_SPO2 = "ycbt_measure_spo2";
    public static final String PREF_HEART_RATE_MONITORING_CONFIGURED =
            "ycbt_heart_rate_monitoring_configured";
    public static final String PREF_SPO2_MONITORING_CONFIGURED = "ycbt_spo2_monitoring_configured";
    public static final String PREF_CAPABILITY_HEART_RATE = "ycbt_capability_heart_rate";
    public static final String PREF_CAPABILITY_SPO2 = "ycbt_capability_spo2";
    public static final String PREF_CAPABILITY_HRV = "ycbt_capability_hrv";
    public static final String PREF_CAPABILITY_STEPS = "ycbt_capability_steps";
    public static final String PREF_CAPABILITY_SLEEP = "ycbt_capability_sleep";
    public static final String PREF_CAPABILITY_FIND_DEVICE = "ycbt_capability_find_device";
    public static final String PREF_CAPABILITY_BLOOD_PRESSURE = "ycbt_capability_blood_pressure";
    public static final String PREF_CAPABILITY_MANUAL_HEART_RATE = "ycbt_capability_manual_heart_rate";
    public static final String PREF_CAPABILITY_MANUAL_BLOOD_PRESSURE = "ycbt_capability_manual_blood_pressure";
    public static final String PREF_CAPABILITY_MANUAL_SPO2 = "ycbt_capability_manual_spo2";
    public static final String PREF_CAPABILITY_MANUAL_HRV = "ycbt_capability_manual_hrv";
    public static final String PREF_CAPABILITY_TEMPERATURE = "ycbt_capability_temperature";
    public static final String PREF_CAPABILITY_BLOOD_SUGAR = "ycbt_capability_blood_sugar";
    public static final String PREF_CAPABILITY_STRESS = "ycbt_capability_stress";
    public static final byte[] OBSERVED_MANUFACTURER_BYTES = new byte[]{0x10, 0x78};

    public static final UUID SERVICE_UUID = UUID.fromString("be940000-7333-be46-b7ae-689e71722bd5");
    public static final UUID COMMAND_REPLY_CHARACTERISTIC_UUID =
            UUID.fromString("be940001-7333-be46-b7ae-689e71722bd5");
    public static final UUID STREAM_HISTORY_CHARACTERISTIC_UUID =
            UUID.fromString("be940003-7333-be46-b7ae-689e71722bd5");
    public static final UUID WRITE_CHARACTERISTIC_UUID = COMMAND_REPLY_CHARACTERISTIC_UUID;

    private YcbtConstants() {
    }
}
