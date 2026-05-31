package nodomain.freeyourgadget.gadgetbridge.service.devices.magene;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.io.IOException;

import java.util.Arrays;
import java.util.TimeZone;


import nodomain.freeyourgadget.gadgetbridge.devices.magene.FileType;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.MageneConstants;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.SRAPPacketParser;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.config.NotificationsConfig;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.config.RidingBikeConfig;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.config.RouteFile;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.config.UserInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets.BondSyncStateControlPacket;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets.CommonFileInfoPacket;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets.CommonFilePacket;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets.DeviceControlPacket;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets.DeviceStatusPacket;
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

import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.GpxRouteFileConverter;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class MageneSupport extends AbstractBTLESingleDeviceSupport {

    private static final Logger LOG = LoggerFactory.getLogger(MageneSupport.class);
    public BluetoothGattCharacteristic readCharacteristic;
    public BluetoothGattCharacteristic writeCharacteristic;
    private byte nodeAddress;
    private MageneFileManager mageneFileManager;
    boolean waitingsyncEnd = false;

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

    @SuppressLint("MissingPermission")
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
        }
        // Now waiting for user confirmation of bonding
        if (parsedObject instanceof  DeviceStatusPacket.Read) {
            //need more parsing here
            LOG.info("recieved DeviceStatusPacket.Read not parsed payload");
            BondSyncStateControlPacket.SyncStart.Write syncStart = new BondSyncStateControlPacket.SyncStart.Write(nodeAddress);
            builder.write(writeCharacteristic, syncStart.toByteArray());
        } else if (parsedObject instanceof BondSyncStateControlPacket.SyncStart.Response) {
            LOG.info("Sync started");
            BondSyncStateControlPacket.SetTimestamp.Write setTimeStamp = new BondSyncStateControlPacket.SetTimestamp.Write(nodeAddress, System.currentTimeMillis() / 1000);
            builder.write(writeCharacteristic, setTimeStamp.toByteArray());

        } else if (parsedObject instanceof BondSyncStateControlPacket.SetTimestamp.Response) {
            LOG.info("Timestamp set");
            int offset = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000;
            int offsetAbove12 = offset < 0 ? Math.abs(offset) + 54000 : offset;
            BondSyncStateControlPacket.SetTimezone.Write setTimezone = new BondSyncStateControlPacket.SetTimezone.Write(nodeAddress, offsetAbove12);
            builder.write(writeCharacteristic, setTimezone.toByteArray());
        } else if (parsedObject instanceof BondSyncStateControlPacket.SetTimezone.Response) {
            LOG.info("Timezone set");
            // TODO: upload configs here
//            sendWifiConfig();
//            sendUserAvatar();
//            sendIndoorTrainconfig();
//            sendRidingModeConfig();
//            sendBikeConfig();
            //sendUserConfig();
            LOG.info("Sending sync end");

            String bluetoothName;
            try {
                bluetoothName = BluetoothAdapter.getDefaultAdapter().getName();
            } catch (final Exception e) {
                LOG.error("Failed to get bluetooth name", e);
                bluetoothName = "Unknown";
            }
            DeviceControlPacket.SetBtAndDeviceName.Write setBtDeviceName = new DeviceControlPacket.SetBtAndDeviceName.Write(nodeAddress, bluetoothName, Build.MODEL);
            builder.write(writeCharacteristic, setBtDeviceName.toByteArray());

        } else if (parsedObject instanceof  DeviceControlPacket.SetBtAndDeviceName.Response) {
            LOG.info("BT name set");
            BondSyncStateControlPacket.SyncEnd.Write  syncEnd = new BondSyncStateControlPacket.SyncEnd.Write(nodeAddress);
            builder.write(writeCharacteristic, syncEnd.toByteArray());
            LOG.info("Sent SyncEnd");
        } else if (parsedObject instanceof BondSyncStateControlPacket.SyncEnd.Response) {
            LOG.info("Connected and synced");
        }


        // file upload handler
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

            mageneFileManager.endUpload(response);

            if (mageneFileManager.isQueueEmpty()) {


            }

        }

        builder.queue();
        return true;
    }

    private void sendWifiConfig() {
        byte[] hardcodedFile = GB.hexStringToByteArray("687474700000000000000000000000007266732d6669746e6573732e72667376722e636f6d00000000000000000000000000000000000000000000000000000000000000000000000000000000000000353063386635613363383266666635353165646461333066653238653464636100000000000000000000000000000000000000000000000000000000000000005dbd030300000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
                "01000000a55a55aa");
        mageneFileManager.startUpload(hardcodedFile, "wifi_conf.bin", FileType.WIFI_CONFIG);
    }

    private void sendUserAvatar() {
        byte[] hardcodedFile = GB.hexStringToByteArray("424d4208000000000000420000002800000020000000200000000100100003000000000800000000000000000000000000000300000000f80000e00700001f000000e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9" +
                "e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9c0f1c0f1c0f1c0f1c0f1c0f1c0f1c0f1c0f1c0f1c0f1c0f1e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9" +
                "c0f1a0e1a0e1a0e1a0e1a0e1a0e1a0e1a0e1a0e1a0e1a0e1a0e1c0e9c0f1e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9c0e9a0e1a0e180d960d160d160d160d160d160d160d180d9a0e1a0e1c0e9c0f1e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9c0e9a0e1a0e128d34ed44ed44ed44ed44ed44ed40dcc08cba1c980d9a0e1a0e1c0f1e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9c0f1c0f1c0f1c0f1c0f1c0f1c0e9a0e180e1" +
                "4de4ffffffffffffffffffffffffffffbeffd5e5a6ca80d9a0e1c0e9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9c0f1a0e1a0e1a0e1a0e1a0e1a0e1a0e1a0e1e6e298f6dafedafefafe7dffffffffffffffffffdaf6a6d280d9a0e1c0f1e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9c0f1a0e1a0e1a0d980c980c180c180c180c9a0d9e2d1e2c9e2c9e2c9a5d231edbeffffffffffffff16eee1d9a0e1c0e9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9c0e9a0e1e1d9b0dcb5ddb5ddb5ddb0dcc1d9cbdb32dd32dd" +
                "32ddcbd3c1d173edffffffffffffbeff07db80e1a0e1c0f1e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9c0f1a0e1a0e132edffffffffffff3cf764d28eecffffffffffff9dffe7da28e3beffffffffffffff8ee480d9a0e1c0f1e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9c0f1a0e180e189e3dfffffffffffffffebe3c6e29dffffffffffffff8fe422dadaf6ffffffffffffd5eda0d9a0e1c0e9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f1a0e1a0e184e23cf7ffffffffffff73ede1d998f6ffffffffffff78eee1d9" +
                "32edffffffffffff3bf764daa0e1a0e1e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9c0e9a0e1e1e116eeffffffffffffb9f602da32edffffffffffff7dff84dacbe3dfffffffffffffffaadb80e1a0e1c0f1e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9c0f1a0e1a0e1d0ecffffffffffff9dffc6dacbe3ffffffffffffffffaadb85da5cf7ffffffffffff11e580d9a0e1c0e9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9c0f1a0e180e169e3beffffffffffffff0ce485da7dffffffffffffff32e5e1d978f6ffffffff" +
                "ffff78f6e1d9a0e1c0e9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9c0e9a0e143e2daf6ffffffffffff94ede2d978f6ffffffffffff78f6e1d111edffffffffffff7dffa5daa0e1a0e1c0f1e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9c0e9a0e1a0e194edffffffffffff1bf743d2afecffffffffffff7dffc6daaae3dfffffffffffffffcbdb80d9a0e1c0f1e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9c0f1a0e180e12ce4ffffffffffffffff4edc02d211ed16f616f616f668e322e294ed36f636f636f62deca0e1" +
                "a0e1c0e9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9c0f1a0e1a0e184e2faf6ffffffffffff5cf72dd424c2e2c1e2c1e2c1c1c9a0d9c0e1c0e1c0e1c0e1c0e1a0e1a0e1c0e9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9c0e9a0e1a0e1ebe39dffffffffffffffffff3bf778ee78ee78eed5e522daa0e1a0e1a0e1a0e1a0e1a0e1a0e1a0e1c0f1e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9c0f1a0e1a0e1a0e1aaeb98f6dfffffffffffffffffffffffffffbeff48e380e1a0e1c0e9c0e9c0e9c0e9c0e9c0e9c0f1e0f9" +
                "e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9c0f1a0e1a0e1a0e122e2aaeb8eecf0f4f0f4f0f4f0f4f0f4f0f4e6e2a0e1a0e1c0f1e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9c0f1a0e1a0e1a0e180e180e1a0e1a0e1a0e1a0e1a0e1a0e1a0e1a0e1a0e1c0e9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9c0f1c0e9c0e9a0e1a0e1a0e1a0e1a0e1a0e1a0e1a0e1a0e1a0e1a0e1c0e9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9" +
                "e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9c0f1c0f1c0f1c0f1c0f1c0f1c0f1c0f1c0f1c0f1c0f1c0f1e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9" +
                "e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9e0f9");
        mageneFileManager.startUpload(hardcodedFile, "avatar_file.bin", FileType.USER_AVATAR);
    }

    private void sendIndoorTrainconfig() {
        byte[] hardcodedFile = GB.hexStringToByteArray("050000000000000000e79baee6a087e58a9fe78e8700000000000000000000000000000000000000000804011fa5ff0000000000000000000000000000000000000000000000000001169e03cb02e10071004000080031020000000000000000010d69035101580119012001000000000000000000000000010d65039400a9036202b003000000000000000000000000011d4000080047000f004e00160031027b012a023f0200000000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001e998bbe58a9be7ad89e7baa700000000000000000000000000000000000000000804011fa5ff000000000000000000000000000000000000000000000000000116a203e100b00071004000080031020000000000000000" +
                "010d69035101580119012001000000000000000000000000010d65039400a9036202b003000000000000000000000000011d4000080047000f004e00160031027b012a023f0200000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000002e59da1e5baa6e7ad89e7baa700000000000000000000000000000000000000000804011fa5ff0000000000000000000000000000000000000000000000000001169a03e100b00071004000080031020000000000000000010d69035101580119012001000000000000000000000000010d65039400a9036202b003000000000000000000000000011d4000080047000f004e00160031027b012a023f020000000000000000000000000000000000000000000000000000" +
                "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000003e8afbee7a88be8aeade7bb830000000000000000000000000000000000000000040401ea45ad00000000000000000000000000000000000000000000000000010da603e10071004000080000000000" +
                "0000000000000000010d69035101580119012001000000000000000000000000010d65039400a9036202b00300000000000000000000000001163e030f001600400047002a027b010000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
                "00000000000000000000000000000000000000000000000004e6a8a1e68b9fe8b7afe7babf0000000000000000000000000000000000000000070401d63b4b000000000000000000000000000000000000000000000000000104880308000a030000000000000000000000000000000001165203a9007100400031027b01d6010000000000000000010d69035101580119012001000000000000000000000000010d65039400a9036202b00300000000000000000000000000000000000000000000000000000000" +
                "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001000000a55a55aa" );
        mageneFileManager.startUpload(hardcodedFile, "riding_mode_indoor.bin", FileType.INDOOR_TRAIN);
    }

    private void sendRidingModeConfig() {
        byte[] hardcodedFile = GB.hexStringToByteArray("02000000000000000b436f6d6d75746500000000000000000000000000000000000000000000000000020501bbac250000000000000000000000000000000000000000000000000001076103410231027b010000000000000000000000000000011320032c020a036202d601dd01000000000000000000000116ac0090019701b301ba0140007100000000000000000001048803080080030000000000000000000000000000000001088102420246024d0200000000000000000000000000000000000000000000" +
                "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000a526f61640000000000000000000000000000000000000000000000000000000001050180bb2500000000000000000000000000000000000000000000000000010d610331027b013f02d601000000000000000000000000" +
                "0116570390019701d601dd012a0262020000000000000000010488030800800300000000000000000000000000000000011cab0008000f004000470071007800b301ba01000000000101700300000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000001000000a55a55aa" );
        mageneFileManager.startUpload(hardcodedFile, "riding_mode.bin", FileType.RIDING_MODE_CONFIG);

    }

    private void sendBikeConfig() {
        RidingBikeConfig.Entry bike1 = new RidingBikeConfig.Entry((byte)10, "Welt 29", (short)15200, (short)2342);
        RidingBikeConfig config = new RidingBikeConfig(Arrays.asList(bike1));
        try {
            mageneFileManager.startUpload(config.serializeToByteArray(), config.getFilename(), config.getFileType());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    @Override
    public void onNotification(NotificationSpec notificationSpec) {
        TransactionBuilder builder = createTransactionBuilder("notification");

        NotificationPacket.Write writeNotificationPacket = new NotificationPacket.Write(nodeAddress,
                NotificationPacket.NotificationType.MESSAGE,
                NotificationPacket.NotificationOrigin.OTHER,
                notificationSpec);

        builder.write(writeCharacteristic, writeNotificationPacket.toByteArray());
        builder.queue();
    }


    @Override
    public void onSendConfiguration(String config) {

        switch (config) {
            case ActivityUser.PREF_USER_NAME:
            case ActivityUser.PREF_USER_WEIGHT_KG:
            case ActivityUser.PREF_USER_GENDER:
            case ActivityUser.PREF_USER_HEIGHT_CM:
            case ActivityUser.PREF_USER_DATE_OF_BIRTH:
                sendUserConfig();
                break;

        }
    }

    private void sendUserConfig() {
        ActivityUser user = new ActivityUser();
        UserInfo userInfo = new UserInfo(user.getName(),
                "C606",
                185,
                185,
                239,
                user.getDateOfBirth().getYear(),
                user.getDateOfBirth().getMonthValue(),
                user.getDateOfBirth().getDayOfMonth(),
                user.getGender(),
                user.getHeightCm(),
                user.getWeightKg()*10
        ); //FIXME: input or calculate mhr, lthr and ftp


        //NotificationsConfig notifyConf = new NotificationsConfig(true,
        //true,true,true,true,true,true,true,true,true);
        try {
            mageneFileManager.startUpload(userInfo.serializeToByteArray(), userInfo.getFilename(), userInfo.getFileType());


//            mageneFileManager.startUpload(notifyConf.serializeToByteArray(), notifyConf.getFilename(), notifyConf.getFileType());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onInstallApp(Uri uri, @NonNull final Bundle options) {
        final MageneGpxRouteInstallHandler gpxRouteHandler = new MageneGpxRouteInstallHandler(uri, getContext());
        if (gpxRouteHandler.isValid()) {
            final String trackName = options.getString(MageneGpxRouteInstallHandler.EXTRA_TRACK_NAME);

            final MageneGpxRouteFileConverter mageneGpxRouteFileConverter = new MageneGpxRouteFileConverter(
                    gpxRouteHandler.getGpxFile(),
                    trackName
            );


            try {
                byte[] payload = mageneGpxRouteFileConverter.convertToPayload();
                RouteFile routeFile = new RouteFile(
                        "route_111222.bin", System.currentTimeMillis()/1000, trackName,
                        750, 3200, 669, -37,
                        0,(short)mageneGpxRouteFileConverter.getNumberOfSegments(),
                        mageneGpxRouteFileConverter.getRouteLatitude(), mageneGpxRouteFileConverter.getRouteLongitude(),
                -300,
                        payload,
                        trackName
                ); // FIXME: hardcoded values

                LOG.info("Route file size: " + routeFile.serializeToByteArray().length + "dump: " + GB.hexdump(routeFile.serializeToByteArray()));

                mageneFileManager.startUpload(routeFile.serializeToByteArray(), routeFile.getFilename(), routeFile.getFileType());
            } catch (final Exception e) {
                GB.toast(getContext(), "Gpx install error: " + e.getLocalizedMessage(), Toast.LENGTH_LONG, GB.ERROR, e);
            }

            return;
        }
    }

}
