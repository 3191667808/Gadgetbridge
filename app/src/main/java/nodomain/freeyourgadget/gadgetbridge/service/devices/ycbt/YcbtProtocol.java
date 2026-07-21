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
    private static final int COMMAND_BATTERY = 0x00;
    private static final int COMMAND_MODEL = 0x03;
    private static final int BATTERY_LEVEL_PAYLOAD_OFFSET = 5;
    private static final byte[] BATTERY_REQUEST_PAYLOAD = new byte[]{0x47, 0x43};
    private static final byte[] MODEL_REQUEST_PAYLOAD = new byte[]{0x47, 0x50};

    private YcbtProtocol() {
    }

    static byte[] buildModelRequest() {
        return YcbtFrameCodec.encode(GROUP_DEVICE_INFORMATION, COMMAND_MODEL, MODEL_REQUEST_PAYLOAD);
    }

    static byte[] buildBatteryRequest() {
        return YcbtFrameCodec.encode(GROUP_DEVICE_INFORMATION, COMMAND_BATTERY, BATTERY_REQUEST_PAYLOAD);
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
}
