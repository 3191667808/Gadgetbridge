package nodomain.freeyourgadget.gadgetbridge.service.devices.onemore_sonoflow;

import android.content.SharedPreferences;

import java.util.Arrays;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.serial.GBDeviceProtocol;

public class OneMoreSonoFlowProtocol extends GBDeviceProtocol  {
    protected OneMoreSonoFlowProtocol(GBDevice device) {
        super(device);
    }

    @Override
    public byte[] encodeSendConfiguration(String config) {
        SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());

        if (config.equals(DeviceSettingsPreferenceConst.PREF_NOISE_CONTROL_SELECTOR)){
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

            // TODO: second to last two bytes change between packets, but hardcoding seems to work for now
            return new byte[] { 0x11, 0x01, 0x00, 0x5e, 0x00, 0x01, 0x00, 0x13, 0x5c, packetValue };
        }

        return super.encodeSendConfiguration(config);
    }

    @Override
    public GBDeviceEvent[] decodeResponse(byte[] responseData) {
        return super.decodeResponse(responseData);
    }
}
