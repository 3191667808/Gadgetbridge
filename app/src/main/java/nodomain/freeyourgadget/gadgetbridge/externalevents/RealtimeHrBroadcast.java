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
package nodomain.freeyourgadget.gadgetbridge.externalevents;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.activities.HeartRateUtils;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

/**
 * Sends a global broadcast for each realtime heart rate measurement received from a device, so
 * that third party apps can consume live heart rate data without having to open a second
 * Bluetooth connection to the device.
 * <p>
 * Broadcasts are edge-triggered: one broadcast per measurement received, so they only fire while
 * a realtime measurement session is running on the device. Disabled by default - must be enabled
 * per-device in the device-specific developer settings (Intent API section), mirroring the third
 * party alarms API.
 */
public final class RealtimeHrBroadcast {
    private static final Logger LOG = LoggerFactory.getLogger(RealtimeHrBroadcast.class);

    public static final String ACTION_REALTIME_HR = "nodomain.freeyourgadget.gadgetbridge.action.REALTIME_HR";

    public static final String EXTRA_HR = "hr";
    public static final String EXTRA_TIMESTAMP = "timestamp";
    public static final String EXTRA_MAC_ADDR = "device";

    public static final String PREF_THIRD_PARTY_APPS_REALTIME_HR = "third_party_apps_realtime_hr";

    private RealtimeHrBroadcast() {
        // utility class
    }

    /**
     * Broadcast a realtime heart rate measurement to third party apps, if enabled for this device.
     *
     * @param context   the context to send the broadcast from
     * @param device    the device the measurement originated from
     * @param heartRate the heart rate, in bpm - implausible values (sentinels like 0 or 255) are
     *                  not broadcast
     */
    public static void sendIfEnabled(final Context context, final GBDevice device, final int heartRate) {
        if (heartRate < HeartRateUtils.MIN_HEART_RATE_VALUE || heartRate > HeartRateUtils.MAX_HEART_RATE_VALUE) {
            // not measured or invalid (eg. 0, -1, 255 sentinels)
            return;
        }

        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(device.getAddress());
        if (prefs == null || !prefs.getBoolean(PREF_THIRD_PARTY_APPS_REALTIME_HR, false)) {
            return;
        }

        LOG.debug("Broadcasting realtime HR {} for {}", heartRate, device.getAddress());

        final Intent intent = new Intent(ACTION_REALTIME_HR)
                .putExtra(EXTRA_HR, heartRate)
                .putExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
                .putExtra(EXTRA_MAC_ADDR, device.getAddress());
        context.sendBroadcast(intent);
    }
}
