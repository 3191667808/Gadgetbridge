package nodomain.freeyourgadget.gadgetbridge.service.devices.sr08ring;


import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventCameraRemote;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractTimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.sr08ring.SR08RingConstants;
import nodomain.freeyourgadget.gadgetbridge.devices.sr08ring.samples.SR08ActivitySampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.sr08ring.samples.SR08SleepSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.AbstractTimeSample;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.SR08ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.SR08SleepSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService;
import nodomain.freeyourgadget.gadgetbridge.model.GenericItem;
import nodomain.freeyourgadget.gadgetbridge.model.ItemWithDetails;
import nodomain.freeyourgadget.gadgetbridge.model.SleepScoreSample;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLEDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.BLETypeConversions;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattCharacteristic;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.actions.SetDeviceStateAction;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class Sr08RingDeviceSupport extends AbstractBTLEDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(Sr08RingDeviceSupport.class);
    private final Handler backgroundTasksHandler = new Handler(Looper.getMainLooper());
    boolean active = false;

    public Sr08RingDeviceSupport() {
        super(LOG);
        addSupportedService(GattService.UUID_SERVICE_GENERIC_ACCESS);
        addSupportedService(GattService.UUID_SERVICE_GENERIC_ATTRIBUTE);
        addSupportedService(GattService.UUID_SERVICE_HUMAN_INTERFACE_DEVICE);

        addSupportedService(UUID.fromString("0000fef5-0000-1000-8000-00805f9b34fb"));
        addSupportedService(UUID.fromString("000056ff-0000-1000-8000-00805f9b34fb"));
    }


    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        // mark the device as initializing
        builder.add(new SetDeviceStateAction(getDevice(), GBDevice.State.INITIALIZING, getContext()));

        // ... custom initialization logic ...

        // set device firmware to prevent the following error when you (later) try to save data to database and
        // device firmware has not been set yet
        // Error executing 'the bind value at index 2 is null'java.lang.IllegalArgumentException: the bind value at index 2 is null
        getDevice().setFirmwareVersion("N/A");
        getDevice().setFirmwareVersion2("N/A");

        // mark the device as initialized
        builder.add(new SetDeviceStateAction(getDevice(), GBDevice.State.INITIALIZED, getContext()));
        builder.notify(getCharacteristic(GattCharacteristic.UUID_CHARACTERISTIC_HEART_RATE_MEASUREMENT), true);

        builder.notify(getCharacteristic(GattCharacteristic.UUID_CHARACTERISTIC_MANUFACTURER_NAME_STRING), true);
        builder.notify(getCharacteristic(UUID.fromString("000056ff-0000-1000-8000-00805f9b34fb")), true);

        builder.notify(getCharacteristic(SR08RingConstants.CHARACTERISTIC_READ), true);

        // Delay initialization with 2 seconds to give the ring time to settle
        backgroundTasksHandler.removeCallbacksAndMessages(null);
        backgroundTasksHandler.postDelayed(this::postConnectInit, 2000);

        return builder;

    }

    private void postConnectInit() {
        setAppId();
        setTime();
        setHourFormat();
        setHeartRateMeasurement();
        setLanguage();
        //setBloodOxygenEnabled(true);

        getDeviceInfo();
        getBatteryState();
    }

    private void getDeviceInfo() {
        send("deviceInfo", SR08RingConstants.CMD_GET_DEVICE_INFO, new byte[]{});
    }

    private void setHeartRateMeasurement() {

    }

    private void setHourFormat() {
        boolean is24hFormat = getDevicePrefs().getTimeFormat().equals(DeviceSettingsPreferenceConst.PREF_TIMEFORMAT_24H);
        send("setHourFormat", SR08RingConstants.CMD_SET_HOUR_FORMAT, new byte[]{(byte) (is24hFormat ? 0 : 1)});
    }

    private void getBatteryState() {
        send("getBatteryState", SR08RingConstants.CMD_GET_BATTERY, new byte[]{});
    }

    private void setTime() {
        final Instant now = Instant.now();
        send("setTime", SR08RingConstants.CMD_SET_TIME, BLETypeConversions.fromUint32((int) now.getEpochSecond()));
    }

    private void setBloodOxygenEnabled(boolean enabled) {
        send("setBloodOxygenEnabled", SR08RingConstants.CMD_SET_SPO2_ENABLED, new byte[]{(byte) (enabled ? 1 : 0)});
        //send("setBloodOxygenEnabled", (byte) 35, new byte[]{(byte) (enabled ? 1 : 0)});
    }



    private void setLiveHeartRateEnabled(boolean enabled, int b, int activitySlot) { // slot 1-5 for continues meass
        if (enabled) {
            byte[] data = ByteBuffer.allocate(5)
                    .put(BLETypeConversions.fromUint32(b))
                    .put((byte) activitySlot)
                    .array();
            send("setLiveHeartRateEnabled", SR08RingConstants.CMD_SET_LIVE_HEART_RATE_ENABLED, data);
        } else {
            send("setLiveHeartRateDisabled", SR08RingConstants.CMD_SET_LIVE_HEART_RATE_DISABLED, new byte[]{0, 0, 0, 0, (byte) activitySlot});
        }
    }

    private void setLanguage() {
        Locale locale = Locale.getDefault();
        String language = locale.getLanguage();
        String country = locale.getCountry();
        if (language.endsWith("zh")) {
            if ("中国".equalsIgnoreCase(locale.getDisplayCountry())) {
                country = "CN";
            } else {
                country = "TW";
            }
        }
        String str = language + "-" + country;
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        send("setLanguage", SR08RingConstants.CMD_SET_LANG, Arrays.copyOf(bytes, 19));
    }

    private void triggerActivityReport() { // TODO days?
        send("triggerActivityReport", SR08RingConstants.CMD_TRIGGER_ACTIVITY_REPORT, new byte[]{});
    }

    private void triggerHearRateReport() {
        send("triggerHearRateReport", SR08RingConstants.CMD_TRIGGER_HEART_RATE_REPORT, new byte[]{});
    }

    private void triggerSportReport() {
        send("triggerSportReport", SR08RingConstants.CMD_TRIGGER_SPORT_REPORT, new byte[]{});
    }

    private void setAppId() {
        String uuid = "e71b09b8-9757-47df-b0c1-b51b640d29c8"; // Todo non constant
        send("setAppId", SR08RingConstants.CMD_SET_APP_ID, uuid.substring(0, 18).getBytes(StandardCharsets.UTF_8));
    }

    private void send(String taskName, byte cmd, byte[] data) {
        TransactionBuilder builder = new TransactionBuilder(taskName);
        if (data.length > 19) {
            LOG.warn("package for task {} to long: size {} > 19", taskName, data.length);
            return;
        }
        byte[] contents = ByteBuffer.allocate(20)
                .put(cmd)
                .put(data)
                .array();
        BluetoothGattCharacteristic characteristic = getCharacteristic(SR08RingConstants.CHARACTERISTIC_WRITE);
        if (characteristic != null) {
            builder.write(characteristic, contents);
            builder.queue(getQueue());
        }
    }

    private Map<Instant, Byte> parseReportBlock(byte[] data) {
        Map<Instant, Byte> entries = new HashMap<>();
        Instant timestamp = Instant.ofEpochSecond(BLETypeConversions.toUint32(data, 1));
        for (int i = 5; i < 20; i++) {
            entries.put(timestamp, data[i]);
            timestamp = timestamp.plus(1, ChronoUnit.MINUTES);
        }
        return entries;
    }

    @Override
    public void onCameraStatusChange(GBDeviceEventCameraRemote.Event event, String filename) {
        if (event == GBDeviceEventCameraRemote.Event.OPEN_CAMERA) {
            send("setCameraMode", SR08RingConstants.CMD_SET_CAMERA_MODE, new byte[]{1});
        } else if (event == GBDeviceEventCameraRemote.Event.CLOSE_CAMERA) {
            send("setCameraMode", SR08RingConstants.CMD_SET_CAMERA_MODE, new byte[]{0});
        }
    }

    @Override
    public void onEnableRealtimeSteps(boolean enable) {
        super.onEnableRealtimeSteps(enable);
    }

    @Override
    public void onFindDevice(boolean start) { // TODO repeat
        if (start)
            send("findDevice", SR08RingConstants.CMD_TRIGGER_BLINK, new byte[]{});
    }

    @Override
    public void onReset(int flags) {
        super.onReset(flags);
    }

    @Override
    public void onSetHeartRateMeasurementInterval(int seconds) {
        super.onSetHeartRateMeasurementInterval(seconds);
    }

    @Override
    public void onSleepAsAndroidAction(String action, Bundle extras) {
        super.onSleepAsAndroidAction(action, extras);
    }

    @Override
    public void onEnableHeartRateSleepSupport(boolean enable) {
        super.onEnableHeartRateSleepSupport(enable);
    }

    @Override
    public void onFetchRecordedData(int dataTypes) {
        GB.updateTransferNotification(getContext().getString(R.string.busy_task_fetch_activity_data), "", true, 0, getContext());

        triggerActivityReport();
        //triggerSportReport();
        //triggerHearRateReport();
    }

    @Override
    public void onEnableRealtimeHeartRateMeasurement(boolean enable) {
        if (enable == active) return;
        active = enable;
        setLiveHeartRateEnabled(enable, 0, 1);
    }

    @Override
    public void onHeartRateTest() {
        setLiveHeartRateEnabled(true, 0, 0);
    }

    public boolean onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        super.onCharacteristicChanged(gatt, characteristic);

        UUID characteristicUUID = characteristic.getUuid();
        byte[] characteristicValue = characteristic.getValue();

        if (characteristicUUID.equals(SR08RingConstants.CHARACTERISTIC_READ)) {
            if (characteristicValue.length != 20) {
                LOG.error("unexpected length {}!", characteristicValue.length);
                return false;
            }
            switch (characteristicValue[0]) {
                case SR08RingConstants.CMD_GET_BATTERY:
                    evaluateBatteryData(characteristicValue);
                    break;
                case SR08RingConstants.CMD_SET_TIME:
                    LOG.info("time set");
                    break;
                case SR08RingConstants.CMD_SET_HOUR_FORMAT:
                    LOG.info("time format set");
                    break;
                case SR08RingConstants.CMD_SET_LANG:
                    LOG.info("language set");
                    break;
                case SR08RingConstants.CMD_SET_LIVE_HEART_RATE_ENABLED:
                    int unixtime = BLETypeConversions.toUint32(characteristicValue, 1);
                    if (unixtime == 0) {
                        LOG.info("pulse: measurement error");
                        break;
                    }
                    Instant time = Instant.ofEpochSecond(unixtime);
                    int pulse = characteristicValue[5] & 0xff;
                    LOG.info("pulse: {} {}", time, pulse);
                    try (DBHandler db = GBApplication.acquireDB()) {
                        SR08ActivitySampleProvider sampleProvider = new SR08ActivitySampleProvider(getDevice(), db.getDaoSession());
                        Long userId = DBHelper.getUser(db.getDaoSession()).getId();
                        Long deviceId = DBHelper.getDevice(getDevice(), db.getDaoSession()).getId();
                        SR08ActivitySample gbSample = new SR08ActivitySample();
                        gbSample.setDeviceId(deviceId);
                        gbSample.setUserId(userId);
                        gbSample.setTimestamp(unixtime);
                        gbSample.setHeartRate(pulse);
                        sampleProvider.addGBActivitySample(gbSample);
                        //if (ChronoUnit.SECONDS.between(time, Instant.now()) <= 5) {
                        Intent intent = new Intent(DeviceService.ACTION_REALTIME_SAMPLES)
                                .putExtra(GBDevice.EXTRA_DEVICE, getDevice())
                                .putExtra(DeviceService.EXTRA_REALTIME_SAMPLE, gbSample);
                        LocalBroadcastManager.getInstance(getContext()).sendBroadcast(intent);
                        //}
                    } catch (Exception e) {
                        LOG.error("Error acquiring database for recording heart rate samples", e);
                    }
                    break;
                case 0x24:
                    LOG.info("spo2: {}", characteristicValue[4]);
                    break;
                case SR08RingConstants.CMD_TRIGGER_ACTIVITY_REPORT:
                    Map<Instant, Byte> stepEntries = parseReportBlock(characteristicValue);
                    LOG.info("steps {}", stepEntries);
                    safeStepData(stepEntries);
                    break;
                case 0x11:
                    Map<Instant, Byte> sleepEntries = parseReportBlock(characteristicValue);
                    LOG.info("sleep {}", sleepEntries);
                    safeTimeSamplesData(SR08SleepSampleProvider.class, // TODO change to activity 40 99
                            sleepEntries,
                            v -> {
                                SR08SleepSample sample = new SR08SleepSample();
                                sample.setSleepScore(v);
                                return sample;
                            });
                    break;
                case SR08RingConstants.CMD_TRIGGER_HEART_RATE_REPORT:
                    Map<Instant, Byte> pulseEntries = parseReportBlock(characteristicValue);
                    LOG.info("pulse {}", pulseEntries);
                    break;
                case 19:
                    int something_step_unix = BLETypeConversions.toUint32(characteristicValue, 1);
                    Instant something_step_time = Instant.ofEpochSecond(something_step_unix);
                    LOG.warn("{} step {} unknown {}", something_step_time, BLETypeConversions.toUint32(characteristicValue, 5), characteristicValue[14]);
                case 3:
                    int step_unix = BLETypeConversions.toUint32(characteristicValue, 1);
                    Instant step_time = Instant.ofEpochSecond(step_unix);
                    LOG.info("{} step {} dist {} cal {}",
                            step_time,
                            BLETypeConversions.toUint32(characteristicValue, 5),
                            BLETypeConversions.toUint32(characteristicValue, 9),
                            BLETypeConversions.toUint32(characteristicValue, 13));
                    /*
                    Intent intent = new Intent(DeviceService.ACTION_REALTIME_SAMPLES)
                            .putExtra(GBDevice.EXTRA_DEVICE, device)
                            .putExtra(DeviceService.EXTRA_REALTIME_SAMPLE, activitySample);
                     */
                    break;
                case SR08RingConstants.CMD_GET_DEVICE_INFO:
                    getDevice().setFirmwareVersion("V" + BLETypeConversions.toUint16(characteristicValue, 1));
                    getDevice().addDeviceInfo(new GenericItem("CID",  Integer.toString(BLETypeConversions.toUint16(characteristicValue, 9))));
                    getDevice().addDeviceInfo(new GenericItem("DID", Integer.toString(BLETypeConversions.toUint16(characteristicValue, 11))));
                    getDevice().addDeviceInfo(new GenericItem("CRC", Integer.toHexString(BLETypeConversions.toUint32(characteristicValue, 16))));

                    LOG.info("mac: {}", ByteBuffer.wrap(characteristicValue, 3, 6));
                    break;
                default:
                    int maybe_unix = BLETypeConversions.toUint32(characteristicValue, 1);
                    Instant maybe_time = Instant.ofEpochSecond(maybe_unix);
                    LOG.warn("{} unknown type {}", maybe_time, characteristicValue);
                    /*32 0xf6 62
                    0x51 current run steps
                     * */
            }
        } else {
            LOG.warn("other characteristic {}", characteristicUUID);
        }
        return false;
    }

    private <T extends AbstractTimeSample> void safeTimeSamplesData(Class<? extends AbstractTimeSampleProvider<T>> c,
                                                                    Map<Instant, Byte> stepEntries,
                                                                    Function<Byte, T> mapper) {
        try (DBHandler db = GBApplication.acquireDB()) {
            TimeSampleProvider<T> sampleProvider = c.getDeclaredConstructor(GBDevice.class, DaoSession.class).newInstance(getDevice(), db.getDaoSession());
            Long userId = DBHelper.getUser(db.getDaoSession()).getId();
            Long deviceId = DBHelper.getDevice(getDevice(), db.getDaoSession()).getId();
            List<T> samples = stepEntries
                    .entrySet()
                    .stream()
                    .map(e -> {
                        T sample = mapper.apply(e.getValue());
                        sample.setTimestamp((int) (e.getKey().toEpochMilli() / 1000));
                        sample.setDeviceId(deviceId);
                        sample.setUserId(userId);
                        return sample;
                    })
                    .collect(Collectors.toList());
            sampleProvider.addSamples(samples);
        } catch (Exception e) {
            LOG.error("Error acquiring database for recording time samples", e);
        }
    }

    private void safeStepData(Map<Instant, Byte> stepEntries) {
        try (DBHandler db = GBApplication.acquireDB()) {
            SR08ActivitySampleProvider sampleProvider = new SR08ActivitySampleProvider(getDevice(), db.getDaoSession());
            Long userId = DBHelper.getUser(db.getDaoSession()).getId();
            Long deviceId = DBHelper.getDevice(getDevice(), db.getDaoSession()).getId();
            SR08ActivitySample[] samples = (SR08ActivitySample[]) stepEntries
                    .entrySet()
                    .stream()
                    .map(
                            e -> {
                                SR08ActivitySample gbSample = new SR08ActivitySample();
                                gbSample.setDeviceId(deviceId);
                                gbSample.setUserId(userId);
                                gbSample.setTimestamp((int) (e.getKey().toEpochMilli() / 1000));
                                gbSample.setHeartRate(e.getValue());
                                return gbSample;
                            }
                    )
                    .toArray();
            sampleProvider.addGBActivitySamples(samples);
        } catch (Exception e) {
            LOG.error("Error acquiring database for recording heart rate samples", e);
        }
    }

    private void evaluateBatteryData(byte[] characteristicValue) {
        GBDeviceEventBatteryInfo batteryEvent = new GBDeviceEventBatteryInfo();
        batteryEvent.level = characteristicValue[1];
        if (characteristicValue[2] == 1) {
            if (batteryEvent.level == 100) {
                batteryEvent.state = BatteryState.BATTERY_CHARGING_FULL;
            } else {
                batteryEvent.state = BatteryState.BATTERY_CHARGING;
            }
        } else {
            batteryEvent.state = BatteryState.BATTERY_NORMAL;
        }

        evaluateGBDeviceEvent(batteryEvent);
    }

    @Override
    public boolean useAutoConnect() {
        return false;
    }
}
