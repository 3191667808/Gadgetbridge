package nodomain.freeyourgadget.gadgetbridge.service.devices.keephealth;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Calendar;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.keephealth.C60Constants;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class C60DeviceSupport extends AbstractBTLESingleDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(C60DeviceSupport.class);
    private final GBDeviceEventBatteryInfo batteryCmd = new GBDeviceEventBatteryInfo();

    private final byte[] CMD_GET_DEVICE_DATA = { 0x01, 0x00, 0x00, (byte)0xb0 };
    private final byte CMD_GET_DEVICE_DATA_RESPONSE_PREFIX = (byte) 0x1b;
    private final byte[] CMD_GET_CURRENT_BATTERY = { 0x27, 0x00, 0x00, (byte)0x74 };
    private final byte CMD_GET_CURRENT_BATTERY_RESPONSE_PREFIX = (byte) 0xa7;

    public C60DeviceSupport() {
        super(LOG);
        addSupportedService(C60Constants.SERVICE);
    }

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        builder.setDeviceState(GBDevice.State.INITIALIZING);
        builder.notify(C60Constants.CHARACTERISTIC_READ, true);

        getDeviceData();
        getBatteryData();
        setDateTime();

        getDevice().setFirmwareVersion("N/A");
        getDevice().setFirmwareVersion2("N/A");

        builder.setDeviceState(GBDevice.State.INITIALIZED);

        return builder;
    }

    @Override
    public void onSetTime() {
        setDateTime();
    }

    @Override
    public boolean useAutoConnect() {
        return true;
    }

    public boolean onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
        super.onCharacteristicChanged(gatt, characteristic, value);

        UUID characteristicUUID = characteristic.getUuid();

        LOG.info("Characteristic changed UUID: {}", characteristicUUID);
        LOG.info("Characteristic changed value: {}", GB.hexdump(value));

        if (responseChecksumValid(value)) {
            if (value[0] == CMD_GET_CURRENT_BATTERY_RESPONSE_PREFIX) {
                handleBatteryInfo(value);
            }
        } else {
            LOG.info("Received data have invalid checksum: {}", GB.hexdump(value));
        }

        return false;
    }

    private void sendCommand(String taskName, byte[] contents) {
        TransactionBuilder builder = createTransactionBuilder(taskName);
        BluetoothGattCharacteristic characteristic = getCharacteristic(C60Constants.CHARACTERISTIC_WRITE);
        if (characteristic != null && contents != null) {
            builder.write(characteristic, contents);
            builder.queue();
        }
    }

    private void handleBatteryInfo(byte[] info) {
        LOG.debug("Battery info: " + GB.hexdump(info));
        batteryCmd.level = Math.min(info[3], 100);
        handleGBDeviceEvent(batteryCmd);
    }

    private byte[] encodeSetCurrentTime() {
        byte length = 12;
        ByteBuffer buf = ByteBuffer.allocate(length);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        buf.put((byte) 4);
        buf.put((byte) 8);
        buf.put((byte) 0);
        final Calendar calendar = Calendar.getInstance();
        int year = calendar.get(Calendar.YEAR);
        int high = (year >> 8) & 0xFF;
        int low = year & 0xFF;
        buf.put((byte) low);
        buf.put((byte) high);
        buf.put((byte) calendar.get(Calendar.MONTH));
        buf.put((byte) calendar.get(Calendar.DAY_OF_MONTH));
        buf.put((byte) calendar.get(Calendar.HOUR_OF_DAY));
        buf.put((byte) calendar.get(Calendar.MINUTE));
        buf.put((byte) calendar.get(Calendar.SECOND));
        buf.put((byte) 0);
        buf.put(getChecksum(buf.array()));
        return buf.array();
    }

    public void getDeviceData() {
        sendCommand("get Device Data", CMD_GET_DEVICE_DATA);
    }

    public void getBatteryData() {
        sendCommand("get Battery Data", CMD_GET_CURRENT_BATTERY);
    }

    public void setDateTime() {
        sendCommand("set Date Time", encodeSetCurrentTime());
    }

    private byte getChecksum(byte[] command) {
        byte sum = 0;
        for (byte b : command) {
            sum = (byte) (sum + b);
        }
        return (byte) ((sum * C60Constants.CHECKSUM_CODE) + 90);
    }

    private byte getResponseChecksum(byte[] data) {
        int len = data.length - 1;
        byte sum = 0;
        for (int i = 0; i < len; i++) {
            sum += data[i];
        }
        return (byte) (sum * C60Constants.CHECKSUM_CODE + 90);
    }

    private boolean responseChecksumValid(byte[] data) {
        return (getResponseChecksum(data) & (byte) 0xff) == (data[data.length - 1] & (byte) 0xff);
    }
}
