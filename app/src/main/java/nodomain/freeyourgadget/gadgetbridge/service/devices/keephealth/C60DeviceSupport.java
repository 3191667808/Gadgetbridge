package nodomain.freeyourgadget.gadgetbridge.service.devices.keephealth;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.SharedPreferences;
import android.text.format.DateFormat;
import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHeartRateSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSpo2SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.keephealth.C60Constants;
import nodomain.freeyourgadget.gadgetbridge.devices.keephealth.KeephealthBloodPressureSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.keephealth.KeephealthSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSpo2Sample;
import nodomain.freeyourgadget.gadgetbridge.entities.KeephealthActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.KeephealthBloodPressureSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;
import nodomain.freeyourgadget.gadgetbridge.util.StringUtils;

public class C60DeviceSupport extends AbstractBTLESingleDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(C60DeviceSupport.class);
    private ByteBuffer cmdBuff = null;
    private final GBDeviceEventBatteryInfo batteryCmd = new GBDeviceEventBatteryInfo();

    private final byte[] CMD_GET_DEVICE_DATA = { 0x01, 0x00, 0x00, (byte) 0xb0 };


    private final byte[] CMD_GET_DEVICE_STATE = { 0x02, 0x00, 0x00, 0x06 };
    private final byte[] CMD_SET_DEVICE_STATE = {
            (byte) 0x02, (byte) 0x10, (byte) 0x00
    };

    private final byte[] CMD_SET_USER_INFO = {
            (byte) 0x03, (byte) 0x07, (byte) 0x00
    };

    // TODO find more about
    // two fragment response
    private final byte[] CMD_GET_ALARM = { 0x05, 0x00, 0x08, (byte) 0x80 };

    private final byte[] CMD_GET_INACTIVITY = {
            (byte) 0x06, (byte) 0x00, (byte) 0x00, (byte) 0x5e
    };
    private final byte[] CMD_SET_INACTIVITY = {
            (byte) 0x06, (byte) 0x05, (byte) 0x00
    };

    private final byte[] CMD_GET_TARGET_DATA = { 0x07, 0x00, 0x00, (byte) 0xb4 };
    private final byte[] CMD_SET_TARGET_DATA = { 0x07, 0x0e, 0x00 };

    private final byte[] CMD_GET_DO_NOT_DISTURB = {
            (byte) 0x08, (byte) 0x00, (byte) 0x00, (byte) 0x0a
    };
    private final byte[] CMD_SET_DO_NOT_DISTURB = {
            (byte) 0x08, (byte) 0x10, (byte) 0x00
    };

    // TODO find more about
    private final byte[] CMD_GET_NOTICE = { 0x09, 0x00, 0x00, (byte) 0x60 };

    private final byte[] CMD_GET_CURRENT_STEPS = { 0x20, 0x01, 0x00, 0x00, 0x70 };
    private final byte[] CMD_GET_CURRENT_HISTORY_STEP = { 0x20, 0x05, 0x00, 0x01 };


    private final byte[] CMD_GET_CURRENT_HEARTRATE = { 0x21, 0x01, 0x00, 0x00, (byte) 0xc6 };

    // TODO find more about
    // Obtain blood pressure and blood oxygen data
    private final byte[] CMD_GET_OXYGEN = { 0x21, 0x01, 0x00, 0x07, (byte) 0x20 };
    // TODO find more about
    // Obtaining automatic heart rate sampling data
    private final byte[] CMD_GET_HEARTRATE_SAMPLING = { 0x21, 0x01, 0x00, 0x08, (byte) 0x76 };
    // Step count and sleep history data, 15 fragment response
    private final byte[] CMD_GET_CURRENT_HISTORY_HEARTRATE = { 0x21, 0x05, 0x00, 0x01 };

    private final byte[] CMD_GET_CURRENT_BATTERY = { 0x27, 0x00, 0x00, 0x74 };

    private final byte[] CMD_GET_CURRENT_BODYTEMP = { 0x2c, 0x01, 0x00, 0x00, (byte) 0x78 };

    private final byte[] CMD_GET_HYDRATION = { 0x2e, 0x01, 0x00, 0x01, (byte) 0x7a };
    private final byte[] CMD_SET_HYDRATION = { 0x2e, 0x17, 0x00 };

    private byte[] currentDeviceSettings = null;
    private byte[] currentDndSettings = null;

    private int daysAgo;
    private Calendar syncingDay;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> responseTimeoutFuture;

    private synchronized void startResponseTimeout() {
        cancelResponseTimeout();
        long RESPONSE_TIMEOUT_MS = 10_000;
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
        getInactivityState(builder);
        builder.wait(wait);
        getTargetData(builder);
        builder.wait(wait);
        getHydration(builder);
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
                } else if (cmdPrefix == CMD_GET_INACTIVITY[0]) {
                    handleInactivity(value);
                } else if (cmdPrefix == CMD_GET_TARGET_DATA[0]) {
                    handleTargetData(value);
                } else if (cmdPrefix == CMD_GET_HYDRATION[0]) {
                    handleHydration(value);
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
                configPacket = setDeviceStateCommand(prefs);
                break;
            case DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_NOAUTO:
            case DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_NOAUTO_START:
            case DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_NOAUTO_END:
                configPacket = setDoNotDisturbCommand(prefs);
                break;
            case DeviceSettingsPreferenceConst.PREF_INACTIVITY_ENABLE:
            case DeviceSettingsPreferenceConst.PREF_INACTIVITY_THRESHOLD:
            case DeviceSettingsPreferenceConst.PREF_INACTIVITY_START:
            case DeviceSettingsPreferenceConst.PREF_INACTIVITY_END:
            case DeviceSettingsPreferenceConst.PREF_INACTIVITY_MO:
            case DeviceSettingsPreferenceConst.PREF_INACTIVITY_TU:
            case DeviceSettingsPreferenceConst.PREF_INACTIVITY_WE:
            case DeviceSettingsPreferenceConst.PREF_INACTIVITY_TH:
            case DeviceSettingsPreferenceConst.PREF_INACTIVITY_FR:
            case DeviceSettingsPreferenceConst.PREF_INACTIVITY_SA:
            case DeviceSettingsPreferenceConst.PREF_INACTIVITY_SU:
                configPacket = setInactivityCommand(prefs);
                break;
            case DeviceSettingsPreferenceConst.PREF_USER_FITNESS_GOAL_NOTIFICATION:
            case DeviceSettingsPreferenceConst.PREF_USER_FITNESS_GOAL:
            case ActivityUser.PREF_USER_CALORIES_BURNT:
            case ActivityUser.PREF_USER_DISTANCE_METERS:
                configPacket = setGoalCommand(prefs);
                break;
            case DeviceSettingsPreferenceConst.PREF_HYDRATION_SWITCH:
            case DeviceSettingsPreferenceConst.PREF_HYDRATION_REMINDER_START:
            case DeviceSettingsPreferenceConst.PREF_HYDRATION_REMINDER_END:
                configPacket = setHydrationCommand(prefs);
                break;
            case ActivityUser.PREF_USER_GENDER:
            case ActivityUser.PREF_USER_DATE_OF_BIRTH:
            case ActivityUser.PREF_USER_HEIGHT_CM:
            case ActivityUser.PREF_USER_WEIGHT_KG:
            case ActivityUser.PREF_USER_STEP_LENGTH_CM:
                configPacket = setUserInfoCommand(prefs);
                break;
            default:
                LOG.debug("Unknown pref: {}", config);
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
    }

    private void sendWrite(String taskName, byte[] contents) {
        final int CHUNK_SIZE = 20;
        TransactionBuilder builder = createTransactionBuilder(taskName);
        BluetoothGattCharacteristic characteristic = getCharacteristic(C60Constants.CHARACTERISTIC_WRITE);
        if (characteristic != null) {
            if (contents.length > CHUNK_SIZE) {
                for (int offset = 0; offset < contents.length; offset += CHUNK_SIZE) {
                    int len = Math.min(CHUNK_SIZE, contents.length - offset);
                    byte[] chunk = Arrays.copyOfRange(contents, offset, offset + len);
                    builder.write(characteristic, chunk);
                }
            } else {
                builder.write(characteristic, contents);
            }
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
            Prefs prefs = getDevicePrefs();
            SharedPreferences sharedPrefs = prefs.getPreferences();
            String langCode = "en_US";
            for (Map.Entry<String,Integer> e : C60Constants.LANGUAGES.entrySet()) {
                if (e.getValue() == info[6]) langCode = e.getKey();
            }
            sharedPrefs.edit()
                    .putString(DeviceSettingsPreferenceConst.PREF_LANGUAGE, langCode)
                    .putString(DeviceSettingsPreferenceConst.PREF_TIMEFORMAT, info[8] == 0x01 ? DeviceSettingsPreferenceConst.PREF_TIMEFORMAT_12H : DeviceSettingsPreferenceConst.PREF_TIMEFORMAT_24H)
                    .putBoolean(DeviceSettingsPreferenceConst.PREF_LIFTWRIST_NOSHED, info[9] == 0x01)
                    .apply();
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
                            activitySample[sampleIndex].setSteps(value);
                            LOG.debug("sample {} time {}:{} steps {}", sampleIndex, hour, minute, value);
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
                List<GenericHeartRateSample> samples = new ArrayList<>();
                List<GenericSpo2Sample> spo2Samples = new ArrayList<>();
                List<KeephealthBloodPressureSample> bpSamples = new ArrayList<>();

                try (DBHandler db = GBApplication.acquireDB()) {
                    Long userId = DBHelper.getUser(db.getDaoSession()).getId();
                    Long deviceId = DBHelper.getDevice(getDevice(), db.getDaoSession()).getId();
                    GenericHeartRateSampleProvider sampleProvider = new GenericHeartRateSampleProvider(getDevice(), db.getDaoSession());
                    GenericSpo2SampleProvider spo2SampleProvider = new GenericSpo2SampleProvider(getDevice(), db.getDaoSession());
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
                        GenericHeartRateSample sample = new GenericHeartRateSample(timestamp, deviceId);
                        sample.setHeartRate(hr);
                        samples.add(sample);

                        GenericSpo2Sample spo2Sample = new GenericSpo2Sample(timestamp, deviceId);
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
                Prefs prefs = getDevicePrefs();
                SharedPreferences sharedPrefs = prefs.getPreferences();
                sharedPrefs.edit()
                        .putString(DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_NOAUTO, data[3] == (byte) 0xff ? DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_SCHEDULED : DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_OFF)
                        .putString(DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_NOAUTO_START, (int)data[4] + ":" + (int)data[5])
                        .putString(DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_NOAUTO_END, (int)data[6] + ":" + (int)data[7])
                        .apply();
                LOG.debug("Saved DND settings: " + GB.hexdump(this.currentDndSettings));
            }
        } else {
            LOG.debug("other sleep/dnd data: " + GB.hexdump(data));
        }
    }

    private void handleInactivity(byte[] data) {
        if (data.length == 9) {
            Prefs prefs = getDevicePrefs();
            SharedPreferences sharedPrefs = prefs.getPreferences();
            int rawMask = data[6] & 0xFF;
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");
            LocalTime start = LocalTime.of(data[4],0);
            LocalTime end = LocalTime.of(data[5], 0);
            sharedPrefs.edit()
                .putBoolean(DeviceSettingsPreferenceConst.PREF_INACTIVITY_ENABLE, data[3] == (byte) 0x01)
                .putString(DeviceSettingsPreferenceConst.PREF_INACTIVITY_THRESHOLD, String.valueOf(((int)data[7] * 5)))
                .putString(DeviceSettingsPreferenceConst.PREF_INACTIVITY_START, start.format(fmt))
                .putString(DeviceSettingsPreferenceConst.PREF_INACTIVITY_END, end.format(fmt))
                .putBoolean(DeviceSettingsPreferenceConst.PREF_INACTIVITY_MO, (rawMask & WeekdayMask.MON_BIT) != 0)
                .putBoolean(DeviceSettingsPreferenceConst.PREF_INACTIVITY_TU, (rawMask & WeekdayMask.TUE_BIT) != 0)
                .putBoolean(DeviceSettingsPreferenceConst.PREF_INACTIVITY_WE, (rawMask & WeekdayMask.WED_BIT) != 0)
                .putBoolean(DeviceSettingsPreferenceConst.PREF_INACTIVITY_TH, (rawMask & WeekdayMask.THU_BIT) != 0)
                .putBoolean(DeviceSettingsPreferenceConst.PREF_INACTIVITY_FR, (rawMask & WeekdayMask.FRI_BIT) != 0)
                .putBoolean(DeviceSettingsPreferenceConst.PREF_INACTIVITY_SA, (rawMask & WeekdayMask.SAT_BIT) != 0)
                .putBoolean(DeviceSettingsPreferenceConst.PREF_INACTIVITY_SU, (rawMask & WeekdayMask.SUN_BIT) != 0)
                .apply();
        }
    }

    private void handleTargetData(byte[] data) {
        if (data.length == 9) {
            // TODO should i do something with it?
            LOG.debug("Received target data");
        }
    }

    private void handleHydration(byte[] data) {
        if (data.length == 27) {
            Prefs prefs = getDevicePrefs();
            SharedPreferences sharedPrefs = prefs.getPreferences();
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");
            LocalTime start = LocalTime.of(data[10], data[11]);
            LocalTime end = LocalTime.of(data[24], data[25]);
            sharedPrefs.edit()
                    .putBoolean(DeviceSettingsPreferenceConst.PREF_HYDRATION_SWITCH, data[4] == (byte) 0xff)
                    .putString(DeviceSettingsPreferenceConst.PREF_HYDRATION_REMINDER_START, start.format(fmt))
                    .putString(DeviceSettingsPreferenceConst.PREF_HYDRATION_REMINDER_END, end.format(fmt))
                    .apply();
            LOG.debug("saved hydration ({}) from: {} to: {}", data[4] == (byte) 0xff, start.format(fmt), end.format(fmt));
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
    public C60DeviceSupport getInactivityState(TransactionBuilder builder) {
        builder.write(C60Constants.CHARACTERISTIC_WRITE, CMD_GET_INACTIVITY);
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

    public byte[] setUserInfoCommand(Prefs prefs) {
        byte length = 11;
        ActivityUser activityUser = new ActivityUser();
        ByteBuffer buf = ByteBuffer.allocate(length);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put(CMD_SET_USER_INFO);
        byte gender = (byte) (activityUser.getGender() == ActivityUser.GENDER_FEMALE ? 0x01 : 0x00);
        buf.put(gender);
        byte years = (byte) activityUser.getAge();
        buf.put(years);
        int heightCm = activityUser.getHeightCm();
        buf.put((byte) (heightCm & 0xFF));
        buf.put((byte) ((heightCm >> 8) & 0xFF));
        int weightKg = activityUser.getWeightKg() * 10;
        buf.put((byte) (weightKg & 0xFF));
        buf.put((byte) ((weightKg >> 8) & 0xFF));
        int stepCm = activityUser.getStepLengthCm();
        buf.put((byte) stepCm);
        buf.put(getChecksum(buf.array()));
        return buf.array();
    }

    public byte[] setDeviceStateCommand(Prefs prefs) {
        byte length = 20;
        ByteBuffer buf = ByteBuffer.allocate(length);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put(CMD_SET_DEVICE_STATE);
        buf.put(this.currentDeviceSettings);

        // set language byte
        Integer langIdObj = C60Constants.LANGUAGES.getOrDefault(prefs.getString(DeviceSettingsPreferenceConst.PREF_LANGUAGE, "en_US"), 0);
        int langId = (langIdObj != null) ? langIdObj : 0;
        buf.put(6, (byte) langId);

        // set timeformat byte
        byte timeformatByte;
        String timeFormat = prefs.getString(DeviceSettingsPreferenceConst.PREF_TIMEFORMAT, DeviceSettingsPreferenceConst.PREF_TIMEFORMAT_AUTO);
        if (timeFormat.equals(DeviceSettingsPreferenceConst.PREF_TIMEFORMAT_12H)) {
            timeformatByte = 0x01;
        } else if (timeFormat.equals(DeviceSettingsPreferenceConst.PREF_TIMEFORMAT_24H)) {
            timeformatByte = 0x00;
        } else {
            timeformatByte = (byte) (DateFormat.is24HourFormat(GBApplication.getContext()) ? 0x00 : 0x01);
        }
        buf.put(8, timeformatByte);

        // set liftwrist byte
        byte stateByte = (prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_LIFTWRIST_NOSHED, true)) ? (byte) 0x01 : 0x00;
        buf.put(9, stateByte);

        buf.put(getChecksum(buf.array()));
        this.currentDeviceSettings = trimData(buf.array());
        return buf.array();
    }

    public byte[] setDoNotDisturbCommand(Prefs prefs) {
        byte length = 20;
        ByteBuffer buf = ByteBuffer.allocate(length);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put(CMD_SET_DO_NOT_DISTURB);
        buf.put(this.currentDndSettings);
        byte enabled = (prefs.getString(DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_NOAUTO, DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_OFF).equals(DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_SCHEDULED))
                ? (byte) 0xff : (byte) 0x00;
        buf.put(3, enabled); // 3
        LocalTime time;
        time = prefs.getLocalTime(DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_NOAUTO_START, "00:00");
        buf.put(4, (byte) time.getHour());   // 4
        buf.put(5, (byte) time.getMinute()); // 5
        time = prefs.getLocalTime(DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_NOAUTO_END, "00:00");
        buf.put(6, (byte) time.getHour());   // 6
        buf.put(7, (byte) time.getMinute()); // 7
        buf.put(getChecksum(buf.array()));
        this.currentDndSettings = trimData(buf.array());
        return buf.array();
    }

    public byte[] setInactivityCommand(Prefs prefs) {
        byte length = 9;
        ByteBuffer buf = ByteBuffer.allocate(length);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put(CMD_SET_INACTIVITY);

        // enable flag
        byte enable = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_INACTIVITY_ENABLE, false) ? (byte)0x01 : (byte)0x00;
        buf.put(enable);

        // start hour
        LocalTime time = prefs.getLocalTime(DeviceSettingsPreferenceConst.PREF_INACTIVITY_START, "00:00");
        buf.put((byte) time.getHour());

        // end hour
        time = prefs.getLocalTime(DeviceSettingsPreferenceConst.PREF_INACTIVITY_END, "00:00");
        buf.put((byte) time.getHour());

        // weekday mask (byte 6)
        int mask = 0;
        boolean anyDay = false;
        if (prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_INACTIVITY_MO, false)) { mask |= WeekdayMask.MON; anyDay = true; }
        if (prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_INACTIVITY_TU, false)) { mask |= WeekdayMask.TUE; anyDay = true; }
        if (prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_INACTIVITY_WE, false)) { mask |= WeekdayMask.WED; anyDay = true; }
        if (prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_INACTIVITY_TH, false)) { mask |= WeekdayMask.THU; anyDay = true; }
        if (prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_INACTIVITY_FR, false)) { mask |= WeekdayMask.FRI; anyDay = true; }
        if (prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_INACTIVITY_SA, false)) { mask |= WeekdayMask.SAT; anyDay = true; }
        if (prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_INACTIVITY_SU, false)) { mask |= WeekdayMask.SUN; anyDay = true; }

        // if no weekdays selected, set ONCE bit
        if (!anyDay) mask |= WeekdayMask.ONCE;

        buf.put((byte)(mask & 0xFF));

        // threshold (byte 7) stored as minutes/5
        int thresholdMinutes = prefs.getInt(DeviceSettingsPreferenceConst.PREF_INACTIVITY_THRESHOLD, 0);
        int thresholdUnits = Math.max(0, Math.min(255, thresholdMinutes / 5));
        buf.put((byte) thresholdUnits);

        buf.put(getChecksum(buf.array()));
        return buf.array();
    }

    public byte[] setGoalCommand(Prefs prefs) {
        ActivityUser activityUser = new ActivityUser();
        int steps = activityUser.getStepsGoal();
        int calories = activityUser.getCaloriesBurntGoal();
        int distance = activityUser.getDistanceGoalMeters() / 1000;  // ZeTime only accepts km goals

        byte length = 18;
        ByteBuffer buf = ByteBuffer.allocate(length);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put(CMD_SET_TARGET_DATA);
        buf.put((byte) 0x00); // sleep on/off
        buf.put((byte) 0x00); // sleep goal?

        // steps goal
        buf.put(prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_USER_FITNESS_GOAL_NOTIFICATION, false) ? (byte) 0x01 : 0x00);
        buf.put((byte) (steps & 0xFF));
        buf.put((byte) ((steps >>> 8) & 0xFF));
        buf.put((byte) ((steps >>> 16) & 0xFF));

        // calories is always disabled ?
        buf.put((byte) 0x00);
        buf.put((byte) (calories & 0xFF));
        buf.put((byte) ((calories >>> 8) & 0xFF));
        buf.put((byte) ((calories >>> 16) & 0xFF));

        // distance is always disabled ?
        buf.put((byte) 0x00);
        buf.put((byte) (distance & 0xFF));
        buf.put((byte) ((distance >>> 8) & 0xFF));
        buf.put((byte) ((distance >>> 16) & 0xFF));

        buf.put(getChecksum(buf.array()));
        return buf.array();
    }

    public byte[] setHydrationCommand(Prefs prefs) {
        byte length = 27;
        ByteBuffer buf = ByteBuffer.allocate(length);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put(CMD_SET_HYDRATION);

        // hardcoded
        buf.put((byte) 0x02);
        // enabled ?
        buf.put(prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_HYDRATION_SWITCH, false) ? (byte) 0xff : 0x00);
        buf.put((byte) 0x01);
        buf.put((byte) 0x08);
        buf.put((byte) 0x07);
        buf.put((byte) 0x00);
        buf.put((byte) 0x00);
        // start end hour
        LocalTime start = prefs.getLocalTime(DeviceSettingsPreferenceConst.PREF_HYDRATION_REMINDER_START, "00:00");
        LocalTime end = prefs.getLocalTime(DeviceSettingsPreferenceConst.PREF_HYDRATION_REMINDER_END, "00:00");

        int slots = 8;
        int startMinutes = start.getHour() * 60 + start.getMinute();
        int endMinutes = end.getHour() * 60 + end.getMinute();

        // handle crossing midnight
        if (endMinutes <= startMinutes) endMinutes += 24 * 60;

        double interval = (double) (endMinutes - startMinutes) / (slots - 1);

        List<LocalTime> result = new ArrayList<>();
        for (int i = 0; i < slots; i++) {
            int mins = (int) Math.round(startMinutes + i * interval); // round to nearest minute
            mins = ((mins % (24 * 60)) + (24 * 60)) % (24 * 60); // normalize to 0..1439
            int h = mins / 60;
            int m = mins % 60;
            result.add(LocalTime.of(h, m));
        }

        int counter = 1;
        for (LocalTime t : result) {
            LOG.debug("{} cup: {}", counter, t);
            buf.put((byte) t.getHour());
            buf.put((byte) t.getMinute());
            counter++;
        }

        buf.put(getChecksum(buf.array()));
        return buf.array();
    }

    public C60DeviceSupport setUserInfo(TransactionBuilder builder) {
        builder.write(C60Constants.CHARACTERISTIC_WRITE, CMD_SET_USER_INFO);
        return this;
    }

    public C60DeviceSupport getTargetData(TransactionBuilder builder) {
        builder.write(C60Constants.CHARACTERISTIC_WRITE, CMD_GET_TARGET_DATA);
        return this;
    }

    public C60DeviceSupport getHydration(TransactionBuilder builder) {
        builder.write(C60Constants.CHARACTERISTIC_WRITE, CMD_GET_HYDRATION);
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
