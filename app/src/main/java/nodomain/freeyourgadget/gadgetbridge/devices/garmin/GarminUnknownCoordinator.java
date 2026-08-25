package nodomain.freeyourgadget.gadgetbridge.devices.garmin;

import android.util.SparseArray;

import androidx.annotation.NonNull;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.garmin.watches.GarminWatchCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate;
import nodomain.freeyourgadget.gadgetbridge.service.btle.BluetoothCompanyIdentifiers;

public class GarminUnknownCoordinator extends GarminWatchCoordinator {
    @Override
    public boolean isExperimental() {
        return true;
    }

    @Override
    public boolean supports(@NonNull final GBDeviceCandidate candidate) {
        final SparseArray<byte[]> manufacturerSpecificData = candidate.getManufacturerSpecificData();
        // Only check whether it's a Garmin device
        return manufacturerSpecificData.get(BluetoothCompanyIdentifiers.GARMIN_INTERNATIONAL_INC) != null;
    }

    @Override
    public int getOrderPriority() {
        return 2;
    }

    @Override
    public int getDefaultIconResource() {
        return R.drawable.ic_device_default;
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_garmin_unknown;
    }

    @Override
    public DeviceKind getDeviceKind(@NonNull final GBDevice device) {
        return DeviceKind.UNKNOWN;
    }
}
