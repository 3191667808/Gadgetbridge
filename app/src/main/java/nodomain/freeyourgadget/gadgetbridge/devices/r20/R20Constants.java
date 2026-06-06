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
package nodomain.freeyourgadget.gadgetbridge.devices.r20;

import java.util.UUID;

/**
 * Protocol constants for the R20 smart ring (Yucheng YCBT-SDK family).
 * Internal model name reported by the firmware is "R11M".
 *
 * <p>Wire format:
 * <pre>
 *   [group(1)] [key(1)] [len_lo(1)] [len_hi(1)] [payload...] [crc_lo(1)] [crc_hi(1)]
 * </pre>
 * where {@code len = payload + 6}. CRC-16 is the custom Yucheng variant
 * (see {@link nodomain.freeyourgadget.gadgetbridge.service.devices.r20.R20Packet#crc16}).
 */
public final class R20Constants {

    private R20Constants() { }

    // -------- Vendor BLE service & characteristics --------

    public static final UUID UUID_SERVICE =
            UUID.fromString("be940000-7333-be46-b7ae-689e71722bd5");
    /** Write + indicate. Most command replies arrive here. */
    public static final UUID UUID_CHAR_WRITE =
            UUID.fromString("be940001-7333-be46-b7ae-689e71722bd5");
    /** Indicate only — push frames (HR/SpO2/BP/battery uploads). */
    public static final UUID UUID_CHAR_NOTIFY =
            UUID.fromString("be940003-7333-be46-b7ae-689e71722bd5");

    // -------- DataType codes (group<<8 | key) --------

    public static final int SETTING_TIME              = 0x0100;
    public static final int SETTING_USER_INFO         = 0x0103;
    public static final int ENABLE_HEALTH_SENSORS     = 0x0104;
    public static final int SET_MONITOR_INTERVAL      = 0x010C;
    public static final int ENABLE_BG_SPO2_MONITOR    = 0x0126;

    public static final int GET_DEVICE_INFO           = 0x0200;
    public static final int GET_DEVICE_SUPPORT_FN     = 0x0201;
    public static final int GET_DEVICE_NAME           = 0x0203;
    public static final int GET_NOW_STEP              = 0x020C;
    public static final int GET_REAL_BLOOD_OXYGEN     = 0x0211;
    public static final int GET_POWER_STATISTICS      = 0x0225;

    public static final int APP_CONTROL_REAL          = 0x0309;
    public static final int APP_START_MEASUREMENT     = 0x032F;

    public static final int MEASUREMENT_COMPLETE      = 0x040E;
    public static final int MEASUREMENT_STATUS        = 0x0413;

    // History request opcodes (Group_Health = 5)
    public static final int HEALTH_HISTORY_SPORT      = 0x0502;
    public static final int HEALTH_HISTORY_SLEEP      = 0x0504;
    public static final int HEALTH_HISTORY_HEART      = 0x0506;
    public static final int HEALTH_HISTORY_BLOOD      = 0x0508;
    public static final int HEALTH_HISTORY_ALL        = 0x0509;
    // History data-stream opcodes (returned after request)
    public static final int HEALTH_STREAM_SPORT       = 0x0511;
    public static final int HEALTH_STREAM_SLEEP       = 0x0513;
    public static final int HEALTH_STREAM_HEART       = 0x0515;
    public static final int HEALTH_STREAM_BLOOD       = 0x0517;
    public static final int HEALTH_STREAM_ALL         = 0x0518;
    public static final int HEALTH_HISTORY_ACK        = 0x0580;

    /** Delete-after-sync opcodes — the firmware retains history records
     *  until the app explicitly clears them.  Without these the ring
     *  re-sends the same records on every sync. */
    public static final int HEALTH_DELETE_SPORT       = 0x0540;
    public static final int HEALTH_DELETE_SLEEP       = 0x0541;
    public static final int HEALTH_DELETE_HEART       = 0x0542;
    public static final int HEALTH_DELETE_BLOOD       = 0x0543;
    public static final int HEALTH_DELETE_ALL         = 0x0544;
    public static final int REAL_UPLOAD_SNAPSHOT      = 0x0600;

    // Real-time push frames (group 6)
    public static final int REAL_UPLOAD_HEART         = 0x0601;
    public static final int REAL_UPLOAD_BLOOD_OXY     = 0x0602;
    public static final int REAL_UPLOAD_BLOOD_PRESS   = 0x0603;
    public static final int REAL_UPLOAD_BODY_DATA     = 0x0610;
    public static final int REAL_UPLOAD_BATTERY       = 0x0615;

    // -------- Per-command magic "permission" prefixes --------
    // R20 firmware returns 0xFE without these 2-byte payload prefixes on
    // the corresponding Get* commands.

    public static final byte[] MAGIC_GET_DEVICE_INFO       = {'G', 'C'};
    public static final byte[] MAGIC_GET_DEVICE_SUPPORT_FN = {'G', 'F'};
    public static final byte[] MAGIC_GET_DEVICE_NAME       = {'G', 'P'};
    public static final byte[] MAGIC_GET_REAL_BLOOD_OXYGEN = {'I', 'S'};

    // -------- APP_START_MEASUREMENT type byte --------
    // Payload layout: [0x01, MEASURE_*, action]   action: 0x01=start, 0x00=stop
    public static final int MEASURE_HEART_RATE     = 0;
    public static final int MEASURE_BLOOD_PRESSURE = 1;
    public static final int MEASURE_BLOOD_OXYGEN   = 2;
    public static final int MEASURE_RESPIRATION    = 3;
    public static final int MEASURE_TEMPERATURE    = 4;
    public static final int MEASURE_BLOOD_SUGAR    = 5;
    public static final int MEASURE_URIC_ACID      = 6;
    public static final int MEASURE_KETONE         = 7;
    public static final int MEASURE_EDA            = 8;
    public static final int MEASURE_LIPIDS         = 9;
    public static final int MEASURE_HRV            = 10;
    public static final int MEASURE_PPG_RAW        = 11;
    public static final int MEASURE_BP_ALT         = 12;
    public static final int MEASURE_VO2MAX         = 13;

    /** Epoch offset used by Yucheng firmware: 2000-01-01 in Unix seconds. */
    public static final long EPOCH_2000_UNIX_SECONDS = 946684800L;

    public static final String MANUFACTURER = "Yucheng / Zhishang Wear";
    public static final String SCAN_NAME    = "R20";
}
