/*  Copyright (C) 2025

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.marshall.acton3;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventDisplayMessage;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventUpdatePreferences;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.actions.SetDeviceStateAction;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.IntentListener;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.deviceinfo.DeviceInfo;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.deviceinfo.DeviceInfoProfile;

import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.*;

public class MarshallActon3Support extends AbstractBTLESingleDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(MarshallActon3Support.class);

    private final DeviceInfoProfile<MarshallActon3Support> deviceInfoProfile;

    private final IntentListener listener = new IntentListener() {
        @Override
        public void notify(Intent intent) {
            String action = intent.getAction();
            if (DeviceInfoProfile.ACTION_DEVICE_INFO.equals(action)) {
                handleDeviceInfo(intent.getParcelableExtra(DeviceInfoProfile.EXTRA_DEVICE_INFO));
            }
        }
    };

    public MarshallActon3Support() {
        super(LOG);
        addSupportedService(MarshallActon3Constants.UUID_SERVICE_MARSHALL_CONTROL);
        addSupportedService(GattService.UUID_SERVICE_DEVICE_INFORMATION);

        deviceInfoProfile = new DeviceInfoProfile<>(this);
        deviceInfoProfile.addListener(listener);
        addSupportedProfile(deviceInfoProfile);
    }

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        builder.add(new SetDeviceStateAction(getDevice(), GBDevice.State.INITIALIZING, getContext()));

        // Request device information
        deviceInfoProfile.requestDeviceInfo(builder);

        // Enable notifications for control characteristics
        builder.notify(MarshallActon3Constants.UUID_CHARACTERISTIC_VOLUME, true);
        builder.notify(MarshallActon3Constants.UUID_CHARACTERISTIC_EQ, true);
        builder.notify(MarshallActon3Constants.UUID_CHARACTERISTIC_SOURCE, true);
        builder.notify(MarshallActon3Constants.UUID_CHARACTERISTIC_PLACEMENT, true);

        // Read current state
        builder.read(MarshallActon3Constants.UUID_CHARACTERISTIC_VOLUME);
        builder.read(MarshallActon3Constants.UUID_CHARACTERISTIC_EQ);
        builder.read(MarshallActon3Constants.UUID_CHARACTERISTIC_SOURCE);
        builder.read(MarshallActon3Constants.UUID_CHARACTERISTIC_PLACEMENT);

        builder.add(new SetDeviceStateAction(getDevice(), GBDevice.State.INITIALIZED, getContext()));
        return builder;
    }

    private void handleDeviceInfo(DeviceInfo info) {
        if (info == null) {
            return;
        }
        LOG.debug("Device info: {}", info);

        GBDeviceEventVersionInfo versionInfo = new GBDeviceEventVersionInfo();
        versionInfo.fwVersion = info.getFirmwareRevision() != null ? info.getFirmwareRevision() : "N/A";
        String hwVersion = info.getModelNumber() != null ? info.getModelNumber() : "N/A";
        if (info.getHardwareRevision() != null) {
            hwVersion += " (HW: " + info.getHardwareRevision() + ")";
        }
        versionInfo.hwVersion = hwVersion;
        handleGBDeviceEvent(versionInfo);
    }

    @Override
    public boolean onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
        super.onCharacteristicChanged(gatt, characteristic, value);

        UUID characteristicUUID = characteristic.getUuid();

        if (MarshallActon3Constants.UUID_CHARACTERISTIC_VOLUME.equals(characteristicUUID)) {
            return handleVolumeNotification(value);
        } else if (MarshallActon3Constants.UUID_CHARACTERISTIC_EQ.equals(characteristicUUID)) {
            return handleEqNotification(value);
        } else if (MarshallActon3Constants.UUID_CHARACTERISTIC_SOURCE.equals(characteristicUUID)) {
            return handleSourceNotification(value);
        } else if (MarshallActon3Constants.UUID_CHARACTERISTIC_PLACEMENT.equals(characteristicUUID)) {
            return handlePlacementNotification(value);
        }

        return false;
    }

    private boolean handleVolumeNotification(byte[] value) {
        if (value == null || value.length == 0) {
            return false;
        }
        int volume = value[0] & 0xFF;
        LOG.debug("Volume notification: {}", volume);

        GBDeviceEventUpdatePreferences event = new GBDeviceEventUpdatePreferences()
                .withPreference(PREF_MARSHALL_VOLUME, volume);
        handleGBDeviceEvent(event);
        return true;
    }

    private boolean handleEqNotification(byte[] value) {
        if (value == null || value.length < 5) {
            return false;
        }
        int bass = value[0] & 0xFF;
        int treble = value[4] & 0xFF;
        LOG.debug("EQ notification: bass={}, treble={}", bass, treble);

        GBDeviceEventUpdatePreferences event = new GBDeviceEventUpdatePreferences()
                .withPreference(PREF_MARSHALL_BASS, bass)
                .withPreference(PREF_MARSHALL_TREBLE, treble);
        handleGBDeviceEvent(event);
        return true;
    }

    private boolean handleSourceNotification(byte[] value) {
        if (value == null || value.length == 0) {
            return false;
        }
        byte source = value[0];
        LOG.debug("Source notification: {}", source);

        String sourcePref = source == MarshallActon3Constants.SOURCE_AUX ? "aux" : "bluetooth";
        GBDeviceEventUpdatePreferences event = new GBDeviceEventUpdatePreferences()
                .withPreference(PREF_MARSHALL_SOURCE, sourcePref);
        handleGBDeviceEvent(event);
        return true;
    }

    private boolean handlePlacementNotification(byte[] value) {
        if (value == null || value.length == 0) {
            return false;
        }
        byte placement = value[0];
        LOG.debug("Placement notification: {}", placement);

        String placementPref;
        if (placement == MarshallActon3Constants.PLACEMENT_WALL) {
            placementPref = "wall";
        } else if (placement == MarshallActon3Constants.PLACEMENT_EDGE) {
            placementPref = "edge";
        } else {
            placementPref = "free";
        }

        GBDeviceEventUpdatePreferences event = new GBDeviceEventUpdatePreferences()
                .withPreference(PREF_MARSHALL_PLACEMENT, placementPref);
        handleGBDeviceEvent(event);
        return true;
    }

    @Override
    public void onSendConfiguration(String config) {
        SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());

        try {
            TransactionBuilder builder = performInitialized("sendConfig");

            switch (config) {
                case PREF_MARSHALL_VOLUME:
                    int volume = prefs.getInt(PREF_MARSHALL_VOLUME, 15);
                    setVolume(builder, volume);
                    break;
                case PREF_MARSHALL_BASS:
                case PREF_MARSHALL_TREBLE:
                    int bass = prefs.getInt(PREF_MARSHALL_BASS, MarshallActon3Constants.EQ_DEFAULT);
                    int treble = prefs.getInt(PREF_MARSHALL_TREBLE, MarshallActon3Constants.EQ_DEFAULT);
                    setEq(builder, bass, treble);
                    break;
                case PREF_MARSHALL_SOURCE:
                    String source = prefs.getString(PREF_MARSHALL_SOURCE, "bluetooth");
                    setSource(builder, source);
                    break;
                case PREF_MARSHALL_PLACEMENT:
                    String placement = prefs.getString(PREF_MARSHALL_PLACEMENT, "free");
                    setPlacement(builder, placement);
                    break;
            }

            builder.queueImmediately();
        } catch (Exception e) {
            LOG.error("Failed to send configuration", e);
            handleGBDeviceEvent(new GBDeviceEventDisplayMessage(
                    getContext().getString(R.string.marshall_error_sending_config),
                    Toast.LENGTH_LONG,
                    GB.ERROR
            ));
        }
    }

    private void setVolume(TransactionBuilder builder, int volume) {
        byte safeVolume = (byte) Math.max(MarshallActon3Constants.VOLUME_MIN, Math.min(volume, MarshallActon3Constants.VOLUME_MAX));
        builder.write(MarshallActon3Constants.UUID_CHARACTERISTIC_VOLUME, new byte[]{safeVolume});
    }

    private void setEq(TransactionBuilder builder, int bass, int treble) {
        byte safeBass = (byte) Math.max(MarshallActon3Constants.EQ_MIN, Math.min(bass, MarshallActon3Constants.EQ_MAX));
        byte safeTreble = (byte) Math.max(MarshallActon3Constants.EQ_MIN, Math.min(treble, MarshallActon3Constants.EQ_MAX));
        // EQ data format: bass, padding (3 bytes), treble
        byte[] eqData = new byte[]{safeBass, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, safeTreble};
        builder.write(MarshallActon3Constants.UUID_CHARACTERISTIC_EQ, eqData);
    }

    private void setSource(TransactionBuilder builder, String source) {
        byte sourceValue = "aux".equals(source) ? MarshallActon3Constants.SOURCE_AUX : MarshallActon3Constants.SOURCE_BLUETOOTH;
        builder.write(MarshallActon3Constants.UUID_CHARACTERISTIC_SOURCE, new byte[]{sourceValue});
    }

    private void setPlacement(TransactionBuilder builder, String placement) {
        byte placementValue;
        switch (placement) {
            case "wall":
                placementValue = MarshallActon3Constants.PLACEMENT_WALL;
                break;
            case "edge":
                placementValue = MarshallActon3Constants.PLACEMENT_EDGE;
                break;
            default:
                placementValue = MarshallActon3Constants.PLACEMENT_FREE;
                break;
        }
        builder.write(MarshallActon3Constants.UUID_CHARACTERISTIC_PLACEMENT, new byte[]{placementValue});
    }

    @Override
    public boolean useAutoConnect() {
        return true;
    }
}
