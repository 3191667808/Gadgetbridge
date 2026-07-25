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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

/**
 * Allows third party apps to start and stop realtime heart rate measurements without any
 * Gadgetbridge UI interaction. Gated per-device behind the developer settings, mirroring the
 * third party alarms API.
 */
public class RealtimeHrCommandReceiver extends BroadcastReceiver {
    private static final Logger LOG = LoggerFactory.getLogger(RealtimeHrCommandReceiver.class);

    private static final String macAddrPattern = "^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$";

    public static final String COMMAND_START_REALTIME_HR = "nodomain.freeyourgadget.gadgetbridge.command.START_REALTIME_HR";
    public static final String COMMAND_STOP_REALTIME_HR = "nodomain.freeyourgadget.gadgetbridge.command.STOP_REALTIME_HR";

    public static final String EXTRA_MAC_ADDR = "device";

    public static final String PREF_THIRD_PARTY_APPS_START_REALTIME_HR = "third_party_apps_start_realtime_hr";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        final String action = intent.getAction();

        if (!COMMAND_START_REALTIME_HR.equals(action) && !COMMAND_STOP_REALTIME_HR.equals(action)) {
            LOG.warn("Unexpected action {}", action);
            return;
        }

        final String deviceAddress = intent.getStringExtra(EXTRA_MAC_ADDR);

        if (deviceAddress == null) {
            LOG.warn("Missing device address");
            return;
        }

        if (!deviceAddress.matches(macAddrPattern)) {
            LOG.warn("Device address '{}' does not match '{}'", deviceAddress, macAddrPattern);
            return;
        }

        final GBDevice targetDevice = GBApplication.app()
                .getDeviceManager()
                .getDeviceByAddress(deviceAddress);

        if (targetDevice == null) {
            LOG.warn("Unknown device {}", deviceAddress);
            return;
        }

        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(targetDevice.getAddress());

        if (prefs == null || !prefs.getBoolean(PREF_THIRD_PARTY_APPS_START_REALTIME_HR, false)) {
            LOG.warn("Starting realtime heart rate measurements from 3rd party apps not allowed for {}", deviceAddress);
            return;
        }

        if (!targetDevice.isInitialized()) {
            LOG.warn("Device {} not initialized, ignoring realtime heart rate command", deviceAddress);
            return;
        }

        final boolean enable = COMMAND_START_REALTIME_HR.equals(action);
        LOG.info("Got third party request to {} realtime heart rate measurement on {}",
                enable ? "start" : "stop", deviceAddress);
        GBApplication.deviceService(targetDevice).onEnableRealtimeHeartRateMeasurement(enable);
    }

    public IntentFilter buildFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(COMMAND_START_REALTIME_HR);
        intentFilter.addAction(COMMAND_STOP_REALTIME_HR);
        return intentFilter;
    }
}
