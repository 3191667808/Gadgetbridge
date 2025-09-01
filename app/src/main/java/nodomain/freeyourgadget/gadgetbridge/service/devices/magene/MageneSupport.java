package nodomain.freeyourgadget.gadgetbridge.service.devices.magene;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import nodomain.freeyourgadget.gadgetbridge.devices.magene.MageneConstants;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.FunctionCode;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.PageNumber;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.ResourceType;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.SRAPPacket;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.SRAPPacketParser;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets.NodeAddressInfoPacket;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets.NodeBasicInfoReadPacket;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets.NodeSerialInfoPacket;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets.NotificationPacket;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.CallSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class MageneSupport extends AbstractBTLESingleDeviceSupport {

    private static final Logger LOG = LoggerFactory.getLogger(MageneSupport.class);
    public BluetoothGattCharacteristic readCharacteristic;
    public BluetoothGattCharacteristic writeCharacteristic;
    private byte nodeAddress;

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

        NodeBasicInfoReadPacket.Read basicInfoRead = new NodeBasicInfoReadPacket.Read((byte)0x80, ResourceType.MAIN);

        builder.write(writeCharacteristic, basicInfoRead.toByteArray());


        getDevice().setFirmwareVersion("N/A");
        getDevice().setFirmwareVersion2("N/A");

        // mark the device as initialized
        builder.setDeviceState(GBDevice.State.INITIALIZED);
        return builder;
    }

    @Override
    public boolean onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] data) {

        TransactionBuilder builder = createTransactionBuilder("test second packet");

        SRAPPacketParser parser = new SRAPPacketParser();
        Object parsedObject = parser.parse(data);
        if (parsedObject instanceof NodeBasicInfoReadPacket.Response) {
            NodeBasicInfoReadPacket.Response info = (NodeBasicInfoReadPacket.Response) parsedObject;
            LOG.debug(info.toString());
            nodeAddress = info.getNodeAddress();
            getDevice().setFirmwareVersion(String.valueOf(info.getNodeFwVersion()));

            NodeSerialInfoPacket.Read serialInfoPacket = new NodeSerialInfoPacket.Read(nodeAddress, ResourceType.MAIN);
            builder.write(writeCharacteristic, serialInfoPacket.toByteArray());
        } else if (parsedObject instanceof NodeSerialInfoPacket.Response) {
            NodeSerialInfoPacket.Response serialInfo = (NodeSerialInfoPacket.Response) parsedObject;
            LOG.debug(serialInfo.toString());
            NodeAddressInfoPacket.Read addressInfoPacket = new NodeAddressInfoPacket.Read(nodeAddress, ResourceType.MAIN);
            builder.write(writeCharacteristic, addressInfoPacket.toByteArray());
        } else if (parsedObject instanceof NodeAddressInfoPacket.Response) {
            NodeAddressInfoPacket.Response addressInfo = (NodeAddressInfoPacket.Response) parsedObject;
            LOG.debug(addressInfo.toString());
            byte[] writeData;
            writeData = GB.hexStringToByteArray("a9f210e108");
            builder.write(writeCharacteristic, writeData);
        }

        builder.queue();
        return true;
    }


    @Override
    public void onNotification(NotificationSpec notificationSpec) {
        TransactionBuilder builder = createTransactionBuilder("notification");

        NotificationPacket.Write writeNotificationPacket = new NotificationPacket.Write(nodeAddress, ResourceType.MAIN, (byte) 2, (byte) 6, notificationSpec.sender, notificationSpec.body);

        builder.write(writeCharacteristic, writeNotificationPacket.toByteArray());
        builder.queue();
    }

}
