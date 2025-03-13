package nodomain.freeyourgadget.gadgetbridge.service.devices.onemore_sonoflow;

import static nodomain.freeyourgadget.gadgetbridge.util.ArrayUtils.startsWith;

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
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.serial.GBDeviceProtocol;

public class OneMoreSonoFlowProtocol extends GBDeviceProtocol  {
    protected OneMoreSonoFlowProtocol(GBDevice device) {
        super(device);
    }

    @Override
    public byte[] encodeSendConfiguration(String config) {
        SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());

        // TODO: second to last two bytes change between packets, but hardcoding seems to work for now
        // TODO: what is GBDeviceEventUpdatePreferences ?

        if (config.equals(DeviceSettingsPreferenceConst.PREF_NOISE_CONTROL_SELECTOR)) {
            byte packetValue;
            switch (prefs.getString(config, "0")) {
                case "0":
                    // Off
                    packetValue = 0x00;
                    break;
                case "1":
                    // ANC
                    packetValue = 0x01;
                    break;
                case "2":
                    // Pass-through
                    packetValue = 0x03;
                    break;
                default:
                    throw new IllegalStateException();      // TODO: can it be like this?
            }

            return new byte[] { 0x11, 0x01, 0x00, 0x5e, 0x00, 0x01, 0x00, 0x13, 0x5c, packetValue };
        } else if (config.equals(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_LDAC_MODE)) {
            byte packetValue = (byte) ((prefs.getBoolean(config, false)) ? 0x02 : 0x00);

            return new byte[] { 0x11, 0x01, 0x00, 0x6b, 0x00, 0x01, 0x00, 0x2d, 0x57, packetValue };
        } else if (config.equals(DeviceSettingsPreferenceConst.PREF_DUAL_DEVICE_SUPPORT)) {
            byte packetValue = (byte) ((prefs.getBoolean(config, false)) ? 0x01 : 0x00);

            return new byte[] { 0x11, 0x01, 0x00, 0x76, 0x00, 0x01, 0x00, 0x0d, 0x6a, packetValue };
        }

        return super.encodeSendConfiguration(config);
    }

    @Override
    public GBDeviceEvent[] decodeResponse(byte[] responseData) {
        List<GBDeviceEvent> events = new ArrayList<>();
        ByteBuffer buffer = ByteBuffer.wrap(responseData);
        buffer.order(ByteOrder.BIG_ENDIAN);

        if (!startsWith(buffer.array(), OneMorePacket.RESPONSE_PREAMBLE)) {
            // TODO: log
        } else {
            byte command = buffer.array()[3];

        if (buffer.array().length >= 9 && startsWith(buffer.array(), noiseControlHeader)) {
            decodeNoiseControlMode(buffer.array()[9]);
        } else if (buffer.array().length >= 9 && startsWith(buffer.array(), ldacHeader)) {
            decodeLdacMode(buffer.array()[9]);
        } else if (buffer.array().length >= 13 && startsWith(buffer.array(), batteryInfoHeader)) {
            events.add(decodeBatteryInfo(buffer.array()[13]));
        }

        return events.toArray(new GBDeviceEvent[0]);
    }

    /**
     * TODO: rewrite docs
     * Gets triggered when the button on the device is pressed or transparency toggled with the right palm.
     */
    private void decodeNoiseControlMode(byte value) {
        SharedPreferences prefs = getDevicePrefs().getPreferences();
        SharedPreferences.Editor editor = prefs.edit();

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

        editor.putString(DeviceSettingsPreferenceConst.PREF_NOISE_CONTROL_SELECTOR, mode);
        editor.apply();
    }

    private void decodeLdacMode(byte value) {
        SharedPreferences prefs = getDevicePrefs().getPreferences();
        SharedPreferences.Editor editor = prefs.edit();

        boolean enabled = value == 0x02;

        editor.putBoolean(DeviceSettingsPreferenceConst.PREF_SOUNDCORE_LDAC_MODE, enabled);
        editor.apply();
    }

    private void decodeDualDeviceMode(byte value) {
        SharedPreferences prefs = getDevicePrefs().getPreferences();
        SharedPreferences.Editor editor = prefs.edit();

        boolean enabled = value == 0x01;

        editor.putBoolean(DeviceSettingsPreferenceConst.PREF_DUAL_DEVICE_SUPPORT, enabled);
        editor.apply();
    }

    private GBDeviceEventBatteryInfo decodeBatteryInfo(byte value) {
        GBDeviceEventBatteryInfo info = new GBDeviceEventBatteryInfo();
        info.level = value;

        return info;
    }
}
