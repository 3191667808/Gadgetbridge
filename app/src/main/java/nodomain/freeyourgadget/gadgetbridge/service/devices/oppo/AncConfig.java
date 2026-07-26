/*  Copyright (C) 2026 NTeditor

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.oppo;

import androidx.annotation.NonNull;
import android.content.Context;
import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.devices.oppo.OppoHeadphonesPreferences;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventUpdatePreferences;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.SubscriptionType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.AncConfigType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.AncConfigValue;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.OppoMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.OppoCommand;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class AncConfig extends AbstractConfig {
    private static final Logger LOG = LoggerFactory.getLogger(AncConfig.class);

    public AncConfig(@NonNull final Context context, @NonNull final GBDevice device) {
        super(context, device);
    }

    @NonNull
    public List<OppoMessage> encodeGet() {
        final List<OppoMessage> messages = new ArrayList<>();
        final EnumSet<AncConfigType> types = EnumSet.noneOf(AncConfigType.class);
        if (getCoordinator().supportsAnc(getDevice()))
            types.add(AncConfigType.MODE);
        if (getCoordinator().supportsTouchAncCycleModes())
            types.add(AncConfigType.TOUCH_CYCLE_MODES);
        if (getCoordinator().supportsAncLevel(getDevice()))
            types.add(AncConfigType.DYNAMIC_LEVEL);

        for (AncConfigType type : types) {
            messages.add(encodeGet(type));
        }

        return messages;
    }

    @NonNull
    public OppoMessage encodeGet(@NonNull AncConfigType type) {
        final ByteBuffer buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) type.getCode());
        return new OppoMessage(OppoCommand.ANC_CONFIG_REQ, buf.array());
    }

    @NonNull
    public List<OppoMessage> onSendConfiguration(@NonNull String config) {
        final List<OppoMessage> messages = new ArrayList<>();
        switch (config) {
            case OppoHeadphonesPreferences.ANC_MODE:
            case OppoHeadphonesPreferences.ANC_LEVEL:
                messages.add(encodeSetAncMode());
                break;
            case OppoHeadphonesPreferences.TOUCH_ANC_CYCLE_MODES:
                messages.add(encodeSetTouchAncCycleModes());
                break;
        }
        return messages;
    }

    @NonNull
    public List<GBDeviceEvent> decode(@NonNull byte[] payload) {
        final ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        final byte zero = buf.get();
        final GBDeviceEvent event = decodeSingle(buf);

        return event == null ? List.of() : List.of(event);
    }

    private OppoMessage encodeSetAncMode() {
        final String valuePreference = getDevicePrefs().getString(OppoHeadphonesPreferences.ANC_MODE, null);
        final AncConfigValue value = AncConfigValue.fromPreference(valuePreference);
        if (value == null) {
            LOG.warn("Unknown AncConfigValue preference {}", valuePreference);
            return null;
        }

        int code = value.getCode();
        if (getCoordinator().supportsAncLevel(getDevice()) && value == AncConfigValue.ON) {
            final String valueLevelPreference = getDevicePrefs().getString(OppoHeadphonesPreferences.ANC_LEVEL, null);
            AncConfigValue.Level valueLevel = AncConfigValue.Level.fromPreference(valueLevelPreference);
            if (valueLevel == null) {
                LOG.warn("Unknown AncConfigValue.Level preference {}", valueLevelPreference);
                return null;
            }
            code = valueLevel.getCode();
            LOG.debug("Sending ANC mode: ON {}", valueLevel);
        } else {
            LOG.debug("Sending ANC mode: {}", value);
        }

        return new OppoMessage(OppoCommand.ANC_CONFIG_SET, encodeSet(AncConfigType.MODE, code));
    }

    private OppoMessage encodeSetTouchAncCycleModes() {
        final Set<String> valuePreferences = getDevicePrefs().getStringSet(
                OppoHeadphonesPreferences.TOUCH_ANC_CYCLE_MODES, Collections.emptySet());
        final EnumSet<AncConfigValue> values = AncConfigValue.fromPreferences(valuePreferences);
        if (values.size() < 2) {
            LOG.warn("ANC cycle must contain at least 2 values. Current selection: {}", values);
            final String message = getContext()
                    .getString(nodomain.freeyourgadget.gadgetbridge.R.string.select_at_least_option, 2);
            GB.toast(getContext(), message, Toast.LENGTH_LONG, GB.WARN);
            return encodeGet(AncConfigType.TOUCH_CYCLE_MODES);
        }

        LOG.debug("Sending touch ANC cycle modes: {}", values);
        final int mask = AncConfigValue.toMask(values);
        return new OppoMessage(OppoCommand.ANC_CONFIG_SET, encodeSet(AncConfigType.TOUCH_CYCLE_MODES, mask));
    }

    private byte[] encodeSet(@NonNull AncConfigType type, @NonNull Integer value) {
        final ByteBuffer buf = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) type.getCode());
        buf.put(value.byteValue());
        return buf.array();
    }

    private GBDeviceEvent decodeSingle(ByteBuffer buf) {
        final Integer typeCode = getTypeCode(buf);
        final int valueCode = buf.get() & 0xff;
        if (typeCode == null) {
            return null;
        }

        final AncConfigType type = AncConfigType.fromCode(typeCode);
        if (type == null) {
            LOG.warn("Unknown AncConfigType code 0x{}", OppoUtils.numberToHex(typeCode));
            return null;
        }

        switch (type) {
            case MODE -> {
                if (getCoordinator().supportsAncLevel(getDevice())) {
                    final AncConfigValue.Level valueLevel = AncConfigValue.Level.fromCode(valueCode);
                    if (valueLevel != null) {
                        final GBDeviceEventUpdatePreferences event = new GBDeviceEventUpdatePreferences();
                        LOG.debug("Got ANC mode = ON {}", valueLevel);
                        event.withPreference(OppoHeadphonesPreferences.ANC_LEVEL, valueLevel.getPreference());
                        event.withPreference(OppoHeadphonesPreferences.ANC_MODE, AncConfigValue.ON.getPreference());
                        return event;
                    }
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("Unknown AncConfigValue.Level code 0x{}", OppoUtils.numberToHex(valueCode));
                    }
                }
                final AncConfigValue value = AncConfigValue.fromCode(valueCode);
                if (value == null) {
                    LOG.warn("Unknown AncConfigValue code 0x{}", OppoUtils.numberToHex(valueCode));
                    break;
                }

                LOG.debug("Got ANC mode = {}", value);
                return new GBDeviceEventUpdatePreferences(OppoHeadphonesPreferences.ANC_MODE, value.getPreference());
            }
            case TOUCH_CYCLE_MODES -> {
                final EnumSet<AncConfigValue> values = AncConfigValue.fromMask(valueCode);
                if (values.isEmpty()) {
                    LOG.warn("Unknown AncConfigValue mask 0x{}", OppoUtils.numberToHex(valueCode));
                    break;
                }
                LOG.debug("Got touch ANC cycle modes = {}", values);
                final Set<String> preferences = AncConfigValue.toPreferences(values);
                return new GBDeviceEventUpdatePreferences(OppoHeadphonesPreferences.TOUCH_ANC_CYCLE_MODES, preferences);
            }
            case DYNAMIC_LEVEL -> {
                final AncConfigValue.Level valueLevel = AncConfigValue.Level.fromCode(valueCode);
                if (valueLevel == null) {
                    LOG.warn("Unknown AncConfigValue.Level code 0x{}", OppoUtils.numberToHex(valueCode));
                    break;
                }
                LOG.debug("Got ANC mode = ON DYNAMIC ({})", valueLevel);
            }
        }

        return null;
    }

    private Integer getTypeCode(ByteBuffer buf) {
        switch (buf.remaining()) {
            case 2 -> {
                final int one = buf.get() & 0xff;
                if (one != 1) {
                    return null;
                }
                return AncConfigType.MODE.getCode();
            }
            case 3 -> {
                return buf.getShort() & 0xffff;
            }
            default -> {
                LOG.warn("Unexpected payload length {}, expected {} or {}",
                        buf.position() + buf.remaining(),
                        buf.position() + 2,
                        buf.position() + 3);
                return null;
            }
        }
    }
}
