package nodomain.freeyourgadget.gadgetbridge.devices.matrix;

import android.bluetooth.BluetoothDevice;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractDeviceContributor;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType;

public class MatrixPowerwatchDeviceContributor extends AbstractDeviceContributor {
    @Override
    public boolean supports(BluetoothDevice device) {
        String name = device.getName();
        return name != null && name.toUpperCase().contains("MATRIX POWERWATCH X");
    }
    @Override
    public DeviceType getDeviceType() {
        return DeviceType.MATRIX_POWERWATCH_X;
    }
    @Override
    public int getDeviceIcon() { return 0; }
}