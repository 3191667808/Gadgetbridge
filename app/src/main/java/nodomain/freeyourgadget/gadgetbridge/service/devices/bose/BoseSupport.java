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

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.activities.multipoint.MultipointPairingActivity;
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

    private final BroadcastReceiver multipointReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final GBDevice device = intent.getParcelableExtra(GBDevice.EXTRA_DEVICE);
            if (device == null || !getDevice().getAddress().equalsIgnoreCase(device.getAddress())) {
                return;
            }

            switch (intent.getAction()) {
                case MultipointPairingActivity.ACTION_MULTIPOINT_START_PAIRING:
                    final boolean enabled = intent.getBooleanExtra(
                            MultipointPairingActivity.EXTRA_PAIRING_ENABLED, false);
                    sendMultipointCommand(enabled ? "enter pairing mode" : "leave pairing mode",
                            setPairingMode(enabled));
                    broadcastMultipointPairing(enabled);
                    break;
                default:
                    break;
            }
        }
    };

    public BoseSupport() {
        super(LOG, 1024);
        addSupportedService(UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"));
    }

    @Override
    public void setContext(@NonNull final GBDevice gbDevice,
                           @NonNull final BluetoothAdapter btAdapter,
                           @NonNull final Context context) {
        super.setContext(gbDevice, btAdapter, context);
        final IntentFilter multipointFilter = new IntentFilter();
        multipointFilter.addAction(MultipointPairingActivity.ACTION_MULTIPOINT_START_PAIRING);
        LocalBroadcastManager.getInstance(context).registerReceiver(multipointReceiver, multipointFilter);
    }

    @Override
    public void dispose() {
        LocalBroadcastManager.getInstance(getContext()).unregisterReceiver(multipointReceiver);
        super.dispose();
    }

    @Override
    public boolean useAutoConnect() {
        return true;
    }

    @Override
    protected TransactionBuilder initializeDevice(final TransactionBuilder builder) {
        final byte[] connectPayload = connectHandshake();
        final byte[] notificationPayload = enableNotificationsForFunctionBlocks(BLOCK_STATUS,
                BLOCK_DEVICE_MANAGEMENT);
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

        switch (block) {
            case BLOCK_PRODUCT_INFO:
                if (function == FUNCTION_INIT_HANDSHAKE) {
                    LOG.debug("Bose init handshake response: {}", StringUtils.bytesToHex(payload));
                }
                break;
            case BLOCK_STATUS:
                if (function == FUNCTION_BATTERY && operator == OP_STATUS) {
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
                break;
            case BLOCK_DEVICE_MANAGEMENT:
                handleDeviceManagement(function, operator, payload);
                break;
            default:
                LOG.debug("Ignoring Bose frame from unknown block 0x{}", Integer.toHexString(block));
                break;
        }
    }

    private void handleDeviceManagement(final int function, final int operator, final byte[] payload) {
        switch (function) {
            case FUNCTION_PAIRING_MODE:
                LOG.info("Bose pairing mode response: {}", StringUtils.bytesToHex(payload));
                break;
            default:
                break;
        }
    }

    private void sendMultipointCommand(final String name, final byte[] command) {
        final TransactionBuilder builder = createTransactionBuilder(name);
        builder.write(command);
        builder.queue();
    }

    private void broadcastMultipointPairing(final boolean enabled) {
        final Intent intent = new Intent(MultipointPairingActivity.ACTION_MULTIPOINT_PAIRING_UPDATE);
        intent.putExtra(GBDevice.EXTRA_DEVICE, getDevice());
        intent.putExtra(MultipointPairingActivity.EXTRA_PAIRING_ENABLED, enabled);
        LocalBroadcastManager.getInstance(getContext()).sendBroadcast(intent);
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
