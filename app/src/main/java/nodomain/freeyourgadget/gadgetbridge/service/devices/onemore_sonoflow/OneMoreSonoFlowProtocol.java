package nodomain.freeyourgadget.gadgetbridge.service.devices.onemore_sonoflow;

import static nodomain.freeyourgadget.gadgetbridge.util.ArrayUtils.startsWith;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventUpdatePreferences;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.serial.GBDeviceProtocol;

public class OneMoreSonoFlowProtocol extends GBDeviceProtocol  {
    protected OneMoreSonoFlowProtocol(GBDevice device) {
        super(device);
    }

    @Override
    public byte[] encodeSendConfiguration(String config) {
        SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());

        switch (config) {
            case DeviceSettingsPreferenceConst.PREF_NOISE_CONTROL_SELECTOR:
                return OneMorePacket.createSetNoiseControlModePacket(prefs.getString(config, "0"));

            case DeviceSettingsPreferenceConst.PREF_SOUNDCORE_LDAC_MODE:
                return OneMorePacket.createSetLdacModePacket(prefs.getBoolean(config, false));

            case DeviceSettingsPreferenceConst.PREF_DUAL_DEVICE_SUPPORT:
                return OneMorePacket.createSetDualDeviceModePacket(prefs.getBoolean(config, false));
        }

        return super.encodeSendConfiguration(config);
    }

    @Override
    public GBDeviceEvent[] decodeResponse(byte[] responseData) {
        List<GBDeviceEvent> events = new ArrayList<>();
        ByteBuffer buffer = ByteBuffer.wrap(responseData);
        buffer.order(ByteOrder.BIG_ENDIAN);

        while (buffer.position() < buffer.limit()) {
            if (!startsWith(buffer.array(), OneMorePacket.RESPONSE_PREAMBLE)) {
                // skip a byte and try again
                buffer.position(buffer.position() + 1);
                continue;
            }

            // skip too short packets (shortest recorded packet has 10 bytes)
            if (buffer.remaining() < 10) {
                break;
            }

            byte command = buffer.get(buffer.position() + 3);
            if (buffer.remaining() >= 6 && command == OneMorePacket.GET_NOISE_CONTROL_COMMAND) {
                events.add(decodeNoiseControlMode(buffer.get(9)));
                buffer.position(buffer.position() + 10);
            } else if (buffer.remaining() >= 6 && command == OneMorePacket.GET_LDAC_COMMAND) {
                events.add(decodeLdacMode(buffer.get(9)));
                buffer.position(buffer.position() + 10);
            } else if (buffer.remaining() >= 6 && command == OneMorePacket.GET_DUAL_DEVICE_COMMAND) {
                events.add(decodeDualDeviceMode(buffer.get(9)));
                buffer.position(buffer.position() + 10);
            } else if (buffer.remaining() >= 10 && command == OneMorePacket.GET_DEVICE_INFO_COMMAND) {
                events.add(decodeBatteryInfo(buffer.get(13)));
                decodeFirmwareInformation(buffer.get(10), buffer.get(11), buffer.get(12));

                buffer.position(buffer.position() + 19);
            } else {
                // skip a byte and try again
                buffer.position(buffer.position() + 1);
            }
        }

        return events.toArray(new GBDeviceEvent[0]);
    }

    /**
     * TODO: rewrite docs
     * Gets triggered when the button on the device is pressed or transparency toggled with the right palm.
     */
    private GBDeviceEventUpdatePreferences decodeNoiseControlMode(byte value) {
        GBDeviceEventUpdatePreferences event = new GBDeviceEventUpdatePreferences();
        String mode = "0";

        switch (value) {
            case 0x00:
                mode = "0";
                break;
            case 0x01:
                mode = "1";
                break;
            case 0x03:
                mode = "2";
                break;
        }

        event.withPreference(DeviceSettingsPreferenceConst.PREF_NOISE_CONTROL_SELECTOR, mode);

        return event;
    }

    private GBDeviceEventUpdatePreferences decodeLdacMode(byte value) {
        GBDeviceEventUpdatePreferences event = new GBDeviceEventUpdatePreferences();
        boolean enabled = value == 0x02;

        event.withPreference(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_LDAC_MODE, enabled);

        return event;
    }

    private GBDeviceEventUpdatePreferences decodeDualDeviceMode(byte value) {
        GBDeviceEventUpdatePreferences event = new GBDeviceEventUpdatePreferences();
        boolean enabled = value == 0x01;

        event.withPreference(DeviceSettingsPreferenceConst.PREF_DUAL_DEVICE_SUPPORT, enabled);

        return event;
    }

    @SuppressLint("DefaultLocale")
    private void decodeFirmwareInformation(byte major, byte minor, byte patch) {
        String fw = String.format("%d.%d.%d", major, minor, patch);
    }

    private GBDeviceEventBatteryInfo decodeBatteryInfo(byte value) {
        GBDeviceEventBatteryInfo event = new GBDeviceEventBatteryInfo();
        event.level = value;

        return event;
    }
}
