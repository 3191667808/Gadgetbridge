package nodomain.freeyourgadget.gadgetbridge.service.devices.keephealth;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Calendar;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.keephealth.C60Constants;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class C60DeviceSupport extends AbstractBTLESingleDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(C60DeviceSupport.class);
    private final GBDeviceEventBatteryInfo batteryCmd = new GBDeviceEventBatteryInfo();

    private final byte[] CMD_GET_DEVICE_DATA = { 0x01, 0x00, 0x00, (byte) 0xb0 };
    private final byte[] CMD_GET_CURRENT_BATTERY = { 0x27, 0x00, 0x00, 0x74 };
    private final byte[] CMD_GET_DEVICE_STATE = { 0x02, 0x00, 0x00, 0x06 };
    private final byte[] CMD_GET_CURRENT_STEPS = { 0x20, 0x01, 0x00, 0x00, 0x70 };
    private final byte[] CMD_GET_CURRENT_HEARTRATE = { 0x21, 0x01, 0x00, 0x00, (byte) 0xc6 };
    private final byte[] CMD_GET_CURRENT_BODYTEMP = { 0x2c, 0x01, 0x00, 0x00, (byte) 0x78 };
    private final byte[] CMD_SET_DEVICE_STATE = { // TODO build based on settings
            (byte) 0x02, (byte) 0x10, (byte) 0x00, (byte) 0x64, (byte) 0x05,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01,
            (byte) 0x00, (byte) 0x00, (byte) 0xFF, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x02, (byte) 0x00, (byte) 0x58
    };
    private final byte[] CMD_SET_USER_INFO = { // TODO build based on settings
            (byte) 0x03, (byte) 0x07, (byte) 0x00, (byte) 0x00, (byte) 0x14,
            (byte) 0xAA, (byte) 0x00, (byte) 0x58, (byte) 0x02, (byte) 0x46,
            (byte) 0x4A
    };
    private final byte[] CMD_GET_TARGET_DATA = { 0x07, 0x00, 0x00, (byte) 0xb4 };
    private final byte[] CMD_GET_NOTICE = { 0x09, 0x00, 0x00, (byte) 0x60 }; // TODO find more about

    // Obtain blood pressure and blood oxygen data
    private final byte[] CMD_GET_OXYGEN = { 0x21, 0x01, 0x00, 0x07, (byte) 0x20 }; // TODO find more about

    // Obtaining automatic heart rate sampling data
    private final byte[] CMD_GET_HEARTRATE_SAMPLING = { 0x21, 0x01, 0x00, 0x08, (byte) 0x76 }; // TODO find more about

    // two fragment response
    private final byte[] CMD_GET_ALARM = { 0x02, 0x00, 0x08, (byte) 0x80 }; // TODO find more about

    // Step count and sleep history data, 15 fragment response
    private final byte[] CMD_GET_CURRENT_HISTORY_STEP = { 0x20, 0x05, 0x00, 0x01 };


    public C60DeviceSupport() {
        super(LOG);
        addSupportedService(C60Constants.SERVICE);
    }

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        builder.setDeviceState(GBDevice.State.INITIALIZING);
        builder.notify(C60Constants.CHARACTERISTIC_READ, true);

        getDeviceData(builder);
        builder.wait(200);
        getBatteryData(builder);
        builder.wait(200);
        setTime(builder);
        builder.wait(200);
        getDeviceState(builder);
        builder.wait(200);
        getSteps(builder);
        builder.wait(200);
        getHeartrate(builder);
        builder.wait(200);
        getBodytemp(builder);
        builder.wait(200);
        setDeviceState(builder);
        builder.wait(200);
        setUserInfo(builder);
        builder.wait(200);
        getTargetData(builder);
        builder.wait(200);
        getNotice(builder);
        builder.wait(200);
        getOxygen(builder);
        builder.wait(200);
        getHrSampling(builder);
        builder.wait(200);
        getAlarm(builder);
        builder.wait(200);
        getStepsHistory(builder);
        builder.wait(200);

        getDevice().setFirmwareVersion("N/A");
        getDevice().setFirmwareVersion2("N/A");

        builder.setDeviceState(GBDevice.State.INITIALIZED);

        return builder;
    }

    @Override
    public void onSetTime() {
        LOG.debug("set date and time");
        TransactionBuilder builder = createTransactionBuilder("Set date and time");
        setTime(builder);
        builder.queue();
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
            // get cmd based on response first byte - 0x80
            byte cmdPrefix = (byte) (value[0] - (byte) 0x80);
            LOG.info("Expected CMD prefix: {}", GB.hexdump(new byte[]{cmdPrefix}));
            if (cmdPrefix == CMD_GET_DEVICE_DATA[0]) {
                handleDeviceData(value);
            } else if (cmdPrefix == CMD_GET_CURRENT_BATTERY[0]) {
                handleBatteryInfo(value);
            } else if (cmdPrefix == CMD_GET_DEVICE_STATE[0]) {
                handleDeviceState(value);
            } else if (cmdPrefix == CMD_GET_CURRENT_STEPS[0]) {
                handleSteps(value);
            } else {
                LOG.info("Unhandled data: {}", GB.hexdump(value));
            }
        } else {
            LOG.info("Received data have invalid checksum: {}", GB.hexdump(value));
        }

        return false;
    }

    private void handleBatteryInfo(byte[] info) {
        LOG.debug("Battery info: " + GB.hexdump(info));
        var level = info[3];
        if (level == (byte) 0xff) {
            batteryCmd.state = BatteryState.BATTERY_CHARGING;
        } else {
            batteryCmd.state = BatteryState.BATTERY_NORMAL;
            batteryCmd.level = Math.min(info[3], 100);
        }
        handleGBDeviceEvent(batteryCmd);
    }

    private void handleDeviceData(byte[] info) {
        LOG.debug("Device Data: " + GB.hexdump(info));
        String model = new String(info, 3, 8);
        int major = Byte.toUnsignedInt(info[11]);
        int minor = Byte.toUnsignedInt(info[12]);
        String version = major + "." + (minor < 10 ? "0" + minor : Integer.toString(minor));
        getDevice().setModel(model + " v" + version);
    }

    private void handleDeviceState(byte[] info) {
        LOG.debug("Device State: " + GB.hexdump(info));
        // TODO
    }

    private void handleSteps(byte[] data) {
        LOG.debug("Current steps data: " + GB.hexdump(data));
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int totalSteps    = bb.getInt(4);
        int totalCalories = bb.getInt(8);
        int totalDistance = bb.getInt(12);
        GB.toast("totalSteps: " +  totalSteps + " | totalCalories: " + totalCalories + " | totalDistance: " + totalDistance, Toast.LENGTH_LONG, GB.INFO);
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
        buf.put((byte) calendar.get(Calendar.MONTH + 1));
        buf.put((byte) calendar.get(Calendar.DAY_OF_MONTH));
        buf.put((byte) calendar.get(Calendar.HOUR_OF_DAY));
        buf.put((byte) calendar.get(Calendar.MINUTE));
        buf.put((byte) calendar.get(Calendar.SECOND));
        buf.put((byte) 0);
        buf.put(getChecksum(buf.array()));
        return buf.array();
    }

    public C60DeviceSupport getDeviceData(TransactionBuilder builder) {
        builder.write(C60Constants.CHARACTERISTIC_WRITE, CMD_GET_DEVICE_DATA);
        return this;
    }

    public C60DeviceSupport getBatteryData(TransactionBuilder builder) {
        builder.write(C60Constants.CHARACTERISTIC_WRITE, CMD_GET_CURRENT_BATTERY);
        return this;
    }

    public C60DeviceSupport setTime(TransactionBuilder builder) {
        builder.write(C60Constants.CHARACTERISTIC_WRITE, encodeSetCurrentTime());
        return this;
    }

    public C60DeviceSupport getDeviceState(TransactionBuilder builder) {
        builder.write(C60Constants.CHARACTERISTIC_WRITE, CMD_GET_DEVICE_STATE);
        return this;
    }

    public C60DeviceSupport getSteps(TransactionBuilder builder) {
        builder.write(C60Constants.CHARACTERISTIC_WRITE, CMD_GET_CURRENT_STEPS);
        return this;
    }

    public C60DeviceSupport getStepsHistory(TransactionBuilder builder) {
        byte length = 9;
        ByteBuffer buf = ByteBuffer.allocate(length);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        buf.put(CMD_GET_CURRENT_HISTORY_STEP);
        final Calendar calendar = Calendar.getInstance();
        int year = calendar.get(Calendar.YEAR);
        int high = (year >> 8) & 0xFF;
        int low = year & 0xFF;
        buf.put((byte) low);
        buf.put((byte) high);
        buf.put((byte) (calendar.get(Calendar.MONTH) + 1));
        buf.put((byte) calendar.get(Calendar.DAY_OF_MONTH));
        buf.put(getChecksum(buf.array()));
        builder.write(C60Constants.CHARACTERISTIC_WRITE, buf.array());
        return this;
    }

    public C60DeviceSupport getHeartrate(TransactionBuilder builder) {
        builder.write(C60Constants.CHARACTERISTIC_WRITE, CMD_GET_CURRENT_HEARTRATE);
        return this;
    }

    public C60DeviceSupport getBodytemp(TransactionBuilder builder) {
        builder.write(C60Constants.CHARACTERISTIC_WRITE, CMD_GET_CURRENT_BODYTEMP);
        return this;
    }

    public C60DeviceSupport setDeviceState(TransactionBuilder builder) {
        builder.write(C60Constants.CHARACTERISTIC_WRITE, CMD_SET_DEVICE_STATE);
        return this;
    }

    public C60DeviceSupport setUserInfo(TransactionBuilder builder) {
        builder.write(C60Constants.CHARACTERISTIC_WRITE, CMD_SET_USER_INFO);
        return this;
    }

    public C60DeviceSupport getTargetData(TransactionBuilder builder) {
        builder.write(C60Constants.CHARACTERISTIC_WRITE, CMD_GET_TARGET_DATA);
        return this;
    }

    public C60DeviceSupport getNotice(TransactionBuilder builder) {
        builder.write(C60Constants.CHARACTERISTIC_WRITE, CMD_GET_NOTICE);
        return this;
    }

    public C60DeviceSupport getOxygen(TransactionBuilder builder) {
        builder.write(C60Constants.CHARACTERISTIC_WRITE, CMD_GET_OXYGEN);
        return this;
    }

    public C60DeviceSupport getHrSampling(TransactionBuilder builder) {
        builder.write(C60Constants.CHARACTERISTIC_WRITE, CMD_GET_HEARTRATE_SAMPLING);
        return this;
    }

    public C60DeviceSupport getAlarm(TransactionBuilder builder) {
        builder.write(C60Constants.CHARACTERISTIC_WRITE, CMD_GET_ALARM);
        return this;
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
