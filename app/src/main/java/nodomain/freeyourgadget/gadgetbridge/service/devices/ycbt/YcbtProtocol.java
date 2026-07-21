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

final class YcbtProtocol {
    private static final int GROUP_DEVICE_INFORMATION = 0x02;
    private static final int GROUP_APP_CONTROL = 0x03;
    private static final int GROUP_REAL_TIME = 0x06;
    private static final int COMMAND_BATTERY = 0x00;
    private static final int COMMAND_CAPABILITIES = 0x01;
    private static final int COMMAND_MODEL = 0x03;
    private static final int COMMAND_LIVE_VITALS = 0x03;
    private static final int COMMAND_LIVE_MEASUREMENT = 0x2f;
    private static final int LIVE_VITALS_PAYLOAD_LENGTH = 14;
    private static final int MINIMUM_SYSTOLIC = 60;
    private static final int MAXIMUM_SYSTOLIC = 250;
    private static final int MINIMUM_DIASTOLIC = 30;
    private static final int MAXIMUM_DIASTOLIC = 150;
    private static final int BATTERY_LEVEL_PAYLOAD_OFFSET = 5;
    private static final int CAPABILITY_PAYLOAD_LENGTH = 60;
    private static final int BLOOD_PRESSURE_PAYLOAD_OFFSET = 0;
    private static final int BLOOD_PRESSURE_MASK = 1 << 0;
    private static final int TEMPERATURE_PAYLOAD_OFFSET = 8;
    private static final int TEMPERATURE_MASK = 1 << 0;
    private static final int FIND_DEVICE_PAYLOAD_OFFSET = 6;
    private static final int FIND_DEVICE_MASK = 1 << 4;
    private static final int BLOOD_SUGAR_PAYLOAD_OFFSET = 17;
    private static final int BLOOD_SUGAR_MASK = 1 << 3;
    private static final byte[] BATTERY_REQUEST_PAYLOAD = new byte[]{0x47, 0x43};
    private static final byte[] CAPABILITY_REQUEST_PAYLOAD = new byte[]{0x47, 0x46};
    private static final byte[] MODEL_REQUEST_PAYLOAD = new byte[]{0x47, 0x50};
    private static final byte[] BLOOD_PRESSURE_START_PAYLOAD = new byte[]{0x01, 0x01};
    private static final byte[] BLOOD_PRESSURE_STOP_PAYLOAD = new byte[]{0x00, 0x01};

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

    static byte[] buildBloodPressureStartRequest() {
        return YcbtFrameCodec.encode(GROUP_APP_CONTROL, COMMAND_LIVE_MEASUREMENT, BLOOD_PRESSURE_START_PAYLOAD);
    }

    static byte[] buildBloodPressureStopRequest() {
        return YcbtFrameCodec.encode(GROUP_APP_CONTROL, COMMAND_LIVE_MEASUREMENT, BLOOD_PRESSURE_STOP_PAYLOAD);
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
        if (payload.length != CAPABILITY_PAYLOAD_LENGTH) {
            return null;
        }
        return new Capabilities(
                hasBit(payload, BLOOD_PRESSURE_PAYLOAD_OFFSET, BLOOD_PRESSURE_MASK),
                hasBit(payload, TEMPERATURE_PAYLOAD_OFFSET, TEMPERATURE_MASK),
                hasBit(payload, FIND_DEVICE_PAYLOAD_OFFSET, FIND_DEVICE_MASK),
                hasBit(payload, BLOOD_SUGAR_PAYLOAD_OFFSET, BLOOD_SUGAR_MASK)
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
        if (systolic < MINIMUM_SYSTOLIC || systolic > MAXIMUM_SYSTOLIC
                || diastolic < MINIMUM_DIASTOLIC || diastolic > MAXIMUM_DIASTOLIC) {
            return null;
        }
        return new BloodPressure(systolic, diastolic);
    }

    static final class Capabilities {
        private final boolean bloodPressure;
        private final boolean temperature;
        private final boolean findDevice;
        private final boolean bloodSugar;

        private Capabilities(final boolean bloodPressure,
                             final boolean temperature,
                             final boolean findDevice,
                             final boolean bloodSugar) {
            this.bloodPressure = bloodPressure;
            this.temperature = temperature;
            this.findDevice = findDevice;
            this.bloodSugar = bloodSugar;
        }

        boolean hasBloodPressure() {
            return bloodPressure;
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
    }

    static final class BloodPressure {
        private final int systolic;
        private final int diastolic;

        private BloodPressure(final int systolic, final int diastolic) {
            this.systolic = systolic;
            this.diastolic = diastolic;
        }

        int getSystolic() {
            return systolic;
        }

        int getDiastolic() {
            return diastolic;
        }
    }
}
