/*  Copyright (C) 2021-2026 Arjan Schrijver, Daniel Dakhno, Dominic Monroe

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.bose;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.service.AbstractHeadphoneBTBRDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btbr.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.util.StringUtils;

import static nodomain.freeyourgadget.gadgetbridge.service.devices.bose.BoseProtocol.*;

public class BoseSupport extends AbstractHeadphoneBTBRDeviceSupport {
    public static final Logger LOG = LoggerFactory.getLogger(BoseSupport.class);

    private final BoseFrameParser frameParser = new BoseFrameParser();

    public BoseSupport() {
        super(LOG, 1024);
        addSupportedService(UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"));
    }

    @Override
    public void setContext(@NonNull final GBDevice gbDevice,
                           @NonNull final android.bluetooth.BluetoothAdapter btAdapter,
                           @NonNull final android.content.Context context) {
        super.setContext(gbDevice, btAdapter, context);
    }

    @Override
    public boolean useAutoConnect() {
        return true;
    }

    @Override
    protected TransactionBuilder initializeDevice(final TransactionBuilder builder) {
        final byte[] connectPayload = connectHandshake();
        final byte[] notificationPayload = enableNotificationsForFunctionBlocks(BLOCK_STATUS);
        final byte[] batteryPayload = getBattery();
        for (final byte[] payload : new byte[][]{connectPayload, notificationPayload, encodeAnr(), batteryPayload}) {
            builder.write(payload);
        }

        getDevice().setFirmwareVersion("0");

        builder.setDeviceState(GBDevice.State.INITIALIZED);

        return builder;
    }

    @Override
    public void onSocketRead(final byte[] bytes) {
        LOG.debug("Bose RX: {}", StringUtils.bytesToHex(bytes));

        final List<byte[]> frames = frameParser.feed(bytes);
        for (final byte[] frame : frames) {
            try {
                handleFrame(frame);
            } catch (final Exception e) {
                LOG.error("Failed to handle Bose frame " + StringUtils.bytesToHex(frame), e);
            }
        }
    }

    private void handleFrame(final byte[] frame) {
        final int block = frame[0] & 0xFF;
        final int function = frame[1] & 0xFF;
        final int operator = frame[2] & 0x0F;
        final byte[] payload = Arrays.copyOfRange(frame, 4, frame.length);

        if (operator == OP_ERROR) {
            LOG.warn("Bose BMAP error on block 0x{} function 0x{}: {}",
                    Integer.toHexString(block), Integer.toHexString(function),
                    StringUtils.bytesToHex(payload));
            return;
        }

        if (block == BLOCK_PRODUCT_INFO && function == FUNCTION_INIT_HANDSHAKE) {
            LOG.debug("Bose init handshake response: {}", StringUtils.bytesToHex(payload));
            return;
        }

        if (block == BLOCK_STATUS && function == FUNCTION_BATTERY && operator == OP_STATUS) {
            final int level = decodeBatteryLevel(payload);
            if (level >= 0 && level <= 100) {
                final GBDeviceEventBatteryInfo batteryInfo = new GBDeviceEventBatteryInfo();
                batteryInfo.level = (short) level;
                batteryInfo.state = BatteryState.BATTERY_NORMAL;
                evaluateGBDeviceEvent(batteryInfo);
            } else {
                LOG.debug("Ignoring implausible battery level: {}", level);
            }
        }
    }

    @Override
    public void onSendConfiguration(@NonNull final String config) {
        final TransactionBuilder builder = createTransactionBuilder("set noise cancelling");
        if (DeviceSettingsPreferenceConst.PREF_QC35_NOISE_CANCELLING_LEVEL.equals(config)) {
            builder.write(encodeAnr());
        } else {
            return;
        }
        builder.queue();
    }

    @NonNull
    private byte[] encodeAnr() {
        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        final int level = prefs.getInt(DeviceSettingsPreferenceConst.PREF_QC35_NOISE_CANCELLING_LEVEL, 0);
        return setAnr(level);
    }
}
