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
package nodomain.freeyourgadget.gadgetbridge.service.devices.ringconn;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.os.Handler;
import android.os.Looper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSpo2SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.ringconn.RingConnSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSpo2Sample;
import nodomain.freeyourgadget.gadgetbridge.entities.RingConnActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.User;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class RingConnDeviceSupport extends AbstractBTLESingleDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(RingConnDeviceSupport.class);

    static final UUID SERVICE = UUID.fromString("8327ad99-2d87-4a22-a8ce-6dd7971c0437");
    static final UUID WRITE_CHAR = UUID.fromString("8327ad98-2d87-4a22-a8ce-6dd7971c0437");
    static final UUID NOTIFY_CHAR = UUID.fromString("8327ad97-2d87-4a22-a8ce-6dd7971c0437");

    private static final long READ_TIMEOUT_MS = 20_000L;
    private static final long REPLAY_WAIT_MS = 2_500L;
    private static final long DRAINED_QUIET_MS = 800L;
    /** Auth is keyed on the ring's own MAC, so anything shorter cannot produce a response. */
    private static final int MAC_LEN = 6;

    private final Handler handler = new Handler(Looper.getMainLooper());
    // Written while building a transaction, read on the GATT callback thread.
    private volatile RingConnSyncEngine engine;
    private volatile boolean sawRecords = false;
    private volatile boolean completed = false;
    // Last battery snapshot actually reported; the ring pushes one every ~14s and GB inserts a
    // BatteryLevel row per event, so unchanged values are dropped rather than stored.
    private volatile int lastBatteryLevel = -1;
    private volatile Boolean lastBatteryCharging = null;

    public RingConnDeviceSupport() {
        super(LOG);
        addSupportedService(SERVICE);
    }

    @Override
    public boolean useAutoConnect() {
        return false;
    }

    @Override
    protected TransactionBuilder initializeDevice(final TransactionBuilder builder) {
        builder.setDeviceState(GBDevice.State.INITIALIZING);
        builder.notify(NOTIFY_CHAR, true);
        builder.setDeviceState(GBDevice.State.INITIALIZED);
        // Sync the activity backlog on connect, then stay connected (see finishSync). GB is the
        // sole consumer of the ring's shared 4c record cursor, so it keeps the ring rather than
        // releasing it. onFetchRecordedData re-runs the same drain on the manual fetch trigger.
        beginSync(builder);
        return builder;
    }

    @Override
    public void onFetchRecordedData(final int dataTypes) {
        if (getDevice().isBusy()) {
            return;
        }
        final TransactionBuilder builder = createTransactionBuilder("ringconn-sync");
        beginSync(builder);
        builder.queue();
    }

    /** Reset state, mark the device busy, and kick the auth+replay handshake on {@code builder}. */
    private void beginSync(final TransactionBuilder builder) {
        final byte[] mac = macBytes(getDevice().getAddress());
        if (mac == null || mac.length < MAC_LEN) {
            LOG.error("Not syncing: {} is not a usable MAC for auth", getDevice().getAddress());
            return;
        }
        final RingConnSyncEngine previous = engine;
        engine = new RingConnSyncEngine(mac);
        if (previous != null) {
            engine.adoptStepState(previous); // Banked steps outlive a re-sync; see adoptStepState.
        }
        sawRecords = false;
        completed = false;
        builder.setBusyTask(R.string.busy_task_fetch_activity_data);
        for (final byte[] cmd : engine.start()) {
            builder.write(WRITE_CHAR, cmd);
        }
        armQuietTimer(READ_TIMEOUT_MS);
    }

    @Override
    public boolean onCharacteristicChanged(final BluetoothGatt gatt,
                                           final BluetoothGattCharacteristic characteristic,
                                           final byte[] value) {
        if (super.onCharacteristicChanged(gatt, characteristic, value)) {
            return true;
        }
        if (engine == null || !NOTIFY_CHAR.equals(characteristic.getUuid())) {
            return false;
        }
        final RingConnSyncEngine.Actions actions = engine.onNotification(value);
        if (actions.getBattery() != null) {
            // Keeps the accumulator falsifiable: a zero step total cannot otherwise be told
            // apart from a wrong byte offset.
            LOG.info("RingConn status: stepAccumulator={} delta={}",
                    RingConnRecordParser.INSTANCE.parseStepAccumulator(value), actions.getStepsDelta());
            dispatchBattery(actions.getBattery());
            // Pure status push (~every 14s), not part of the sync stream: leave the quiet timer alone.
            return true;
        }
        if (actions.getCommandsToWrite().isEmpty() && actions.getBucketsToPersist().isEmpty()) {
            // A frame the parser rejects yields no work and is never acked, which pins the ring's
            // replay cursor. Silence here cost a 30 h data gap before it was spotted.
            LOG.warn("Unhandled RingConn frame: {}", GB.hexdump(value));
        }
        if (!actions.getCommandsToWrite().isEmpty()) {
            final TransactionBuilder b = createTransactionBuilder("ringconn-cmd");
            for (final byte[] cmd : actions.getCommandsToWrite()) {
                b.write(WRITE_CHAR, cmd);
            }
            b.queue();
        }
        if (!actions.getBucketsToPersist().isEmpty()) {
            sawRecords = true;
            persist(actions.getBucketsToPersist());
        }
        armQuietTimer(actions.getActivityDrained() ? DRAINED_QUIET_MS : REPLAY_WAIT_MS);
        return true;
    }

    private void armQuietTimer(final long delayMs) {
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::finishSync, delayMs);
    }

    private void finishSync() {
        final boolean firstCompletion = !completed;
        completed = true;
        if (!firstCompletion && !sawRecords) {
            return;
        }
        // Stay connected (like every other GB gadget). Self-disconnecting races the last in-flight
        // ack write — disconnect() closes the gatt before the write callback fires, wedging the
        // BtLEQueue out-thread on its latch so every later reconnect's init never runs. GB is sole consumer.
        LOG.info("RingConn sync complete (sawRecords={}), staying connected", sawRecords);
        if (getDevice().isBusy()) {
            getDevice().unsetBusyTask();
            getDevice().sendDeviceUpdateIntent(getContext());
        }
        // Broadcast ACTION_NEW_DATA so device-card steps, charts and dashboard recompute;
        // sendDeviceUpdateIntent only re-renders cached values, it does not recompute totals.
        // Cleared so a later drain on this long-lived connection signals again instead of going quiet.
        if (sawRecords) {
            sawRecords = false;
            GB.signalActivityDataFinish(getDevice());
        }
    }

    /** Report a battery snapshot; GB persists history and fires low/full notifications from here. */
    private void dispatchBattery(final RingConnBatteryStatus status) {
        // Each event inserts a BatteryLevel row, and the ring pushes one every ~14s on a connection
        // we deliberately keep open, so report only when the reading actually moves.
        if (status.getLevel() == lastBatteryLevel
                && Boolean.valueOf(status.getCharging()).equals(lastBatteryCharging)) {
            return;
        }
        lastBatteryLevel = status.getLevel();
        lastBatteryCharging = status.getCharging();
        final GBDeviceEventBatteryInfo evt = new GBDeviceEventBatteryInfo();
        evt.level = status.getLevel();
        evt.state = batteryState(status.getLevel(), status.getCharging());
        handleGBDeviceEvent(evt);
    }

    private static BatteryState batteryState(final int level, final boolean charging) {
        if (charging) {
            return level >= 100 ? BatteryState.BATTERY_CHARGING_FULL : BatteryState.BATTERY_CHARGING;
        }
        // Leave BATTERY_LOW to the event's own threshold check (GBDeviceEventBatteryInfo.evaluate).
        return BatteryState.BATTERY_NORMAL;
    }

    private void persist(final List<RingConnSyncEngine.Bucket> buckets) {
        try (DBHandler dbHandler = GBApplication.acquireDB()) {
            final DaoSession session = dbHandler.getDaoSession();
            final Device device = DBHelper.getDevice(getDevice(), session);
            final User user = DBHelper.getUser(session);
            final RingConnSampleProvider provider = new RingConnSampleProvider(getDevice(), session);
            final Map<Integer, Integer> liveSteps = liveStepsByTimestamp(provider, buckets);
            final List<RingConnActivitySample> samples = new ArrayList<>();
            for (final RingConnSyncEngine.Bucket bucket : buckets) {
                final RingConnActivitySample sample = new RingConnActivitySample();
                sample.setTimestamp((int) bucket.getUnixSeconds());
                sample.setDeviceId(device.getId());
                sample.setUserId(user.getId());
                // The 4c record carries no gait-filtered step count; steps arrive banked on the
                // bucket from the status accumulator. Keep whatever a previous sync already stored:
                // insertOrReplace would otherwise zero them when the ring replays the same epoch.
                final Integer carried = liveSteps.get((int) bucket.getUnixSeconds());
                sample.setSteps(Math.max(bucket.getSteps(), carried == null ? 0 : carried));
                sample.setRawIntensity(bucket.getMotionLevel());
                // No stage rides the wire (the vendor app computes the hypnogram), so store the
                // inferred session as LIGHT_SLEEP: SleepAnalysis and DailyTotals bucket only the
                // four staged kinds, and SLEEP_ANY would silently contribute zero minutes to both.
                sample.setRawKind((bucket.getAsleep() ? ActivityKind.LIGHT_SLEEP : ActivityKind.ACTIVITY).getCode());
                sample.setProvider(provider);
                samples.add(sample);
            }
            provider.addGBActivitySamples(samples);   // insertOrReplaceInTx -> idempotent by (device, timestamp)
            persistSpo2(session, device, user, buckets);
        } catch (final Exception e) {
            LOG.error("Failed to persist RingConn samples", e);
        }
    }

    /** Existing non-zero step counts in the range these buckets cover, so a re-sync cannot erase them. */
    private Map<Integer, Integer> liveStepsByTimestamp(final RingConnSampleProvider provider,
                                                       final List<RingConnSyncEngine.Bucket> buckets) {
        int from = Integer.MAX_VALUE;
        int to = Integer.MIN_VALUE;
        for (final RingConnSyncEngine.Bucket bucket : buckets) {
            final int ts = (int) bucket.getUnixSeconds();
            from = Math.min(from, ts);
            to = Math.max(to, ts);
        }
        final Map<Integer, Integer> steps = new HashMap<>();
        for (final RingConnActivitySample sample : provider.getAllActivitySamples(from, to)) {
            if (sample.getSteps() > 0) {
                steps.put(sample.getTimestamp(), sample.getSteps());
            }
        }
        return steps;
    }

    /** SpO2 rides the same 2.5-minute records but is only measured on some epochs, so most buckets carry none. */
    private void persistSpo2(final DaoSession session, final Device device, final User user,
                             final List<RingConnSyncEngine.Bucket> buckets) {
        final List<GenericSpo2Sample> samples = new ArrayList<>();
        for (final RingConnSyncEngine.Bucket bucket : buckets) {
            if (bucket.getSpo2() != null) {
                samples.add(new GenericSpo2Sample(bucket.getUnixSeconds() * 1000L, device.getId(),
                        user.getId(), bucket.getSpo2()));
            }
        }
        if (samples.isEmpty()) {
            return;
        }
        new GenericSpo2SampleProvider(getDevice(), session).addSamples(samples);
    }

    /** Null for anything that is not a hex MAC; auth is keyed on it, so a bad address must not
     *  surface later as a parse failure on the GATT callback thread. */
    private static byte[] macBytes(final String address) {
        final String[] parts = address == null ? new String[0] : address.split(":");
        final byte[] mac = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                mac[i] = (byte) Integer.parseInt(parts[i], 16);
            } catch (final NumberFormatException e) {
                return null;
            }
        }
        return mac;
    }

    @Override
    public void dispose() {
        handler.removeCallbacksAndMessages(null);
        super.dispose();
    }
}
