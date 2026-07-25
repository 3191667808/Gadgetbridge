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

import android.os.Handler;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.Set;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Queue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.StringUtils;
import nodomain.freeyourgadget.gadgetbridge.util.LEB128Utils;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.service.btbr.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.BLETypeConversions;
import nodomain.freeyourgadget.gadgetbridge.service.AbstractHeadphoneBTBRDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.OppoCommand;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.OppoMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.TouchConfigType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.TouchConfigSide;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.TouchConfigValue;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.MiscConfigType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.AncConfigType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.AncConfigValue;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.SubscriptionType;
import nodomain.freeyourgadget.gadgetbridge.devices.oppo.OppoHeadphonesCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.oppo.OppoHeadphonesPreferences;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventUpdatePreferences;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventFindPhone;
import nodomain.freeyourgadget.gadgetbridge.activities.multipoint.MultipointDevice;
import nodomain.freeyourgadget.gadgetbridge.activities.multipoint.MultipointPairingActivity;

public class OppoHeadphonesSupport extends AbstractHeadphoneBTBRDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(OppoHeadphonesSupport.class);
    private static final int MAX_MTU = 2048;
    private static final UUID UUID_SERVICE_STANDARD = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_SERVICE_OPPO = UUID.fromString("0000079a-d102-11e1-9b23-00025b00a5a5");
    public static final byte CMD_PREAMBLE = (byte) 0xAA;
    public static final short CMD_MASK_RESPONSE = (short) 0x8000;

    private final ByteBuffer packetBuffer = ByteBuffer.allocate(MAX_MTU).order(ByteOrder.LITTLE_ENDIAN);
    private final AtomicInteger seqNum = new AtomicInteger(0);

    private final Queue<OppoMessage> messageQueue = new ConcurrentLinkedQueue<>();
    private OppoMessage pendingMessage = null;
    private final AtomicInteger timeoutRetries = new AtomicInteger(0);
    private final Handler timeoutHandler = new Handler();

    private AncConfig ancConfig;

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
        timeoutHandler.removeCallbacksAndMessages(null);
        messageQueue.clear();
        pendingMessage = null;
        timeoutRetries.set(0);
        seqNum.set(0);

        subscriptionSet();
        firmwareVersionGet();
        batteryGet();
        touchConfigGet();
        miscConfigGet();
        queueCommand(ancConfig.encodeGet());

        builder.setDeviceState(GBDevice.State.INITIALIZED);
        return builder;
    }

    @Override
    public void setContext(@NonNull GBDevice gbDevice, @NonNull BluetoothAdapter btAdapter, @NonNull Context context) {
        super.setContext(gbDevice, btAdapter, context);
        this.ancConfig = new AncConfig(getContext(), getDevice());
        if (getCoordinator().supportsMultipoint(getDevice())) {
            setupMultipointBroadcastReceiver();
        }
    }

    @Override
    public void dispose() {
        synchronized (ConnectionMonitor) {
            timeoutHandler.removeCallbacksAndMessages(null);
            if (getCoordinator().supportsMultipoint(getDevice())) {
                LocalBroadcastManager.getInstance(getContext()).unregisterReceiver(multipointBroadcastReceiver);
            }
            super.dispose();
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

            int totalLength;
            try {
                totalLength = (int) LEB128Utils.decodeUnsigned(packetBuffer);
            } catch (BufferUnderflowException e) {
                packetBuffer.reset();
                break;
            }
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
                LOG.warn("Unknown command code 0x{}", numberToHex(code));
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

            boolean sendNext;
            try {
                handleCommand(command, payload);
                if (pendingMessage != null) {
                    sendNext = (pendingMessage.command().getCode() | CMD_MASK_RESPONSE) == command.getCode();
                } else {
                    sendNext = false;
                }
            } catch (Exception e) {
                LOG.error("Failed to handle command", e);
                sendNext = true;
            }

            if (sendNext) {
                sendNextCommand();
            }
        }

        packetBuffer.compact();
    }

    @Override
    public void onSendConfiguration(String config) {
        if (config.startsWith(OppoHeadphonesPreferences.TOUCH_PREFIX)) {
            touchConfigSet(config);
            return;
        }

        queueCommand(ancConfig.onSendConfiguration(config));

        switch (config) {
            case OppoHeadphonesPreferences.LDAC -> ldacSet();
            case OppoHeadphonesPreferences.GAME_MODE -> gameModeSet();
            case OppoHeadphonesPreferences.SPATIAL_AUDIO -> spatialAudioSet();
            case OppoHeadphonesPreferences.TOUCH_FIND_PHONE -> findPhoneSet();
        }
    }

    private void handleCommand(OppoCommand command, byte[] payload) {
        final ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);

        if (LOG.isTraceEnabled()) {
            LOG.debug("Handling command {} payload={}", command, StringUtils.bytesToHex(payload));
        }

        switch (command) {
            case BATTERY_RET -> {
                final byte zero = buf.get();
                if (zero != 0) {
                    LOG.warn("Unexpected non-zero byte 0x{} for {}", numberToHex(zero), command);
                    break;
                }

                evaluateGBDeviceEvents(new BatteryInfo(getContext()).decode(payload));
            }
            case SUBSCRIPTION_RET -> parseSubscription(payload);
            case FIRMWARE_RET -> {
                final byte zero = buf.get();
                if (zero != 0) {
                    LOG.warn("Unexpected non-zero byte 0x{} for {}", numberToHex(zero), command);
                    break;
                }

                evaluateGBDeviceEvents(new FirmwareInfo(getContext()).decode(payload));
            }
            case SUBSCRIPTION_ACK, TOUCH_CONFIG_ACK, MISC_CONFIG_ACK,
                    ANC_CONFIG_ACK, FIND_DEVICE_ACK, MULTIPOINT_DEVICES_ACK -> {
                final byte zero = buf.get();
                if (zero != 0) {
                    LOG.warn("Unexpected non-zero byte 0x{} for {}", numberToHex(zero), command);
                    break;
                }

                LOG.debug("Got {}", command);
            }
            case TOUCH_CONFIG_RET -> {
                final byte zero = buf.get();
                if (zero != 0) {
                    LOG.warn("Unexpected non-zero byte 0x{} for {}", numberToHex(zero), command);
                    break;
                }

                parseTouchConfig(payload);
                break;
            }
            case MISC_CONFIG_RET -> {
                final byte zero = buf.get();
                if (zero != 0) {
                    LOG.warn("Unexpected non-zero byte 0x{} for {}", numberToHex(zero), command);
                    break;
                }

                parseMiscConfig(payload);
            }
            case ANC_CONFIG_RET -> {
                final byte zero = buf.get();
                if (zero != 0) {
                    LOG.warn("Unexpected non-zero byte 0x{} for {}", numberToHex(zero), command);
                    break;
                }

                evaluateGBDeviceEvents(ancConfig.decode(payload));
            }
            case FIND_PHONE -> {
                LOG.debug("Got {}", command);
                final GBDeviceEventFindPhone event = new GBDeviceEventFindPhone();
                final int eventCode = buf.get();
                if (eventCode == 0x05) {
                  event.event = GBDeviceEventFindPhone.Event.START;
                } else if (eventCode == 0x06) {
                  event.event = GBDeviceEventFindPhone.Event.STOP;
                } else {
                    LOG.warn("Unexpected byte 0x{}", intToHex(eventCode, 2));
                }

                evaluateGBDeviceEvent(event);
            }
            case MULTIPOINT_DEVICES_RET -> {
                final byte zero = buf.get();
                if (zero != 0) {
                    LOG.warn("Unexpected non-zero byte 0x{} for {}", numberToHex(zero), command);
                    break;
                }

                parseMultipointDevices(payload);
            }
            default -> LOG.warn("Unhandled command {}", command);
        }

    }

    private void batteryGet() {
        queueCommand(OppoCommand.BATTERY_REQ, new byte[0]);
    }

    private void subscriptionSet() {
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
        queueCommand(OppoCommand.SUBSCRIPTION_SET, buf.array());
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
            LOG.warn("Unknown subcription type 0x{}", numberToHex(typeCode));
            return;
        }

        switch (type) {
            case BATTERY: {
                evaluateGBDeviceEvents(new BatteryInfo(getContext()).decode(payload));
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
                evaluateGBDeviceEvents(ancConfig.decode(payload));
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

    private void firmwareVersionGet() {
        queueCommand(OppoCommand.FIRMWARE_REQ, new byte[0]);
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
        queueCommand(OppoCommand.TOUCH_CONFIG_SET, buf.array());
    }

    private void touchConfigGet() {
        queueCommand(OppoCommand.TOUCH_CONFIG_REQ, new byte[] { 0x02, 0x03, 0x01 });
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
                LOG.warn("Unknown touch side code 0x{}", numberToHex(sideCode));
                continue;
            }
            if (type == null) {
                LOG.warn("Unknown touch type code 0x{}", numberToHex(typeCode));
                continue;
            }
            if (value == null) {
                LOG.warn("Unknown touch value code 0x{}", numberToHex(valueCode));
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
        LOG.debug("Sending SPATIAL_AUDIO = {}", isEnabled);
        miscConfigSet(MiscConfigType.SPATIAL_AUDIO, isEnabled);
    }

    private void findPhoneSet() {
        final boolean isEnabled = getDevicePrefs().getBoolean(OppoHeadphonesPreferences.TOUCH_FIND_PHONE, false);
        LOG.debug("Sending TOUCH_FIND_PHONE = {}", isEnabled);
        miscConfigSet(MiscConfigType.TOUCH_FIND_PHONE, isEnabled);
    }

    private void miscConfigSet(final MiscConfigType type, final boolean isEnabled) {
        final byte[] payload = new byte[] {
                (byte) type.getCode(),
                (byte) (isEnabled ? 0x01 : 0x00),
        };
        queueCommand(OppoCommand.MISC_CONFIG_SET, payload);
    }

    private void miscConfigGet() {
        final EnumSet<MiscConfigType> types = EnumSet.noneOf(MiscConfigType.class);
        if (getCoordinator().supportsLdac(getDevice()))
            types.add(MiscConfigType.LDAC);
        if (getCoordinator().supportsGameMode(getDevice()))
            types.add(MiscConfigType.GAME_MODE);
        if (getCoordinator().supportsSpatialAudio(getDevice()))
            types.add(MiscConfigType.SPATIAL_AUDIO);
        if (getCoordinator().supportsFindPhone(getDevice()))
            types.add(MiscConfigType.TOUCH_FIND_PHONE);
        if (types.isEmpty())
            return;

        byte[] payload = new byte[1 + types.size()];
        payload[0] = (byte) types.size();

        int i = 1;
        for (MiscConfigType type : types) {
            payload[i++] = (byte) type.getCode();
        }
        queueCommand(OppoCommand.MISC_CONFIG_REQ, payload);
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
                case TOUCH_FIND_PHONE -> {
                    LOG.debug("Got misc config for TOUCH_FIND_PHONE = {}", isEnabled);
                    eventUpdatePreferences
                            .withPreference(
                                    OppoHeadphonesPreferences.TOUCH_FIND_PHONE,
                                    isEnabled);
                }
                default -> LOG.warn("Unknown misc config type code 0x{}", numberToHex(typeCode));
            }
        }
        evaluateGBDeviceEvent(eventUpdatePreferences);
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
        queueCommand(OppoCommand.MISC_CONFIG_REQ, payload);
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
        queueCommand(OppoCommand.MULTIPOINT_DEVICES_REQ, new byte[0]);
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
        queueCommand(OppoCommand.MULTIPOINT_DEVICES_SET, buf.array());
    }

    @Override
    public void onFindDevice(boolean start) {
        queueCommand(OppoCommand.FIND_DEVICE_REQ, new byte[] { (byte) (start ? 0x01 : 0x00) });
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

    private void queueCommand(final OppoMessage message) {
        messageQueue.add(message);

        if (pendingMessage == null) {
            sendNextCommand();
        }
    }

    private void queueCommand(final List<OppoMessage> messages) {
        for (OppoMessage message : messages) {
            if (message != null) {
                queueCommand(message);
            }
        }
    }

    private void queueCommand(final OppoCommand command, final byte[] payload) {
        queueCommand(new OppoMessage(command, payload));
    }

    private void onCommandTimeout() {
        if (timeoutRetries.getAndIncrement() < 3) {
            LOG.warn("Timed out waiting for response, retrying attempt {}", timeoutRetries);
            if (pendingMessage != null) {
                sendMessage(pendingMessage);
                return;
            }
        }
        LOG.warn("Timed out waiting for response, giving up");
        sendNextCommand();
    }

    private void sendNextCommand() {
        timeoutHandler.removeCallbacksAndMessages(null);
        timeoutRetries.set(0);

        pendingMessage = messageQueue.poll();
        if (pendingMessage != null) {
            LOG.debug("Sending next command in queue: {}", pendingMessage.command());
            sendMessage(pendingMessage);
            return;
        }
        LOG.debug("No more commands in the queue");
    }

    private void sendMessage(OppoMessage message) {
        final TransactionBuilder builder = createTransactionBuilder(message.command().name().toLowerCase());
        builder.write(encodeCommand(message.command(), message.payload()));
        builder.queue();
        timeoutHandler.postDelayed(() -> onCommandTimeout(), 2000L);
    }

    byte[] encodeCommand(final OppoCommand command, final byte[] payload) {
        final int totalLength = 7 + payload.length;
        final byte[] totalLengthBytes = LEB128Utils.encodeUnsigned((long) totalLength);
        final ByteBuffer buf = ByteBuffer.allocate(1 + totalLengthBytes.length + totalLength)
                .order(ByteOrder.LITTLE_ENDIAN);
        buf.put(CMD_PREAMBLE);
        buf.put(totalLengthBytes);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.putShort(command.getCode());
        buf.put((byte) (seqNum.getAndIncrement() & 0xff));
        buf.putShort((short) payload.length);
        buf.put(payload);
        return buf.array();
    }

    private OppoHeadphonesCoordinator getCoordinator() {
        return (OppoHeadphonesCoordinator) getDevice().getDeviceCoordinator();
    }

    private void evaluateGBDeviceEvents(final List<GBDeviceEvent> events) {
        for (GBDeviceEvent event : events) {
            evaluateGBDeviceEvent(event);
        }
    }

    protected static String numberToHex(@NonNull final Number code) {
        long val = code.longValue();
        int byteSize = (code instanceof Byte) ? 1 : (code instanceof Short) ? 2 : (code instanceof Integer) ? 4 : 8;
        return String.format("%0" + (byteSize * 2) + "X", val & (0xFFFFFFFFFFFFFFFFL >>> (64 - byteSize * 8)));
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
