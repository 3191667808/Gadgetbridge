package nodomain.freeyourgadget.gadgetbridge.devices.redmibuds;

import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator;

public class RedmiBuds6ActiveCoordinator extends AbstractRedmiBudsCoordinator {

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_redmi_buds_6_active;
    }

    @Override
    protected Pattern getSupportedDeviceName() {
        return Pattern.compile("Redmi Buds 6 Active");
    }

    @Override
    public DeviceCoordinator.DeviceKind getDeviceKind() {
        return DeviceCoordinator.DeviceKind.HEAD_MOUNTED;
    }
}
