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
package nodomain.freeyourgadget.gadgetbridge.service.devices.bbk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventUpdatePreferences;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.service.btle.BLETypeConversions;
import nodomain.freeyourgadget.gadgetbridge.service.serial.GBDeviceProtocol;

/**
 * Shared BBK (OnePlus/Oppo/Realme) serial protocol helpers
 */
public abstract class AbstractBBKProtocol extends GBDeviceProtocol {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractBBKProtocol.class);

    public static final byte CMD_PREAMBLE = (byte) 0xaa;
    private static final byte SUBSCRIPTION_HEADER = 0x09;

    protected int seqNum = 0;

    protected AbstractBBKProtocol(final GBDevice device) {
        super(device);
    }

    @Override
    public GBDeviceEvent[] decodeResponse(final byte[] responseData) {
        final List<GBDeviceEvent> events = new ArrayList<>();
        final ByteBuffer buf = ByteBuffer.wrap(responseData);

        while (buf.position() < buf.limit()) {
            final byte preamble = buf.get();
            if (preamble != CMD_PREAMBLE) {
                LOG.warn("Unexpected preamble {}", String.format(Locale.ROOT, "0x%02x", preamble));
                continue;
            }

            final int totalLength = buf.get() & 0xff;
            if (buf.limit() - buf.position() < totalLength) {
                LOG.error("Got partial response with {} bytes, expected {}", buf.limit() - buf.position(), totalLength);
                break;
            }

            final byte[] singleResponse = new byte[totalLength + 2];
            buf.position(buf.position() - 2);
            buf.get(singleResponse);

            events.addAll(handleSingleResponse(singleResponse));
        }
        return events.toArray(new GBDeviceEvent[0]);
    }

    protected List<GBDeviceEvent> handleSingleResponse(final byte[] responseData) {
        final ByteBuffer responseBuf = ByteBuffer.wrap(responseData).order(ByteOrder.LITTLE_ENDIAN);
        final byte preamble = responseBuf.get();

        if (preamble != CMD_PREAMBLE) {
            LOG.error("Unexpected preamble {}", String.format(Locale.ROOT, "0x%02x", preamble));
            return Collections.emptyList();
        }

        final int totalLength = responseBuf.get() & 0xff;
        if (responseData.length != totalLength + 2) {
            LOG.error("Invalid number of bytes {}, expected {}", responseData.length, totalLength + 2);
            return Collections.emptyList();
        }

        final short zero = responseBuf.getShort();
        if (zero != 0 && zero != 4) {
            LOG.warn("Unexpected bytes: {}, expected 0 or 4", String.format(Locale.ROOT, "0x%04x", zero));
        }

        final short code = responseBuf.getShort();
        final int seq = responseBuf.get() & 0xff;
        final int payloadLength = responseBuf.getShort() & 0xffff;

        if (payloadLength > responseBuf.remaining()) {
            LOG.error("Payload length {} is larger than remaining {}", payloadLength, responseBuf.remaining());
            return Collections.emptyList();
        } else if (payloadLength < responseBuf.remaining()) {
            LOG.warn("Payload length {} is smaller than remaining {}", payloadLength, responseBuf.remaining());
        }

        final byte[] payload = new byte[payloadLength];
        responseBuf.get(payload);

        return dispatch(code, payload);
    }

    protected abstract List<GBDeviceEvent> dispatch(short code, byte[] payload);

    protected byte[] encodeMessage(final short commandCode, final byte[] payload) {
        final ByteBuffer buf = ByteBuffer.allocate(9 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(CMD_PREAMBLE);
        buf.put((byte) (buf.limit() - 2));
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.putShort(commandCode);
        buf.put((byte) seqNum++);
        buf.putShort((short) payload.length);
        buf.put(payload);
        return buf.array();
    }

    protected static int readStatus(final byte[] payload) {
        if (payload == null || payload.length == 0) {
            return -1;
        }
        if (payload.length >= 2) {
            return BLETypeConversions.toUint16(payload);
        }
        return payload[0] & 0xff;
    }

    protected static List<GBDeviceEvent> parseBattery(final byte[] payload) {
        final List<GBDeviceEvent> events = new ArrayList<>();

        for (int i = 2; i + 1 < payload.length; i += 2) {
            if ((payload[i] & 0xff) == 0xff) {
                continue;
            }
            final int batteryIndex = payload[i] - 1;
            if (batteryIndex < 0 || batteryIndex > 2) {
                LOG.error("Unknown battery index {}", payload[i]);
                break;
            }

            final int batteryLevel = payload[i + 1] & 0x7f;
            if (batteryIndex == 2 && batteryLevel == 0) {
                continue;
            }

            final BatteryState batteryState = (payload[i + 1] & 0x80) != 0 ? BatteryState.BATTERY_CHARGING : BatteryState.BATTERY_NORMAL;

            LOG.debug("Got battery {}: {}%, {}", batteryIndex, batteryLevel, batteryState);

            final GBDeviceEventBatteryInfo eventBatteryInfo = new GBDeviceEventBatteryInfo();
            eventBatteryInfo.batteryIndex = batteryIndex;
            eventBatteryInfo.level = batteryLevel;
            eventBatteryInfo.state = batteryState;
            events.add(eventBatteryInfo);
        }

        java.util.Set<Integer> processedBatteries = events.stream()
                .map(event -> ((GBDeviceEventBatteryInfo) event).batteryIndex)
                .collect(java.util.stream.Collectors.toSet());

        for (int i = 0; i < 3; i++) {
            if (processedBatteries.contains(i)) {
                continue;
            }

            final GBDeviceEventBatteryInfo eventBatteryInfo = new GBDeviceEventBatteryInfo();
            eventBatteryInfo.batteryIndex = i;
            eventBatteryInfo.level = -1;
            eventBatteryInfo.state = BatteryState.UNKNOWN;
            events.add(eventBatteryInfo);
        }

        return events;
    }

    /**
     * Generic touch config parsing for BBK devices. Generic over side/type/value enums
     * to avoid duplication between Oppo (oppo.commands.*) and OnePlus (oneplus.commands.*).
     */
    protected static <S, T, V> GBDeviceEvent parseTouchConfigGeneric(
            final byte[] payload,
            final Function<Integer, S> sideFromCode,
            final Function<Integer, T> typeFromCode,
            final Function<Integer, V> valueFromCode,
            final BiFunction<S, T, String> keyFn,
            final Function<V, String> valueToPref,
            final Predicate<T> bothRewritePredicate,
            final S bothSide) {
        final GBDeviceEventUpdatePreferences event = new GBDeviceEventUpdatePreferences();
        for (int i = 2; i + 4 <= payload.length; i += 4) {
            final int sideCode = payload[i] & 0xff;
            final int typeCode = BLETypeConversions.toUint16(payload, i + 1);
            final int valueCode = payload[i + 3] & 0xff;
            final S side = sideFromCode.apply(sideCode);
            final T type = typeFromCode.apply(typeCode);
            final V value = valueFromCode.apply(valueCode);

            if (side == null) {
                LOG.warn("Unknown touch side code {}", String.format(Locale.ROOT, "0x%02x", sideCode));
                continue;
            }
            if (type == null) {
                LOG.warn("Unknown touch type code {}", String.format(Locale.ROOT, "0x%04x", typeCode));
                continue;
            }
            if (value == null) {
                LOG.warn("Unknown touch value code {}", String.format(Locale.ROOT, "0x%02x", valueCode));
                continue;
            }

            S effectiveSide = side;
            if (bothRewritePredicate != null && bothRewritePredicate.test(type) && bothSide != null) {
                effectiveSide = bothSide;
            }

            LOG.debug("Got touch config for {} {} = {}", effectiveSide, type, value);
            final String key = keyFn.apply(effectiveSide, type);
            final String prefValue = valueToPref.apply(value);
            event.withPreference(key, prefValue);
        }
        return event;
    }

    protected final byte[] encodeBatteryReq(final short commandCode) {
        return encodeMessage(commandCode, new byte[0]);
    }

    protected final byte[] encodeFindDeviceReq(final short commandCode, final boolean start) {
        return encodeMessage(commandCode, new byte[]{(byte) (start ? 0x01 : 0x00)});
    }

    protected final byte[] encodeTouchConfig(final short commandCode, final int sideCode, final int typeCode, final int valueCode) {
        final ByteBuffer buf = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 0x01);
        buf.put((byte) sideCode);
        buf.putShort((short) typeCode);
        buf.put((byte) valueCode);
        return encodeMessage(commandCode, buf.array());
    }

    protected final byte[] encodeSubscriptionSet(final short commandCode, final int[] typeCodes) {
        if (typeCodes == null || typeCodes.length == 0) {
            throw new IllegalArgumentException("Subscription list cannot be empty");
        }
        final byte[] payload = new byte[1 + typeCodes.length];
        payload[0] = SUBSCRIPTION_HEADER;
        for (int i = 0; i < typeCodes.length; i++) {
            payload[i + 1] = (byte) typeCodes[i];
        }
        return encodeMessage(commandCode, payload);
    }

    protected final byte[] encodeFeatureSet(final short commandCode, final int typeCode, final byte[] value) {
        final byte[] payload = new byte[1 + value.length];
        payload[0] = (byte) typeCode;
        System.arraycopy(value, 0, payload, 1, value.length);
        return encodeMessage(commandCode, payload);
    }

    protected final byte[] encodeFeatureReq(final short commandCode, final int[] typeCodes) {
        if (typeCodes == null || typeCodes.length == 0) {
            return encodeMessage(commandCode, new byte[]{0x00});
        }
        final byte[] payload = new byte[1 + typeCodes.length];
        payload[0] = (byte) typeCodes.length;
        for (int i = 0; i < typeCodes.length; i++) {
            payload[i + 1] = (byte) typeCodes[i];
        }
        return encodeMessage(commandCode, payload);
    }
}
