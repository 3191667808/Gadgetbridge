/*  Copyright (C) 2024 José Rebelo
    Copyright (C) 2026 NTeditor

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

import org.apache.commons.lang3.ArrayUtils;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.Set;
import java.util.EnumSet;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.StringUtils;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.service.btbr.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.BLETypeConversions;
import nodomain.freeyourgadget.gadgetbridge.service.AbstractHeadphoneBTBRDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.OppoCommand;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.TouchConfigType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.TouchConfigSide;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.TouchConfigValue;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.MiscConfigType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.AncConfigType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.AncConfigValue;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.SubscriptionType;
import nodomain.freeyourgadget.gadgetbridge.devices.oppo.OppoHeadphonesCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.oppo.OppoHeadphonesPreferences;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventUpdatePreferences;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.activities.multipoint.MultipointDevice;
import nodomain.freeyourgadget.gadgetbridge.activities.multipoint.MultipointPairingActivity;

public class OppoHeadphonesSupport extends AbstractHeadphoneBTBRDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(OppoHeadphonesSupport.class);
    private static final int MAX_MTU = 2048;
    private static final UUID UUID_SERVICE_STANDARD = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_SERVICE_OPPO = UUID.fromString("0000079a-d102-11e1-9b23-00025b00a5a5");
    public static final byte CMD_PREAMBLE = (byte) 0xAA;

    private final ByteBuffer packetBuffer = ByteBuffer.allocate(MAX_MTU).order(ByteOrder.LITTLE_ENDIAN);
    private int seqNum = 0;

    public OppoHeadphonesSupport() {
        super(LOG, MAX_MTU);
    }

    @Override
    public UUID getSupportedService() {
        if (getCoordinator().useStandardSppUuid(getDevice())) {
            return UUID_SERVICE_STANDARD;
        } else {
            return UUID_SERVICE_OPPO;
        }
    }

    @Override
    public boolean useAutoConnect() {
        return true;
    }

    @Override
    protected TransactionBuilder initializeDevice(final TransactionBuilder builder) {
        packetBuffer.clear();
        subscriptionSet(builder);
        firmwareVersionGet(builder);
        batteryGet(builder);
        touchConfigGet(builder);
        miscConfigGet(builder);
        if (getCoordinator().supportsAnc(getDevice())) {
            ancConfigGet();
        }

        builder.setDeviceState(GBDevice.State.INITIALIZED);
        return builder;
    }

    @Override
    public void setContext(@NonNull GBDevice gbDevice, @NonNull BluetoothAdapter btAdapter, @NonNull Context context) {
        super.setContext(gbDevice, btAdapter, context);
        if (getCoordinator().supportsMultipoint(getDevice())) {
            setupMultipointBroadcastReceiver();
        }
    }

    @Override
    public void dispose() {
        synchronized (ConnectionMonitor) {
            super.dispose();
            if (getCoordinator().supportsMultipoint(getDevice())) {
                LocalBroadcastManager.getInstance(getContext()).unregisterReceiver(multipointBroadcastReceiver);
            }
        }
    }

    @Override
    public void onSocketRead(final byte[] data) {
        packetBuffer.put(data);
        packetBuffer.flip();

        while (packetBuffer.hasRemaining()) {
            packetBuffer.mark();

            if (packetBuffer.remaining() < 2) {
                packetBuffer.reset();
                break;
            }

            final byte preamble = packetBuffer.get();
            if (preamble != CMD_PREAMBLE) {
                LOG.warn("Unexpected preamble {}, skipping 1 byte", preamble);
                continue;
            }

            final int totalLength = packetBuffer.get() & 0xFF;
            if (packetBuffer.remaining() < totalLength) {
                LOG.info("Got partial response with {} bytes, expected {}",
                        packetBuffer.remaining(), totalLength);
                packetBuffer.reset();
                break;
            }

            final int nextPacketPosition = packetBuffer.position() + totalLength;

            final short zero = packetBuffer.getShort();
            if (zero != 0 && zero != 4 && zero != 8) {
                // 0 on oppo, 4 on realme?
                // 8 on realme buds t200?
                LOG.warn("Unexpected bytes: {}, expected 0, 4 or 8", zero);
            }

            final short code = packetBuffer.getShort();
            final OppoCommand command = OppoCommand.fromCode(code);
            if (command == null) {
                LOG.warn("Unknown command code 0x{}", intToHex(code, 4));
                packetBuffer.position(nextPacketPosition);
                continue;
            }

            final int seq = packetBuffer.get();
            final int payloadLength = packetBuffer.getShort() & 0xFFFF;
            final int expectedPayloadLength = totalLength - 7;
            if (payloadLength != expectedPayloadLength) {
                LOG.error("Unexpected payload length: {}, expected {}", payloadLength, expectedPayloadLength);
                packetBuffer.position(nextPacketPosition);
                continue;
            }

            final byte[] payload = new byte[payloadLength];
            packetBuffer.get(payload);
            handleCommand(command, payload);
        }

        packetBuffer.compact();
    }

    @Override
    public void onSendConfiguration(String config) {
        if (config.startsWith(OppoHeadphonesPreferences.TOUCH_PREFIX)) {
            touchConfigSet(config);
            return;
        }

        switch (config) {
            case OppoHeadphonesPreferences.LDAC -> ldacSet();
            case OppoHeadphonesPreferences.GAME_MODE -> gameModeSet();
            case OppoHeadphonesPreferences.ANC_MODE -> ancModeSet();
            case OppoHeadphonesPreferences.ANC_LEVEL -> ancModeSet();
            case OppoHeadphonesPreferences.TOUCH_ANC_CYCLE_MODES -> touchAncCycleModesSet();
            case OppoHeadphonesPreferences.SPATIAL_AUDIO -> spatialAudioSet();
        }
    }

    private void handleCommand(OppoCommand command, byte[] payload) {
        switch (command) {
            case BATTERY_RET -> {
                if (payload[0] != 0) {
                    LOG.warn("Unknown battery ret {}", payload[0]);
                    break;
                }

                parseBattery(payload);
            }
            case SUBSCRIPTION_ACK -> LOG.debug("Got subscription ack, status={}", payload[0]);
            case SUBSCRIPTION_RET -> parseSubscription(payload);
            case FIRMWARE_RET -> {
                if (payload[0] != 0) {
                    LOG.warn("Unexpected firmware ret {}", payload[0]);
                    break;
                }

                parseFirmwareVersion(payload);
            }
            case TOUCH_CONFIG_ACK -> LOG.debug("Got touch config ack, status={}", payload[0]);
            case TOUCH_CONFIG_RET -> {
                if (payload[0] != 0) {
                    LOG.warn("Unknown touch config ret {}", payload[0]);
                    break;
                }

                parseTouchConfig(payload);
                break;
            }
            case MISC_CONFIG_ACK -> {
                LOG.debug("Got misc config ack, status={}", payload[0]);
                break;
            }
            case MISC_CONFIG_RET -> {
                if (payload[0] != 0) {
                    LOG.warn("Unknown misc config ret {}", payload[0]);
                    break;
                }

                parseMiscConfig(payload);
            }
            case ANC_CONFIG_ACK -> LOG.debug("Got anc config ack, status={}", payload[0]);
            case ANC_CONFIG_RET -> {
                if (payload[0] != 0) {
                    LOG.warn("Unknown anc config ret {}", payload[0]);
                    break;
                }

                parseAncConfig(payload);
            }
            case FIND_DEVICE_ACK ->
                LOG.debug("Got find device ack, status={}", payload[0]);
            case MULTIPOINT_DEVICES_ACK ->
                LOG.debug("Got multipoint devices ack, status={}", payload[0]);
            case MULTIPOINT_DEVICES_RET -> {
                if (payload[0] != 0) {
                    LOG.warn("Unknown multipoint devices ret {}", payload[0]);
                }
                parseMultipointDevices(payload);
            }
            default -> LOG.warn("Unhandled command {}", command);
        }

    }

    private void batteryGet(final TransactionBuilder builder) {
        sendCommand(builder, OppoCommand.BATTERY_REQ, null);
    }

    private void parseBattery(final byte[] payload) {
        final List<GBDeviceEventBatteryInfo> events = new ArrayList<>();
        final int numBatteries = payload[1] & 0xff;
        for (int i = 2; i < payload.length; i += 2) {
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
            final BatteryState batteryState = (payload[i + 1] & 0x80) != 0 ? BatteryState.BATTERY_CHARGING
                    : BatteryState.BATTERY_NORMAL;

            LOG.debug("Got battery {}: {}%, {}", batteryIndex, batteryLevel, batteryState);

            final GBDeviceEventBatteryInfo eventBatteryInfo = new GBDeviceEventBatteryInfo();
            eventBatteryInfo.batteryIndex = batteryIndex;
            eventBatteryInfo.level = batteryLevel;
            eventBatteryInfo.state = batteryState;
            events.add(eventBatteryInfo);
        }

        List<Integer> processedBatteries = events.stream()
                .map(event -> event.batteryIndex)
                .toList();

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

        for (GBDeviceEventBatteryInfo event : events) {
            evaluateGBDeviceEvent(event);
        }
    }

    private void subscriptionSet(final TransactionBuilder builder) {
        final List<SubscriptionType> types = new ArrayList<>();
        types.add(SubscriptionType.BATTERY);
        types.add(SubscriptionType.STATUS);
        if (getCoordinator().supportsAnc(getDevice()))
            types.add(SubscriptionType.ANC_MODE);
        if (getCoordinator().supportsGameMode(getDevice()))
            types.add(SubscriptionType.GAME_MODE);
        if (getCoordinator().supportsMultipoint(getDevice()))
            types.add(SubscriptionType.MULTIPOINT);

        final ByteBuffer buf = ByteBuffer.allocate(1 + types.size());
        buf.put((byte) 0x09);
        for (SubscriptionType type : types) {
            buf.put((byte) type.getCode());
        }
        sendCommand(builder, OppoCommand.SUBSCRIPTION_SET, buf.array());
    }

    private void parseSubscription(final byte[] payload) {
        final ByteBuffer buf = ByteBuffer.wrap(payload);
        if (buf.remaining() < 1) {
            LOG.warn("Unexpected payload remaining: {}, expected >=1", buf.remaining());
            return;
        }

        final int typeCode = buf.get() & 0xFF;
        final SubscriptionType type = SubscriptionType.fromCode(typeCode);
        if (type == null) {
            LOG.warn("Unknown subcription type 0x{}", intToHex(typeCode, 2));
            return;
        }

        switch (type) {
            case BATTERY: {
                parseBattery(buf.array());
                break;
            }
            case STATUS: {
                LOG.debug("Got status");
                // TODO handle
                break;
            }
            case GAME_MODE: {
                if (buf.remaining() != 1) {
                    LOG.warn("Unexpected payload remaining: {}, expected 1", buf.remaining());
                    return;
                }
                final boolean isEnabled = ((buf.get() & 0xFF) == 0x01);
                LOG.debug("Got misc config for GAME_MODE = {}", isEnabled);
                evaluateGBDeviceEvent(new GBDeviceEventUpdatePreferences(
                        OppoHeadphonesPreferences.GAME_MODE,
                        isEnabled));
                break;
            }
            case ANC_MODE: {
                parseSubscriptionAncMode(payload);
                break;
            }
            case MULTIPOINT: {
                parseMultipointDevices(payload);
                break;
            }
            default: {
                LOG.warn("Unhandled subscription type {}", type);
                break;
            }
        }
    }

    private void parseSubscriptionAncMode(final byte[] payload) {
        final ByteBuffer buf = ByteBuffer.wrap(payload);
        if (buf.remaining() != 3 && buf.remaining() != 4) {
            LOG.warn("Unexpected payload remaining: {}, expected 3 or 4", buf.remaining());
        }

        final int zero = buf.get();
        final int type = ((buf.remaining() == 3) ? (buf.get() & 0xff) : 0x01);
        final int one = buf.get();
        final int valueCode = buf.get() & 0xff;

        final AncConfigValue.Level valueLevel = AncConfigValue.Level.fromCode(valueCode);
        final AncConfigValue value = AncConfigValue.fromCode(valueCode);

        switch (type) {
            case 0x01 -> {
                if (valueLevel != null) {
                    LOG.debug("Got anc config for {} = ON {}", type, valueLevel);
                    final GBDeviceEventUpdatePreferences event = new GBDeviceEventUpdatePreferences();
                    event.withPreference(OppoHeadphonesPreferences.ANC_LEVEL, valueLevel.getPreference());
                    event.withPreference(OppoHeadphonesPreferences.ANC_MODE, AncConfigValue.ON.getPreference());
                    evaluateGBDeviceEvent(event);
                    break;
                }

                if (value == null) {
                    LOG.warn("Unknown anc value code 0x{}", intToHex(valueCode, 2));
                    break;
                }

                LOG.debug("Got anc config for MODE = {}", value);
                evaluateGBDeviceEvent(new GBDeviceEventUpdatePreferences(
                        OppoHeadphonesPreferences.ANC_MODE,
                        value.getPreference()));

            }
            case 0x04 -> {
                LOG.debug("Got anc config for {} = ON DYNAMIC {}", type, valueLevel);
            }
        }
    }

    private void firmwareVersionGet(final TransactionBuilder builder) {
        sendCommand(builder, OppoCommand.FIRMWARE_REQ, null);
    }

    private void parseFirmwareVersion(final byte[] payload) {
        final String fwString;
        if (payload[payload.length - 1] == 0) {
            fwString = new String(ArrayUtils.subarray(payload, 2, payload.length - 1)).strip();
        } else {
            fwString = new String(ArrayUtils.subarray(payload, 2, payload.length)).strip();
        }
        final String[] parts = fwString.split(",");
        if (parts.length % 3 != 0) {
            LOG.warn("Fw parts length {} from '{}' is not divisible by 3", parts.length, fwString);

            // We need to persist something, otherwise Gb misbehaves
            final GBDeviceEventVersionInfo eventVersionInfo = new GBDeviceEventVersionInfo();
            eventVersionInfo.fwVersion = fwString;
            eventVersionInfo.hwVersion = getContext().getString(R.string.n_a);
            evaluateGBDeviceEvent(eventVersionInfo);
            return;
        }
        final String[] fwVersionParts = new String[3];
        for (int i = 0; i < parts.length; i += 3) {
            final String versionPart = parts[i];
            final String versionType = parts[i + 1];
            final String version = parts[i + 2];
            if (!"2".equals(versionType)) {
                continue; // not fw
            }

            switch (versionPart) {
                case "1":
                    fwVersionParts[0] = version;
                    break;
                case "2":
                    fwVersionParts[1] = version;
                    break;
                case "3":
                    fwVersionParts[2] = version;
                    break;
                default:
                    LOG.warn("Unknown firmware version part {}", versionPart);
            }
        }

        final List<String> nonNullParts = new ArrayList<>(fwVersionParts.length);
        for (int i = 0; i < fwVersionParts.length; i++) {
            if (fwVersionParts[i] == null) {
                continue;
            }
            nonNullParts.add(fwVersionParts[i]);
            if (fwVersionParts[i].contains(".")) {
                // Realme devices have the version already with the dots, repeated multiple
                // times
                break;
            }
        }
        final String fwVersion = String.join(".", nonNullParts);

        final GBDeviceEventVersionInfo eventVersionInfo = new GBDeviceEventVersionInfo();
        eventVersionInfo.fwVersion = fwVersion;
        eventVersionInfo.hwVersion = GBApplication.getContext().getString(R.string.n_a);
        evaluateGBDeviceEvent(eventVersionInfo);

        LOG.debug("Got fw version: {}", fwVersion);
    }

    private void touchConfigSet(final String config) {
        final String[] parts = config.split("__");
        final TouchConfigSide side = TouchConfigSide.valueOf(parts[1].toUpperCase(Locale.ROOT));
        final TouchConfigType type = TouchConfigType.valueOf(parts[2].toUpperCase(Locale.ROOT));
        final String valueCode = getDevicePrefs().getString(OppoHeadphonesPreferences.getTouchKey(side, type), null);
        if (valueCode == null) {
            LOG.warn("Failed to get touch option value for {}/{}", side, type);
            return;
        }
        final TouchConfigValue value = TouchConfigValue.valueOf(valueCode.toUpperCase(Locale.ROOT));

        final ByteBuffer buf = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 0x01);
        buf.put((byte) side.getCode());
        buf.putShort((short) type.getCode());
        buf.put((byte) value.getCode());

        LOG.debug("Sending {} {} = {}", side, type, value);
        sendCommand(OppoCommand.TOUCH_CONFIG_SET, buf.array());
    }

    private void touchConfigGet(final TransactionBuilder builder) {
        sendCommand(builder, OppoCommand.TOUCH_CONFIG_REQ, new byte[] { 0x02, 0x03, 0x01 });
    }

    private void parseTouchConfig(final byte[] payload) {
        if ((payload.length - 2) % 4 != 0) {
            LOG.warn("Unexpected touch config ret payload size {}", payload.length);
            return;
        }

        final GBDeviceEventUpdatePreferences eventUpdatePreferences = new GBDeviceEventUpdatePreferences();
        for (int i = 2; i < payload.length; i += 4) {
            final int sideCode = payload[i] & 0xff;
            final int typeCode = BLETypeConversions.toUint16(payload, i + 1);
            final int valueCode = payload[i + 3] & 0xff;
            final TouchConfigSide side = TouchConfigSide.fromCode(sideCode);
            final TouchConfigType type = TouchConfigType.fromCode(typeCode);
            final TouchConfigValue value = TouchConfigValue.fromCode(valueCode);

            if (side == null) {
                LOG.warn("Unknown touch side code 0x{}", intToHex(sideCode, 2));
                continue;
            }
            if (type == null) {
                LOG.warn("Unknown touch type code 0x{}", intToHex(typeCode, 4));
                continue;
            }
            if (value == null) {
                LOG.warn("Unknown touch value code 0x{}", intToHex(valueCode, 2));
                continue;
            }

            LOG.debug("Got touch config for {} {} = {}", side, type, value);

            eventUpdatePreferences.withPreference(
                    OppoHeadphonesPreferences.getTouchKey(side, type),
                    value.name().toLowerCase(Locale.ROOT));
        }
        evaluateGBDeviceEvent(eventUpdatePreferences);
    }

    private void ldacSet() {
        final boolean isEnabled = getDevicePrefs().getBoolean(OppoHeadphonesPreferences.LDAC, false);
        LOG.debug("Sending LDAC = {}", isEnabled);
        miscConfigSet(MiscConfigType.LDAC, isEnabled);
    }

    private void gameModeSet() {
        final boolean isEnabled = getDevicePrefs().getBoolean(OppoHeadphonesPreferences.GAME_MODE, false);
        LOG.debug("Sending GAME_MODE = {}", isEnabled);
        miscConfigSet(MiscConfigType.GAME_MODE, isEnabled);
    }

    private void spatialAudioSet() {
        final boolean isEnabled = getDevicePrefs().getBoolean(OppoHeadphonesPreferences.SPATIAL_AUDIO, false);
        LOG.debug("SPATIAL_AUDIO = {}", isEnabled);
        miscConfigSet(MiscConfigType.SPATIAL_AUDIO, isEnabled);
    }

    private void miscConfigSet(final MiscConfigType type, final boolean isEnabled) {
        final byte[] payload = new byte[] {
                (byte) type.getCode(),
                (byte) (isEnabled ? 0x01 : 0x00),
        };
        sendCommand(OppoCommand.MISC_CONFIG_SET, payload);
    }

    private void miscConfigGet(final TransactionBuilder builder) {
        final EnumSet<MiscConfigType> types = EnumSet.noneOf(MiscConfigType.class);
        if (getCoordinator().supportsLdac(getDevice()))
            types.add(MiscConfigType.LDAC);
        if (getCoordinator().supportsGameMode(getDevice()))
            types.add(MiscConfigType.GAME_MODE);
        if (getCoordinator().supportsSpatialAudio(getDevice()))
            types.add(MiscConfigType.SPATIAL_AUDIO);
        if (types.isEmpty())
            return;

        byte[] payload = new byte[1 + types.size()];
        payload[0] = (byte) types.size();

        int i = 1;
        for (MiscConfigType type : types) {
            payload[i++] = (byte) type.getCode();
        }
        sendCommand(builder, OppoCommand.MISC_CONFIG_REQ, payload);
    }

    private void parseMiscConfig(final byte[] payload) {
        final ByteBuffer buf = ByteBuffer.wrap(payload);
        if (buf.remaining() < 2) {
            LOG.warn("Unexpected misc config ret payload remaining {}, expected >=2", buf.remaining());
            return;
        }

        final int zero = buf.get();
        final int numTypes = buf.get() & 0xFF;
        if (buf.remaining() < (numTypes * 2)) {
            LOG.warn("Unexpected misc config ret payload remaining {}, expected >= {}", buf.remaining(),
                    (numTypes * 2));
            return;
        }

        final GBDeviceEventUpdatePreferences eventUpdatePreferences = new GBDeviceEventUpdatePreferences();
        for (int i = 0; i < numTypes; i++) {
            if (buf.remaining() < 2) {
                LOG.warn("Unexpected misc config ret payload remaining {}, expected >= 2", buf.remaining());
                break;
            }

            final int typeCode = buf.get() & 0xFF;
            final int valueCode = buf.get() & 0xFF;
            final boolean isEnabled = (valueCode == 1);

            final MiscConfigType type = MiscConfigType.fromCode(typeCode);
            if (type == null) {
                LOG.warn("Unknown misc config type code {}", typeCode);
                continue;
            }

            switch (type) {
                case LDAC -> {
                    LOG.debug("Got misc config for LDAC = {}", isEnabled);
                    eventUpdatePreferences.withPreference(
                            OppoHeadphonesPreferences.LDAC,
                            isEnabled);
                }
                case MULTIPOINT -> {
                    LOG.debug("Got misc config for MULTIPOINT = {}",
                            isEnabled);
                    broadcastMultipointStatus(isEnabled);
                }
                case GAME_MODE -> {
                    LOG.debug("Got misc config for GAME_MODE = {}", isEnabled);
                    eventUpdatePreferences
                            .withPreference(
                                    OppoHeadphonesPreferences.GAME_MODE,
                                    isEnabled);
                }
                case SPATIAL_AUDIO -> {
                    LOG.debug("Got misc config for SPATIAL_AUDIO = {}", isEnabled);
                    eventUpdatePreferences
                            .withPreference(
                                    OppoHeadphonesPreferences.SPATIAL_AUDIO,
                                    isEnabled);
                }
                default -> LOG.warn("Unknown misc config type code 0x{}", intToHex(typeCode, 2));
            }
        }
        evaluateGBDeviceEvent(eventUpdatePreferences);
    }

    private void ancModeSet() {
        final String valuePreference = getDevicePrefs().getString(OppoHeadphonesPreferences.ANC_MODE, null);
        AncConfigValue value = AncConfigValue.fromPreference(valuePreference);
        if (value == null) {
            LOG.warn("Unknown ANC prefId = \"{}\"", valuePreference);
            return;
        }

        int code = value.getCode();
        if (getCoordinator().supportsAncLevel(getDevice()) && value == AncConfigValue.ON) {
            final String valueLevelPreference = getDevicePrefs().getString(OppoHeadphonesPreferences.ANC_LEVEL, null);
            AncConfigValue.Level valueLevel = AncConfigValue.Level.fromPreference(valueLevelPreference);
            if (valueLevel == null) {
                LOG.warn("Unknown ANC level prefId = \"{}\"", valueLevelPreference);
                return;
            }
            code = valueLevel.getCode();
            LOG.debug("Sending ANC value = ON {}", valueLevel);
        } else {
            LOG.debug("Sending ANC value = {}", value);
        }
        ancConfigSet(AncConfigType.MODE, code);
    }

    private void touchAncCycleModesSet() {
        final Set<String> valuePreferences = getDevicePrefs().getStringSet(
                OppoHeadphonesPreferences.TOUCH_ANC_CYCLE_MODES, Collections.emptySet());
        final EnumSet<AncConfigValue> values = AncConfigValue.fromPreferences(valuePreferences);
        if (values.size() < 2) {
            LOG.warn("ANC cycle must contain at least 2 values. Current selection: {}", values);
            final String message = getContext()
                    .getString(nodomain.freeyourgadget.gadgetbridge.R.string.select_at_least_option, 2);
            GB.toast(getContext(), message, Toast.LENGTH_LONG, GB.WARN);
            ancConfigGet();
            return;
        }

        LOG.debug("Sending ANC touch cycle values = {}", values);
        final int mask = AncConfigValue.toMask(values);
        ancConfigSet(AncConfigType.TOUCH_CYCLE_MODES, mask);
    }

    private void ancConfigSet(final AncConfigType type, final int value) {
        final byte[] payload = new byte[] {
                (byte) type.getCode(),
                (byte) 0x01,
                (byte) value
        };
        sendCommand(OppoCommand.ANC_CONFIG_SET, payload);
    }

    private void ancConfigGet() {
        Consumer<AncConfigType> sendAncConfig = (configType) -> {
            byte[] payload = new byte[] {
                    (byte) configType.getCode(),
                    (byte) 0x01,
            };
            sendCommand(OppoCommand.ANC_CONFIG_REQ, payload);
        };

        sendAncConfig.accept(AncConfigType.MODE);
        sendAncConfig.accept(AncConfigType.TOUCH_CYCLE_MODES);
    }

    private void parseAncConfig(final byte[] payload) {
        final ByteBuffer buf = ByteBuffer.wrap(payload);
        if (buf.remaining() != 4) {
            LOG.warn("Unexpected anc config ret payload remaining {}, expected 4", buf.remaining());
            return;
        }

        final GBDeviceEventUpdatePreferences event = new GBDeviceEventUpdatePreferences();
        final int zero = buf.get();
        final int typeCode = buf.get() & 0xFF;
        final int one = buf.get();
        final int valueCode = buf.get() & 0xff;

        final AncConfigType type = AncConfigType.fromCode(typeCode);
        if (type == null) {
            LOG.warn("Unknown anc type code 0x{}", intToHex(typeCode, 2));
            return;
        }

        switch (type) {
            case MODE: {
                if (getCoordinator().supportsAncLevel(getDevice())) {
                    AncConfigValue.Level valueLevel = AncConfigValue.Level.fromCode(valueCode);
                    if (valueLevel != null) {
                        LOG.debug("Got anc config for {} = ON {}", type, valueLevel);
                        event.withPreference(OppoHeadphonesPreferences.ANC_LEVEL, valueLevel.getPreference());
                        event.withPreference(OppoHeadphonesPreferences.ANC_MODE, AncConfigValue.ON.getPreference());
                        break;
                    }
                }
                final AncConfigValue value = AncConfigValue.fromCode(valueCode);
                if (value == null) {
                    LOG.warn("Unknown anc value code 0x{}", intToHex(valueCode, 2));
                    break;
                }

                LOG.debug("Got anc config for {} = {}", type, value);
                event.withPreference(OppoHeadphonesPreferences.ANC_MODE, value.getPreference());
                break;
            }
            case TOUCH_CYCLE_MODES: {
                final EnumSet<AncConfigValue> values = AncConfigValue.fromMask(valueCode);
                if (values.isEmpty()) {
                    LOG.warn("Unknown anc value mask 0x{}", intToHex(valueCode, 2));
                    break;
                }
                final Set<String> valuePrefIds = AncConfigValue.toPreferences(values);
                LOG.debug("Got anc config for {} = {}", type, valuePrefIds);
                event.withPreference(OppoHeadphonesPreferences.TOUCH_ANC_CYCLE_MODES, valuePrefIds);
                break;
            }
            default: {
                LOG.debug("Unknown anc type code {}", typeCode);
                break;
            }
        }
        evaluateGBDeviceEvent(event);
    }

    private void multipointSet(final boolean isEnabled) {
        LOG.debug("Sending MULTIPOINT = {}", isEnabled);
        miscConfigSet(MiscConfigType.MULTIPOINT, isEnabled);
        broadcastMultipointStatus(isEnabled);
    }

    private void multipointGet() {
        LOG.info("Requesting multipoint status");
        final byte[] payload = new byte[] {
                (byte) 0x01,
                (byte) MiscConfigType.MULTIPOINT.getCode()
        };
        sendCommand(OppoCommand.MISC_CONFIG_REQ, payload);
    }

    private void parseMultipointDevices(final byte[] payload) {
        final ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        if (buf.remaining() < 2) {
            LOG.warn("Unexpected multipoint devices ret payload remaining: {}, expected >=2", buf.remaining());
            return;
        }

        final byte zero = buf.get();
        final int devicesCount = buf.get() & 0xFF;

        LOG.debug("Got {} multipoint devices", devicesCount);
        ArrayList<MultipointDevice> devices = new ArrayList<>();

        for (int i = 0; i < devicesCount; i++) {
            if (buf.remaining() < 10) {
                LOG.warn("Unexpected multipoint devices ret payload remaining: {}, expected >=10", buf.remaining());
                return;
            }

            byte[] macBytes = new byte[6];
            buf.get(macBytes);
            if (getCoordinator().multipointMacOrder(getDevice()) == ByteOrder.LITTLE_ENDIAN) {
                macBytes = bytesReverse(macBytes);
            }

            StringBuilder sb = new StringBuilder();
            for (int b = 0; b < macBytes.length; b++) {
                sb.append(String.format("%02X", macBytes[b]));
                if (b < macBytes.length - 1) {
                    sb.append(":");
                }
            }
            String macAddress = sb.toString();

            final int deviceType = buf.get();
            final boolean isConnected = (buf.get() == 2);
            final boolean isSelf = (buf.get() == 1);

            int nameLength = buf.get() & 0xFF;
            if (buf.remaining() < nameLength) {
                LOG.warn("Unexpected multipoint devices ret payload remaining: {}, expected >={}", buf.remaining(),
                        nameLength);
                return;
            }

            byte[] nameBytes = new byte[nameLength];
            buf.get(nameBytes);
            final String deviceName = new String(nameBytes, StandardCharsets.UTF_8);

            LOG.debug("Device {}: {} ({})", deviceName, macAddress, isConnected);
            devices.add(new MultipointDevice(macAddress, deviceName, isConnected));
        }
        broadcastMultipointList(devices);
    }

    private void multipointDevicesGet() {
        LOG.info("Requesting paired devices");
        sendCommand(OppoCommand.MULTIPOINT_DEVICES_REQ, null);
    }

    private void multipointDevicesSet(String deviceAddress, boolean isConnect) {
        LOG.info("Connecting to {}", deviceAddress);

        byte[] macAddress = StringUtils.hexToBytes(deviceAddress.replace(":", ""));
        if (macAddress.length != 6) {
            LOG.warn("Unexpected MAC Address length: {}, expected 6", macAddress.length);
            return;
        }

        if (getCoordinator().multipointMacOrder(getDevice()) == ByteOrder.LITTLE_ENDIAN) {
            macAddress = bytesReverse(macAddress);
        }

        final ByteBuffer buf = ByteBuffer.allocate(8);
        buf.put((byte) 0x01);
        buf.put(macAddress);
        buf.put((byte) (isConnect ? 0x01 : 0x00));
        sendCommand(OppoCommand.MULTIPOINT_DEVICES_SET, buf.array());
    }

    @Override
    public void onFindDevice(boolean start) {
        sendCommand(OppoCommand.FIND_DEVICE_REQ, new byte[] { (byte) (start ? 0x01 : 0x00) });
    }

    private void setupMultipointBroadcastReceiver() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(MultipointPairingActivity.ACTION_MULTIPOINT_ENABLE);
        intentFilter.addAction(MultipointPairingActivity.ACTION_MULTIPOINT_DISABLE);
        intentFilter.addAction(MultipointPairingActivity.ACTION_MULTIPOINT_GET_DEVICES);
        intentFilter.addAction(MultipointPairingActivity.ACTION_MULTIPOINT_GET_STATUS);
        intentFilter.addAction(MultipointPairingActivity.ACTION_MULTIPOINT_CONNECT_DEVICE);
        intentFilter.addAction(MultipointPairingActivity.ACTION_MULTIPOINT_DISCONNECT_DEVICE);

        LocalBroadcastManager.getInstance(getContext()).registerReceiver(multipointBroadcastReceiver, intentFilter);
    }

    private final BroadcastReceiver multipointBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }

            GBDevice device = intent.getParcelableExtra(GBDevice.EXTRA_DEVICE);
            if (device == null || !device.getAddress().equals(gbDevice.getAddress())) {
                return;
            }

            String action = intent.getAction();
            if (action == null) {
                return;
            }

            switch (action) {
                case MultipointPairingActivity.ACTION_MULTIPOINT_ENABLE -> multipointSet(true);
                case MultipointPairingActivity.ACTION_MULTIPOINT_DISABLE -> multipointSet(false);
                case MultipointPairingActivity.ACTION_MULTIPOINT_GET_STATUS -> multipointGet();
                case MultipointPairingActivity.ACTION_MULTIPOINT_GET_DEVICES -> multipointDevicesGet();
                case MultipointPairingActivity.ACTION_MULTIPOINT_CONNECT_DEVICE -> {
                    final String deviceAddress = intent.getStringExtra(MultipointPairingActivity.EXTRA_DEVICE_ADDRESS);
                    multipointDevicesSet(deviceAddress, true);
                }
                case MultipointPairingActivity.ACTION_MULTIPOINT_DISCONNECT_DEVICE -> {
                    final String deviceAddress = intent.getStringExtra(MultipointPairingActivity.EXTRA_DEVICE_ADDRESS);
                    multipointDevicesSet(deviceAddress, false);
                }
                default -> LOG.warn("Unknown action {}", action);
            }
        }
    };

    private void broadcastMultipointStatus(boolean isEnabled) {
        final Intent intent = new Intent(MultipointPairingActivity.ACTION_MULTIPOINT_STATUS_UPDATE);
        intent.putExtra(GBDevice.EXTRA_DEVICE, getDevice());
        intent.putExtra(MultipointPairingActivity.EXTRA_MULTIPOINT_ENABLED, isEnabled);
        LocalBroadcastManager.getInstance(getContext()).sendBroadcast(intent);
    }

    private void broadcastMultipointList(List<MultipointDevice> devices) {
        Intent intent = new Intent(MultipointPairingActivity.ACTION_MULTIPOINT_DEVICE_LIST);
        intent.putExtra(GBDevice.EXTRA_DEVICE, getDevice());
        intent.putParcelableArrayListExtra(
                MultipointPairingActivity.EXTRA_DEVICE_LIST,
                new ArrayList<>(devices));
        LocalBroadcastManager.getInstance(getContext()).sendBroadcast(intent);
    }

    private void sendCommand(final TransactionBuilder builder, final OppoCommand command, @Nullable byte[] payload) {
        if (payload == null) {
            payload = new byte[0];
        }

        final ByteBuffer buf = ByteBuffer.allocate(9 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(CMD_PREAMBLE);
        buf.put((byte) (buf.limit() - 2));
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.putShort(command.getCode());
        buf.put((byte) seqNum++);

        buf.putShort((short) payload.length);
        buf.put(payload);
        builder.write(buf.array());
    }

    private void sendCommand(final OppoCommand command, @Nullable byte[] payload) {
        final TransactionBuilder builder = createTransactionBuilder(command.name().toLowerCase());
        sendCommand(builder, command, payload);
        builder.queue();
    }

    private OppoHeadphonesCoordinator getCoordinator() {
        return (OppoHeadphonesCoordinator) getDevice().getDeviceCoordinator();
    }

    private static String intToHex(final int code, final int len) {
        return String.format(Locale.ROOT, "%0" + len + "x", code);
    }

    @NonNull
    private static byte[] bytesReverse(@NonNull final byte[] bytes) {
        byte[] reversed = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            reversed[i] = bytes[bytes.length - 1 - i];
        }
        return reversed;
    }
}
