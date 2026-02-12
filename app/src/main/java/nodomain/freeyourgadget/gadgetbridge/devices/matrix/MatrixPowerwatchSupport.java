package nodomain.freeyourgadget.gadgetbridge.devices.matrix;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import java.util.UUID;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLEDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;

public class MatrixPowerwatchSupport extends AbstractBTLEDeviceSupport {
    public static final UUID UUID_SERVICE_MATRIX = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e1011");
    public static final UUID UUID_CHARACTERISTIC_WRITE = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e0011");
    public static final UUID UUID_CHARACTERISTIC_NOTIFY = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e0012");

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        builder.notify(getCharacteristic(UUID_CHARACTERISTIC_NOTIFY), true);
        byte[] initPacket = new byte[]{(byte) 0x3A, 0x00, 0x40, 0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        builder.write(getCharacteristic(UUID_CHARACTERISTIC_WRITE), initPacket);
        return builder;
    }

    @Override
    public boolean useAutoConnect() { return true; }

    @Override
    public boolean onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        return false;
    }
}
