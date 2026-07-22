/*  Copyright (C) 2026 Gadgetbridge contributors

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.ycbt;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;

final class YcbtProtocol {
    private static final int GROUP_SETTING = 0x01;
    private static final int GROUP_DEVICE_INFORMATION = 0x02;
    private static final int GROUP_APP_CONTROL = 0x03;
    private static final int GROUP_REAL_TIME = 0x06;
    private static final int COMMAND_SET_TIME = 0x00;
    private static final int COMMAND_LIVE_ACTIVITY_REQUEST = 0x09;
    private static final int COMMAND_LIVE_STATUS = 0x00;
    private static final int COMMAND_LIVE_HEART_RATE = 0x01;
    private static final int COMMAND_LIVE_SPO2 = 0x02;
    private static final int COMMAND_BATTERY = 0x00;
    private static final int COMMAND_CAPABILITIES = 0x01;
    private static final int COMMAND_MODEL = 0x03;
    private static final int COMMAND_LIVE_VITALS = 0x03;
    private static final int COMMAND_LIVE_BATTERY = 0x15;
    private static final int COMMAND_LIVE_MEASUREMENT = 0x2f;
    private static final int COMMAND_FIND_DEVICE = 0x00;
    private static final int COMMAND_HEART_RATE_MONITORING = 0x0c;
    private static final int COMMAND_SPO2_MONITORING = 0x26;
    private static final int DEFAULT_MONITORING_INTERVAL_MINUTES = 60;
    private static final int MINIMUM_MONITORING_INTERVAL_MINUTES = 30;
    private static final int MAXIMUM_MONITORING_INTERVAL_MINUTES = 255;
    private static final int LIVE_VITALS_PAYLOAD_LENGTH = 14;
    private static final int MINIMUM_SYSTOLIC = 60;
    private static final int MAXIMUM_SYSTOLIC = 250;
    private static final int MINIMUM_DIASTOLIC = 30;
    private static final int MAXIMUM_DIASTOLIC = 150;
    private static final int BATTERY_LEVEL_PAYLOAD_OFFSET = 5;
    private static final int MINIMUM_CAPABILITY_PAYLOAD_LENGTH = 14;
    private static final int STEPS_MASK = 1 << 7;
    private static final int SLEEP_MASK = 1 << 6;
    private static final int HEART_RATE_MASK = 1 << 3;
    private static final int BLOOD_PRESSURE_PAYLOAD_OFFSET = 0;
    private static final int BLOOD_PRESSURE_MASK = 1 << 0;
    private static final int SPO2_PAYLOAD_OFFSET = 1;
    private static final int SPO2_MASK = 1 << 3;
    private static final int HRV_PAYLOAD_OFFSET = 1;
    private static final int HRV_MASK = 1 << 1;
    private static final int TEMPERATURE_PAYLOAD_OFFSET = 8;
    private static final int TEMPERATURE_MASK = 1 << 0;
    private static final int FIND_DEVICE_PAYLOAD_OFFSET = 6;
    private static final int FIND_DEVICE_MASK = 1 << 4;
    private static final int BLOOD_SUGAR_PAYLOAD_OFFSET = 17;
    private static final int BLOOD_SUGAR_MASK = 1 << 3;
    private static final int MANUAL_MEASUREMENT_PAYLOAD_OFFSET = 15;
    private static final int MANUAL_HEART_RATE_MASK = 1 << 1;
    private static final int MANUAL_BLOOD_PRESSURE_MASK = 1 << 2;
    private static final int MANUAL_SPO2_MASK = 1 << 3;
    private static final int STRESS_PAYLOAD_OFFSET = 22;
    private static final int STRESS_MASK = 1 << 6;
    private static final int MANUAL_HRV_PAYLOAD_OFFSET = 23;
    private static final int MANUAL_HRV_MASK = 1 << 0;
    private static final int MINIMUM_PULSE = 30;
    private static final int MAXIMUM_PULSE = 220;
    private static final byte[] BATTERY_REQUEST_PAYLOAD = new byte[]{0x47, 0x43};
    private static final byte[] CAPABILITY_REQUEST_PAYLOAD = new byte[]{0x47, 0x46};
    private static final byte[] MODEL_REQUEST_PAYLOAD = new byte[]{0x47, 0x50};
    private static final byte[] BLOOD_PRESSURE_START_PAYLOAD = new byte[]{0x01, 0x01};
    private static final byte[] BLOOD_PRESSURE_STOP_PAYLOAD = new byte[]{0x00, 0x01};
    private static final byte[] HEART_RATE_START_PAYLOAD = new byte[]{0x01, 0x00};
    private static final byte[] HEART_RATE_STOP_PAYLOAD = new byte[]{0x00, 0x00};
    private static final byte[] SPO2_START_PAYLOAD = new byte[]{0x01, 0x02};
    private static final byte[] SPO2_STOP_PAYLOAD = new byte[]{0x00, 0x02};
    private static final byte[] FIND_DEVICE_PAYLOAD = new byte[]{0x01, 0x05, 0x02};
    private static final byte[] LIVE_ACTIVITY_REQUEST_PAYLOAD = new byte[]{0x01, 0x00, 0x02};
    private static final int MINIMUM_SPO2 = 70;
    private static final int MAXIMUM_SPO2 = 100;
    private static final int MINIMUM_HRV = 1;
    private static final int MAXIMUM_HRV = 300;
    private static final double MINIMUM_TEMPERATURE_CELSIUS = 30.0;
    private static final double MAXIMUM_TEMPERATURE_CELSIUS = 45.0;

    private YcbtProtocol() {
    }

    static byte[] buildModelRequest() {
        return YcbtFrameCodec.encode(GROUP_DEVICE_INFORMATION, COMMAND_MODEL, MODEL_REQUEST_PAYLOAD);
    }

    static byte[] buildBatteryRequest() {
        return YcbtFrameCodec.encode(GROUP_DEVICE_INFORMATION, COMMAND_BATTERY, BATTERY_REQUEST_PAYLOAD);
    }

    static byte[] buildCapabilityRequest() {
        return YcbtFrameCodec.encode(GROUP_DEVICE_INFORMATION, COMMAND_CAPABILITIES, CAPABILITY_REQUEST_PAYLOAD);
    }

    static byte[] buildSetTimeRequest(final Instant instant, final ZoneId zoneId) {
        if (instant == null || zoneId == null) {
            throw new IllegalArgumentException("Instant and zone ID must not be null");
        }
        final ZonedDateTime localTime = instant.atZone(zoneId);
        final int year = localTime.getYear();
        return YcbtFrameCodec.encode(GROUP_SETTING, COMMAND_SET_TIME, new byte[]{
                (byte) (year & 0xff),
                (byte) ((year >> 8) & 0xff),
                (byte) localTime.getMonthValue(),
                (byte) localTime.getDayOfMonth(),
                (byte) localTime.getHour(),
                (byte) localTime.getMinute(),
                (byte) localTime.getSecond(),
                (byte) (localTime.getDayOfWeek().getValue() - 1)
        });
    }

    static byte[] buildBloodPressureStartRequest() {
        return YcbtFrameCodec.encode(GROUP_APP_CONTROL, COMMAND_LIVE_MEASUREMENT, BLOOD_PRESSURE_START_PAYLOAD);
    }

    static byte[] buildBloodPressureStopRequest() {
        return YcbtFrameCodec.encode(GROUP_APP_CONTROL, COMMAND_LIVE_MEASUREMENT, BLOOD_PRESSURE_STOP_PAYLOAD);
    }

    static byte[] buildHeartRateStartRequest() {
        return YcbtFrameCodec.encode(GROUP_APP_CONTROL, COMMAND_LIVE_MEASUREMENT, HEART_RATE_START_PAYLOAD);
    }

    static byte[] buildHeartRateStopRequest() {
        return YcbtFrameCodec.encode(GROUP_APP_CONTROL, COMMAND_LIVE_MEASUREMENT, HEART_RATE_STOP_PAYLOAD);
    }

    static byte[] buildSpo2StartRequest() {
        return YcbtFrameCodec.encode(GROUP_APP_CONTROL, COMMAND_LIVE_MEASUREMENT, SPO2_START_PAYLOAD);
    }

    static byte[] buildSpo2StopRequest() {
        return YcbtFrameCodec.encode(GROUP_APP_CONTROL, COMMAND_LIVE_MEASUREMENT, SPO2_STOP_PAYLOAD);
    }

    static byte[] buildHeartRateMonitoringRequest(final boolean enabled, final int intervalMinutes) {
        return buildMonitoringRequest(COMMAND_HEART_RATE_MONITORING, enabled, intervalMinutes);
    }

    static byte[] buildSpo2MonitoringRequest(final boolean enabled, final int intervalMinutes) {
        return buildMonitoringRequest(COMMAND_SPO2_MONITORING, enabled, intervalMinutes);
    }

    private static byte[] buildMonitoringRequest(final int command,
                                                 final boolean enabled,
                                                 final int intervalMinutes) {
        return YcbtFrameCodec.encode(
                GROUP_SETTING,
                command,
                new byte[]{enabled ? (byte) 0x01 : 0x00, (byte) normalizeMonitoringInterval(intervalMinutes)}
        );
    }

    static int normalizeMonitoringInterval(final int intervalMinutes) {
        if (intervalMinutes <= 0) {
            return DEFAULT_MONITORING_INTERVAL_MINUTES;
        }
        return Math.max(
                MINIMUM_MONITORING_INTERVAL_MINUTES,
                Math.min(MAXIMUM_MONITORING_INTERVAL_MINUTES, intervalMinutes)
        );
    }

    static byte[] buildFindDeviceRequest() {
        return YcbtFrameCodec.encode(GROUP_APP_CONTROL, COMMAND_FIND_DEVICE, FIND_DEVICE_PAYLOAD);
    }

    static byte[] buildLiveActivityRequest() {
        return YcbtFrameCodec.encode(
                GROUP_APP_CONTROL,
                COMMAND_LIVE_ACTIVITY_REQUEST,
                LIVE_ACTIVITY_REQUEST_PAYLOAD
        );
    }

    static byte[] frameLogicalCommand(final byte[] logicalCommand) {
        if (logicalCommand == null || logicalCommand.length < 2) {
            throw new IllegalArgumentException("Logical command must contain a group and command");
        }
        return YcbtFrameCodec.encode(
                logicalCommand[0] & 0xff,
                logicalCommand[1] & 0xff,
                Arrays.copyOfRange(logicalCommand, 2, logicalCommand.length)
        );
    }

    static Integer parseBatteryLevel(final YcbtFrameCodec.Frame frame) {
        if (frame.getGroup() != GROUP_DEVICE_INFORMATION || frame.getCommand() != COMMAND_BATTERY) {
            return null;
        }

        final byte[] payload = frame.getPayload();
        if (payload.length <= BATTERY_LEVEL_PAYLOAD_OFFSET) {
            return null;
        }
        final int level = payload[BATTERY_LEVEL_PAYLOAD_OFFSET] & 0xff;
        return level <= 100 ? level : null;
    }

    static Capabilities parseCapabilities(final YcbtFrameCodec.Frame frame) {
        if (frame.getGroup() != GROUP_DEVICE_INFORMATION || frame.getCommand() != COMMAND_CAPABILITIES) {
            return null;
        }

        final byte[] payload = frame.getPayload();
        if (payload.length < MINIMUM_CAPABILITY_PAYLOAD_LENGTH) {
            return null;
        }
        return new Capabilities(
                hasBit(payload, 0, STEPS_MASK),
                hasBit(payload, 0, SLEEP_MASK),
                hasBit(payload, 0, HEART_RATE_MASK),
                hasBit(payload, BLOOD_PRESSURE_PAYLOAD_OFFSET, BLOOD_PRESSURE_MASK),
                hasBit(payload, SPO2_PAYLOAD_OFFSET, SPO2_MASK),
                hasBit(payload, HRV_PAYLOAD_OFFSET, HRV_MASK),
                hasBit(payload, TEMPERATURE_PAYLOAD_OFFSET, TEMPERATURE_MASK),
                hasBit(payload, FIND_DEVICE_PAYLOAD_OFFSET, FIND_DEVICE_MASK),
                hasBit(payload, BLOOD_SUGAR_PAYLOAD_OFFSET, BLOOD_SUGAR_MASK),
                hasBit(payload, STRESS_PAYLOAD_OFFSET, STRESS_MASK),
                hasBit(payload, MANUAL_MEASUREMENT_PAYLOAD_OFFSET, MANUAL_HEART_RATE_MASK),
                hasBit(payload, MANUAL_MEASUREMENT_PAYLOAD_OFFSET, MANUAL_BLOOD_PRESSURE_MASK),
                hasBit(payload, MANUAL_MEASUREMENT_PAYLOAD_OFFSET, MANUAL_SPO2_MASK),
                hasBit(payload, MANUAL_HRV_PAYLOAD_OFFSET, MANUAL_HRV_MASK)
        );
    }

    private static boolean hasBit(final byte[] payload, final int offset, final int mask) {
        return offset >= 0 && offset < payload.length && (payload[offset] & mask) != 0;
    }

    static String parseModelResponse(final YcbtFrameCodec.Frame frame) {
        if (frame.getGroup() != GROUP_DEVICE_INFORMATION || frame.getCommand() != COMMAND_MODEL) {
            return null;
        }

        final byte[] payload = frame.getPayload();
        int length = 0;
        while (length < payload.length && payload[length] != 0) {
            final int value = payload[length] & 0xff;
            if (value < 0x20 || value > 0x7e) {
                return null;
            }
            length++;
        }
        if (length == 0) {
            return null;
        }
        if (length == payload.length) {
            return null;
        }
        for (int index = length; index < payload.length; index++) {
            if (payload[index] != 0) {
                return null;
            }
        }
        return new String(payload, 0, length, StandardCharsets.US_ASCII);
    }

    static Integer parseBloodPressureControlReply(final YcbtFrameCodec.Frame frame) {
        if (frame.getGroup() != GROUP_APP_CONTROL || frame.getCommand() != COMMAND_LIVE_MEASUREMENT) {
            return null;
        }
        final byte[] payload = frame.getPayload();
        return payload.length == 1 ? payload[0] & 0xff : null;
    }

    static BloodPressure parseBloodPressureResult(final YcbtFrameCodec.Frame frame) {
        if (frame.getGroup() != GROUP_REAL_TIME || frame.getCommand() != COMMAND_LIVE_VITALS) {
            return null;
        }
        final byte[] payload = frame.getPayload();
        if (payload.length != LIVE_VITALS_PAYLOAD_LENGTH) {
            return null;
        }

        final int systolic = payload[0] & 0xff;
        final int diastolic = payload[1] & 0xff;
        final int rawPulse = payload[2] & 0xff;
        if (systolic < MINIMUM_SYSTOLIC || systolic > MAXIMUM_SYSTOLIC
                || diastolic < MINIMUM_DIASTOLIC || diastolic > MAXIMUM_DIASTOLIC) {
            return null;
        }
        final int pulse = rawPulse >= MINIMUM_PULSE && rawPulse <= MAXIMUM_PULSE ? rawPulse : 0;
        return new BloodPressure(systolic, diastolic, pulse);
    }

    static Activity parseLiveActivity(final YcbtFrameCodec.Frame frame) {
        if (frame.getGroup() != GROUP_REAL_TIME || frame.getCommand() != COMMAND_LIVE_STATUS) {
            return null;
        }
        final byte[] payload = frame.getPayload();
        if (payload.length < 6) {
            return null;
        }
        return new Activity(u16(payload, 0), u16(payload, 2), u16(payload, 4));
    }

    static Integer parseLiveHeartRate(final YcbtFrameCodec.Frame frame) {
        if (frame.getGroup() != GROUP_REAL_TIME || frame.getCommand() != COMMAND_LIVE_HEART_RATE) {
            return null;
        }
        final byte[] payload = frame.getPayload();
        if (payload.length < 1) {
            return null;
        }
        return plausibleHeartRate(payload[0] & 0xff);
    }

    static Integer parseLiveSpo2(final YcbtFrameCodec.Frame frame) {
        if (frame.getGroup() != GROUP_REAL_TIME || frame.getCommand() != COMMAND_LIVE_SPO2) {
            return null;
        }
        final byte[] payload = frame.getPayload();
        if (payload.length < 1) {
            return null;
        }
        return plausibleSpo2(payload[0] & 0xff);
    }

    static Integer parseLiveBattery(final YcbtFrameCodec.Frame frame) {
        if (frame.getGroup() != GROUP_REAL_TIME || frame.getCommand() != COMMAND_LIVE_BATTERY) {
            return null;
        }
        final byte[] payload = frame.getPayload();
        if (payload.length < 2) {
            return null;
        }
        final int level = payload[1] & 0xff;
        return level <= 100 ? level : null;
    }

    static LiveVitals parseLiveVitals(final YcbtFrameCodec.Frame frame) {
        if (frame.getGroup() != GROUP_REAL_TIME || frame.getCommand() != COMMAND_LIVE_VITALS) {
            return null;
        }
        final byte[] payload = frame.getPayload();
        if (payload.length < 5) {
            return null;
        }

        final Integer heartRate = plausibleHeartRate(payload[2] & 0xff);
        final int rawHrv = payload[3] & 0xff;
        final int hrv = rawHrv >= MINIMUM_HRV && rawHrv <= MAXIMUM_HRV ? rawHrv : 0;
        final Integer spo2 = plausibleSpo2(payload[4] & 0xff);
        double temperatureCelsius = 0;
        if (payload.length >= 7 && (payload[5] & 0xff) > 0) {
            final int integer = payload[5] & 0xff;
            final int fraction = payload[6] & 0xff;
            final double parsed = Double.parseDouble(integer + "." + fraction);
            if (parsed >= MINIMUM_TEMPERATURE_CELSIUS && parsed <= MAXIMUM_TEMPERATURE_CELSIUS) {
                temperatureCelsius = parsed;
            }
        }
        return new LiveVitals(
                heartRate == null ? 0 : heartRate,
                hrv,
                spo2 == null ? 0 : spo2,
                temperatureCelsius
        );
    }

    private static Integer plausibleHeartRate(final int heartRate) {
        return heartRate >= MINIMUM_PULSE && heartRate <= MAXIMUM_PULSE ? heartRate : null;
    }

    private static Integer plausibleSpo2(final int spo2) {
        return spo2 >= MINIMUM_SPO2 && spo2 <= MAXIMUM_SPO2 ? spo2 : null;
    }

    private static int u16(final byte[] payload, final int offset) {
        return (payload[offset] & 0xff) | ((payload[offset + 1] & 0xff) << 8);
    }

    static final class Capabilities {
        private final boolean steps;
        private final boolean sleep;
        private final boolean heartRate;
        private final boolean bloodPressure;
        private final boolean spo2;
        private final boolean hrv;
        private final boolean temperature;
        private final boolean findDevice;
        private final boolean bloodSugar;
        private final boolean stress;
        private final boolean manualHeartRate;
        private final boolean manualBloodPressure;
        private final boolean manualSpo2;
        private final boolean manualHrv;

        private Capabilities(final boolean steps,
                             final boolean sleep,
                             final boolean heartRate,
                             final boolean bloodPressure,
                             final boolean spo2,
                             final boolean hrv,
                             final boolean temperature,
                             final boolean findDevice,
                             final boolean bloodSugar,
                             final boolean stress,
                             final boolean manualHeartRate,
                             final boolean manualBloodPressure,
                             final boolean manualSpo2,
                             final boolean manualHrv) {
            this.steps = steps;
            this.sleep = sleep;
            this.heartRate = heartRate;
            this.bloodPressure = bloodPressure;
            this.spo2 = spo2;
            this.hrv = hrv;
            this.temperature = temperature;
            this.findDevice = findDevice;
            this.bloodSugar = bloodSugar;
            this.stress = stress;
            this.manualHeartRate = manualHeartRate;
            this.manualBloodPressure = manualBloodPressure;
            this.manualSpo2 = manualSpo2;
            this.manualHrv = manualHrv;
        }

        boolean hasSteps() {
            return steps;
        }

        boolean hasSleep() {
            return sleep;
        }

        boolean hasHeartRate() {
            return heartRate;
        }

        boolean hasBloodPressure() {
            return bloodPressure;
        }

        boolean hasSpo2() {
            return spo2;
        }

        boolean hasHrv() {
            return hrv;
        }

        boolean hasTemperature() {
            return temperature;
        }

        boolean hasFindDevice() {
            return findDevice;
        }

        boolean hasBloodSugar() {
            return bloodSugar;
        }

        boolean hasStress() {
            return stress;
        }

        boolean hasManualHeartRate() {
            return manualHeartRate;
        }

        boolean hasManualBloodPressure() {
            return manualBloodPressure;
        }

        boolean hasManualSpo2() {
            return manualSpo2;
        }

        boolean hasManualHrv() {
            return manualHrv;
        }
    }

    static final class BloodPressure {
        private final int systolic;
        private final int diastolic;
        private final int pulse;

        private BloodPressure(final int systolic, final int diastolic, final int pulse) {
            this.systolic = systolic;
            this.diastolic = diastolic;
            this.pulse = pulse;
        }

        int getSystolic() {
            return systolic;
        }

        int getDiastolic() {
            return diastolic;
        }

        int getPulse() {
            return pulse;
        }
    }

    static final class Activity {
        private final int steps;
        private final int distanceMeters;
        private final int calories;

        private Activity(final int steps, final int distanceMeters, final int calories) {
            this.steps = steps;
            this.distanceMeters = distanceMeters;
            this.calories = calories;
        }

        int getSteps() {
            return steps;
        }

        int getDistanceMeters() {
            return distanceMeters;
        }

        int getCalories() {
            return calories;
        }
    }

    static final class LiveVitals {
        private final int heartRate;
        private final int hrv;
        private final int spo2;
        private final double temperatureCelsius;

        private LiveVitals(final int heartRate,
                           final int hrv,
                           final int spo2,
                           final double temperatureCelsius) {
            this.heartRate = heartRate;
            this.hrv = hrv;
            this.spo2 = spo2;
            this.temperatureCelsius = temperatureCelsius;
        }

        int getHeartRate() {
            return heartRate;
        }

        int getHrv() {
            return hrv;
        }

        int getSpo2() {
            return spo2;
        }

        double getTemperatureCelsius() {
            return temperatureCelsius;
        }
    }
}
