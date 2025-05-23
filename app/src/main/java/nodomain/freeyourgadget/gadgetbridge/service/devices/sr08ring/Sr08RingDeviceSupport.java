package nodomain.freeyourgadget.gadgetbridge.service.devices.sr08ring;


import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.os.Handler;
import android.os.Looper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.sr08ring.SR08RingConstants;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLEDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.BLETypeConversions;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattCharacteristic;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.actions.SetDeviceStateAction;

public class Sr08RingDeviceSupport extends AbstractBTLEDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(Sr08RingDeviceSupport.class);
    private final Handler backgroundTasksHandler = new Handler(Looper.getMainLooper());

    enum State {
        IDLE,
        ACTIVITY_REPORT,
        SPORT_REPORT
    }

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
        setTime();
        setAppId();
        setHourFormat();
        setHeartRateMeasurement();
        setLanguage();

        getBatteryState();

        triggerActivityReport();
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

    private  void setBloodOxygenEnabled(boolean enabled) {
        send("setBloodOxygenEnabled", SR08RingConstants.CMD_SET_SPO2_ENABLED, new byte[]{(byte) (enabled ? 1 : 0)});
    }

    /*
    private void setCameraTrigger(boolean enabled) {
        send("s", (byte) 7, new byte[]{(byte) (enabled ? 1 : 0)});
    }

    private void getCurSportData(){
        send("s", (byte) 3, new byte[]{});
    }
     */

    private void setLiveHeartRateEnabled(boolean enabled, int b, int c) {
        if (enabled) {
            ByteBuffer data = ByteBuffer.wrap(BLETypeConversions.fromUint32(b));
            data.put((byte) c);
            send("setLiveHeartRateEnabled", SR08RingConstants.SET_LIVE_HEART_RATE_ENABLED, data.array());
        } else {
            send("setLiveHeartRateDisabled", SR08RingConstants.SET_LIVE_HEART_RATE_DISABLED, new byte[]{0, 0, 0, 0, (byte) c});
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

    private void triggerActivityReport() {
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

    private Map<Instant, Byte> parseReportBlock(byte[] data){
        Map<Instant, Byte> entries = new HashMap<>();
        Instant timestamp = Instant.ofEpochSecond(BLETypeConversions.toUint32(data, 1));
        for (int i = 5; i < 20; i++) {
            entries.put(timestamp, data[i]);
            timestamp = timestamp.plus(1, ChronoUnit.MINUTES);
        }
        return entries;
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
                case 0x14:
                    Instant time = Instant.ofEpochSecond(BLETypeConversions.toUint32(characteristicValue, 1));
                    int pulse = characteristicValue[5] & 0xff;
                    LOG.info("pulse: {} {}", time, pulse);
                    break;
                case 0x24:
                    LOG.info("spo2: {}", characteristicValue[4]);
                    break;
                case SR08RingConstants.CMD_TRIGGER_ACTIVITY_REPORT:
                    Map<Instant, Byte> stepEntries = parseReportBlock(characteristicValue);
                    LOG.info("steps {}", stepEntries);
                    break;
                case 0x11:
                    Map<Instant, Byte> sleepEntries = parseReportBlock(characteristicValue);
                    LOG.info("sleep {}", sleepEntries);
                    break;
                case 0x16:
                    Map<Instant, Byte> pulseEntries = parseReportBlock(characteristicValue);
                    LOG.info("pulse {}", pulseEntries);
                    break;
                default:
                    LOG.warn("unknown type {}", characteristicValue[0]);
                    /*32 12 0xf6 3 19*/
            }
        } else {
            LOG.warn("other characteristic {}", characteristicUUID);
        }
        return false;
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
