/*  Copyright (C) 2026 Marc

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.fitbit;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class FitbitCoap {
    static final int TYPE_CONFIRMABLE = 0;
    static final int TYPE_NON_CONFIRMABLE = 1;
    static final int TYPE_ACKNOWLEDGEMENT = 2;
    static final int CODE_GET = 0x01;
    static final int CODE_POST = 0x02;
    static final int CODE_CHANGED = 0x44;

    private static final int OPTION_URI_PATH = 11;
    private static final int PAYLOAD_MARKER = 0xff;

    private FitbitCoap() {
    }

    static Message parseMessage(final byte[] packet) {
        if (packet.length < 4) {
            return null;
        }

        final int version = (packet[0] >> 6) & 0x03;
        final int type = (packet[0] >> 4) & 0x03;
        final int tokenLength = packet[0] & 0x0f;
        if (version != 1 || tokenLength > 8 || 4 + tokenLength > packet.length) {
            return null;
        }

        final int code = packet[1] & 0xff;
        final int messageId = readU16(packet, 2);
        final byte[] token = Arrays.copyOfRange(packet, 4, 4 + tokenLength);
        final List<String> uriPathSegments = new ArrayList<>();
        int optionNumber = 0;
        int pos = 4 + tokenLength;
        byte[] payload = new byte[0];

        while (pos < packet.length) {
            if ((packet[pos] & 0xff) == PAYLOAD_MARKER) {
                pos++;
                payload = Arrays.copyOfRange(packet, pos, packet.length);
                break;
            }

            final int optionHeader = packet[pos++] & 0xff;
            final int optionDeltaNibble = (optionHeader >> 4) & 0x0f;
            final int optionLengthNibble = optionHeader & 0x0f;
            final OptionField optionDelta = readOptionField(packet, pos, optionDeltaNibble);
            if (optionDelta == null) {
                return null;
            }
            pos = optionDelta.nextOffset;

            final OptionField optionLength = readOptionField(packet, pos, optionLengthNibble);
            if (optionLength == null || optionLength.nextOffset + optionLength.value > packet.length) {
                return null;
            }
            pos = optionLength.nextOffset;

            optionNumber += optionDelta.value;
            if (optionNumber == OPTION_URI_PATH) {
                uriPathSegments.add(new String(packet, pos, optionLength.value, StandardCharsets.UTF_8));
            }
            pos += optionLength.value;
        }

        return new Message(type, code, messageId, token, uriPathSegments, payload);
    }

    static Integer resolveResponseCode(final Message request) {
        if (request.type != TYPE_CONFIRMABLE || request.code != CODE_POST) {
            return null;
        }

        if ("/events".equals(request.path) || "/sync/request".equals(request.path)) {
            return CODE_CHANGED;
        }

        if ("/md/3d02".equals(request.path)) {
            return CODE_CHANGED;
        }

        return null;
    }

    static boolean isResponse(final Message message) {
        return ((message.code >> 5) & 0x07) != 0;
    }

    static byte[] buildAckResponse(final Message request, final int responseCode) {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write((1 << 6) | (TYPE_ACKNOWLEDGEMENT << 4) | request.token.length);
        output.write(responseCode);
        output.write((request.messageId >> 8) & 0xff);
        output.write(request.messageId & 0xff);
        output.write(request.token, 0, request.token.length);

        int previousOptionNumber = 0;
        for (final String segment : request.uriPathSegments) {
            final byte[] value = segment.getBytes(StandardCharsets.UTF_8);
            writeOption(output, OPTION_URI_PATH - previousOptionNumber, value);
            previousOptionNumber = OPTION_URI_PATH;
        }

        return output.toByteArray();
    }

    static byte[] buildRequestMessage(final int type,
                                      final int code,
                                      final int messageId,
                                      final byte[] token,
                                      final String path,
                                      final byte[] payload) {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write((1 << 6) | (type << 4) | token.length);
        output.write(code);
        output.write((messageId >> 8) & 0xff);
        output.write(messageId & 0xff);
        output.write(token, 0, token.length);

        int previousOptionNumber = 0;
        for (final String segment : pathSegments(path)) {
            final byte[] value = segment.getBytes(StandardCharsets.UTF_8);
            writeOption(output, OPTION_URI_PATH - previousOptionNumber, value);
            previousOptionNumber = OPTION_URI_PATH;
        }

        if (payload.length > 0) {
            output.write(PAYLOAD_MARKER);
            output.write(payload, 0, payload.length);
        }

        return output.toByteArray();
    }

    static String requestKey(final int messageId, final byte[] token) {
        return messageId + ":" + toHex(token);
    }

    static String normalizePath(final String path) {
        if (path == null) {
            return "";
        }

        String normalized = path.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    static String formatType(final int type) {
        if (type == TYPE_CONFIRMABLE) {
            return "CON";
        }

        if (type == TYPE_NON_CONFIRMABLE) {
            return "NON";
        }

        if (type == TYPE_ACKNOWLEDGEMENT) {
            return "ACK";
        }

        return Integer.toString(type);
    }

    static String formatCode(final int code) {
        return String.format("%d.%02d", (code >> 5) & 0x07, code & 0x1f);
    }

    static String toHex(final byte[] value) {
        final StringBuilder builder = new StringBuilder(value.length * 2);
        for (final byte b : value) {
            builder.append(String.format("%02x", b & 0xff));
        }
        return builder.toString();
    }

    private static String[] pathSegments(final String path) {
        final String normalized = normalizePath(path);
        if (normalized.isEmpty()) {
            return new String[0];
        }
        return normalized.split("/");
    }

    private static String buildPath(final List<String> uriPathSegments) {
        if (uriPathSegments.isEmpty()) {
            return "/";
        }

        final StringBuilder builder = new StringBuilder();
        for (final String segment : uriPathSegments) {
            builder.append('/').append(segment);
        }
        return builder.toString();
    }

    private static OptionField readOptionField(final byte[] packet,
                                               final int offset,
                                               final int nibble) {
        if (nibble < 13) {
            return new OptionField(nibble, offset);
        }

        if (nibble == 13) {
            if (offset >= packet.length) {
                return null;
            }
            return new OptionField(13 + (packet[offset] & 0xff), offset + 1);
        }

        if (nibble == 14) {
            if (offset + 2 > packet.length) {
                return null;
            }
            return new OptionField(269 + readU16(packet, offset), offset + 2);
        }

        return null;
    }

    private static void writeOption(final ByteArrayOutputStream output,
                                    final int delta,
                                    final byte[] value) {
        final int deltaNibble = optionNibble(delta);
        final int lengthNibble = optionNibble(value.length);
        output.write((deltaNibble << 4) | lengthNibble);
        writeOptionExtendedValue(output, delta, deltaNibble);
        writeOptionExtendedValue(output, value.length, lengthNibble);
        output.write(value, 0, value.length);
    }

    private static int optionNibble(final int value) {
        if (value < 13) {
            return value;
        }

        if (value < 269 + 0x10000) {
            return value < 269 ? 13 : 14;
        }

        throw new IllegalArgumentException("CoAP option value too large: " + value);
    }

    private static void writeOptionExtendedValue(final ByteArrayOutputStream output,
                                                 final int value,
                                                 final int nibble) {
        if (nibble == 13) {
            output.write(value - 13);
        } else if (nibble == 14) {
            output.write(((value - 269) >> 8) & 0xff);
            output.write((value - 269) & 0xff);
        }
    }

    private static int readU16(final byte[] value, final int offset) {
        return ((value[offset] & 0xff) << 8) | (value[offset + 1] & 0xff);
    }

    static final class Message {
        final int type;
        final int code;
        final int messageId;
        final byte[] token;
        final List<String> uriPathSegments;
        final String path;
        final byte[] payload;
        final int payloadLength;

        private Message(final int type,
                        final int code,
                        final int messageId,
                        final byte[] token,
                        final List<String> uriPathSegments,
                        final byte[] payload) {
            this.type = type;
            this.code = code;
            this.messageId = messageId;
            this.token = token;
            this.uriPathSegments = uriPathSegments;
            this.path = buildPath(uriPathSegments);
            this.payload = payload;
            this.payloadLength = payload.length;
        }
    }

    private static final class OptionField {
        private final int value;
        private final int nextOffset;

        private OptionField(final int value, final int nextOffset) {
            this.value = value;
            this.nextOffset = nextOffset;
        }
    }
}
