package nodomain.freeyourgadget.gadgetbridge.service.devices._1more_sonoflow;

import android.content.SharedPreferences;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.serial.GBDeviceProtocol;

public class _1MoreSonoFlowProtocol extends GBDeviceProtocol  {
    protected _1MoreSonoFlowProtocol(GBDevice device) {
        super(device);
    }

    @Override
    public byte[] encodeSendConfiguration(String config) {
        SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());

        if (config.equals(DeviceSettingsPreferenceConst.PREF_ACTIVE_NOISE_CANCELLING_TOGGLE)){
            boolean enabled = prefs.getBoolean(config, false);

            byte value;
            if (enabled)
                value = 0x01;
            else
                value = 0x00;

            // TODO: second to last two bytes change between packets, but hardcoding seems to work for now
            return new byte[] { 0x11, 0x01, 0x00, 0x5e, 0x00, 0x01, 0x00, 0x13, 0x5c, value };
        }

        return super.encodeSendConfiguration(config);
    }

    @Override
    public GBDeviceEvent[] decodeResponse(byte[] responseData) {
        return super.decodeResponse(responseData);
    }
}
