/*  Copyright (C) 2025 Daniel Giritzer, MSc (giri@nwrk.biz)
    Copyright (C) 2025 De_Coder (de_coder@posteo.de)

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.keepfit;


import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.SharedPreferences;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.TimeZone;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.activities.CameraActivity;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventCameraRemote;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventMusicControl;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventFindPhone;
import nodomain.freeyourgadget.gadgetbridge.devices.keepfit.KeepFitConstants;
import nodomain.freeyourgadget.gadgetbridge.devices.keepfit.samples.KeepFitActivitySampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.keepfit.samples.KeepFitHeartRateSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.keepfit.samples.KeepFitSpo2SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.keepfit.samples.KeepFitStressSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.keepfit.samples.KeepFitBloodPressureSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.KeepFitActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.KeepFitHeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.KeepFitSpo2Sample;
import nodomain.freeyourgadget.gadgetbridge.entities.KeepFitStressSample;
import nodomain.freeyourgadget.gadgetbridge.entities.KeepFitBloodPressureSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService;
import nodomain.freeyourgadget.gadgetbridge.model.GenericItem;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.weather.Weather;
import nodomain.freeyourgadget.gadgetbridge.model.weather.WeatherMapper;
import nodomain.freeyourgadget.gadgetbridge.model.WeatherSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationType;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.BLETypeConversions;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser;

public class KeepFitDeviceSupport extends AbstractBTLESingleDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(KeepFitDeviceSupport.class);
    private final Handler backgroundTasksHandler = new Handler(Looper.getMainLooper());
    boolean active = false;
    long timezoneOffset;

    // needed to detect the stop condition for the heart-rate report
    private int hrSyncTotTimes;
    private int hrSyncCurTimes;
    private int hrSyncLastTotTimes;

    // used to determine which day to sync
    private int syncDay;
    private final int daysToSync = 2;

    // spo2 task
    private ScheduledExecutorService spo2Schedulder;
    private ScheduledFuture<?> spo2Future;
    private final int spoIntervalHours = 1;

    public class ReportBlockResult {
        public final Map<Instant, Byte> first;
        public final boolean second;

        public ReportBlockResult(Map<Instant, Byte> first, boolean second) {
            this.first = first;
            this.second = second;
        }
    };

    public class SportReportEntry {
        public final int steps;
        public final int type;
        public final Instant time;

        public SportReportEntry(int steps, int type, Instant time) {
            this.steps = steps;
            this.type = type;
            this.time = time;
        }
    };

    public class SportReportBlockResult {
        public final List<SportReportEntry> first;
        public final boolean second;

        public SportReportBlockResult(List<SportReportEntry> first, boolean second) {
            this.first = first;
            this.second = second;
        }
    };

    public KeepFitDeviceSupport() {
        super(LOG);
        addSupportedService(KeepFitConstants.SUOTA_SERVICE);
        addSupportedService(KeepFitConstants.COMMUNICATION_SERVICE);

        timezoneOffset = TimeZone.getDefault().getOffset(System.currentTimeMillis());

        // to fetch spo data on a fixed rate
        spo2Schedulder = Executors.newSingleThreadScheduledExecutor();
    }

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        // mark the device as initializing
        builder.setDeviceState(GBDevice.State.INITIALIZING);

        getDevice().setFirmwareVersion("N/A");
        getDevice().setFirmwareVersion2("N/A");

        // mark the device as initialized
        builder.setDeviceState(GBDevice.State.INITIALIZED);
        builder.notify(KeepFitConstants.CHARACTERISTIC_WRITE, true);
        builder.notify(KeepFitConstants.CHARACTERISTIC_READ, true);

        // Delay initialization with 2 seconds to give the ring time to settle
        backgroundTasksHandler.removeCallbacksAndMessages(null);
        backgroundTasksHandler.postDelayed(this::postConnectInit, 2000);

        return builder;

    }

    private void postConnectInit() {
        setAppId();
        setDateTime();
        setHourFormat();
        setLanguage();
        getDeviceInfo();
        getBatteryState();
        sendUserInfo();
        setWeatherData();
    }

    /**
     * @brief Helper function to send user info to the device (used during init and when user info changes).
     */
    private void sendUserInfo() {
        ActivityUser activityUser = new ActivityUser();
        setStepGoal(activityUser.getStepsGoal());
        setUserInfo(activityUser.getGender() != ActivityUser.GENDER_MALE, 
                    (byte)activityUser.getAge(), 
                    (byte)activityUser.getHeightCm(), 
                    (byte)activityUser.getWeightKg(), (byte)0);
    }

    private void getDeviceInfo() {
        send("getDeviceInfo", KeepFitConstants.CMD_GET_DEVICE_INFO, new byte[]{});
    }

    /**
     * Sets the hour format to 24:00
     */
    private void setHourFormat() {
        boolean is24hFormat = getDevicePrefs().getTimeFormat().equals(DeviceSettingsPreferenceConst.PREF_TIMEFORMAT_24H);
        send("setHourFormat", KeepFitConstants.CMD_SET_HOUR_FORMAT, new byte[]{(byte) (is24hFormat ? 0 : 1)});
    }

    /**
     * Request the battery state.
     */
    private void getBatteryState() {
        send("getBatteryState", KeepFitConstants.CMD_GET_BATTERY, new byte[]{});
    }

    private void setDateTime() {
        // Get current local date and time
        Instant now = Instant.now();
        long nowLocal = (now.toEpochMilli() + timezoneOffset) / 1000;
        send("setDateTime", KeepFitConstants.CMD_SET_TIME, BLETypeConversions.fromUint32((int)nowLocal));
    }

    /**
     * Enable/Disable blood oxygen measurement.
     * (not quite sure what this api call does)
     * @param enabled true to enable false otherwise
     */
    private void setBloodOxygenEnabled(boolean enabled) {
        send("setBloodOxygenEnabled", KeepFitConstants.CMD_TOGGLE_SPO2, new byte[]{(byte) (enabled ? 1 : 0)});
    }

    /**
     * Enable/Disable blood pressure measurement. 
     * (acts as a trigger, result also contains oxygen value)
     * @param enabled true to trigger, false does not seem to have any effect.
     */
    private void setBloodPressureEnabled(boolean enabled) {
        send("setBloodPressureEnabled", KeepFitConstants.CMD_TOGGLE_BLOOD_PRESSURE, new byte[]{(byte) (enabled ? 1 : 0)});
    }


    /**
     * Allows setting the step goal for a day.
     * @param steps Number of steps to set
     */
    private void setStepGoal(int steps) {
        byte[] byteArray = {
            (byte)(steps & 0XFF),
            (byte)((steps >> 8) & 0XFF),
            (byte)((steps >> 16) & 0XFF),
            (byte)((steps >> 24) & 0XFF)
        };
        send("setStepGoal", KeepFitConstants.CMD_SET_STEP_GOAL, byteArray);
    }

    /**
     * Set the device as lost (device might vibrate/make sound/blink)
     * @param lost true if lost, false otherwise
     */
    private void setLost(boolean lost) {
        byte[] byteArray = {
            lost ? (byte) 1 : 0
        };
        send("setLost", KeepFitConstants.CMD_TRIGGER_LOST, byteArray);
    }

    
    private void sendVibrationSignal(byte time) {
        if (time < 0 || time > 10) {
            time = 10;
        }
        byte[] byteArray = {
            (byte)(time)
        };
        send("sendVibrationSignal", KeepFitConstants.CMD_SEND_VIBRATION_SIGNAL, byteArray);      
    }

    /**
     * Allows setting user specific info
     * @param gender 0 male, 1 female
     * @param age Age of the user
     * @param height height of the user
     * @param weight weight of the user
     * @param unit 0 metric, 1 british-traditional
     */
    private void setUserInfo(boolean gender, byte age, byte height, byte weight, byte unit) {
        byte[] byteArray = {
            (gender ? (byte)(age | 128) : (byte)(0 | age)),
            (byte)(height),
            (byte)(weight),
            (byte)(unit)
        };

        send("setUserInfo", KeepFitConstants.CMD_SET_USER_INFO, byteArray);
    }

    private void setHeartRateArea(boolean enable, byte from, byte to) {
        byte[] byteArray = {
            enable ? (byte) 1 : 0,
            (byte)(from),
            (byte)(to)
        };
        send("setHeartRateArea", KeepFitConstants.CMD_SET_HEART_RATE_AREA, byteArray);
    }

    /**
     * Allows enabling/disabling the auto heart rate measurement 
     * @param enable If true this option will be enabled.
     * @param startHour Start-Hour of a day for the time period this shall be enabled. 
     * @param startMinute Start-Minutes of a day for the time period this shall be enabled. 
     * @param endHour End-Hour of a day for the time period this shall be enabled. 
     * @param endMinute End-Minute of a day for the time period this shall be enabled. 
     * @param interval The interval (in minutes) inbetween 'startHour:startMinute - endHour:endMinute' automatic measurement shall take place.
     * @param duration The duration (in minutes) of the measurement.
     */
    private void setAutoHeartMode(boolean enable, int startHour, int startMinute, int endHour, int endMinute, int interval, int duration) {
        if (duration >= interval) { // sanity check
            if (interval > 1) {
                duration = interval - 1;
            } else {
                // defaults
                interval = 15;
                duration = 2;
            }
        }

        byte[] byteArray = {
            (byte)startHour,
            (byte)startMinute,
            (byte)endHour,
            (byte)endMinute,
            enable ? (byte) 1 : 0,
            (byte)interval,
            (byte)duration
        };

        send("setAutoHeartMode", KeepFitConstants.CMD_SET_AUTO_HEART_MODE, byteArray);
    }

    private void setSleepTime(int startNoonHour, int startNoonMinute, int endNoonHour, int endNoonMinute, 
                              int startNightHour, int startNightMinute, int endNightHour, int endNightMinute) {
        byte[] byteArray = {
            (byte)startNoonHour,
            (byte)startNoonMinute,
            (byte)endNoonHour,
            (byte)endNoonMinute,
            (byte)startNightHour,
            (byte)startNightMinute,
            (byte)endNightHour,
            (byte)endNightMinute,
        };

        send("setSleepTime", KeepFitConstants.CMD_SET_SLEEP_TIME, byteArray);
    }

    /**
     * Sets the sedentary reminder (idle time).
     * @param period The period in seconds after which the device should remind the user to move (disables reminder if 0).
     * @param startHour Start-Hour of a day for the time period this shall be enabled.
     * @param startMinute Start-Minutes of a day for the time period this shall be enabled.
     * @param endHour End-Hour of a day for the time period this shall be enabled.
     * @param endMinute End-Minute of a day for the time period this shall be enabled.
     */
    private void setSedentaryReminder(int period, int startHour, int startMinute, int endHour, int endMinute) {

        byte[] byteArray = {
            (byte)(period & 0XFF),
            (byte)((period >> 8) & 0XFF),
            (byte)((period >> 16) & 0XFF),
            (byte)((period >> 24) & 0XFF),
            (byte)startHour,
            (byte)startMinute,
            (byte)endHour,
            (byte)endMinute,
        };

        send("setSedentaryReminder", KeepFitConstants.CMD_SET_IDLE_TIME, byteArray);
    }

    /**
     * Allows setting the custom alarms (medicine, drinking, custom)
     * @param period The period in seconds after which the device should remind the user to move (disables reminder if 0).
     * @param startHour Start-Hour of a day for the time period this shall be enabled.
     * @param startMinute Start-Minutes of a day for the time period this shall be enabled.
     * @param endHour End-Hour of a day for the time period this shall be enabled.
     * @param endMinute End-Minute of a day for the time period this shall be enabled.
     * @param custom1 1 for drinking, 2 for medicine
     * @param custom2 1 for drinking, 2 for medicine
     */
    private void setGenericReminder(int period, int startHour, int startMinute, int endHour, int endMinute, int custom1, int custom2) {
        byte[] byteArray = {
            (byte)(period & 0XFF),
            (byte)((period >> 8) & 0XFF),
            (byte)((period >> 16) & 0XFF),
            (byte)((period >> 24) & 0XFF),
            (byte)startHour,
            (byte)startMinute,
            (byte)endHour,
            (byte)endMinute,
            (byte)custom1,
            (byte)custom2,
        };

        send("setGenericReminder", KeepFitConstants.CMD_SET_REMINDER, byteArray);
    }

    /**
     * Allows setting the menstrual cycle.
     * @param enable Enable menstrual cycle
     * @param year Start year
     * @param month Start month
     * @param day Start day
     * @param length duration of menstrual cycle
     * @param period period of menstrual cycle
     */
    private void setMenstrualCycle(boolean enable, int year, int month, int day, int lenght, int period) {
        byte[] byteArray = {
            (byte)(year & 0XFF),
            (byte)((year >> 8) & 0XFF),
            (byte)month,
            (byte)day,
            (byte)lenght,
            (byte)period,
            enable ? (byte) 1 : 0,
        };

        send("setGenericReminder", KeepFitConstants.CMD_SET_MENSTRUAL_CYCLE, byteArray);
    }

    /**
     * Enables or disables the "Do Not Disturb" mode.
     * @param enableBackLight If true the backlight-on-rise will be enabled during the quiet time.
     * @param enableVibation If true the vibration will be enabled during the quiet time.
     * @param enableQuiet If true the quiet mode will be enabled during the quiet time.
     * @param startHour Start-Hour of a day for the time period this shall be enabled.
     * @param startMinute Start-Minutes of a day for the time period this shall be enabled.
     * @param endHour End-Hour of a day for the time period this shall be enabled.
     * @param endMinute End-Minute of a day for the time period this shall be enabled.
     */
    private void setDoNotDisturb(boolean enableBackLight, boolean enableVibation, boolean enableQuiet, 
                              int startHour, int startMinute, int endHour, int endMinute) {

        byte[] byteArray = {
            (byte)(enableBackLight ? 1 : 0),
            (byte)(enableVibation ? 1 : 0),
            (byte)0,
            (byte)0,
            (byte)(enableQuiet ? 1 : 0),
            (byte)startHour,
            (byte)startMinute,
            (byte)endHour,
            (byte)endMinute,
            (byte)0, // enable calling (flag)
            (byte)0, // short video mode (smart ring instagram swipe, flag)
            (byte)0, // wear mode
            (byte)0, // light degree

        };

        send("setDoNotDisturb", KeepFitConstants.CMD_SET_DEVICE_INFO, byteArray);
    }

    /**
     * Enables or disables remote camera shutter mode.
     * @param enable If true this option will be enabled.
     */
    public void setCameraModeEnabled(boolean enable) {
        send("setCameraModeEnabled", KeepFitConstants.CMD_SET_CAMERA_MODE, new byte[]{enable ? (byte) 1 : 0});
    }

    private void setLiveHeartRateEnabled(boolean enabled, int b, int activitySlot) { // slot 1-5 for continues meass
        if (enabled) {
            byte[] data = ByteBuffer.allocate(5)
                    .put(BLETypeConversions.fromUint32(b))
                    .put((byte) activitySlot)
                    .array();
            send("setLiveHeartRateEnabled", KeepFitConstants.CMD_SET_LIVE_HEART_RATE_ENABLED, data);
        } else {
            send("setLiveHeartRateDisabled", KeepFitConstants.CMD_SET_LIVE_HEART_RATE_DISABLED, new byte[]{0, 0, 0, 0, (byte) activitySlot});
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
        send("setLanguage", KeepFitConstants.CMD_SET_LANG, Arrays.copyOf(bytes, 19));
    }

    /**
     * Fetches the activity report (step count, sleep time and heart rate).
     * @param days Number of days to fetch (0-27, where 0 is the data of today).
     */
    private void triggerActivityReportByDays(byte days) {
        if (days < 0) {
            days = 0;
        }
        byte corrDay = 27;
        if (days <= 27) {
            corrDay = days;
        }
        byte[] byteArray = {
            corrDay
        };
        send("triggerActivityReport", KeepFitConstants.CMD_TRIGGER_ACTIVITY_REPORT, byteArray);
    }

    /**
     * @brief Sends the weather data to the device.
     */
    private void setWeatherData() {
        
        final WeatherSpec weatherSpec = Weather.getWeatherSpec();
        if (weatherSpec == null) {
            LOG.warn("No weather found in singleton");
            return;
        }
        
        byte[] timestamp = BLETypeConversions.fromUint32(weatherSpec.getTimestamp());
        byte[] pm25 = BLETypeConversions.fromUint32((int)weatherSpec.getAirQuality().getPm25());
        byte[] aqi = BLETypeConversions.fromUint32((int)weatherSpec.getAirQuality().getAqi());

        byte[] byteArray = {
            timestamp[0],
            timestamp[1],
            timestamp[2],
            timestamp[3],
            0, 0, // daytime weather ?
            0, 0, // evenig weather ?
            (byte) (weatherSpec.getTodayMinTemp() - 273),
            (byte) (weatherSpec.getTodayMaxTemp() - 273),
            (byte) 0, // AirQuality ?
            pm25[0], pm25[1],
            (byte) weatherSpec.getUvIndex(),
            aqi[0], aqi[1],
            (byte) (weatherSpec.getCurrentTemp() - 273)
        };
        send("setWeatherData", KeepFitConstants.CMD_SET_WEATHER_DATA, byteArray);
    }

    private void triggerActivityReport() {
        GB.updateTransferNotification(getContext().getString(R.string.busy_task_fetch_activity_data), "", true, 0, getContext());
        getDevice().setBusyTask(R.string.busy_task_fetch_activity_data, getContext());
        getDevice().sendDeviceUpdateIntent(getContext());
        this.syncDay = 0;
        triggerActivityReportByDays((byte)this.syncDay);
    }

    /**
     * Fetches the hard rate report.
     * @param days Number of days to fetch (0-1).
     */
    private void triggerHeartRateReportByDays(byte days) {
        if (days < 0) {
            days = 0;
        }
        if (days > 1) {
            days = 1;
        }
        byte[] byteArray = {
            (byte)(days)
        };
        send("triggerHeartRateReport", KeepFitConstants.CMD_TRIGGER_HEART_RATE_REPORT, byteArray);
    }

    /**
     * Sends a notification to be displayed to the device.
     * @param id Notification id (unique four digit string 0000 - 9999)
     * @param type Type of notification (use one of the NOTIFICATION_* constants)
     * @param title Notification title
     * @param content Notification content
     */
    public void sendNotification(String id, int type, String title, String content) {
        int length = content.getBytes(StandardCharsets.UTF_8).length;

        // calculate how many protocol messages are needed
        int packLen = 2;
        if (length % 17 != 0) {
            packLen = length / 17 + 3;
        } else if (length == 0) {
            packLen = 3;
        } else {
            packLen = length / 17 + 2;
        }

        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        for (int i = 1; i <= packLen; i++) {
            byte[] bArr = new byte[19];
            bArr[0] = (byte) packLen; // number of packets
            bArr[1] = (byte) i; // packet number
            if (i == 1) { // message id + type
                bArr[2] = 0;
                bArr[3] = (byte) type;
                byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
                System.arraycopy(idBytes, 0, bArr, 4, idBytes.length);
            } else if (i == 2) { // message title
                byte[] titleBytes = title.getBytes(StandardCharsets.UTF_8);
                System.arraycopy(titleBytes, 0, bArr, 2, Math.min(titleBytes.length, 17));
            } else if (length > 0) { // message body
                int contentBegin = (i - 3) * 17;
                int contentOffset = length - contentBegin;
                System.arraycopy(contentBytes, contentBegin, bArr, 2, (contentOffset > 17 ? contentBegin + 17 : contentBegin + contentOffset) - contentBegin);
            }
            send("sendNotification", KeepFitConstants.CMD_ALERT_NOTIFICATION, bArr);
        }
    }

    private void triggerSportReportByDay(byte day) {
        byte[] byteArray = {
            (byte)(day)
        };
        send("triggerSportReport", KeepFitConstants.CMD_TRIGGER_SPORT_REPORT, byteArray);
    }

    private void getCurrentSportsData() {
        send("getCurrentSportsData", KeepFitConstants.CMD_GET_CUR_SPORT_DATA, new byte[]{});      
    }

    public String getPhoneUniqueId() {
        final Prefs prefs = getDevicePrefs();
        final String uuString = prefs.getString(DeviceSettingsPreferenceConst.PREF_PHONE_UNIQUE_ID, "");
        if (!uuString.isEmpty()) {
            return uuString;
        }
        String uuid = UUID.randomUUID().toString();
        SharedPreferences.Editor editor = prefs.getPreferences().edit();
        editor.putString(DeviceSettingsPreferenceConst.PREF_PHONE_UNIQUE_ID, uuid);
        editor.apply(); 
        return uuid;
    }

    private void setAppId() {
        String uuid = getPhoneUniqueId();
        byte[] uuidBytes = Arrays.copyOf(uuid.getBytes(StandardCharsets.UTF_8), 19);
        send("setAppId", KeepFitConstants.CMD_SET_APP_ID, uuidBytes);
    }

    private void send(String taskName, byte cmd, byte[] data) {
        TransactionBuilder builder = createTransactionBuilder(taskName);
        if (data.length > 19) {
            LOG.warn("package for task {} to long: size {} > 19", taskName, data.length);
            return;
        }
        byte[] contents = ByteBuffer.allocate(20)
                .put(cmd)
                .put(data)
                .array();

        builder.write(KeepFitConstants.CHARACTERISTIC_WRITE, contents);
        builder.queue();
    }

    private Map<Instant, Integer> parseHrReportBlock(byte[] data) {
        Map<Instant, Integer> entries = new HashMap<>();
        Instant timestamp = Instant.ofEpochSecond(BLETypeConversions.toUint32(data, 2) - (((long) timezoneOffset) / 1000));

        int round = Math.round((float)(data[8] + data[9] + data[10] + data[11] + data[12] + data[13]) / 6.0f);
        entries.put(timestamp, round);

        timestamp = timestamp.plus(1, ChronoUnit.MINUTES);
        round = Math.round((float) (data[14] + data[15] + data[16] + data[17] + data[18] + data[19]) / 6.0f);
        entries.put(timestamp, round);
        return entries;
    }

    private ReportBlockResult parseReportBlock(byte[] data) {
        Map<Instant, Byte> entries = new HashMap<>();
        Instant timestamp = Instant.ofEpochSecond(BLETypeConversions.toUint32(data, 1) - (((long) timezoneOffset) / 1000));

        // stop condition
        ZoneId zone = ZoneId.systemDefault();
        LocalTime stopTime = timestamp.atZone(zone).toLocalTime();
        boolean is2345 = stopTime.getHour() == 23 && stopTime.getMinute() == 45;
        if(is2345) {
            getDevice().unsetBusyTask();
            getDevice().sendDeviceUpdateIntent(getContext());
            GB.updateTransferNotification(null, "", false, 100, getContext());
        }

        for (int i = 5; i < 20; i++) {
            entries.put(timestamp, data[i]);
            timestamp = timestamp.plus(1, ChronoUnit.MINUTES);
        }
        return new ReportBlockResult(entries, is2345);
    }

    private SportReportBlockResult parseSportBlock(byte[] data) {
        List<SportReportEntry> entries = new ArrayList<>();
        Instant timestamp = Instant.ofEpochSecond(BLETypeConversions.toUint32(data, 1) - (((long) timezoneOffset) / 1000));

        // stop condition
        ZoneId zone = ZoneId.systemDefault();
        LocalTime stopTime = timestamp.atZone(zone).toLocalTime();
        boolean is2345 = stopTime.getHour() == 23 && stopTime.getMinute() == 45;
        if(is2345) {
            getDevice().unsetBusyTask();
            getDevice().sendDeviceUpdateIntent(getContext());
            GB.updateTransferNotification(null, "", false, 100, getContext());
        }

        for (int i = 0; i < 6; i++) {
            int type = ((int)data[i * 2 + 5]) & 15;
            int steps = (((int)data[i * 2 + 6]) << 4) + (((int)data[i * 2 + 5]) >> 4);
            entries.add(new SportReportEntry(steps, type, timestamp));
            timestamp = timestamp.plus(1, ChronoUnit.MINUTES);
        }
        return new SportReportBlockResult(entries, is2345);
    }

    @Override
    public void onEnableRealtimeSteps(boolean enable) {
        super.onEnableRealtimeSteps(enable);
    }

    @Override
    public void onFindDevice(boolean start) {
        setLost(start); // dedicated set lost call
        if(start) {
            // some devices (e.g. smart rings) do not support the setLost opcode.
            // they will blink on the sendVibrationSignal opcode instead.
            sendVibrationSignal((byte)10);
        }
    }

    @Override
    public void onReset(int flags) {
        super.onReset(flags);
    }

    @Override
    public void onSetHeartRateMeasurementInterval(int seconds) {
        // always enable the whole day:
        int startHour = 0;
        int startMinutes = 0;
        int endHour = 23;
        int endMinutes = 59;

        int duration = 2;

        setAutoHeartMode(seconds > 0 ? true : false, startHour, startMinutes, endHour, endMinutes, seconds / 60, duration);
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
        triggerActivityReport(); // fetches step data and sleep data
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

    @Override
    public void onSetTime() {
        setDateTime();
    }

    @Override
    public void onSendConfiguration(String config) {

        final Prefs prefs = getDevicePrefs();
    

        // spo2 interval
        if(config.equals("") || config.equals(DeviceSettingsPreferenceConst.PREF_SPO2_ALL_DAY_MONITORING))
        {
            final boolean spo2Enabled = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_SPO2_ALL_DAY_MONITORING, false);
            if(spo2Enabled) {
                if(spo2Future == null) { // check if running
                    spo2Future = spo2Schedulder.scheduleWithFixedDelay(new Runnable() {
                        @Override
                        public void run() {
                            setBloodPressureEnabled(true);
                        }
                    }, 0, spoIntervalHours, TimeUnit.HOURS); // fetch spo data every spoIntervalHours
                }
            } else {
                if(spo2Future != null) {
                    spo2Future.cancel(true);
                    spo2Future = null;
                }
                setBloodPressureEnabled(false); // this most probably does nothing...
            }
        }

        // do not disturb & notifications
        else if(
            config.equals(DeviceSettingsPreferenceConst.PREF_VIBRATION_ENABLE) ||
            config.equals(DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_NOAUTO) ||
            config.equals(DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_NOAUTO_START) ||
            config.equals(DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_NOAUTO_END)
        )
        { 
            final boolean vibrate = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_VIBRATION_ENABLE, false);
            final boolean doNotDisturb = prefs.getString(DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_NOAUTO, "off").equals("scheduled");
            final String startStr = prefs.getString(DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_NOAUTO_START, "08:00");
            final String endStr = prefs.getString(DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_NOAUTO_END, "16:00");
            Calendar startCalendar = GregorianCalendar.getInstance();
            Calendar endCalendar = GregorianCalendar.getInstance();
            DateFormat df = new SimpleDateFormat("HH:mm");

            try {
                startCalendar.setTime(df.parse(startStr));
                endCalendar.setTime(df.parse(endStr));
            } catch (ParseException e) {
                LOG.debug("settings error: " + e);
            }

            setDoNotDisturb(true, vibrate, doNotDisturb, 
                            startCalendar.get(Calendar.HOUR_OF_DAY), startCalendar.get(Calendar.MINUTE), 
                            endCalendar.get(Calendar.HOUR_OF_DAY), endCalendar.get(Calendar.MINUTE));
        }

        // sleep interval
        else if(
            config.equals(DeviceSettingsPreferenceConst.PREF_SLEEP_TIME) ||
            config.equals(DeviceSettingsPreferenceConst.PREF_SLEEP_TIME_START) ||
            config.equals(DeviceSettingsPreferenceConst.PREF_SLEEP_TIME_END)
        )
        {
            final boolean sleepTimeEnaled = prefs.getString(DeviceSettingsPreferenceConst.PREF_SLEEP_TIME, "off").equals("scheduled");
            final String startStr = prefs.getString(DeviceSettingsPreferenceConst.PREF_SLEEP_TIME_START, "22:00");
            final String endStr = prefs.getString(DeviceSettingsPreferenceConst.PREF_SLEEP_TIME_END, "08:00");
            Calendar startCalendar = GregorianCalendar.getInstance();
            Calendar endCalendar = GregorianCalendar.getInstance();
            DateFormat df = new SimpleDateFormat("HH:mm");

            try {
                startCalendar.setTime(df.parse(startStr));
                endCalendar.setTime(df.parse(endStr));
            } catch (ParseException e) {
                LOG.debug("settings error: " + e);
            }

            if(sleepTimeEnaled) { // these devices seem to support two intervals (noon and night), we use only the night one
                setSleepTime(0,0,0,0, startCalendar.get(Calendar.HOUR_OF_DAY), startCalendar.get(Calendar.MINUTE), 
                                    endCalendar.get(Calendar.HOUR_OF_DAY), endCalendar.get(Calendar.MINUTE));
            } else {
                setSleepTime(0,0,0,0,0,0,0,0);
            }
        }

        // sedentary reminder
        else if(
            config.equals(DeviceSettingsPreferenceConst.PREF_INACTIVITY_ENABLE) ||
            config.equals(DeviceSettingsPreferenceConst.PREF_INACTIVITY_THRESHOLD_EXTENDED) ||
            config.equals(DeviceSettingsPreferenceConst.PREF_INACTIVITY_START) ||
            config.equals(DeviceSettingsPreferenceConst.PREF_INACTIVITY_END)
        )
        { 
            final boolean sedentaryReminderEnaled = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_INACTIVITY_ENABLE, false);
            final int sedentaryTimeout = prefs.getInt(DeviceSettingsPreferenceConst.PREF_INACTIVITY_THRESHOLD_EXTENDED, 4);
            final String startStr = prefs.getString(DeviceSettingsPreferenceConst.PREF_INACTIVITY_START, "08:00");
            final String endStr = prefs.getString(DeviceSettingsPreferenceConst.PREF_INACTIVITY_END, "16:00");
            Calendar startCalendar = GregorianCalendar.getInstance();
            Calendar endCalendar = GregorianCalendar.getInstance();
            DateFormat df = new SimpleDateFormat("HH:mm");

            try {
                startCalendar.setTime(df.parse(startStr));
                endCalendar.setTime(df.parse(endStr));
            } catch (ParseException e) {
                LOG.debug("settings error: " + e);
            }

            if(sedentaryReminderEnaled) {
                setSedentaryReminder(sedentaryTimeout * 60, startCalendar.get(Calendar.HOUR_OF_DAY), startCalendar.get(Calendar.MINUTE), 
                                    endCalendar.get(Calendar.HOUR_OF_DAY), endCalendar.get(Calendar.MINUTE));
            } else {
                setSedentaryReminder(0,0,0,0,0);
            }
        }

        // drinking reminder
        else if(
            config.equals(DeviceSettingsPreferenceConst.PREF_HYDRATION_SWITCH) ||
            config.equals(DeviceSettingsPreferenceConst.PREF_HYDRATION_PERIOD) ||
            config.equals(DeviceSettingsPreferenceConst.PREF_HYDRATION_DND) ||
            config.equals(DeviceSettingsPreferenceConst.PREF_HYDRATION_DND_START) ||
            config.equals(DeviceSettingsPreferenceConst.PREF_HYDRATION_DND_END)
        )
        { 
            final boolean hydrationReminderEnabled = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_HYDRATION_SWITCH, false);
            final int hydrationTimeout = prefs.getInt(DeviceSettingsPreferenceConst.PREF_HYDRATION_PERIOD, 60);

            final boolean reminderActiveTime = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_HYDRATION_DND, false);
            final String startStr = prefs.getString(DeviceSettingsPreferenceConst.PREF_HYDRATION_DND_START, "08:00");
            final String endStr = prefs.getString(DeviceSettingsPreferenceConst.PREF_HYDRATION_DND_END, "16:00");
            Calendar startCalendar = GregorianCalendar.getInstance();
            Calendar endCalendar = GregorianCalendar.getInstance();
            DateFormat df = new SimpleDateFormat("HH:mm");

            try {
                startCalendar.setTime(df.parse(startStr));
                endCalendar.setTime(df.parse(endStr));
            } catch (ParseException e) {
                LOG.debug("settings error: " + e);
            }

            if(hydrationReminderEnabled) {
                if(reminderActiveTime) {
                    setGenericReminder(hydrationTimeout * 60, endCalendar.get(Calendar.HOUR_OF_DAY), endCalendar.get(Calendar.MINUTE), 
                                        startCalendar.get(Calendar.HOUR_OF_DAY), startCalendar.get(Calendar.MINUTE), 1, 1);
                } else { // all day
                    setGenericReminder(hydrationTimeout * 60, 0, 0, 
                                       23, 59, 1, 1);
                }
            } else {
                setGenericReminder(0,0,0,0,0, 1, 1);
            }
        }


        // remote camera
        else if(
            config.equals(DeviceSettingsPreferenceConst.PREF_CAMERA_REMOTE)
        )
        {
            final boolean cameraModeEnabled = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_CAMERA_REMOTE, false);
            setCameraModeEnabled(cameraModeEnabled);
        }

        // user info (step goal, height, weight, etc.)
        sendUserInfo();
    }

    @Override
    public boolean onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] characteristicValue) {
        super.onCharacteristicChanged(gatt, characteristic, characteristicValue);

        UUID characteristicUUID = characteristic.getUuid();
        if (characteristicUUID.equals(KeepFitConstants.CHARACTERISTIC_READ)) {
            if (characteristicValue.length != 20) {
                LOG.error("unexpected length {}!", characteristicValue.length);
                return false;
            }
            switch (characteristicValue[0]) {
                case KeepFitConstants.CMD_KEEPALIVE_PING:
                    send("sendPing", KeepFitConstants.CMD_KEEPALIVE_PING, new byte[]{});
                    setAppId();
                    break;
                case KeepFitConstants.CMD_GET_BATTERY:
                    evaluateBatteryData(characteristicValue);
                    break;
                case KeepFitConstants.CMD_SET_TIME:
                    LOG.info("time set");
                    break;
                case KeepFitConstants.CMD_SET_HOUR_FORMAT:
                    LOG.info("time format set");
                    break;
                case KeepFitConstants.CMD_SET_LANG:
                    LOG.info("language set");
                    break;
                case KeepFitConstants.CMD_SET_CAMERA_MODE:
                    if(characteristicValue[1] == 1) { // open camera
                        GBDeviceEventCameraRemote openCameraEvent = new GBDeviceEventCameraRemote();
                        openCameraEvent.event = GBDeviceEventCameraRemote.Event.OPEN_CAMERA;
                        evaluateGBDeviceEvent(openCameraEvent);
                    } else { // close camera
                        GBDeviceEventCameraRemote closeCameraEvent = new GBDeviceEventCameraRemote();
                        closeCameraEvent.event = GBDeviceEventCameraRemote.Event.CLOSE_CAMERA;
                        evaluateGBDeviceEvent(closeCameraEvent);
                    }
                    break;
                case KeepFitConstants.CMD_GET_DEVICE_COMMAND: // commands received from the device
                    switch (characteristicValue[1]) {
                        case 1: // find my device
                            LOG.info("Find phone");
                            GBDeviceEventFindPhone deviceEventFindPhone = new GBDeviceEventFindPhone();
                            deviceEventFindPhone.event = GBDeviceEventFindPhone.Event.START;
                            evaluateGBDeviceEvent(deviceEventFindPhone);
                            break;
                        case 2: // snap picture
                            GBDeviceEventCameraRemote takePictureEvent = new GBDeviceEventCameraRemote();
                            takePictureEvent.event = GBDeviceEventCameraRemote.Event.TAKE_PICTURE;
                            evaluateGBDeviceEvent(takePictureEvent);
                            break;
                        case 4: // end phonecall
                            break;
                        case 5: // ? sync weather data ?
                            setWeatherData();
                            break;
                        case 8: // answer phonecall
                            break;
                        case 16: // music pause
                        {
                            GBDeviceEventMusicControl deviceEventMusicControl = new GBDeviceEventMusicControl();
                            deviceEventMusicControl.event = GBDeviceEventMusicControl.Event.PLAYPAUSE;
                            evaluateGBDeviceEvent(deviceEventMusicControl);
                            break;
                        }
                        case 32: // music next
                        {
                            GBDeviceEventMusicControl deviceEventMusicControl = new GBDeviceEventMusicControl();
                            deviceEventMusicControl.event = GBDeviceEventMusicControl.Event.NEXT;
                            evaluateGBDeviceEvent(deviceEventMusicControl);
                            break;
                        }
                        case 64: // music prev
                        {
                            GBDeviceEventMusicControl deviceEventMusicControl = new GBDeviceEventMusicControl();
                            deviceEventMusicControl.event = GBDeviceEventMusicControl.Event.PREVIOUS;
                            evaluateGBDeviceEvent(deviceEventMusicControl);
                            break;
                        }
                        case 65: // open camera 
                            break;
                        case 66: // close camera 
                            break;
                        case 68: // volume up 
                            break;
                        case 69: // volume down
                            break;
                        default:
                            break;
                    }
                    break;
                case KeepFitConstants.CMD_SPORT_REPORT_RESULT_CURRENT:
                    // Instant timestamp = Instant.ofEpochSecond(BLETypeConversions.toUint32(characteristicValue, 1) - (((long) timezoneOffset) / 1000));
                    // int steps = BLETypeConversions.toUint32(characteristicValue, 5);
                    // int runningtime = BLETypeConversions.toUint32(characteristicValue, 9);
                    // int steptime = BLETypeConversions.toUint32(characteristicValue, 13);
                    // todo: anything with this data?
                    break;
                case KeepFitConstants.CMD_GET_CUR_SPORT_DATA: // will be sent on device connect
                    // int step_unix = BLETypeConversions.toUint32(characteristicValue, 1) - (((long) timezoneOffset) / 1000);
                    // int steps = BLETypeConversions.toUint32(characteristicValue, 5);
                    // int distance = BLETypeConversions.toUint32(characteristicValue, 9);
                    // int calories = BLETypeConversions.toUint32(characteristicValue, 13);
                    // // giri: the last three bytes are used for the sleep time

                    // if(steps > 0) {
                    //     try (DBHandler db = GBApplication.acquireDB()) {
                    //         KeepFitActivitySampleProvider sampleProvider = new KeepFitActivitySampleProvider(getDevice(), db.getDaoSession());
                    //         Long userId = DBHelper.getUser(db.getDaoSession()).getId();
                    //         Long deviceId = DBHelper.getDevice(getDevice(), db.getDaoSession()).getId();
                    //         KeepFitActivitySample gbSample = new KeepFitActivitySample();
                    //         gbSample.setDeviceId(deviceId);
                    //         gbSample.setUserId(userId);
                    //         gbSample.setTimestamp(step_unix);
                    //         //gbSample.setSteps(steps); steps are fetched on its own
                    //         gbSample.setDistance(distance);
                    //         gbSample.setCalories(calories);
                    //         sampleProvider.addGBActivitySample(gbSample);
                    //     } catch (Exception e) {
                    //         LOG.error("Error acquiring database for recording stress samples", e);
                    //     }
                    // }
                    break;
                case KeepFitConstants.CMD_RECEIVED_SPO2_DATA:
                    int spo2Value = characteristicValue[1] & 0xff;
                    // giri: not used on the hardware / app I know. There they use the next case
                    break;
                case KeepFitConstants.CMD_TOGGLE_BLOOD_PRESSURE:
                    // ack blood pressure enabled
                    break;
                case KeepFitConstants.CMD_TOGGLE_BLOOD_PRESSURE_DONE:
                    // ack blood pressure disabled/done
                    break;
                case KeepFitConstants.CMD_RECEIVED_SENSOR_DATA:
                    long epochMilli = java.time.Instant.now().toEpochMilli();

                    int heartrate = characteristicValue[1] & 0xff;
                    int systolicPressure = characteristicValue[2] & 0xff;
                    int diastolicpressure = characteristicValue[3] & 0xff;
                    int oxygen = characteristicValue[4] & 0xff;
                    int fatigueValue = characteristicValue[5] & 0xff;

                    if(heartrate > 0) {
                        try (DBHandler db = GBApplication.acquireDB()) {
                            KeepFitHeartRateSampleProvider sampleProvider = new KeepFitHeartRateSampleProvider(getDevice(), db.getDaoSession());
                            Long userId = DBHelper.getUser(db.getDaoSession()).getId();
                            Long deviceId = DBHelper.getDevice(getDevice(), db.getDaoSession()).getId();
                            KeepFitHeartRateSample gbSample = new KeepFitHeartRateSample();
                            gbSample.setDeviceId(deviceId);
                            gbSample.setUserId(userId);
                            gbSample.setTimestamp(epochMilli);
                            gbSample.setHeartRate(heartrate);
                            
                            sampleProvider.addSample(gbSample);
                        } catch (Exception e) {
                            LOG.error("Error acquiring database for recording heart rate samples", e);
                        }
                    }

                    if(oxygen > 0) {
                        try (DBHandler db = GBApplication.acquireDB()) {
                            KeepFitSpo2SampleProvider sampleProvider = new KeepFitSpo2SampleProvider(getDevice(), db.getDaoSession());
                            Long userId = DBHelper.getUser(db.getDaoSession()).getId();
                            Long deviceId = DBHelper.getDevice(getDevice(), db.getDaoSession()).getId();
                            KeepFitSpo2Sample gbSample = new KeepFitSpo2Sample();
                            gbSample.setDeviceId(deviceId);
                            gbSample.setUserId(userId);
                            gbSample.setTimestamp(epochMilli);
                            gbSample.setSpo2(oxygen);
                            sampleProvider.addSample(gbSample);
                        } catch (Exception e) {
                            LOG.error("Error acquiring database for recording SpO2 samples", e);
                        }
                    }

                    if(fatigueValue > 0) { // stress level
                        try (DBHandler db = GBApplication.acquireDB()) {
                            KeepFitStressSampleProvider sampleProvider = new KeepFitStressSampleProvider(getDevice(), db.getDaoSession());
                            Long userId = DBHelper.getUser(db.getDaoSession()).getId();
                            Long deviceId = DBHelper.getDevice(getDevice(), db.getDaoSession()).getId();
                            KeepFitStressSample gbSample = new KeepFitStressSample();
                            gbSample.setDeviceId(deviceId);
                            gbSample.setUserId(userId);
                            gbSample.setTimestamp(epochMilli);
                            gbSample.setStress(fatigueValue);
                            sampleProvider.addSample(gbSample);
                        } catch (Exception e) {
                            LOG.error("Error acquiring database for recording stress samples", e);
                        }
                    }

                    if(systolicPressure > 0 && diastolicpressure > 0) {
                        try (DBHandler dbHandler = GBApplication.acquireDB()) {
                            KeepFitBloodPressureSampleProvider sampleProvider = new KeepFitBloodPressureSampleProvider(getDevice(), dbHandler.getDaoSession());
                            Long userId = DBHelper.getUser(dbHandler.getDaoSession()).getId();
                            Long deviceId = DBHelper.getDevice(getDevice(), dbHandler.getDaoSession()).getId();

                            KeepFitBloodPressureSample sample = new KeepFitBloodPressureSample();
                            sample.setTimestamp(epochMilli);
                            sample.setBpSystolic(systolicPressure);
                            sample.setBpDiastolic(diastolicpressure);
                            sample.setDeviceId(deviceId);
                            sample.setUserId(userId);

                            sampleProvider.addSample(sample);
                        } catch (Exception e) {
                            LOG.error("Error acquiring database for recording blood pressure samples", e);
                        }
                    }
                    break;
                case KeepFitConstants.CMD_SET_LIVE_HEART_RATE_ENABLED:
                    long unixtime = BLETypeConversions.toUint32(characteristicValue, 1) - (((long) timezoneOffset) / 1000);
                    if (unixtime == 0) {
                        LOG.info("pulse: measurement error");
                        break;
                    }
                    Instant time = Instant.ofEpochSecond(unixtime);
                    int pulse = characteristicValue[5] & 0xff;
                    int sleepStatus = characteristicValue[6] & 0xff; // save me maybe?

                    if(pulse <= 0) {
                        break; // wait for values
                    }

                    LOG.info("pulse: {} {}", time, pulse);
                    try (DBHandler db = GBApplication.acquireDB()) {
                        KeepFitHeartRateSampleProvider sampleProvider = new KeepFitHeartRateSampleProvider(getDevice(), db.getDaoSession());
                        Long userId = DBHelper.getUser(db.getDaoSession()).getId();
                        Long deviceId = DBHelper.getDevice(getDevice(), db.getDaoSession()).getId();
                        KeepFitHeartRateSample gbSample = new KeepFitHeartRateSample();
                        gbSample.setDeviceId(deviceId);
                        gbSample.setUserId(userId);
                        gbSample.setTimestamp(time.toEpochMilli());
                        gbSample.setHeartRate(pulse);
                        
                        sampleProvider.addSample(gbSample);
                        // Send local intent with sample for listeners like the heart rate dialog
                        Intent liveIntent = new Intent(DeviceService.ACTION_REALTIME_SAMPLES);
                        liveIntent.putExtra(GBDevice.EXTRA_DEVICE, getDevice());
                        liveIntent.putExtra(DeviceService.EXTRA_REALTIME_SAMPLE, gbSample);
                        LocalBroadcastManager.getInstance(getContext())
                                .sendBroadcast(liveIntent);
                    } catch (Exception e) {
                        LOG.error("Error acquiring database for recording heart rate samples", e);
                    }
                    break;
                case KeepFitConstants.CMD_TRIGGER_ACTIVITY_REPORT:
                    GB.updateTransferNotification(getContext().getString(R.string.busy_task_fetch_activity_data), "", true, 0, getContext());
                    getDevice().setBusyTask(R.string.busy_task_fetch_activity_data, getContext());
                    getDevice().sendDeviceUpdateIntent(getContext());

                    ReportBlockResult stepEntries = parseReportBlock(characteristicValue);
                    LOG.info("steps {}", stepEntries);
                    safeStepData(stepEntries.first);

                    if(stepEntries.second) { // end condition (sleep data and step data fetching finished)
                        triggerSportReportByDay((byte)this.syncDay);
                    }
                    break;
                case KeepFitConstants.CMD_TRIGGER_SPORT_REPORT:
                    SportReportBlockResult sportEntries = parseSportBlock(characteristicValue);
                    safeSportData(sportEntries.first);

                    if(sportEntries.second) { // end condition (sport data fetching finished)
                        triggerHeartRateReportByDays((byte)this.syncDay);
                    }
                    break;
                case KeepFitConstants.CMD_TRIGGER_ACTIVITY_REPORT_SLEEP_RESULT:
                    GB.updateTransferNotification(getContext().getString(R.string.busy_task_fetch_sleep_data), "", true, 0, getContext());
                    getDevice().setBusyTask(R.string.busy_task_fetch_sleep_data, getContext());
                    getDevice().sendDeviceUpdateIntent(getContext());

                    ReportBlockResult sleepEntries = parseReportBlock(characteristicValue);
                    LOG.info("sleep {}", sleepEntries);
                    safeSleepData(sleepEntries.first);
                    break;
                case KeepFitConstants.CMD_TRIGGER_HEART_RATE_REPORT:
                    GB.updateTransferNotification(getContext().getString(R.string.busy_task_fetch_hr_data), "", true, 0, getContext());
                    getDevice().setBusyTask(R.string.busy_task_fetch_hr_data, getContext());
                    getDevice().sendDeviceUpdateIntent(getContext());

                    if(characteristicValue[1] == (byte)0xff) { // fetching data finished
                        getDevice().unsetBusyTask();
                        getDevice().sendDeviceUpdateIntent(getContext());
                        GB.updateTransferNotification(null, "", false, 100, getContext());
                        break;
                    }

                    if(characteristicValue[1] == (byte)0xf0) {
                        this.hrSyncTotTimes = ((characteristicValue[7] & 0xff) << 8) | (characteristicValue[6] & 0xff);
                        break;
                    }

                    if(characteristicValue[1] == (byte)0xaa) {
                        this.hrSyncCurTimes = characteristicValue[2];
                        this.hrSyncLastTotTimes = ((characteristicValue[8] & 0xff) << 8) | (characteristicValue[7] & 0xff);
                        break;
                    }

                    if(characteristicValue[1] == (byte)0xa0) {
                        Map<Instant, Integer> hrEntries = parseHrReportBlock(characteristicValue);
                        LOG.info("pulse {}", hrEntries);
                        safeHrData(hrEntries);

                        int done = ((characteristicValue[7] & 0xff) << 8) | (characteristicValue[6] & 0xff) + 1;
                        if(this.hrSyncCurTimes == this.hrSyncTotTimes && this.hrSyncLastTotTimes == done) { // fetching data finished
                            getDevice().unsetBusyTask();
                            getDevice().sendDeviceUpdateIntent(getContext());
                            GB.updateTransferNotification(null, "", false, 100, getContext());

                            if(this.syncDay >= daysToSync) {
                                this.syncDay = 0;
                                break;
                            }
  
                            this.syncDay++; // start over fetching another day
                            triggerActivityReportByDays((byte)this.syncDay);
                            break;
                        }
                    }
                    break;
                case KeepFitConstants.CMD_GET_DEVICE_INFO:
                    getDevice().setFirmwareVersion("V" + BLETypeConversions.toUint16(characteristicValue, 1));
                    getDevice().addDeviceInfo(new GenericItem("CID",  Integer.toString(BLETypeConversions.toUint16(characteristicValue, 9))));
                    getDevice().addDeviceInfo(new GenericItem("DID", Integer.toString(BLETypeConversions.toUint16(characteristicValue, 11))));
                    getDevice().addDeviceInfo(new GenericItem("CRC", Integer.toHexString(BLETypeConversions.toUint32(characteristicValue, 16))));

                    LOG.info("mac: {}", ByteBuffer.wrap(characteristicValue, 3, 6));
                    break;
                case KeepFitConstants.CMD_TRIGGER_LOST:
                    // ack set lost
                    break;
                case KeepFitConstants.CMD_TRIGGER_LOST_DONE:
                    // ack set lost disabled/done
                    break;
                case KeepFitConstants.CMD_SET_STEP_GOAL_ERROR: // FALLTHROUGH
                case KeepFitConstants.CMD_SET_USER_INFO_ERROR: // FALLTHROUGH
                case KeepFitConstants.CMD_TRIGGER_ACTIVITY_REPORT_ERROR: // FALLTHROUGH
                case KeepFitConstants.CMD_SPORT_REPORT_RESULT_ERROR: // FALLTHROUGH
                case KeepFitConstants.CMD_TRIGGER_HEART_RATE_REPORT_ERROR:
                    getDevice().unsetBusyTask();
                    getDevice().sendDeviceUpdateIntent(getContext());
                    GB.updateTransferNotification(null, "", false, 100, getContext());
                    break;
                case KeepFitConstants.CMD_DEVICE_SUPPORTED_FUNCTIONS:
                    // gives feedback, what this particular device supports
                    break;
                default:
                    int maybe_unix = BLETypeConversions.toUint32(characteristicValue, 1);
                    Instant maybe_time = Instant.ofEpochSecond(maybe_unix);
                    LOG.warn("{} unknown type {}", maybe_time, characteristicValue);
                    break;
            }
        } else {
            LOG.warn("other characteristic {}", characteristicUUID);
        }
        return false;
    }

    private void safeHrData(Map<Instant, Integer> hrEntries) {
        try (DBHandler db = GBApplication.acquireDB()) {
            KeepFitHeartRateSampleProvider sampleProvider = new KeepFitHeartRateSampleProvider(getDevice(), db.getDaoSession());
            Long userId = DBHelper.getUser(db.getDaoSession()).getId();
            Long deviceId = DBHelper.getDevice(getDevice(), db.getDaoSession()).getId();
            List<KeepFitHeartRateSample> samples = (List<KeepFitHeartRateSample>) hrEntries
                    .entrySet()
                    .stream()
                    .map(
                            e -> {
                                int value = e.getValue();
                                KeepFitHeartRateSample gbSample = new KeepFitHeartRateSample();
                                gbSample.setDeviceId(deviceId);
                                gbSample.setUserId(userId);
                                long timestamp = e.getKey().toEpochMilli();
                                gbSample.setTimestamp(timestamp);
                                gbSample.setHeartRate(value);
                                return gbSample;
                            }
                    )
                    .toList();
            sampleProvider.addSamples(samples);
        } catch (Exception e) {
            LOG.error("Error acquiring database for recording sleep samples", e);
        }
    }

    private void safeSleepData(Map<Instant, Byte> sleepEntries) {
        try (DBHandler db = GBApplication.acquireDB()) {
            KeepFitActivitySampleProvider sampleProvider = new KeepFitActivitySampleProvider(getDevice(), db.getDaoSession());
            Long userId = DBHelper.getUser(db.getDaoSession()).getId();
            Long deviceId = DBHelper.getDevice(getDevice(), db.getDaoSession()).getId();
            List<KeepFitActivitySample> samples = sleepEntries
                    .entrySet()
                    .stream()
                    .map(
                            e -> {
                                Byte value = e.getValue();
                                KeepFitActivitySample gbSample = new KeepFitActivitySample();
                                ActivityKind rawKind = ActivityKind.AWAKE_SLEEP;
                                if (value >= 80) {
                                    rawKind = ActivityKind.DEEP_SLEEP;
                                } else if (value >= 1) {
                                    rawKind = ActivityKind.LIGHT_SLEEP;
                                }

                                gbSample.setDeviceId(deviceId);
                                gbSample.setUserId(userId);
                                gbSample.setRawKind(rawKind.getCode());
                                gbSample.setTimestamp((int) (e.getKey().toEpochMilli() / 1000));
                                return gbSample;
                            }
                    )
                    .toList();
            sampleProvider.addGBActivitySamples(samples);
        } catch (Exception e) {
            LOG.error("Error acquiring database for recording sleep samples", e);
        }
    }

    private void safeSportData(List<SportReportEntry> sportEntries) {
        try (DBHandler db = GBApplication.acquireDB()) {
            KeepFitActivitySampleProvider sampleProvider = new KeepFitActivitySampleProvider(getDevice(), db.getDaoSession());
            Long userId = DBHelper.getUser(db.getDaoSession()).getId();
            Long deviceId = DBHelper.getDevice(getDevice(), db.getDaoSession()).getId();
            List<KeepFitActivitySample> samples = sportEntries
                    .stream()
                    .map(
                            e -> {
                                KeepFitActivitySample gbSample = new KeepFitActivitySample();
                                ActivityKind rawKind = ActivityKind.WALKING;

                                if(e.type == 1) {
                                    rawKind = ActivityKind.WALKING;
                                } 
                                else if(e.type == 2) {
                                    rawKind = ActivityKind.RUNNING;
                                    // ActivityKind.RUNNING;
                                    // ActivityKind.HIKING;
                                    // ActivityKind.CYCLING;
                                    // ActivityKind.HIKING;
                                    // ActivityKind.BADMINTON;
                                    // ActivityKind.TABLE_TENNIS;
                                    // ActivityKind.BASKETBALL;
                                    // ActivityKind.SOCCER;
                                    // ActivityKind.JUMP_ROPING;
                                    // ActivityKind.TENNIS;
                                    // ActivityKind.BASEBALL;
                                    // ActivityKind.YOGA;
                                    // ActivityKind.GOLF;
                                    // ActivityKind.VOLLEYBALL;
                                    // ActivityKind.PILATES;
                                }

                                gbSample.setDeviceId(deviceId);
                                gbSample.setUserId(userId);
                                gbSample.setRawKind(rawKind.getCode());
                                gbSample.setTimestamp((int) (e.time.toEpochMilli() / 1000));
                                gbSample.setSteps(e.steps);
                                return gbSample;
                            }
                    )
                    .toList();
            sampleProvider.addGBActivitySamples(samples);
        } catch (Exception e) {
            LOG.error("Error acquiring database for recording sleep samples", e);
        }
    }

    private void safeStepData(Map<Instant, Byte> stepEntries) {
        try (DBHandler db = GBApplication.acquireDB()) {
            KeepFitActivitySampleProvider sampleProvider = new KeepFitActivitySampleProvider(getDevice(), db.getDaoSession());
            Long userId = DBHelper.getUser(db.getDaoSession()).getId();
            Long deviceId = DBHelper.getDevice(getDevice(), db.getDaoSession()).getId();
            List<KeepFitActivitySample> samples = stepEntries
                    .entrySet()
                    .stream()
                    .map(
                            e -> {
                                Byte value = e.getValue();
                                KeepFitActivitySample gbSample = new KeepFitActivitySample();
                                gbSample.setProvider(sampleProvider);
                                gbSample.setDeviceId(deviceId);
                                gbSample.setUserId(userId);
                                gbSample.setTimestamp((int) (e.getKey().toEpochMilli() / 1000));
                                gbSample.setSteps(value);
                                return gbSample;
                            }
                    )
                    .toList();
            sampleProvider.addGBActivitySamples(samples);
        } catch (Exception e) {
            LOG.error("Error acquiring database for recording activity samples", e);
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

    @Override
    public void onNotification(NotificationSpec notificationSpec) {
        int icon = KeepFitConstants.NOTIFICATION_SMS;
        switch (notificationSpec.type) {
            case FACEBOOK:
            case FACEBOOK_MESSENGER:
                icon = KeepFitConstants.NOTIFICATION_FACEBOOK;
                break;
            case GMAIL:
                icon = KeepFitConstants.NOTIFICATION_GMAIL;
                break;
            case INSTAGRAM:
                icon = KeepFitConstants.NOTIFICATION_INSTAGRAM;
                break;
            case KAKAO_TALK:
                icon = KeepFitConstants.NOTIFICATION_KAKAOTALK;
                break;
            case LINE:
                icon = KeepFitConstants.NOTIFICATION_LINE;
                break;
            case LINKEDIN:
                icon = KeepFitConstants.NOTIFICATION_LINKEDIN;
                break;
            case OUTLOOK:
                icon = KeepFitConstants.NOTIFICATION_OUTLOOK;
                break;
            case PINTEREST:
                icon = KeepFitConstants.NOTIFICATION_PINTEREST;
                break;
            case SKYPE:
                icon = KeepFitConstants.NOTIFICATION_SKYPE;
                break;
            case QQ:
                icon = KeepFitConstants.NOTIFICATION_MQQ;
                break;
            case SNAPCHAT:
                icon = KeepFitConstants.NOTIFICATION_SNAPCHAT;
                break;
            case TELEGRAM:
                icon = KeepFitConstants.NOTIFICATION_TELEGRAM;
                break;
            case TUMBLR:
                icon = KeepFitConstants.NOTIFICATION_TUMBLR;
                break;
            case TWITTER:
                icon = KeepFitConstants.NOTIFICATION_TWITTER;
                break;
            case VIBER:
                icon = KeepFitConstants.NOTIFICATION_VIBER;
                break;
            case VK:
                icon = KeepFitConstants.NOTIFICATION_VKONTAKTE;
                break;
            case WHATSAPP:
                icon = KeepFitConstants.NOTIFICATION_WHATSAPP;
                break;
            case YOUTUBE:
                icon = KeepFitConstants.NOTIFICATION_YOUTUBE;
                break;
            case GENERIC_PHONE:
                icon = KeepFitConstants.NOTIFICATION_PHONE;
                break;
            case GENERIC_CALENDAR: /* FALLTHROUGH */
            case BUSINESS_CALENDAR:
                icon = KeepFitConstants.NOTIFICATION_IOSCALENDAR;
                break;
            case GENERIC_EMAIL: /* FALLTHROUGH */
            case MAILBOX: /* FALLTHROUGH */
            case YAHOO_MAIL:
                icon = KeepFitConstants.NOTIFICATION_IOSEMAIL;
                break;
            default:
                icon = KeepFitConstants.NOTIFICATION_SMS;
                break;
        }

        int msgId = notificationSpec.getId();
        String id = String.format("%04d", msgId % 10000);
        String title = "";
        String body = "";

        if (notificationSpec.title != null) {
            title = notificationSpec.title;
        } else { // backup if no title exists
            if (notificationSpec.sender != null) {
                title = notificationSpec.sender;
            } else {
                if (notificationSpec.phoneNumber != null) { //use number only if there is no sender
                    title = notificationSpec.phoneNumber;
                }
            }
        }

        if (notificationSpec.body != null) {
            body = notificationSpec.body;
        }

        sendNotification(id, icon, title, body);
    }

    @Override
    public void onSendWeather() {
        setWeatherData();
    }

    @Override
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
        super.onConnectionStateChange(gatt, status, newState);
        if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            if(spo2Future != null){
                spo2Future.cancel(true);
                spo2Future = null;
            }
        } else if(newState == BluetoothProfile.STATE_CONNECTED) {
            onSendConfiguration(""); // re-start configured background services
        }
    }

    @Override
    public void dispose() {
        synchronized (ConnectionMonitor) {
            LOG.info("Dispose");
            backgroundTasksHandler.removeCallbacksAndMessages(null);
            if(spo2Future != null) {
                spo2Future.cancel(true);
                spo2Future = null;
            }
            super.dispose();
        }
    }
}
