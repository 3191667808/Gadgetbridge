package nodomain.freeyourgadget.gadgetbridge.service.devices.protocentral;

import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.actions.SetDeviceStateAction;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.IntentListener;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.battery.BatteryInfoProfile;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.deviceinfo.DeviceInfoProfile;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Intent;

public class HealthyPiMoveSupport extends AbstractBTLESingleDeviceSupport {

    private static final Logger LOG = LoggerFactory.getLogger(HealthyPiMoveSupport.class);

    private final GBDeviceEventBatteryInfo batteryCmd = new GBDeviceEventBatteryInfo();

    private final BatteryInfoProfile<HealthyPiMoveSupport> batteryInfoProfile;
    private final DeviceInfoProfile<HealthyPiMoveSupport> deviceInfoProfile;

    public HealthyPiMoveSupport() {
        super(LOG);
        addSupportedService(GattService.UUID_SERVICE_DEVICE_INFORMATION);
        addSupportedService(GattService.UUID_SERVICE_BATTERY_SERVICE);
        //addSupportedService(GattService.UUID_SERVICE_PULSE_OXIMETER); // HPI_SPO2_SERVICE
        // missing: HPI_SPO2_CHAR - 0x2a5e
        //addSupportedService(GattService.UUID_SERVICE_HEALTH_THERMOMETER); // HPI_TEMP_SERVICE
        // missing: HPI_TEMP_CHAR - 0x2a6e

        IntentListener mListener = new IntentListener() {
            @Override
            public void notify(Intent intent) {
                String action = intent.getAction();
                if (DeviceInfoProfile.ACTION_DEVICE_INFO.equals(action)) {
                    handleDeviceInfo((nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.deviceinfo.DeviceInfo) intent.getParcelableExtra(DeviceInfoProfile.EXTRA_DEVICE_INFO));
                } else if (BatteryInfoProfile.ACTION_BATTERY_INFO.equals(action)) {
                    handleBatteryInfo((nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.battery.BatteryInfo) intent.getParcelableExtra(BatteryInfoProfile.EXTRA_BATTERY_INFO));
                }
            }
        };

        deviceInfoProfile = new DeviceInfoProfile<>(this);
        deviceInfoProfile.addListener(mListener);
        addSupportedProfile(deviceInfoProfile);

        batteryInfoProfile = new BatteryInfoProfile<>(this);
        batteryInfoProfile.addListener(mListener);
        addSupportedProfile(batteryInfoProfile);
    }

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        builder.setDeviceState(GBDevice.State.INITIALIZING);
        getDevice().setFirmwareVersion("N/A");
        getDevice().setFirmwareVersion2("N/A");

        deviceInfoProfile.requestDeviceInfo(builder);

        batteryInfoProfile.requestBatteryInfo(builder);
        batteryInfoProfile.enableNotify(builder, true);

        //builder.notify(getCharacteristic(GattService.UUID_SERVICE_PULSE_OXIMETER), true);
        //builder.notify(getCharacteristic(GattService.UUID_SERVICE_HEALTH_THERMOMETER), true);

        builder.setDeviceState(GBDevice.State.INITIALIZED);
        return builder;
    }

    private void handleDeviceInfo(nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.deviceinfo.DeviceInfo info) {
        LOG.debug("Device info: " + info);
        GBDeviceEventVersionInfo versionCmd = new GBDeviceEventVersionInfo();
        versionCmd.fwVersion = info.getFirmwareRevision();
        handleGBDeviceEvent(versionCmd);
    }

    private void handleBatteryInfo(nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.battery.BatteryInfo info) {
        LOG.debug("Battery info: " + info);
        batteryCmd.level = (short) info.getPercentCharged();
        handleGBDeviceEvent(batteryCmd);
    }

    @Override
    public boolean onCharacteristicChanged(BluetoothGatt gatt,
                                           BluetoothGattCharacteristic characteristic,
                                           byte[] value) {
        if (super.onCharacteristicChanged(gatt, characteristic, value)) {
            return true;
        }

        UUID characteristicUUID = characteristic.getUuid();

        LOG.info("Characteristic changed: " + characteristicUUID);
        return false;
    }

    @Override
    public boolean useAutoConnect() {
        return true;
    }
}
