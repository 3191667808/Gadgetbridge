package nodomain.freeyourgadget.gadgetbridge.devices.matrix;

import android.bluetooth.BluetoothDevice;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractDeviceContributor;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType;

public class MatrixPowerwatchDeviceContributor extends AbstractDeviceContributor {
    @Override
    public boolean supports(BluetoothDevice device) {
        String name = device.getName();
        // Tato funkce zajistí, že se hodinky v seznamu Bluetooth objeví jako podporované
        return name != null && name.toUpperCase().contains("MATRIX POWERWATCH X");
    }

    @Override
    public DeviceType getDeviceType() {
        // Toto musí přesně odpovídat názvu, který jsme přidali do DeviceType.java
        return DeviceType.MATRIX_POWERWATCH_X;
    }

    @Override
    public int getDeviceIcon() {
        // Zatím necháme 0, později tam můžeme přiřadit ikonku hodinek
        return 0;
    }
}