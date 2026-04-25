/*  Copyright (C) 2026 Viktor Karpov

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.miscale;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.devices.miscale.MiScaleSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.miscale.MiScaleS400Coordinator;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.MiScaleWeightSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.AbstractDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class MiScaleS400DeviceSupport extends AbstractDeviceSupport {
    public static final String PREF_MISCALE_S400_BIND_KEY = "pref_miscale_s400_bind_key";

    private static final Logger LOG = LoggerFactory.getLogger(MiScaleS400DeviceSupport.class);
    private static final ParcelUuid BODY_COMPOSITION_SERVICE_UUID = new ParcelUuid(UUID.fromString("0000181b-0000-1000-8000-00805f9b34fb"));
    private static final ParcelUuid XIAOMI_SERVICE_DATA_UUID = new ParcelUuid(UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb"));
    private static final long SCAN_TIMEOUT_MS = 60_000;
    private static final long DUPLICATE_WINDOW_MS = 10_000;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private BluetoothLeScanner scanner;
    private boolean scanning = false;
    private byte[] lastServiceData;
    private byte[] lastUnhandledAdvertisement;
    private long lastMeasurementTimestamp = 0;
    private boolean warnedMissingBindKey = false;

    private final Runnable timeoutRunnable = () -> {
        LOG.debug("S400 scan timed out for {}", getDevice().getAddress());
        stopScan(GBDevice.State.NOT_CONNECTED);
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(final int callbackType, final ScanResult result) {
            handleScanResult(result);
        }

        @Override
        public void onScanFailed(final int errorCode) {
            LOG.error("S400 scan failed: {}", errorCode);
            stopScan(GBDevice.State.NOT_CONNECTED);
        }
    };

    @Override
    public boolean connect() {
        if (scanning) {
            return true;
        }

        final BluetoothAdapter adapter = getBluetoothAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            return false;
        }

        if (!hasScanPermission()) {
            LOG.warn("Missing BLE scan permission for S400");
            return false;
        }

        final BluetoothManager bluetoothManager = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        scanner = bluetoothManager != null ? bluetoothManager.getAdapter().getBluetoothLeScanner() : null;
        if (scanner == null) {
            LOG.warn("Could not get BluetoothLeScanner for S400");
            return false;
        }

        final String bindKey = getDevicePrefs().getString(PREF_MISCALE_S400_BIND_KEY, null);
        if (!MiScaleS400Decryptor.isValidBindKey(bindKey)) {
            if (!warnedMissingBindKey) {
                warnedMissingBindKey = true;
                GB.toast(getContext().getString(R.string.miscale_s400_bind_key_missing), Toast.LENGTH_LONG, GB.WARN);
            }
            return false;
        }

        final List<ScanFilter> filters = Collections.singletonList(
                new ScanFilter.Builder()
                        .setDeviceAddress(getDevice().getAddress())
                        .build()
        );

        final ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build();

        try {
            LOG.debug("Starting S400 scan for {}", getDevice().getAddress());
            scanner.startScan(filters, settings, scanCallback);
            scanning = true;
            getDevice().setUpdateState(GBDevice.State.WAITING_FOR_SCAN, getContext());
            GB.toast(getContext().getString(R.string.miscale_s400_step_on_scale), Toast.LENGTH_SHORT, GB.INFO);
            handler.removeCallbacks(timeoutRunnable);
            handler.postDelayed(timeoutRunnable, SCAN_TIMEOUT_MS);
            return true;
        } catch (final SecurityException e) {
            LOG.error("Unable to start BLE scan for S400", e);
            return false;
        }
    }

    @Override
    public void dispose() {
        stopScan(GBDevice.State.NOT_CONNECTED);
    }

    @Override
    public boolean useAutoConnect() {
        return false;
    }

    private boolean hasScanPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || ActivityCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }

        return ActivityCompat.checkSelfPermission(getContext(), Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    private void stopScan(final GBDevice.State finalState) {
        handler.removeCallbacks(timeoutRunnable);

        if (scanner != null && scanning && hasScanPermission()) {
            try {
                scanner.stopScan(scanCallback);
            } catch (final Exception e) {
                LOG.debug("Ignoring stopScan exception", e);
            }
        }

        scanning = false;
        getDevice().setUpdateState(finalState, getContext());
    }

    private void handleScanResult(final ScanResult result) {
        final ScanRecord scanRecord = result.getScanRecord();
        if (scanRecord == null) {
            LOG.debug("Ignoring S400 scan result without scan record");
            return;
        }

        final byte[] serviceData = extractServiceData(scanRecord);
        if (serviceData == null) {
            logUnhandledAdvertisement(result, scanRecord);
            return;
        }

        final long now = System.currentTimeMillis();
        if (lastServiceData != null && Arrays.equals(lastServiceData, serviceData) && now - lastMeasurementTimestamp < DUPLICATE_WINDOW_MS) {
            return;
        }

        final String bindKey = getDevicePrefs().getString(PREF_MISCALE_S400_BIND_KEY, null);
        final MiScaleS400Decryptor.Measurement measurement = MiScaleS400Decryptor.decrypt(serviceData, getDevice().getAddress(), bindKey);
        if (measurement == null) {
            LOG.debug("Failed to decrypt S400 payload from {}", result.getDevice().getAddress());
            return;
        }

        lastServiceData = serviceData.clone();
        lastMeasurementTimestamp = now;

        LOG.debug("Received S400 measurement from {}: weight={}kg", result.getDevice().getAddress(), measurement.getWeightKg());

        try (DBHandler db = GBApplication.acquireDB()) {
            final MiScaleSampleProvider provider = new MiScaleSampleProvider(getDevice(), db.getDaoSession());
            final Long userId = DBHelper.getUser(db.getDaoSession()).getId();
            final Long deviceId = DBHelper.getDevice(getDevice(), db.getDaoSession()).getId();

            provider.addSample(new MiScaleWeightSample(now, deviceId, userId, measurement.getWeightKg()));

            if (measurement.getHeartRate() != null) {
                db.getDaoSession().getGenericHeartRateSampleDao().insertOrReplace(
                        new GenericHeartRateSample(now, deviceId, userId, measurement.getHeartRate())
                );
            }
        } catch (final Exception e) {
            LOG.error("Error saving S400 measurement", e);
        }

        MiScaleS400Coordinator.updateStoredMeasurementInfo(getDevice(), now, measurement.getWeightKg(), measurement.getHeartRate());
        getDevice().sendDeviceUpdateIntent(getContext());
        GB.toast(buildMeasurementToast(measurement), Toast.LENGTH_LONG, GB.INFO);

        stopScan(GBDevice.State.NOT_CONNECTED);
    }

    private String buildMeasurementToast(final MiScaleS400Decryptor.Measurement measurement) {
        final StringBuilder sb = new StringBuilder(getContext().getString(R.string.weight_kg, measurement.getWeightKg()));

        if (measurement.getHeartRate() != null) {
            sb.append("  ").append(getContext().getString(R.string.bpm_value_unit, measurement.getHeartRate()));
        }

        return sb.toString();
    }

    private byte[] extractServiceData(final ScanRecord scanRecord) {
        final byte[] xiaomiServiceData = scanRecord.getServiceData(XIAOMI_SERVICE_DATA_UUID);
        if (xiaomiServiceData != null && xiaomiServiceData.length >= 24) {
            final byte[] payload = new byte[xiaomiServiceData.length + 2];
            payload[0] = (byte) 0x95;
            payload[1] = (byte) 0xfe;
            System.arraycopy(xiaomiServiceData, 0, payload, 2, xiaomiServiceData.length);
            return payload;
        }

        final byte[] directServiceData = scanRecord.getServiceData(BODY_COMPOSITION_SERVICE_UUID);
        if (directServiceData != null && directServiceData.length >= 24) {
            return directServiceData;
        }

        final byte[] rawBytes = scanRecord.getBytes();
        if (rawBytes == null) {
            return null;
        }

        int offset = 0;
        while (offset < rawBytes.length) {
            final int fieldLength = rawBytes[offset] & 0xff;
            if (fieldLength == 0) {
                break;
            }

            final int nextOffset = offset + fieldLength + 1;
            if (nextOffset > rawBytes.length || fieldLength < 3) {
                break;
            }

            final int fieldType = rawBytes[offset + 1] & 0xff;
            if (fieldType == 0x16 && rawBytes[offset + 2] == (byte) 0x95 && rawBytes[offset + 3] == (byte) 0xfe) {
                final int payloadLength = fieldLength - 1;
                final byte[] payload = new byte[payloadLength];
                System.arraycopy(rawBytes, offset + 2, payload, 0, payloadLength);
                return payload;
            }

            offset = nextOffset;
        }

        return null;
    }

    private void logUnhandledAdvertisement(final ScanResult result, final ScanRecord scanRecord) {
        final byte[] rawBytes = scanRecord.getBytes();
        if (rawBytes == null) {
            LOG.debug("Ignoring S400 advertisement without body composition service data from {} and without raw bytes", result.getDevice().getAddress());
            return;
        }

        if (lastUnhandledAdvertisement != null && Arrays.equals(lastUnhandledAdvertisement, rawBytes)) {
            return;
        }

        lastUnhandledAdvertisement = rawBytes.clone();
        LOG.debug("Ignoring S400 advertisement without body composition service data from {}", result.getDevice().getAddress());
    }
}
