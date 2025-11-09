package nodomain.freeyourgadget.gadgetbridge.service.devices.keephealth;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.text.format.DateFormat;
import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.SettingsActivity;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.keephealth.C60Constants;
import nodomain.freeyourgadget.gadgetbridge.devices.keephealth.KeephealthBloodPressureSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.keephealth.KeephealthHeartRateSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.keephealth.KeephealthSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.keephealth.KeephealthSpo2SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.KeephealthActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.KeephealthBloodPressureSample;
import nodomain.freeyourgadget.gadgetbridge.entities.KeephealthHeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.KeephealthSpo2Sample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;
import nodomain.freeyourgadget.gadgetbridge.util.StringUtils;
import nodomain.freeyourgadget.gadgetbridge.util.preferences.DevicePrefs;

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
//    private final byte[] CMD_SET_DEVICE_STATE = { // TODO build based on settings
//            (byte) 0x02, (byte) 0x10, (byte) 0x00, (byte) 0x64, (byte) 0x05,
//            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01,
//            (byte) 0x00, (byte) 0x00, (byte) 0xFF, (byte) 0x00, (byte) 0x00,
//            (byte) 0x00, (byte) 0x00, (byte) 0x02, (byte) 0x00, (byte) 0x58
//    };
    private final byte[] CMD_SET_DEVICE_STATE = { // TODO build based on settings
            (byte) 0x02, (byte) 0x10, (byte) 0x00
    };

    private final byte[] CMD_GET_DO_NOT_DISTURB = {
            (byte) 0x08, (byte) 0x00, (byte) 0x00, (byte) 0x0a
    };
    private final byte[] CMD_SET_DO_NOT_DISTURB = { // TODO build based on settings
            (byte) 0x08, (byte) 0x10, (byte) 0x00
    };

    private final byte[] CMD_SET_USER_INFO = { // TODO build based on settings
            (byte) 0x03, (byte) 0x07, (byte) 0x00, (byte) 0x00, (byte) 0x14,
            (byte) 0xAA, (byte) 0x00, (byte) 0x58, (byte) 0x02, (byte) 0x46,
            (byte) 0x4A
    };
    private final byte[] CMD_GET_TARGET_DATA = { 0x07, 0x00, 0x00, (byte) 0xb4 }; // TODO implement
    private final byte[] CMD_GET_NOTICE = { 0x09, 0x00, 0x00, (byte) 0x60 }; // TODO find more about

    // Obtain blood pressure and blood oxygen data
    private final byte[] CMD_GET_OXYGEN = { 0x21, 0x01, 0x00, 0x07, (byte) 0x20 }; // TODO find more about

    // Obtaining automatic heart rate sampling data
    private final byte[] CMD_GET_HEARTRATE_SAMPLING = { 0x21, 0x01, 0x00, 0x08, (byte) 0x76 }; // TODO find more about

    // two fragment response
    private final byte[] CMD_GET_ALARM = { 0x05, 0x00, 0x08, (byte) 0x80 }; // TODO find more about

    // Step count and sleep history data, 15 fragment response
    private final byte[] CMD_GET_CURRENT_HISTORY_STEP = { 0x20, 0x05, 0x00, 0x01 };
    private final byte[] CMD_GET_CURRENT_HISTORY_HEARTRATE = { 0x21, 0x05, 0x00, 0x01 };

    private byte[] currentDeviceSettings = null;
    private byte[] currentDndSettings = null;

    private int daysAgo;
    private Calendar syncingDay;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> responseTimeoutFuture;
    private final long RESPONSE_TIMEOUT_MS = 10_000; // choose e.g. 10s or whatever you need

    private synchronized void startResponseTimeout() {
        cancelResponseTimeout();
        responseTimeoutFuture = scheduler.schedule(() -> {
            LOG.warn("Response timeout fired, finishing fetch");
            fetchRecordedDataFinished();
        }, RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void cancelResponseTimeout() {
        if (responseTimeoutFuture != null) {
            responseTimeoutFuture.cancel(true);
            responseTimeoutFuture = null;
        }
    }

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
        getDevice().setFirmwareVersion("N/A");
        getDevice().setFirmwareVersion2("N/A");

        int wait = 100;
        getDeviceData(builder);
        builder.wait(wait);
        getBatteryData(builder);
        builder.wait(wait);
        setTime(builder);
        builder.wait(wait);
        getDeviceState(builder);
        builder.wait(wait);
        getDndState(builder);
        builder.wait(wait);
//        getSteps(builder);
//        builder.wait(wait);
//        getHeartrate(builder);
//        builder.wait(wait);
//        getBodytemp(builder);
//        builder.wait(wait);
//        setDeviceState(builder);
//        builder.wait(wait);
//        setUserInfo(builder);
//        builder.wait(wait);
//        getTargetData(builder);
//        builder.wait(wait);
//        getNotice(builder);
//        builder.wait(wait);
//        getOxygen(builder);
//        builder.wait(wait);
//        getHrSampling(builder);
//        builder.wait(wait);
//        getAlarm(builder);
//        builder.wait(wait);
//        getStepsHistory(builder);
//        builder.wait(500);
//        getHeartrateHistory(builder);
//        builder.wait(500);

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

    @Override
    public void onFetchRecordedData(int dataTypes) {
        GB.updateTransferNotification(getContext().getString(R.string.busy_task_fetch_activity_data), "", true, 0, getContext());
        daysAgo = 0;
        fetchHistoryActivity();
    }

    private void fetchRecordedDataFinished() {
        cancelResponseTimeout();
        GB.updateTransferNotification(null, "", false, 100, getContext());
        LOG.info("Sync finished!");
        getDevice().unsetBusyTask();
        getDevice().sendDeviceUpdateIntent(getContext());
        GB.signalActivityDataFinish(getDevice());
    }

    private void fetchHistoryActivity() {
        getDevice().setBusyTask(R.string.busy_task_fetch_activity_data, getContext());
        getDevice().sendDeviceUpdateIntent(getContext());
        syncingDay = Calendar.getInstance();
        syncingDay.add(Calendar.DAY_OF_MONTH, 0 - daysAgo);
        syncingDay.set(Calendar.HOUR_OF_DAY, 0);
        syncingDay.set(Calendar.MINUTE, 0);
        syncingDay.set(Calendar.SECOND, 0);
        syncingDay.set(Calendar.MILLISECOND, 0);
        byte[] activityHistoryRequest = getStepsHistoryCommand(syncingDay);
        LOG.info("Fetch historical activity data request sent: {}", StringUtils.bytesToHex(activityHistoryRequest));
        sendWrite("activityHistoryRequest", activityHistoryRequest);
        startResponseTimeout();
    }

    private void fetchHistoryHR() {
        getDevice().setBusyTask(R.string.busy_task_fetch_hr_data, getContext());
        getDevice().sendDeviceUpdateIntent(getContext());
        syncingDay = Calendar.getInstance();
        syncingDay.add(Calendar.DAY_OF_MONTH, 0 - daysAgo);
        syncingDay.set(Calendar.HOUR_OF_DAY, 0);
        syncingDay.set(Calendar.MINUTE, 0);
        syncingDay.set(Calendar.SECOND, 0);
        syncingDay.set(Calendar.MILLISECOND, 0);
        byte[] hrHistoryRequest = getHeartrateHistoryCommand(syncingDay);
        LOG.info("Fetch historical HR data request sent ({}): {}", DateTimeUtils.formatIso8601(syncingDay.getTime()), StringUtils.bytesToHex(hrHistoryRequest));
        sendWrite("hrHistoryRequest", hrHistoryRequest);
        startResponseTimeout();
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
                    getDevice().unsetBusyTask();
                    getDevice().sendDeviceUpdateIntent(getContext());
                    if (!getDevice().isBusy()) {
                        if (daysAgo < 7) {
                            daysAgo++;
                            fetchHistoryActivity();
                        } else {
                            daysAgo = 0;
                            fetchHistoryHR();
                        }
                    }
                } else if (cmdPrefix == CMD_GET_CURRENT_HEARTRATE[0]) {
                    handleHeartrate(value);
                    getDevice().unsetBusyTask();
                    getDevice().sendDeviceUpdateIntent(getContext());
                    if (!getDevice().isBusy()) {
                        if (daysAgo < 7) {
                            daysAgo++;
                            fetchHistoryHR();
                        } else {
                            daysAgo = 0;
                            fetchRecordedDataFinished();
                        }
                    }
                } else if (cmdPrefix == CMD_GET_DO_NOT_DISTURB[0]) {
                    handleDoNotDisturb(value);
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

    @Override
    public void onSendConfiguration(String config) {
        final Prefs prefs = getDevicePrefs();
        byte[] configPacket = null;
        switch (config) {
            case DeviceSettingsPreferenceConst.PREF_TIMEFORMAT:
            case DeviceSettingsPreferenceConst.PREF_LANGUAGE:
            case DeviceSettingsPreferenceConst.PREF_LIFTWRIST_NOSHED:
                configPacket = setDeviceStateCommand(prefs, config);
                break;
            case DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_NOAUTO:
            case DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_NOAUTO_START:
            case DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_NOAUTO_END:
                configPacket = setDoNotDisturbCommand(prefs, config);
                break;
            default:
                try {
                    LOG.debug("Unknown pref: {}, value: {}", config, prefs.getString(config, "default"));
                } catch (Exception e){}
                try {
                    LOG.debug("Unknown pref: {}, value: {}", config, prefs.getBoolean(config, false));
                } catch (Exception e){}
                try {
                    LOG.debug("Unknown pref: {}, value: {}", config, prefs.getFloat(config, 0.0f));
                } catch (Exception e){}
                try {
                    LOG.debug("Unknown pref: {}, value: {}", config, prefs.getInt(config, 0));
                } catch (Exception e){}
        }

        if (configPacket == null) { return; }
        LOG.debug("send config: {} - {}", config, StringUtils.bytesToHex(configPacket));
        sendWrite("onSendConfigurationRequest", configPacket);
    }

    @Override
    public void onFindDevice(boolean start) {
        if (!start) return;
        ByteBuffer buf = ByteBuffer.allocate(12);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        buf.put((byte) 0x10);
        buf.put((byte) 8);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 1);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 0xc0);
//            buf.put(getChecksum(buf.array()));
        // 10 08 00 00 00 00 00 01 00 00 00 c0
        sendCommand("Find Me", buf.array());
    }

    private void sendWrite(String taskName, byte[] contents) {
        TransactionBuilder builder = createTransactionBuilder(taskName);
        BluetoothGattCharacteristic characteristic = getCharacteristic(C60Constants.CHARACTERISTIC_WRITE);
        if (characteristic != null) {
            builder.write(characteristic, contents);
            builder.queue();
        }
    }

    private void sendCommand(String taskName, byte[] contents) {
        TransactionBuilder builder = createTransactionBuilder(taskName);
        BluetoothGattCharacteristic characteristic = getCharacteristic(C60Constants.CHARACTERISTIC_WRITE);
        if (characteristic != null) {
            builder.write(characteristic, contents);
            builder.queue();
        }
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
        getDevice().setFirmwareVersion(version);
    }

    private void handleDeviceState(byte[] info) {
        LOG.debug("Device State: " + GB.hexdump(info));
        if (info.length == 20) {
            this.currentDeviceSettings = trimData(info);
            LOG.debug("Saved Device State: " + GB.hexdump(this.currentDeviceSettings));
        }
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
                        int timestamp = (int) (buildTimestamp(year, month, day, hour, minute) / 1000); // implement to produce epoch seconds or ms as needed

                        activitySample[sampleIndex] = new KeephealthActivitySample(timestamp, deviceId);

                        if (flag == 0xF) {
                            int sleepStatus = (value >> 8) & 0xF;
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

    private void handleHeartrate(byte[] data) {
        if (data[3] == 0) {
            LOG.debug("Current heartrate data: " + GB.hexdump(data));
//            ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
//            int totalSteps    = bb.getInt(4);
//            int totalCalories = bb.getInt(8);
//            int totalDistance = bb.getInt(12);
//            GB.toast("totalSteps: " +  totalSteps + " | totalCalories: " + totalCalories + " | totalDistance: " + totalDistance, Toast.LENGTH_LONG, GB.INFO);
        } else if (data[3] == 1) {
            if (data[4] == 5) {
                LOG.debug("No history heartrate data for this date");
            } else {
                LOG.debug("History heart/bp data: " + GB.hexdump(data));
                ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

                // Header (9 bytes)
                byte header0 = buf.get();                  // [0]
                short length = buf.getShort();             // [1..2]
                byte subtype = buf.get();                  // [3]
                int year = buf.getShort() & 0xFFFF;        // [4..5]
                int month = buf.get() & 0xFF;              // [6]
                int day = buf.get() & 0xFF;                // [7]
                int interval = buf.get() & 0xFF;           // [8] minutes per sample

                int samplesPerDay = 1440 / interval;

                // allocate arrays
                List<KeephealthHeartRateSample> samples = new ArrayList<>();
                List<KeephealthSpo2Sample> spo2Samples = new ArrayList<>();
                List<KeephealthBloodPressureSample> bpSamples = new ArrayList<>();

                try (DBHandler db = GBApplication.acquireDB()) {
                    Long userId = DBHelper.getUser(db.getDaoSession()).getId();
                    Long deviceId = DBHelper.getDevice(getDevice(), db.getDaoSession()).getId();
                    KeephealthHeartRateSampleProvider sampleProvider = new KeephealthHeartRateSampleProvider(getDevice(), db.getDaoSession());
                    KeephealthSpo2SampleProvider spo2SampleProvider = new KeephealthSpo2SampleProvider(getDevice(), db.getDaoSession());
                    KeephealthBloodPressureSampleProvider bpSampleProvider = new KeephealthBloodPressureSampleProvider(getDevice(), db.getDaoSession());

                    Calendar cal = Calendar.getInstance();
                    cal.clear();
                    cal.set(year, month - 1, day, 0, 0, 0);

                    for (int sampleIndex = 0; sampleIndex < samplesPerDay && buf.remaining() >= 4; sampleIndex++) {
                        int hr = buf.get() & 0xFF;           // heart rate
                        int fz = buf.get() & 0xFF;           // blood pressure fz (diastolic/systolic order in original)
                        int ss = buf.get() & 0xFF;           // blood pressure ss
                        int oxy = buf.get() & 0xFF;          // oxygen

                        int hour = sampleIndex / (60 / interval);
                        int minute = (sampleIndex % (60 / interval)) * interval;
                        cal.set(Calendar.HOUR_OF_DAY, hour);
                        cal.set(Calendar.MINUTE, minute);
                        cal.set(Calendar.SECOND, 0);
                        long timestamp = buildTimestamp(year, month, day, hour, minute);

                        // create sample
                        KeephealthHeartRateSample sample = new KeephealthHeartRateSample(timestamp, deviceId);
                        sample.setHeartRate(hr);
                        samples.add(sample);

                        KeephealthSpo2Sample spo2Sample = new KeephealthSpo2Sample(timestamp, deviceId);
                        spo2Sample.setSpo2(oxy);
                        spo2Samples.add(spo2Sample);

                        KeephealthBloodPressureSample bpSample = new KeephealthBloodPressureSample(timestamp, deviceId);
                        bpSample.setBpDiastolic(Math.min(fz, ss));   // original code orders values to ss/fz but store both
                        bpSample.setBpSystolic(Math.max(fz, ss));
                        bpSamples.add(bpSample);

                        LOG.debug("sample {} time {}:{} timestamp: {} hr {}", sampleIndex, hour, minute, timestamp, hr);
                        LOG.debug("sample {} time {}:{} bp {}/{} oxy {}", sampleIndex, hour, minute, Math.min(fz, ss), Math.max(fz, ss), oxy);
                    }

                    sampleProvider.addSamples(samples);
                    spo2SampleProvider.addSamples(spo2Samples);
                    bpSampleProvider.addSamples(bpSamples);
                } catch (Exception e) {
                    LOG.error("Error acquiring database", e);
                }
            }
        } else {
            LOG.debug("Cmd arg: " + GB.hexdump(new byte[]{data[3]}));
            LOG.debug("other heartrate data: " + GB.hexdump(data));
        }
    }

    private void handleDoNotDisturb(byte[] data) {
        byte[] dndPrefix = {(byte)0x88, 0x10, 0x00};

        if (data.length >= dndPrefix.length && Arrays.equals(Arrays.copyOfRange(data, 0, dndPrefix.length), dndPrefix)) {
            LOG.debug("Current DND settings: " + GB.hexdump(data));
            if (data.length == 20) {
                this.currentDndSettings = trimData(data);
                LOG.debug("Saved DND settings: " + GB.hexdump(this.currentDndSettings));
            }
        } else {
            LOG.debug("other sleep/dnd data: " + GB.hexdump(data));
        }
    }

    private long buildTimestamp(int year, int month, int day, int hour, int minute) {
        Calendar cal = Calendar.getInstance();
        cal.clear();
        cal.set(year, month - 1, day, hour, minute, 0);
        return cal.getTimeInMillis();
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

    public C60DeviceSupport getDndState(TransactionBuilder builder) {
        builder.write(C60Constants.CHARACTERISTIC_WRITE, CMD_GET_DO_NOT_DISTURB);
        return this;
    }

    public C60DeviceSupport getSteps(TransactionBuilder builder) {
        builder.write(C60Constants.CHARACTERISTIC_WRITE, CMD_GET_CURRENT_STEPS);
        return this;
    }

    public C60DeviceSupport getStepsHistory(TransactionBuilder builder) {
        // TODO implement automatic history loading till "a0 02 00 01 05 ca" response
        // probably means no data for that date
        // for now just load last 4 days manually
        Calendar calendar = Calendar.getInstance();
        getStepsHistoryByCalendar(builder, calendar);
        builder.wait(500);
        calendar.add(Calendar.DAY_OF_MONTH, -1);
        getStepsHistoryByCalendar(builder, calendar);
        builder.wait(500);
        calendar.add(Calendar.DAY_OF_MONTH, -1);
        getStepsHistoryByCalendar(builder, calendar);
        builder.wait(500);
        calendar.add(Calendar.DAY_OF_MONTH, -1);
        getStepsHistoryByCalendar(builder, calendar);
        builder.wait(500);
        calendar.add(Calendar.DAY_OF_MONTH, -1);
        getStepsHistoryByCalendar(builder, calendar);
        builder.wait(500);
        return this;
    }
    public C60DeviceSupport getStepsHistoryByCalendar(TransactionBuilder builder, Calendar calendar) {
        builder.write(C60Constants.CHARACTERISTIC_WRITE, getStepsHistoryCommand(calendar));
        return this;
    }

    public byte[] getStepsHistoryCommand(Calendar calendar) {
        byte length = 9;
        ByteBuffer buf = ByteBuffer.allocate(length);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put(CMD_GET_CURRENT_HISTORY_STEP);
        int year = calendar.get(Calendar.YEAR);
        int high = (year >> 8) & 0xFF;
        int low = year & 0xFF;
        buf.put((byte) low);
        buf.put((byte) high);
        buf.put((byte) (calendar.get(Calendar.MONTH) + 1));
        buf.put((byte) calendar.get(Calendar.DAY_OF_MONTH));
        buf.put(getChecksum(buf.array()));
        return buf.array();
    }


    public C60DeviceSupport getHeartrateHistory(TransactionBuilder builder) {
        Calendar calendar = Calendar.getInstance();
        getHeartrateHistoryByCalendar(builder, calendar);
        builder.wait(500);
        calendar.add(Calendar.DAY_OF_MONTH, -1);
        getHeartrateHistoryByCalendar(builder, calendar);
        builder.wait(500);
        calendar.add(Calendar.DAY_OF_MONTH, -1);
        getHeartrateHistoryByCalendar(builder, calendar);
        builder.wait(500);
        calendar.add(Calendar.DAY_OF_MONTH, -1);
        getHeartrateHistoryByCalendar(builder, calendar);
        builder.wait(500);
        calendar.add(Calendar.DAY_OF_MONTH, -1);
        getHeartrateHistoryByCalendar(builder, calendar);
        builder.wait(500);
        return this;
    }

    public C60DeviceSupport getHeartrateHistoryByCalendar(TransactionBuilder builder, Calendar calendar) {
        builder.write(C60Constants.CHARACTERISTIC_WRITE, getHeartrateHistoryCommand(calendar));
        return this;
    }

    public byte[] getHeartrateHistoryCommand(Calendar calendar) {
        byte length = 9;
        ByteBuffer buf = ByteBuffer.allocate(length);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        buf.put(CMD_GET_CURRENT_HISTORY_HEARTRATE);
        int year = calendar.get(Calendar.YEAR);
        int high = (year >> 8) & 0xFF;
        int low = year & 0xFF;
        buf.put((byte) low);
        buf.put((byte) high);
        buf.put((byte) (calendar.get(Calendar.MONTH) + 1));
        buf.put((byte) calendar.get(Calendar.DAY_OF_MONTH));
        buf.put(getChecksum(buf.array()));
        return buf.array();
    }

    public C60DeviceSupport getHeartrate(TransactionBuilder builder) {
        builder.write(C60Constants.CHARACTERISTIC_WRITE, CMD_GET_CURRENT_HEARTRATE);
        return this;
    }

    public C60DeviceSupport getBodytemp(TransactionBuilder builder) {
        builder.write(C60Constants.CHARACTERISTIC_WRITE, CMD_GET_CURRENT_BODYTEMP);
        return this;
    }
    public byte[] setDeviceStateCommand(Prefs prefs, String pref) {
        byte length = 20;
        ByteBuffer buf = ByteBuffer.allocate(length);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put(CMD_SET_DEVICE_STATE);
        buf.put(this.currentDeviceSettings);
        switch (pref) {
            case DeviceSettingsPreferenceConst.PREF_TIMEFORMAT:
                buf = setDeviceStateTimeformatCommand(buf, prefs.getString(pref, DeviceSettingsPreferenceConst.PREF_TIMEFORMAT_AUTO));
                break;
            case DeviceSettingsPreferenceConst.PREF_LANGUAGE:
                buf = setDeviceStateLanguageCommand(buf, prefs.getString(pref, "en_US"));
                break;
            case DeviceSettingsPreferenceConst.PREF_LIFTWRIST_NOSHED:
                buf = setDeviceStateLiftwristCommand(buf, prefs.getBoolean(pref, true));
                break;
        }
        buf.put(getChecksum(buf.array()));
        this.currentDeviceSettings = trimData(buf.array());
        return buf.array();
    }

    public byte[] setDoNotDisturbCommand(Prefs prefs, String pref) {
        byte length = 20;
        ByteBuffer buf = ByteBuffer.allocate(length);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put(CMD_SET_DO_NOT_DISTURB);
        buf.put(this.currentDndSettings);
        LocalTime time;
        switch (pref) {
            case DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_NOAUTO:
                byte stateByte = (
                        prefs.getString(pref, DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_OFF).equals(DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_SCHEDULED))
                        ? (byte) 0xff : 0x00;
                buf.put(3, stateByte);
                break;
            case DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_NOAUTO_START:
                time = prefs.getLocalTime(pref, "00:00");
                buf.put(4, (byte) time.getHour());
                buf.put(5, (byte) time.getMinute());
                break;
            case DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_NOAUTO_END:
                time = prefs.getLocalTime(pref, "00:00");
                buf.put(6, (byte) time.getHour());
                buf.put(7, (byte) time.getMinute());
                break;
        }
        buf.put(getChecksum(buf.array()));
        this.currentDndSettings = trimData(buf.array());
        return buf.array();
    }

    public ByteBuffer setDeviceStateTimeformatCommand(ByteBuffer buf, String timeFormat) {
        byte timeformatByte;
        if (timeFormat.equals(DeviceSettingsPreferenceConst.PREF_TIMEFORMAT_12H)) {
            timeformatByte = 0x01;
        } else if (timeFormat.equals(DeviceSettingsPreferenceConst.PREF_TIMEFORMAT_24H)) {
            timeformatByte = 0x00;
        } else {
            timeformatByte = (byte) (DateFormat.is24HourFormat(GBApplication.getContext()) ? 0x00 : 0x01);
        }
        buf.put(8, timeformatByte);
        return buf;
    }

    public ByteBuffer setDeviceStateLanguageCommand(ByteBuffer buf, String languageCode) {
        Integer langIdObj = C60Constants.LANGUAGES.getOrDefault(languageCode, 0);
        int langId = (langIdObj != null) ? langIdObj : 0;
        buf.put(6, (byte) langId);
        return buf;
    }

    public ByteBuffer setDeviceStateLiftwristCommand(ByteBuffer buf, boolean state) {
        byte stateByte = (state) ? (byte) 0x01 : 0x00;
        buf.put(9, stateByte);
        return buf;
    }

//    public C60DeviceSupport setDeviceState(TransactionBuilder builder) {
//        builder.write(C60Constants.CHARACTERISTIC_WRITE, CMD_SET_DEVICE_STATE);
//        return this;
//    }

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

    private byte[] trimData(byte[] data) {
        int newLen = data.length - 4;
        byte[] trimmed = new byte[newLen];
        System.arraycopy(data, 3, trimmed, 0, newLen);
        return trimmed;
    }

    // UNUSED might be used later
    // setting of sport modes
    // full hashmap in SPORT_MODES constant
//    Map<Integer, Boolean> statusMap = new HashMap<>();
//    statusMap.put(0, true);   // walk
//    statusMap.put(1, true);   // run
//    statusMap.put(8, true);   // badminton
//    statusMap.put(24, false); // indoor_walk
//    public static byte[] buildSportModePacket(Map<Integer, Boolean> statusMap) {
//        byte[] packet = new byte[20];
//        // Header / command fields (match original)
//        packet[0] = Opcodes.OPC_laload; // must match device expected start byte
//        packet[1] = 16;
//        packet[2] = 0;
//        packet[3] = 2;
//
//        // Helper to build a byte from mode indices range [base..base+7]
//        java.util.function.IntFunction<Byte> buildByte = base -> {
//            byte[] bits = new byte[8];
//            for (int i = 0; i < 8; i++) {
//                int modeIdx = base + i;
//                // mapping in original: mode base..base+7 -> bits[7..0]
//                int bitArrayIndex = 7 - i;
//                boolean val = false;
//                if (statusMap != null && statusMap.containsKey(modeIdx)) {
//                    Boolean b = statusMap.get(modeIdx);
//                    val = b != null && b;
//                }
//                bits[bitArrayIndex] = (byte) (val ? 1 : 0);
//            }
//            int intVal = ByteDataConvertUtil.Bit8Array2Int(bits);
//            return (byte) intVal;
//        };
//
//        // bArr[4] covers modes 0..7, bArr[5] 8..15, bArr[6] 16..23, bArr[7] 24..31 (we use up to 25)
//        packet[4] = buildByte.apply(0);
//        packet[5] = buildByte.apply(8);
//        packet[6] = buildByte.apply(16);
//        // For base 24 only modes 24..25 exist; remaining bits remain zero
//        packet[7] = buildByte.apply(24);
//
//        // bytes 8..18 are zero (explicitly set to 0 for clarity)
//        for (int i = 8; i <= 18; i++) packet[i] = 0;
//
//        // checksum (uses existing helper)
//        packet[19] = CmdHelper.completeCheckCode(packet);
//
//        return packet;
//    }
}
