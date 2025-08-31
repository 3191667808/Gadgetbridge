package nodomain.freeyourgadget.gadgetbridge.service.devices.magene;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import nodomain.freeyourgadget.gadgetbridge.devices.magene.MageneConstants;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class MageneSupport extends AbstractBTLESingleDeviceSupport {

    private static final Logger LOG = LoggerFactory.getLogger(MageneSupport.class);
    public BluetoothGattCharacteristic readCharacteristic;
    public BluetoothGattCharacteristic writeCharacteristic;

    public MageneSupport() {

        super(LOG);
        addSupportedService(MageneConstants.UUID_CHARACTERISTIC_RX);
        addSupportedService(MageneConstants.UUID_CHARACTERISTIC_UART);
    }

    @Override
    public boolean useAutoConnect() {
        return false;
    }

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        // mark the device as initializing
        builder.setDeviceState(GBDevice.State.INITIALIZING);
        readCharacteristic = getCharacteristic(MageneConstants.UUID_CHARACTERISTIC_RX);
        writeCharacteristic = getCharacteristic(MageneConstants.UUID_CHARACTERISTIC_TX);

        builder.notify(MageneConstants.UUID_CHARACTERISTIC_RX, true);
        builder.notify(GattService.UUID_SERVICE_BATTERY_SERVICE, true);
        builder.setCallback(this);

        byte[] data = GB.hexStringToByteArray("80f10101000000000000000000"); // HACK: send firrst packet to check UART configured correctly

        builder.write(writeCharacteristic, data);


        getDevice().setFirmwareVersion("N/A");
        getDevice().setFirmwareVersion2("N/A");

        // mark the device as initialized
        builder.setDeviceState(GBDevice.State.INITIALIZED);
        return builder;
    }

    @Override
    public boolean onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] data) {
        TransactionBuilder builder = createTransactionBuilder("test second packet");
        byte[] writeData;
        if (data[3] == 1 ) {

            writeData = GB.hexStringToByteArray("a9f101020000000000000000000000000000000000000000000000"); // HACK hardcoded ask for serial number
            builder.write(writeCharacteristic, writeData);

        } else if  (data[3] == 2) {
            writeData = GB.hexStringToByteArray("a9f1010302");
            builder.write(writeCharacteristic, writeData);
        } else if  (data[3] == 3) {
            writeData = GB.hexStringToByteArray("a9f210e108");
            builder.write(writeCharacteristic, writeData);
        }
        builder.queue();
        return true;
    }


    @Override
    public void onNotification(NotificationSpec notificationSpec) {
        TransactionBuilder builder = createTransactionBuilder("notification");
        byte[] dataHeader = GB.hexStringToByteArray("A9F201440206");
        byte[] senderData = notificationSpec.sender.getBytes(StandardCharsets.UTF_8);
        byte[] message = notificationSpec.body.getBytes(StandardCharsets.UTF_8);
        byte[] fullData = new byte[senderData.length + message.length + dataHeader.length + 2];
        ByteBuffer buffer = ByteBuffer.wrap(fullData);
        buffer.put(dataHeader);
        buffer.put((byte) senderData.length);;
        buffer.put(senderData);
        buffer.put((byte)(message.length));
        buffer.put(message);
        fullData = buffer.array();
        builder.write(writeCharacteristic, fullData);
        builder.queue();
    }

}
