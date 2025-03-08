package nodomain.freeyourgadget.gadgetbridge.service.devices._1more_sonoflow;

import android.util.Log;

import nodomain.freeyourgadget.gadgetbridge.service.AbstractHeadphoneDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.serial.GBDeviceIoThread;
import nodomain.freeyourgadget.gadgetbridge.service.serial.GBDeviceProtocol;

public class _1MoreSonoFlowSupport extends AbstractHeadphoneDeviceSupport {
    @Override
    protected GBDeviceProtocol createDeviceProtocol() {
        return new _1MoreSonoFlowProtocol(getDevice());
    }

    @Override
    protected GBDeviceIoThread createDeviceIOThread() {
        return new _1MoreSonoFlowIOThread(
                getDevice(),
                getContext(),
                (_1MoreSonoFlowProtocol) getDeviceProtocol(),
                _1MoreSonoFlowSupport.this,
                getBluetoothAdapter()
        );
    }

    @Override
    public boolean useAutoConnect() {
        return false;
    }

    @Override
    public void onSendConfiguration(String config) {
        Log.d("pacjodebug", "got setting: " + config);

        // TODO: handle
//        if (config.equals(DeviceSettingsPreferenceConst.PREF_ACTIVE_NOISE_CANCELLING_TOGGLE)) {
//
//        }

        super.onSendConfiguration(config);
    }
}
