/*
    Copyright (C) 2026 Jonathan Styles

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
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package nodomain.freeyourgadget.gadgetbridge.service.devices.ihealthtrack;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Intent;
import android.widget.Toast;

import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericBloodPressureSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericBloodPressureSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.actions.SetDeviceStateAction;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.IntentListener;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.deviceinfo.DeviceInfo;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.deviceinfo.DeviceInfoProfile;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/**
 * iHealth Track (KN-550BT) blood pressure monitor.
 *
 * Custom (non-SIG) GATT service "com.jiuan.dev" with one write and one
 * notify characteristic. Every application payload is wrapped in a small
 * framed packet (0xB0 phone->device / 0xA0 device->phone, length, sequence
 * id, checksum) and chunked to 20 bytes per GATT write. Pairing requires an
 * XXTEA-based challenge/response using a static key that ships inside the
 * vendor app (see {@link XXTea2}) - it's obfuscation, not a real secret.
 *
 * Full protocol write-up: https://gist.github.com/jontyms/293edf1d088b07e9cdd051f4525be9fa
 */
public class IHealthTrackDeviceSupport extends AbstractBTLESingleDeviceSupport implements IntentListener {

    private static final Logger LOG = LoggerFactory.getLogger(IHealthTrackDeviceSupport.class);

    private static final UUID SERVICE_JIUAN_DEV =
            UUID.fromString("636f6d2e-6a69-7561-6e2e-646576000000"); // ASCII "com.jiuan.dev"

    private static final UUID CHAR_WRITE =
            UUID.fromString("7265632e-6a69-7561-6e2e-646576000000"); // ASCII "rec.jiuan.dev"

    private static final UUID CHAR_NOTIFY =
            UUID.fromString("7365642e-6a69-7561-6e2e-646576000000"); // ASCII "sed.jiuan.dev"

    private static final byte DEVICE_TYPE = (byte) 0xA1;

    private static final byte CMD_IDENTIFY = (byte) 0xFA;
    private static final byte CMD_AUTH_CHALLENGE = (byte) 0xFB;
    private static final byte CMD_AUTH_RESPONSE = (byte) 0xFC;
    private static final byte CMD_AUTH_SUCCESS = (byte) 0xFD;
    private static final byte CMD_AUTH_FAILURE = (byte) 0xFE;
    private static final byte CMD_BATTERY = 0x20;
    private static final byte CMD_TIME_SYNC = 0x21;
    private static final byte CMD_OFFLINE_COUNT = 0x40;
    private static final byte CMD_GET_OFFLINE_DATA = 0x4A;
    /**
     * "Transfer finished" - tells the device its unsynced queue was
     * received. Verified live: this clears BOTH the BLE offline queue (next
     * sync reports 0 records) AND the device's own on-screen recall/history
     * display - there is no separate permanent on-device archive, the phone
     * app is the archive. See PROTOCOL.md.
     */
    private static final byte CMD_TRANSFER_FINISHED = 0x47;

    private static final int WRITE_CHUNK_SIZE = 20;
    private static final int RECORD_LENGTH = 11;

    /** Static key baked into the vendor Android SDK - shared by every unit. */
    private static final byte[] STATIC_KEY = {
            25, 1, 7, -106, -14, 35, 26, 104, -117, 84, 52, 98, -116, 87, -21, 25
    };

    private int seqId = 1;
    private final ByteArrayOutputStream rxAssembly = new ByteArrayOutputStream();
    private final ByteArrayOutputStream offlineBuffer = new ByteArrayOutputStream();

    /** True from auth success until {@link #finishSync()} runs; used to close out the sync if the link drops mid-fetch. */
    private boolean syncInProgress = false;

    private final DeviceInfoProfile<IHealthTrackDeviceSupport> deviceInfoProfile;

    public IHealthTrackDeviceSupport() {
        super(LOG);
        addSupportedService(SERVICE_JIUAN_DEV);
        addSupportedService(GattService.UUID_SERVICE_DEVICE_INFORMATION);

        deviceInfoProfile = new DeviceInfoProfile<>(this);
        deviceInfoProfile.addListener(this);
        addSupportedProfile(deviceInfoProfile);
    }

    @Override
    public boolean useAutoConnect() {
        return false;
    }

    @Override
    protected TransactionBuilder initializeDevice(final TransactionBuilder builder) {
        LOG.info("Initializing iHealth Track (KN-550BT)");
        seqId = 1;
        rxAssembly.reset();
        offlineBuffer.reset();

        builder.add(new SetDeviceStateAction(getDevice(), GBDevice.State.INITIALIZING, getContext()));

        deviceInfoProfile.requestDeviceInfo(builder);

        final BluetoothGattCharacteristic notifyChar = getCharacteristic(CHAR_NOTIFY);
        if (notifyChar != null) {
            builder.notify(notifyChar, true);
        } else {
            LOG.warn("Notify characteristic not found!");
        }

        final byte[] r1 = new byte[16];
        new SecureRandom().nextBytes(r1);
        builder.writeChunkedData(getCharacteristic(CHAR_WRITE),
                buildFrame(concat(new byte[]{DEVICE_TYPE, CMD_IDENTIFY}, r1)), WRITE_CHUNK_SIZE);

        // Marks the init queue as done; the actual auth/sync handshake below
        // continues asynchronously as the device replies over CHAR_NOTIFY.
        builder.add(new SetDeviceStateAction(getDevice(), GBDevice.State.INITIALIZED, getContext()));

        return builder;
    }

    @Override
    public boolean onCharacteristicChanged(final BluetoothGatt gatt,
                                           final BluetoothGattCharacteristic characteristic,
                                           final byte[] value) {
        if (super.onCharacteristicChanged(gatt, characteristic, value)) {
            return true;
        }
        if (CHAR_NOTIFY.equals(characteristic.getUuid())) {
            handleNotification(value);
            return true;
        }
        LOG.debug("Unhandled characteristic: {} = {}", characteristic.getUuid(), GB.hexdump(value));
        return false;
    }

    @Override
    public void onConnectionStateChange(final BluetoothGatt gatt, final int status, final int newState) {
        super.onConnectionStateChange(gatt, status, newState);
        if (newState != BluetoothGatt.STATE_CONNECTED && syncInProgress) {
            // The device disconnects on its own once a full exchange is done, but if it drops
            // mid-fetch (out of range, low battery, etc.) we still need to close out the sync
            // so Gadgetbridge's UI doesn't hang waiting for it.
            LOG.warn("Disconnected mid-sync, signalling activity data finish");
            finishSync();
        }
    }

    @Override
    public void notify(final Intent intent) {
        if (DeviceInfoProfile.ACTION_DEVICE_INFO.equals(intent.getAction())) {
            final DeviceInfo info = intent.getParcelableExtra(DeviceInfoProfile.EXTRA_DEVICE_INFO);
            if (info != null) {
                final GBDeviceEventVersionInfo versionCmd = new GBDeviceEventVersionInfo();
                versionCmd.hwVersion = info.getHardwareRevision();
                versionCmd.fwVersion = info.getFirmwareRevision();
                handleGBDeviceEvent(versionCmd);
            }
        }
    }

    /**
     * Reassembles notifications into complete frames. The device's ATT MTU
     * may be smaller than a frame (e.g. the auth challenge is ~58 bytes), so
     * a frame can be split across several notify callbacks.
     */
    private void handleNotification(final byte[] chunk) {
        if (chunk == null || chunk.length == 0) {
            return;
        }
        if (rxAssembly.size() == 0 && (chunk[0] & 0xFF) != 0xA0) {
            LOG.warn("Unexpected start of notification, discarding: {}", GB.hexdump(chunk));
            return;
        }
        rxAssembly.write(chunk, 0, chunk.length);

        byte[] buf = rxAssembly.toByteArray();
        while (buf.length >= 2) {
            final int frameLen = (buf[1] & 0xFF) + 3;
            if (buf.length < frameLen) {
                break; // wait for the rest of this frame
            }
            processFrame(Arrays.copyOfRange(buf, 0, frameLen));
            buf = Arrays.copyOfRange(buf, frameLen, buf.length);
            rxAssembly.reset();
            try {
                rxAssembly.write(buf);
            } catch (final IOException ignored) {
            }
        }
    }

    private void processFrame(final byte[] frame) {
        LOG.debug("RX frame: {}", GB.hexdump(frame));
        if ((frame[0] & 0xFF) != 0xA0 || frame.length < 6) {
            LOG.warn("Malformed frame: {}", GB.hexdump(frame));
            return;
        }

        final int stateId = frame[2] & 0xFF;
        final int seqIdIn = frame[3] & 0xFF;

        if ((stateId & 0xF0) != 0xF0) {
            sendRaw("kn550bt ack", buildAck(stateId, seqIdIn));
            final int bag = stateId & 0x0F;
            final int expected = (seqIdIn + bag * 2) & 0xFF;
            seqId = (expected + 2) & 0xFF;
        }

        final byte[] payload = Arrays.copyOfRange(frame, 4, frame.length - 1);
        if (payload.length < 2) {
            return;
        }
        final int cmdId = payload[1] & 0xFF;

        switch (cmdId) {
            case CMD_AUTH_CHALLENGE & 0xFF:
                handleAuthChallenge(payload);
                break;
            case CMD_AUTH_SUCCESS & 0xFF:
                LOG.info("Authentication successful");
                syncInProgress = true;
                sendCommand("kn550bt battery", new byte[]{DEVICE_TYPE, CMD_BATTERY, 0x00, 0x00, 0x00});
                sendCommand("kn550bt time sync", buildTimeSync());
                sendCommand("kn550bt offline count", new byte[]{DEVICE_TYPE, CMD_OFFLINE_COUNT, 1, 0x00, 0x00});
                break;
            case CMD_AUTH_FAILURE & 0xFF:
                LOG.warn("Device rejected authentication response");
                break;
            case CMD_BATTERY & 0xFF:
                handleBattery(payload);
                break;
            case CMD_TIME_SYNC & 0xFF:
                LOG.debug("Time sync acknowledged");
                break;
            case CMD_OFFLINE_COUNT & 0xFF:
                handleOfflineCount(payload);
                break;
            case CMD_GET_OFFLINE_DATA & 0xFF:
                handleOfflineData(payload);
                break;
            case CMD_TRANSFER_FINISHED & 0xFF:
                LOG.debug("Transfer-finished acknowledged");
                break;
            default:
                LOG.debug("Unhandled cmd 0x{}: {}", Integer.toHexString(cmdId), GB.hexdump(payload));
        }
    }

    private void handleAuthChallenge(final byte[] payload) {
        // payload = [DEVICE_TYPE, 0xFB, r2Stroke(16), ...(18 unknown)..., deviceId(16), ...]
        final byte[] challenge = Arrays.copyOfRange(payload, 2, payload.length);
        if (challenge.length < 52) {
            LOG.warn("Auth challenge too short: {} bytes", challenge.length);
            return;
        }
        final byte[] r2Stroke = Arrays.copyOfRange(challenge, 0, 16);
        final byte[] deviceId = Arrays.copyOfRange(challenge, 36, 52);
        final byte[] ka = XXTea2.encrypt(deviceId, STATIC_KEY);
        final byte[] r2 = XXTea2.encrypt(r2Stroke, ka);
        sendCommand("kn550bt auth response", concat(new byte[]{DEVICE_TYPE, CMD_AUTH_RESPONSE}, r2));
    }

    /**
     * Verified live against real hardware/official app traffic: reply
     * payload is {@code [device_type, cmd_id, percent, 0x00, 0x00]}, e.g.
     * {@code 0x64} (100) on a freshly-charged unit. See PROTOCOL.md.
     */
    private void handleBattery(final byte[] payload) {
        if (payload.length < 3) {
            return;
        }
        final int percent = payload[2] & 0xFF;
        LOG.info("Battery level: {}%", percent);
        final GBDeviceEventBatteryInfo batteryEvent = new GBDeviceEventBatteryInfo();
        batteryEvent.state = BatteryState.BATTERY_NORMAL;
        batteryEvent.level = percent;
        evaluateGBDeviceEvent(batteryEvent);
        handleGBDeviceEvent(batteryEvent);
    }

    private void handleOfflineCount(final byte[] payload) {
        if (payload.length < 4) {
            return;
        }
        final int offlineNum = payload[3] & 0xFF;
        LOG.info("Device reports {} offline record(s)", offlineNum);
        if (offlineNum > 0) {
            sendCommand("kn550bt get offline data", new byte[]{DEVICE_TYPE, CMD_GET_OFFLINE_DATA, 1, 0x00, 0x00});
        } else {
            finishSync();
        }
    }

    private void handleOfflineData(final byte[] payload) {
        if (payload.length <= 2) {
            finishOfflineSync();
            return;
        }

        final boolean more = payload[2] != 0;
        final byte[] chunk = Arrays.copyOfRange(payload, 4, payload.length);
        offlineBuffer.write(chunk, 0, chunk.length);

        final byte[] buffered = offlineBuffer.toByteArray();
        final int recordCount = buffered.length / RECORD_LENGTH;
        final List<GenericBloodPressureSample> samples = new ArrayList<>();
        for (int i = 0; i < recordCount; i++) {
            final byte[] record = Arrays.copyOfRange(buffered, i * RECORD_LENGTH, (i + 1) * RECORD_LENGTH);
            samples.add(parseRecord(record));
        }

        // Keep any trailing partial record (< RECORD_LENGTH bytes) buffered
        // for the next notification instead of discarding it.
        final int remainderStart = recordCount * RECORD_LENGTH;
        offlineBuffer.reset();
        offlineBuffer.write(buffered, remainderStart, buffered.length - remainderStart);

        if (!samples.isEmpty()) {
            persistSamples(samples);
        }

        if (more) {
            sendCommand("kn550bt get offline data", new byte[]{DEVICE_TYPE, CMD_GET_OFFLINE_DATA, 1, 0x00, 0x00});
        } else {
            finishOfflineSync();
        }
    }

    /**
     * Called once a full offline-data pull has completed. Tells the device
     * its unsynced queue was transferred - this also clears the readings
     * from the device's own on-screen history, see
     * {@link #CMD_TRANSFER_FINISHED}.
     */
    private void finishOfflineSync() {
        sendCommand("kn550bt transfer finished", new byte[]{DEVICE_TYPE, CMD_TRANSFER_FINISHED, 0x00, 0x00, 0x00});
        finishSync();
    }

    /** Marks the sync as closed out and tells Gadgetbridge the activity-data fetch is done. */
    private void finishSync() {
        syncInProgress = false;
        GB.signalActivityDataFinish(getDevice());
    }

    private GenericBloodPressureSample parseRecord(final byte[] record) {
        final int year = (record[0] & 0xFF) + 2000;
        final int month = record[1] & 0xFF;
        final int day = record[2] & 0xFF;
        final int hour = record[3] & 0xFF;
        final int minute = record[4] & 0xFF;
        final int second = record[5] & 0xFF;
        final int diastolicOffset = record[6] & 0xFF;
        final int diastolic = record[7] & 0xFF;
        final int systolic = diastolic + diastolicOffset;
        final int heartRate = record[8] & 0xFF;
        final boolean arrhythmia = (record[10] & 0x80) != 0;

        final Calendar cal = Calendar.getInstance();
        cal.clear();
        cal.set(year, month - 1, day, hour, minute, second);

        final GenericBloodPressureSample sample = new GenericBloodPressureSample();
        sample.setTimestamp(cal.getTimeInMillis());
        sample.setBpSystolic(systolic);
        sample.setBpDiastolic(diastolic);
        sample.setPulseRate(heartRate);
        sample.setMeanArterialPressure(0);
        sample.setUserIndex(0);
        // GenericBloodPressureSample has no dedicated arrhythmia column;
        // reuse measurementStatus as a simple flag (1 = arrhythmia detected).
        sample.setMeasurementStatus(arrhythmia ? 1 : 0);
        return sample;
    }

    private void persistSamples(final List<GenericBloodPressureSample> samples) {
        try (DBHandler handler = GBApplication.acquireDB()) {
            final DaoSession session = handler.getDaoSession();
            final GenericBloodPressureSampleProvider provider =
                    new GenericBloodPressureSampleProvider(getDevice(), session);
            provider.persistSamples(samples, getContext());
            LOG.info("Persisted {} blood pressure sample(s)", samples.size());
        } catch (final Exception e) {
            GB.toast(getContext(), "Error saving blood pressure data", Toast.LENGTH_LONG, GB.ERROR, e);
        }
    }

    // -------------------------------------------------------------------------
    // Packet building
    // -------------------------------------------------------------------------

    private byte[] buildFrame(final byte[] payload) {
        final byte[] frame = new byte[payload.length + 5];
        frame[0] = (byte) 0xB0;
        frame[1] = (byte) ((payload.length + 2) & 0xFF);
        frame[2] = 0;
        frame[3] = (byte) seqId;
        seqId = (seqId + 2) % 256;
        System.arraycopy(payload, 0, frame, 4, payload.length);
        int checksum = 0;
        for (int i = 2; i < frame.length - 1; i++) {
            checksum += frame[i] & 0xFF;
        }
        frame[frame.length - 1] = (byte) (checksum & 0xFF);
        return frame;
    }

    private byte[] buildAck(final int stateId, final int seqIdIn) {
        final int ackStateId = 0xA0 + (stateId & 0x0F);
        final int seq = seqIdIn & 0xFF;
        final int tempAsk = (seq == 0) ? 255 : seq - 1;
        final int ackSeq = (tempAsk + 2) & 0xFF;
        final byte[] packet = new byte[6];
        packet[0] = (byte) 0xB0;
        packet[1] = 0x03;
        packet[2] = (byte) ackStateId;
        packet[3] = (byte) ackSeq;
        packet[4] = DEVICE_TYPE;
        int checksum = 0;
        for (int i = 2; i < 5; i++) {
            checksum += packet[i] & 0xFF;
        }
        packet[5] = (byte) (checksum & 0xFF);
        return packet;
    }

    private byte[] buildTimeSync() {
        final Calendar now = Calendar.getInstance();
        return new byte[]{
                DEVICE_TYPE, CMD_TIME_SYNC,
                (byte) (now.get(Calendar.YEAR) % 100),
                (byte) (now.get(Calendar.MONTH) + 1),
                (byte) now.get(Calendar.DAY_OF_MONTH),
                (byte) now.get(Calendar.HOUR_OF_DAY),
                (byte) now.get(Calendar.MINUTE),
                (byte) now.get(Calendar.SECOND),
        };
    }

    private static byte[] concat(final byte[] a, final byte[] b) {
        final byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private void sendCommand(final String taskName, final byte[] payload) {
        sendRaw(taskName, buildFrame(payload));
    }

    private void sendRaw(final String taskName, final byte[] rawPacket) {
        try {
            final TransactionBuilder builder = performInitialized(taskName);
            builder.writeChunkedData(getCharacteristic(CHAR_WRITE), rawPacket, WRITE_CHUNK_SIZE);
            builder.queue();
        } catch (final IOException e) {
            LOG.warn("Unable to send {}", taskName, e);
        }
    }
}
