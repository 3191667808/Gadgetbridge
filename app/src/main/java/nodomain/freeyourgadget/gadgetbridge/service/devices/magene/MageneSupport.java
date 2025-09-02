package nodomain.freeyourgadget.gadgetbridge.service.devices.magene;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.devices.magene.FileType;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.MageneConstants;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.FunctionCode;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.PageNumber;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.ResourceType;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.SRAPPacket;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.SRAPPacketParser;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets.BondSyncStateControlPacket;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets.CommonFileInfoPacket;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets.CommonFilePacket;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets.FileTransformControlPacket;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets.NodeAddressInfoPacket;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets.NodeBasicInfoReadPacket;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets.NodeSerialInfoPacket;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets.NotificationPacket;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser;
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
    private MageneFileManager mageneFileManager;

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
        builder.notify(MageneConstants.UUID_CHARACTERISTIC_TX, true);
        builder.notify(GattService.UUID_SERVICE_BATTERY_SERVICE, true);
        builder.setCallback(this);

        NodeBasicInfoReadPacket.Read basicInfoRead = new NodeBasicInfoReadPacket.Read((byte)0x80);

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
            mageneFileManager = new MageneFileManager(nodeAddress, this);
            getDevice().setFirmwareVersion(String.valueOf(info.getNodeFwVersion()));

            NodeSerialInfoPacket.Read serialInfoPacket = new NodeSerialInfoPacket.Read(nodeAddress);
            builder.write(writeCharacteristic, serialInfoPacket.toByteArray());
        } else if (parsedObject instanceof NodeSerialInfoPacket.Response) {
            NodeSerialInfoPacket.Response serialInfo = (NodeSerialInfoPacket.Response) parsedObject;
            LOG.debug(serialInfo.toString());
            NodeAddressInfoPacket.Read addressInfoPacket = new NodeAddressInfoPacket.Read(nodeAddress);
            builder.write(writeCharacteristic, addressInfoPacket.toByteArray());
        } else if (parsedObject instanceof NodeAddressInfoPacket.Response) {
            NodeAddressInfoPacket.Response addressInfo = (NodeAddressInfoPacket.Response) parsedObject;
            LOG.debug(addressInfo.toString());
            BondSyncStateControlPacket.RequestBond.Write requestBond = new BondSyncStateControlPacket.RequestBond.Write(nodeAddress);
            builder.write(writeCharacteristic, requestBond.toByteArray());
        } else if (parsedObject instanceof BondSyncStateControlPacket.RequestBond.Response) {
            LOG.info("Bonded");
            BondSyncStateControlPacket.SyncStart.Write syncStart = new BondSyncStateControlPacket.SyncStart.Write(nodeAddress);
            builder.write(writeCharacteristic, syncStart.toByteArray());
        } else if (parsedObject instanceof BondSyncStateControlPacket.SyncStart.Response) {
            LOG.info("Sync started");
            // TODO: upload configs here
            BondSyncStateControlPacket.SetTimestamp.Write setTimeStamp = new BondSyncStateControlPacket.SetTimestamp.Write(nodeAddress, System.currentTimeMillis()/1000);
            builder.write(writeCharacteristic, setTimeStamp.toByteArray());
        } else if (parsedObject instanceof BondSyncStateControlPacket.SetTimestamp.Response) {
            LOG.info("Timestamp set");
            int offset = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000;
            int offsetAbove12 = offset < 0 ? Math.abs(offset) + 54000 : offset;
            BondSyncStateControlPacket.SetTimezone.Write setTimezone = new BondSyncStateControlPacket.SetTimezone.Write(nodeAddress, offsetAbove12);
            builder.write(writeCharacteristic, setTimezone.toByteArray());
        } else if (parsedObject instanceof BondSyncStateControlPacket.SetTimezone.Response) {
            LOG.info("Timezone set");
            LOG.info("Sending sync end");
            BondSyncStateControlPacket.SyncEnd.Write  syncEnd = new BondSyncStateControlPacket.SyncEnd.Write(nodeAddress);
            builder.write(writeCharacteristic, syncEnd.toByteArray());
            builder.write(writeCharacteristic, syncEnd.toByteArray());
        } else if (parsedObject instanceof BondSyncStateControlPacket.SyncEnd.Response) {
            LOG.info("Connected and synced");
        }

        if (parsedObject instanceof FileTransformControlPacket.Response) {
            FileTransformControlPacket.Response response = (FileTransformControlPacket.Response) parsedObject;
            if ( response.getControlType() == FileTransformControlPacket.FileTransformControlCode.START_TRANSFER) {
                mageneFileManager.handleMtuResponse(response);
            } else {
                LOG.info("Get file upload end code");
            }
        } else if (parsedObject instanceof CommonFileInfoPacket.Response) {
            CommonFileInfoPacket.Response response = (CommonFileInfoPacket.Response) parsedObject;
            mageneFileManager.onFileInfoSent(response);
        } else if (parsedObject instanceof CommonFilePacket.Notification) {
            LOG.info("upload completed");
            CommonFilePacket.Notification response = (CommonFilePacket.Notification) parsedObject;
            try {
                mageneFileManager.endUpload(response);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

        }

        builder.queue();
        return true;
    }


    @Override
    public void onNotification(NotificationSpec notificationSpec) {
        TransactionBuilder builder = createTransactionBuilder("notification");

        NotificationPacket.Write writeNotificationPacket = new NotificationPacket.Write(nodeAddress,
                NotificationPacket.NotificationType.MESSAGE,
                NotificationPacket.NotificationOrigin.WHATSAPP,
                notificationSpec);

        builder.write(writeCharacteristic, writeNotificationPacket.toByteArray());
        builder.queue();
    }


    @Override
    public void onSendConfiguration(String config) {

        switch (config) {
            case ActivityUser.PREF_USER_WEIGHT_KG:
            case ActivityUser.PREF_USER_GENDER:
            case ActivityUser.PREF_USER_HEIGHT_CM:
            case ActivityUser.PREF_USER_DATE_OF_BIRTH:
                sendUserConfig();
                break;

        }
    }

    private void sendUserConfig() {
        byte[] hardcodedUserInfo = GB.hexStringToByteArray("4a6f6e6820446f650000000000000000000000000000000000000000000000004336303600000000000000000000000000000000000000000000000000000000b900b900ef00c607060101c0b603000000000000000000000000000000000000000000000000000001000000a55a55aa");
        try {
            mageneFileManager.startUpload(hardcodedUserInfo, "user_info.bin", FileType.USER_PROFILE);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
