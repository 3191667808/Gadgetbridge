package nodomain.freeyourgadget.gadgetbridge.devices.coros;

import androidx.annotation.NonNull;

import nodomain.freeyourgadget.gadgetbridge.GBException;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

public abstract class AbstractCorosDeviceCoordinator extends AbstractBLEDeviceCoordinator {

    @Override
    public String getManufacturer() {
        return "Coros";
    }

    @Override
    public boolean supportsActivityTracking(@NonNull GBDevice device) {
        return false;
    }

    @Override
    public boolean supportsWeather(@NonNull GBDevice device) {
        return false;
    }

    @Override
    public int getBondingStyle() {
        return BONDING_STYLE_NONE;
    }

    @Override
    public int getBatteryCount(GBDevice device) {
        return 1;
    }

    @Override
    public int getDefaultIconResource() {
        return R.drawable.ic_device_default;
    }

    @Override
    protected void deleteDevice(@NonNull GBDevice gbDevice,
                                @NonNull Device device,
                                @NonNull DaoSession session) throws GBException {
        super.deleteDevice(gbDevice, device, session);
    }
}
