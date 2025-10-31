package nodomain.freeyourgadget.gadgetbridge.service.devices.keephealth;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.keephealth.C60Constants;
import nodomain.freeyourgadget.gadgetbridge.devices.keephealth.KeephealthSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.KeephealthActivitySample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class C60DeviceSupport extends AbstractBTLESingleDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(C60DeviceSupport.class);
    private ByteBuffer cmdBuff = null;
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
    private final byte[] CMD_GET_ALARM = { 0x05, 0x00, 0x08, (byte) 0x80 }; // TODO find more about

    // Step count and sleep history data, 15 fragment response
    private final byte[] CMD_GET_CURRENT_HISTORY_STEP = { 0x20, 0x05, 0x00, 0x01 };


    public C60DeviceSupport() {
        super(LOG);
        addSupportedService(C60Constants.SERVICE);
//        addSupportedService(C60Constants.SERVICE_ACTIVE_UPLOAD);
//        addSupportedService(C60Constants.SERVICE_ECG);
//        addSupportedService(C60Constants.SERVICE_FFD2);
    }

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        builder.setDeviceState(GBDevice.State.INITIALIZING);
        builder.notify(C60Constants.CHARACTERISTIC_READ, true);
//        builder.notify(C60Constants.READ_ECG, true);
//        builder.notify(C60Constants.READ_FFD2, true);
//        builder.notify(C60Constants.SERVICE_ACTIVE_UPLOAD_READ, true);
        // builder.requestMtu(23);

        int wait = 100;
        getDeviceData(builder);
        builder.wait(wait);
        getBatteryData(builder);
        builder.wait(wait);
        setTime(builder);
        builder.wait(wait);
        getDeviceState(builder);
        builder.wait(wait);
        getSteps(builder);
        builder.wait(wait);
        getHeartrate(builder);
        builder.wait(wait);
        getBodytemp(builder);
        builder.wait(wait);
        setDeviceState(builder);
        builder.wait(wait);
        setUserInfo(builder);
        builder.wait(wait);
        getTargetData(builder);
        builder.wait(wait);
        getNotice(builder);
        builder.wait(wait);
        getOxygen(builder);
        builder.wait(wait);
        getHrSampling(builder);
        builder.wait(wait);
        getAlarm(builder);
        builder.wait(wait);
        getStepsHistory(builder);
        builder.wait(wait);

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

    public boolean onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] responseValue) {
        super.onCharacteristicChanged(gatt, characteristic, responseValue);

        UUID characteristicUUID = characteristic.getUuid();

        LOG.info("Characteristic changed UUID: {}", characteristicUUID);
        LOG.info("Characteristic changed value: {}", GB.hexdump(responseValue));

        ByteBuffer bb = ByteBuffer.wrap(responseValue).order(ByteOrder.LITTLE_ENDIAN);

        if (cmdBuff == null) {
            int expectedLength = bb.getShort(1) + 4; // 1 cmd, 2 length, 1 checksum
            LOG.info("Incoming data expected length: {}", expectedLength);
            cmdBuff = ByteBuffer.allocate(expectedLength);
        }

        cmdBuff.put(bb);

        LOG.info("cmdBuff.remaining() = {}", cmdBuff.remaining());

        if (cmdBuff.remaining() == 0) {
            byte[] value = cmdBuff.array();
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
            // when received all data delete buffer
            cmdBuff = null;
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
        if (data[3] == 0) {
            LOG.debug("Current steps data: " + GB.hexdump(data));
            ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int totalSteps    = bb.getInt(4);
            int totalCalories = bb.getInt(8);
            int totalDistance = bb.getInt(12);
            GB.toast("totalSteps: " +  totalSteps + " | totalCalories: " + totalCalories + " | totalDistance: " + totalDistance, Toast.LENGTH_LONG, GB.INFO);
        } else if (data[3] == 1) {
            if (data[4] == 5) {
                LOG.debug("No history steps data for this date");
            } else {
                LOG.debug("History steps data: " + GB.hexdump(data));
                ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

                // Header (9 bytes)
                byte header0 = buf.get();                    // [0]
                short length = buf.getShort();            // [1..2]
                byte subtype = buf.get();                    // [3]
                int year = buf.getShort() & 0xFFFF;          // [4..5]
                int month = buf.get() & 0xFF;                // [6]
                int day = buf.get() & 0xFF;                  // [7]
                int interval = buf.get() & 0xFF;             // [8] minutes per sample

                int samplesPerDay = 1440 / interval;

                KeephealthActivitySample[] activitySample = new KeephealthActivitySample[samplesPerDay];

                try (DBHandler db = GBApplication.acquireDB()) {
                    Long userId = DBHelper.getUser(db.getDaoSession()).getId();
                    Long deviceId = DBHelper.getDevice(getDevice(), db.getDaoSession()).getId();
                    KeephealthSampleProvider sampleProvider = new KeephealthSampleProvider(getDevice(), db.getDaoSession());

                    for (int sampleIndex = 0; sampleIndex < samplesPerDay && buf.remaining() >= 2; sampleIndex++) {
                        int raw = buf.getShort() & 0xFFFF;         // little-endian 16-bit
                        int flag = (raw >> 12) & 0xF;             // top 4 bits
                        int value = raw & 0x0FFF;                 // lower 12 bits

                        int hour = sampleIndex / (60 / interval);
                        int minute = (sampleIndex % (60 / interval)) * interval;
                        int timestamp = buildTimestamp(year, month, day, hour, minute); // implement to produce epoch seconds or ms as needed

                        activitySample[sampleIndex] = new KeephealthActivitySample(timestamp, deviceId);

                        if (flag == 0xF) {
                            int sleepStatus = (value >> 8) & 0xF;
                            // TODO find codes for sleep status
                            activitySample[sampleIndex].setRawKind(sleepStatus);
                            LOG.debug("sample {} time {}:{} sleepStatus {}", sampleIndex, hour, minute, sleepStatus);
                        } else {
                            int steps = value;
                            activitySample[sampleIndex].setSteps(steps);
                            LOG.debug("sample {} time {}:{} steps {}", sampleIndex, hour, minute, steps);
                        }
                    }

                    sampleProvider.addGBActivitySamples(activitySample);
                } catch (Exception e) {
                    LOG.error("Error acquiring database", e);
                }
            }
        } else {
            LOG.debug("Cmd arg: " + GB.hexdump(new byte[]{data[3]}));
            LOG.debug("other steps data: " + GB.hexdump(data));
        }
    }

    private int buildTimestamp(int year, int month, int day, int hour, int minute) {
        Calendar cal = Calendar.getInstance();
        cal.clear();
        cal.set(year, month - 1, day, hour, minute, 0);
        return (int) (cal.getTimeInMillis() / 1000);
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
        buf.put((byte) (calendar.get(Calendar.MONTH) + 1));
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
