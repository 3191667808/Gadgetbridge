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
package nodomain.freeyourgadget.gadgetbridge.service.devices.oneplus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import nodomain.freeyourgadget.gadgetbridge.service.btle.BLETypeConversions;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventUpdatePreferences;
import nodomain.freeyourgadget.gadgetbridge.devices.oneplus.OnePlusBuds4Preferences;
import nodomain.freeyourgadget.gadgetbridge.devices.oneplus.OnePlusEqPreset;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.util.StringUtils;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oneplus.commands.OnePlusBuds4AncConfigType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oneplus.commands.OnePlusBuds4AncMode;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oneplus.commands.OnePlusBuds4AncTouchCycleMode;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oneplus.commands.OnePlusBuds4Command;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oneplus.commands.OnePlusBuds4Feature;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oneplus.commands.OnePlusBuds4SubscriptionType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oneplus.commands.OnePlusBuds4TouchConfigSide;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oneplus.commands.OnePlusBuds4TouchConfigType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oneplus.commands.OnePlusBuds4TouchConfigValue;
import nodomain.freeyourgadget.gadgetbridge.service.devices.bbk.AbstractBBKProtocol;
import nodomain.freeyourgadget.gadgetbridge.util.preferences.DevicePrefs;

public class OnePlusBuds4Protocol extends AbstractBBKProtocol {
    private static final Logger LOG = LoggerFactory.getLogger(OnePlusBuds4Protocol.class);

    private static final int[] EQ_BAND_FREQUENCIES_HZ = {62, 250, 1000, 4000, 8000, 16000};

    protected OnePlusBuds4Protocol(final GBDevice device) {
        super(device);
    }

    @Override
    protected List<GBDeviceEvent> dispatch(final short code, final byte[] payload) {
        final OnePlusBuds4Command command = OnePlusBuds4Command.fromCode(code);
        if (command == null) {
            LOG.warn("Unknown command code {}", String.format(Locale.ROOT, "0x%04x", code));
            return Collections.emptyList();
        }

        final String currentEqCustomPresets = getDevice() == null ? ""
                : getDevicePrefs().getString(OnePlusBuds4Preferences.EQ_CUSTOM_PRESETS, "");

        final List<GBDeviceEvent> events = new ArrayList<>();

        switch (command) {
            case BATTERY_RET: {
                if (payload[0] != 0) {
                    LOG.error("Unknown battery ret {}", payload[0]);
                    break;
                }
                events.addAll(parseBattery(payload));
                break;
            }
            case SUBSCRIPTION_QUERY_RET:
                LOG.debug("Got subscription query ret: {}", readStatus(payload));
                break;
            case SUBSCRIPTION_ACK:
                LOG.debug("Got subscription ack, status={}", readStatus(payload));
                break;
            case SUBSCRIPTION_RET: {
                events.addAll(parseSubscription(payload));
                break;
            }
            case FIND_DEVICE_ACK: {
                LOG.debug("Got find device ack, status={}", readStatus(payload));
                break;
            }
            case MISC_CONFIG_RET: {
                if (payload[0] != 0) {
                    LOG.warn("Unknown misc config ret {}", payload[0]);
                    break;
                }
                if (payload.length < 3) {
                    LOG.warn("Unexpected misc config ret payload size {}", payload.length);
                    break;
                }

                events.add(parseMiscConfig(payload));
                break;
            }
            case MISC_CONFIG_ACK: {
                LOG.debug("Got misc config ack, status={}", readStatus(payload));
                break;
            }
            case TOUCH_CONFIG_RET: {
                if (payload[0] != 0) {
                    LOG.warn("Unknown touch config ret {}", payload[0]);
                    break;
                }
                if (payload.length < 6) {
                    LOG.warn("Unexpected touch config ret payload size {}", payload.length);
                    break;
                }

                events.add(parseTouchConfig(payload));
                break;
            }
            case TOUCH_CONFIG_ACK: {
                LOG.debug("Got touch config ack, status={}", readStatus(payload));
                break;
            }
            case ANC_CONFIG_RET: {
                if (payload[0] != 0) {
                    LOG.warn("Unknown anc config ret {}", payload[0]);
                    break;
                }
                if (payload.length < 4) {
                    LOG.warn("Unexpected anc config ret payload size {}", payload.length);
                    break;
                }

                events.add(parseAncConfig(payload));
                break;
            }
            case ANC_CONFIG_ACK: {
                LOG.debug("Got anc config ack, status={}", readStatus(payload));
                break;
            }
            case ALARM_VOLUME_RET:
            case ALARM_VOLUME_RET2: {
                if (payload.length < 2) {
                    LOG.warn("Unexpected alarm volume ret payload size {}", payload.length);
                    break;
                }
                final int volume = payload[1] & 0xff;
                final GBDeviceEventUpdatePreferences event = new GBDeviceEventUpdatePreferences();
                event.withPreference(OnePlusBuds4Preferences.ALARM_VOLUME, volume);
                events.add(event);
                break;
            }
            case EQ_RET:
            case EQ_CUSTOM_RET: {
                if (payload.length < 2) {
                    LOG.warn("Got short eq ret ({} bytes)", payload.length);
                    break;
                }
                final int subtype = payload[1] & 0xff;
                if (payload[0] != 0 || subtype <= 0) {
                    LOG.debug("Eq ret not actionable (status={}, subtype={})", payload[0], subtype);
                    break;
                }
                final OnePlusEqPreset preset = OnePlusEqPreset.findBySubtype(
                        OnePlusEqPreset.parse(currentEqCustomPresets), subtype);
                if (preset == null) {
                    LOG.debug("Eq ret confirms unknown custom subtype {} (list not loaded yet)", subtype);
                    break;
                }
                final String value = OnePlusBuds4Preferences.EQ_PRESET_CUSTOM_PREFIX + preset.subtype;
                LOG.debug("Eq ret confirms custom preset '{}' (subtype {})", preset.name, preset.subtype);
                final GBDeviceEventUpdatePreferences event = new GBDeviceEventUpdatePreferences();
                event.withPreference(OnePlusBuds4Preferences.EQ_PRESET, value);
                events.add(event);
                break;
            }
            case EQ_QUERY_RET: {
                final GBDeviceEventUpdatePreferences event = parseEqConfig(payload);
                if (event != null) {
                    events.add(event);
                } else {
                    LOG.debug("Ignoring malformed eq query payload");
                }
                break;
            }
            case EQ_ACTIVE_STATE_RET: {
                final String preset = parseEqActiveState(payload);
                if (preset != null) {
                    final GBDeviceEventUpdatePreferences event = new GBDeviceEventUpdatePreferences();
                    event.withPreference(OnePlusBuds4Preferences.EQ_PRESET, preset);
                    events.add(event);
                } else {
                    LOG.debug("Eq active state not applied: {}", StringUtils.bytesToHex(payload));
                }
                break;
            }
            case EQ_STATE_RET: {
                // Second byte 0x08 means a custom preset 0x00 means built-in.
                LOG.debug("Got eq state notification ({} bytes): {}", payload.length, StringUtils.bytesToHex(payload));
                break;
            }
            case BASS_RET: {
                if (payload.length < 4) {
                    LOG.warn("Unexpected bass boost ret payload size {}", payload.length);
                    break;
                }
                if (payload[0] != 0) {
                    LOG.warn("Unexpected bass boost ret status {}", payload[0]);
                    break;
                }
                if ((payload[1] & 0xff) != 0xfb || (payload[2] & 0xff) != 0x05) {
                    LOG.warn("Unexpected bass boost ret subcommand {}", StringUtils.bytesToHex(payload));
                    break;
                }
                final int level = payload[3]; // signed byte, range -5..+5
                LOG.debug("Got bass boost level {}", level);
                final GBDeviceEventUpdatePreferences event = new GBDeviceEventUpdatePreferences();
                event.withPreference(OnePlusBuds4Preferences.BASS_BOOST, level);
                events.add(event);
                break;
            }
            case SETTINGS_RET: {
                // SETTINGS_RET is a broad settings response. Just observe it for now.
                LOG.debug("Got settings ret ({} bytes): {}", payload.length, StringUtils.bytesToHex(payload));
                break;
            }
            default: {
                LOG.warn("Unhandled command {}", command);
            }
        }

        return events;
    }

    private static List<GBDeviceEvent> parseSubscription(final byte[] payload) {
        final List<GBDeviceEvent> events = new ArrayList<>();
        final int typeCode = payload[0] & 0xff;
        final OnePlusBuds4SubscriptionType type = OnePlusBuds4SubscriptionType.fromCode(typeCode);
        if (type == null) {
            LOG.warn("Unknown subscription type {}", String.format(Locale.ROOT, "0x%02x", typeCode));
            return events;
        }

        final GBDeviceEventUpdatePreferences eventUpdatePreferences = new GBDeviceEventUpdatePreferences();
        switch (type) {
            case BATTERY: {
                events.addAll(parseBattery(payload));
                break;
            }
            case STATUS: {
                LOG.debug("Got status");
                break;
            }
            case ANC_SELECTOR: {
                if (payload.length < 5) {
                    LOG.warn("Unexpected anc selector payload size {}", payload.length);
                    break;
                }
                final int innerType = payload[1] & 0xff;
                final int a = payload[3] & 0xff;
                final int b = payload[4] & 0xff;
                LOG.debug("ANC selector push: innerType=0x{}, A=0x{}, B=0x{}, fullPayload={}",
                        String.format(Locale.ROOT, "%02x", innerType),
                        String.format(Locale.ROOT, "%02x", a),
                        String.format(Locale.ROOT, "%02x", b),
                        StringUtils.bytesToHex(payload));
                if (innerType != 0x01) {
                    LOG.debug("ANC selector push ignored: innerType=0x{} (not 0x01)",
                            String.format(Locale.ROOT, "%02x", innerType));
                    break;
                }
                final OnePlusBuds4AncMode mode = decodeAncMode(a, b);
                if (mode == null) {
                    LOG.warn("Unknown anc mode A=0x{} B=0x{} fullPayload={}",
                            String.format(Locale.ROOT, "%02x", a),
                            String.format(Locale.ROOT, "%02x", b),
                            StringUtils.bytesToHex(payload));
                    break;
                }
                LOG.debug("Got anc selector = {} (raw {} {})", mode,
                        String.format(Locale.ROOT, "%02x", a),
                        String.format(Locale.ROOT, "%02x", b));
                eventUpdatePreferences.withPreference(OnePlusBuds4Preferences.ANC_SELECTOR, mode.getPrefId());
                events.add(eventUpdatePreferences);
                break;
            }
            case ALARM_VOLUME: {
                if (payload.length < 2) {
                    LOG.warn("Unexpected alarm volume subscription payload size {}", payload.length);
                    break;
                }
                final int volume = payload[1] & 0xff;
                LOG.debug("Got alarm volume subscription push = {}", volume);
                final GBDeviceEventUpdatePreferences event = new GBDeviceEventUpdatePreferences();
                event.withPreference(OnePlusBuds4Preferences.ALARM_VOLUME, volume);
                events.add(event);
                break;
            }
            default: {
                LOG.warn("Unhandled subscription type {}", type);
                break;
            }
        }
        return events;
    }

    private static GBDeviceEvent parseMiscConfig(final byte[] payload) {
        final GBDeviceEventUpdatePreferences eventUpdatePreferences = new GBDeviceEventUpdatePreferences();
        for (int i = 2; i + 1 < payload.length; i += 2) {
            final int typeCode = payload[i] & 0xff;
            final int valueCode = payload[i + 1] & 0xff;

            final OnePlusBuds4Feature feature = OnePlusBuds4Feature.fromCode(typeCode);
            if (feature == null) {
                LOG.warn("Unknown misc config feature code {}", typeCode);
                continue;
            }

            final boolean isEnabled = (valueCode == 1);
            switch (feature) {
                case WEAR_DETECTION:
                    LOG.debug("Got misc config for WEAR_DETECTION = {}", isEnabled);
                    eventUpdatePreferences.withPreference(
                        OnePlusBuds4Preferences.WEAR_DETECTION,
                        isEnabled
                    );
                    break;
                case GAME_MODE:
                    LOG.debug("Got misc config for GAME_MODE = {}", isEnabled);
                    eventUpdatePreferences.withPreference(
                        OnePlusBuds4Preferences.GAME_MODE,
                        isEnabled
                    );
                    break;
                case DUAL_CONNECTION:
                    LOG.debug("Got misc config for DUAL_CONNECTION = {}", isEnabled);
                    eventUpdatePreferences.withPreference(
                        OnePlusBuds4Preferences.DUAL_CONNECTION,
                        isEnabled
                    );
                    break;
                case HI_RES_AUDIO:
                    // Logged only; no user-facing preference (read-only / auto-managed by the device).
                    LOG.debug("Got misc config for HI_RES_AUDIO = {}", isEnabled);
                    break;
                case SPATIAL_AUDIO:
                    LOG.debug("Got misc config for SPATIAL_AUDIO = {}", isEnabled);
                    eventUpdatePreferences.withPreference(
                        OnePlusBuds4Preferences.SPATIAL_AUDIO,
                        isEnabled
                    );
                    break;
                case AUTO_PLAY_PAUSE:
                    LOG.debug("Got misc config for AUTO_PLAY_PAUSE = {}", isEnabled);
                    eventUpdatePreferences.withPreference(
                        OnePlusBuds4Preferences.AUTO_PLAY_PAUSE,
                        isEnabled
                    );
                    break;
                default:
                    LOG.debug("Got misc config for feature {} = {}", feature, isEnabled);
                    break;
            }
        }
        return eventUpdatePreferences;
    }

    private static GBDeviceEvent parseTouchConfig(final byte[] payload) {
        return parseTouchConfigGeneric(
                payload,
                OnePlusBuds4TouchConfigSide::fromCode,
                OnePlusBuds4TouchConfigType::fromCode,
                OnePlusBuds4TouchConfigValue::fromCode,
                OnePlusBuds4Preferences::getTouchKey,
                v -> v.name().toLowerCase(Locale.ROOT),
                t -> t == OnePlusBuds4TouchConfigType.ON_CALL_DOUBLE_TAP || t == OnePlusBuds4TouchConfigType.ON_CALL_HOLD,
                OnePlusBuds4TouchConfigSide.BOTH
        );
    }

    private static GBDeviceEvent parseAncConfig(final byte[] payload) {
        final GBDeviceEventUpdatePreferences event = new GBDeviceEventUpdatePreferences();

        final int typeCode = payload[1] & 0xff;
        final int valueCode = payload[3] & 0xff;

        final OnePlusBuds4AncConfigType type = OnePlusBuds4AncConfigType.fromCode(typeCode);
        if (type == null) {
            LOG.warn("Unknown anc type code {}", typeCode);
            return event;
        }

        switch (type) {
            case MODE: {
                final int a = valueCode & 0xff;
                final int b = payload.length > 4 ? payload[4] & 0xff : 0x00;
                final OnePlusBuds4AncMode mode = decodeAncMode(a, b);
                if (mode == null) {
                    LOG.warn("Unknown anc mode A=0x{} B=0x{} fullPayload={}",
                            String.format(Locale.ROOT, "%02x", a),
                            String.format(Locale.ROOT, "%02x", b),
                            StringUtils.bytesToHex(payload));
                    break;
                }
                LOG.debug("Got anc config for {} = {} (raw {} {})", type, mode,
                        String.format(Locale.ROOT, "%02x", a),
                        String.format(Locale.ROOT, "%02x", b));
                event.withPreference(OnePlusBuds4Preferences.ANC_SELECTOR, mode.getPrefId());
                break;
            }
            case TOUCH_CYCLE_MODES: {
                final EnumSet<OnePlusBuds4AncTouchCycleMode> modes = OnePlusBuds4AncTouchCycleMode.fromMask(valueCode);
                if (payload.length > 4 && (payload[4] & 0xff) == 0x08) {
                    // The trailing 0x08 byte is the adaptive flag, split out from the mask
                    modes.add(OnePlusBuds4AncTouchCycleMode.ADAPTIVE);
                }
                if (!modes.isEmpty()) {
                    final Set<String> prefIds = OnePlusBuds4AncTouchCycleMode.toPrefIds(modes);

                    LOG.debug("Got anc config for {} = {}", type, prefIds);
                    event.withPreference(OnePlusBuds4Preferences.ANC_TOUCH_CYCLE_MODES, prefIds);
                }
                break;
            }
            default: {
                LOG.debug("Unknown anc type code {}", typeCode);
                break;
            }
        }
        return event;
    }

    private static GBDeviceEventUpdatePreferences parseEqConfig(final byte[] payload) {
        if (payload == null || payload.length < 2) {
            return null;
        }
        int p = 0;
        final int status = payload[p++] & 0xff;
        final int count = payload[p++] & 0xff;
        if (count <= 0) {
            LOG.debug("Eq query returned no presets (status={})", status);
            final GBDeviceEventUpdatePreferences emptyEvent = new GBDeviceEventUpdatePreferences();
            emptyEvent.withPreference(OnePlusBuds4Preferences.EQ_CUSTOM_PRESETS, OnePlusEqPreset.serialize(new ArrayList<>()));
            return emptyEvent;
        }

        final List<OnePlusEqPreset> presets = new ArrayList<>();
        int selectedSubtype = -1;
        boolean malformed = false;
        for (int e = 0; e < count && p < payload.length; e++) {
            if (p + 4 > payload.length) {
                LOG.warn("Truncated eq entry header");
                malformed = true;
                break;
            }
            final int slotFlag = payload[p++] & 0xff;
            if (payload[p] != (byte) 0xfa || payload[p + 1] != 0x06) {
                LOG.warn("Unexpected eq entry marker at {}", p);
                malformed = true;
                break;
            }
            p += 2; // fa 06
            final int subtype = payload[p++] & 0xff;
            if (p >= payload.length) {
                malformed = true;
                break;
            }
            final int nameLen = payload[p++] & 0xff;
            if (p + nameLen > payload.length) {
                LOG.warn("Truncated eq name (len={})", nameLen);
                malformed = true;
                break;
            }
            final String name = new String(payload, p, nameLen, StandardCharsets.UTF_8);
            p += nameLen;
            if (p >= payload.length) {
                malformed = true;
                break;
            }
            final int bandCount = payload[p++] & 0xff;
            final int[] gains = new int[6];
            boolean truncated = false;
            for (int i = 0; i < bandCount && i < 6; i++) {
                if (p + 3 > payload.length) {
                    truncated = true;
                    break;
                }
                final int freq = (payload[p] & 0xff) | ((payload[p + 1] & 0xff) << 8);
                p += 2;
                final int gain = payload[p++]; // signed 8-bit dB
                if (i < EQ_BAND_FREQUENCIES_HZ.length && freq != EQ_BAND_FREQUENCIES_HZ[i]) {
                    LOG.warn("Unexpected eq band {} frequency {} (expected {})", i, freq, EQ_BAND_FREQUENCIES_HZ[i]);
                }
                gains[i] = gain;
            }
            if (truncated) {
                LOG.warn("Truncated eq bands");
                malformed = true;
                break;
            }
            if (bandCount > 6) {
                final int skip = (bandCount - 6) * 3;
                if (p + skip > payload.length) {
                    LOG.warn("Truncated eq extra bands");
                    malformed = true;
                    break;
                }
                p += skip;
            }

            LOG.debug("Got eq preset name={} slotFlag={} subtype={} bands={}", name, slotFlag, subtype, gains);
            presets.add(new OnePlusEqPreset(subtype, name, gains));
            if (slotFlag == 1) {
                selectedSubtype = subtype;
            }
        }

        if (presets.size() != count) {
            LOG.warn("Eq query payload truncated (parsed {}/{})", presets.size(), count);
            return null;
        }
        if (malformed) {
            LOG.warn("Malformed eq query payload, ignoring (parsed {}/{})", presets.size(), count);
            return null;
        }

        final GBDeviceEventUpdatePreferences event = new GBDeviceEventUpdatePreferences();
        event.withPreference(OnePlusBuds4Preferences.EQ_CUSTOM_PRESETS, OnePlusEqPreset.serialize(presets));
        if (selectedSubtype != -1) {
            event.withPreference(OnePlusBuds4Preferences.EQ_PRESET,
                    OnePlusBuds4Preferences.EQ_PRESET_CUSTOM_PREFIX + selectedSubtype);
            LOG.debug("Eq query indicates active custom subtype {}", selectedSubtype);
        }
        return event;
    }

    @Nullable
    private static String parseEqActiveState(final byte[] payload) {
        if (payload == null || payload.length < 2) {
            return null;
        }
        final int value = payload[1] & 0xff;
        if (value <= OnePlusEqPreset.BUILTIN_BASS_ID) {
            return OnePlusBuds4Preferences.EQ_PRESET_BUILTIN_PREFIX + value;
        }
        if (value == 4) {
            // Generic custom marker (value 4) is not actionable – no subtype, keep existing selection
            return null;
        }
        LOG.warn("Unknown eq active state value {}", value);
        return null;
    }

    @Override
    public byte[] encodeFindDevice(final boolean start) {
        return encodeFindDeviceReq(OnePlusBuds4Command.FIND_DEVICE_REQ.getCode(), start);
    }

    @Override
    public byte[] encodeSendConfiguration(final String config) {
        final DevicePrefs prefs = getDevicePrefs();

        if (config.startsWith(OnePlusBuds4Preferences.TOUCH_PREFIX)) {
            final String[] parts = config.split("__");
            final OnePlusBuds4TouchConfigSide side = OnePlusBuds4TouchConfigSide.valueOf(parts[1].toUpperCase(Locale.ROOT));
            final OnePlusBuds4TouchConfigType type = OnePlusBuds4TouchConfigType.valueOf(parts[2].toUpperCase(Locale.ROOT));
            final String valueCode = prefs.getString(OnePlusBuds4Preferences.getTouchKey(side, type), null);
            if (valueCode == null) {
                LOG.warn("Failed to get touch option value for {}/{}", side, type);
                return super.encodeSendConfiguration(config);
            }

            final OnePlusBuds4TouchConfigValue value = OnePlusBuds4TouchConfigValue.valueOf(valueCode.toUpperCase(Locale.ROOT));

            return encodeTouchConfigSet(side, type, value);
        }

        if (config.equals(OnePlusBuds4Preferences.GAME_MODE)) {
            final boolean value = prefs.getBoolean(OnePlusBuds4Preferences.GAME_MODE, false);
            LOG.debug("Sending game mode = {}", value);
            return encodeFeatureSet(OnePlusBuds4Feature.GAME_MODE, value);
        }

        if (config.equals(OnePlusBuds4Preferences.DUAL_CONNECTION)) {
            final boolean value = prefs.getBoolean(OnePlusBuds4Preferences.DUAL_CONNECTION, false);
            LOG.debug("Sending dual connection = {}", value);
            return encodeFeatureSet(OnePlusBuds4Feature.DUAL_CONNECTION, value);
        }

        if (config.equals(OnePlusBuds4Preferences.SPATIAL_AUDIO)) {
            final boolean value = prefs.getBoolean(OnePlusBuds4Preferences.SPATIAL_AUDIO, false);
            LOG.debug("Sending spatial audio = {}", value);
            return encodeFeatureSet(OnePlusBuds4Feature.SPATIAL_AUDIO, value);
        }

        if (config.equals(OnePlusBuds4Preferences.AUTO_PLAY_PAUSE)) {
            final boolean value = prefs.getBoolean(OnePlusBuds4Preferences.AUTO_PLAY_PAUSE, false);
            LOG.debug("Sending auto play/pause = {}", value);
            return encodeFeatureSet(OnePlusBuds4Feature.AUTO_PLAY_PAUSE, value);
        }

        if (config.equals(OnePlusBuds4Preferences.WEAR_DETECTION)) {
            final boolean value = prefs.getBoolean(OnePlusBuds4Preferences.WEAR_DETECTION, false);
            LOG.debug("Sending wear detection = {}", value);
            return encodeFeatureSet(OnePlusBuds4Feature.WEAR_DETECTION, value);
        }

        if (config.equals(OnePlusBuds4Preferences.ANC_SELECTOR)) {
            final String prefId = prefs.getString(OnePlusBuds4Preferences.ANC_SELECTOR, "off");
            OnePlusBuds4AncMode mode = OnePlusBuds4AncMode.fromPrefId(prefId);
            if (mode == null) {
                LOG.debug("Unknown ANC prefId = \"{}\"", prefId);
                mode = OnePlusBuds4AncMode.OFF;
            }
            LOG.debug("Sending ANC mode = {}", mode);
            prefs.getPreferences().edit()
                    .putString(OnePlusBuds4Preferences.ANC_SELECTOR, mode.getPrefId())
                    .apply();
            return encodeAncModeSet(mode);
        }

        if (config.equals(OnePlusBuds4Preferences.ANC_TOUCH_CYCLE_MODES)) {
            final Set<String> prefIds = prefs.getStringSet(OnePlusBuds4Preferences.ANC_TOUCH_CYCLE_MODES, Collections.emptySet());
            final EnumSet<OnePlusBuds4AncTouchCycleMode> modes = OnePlusBuds4AncTouchCycleMode.fromPrefIds(prefIds);
            if (modes.size() < 2) {
                LOG.warn("ANC cycle must contain at least 2 modes. Current selection: {}", modes);
                return super.encodeSendConfiguration(config);
            }

            LOG.debug("Sending ANC touch cycle modes = {}", modes);
            return encodeAncTouchCycleModesSet(modes);
        }

        if (config.equals(OnePlusBuds4Preferences.ALARM_VOLUME)) {
            final int raw = prefs.getInt(OnePlusBuds4Preferences.ALARM_VOLUME, 5);
            // UI 1 = off, 2 = lowest audible, 10 = max – device uses same 1..10 range (btsnoop: payload 0x01 = off, 0x0a = max)
            final int volume = Math.max(1, Math.min(10, raw));
            LOG.debug("Sending alarm volume = {}", volume);
            return encodeAlarmVolumeSet(volume);
        }

        if (config.equals(OnePlusBuds4Preferences.EQ_PRESET)) {
            final String value = prefs.getString(OnePlusBuds4Preferences.EQ_PRESET, OnePlusBuds4Preferences.EQ_PRESET_UNSET);
            if (value == null || value.isEmpty()) {
                return super.encodeSendConfiguration(config);
            }
            if (value.startsWith(OnePlusBuds4Preferences.EQ_PRESET_BUILTIN_PREFIX)) {
                try {
                    final int id = Integer.parseInt(value.substring(OnePlusBuds4Preferences.EQ_PRESET_BUILTIN_PREFIX.length()));
                    return encodeEqPresetSet(id);
                } catch (final NumberFormatException e) {
                    LOG.warn("Bad builtin eq preset value {}", value);
                    return super.encodeSendConfiguration(config);
                }
            }
            if (value.startsWith(OnePlusBuds4Preferences.EQ_PRESET_CUSTOM_PREFIX)) {
                final String suffix = value.substring(OnePlusBuds4Preferences.EQ_PRESET_CUSTOM_PREFIX.length());
                try {
                    final int subtype = Integer.parseInt(suffix);
                    final List<OnePlusEqPreset> presets = OnePlusEqPreset.parse(
                            prefs.getString(OnePlusBuds4Preferences.EQ_CUSTOM_PRESETS, ""));
                    OnePlusEqPreset preset = OnePlusEqPreset.findBySubtype(presets, subtype);
                    if (preset == null) {
                        LOG.warn("Unknown custom eq preset subtype/slot {}", subtype);
                        return super.encodeSendConfiguration(config);
                    }
                    return encodeEqCustomSet(preset.subtype, preset.name, preset.gains);
                } catch (final NumberFormatException e) {
                    LOG.warn("Bad custom eq preset value {}", value);
                    return super.encodeSendConfiguration(config);
                }
            }
            return super.encodeSendConfiguration(config);
        }

        if (config.startsWith(OnePlusBuds4Preferences.EQ_BAND_PREFIX)) {
            return encodeEqCustomFromPrefs(prefs);
        }

        if (config.equals(OnePlusBuds4Preferences.BASS_BOOST)) {
            final int level = prefs.getInt(OnePlusBuds4Preferences.BASS_BOOST, 0);
            LOG.debug("Sending bass boost = {}", level);
            return encodeBassBoostSet(level);
        }

        return super.encodeSendConfiguration(config);
    }

    public byte[] encodeBatteryReq() {
        return encodeBatteryReq(OnePlusBuds4Command.BATTERY_REQ.getCode());
    }

    public byte[] encodeSubscriptionQuery() {
        return encodeMessage(OnePlusBuds4Command.SUBSCRIPTION_QUERY, new byte[0]);
    }

    public byte[] encodeSubscriptionSet(@NonNull final EnumSet<OnePlusBuds4SubscriptionType> subscriptions) {
        final int[] codes = subscriptions.stream().mapToInt(OnePlusBuds4SubscriptionType::getCode).toArray();
        return encodeSubscriptionSet(OnePlusBuds4Command.SUBSCRIPTION_SET.getCode(), codes);
    }

    public byte[] encodeSettingsQuery() {
        return encodeMessage(OnePlusBuds4Command.SETTINGS_QUERY, new byte[0]);
    }

    public byte[] encodeTouchConfigSet(final OnePlusBuds4TouchConfigSide side, final OnePlusBuds4TouchConfigType type, final OnePlusBuds4TouchConfigValue value) {
        return encodeTouchConfig(OnePlusBuds4Command.TOUCH_CONFIG_SET.getCode(), side.getCode(), type.getCode(), value.getCode());
    }

    public byte[] encodeTouchConfigReq() {
        return encodeMessage(OnePlusBuds4Command.TOUCH_CONFIG_REQ, new byte[]{0x02, 0x03, 0x01});
    }

    public byte[] encodeFeatureSet(final OnePlusBuds4Feature feature, final boolean enable) {
        return encodeFeatureSet(OnePlusBuds4Command.MISC_CONFIG_SET.getCode(), feature.getCode(), new byte[]{(byte) (enable ? 0x01 : 0x00)});
    }

    public byte[] encodeMiscConfigReq(final List<OnePlusBuds4Feature> features) {
        final int[] codes = features == null ? null : features.stream().mapToInt(OnePlusBuds4Feature::getCode).toArray();
        return encodeFeatureReq(OnePlusBuds4Command.MISC_CONFIG_REQ.getCode(), codes);
    }

    public byte[] encodeAncModeSet(final OnePlusBuds4AncMode mode) {
        if (mode == OnePlusBuds4AncMode.ADAPTIVE) {
            // Adaptive carries an extra level byte, observed as 0x00 followed by the
            // mode code (e.g. 01 01 00 08).
            return encodeMessage(OnePlusBuds4Command.ANC_CONFIG_SET, new byte[] {
                (byte) OnePlusBuds4AncConfigType.MODE.getCode(),
                (byte) 0x01,
                (byte) 0x00,
                (byte) mode.getCode()
            });
        }

        return encodeMessage(OnePlusBuds4Command.ANC_CONFIG_SET, new byte[] {
            (byte) OnePlusBuds4AncConfigType.MODE.getCode(),
            (byte) 0x01,
            (byte) mode.getCode()
        });
    }

    public byte[] encodeAncTouchCycleModesSet(final EnumSet<OnePlusBuds4AncTouchCycleMode> modes) {
        int mask = OnePlusBuds4AncTouchCycleMode.toMask(modes);
        boolean hasAdaptive = modes.contains(OnePlusBuds4AncTouchCycleMode.ADAPTIVE);

        if (hasAdaptive) {
            // The adaptive mode is sent as a separate trailing 0x08 byte
            final byte[] payload = new byte[] {
                (byte) OnePlusBuds4AncConfigType.TOUCH_CYCLE_MODES.getCode(),
                (byte) 0x01,
                (byte) mask,
                (byte) 0x08
            };
            return encodeMessage(OnePlusBuds4Command.ANC_CONFIG_SET, payload);
        }

        final byte[] payload = new byte[] {
            (byte) OnePlusBuds4AncConfigType.TOUCH_CYCLE_MODES.getCode(),
            (byte) 0x01,
            (byte) mask
        };
        return encodeMessage(OnePlusBuds4Command.ANC_CONFIG_SET, payload);
    }

    public byte[] encodeAncConfigReq(final OnePlusBuds4AncConfigType type) {
        final byte[] payload = new byte[] {
            (byte) type.getCode(),
            (byte) 0x01,
        };
        return encodeMessage(OnePlusBuds4Command.ANC_CONFIG_REQ, payload);
    }

    public byte[] encodeAlarmVolumeSet(final int volume) {
        final byte[] payload = new byte[] {
            (byte) (volume & 0xff)
        };
        return encodeMessage(OnePlusBuds4Command.ALARM_VOLUME_SET, payload);
    }

    public byte[] encodeEqPresetSet(final int presetId) {
        final byte[] payload = new byte[] {
            (byte) (presetId & 0xff)
        };
        return encodeMessage(OnePlusBuds4Command.EQ_SET, payload);
    }

    public byte[] encodeEqCustomSet(final int subtype, final String name, final int[] gainsDb) {
        final byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buf = ByteBuffer.allocate(4 + 1 + nameBytes.length + 1 + 6 * 3)
                .order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 0x02);
        buf.put((byte) 0xfa);
        buf.put((byte) 0x06);
        buf.put((byte) (subtype & 0xff));
        buf.put((byte) nameBytes.length);
        buf.put(nameBytes);
        buf.put((byte) 6);
        for (int i = 0; i < 6 && i < gainsDb.length; i++) {
            final int freq = EQ_BAND_FREQUENCIES_HZ[i];
            final int db = gainsDb[i];
            buf.put((byte) (freq & 0xff));
            buf.put((byte) ((freq >> 8) & 0xff));
            buf.put((byte) db); // signed 8-bit dB
        }
        return encodeMessage(OnePlusBuds4Command.EQ_CUSTOM_SET, buf.array());
    }

    public byte[] encodeEqQuery() {
        return encodeMessage(OnePlusBuds4Command.EQ_QUERY, new byte[0]);
    }

    public byte[] encodeEqActiveStateReq() {
        return encodeMessage(OnePlusBuds4Command.EQ_ACTIVE_STATE_REQ, new byte[0]);
    }

    public byte[] encodeAlarmVolumeReq2() {
        return encodeMessage(OnePlusBuds4Command.ALARM_VOLUME_REQ2, new byte[0]);
    }

    public byte[] encodeBassBoostSet(final int level) {
        final int clamped = Math.max(-5, Math.min(5, level));
        final byte[] payload = new byte[] {
                (byte) 0xfb,
                (byte) 0x05,
                (byte) clamped,
        };
        return encodeMessage(OnePlusBuds4Command.BASS_SET, payload);
    }

    public byte[] encodeBassBoostReq() {
        return encodeMessage(OnePlusBuds4Command.BASS_REQ, new byte[0]);
    }

    private byte[] encodeEqCustomFromPrefs(final DevicePrefs prefs) {
        final int[] gains = new int[6];
        for (int i = 0; i < 6; i++) {
            gains[i] = prefs.getInt(OnePlusBuds4Preferences.getEqBandKey(i), 0);
        }
        final OnePlusEqPreset updatedPreset = lookupPresetAndUpdateGains(prefs, gains);
        final int subtype = updatedPreset != null ? updatedPreset.subtype : 0x05;
        final String name = updatedPreset != null ? updatedPreset.name : "Gadgetbridge";
        LOG.debug("Sending custom eq name={} subtype={}", name, subtype);
        return encodeEqCustomSet(subtype, name, gains);
    }

    @Nullable
    private OnePlusEqPreset lookupPresetAndUpdateGains(final DevicePrefs prefs, final int[] gains) {
        final String presetValue = prefs.getString(OnePlusBuds4Preferences.EQ_PRESET, OnePlusBuds4Preferences.EQ_PRESET_UNSET);
        if (presetValue == null || !presetValue.startsWith(OnePlusBuds4Preferences.EQ_PRESET_CUSTOM_PREFIX)) {
            return null;
        }
        final String suffix = presetValue.substring(OnePlusBuds4Preferences.EQ_PRESET_CUSTOM_PREFIX.length());
        try {
            final int selId = Integer.parseInt(suffix);
            final List<OnePlusEqPreset> presets = OnePlusEqPreset.parse(
                    prefs.getString(OnePlusBuds4Preferences.EQ_CUSTOM_PRESETS, ""));
            final OnePlusEqPreset preset = OnePlusEqPreset.findBySubtype(presets, selId);
            if (preset == null) {
                return null;
            }
            for (int k = 0; k < presets.size(); k++) {
                if (presets.get(k).subtype == preset.subtype) {
                    presets.set(k, new OnePlusEqPreset(preset.subtype, preset.name, gains));
                    break;
                }
            }
            final String updated = OnePlusEqPreset.serialize(presets);
            final String current = prefs.getString(OnePlusBuds4Preferences.EQ_CUSTOM_PRESETS, "");
            if (!current.equals(updated)) {
                prefs.getPreferences().edit()
                        .putString(OnePlusBuds4Preferences.EQ_CUSTOM_PRESETS, updated)
                        .apply();
            }
            return preset;
        } catch (final NumberFormatException ignored) {
            return null;
        }
    }

    private byte[] encodeMessage(final OnePlusBuds4Command command, final byte[] payload) {
        return super.encodeMessage(command.getCode(), payload);
    }

    @Nullable
    private static OnePlusBuds4AncMode decodeAncMode(int a, int b) {
        if (a == 0x08 && b == 0x00) return OnePlusBuds4AncMode.OFF;
        if (a == 0x00 && b == 0x01) return OnePlusBuds4AncMode.TRANSPARENCY;
        if (a == 0x00 && b == 0x08) return OnePlusBuds4AncMode.ADAPTIVE;
        OnePlusBuds4AncMode m = OnePlusBuds4AncMode.fromCode(a);
        if (m != null) return m;
        return b != 0 ? OnePlusBuds4AncMode.fromCode(b) : null;
    }
}
