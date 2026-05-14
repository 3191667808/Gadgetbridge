/*  Copyright (C) 2026 Dany Mestas

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.oura;

import android.app.Notification;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHeartRateSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHrvValueSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSleepStageSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSpo2SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericTemperatureSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.HeartRrIntervalSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.OuraRecoverySampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.OuraSleepSessionSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.oura.OuraConstants;
import nodomain.freeyourgadget.gadgetbridge.devices.oura.samples.OuraActivitySampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHrvValueSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSleepStageSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSpo2Sample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericTemperatureSample;
import nodomain.freeyourgadget.gadgetbridge.entities.HeartRrIntervalSample;
import nodomain.freeyourgadget.gadgetbridge.entities.HeartRrIntervalSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.OuraActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.OuraActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.OuraRecoverySample;
import nodomain.freeyourgadget.gadgetbridge.entities.OuraSleepSessionSample;
import nodomain.freeyourgadget.gadgetbridge.entities.User;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.RecordedDataTypes;
import nodomain.freeyourgadget.gadgetbridge.model.TemperatureSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol.OuraAuth;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol.OuraEventParser;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol.OuraOpcode;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol.OuraPacket;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol.OuraTimeSync;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol.OuraUUIDs;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.StringUtils;

public class OuraRing4Support extends AbstractBTLESingleDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(OuraRing4Support.class);

    private OuraTimeSync.BootClock bootClock;
    private long syncUnixAtSync;
    private long pendingCursor;
    /** Ring boot-ticks at SyncTime response — the target end-of-drain (= ring's "now"). Captured
     *  once per connection and used to compute drain completion percentage. 0 until SyncTime
     *  response lands. */
    private long drainTargetCursor;
    /** Cursor value at the very start of the drain (first iteration). Used as the denominator
     *  base for completion percentage so the bar moves across full backlog, not just current
     *  iteration. */
    private long drainStartCursor;
    /** Last % value pushed to the transfer notification. Used to throttle updates — each
     *  handleBundledEvents call would otherwise fire one notification update, producing
     *  hundreds of updates per long drain. Only re-publish when the integer percentage
     *  changes. Reset to -1 between drains so the very first publish always lands. */
    private int lastPublishedPercent = -1;

    /** Ring caps each `0x10 GetEvents` response at 255 records. When the drain summary reports
     *  exactly 255, more events are available — re-fire startEventDrain() to continue. Hard
     *  cap iterations to bound worst-case session length (e.g. catching up after factory reset
     *  + multi-day gap). 2000 × 255 ≈ 510k events ≈ several weeks of dense event log — sized for
     *  an overnight catch-up where one 8-hr night plus a daytime gap produces ~25k records and
     *  the cursor must traverse ~700k ticks to reach the trailing BEDTIME/SLEEP_TEMP frames. */
    private static final int DRAIN_MAX_ITERATIONS = 2000;
    private int drainIterations;
    /** Cursor value at start of the current iteration. After the batch, if pendingCursor did
     *  not advance past this, we're re-reading already-known data — stop the loop. */
    private long iterationStartCursor;

    /** Per-connection budget for INFO-level payload dumps of high-volume / unknown event tags.
     *  Key = `(tag << 8) | sub` (sub=0 for non-sub-typed tags). Decremented on each emit.
     *  When the budget for a key reaches 0, subsequent records fall back to DEBUG.
     *  Cleared in {@link #initializeDevice(TransactionBuilder)} so each fresh connect gets a
     *  representative sample. */
    private final Map<Integer, Integer> infoLogBudget = new HashMap<>();

    public OuraRing4Support() {
        super(LOG);
        addSupportedService(OuraUUIDs.PRIMARY_SERVICE);
    }

    @Override
    public boolean useAutoConnect() {
        return false;
    }

    @Override
    public void dispose() {
        synchronized (ConnectionMonitor) {
            // GBDevice.reset() drops the busyTask label automatically, but the status-bar transfer
            // notification (started by startEventDrain on the first drain iteration) lingers across
            // BT-off / link drops unless we clear it on dispose.
            GB.updateTransferNotification(null, "", false, 100, getContext());
            super.dispose();
        }
    }

    @Override
    public void onFetchRecordedData(final int dataTypes) {
        LOG.info("Oura onFetchRecordedData: dataTypes=0x{}", Integer.toHexString(dataTypes));
        // Sync menu and per-type pulls both route through here. The drain is one big stream
        // anyway, so any activity/HR/SpO2/sleep/temperature/HRV request triggers the same drain.
        final int relevant = RecordedDataTypes.TYPE_ACTIVITY
                | RecordedDataTypes.TYPE_SPO2
                | RecordedDataTypes.TYPE_HEART_RATE
                | RecordedDataTypes.TYPE_TEMPERATURE;
        if (dataTypes == 0 || (dataTypes & relevant) != 0 || dataTypes == RecordedDataTypes.TYPE_SYNC) {
            startEventDrain();
        }
    }

    @Override
    public void onSendConfiguration(final String config) {
        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        final int feature = featureIdForPref(config);
        if (feature >= 0) {
            final boolean enabled = prefs.getBoolean(config, false);
            sendSetFeatureMode(feature, enabled ? OuraOpcode.FEATURE_MODE_AUTO : OuraOpcode.FEATURE_MODE_OFF);
            LOG.info("Oura feature 0x{} toggled: enabled={} (pref={})", Integer.toHexString(feature), enabled, config);
            return;
        }
        super.onSendConfiguration(config);
    }

    private static int featureIdForPref(final String prefKey) {
        if (prefKey == null) {
            return -1;
        }
        switch (prefKey) {
            case DeviceSettingsPreferenceConst.PREF_SPO2_ALL_DAY_MONITORING:
                return OuraOpcode.FEATURE_SPO2;
            case OuraConstants.PREF_OURA_DAYTIME_HR:
                return OuraOpcode.FEATURE_DAYTIME_HR;
            case OuraConstants.PREF_OURA_RESTING_HR:
                return OuraOpcode.FEATURE_RESTING_HR;
            case OuraConstants.PREF_OURA_EXERCISE_HR:
                return OuraOpcode.FEATURE_EXERCISE_HR;
            case OuraConstants.PREF_OURA_REAL_STEPS:
                return OuraOpcode.FEATURE_REAL_STEPS;
            case OuraConstants.PREF_OURA_TAP_TO_TAG:
                return OuraOpcode.FEATURE_TAP_TO_TAG;
            case OuraConstants.PREF_OURA_CHARGING_CONTROL:
                return OuraOpcode.FEATURE_CHARGING_CONTROL;
            default:
                return -1;
        }
    }

    private static String prefKeyForFeature(final int featureId) {
        switch (featureId) {
            case OuraOpcode.FEATURE_SPO2:
                return DeviceSettingsPreferenceConst.PREF_SPO2_ALL_DAY_MONITORING;
            case OuraOpcode.FEATURE_DAYTIME_HR:
                return OuraConstants.PREF_OURA_DAYTIME_HR;
            case OuraOpcode.FEATURE_RESTING_HR:
                return OuraConstants.PREF_OURA_RESTING_HR;
            case OuraOpcode.FEATURE_EXERCISE_HR:
                return OuraConstants.PREF_OURA_EXERCISE_HR;
            case OuraOpcode.FEATURE_REAL_STEPS:
                return OuraConstants.PREF_OURA_REAL_STEPS;
            case OuraOpcode.FEATURE_TAP_TO_TAG:
                return OuraConstants.PREF_OURA_TAP_TO_TAG;
            case OuraOpcode.FEATURE_CHARGING_CONTROL:
                return OuraConstants.PREF_OURA_CHARGING_CONTROL;
            default:
                return null;
        }
    }

    @Override
    protected TransactionBuilder initializeDevice(final TransactionBuilder builder) {
        infoLogBudget.clear();
        drainIterations = 0;
        builder.setDeviceState(GBDevice.State.INITIALIZING);
        builder.notify(OuraUUIDs.CHAR_NOTIFY, true);
        // Android may silently upgrade to 517; RX paths are length-driven (OuraEventParser walks [tag][len][payload] records).
        builder.requestMtu(247);
        write(builder, OuraPacket.frame(OuraOpcode.OP_GET_FW, new byte[]{0x00, 0x00, 0x00}));
        return builder;
    }

    @Override
    public boolean onCharacteristicChanged(final BluetoothGatt gatt,
                                           final BluetoothGattCharacteristic characteristic,
                                           final byte[] value) {
        if (super.onCharacteristicChanged(gatt, characteristic, value)) {
            return true;
        }
        final UUID uuid = characteristic.getUuid();
        if (!OuraUUIDs.CHAR_NOTIFY.equals(uuid)) {
            return false;
        }
        if (value == null || value.length == 0) {
            return true;
        }

        // Route by leading byte: tags ≥ 0x41 are bundled event records to be record-walked.
        // Non-bundle HVNs (single-response opcodes 0x09/0x0D/0x11/0x13/0x17/0x19/0x1D/0x1F/0x29/0x2F)
        // fall through to dispatch() and are NOT walked as event bundles — see protocol notes in BLE.md.
        if (OuraEventParser.isEventFrame(value)) {
            handleBundledEvents(value);
            return true;
        }

        try {
            dispatch(value);
        } catch (final Exception e) {
            LOG.error("Failed to handle Oura notification {}", StringUtils.bytesToHex(value), e);
        }
        return true;
    }

    private void dispatch(final byte[] frame) throws Exception {
        final OuraPacket pkt = OuraPacket.parse(frame);
        switch (pkt.tag) {
            case OuraOpcode.OP_GET_FW_RESP:
                handleFirmwareResponse(pkt.payload);
                preAuthPipeline();
                break;
            case OuraOpcode.OP_GET_BATTERY_RESP:
                handleBatteryResponse(pkt.payload);
                break;
            case OuraOpcode.OP_SYNC_TIME_RESP:
                handleSyncTimeResponse(pkt.payload);
                postSyncTimePipeline();
                break;
            case OuraOpcode.OP_GET_PRODUCT_INFO_RESP:
                LOG.debug("Oura ProductInfo: {}", StringUtils.bytesToHex(pkt.payload));
                break;
            case OuraOpcode.OP_SET_BLE_MODE_RESP:
            case OuraOpcode.OP_SET_NOTIFICATION_RESP:
            case OuraOpcode.OP_CHECK_SLEEP_ANALYSIS_RESP:
                LOG.debug("Oura ack 0x{}: {}", Integer.toHexString(pkt.tag), StringUtils.bytesToHex(pkt.payload));
                break;
            case OuraOpcode.OP_GET_EVENTS_SUMMARY:
                handleEventsSummary(pkt.payload);
                break;
            case OuraOpcode.OP_EXT:
                handleExtended(pkt.payload);
                break;
            default:
                LOG.debug("Unhandled Oura opcode 0x{}", Integer.toHexString(pkt.tag));
                break;
        }
    }

    private void handleFirmwareResponse(final byte[] payload) {
        if (payload.length < 12) {
            LOG.warn("Short firmware response: {}", StringUtils.bytesToHex(payload));
            return;
        }
        final String fwSemver = String.format("%d.%02d.%02d", payload[3] & 0xff, payload[4] & 0xff, payload[5] & 0xff);
        final GBDeviceEventVersionInfo evt = new GBDeviceEventVersionInfo();
        evt.fwVersion = fwSemver;
        evt.hwVersion = "Oura Ring 4";
        evaluateGBDeviceEvent(evt);
    }

    private void handleBatteryResponse(final byte[] payload) {
        if (payload.length < 6) {
            return;
        }
        // Battery payload layout per BLE.md § 0x0D: [level%][status?][?][?][mV:u16LE].
        // Byte 1 is non-zero in some non-charging captures, so it's NOT a clean charge-progress
        // percent — until a real cradle snoop confirms semantics, only the battery percent and
        // a low-battery heuristic on voltage are exposed.
        final int level = payload[0] & 0xff;
        final int voltageMv = OuraPacket.readU16LE(payload, 4);
        final GBDeviceEventBatteryInfo evt = new GBDeviceEventBatteryInfo();
        evt.level = level;
        evt.voltage = voltageMv / 1000f;
        evt.state = level <= 15 ? BatteryState.BATTERY_LOW : BatteryState.BATTERY_NORMAL;
        evaluateGBDeviceEvent(evt);
    }

    private void handleSyncTimeResponse(final byte[] payload) {
        final OuraTimeSync.SyncTimeResponse resp = OuraTimeSync.parseSyncTimeResp(payload);
        bootClock = new OuraTimeSync.BootClock(syncUnixAtSync, resp.ringBootTicks);
        drainTargetCursor = resp.ringBootTicks;
        LOG.info("Oura time sync: ringBootTicks={} status=0x{}", resp.ringBootTicks, Integer.toHexString(resp.status));
    }

    private void handleEventsSummary(final byte[] payload) {
        final int received = payload.length >= 1 ? (payload[0] & 0xff) : 0;
        LOG.info("Oura GetEvents summary: received={} iteration={}", received, drainIterations);
        if (pendingCursor > 0) {
            persistCursor(pendingCursor);
        }
        // Continue draining if (a) ring filled the per-batch cap of 255 (more available),
        // (b) cursor actually advanced (otherwise we're re-reading already-known data —
        // the only events received had bootTs <= iterationStartCursor), and (c) we haven't
        // hit the safety iteration cap. Cursor-advance check is the primary "stop when
        // data is already known" guard — defensive against firmware sending old events.
        // Dedupe is automatic via composite PK (timestamp, deviceId) + insertOrReplace on
        // all sample DAOs, so even re-drained records overwrite the same row rather than
        // duplicating, but stopping early saves BLE bandwidth + battery.
        final boolean cursorAdvanced = pendingCursor > iterationStartCursor;
        if (received >= 255 && cursorAdvanced && drainIterations < DRAIN_MAX_ITERATIONS) {
            drainIterations++;
            startEventDrain();
            return;
        }
        if (received >= 255 && !cursorAdvanced) {
            LOG.warn("Oura drain halted: 255 events received but cursor stuck at {} — already-known data, ring buffer may have wrapped or firmware quirk", pendingCursor);
        }
        finishDrain();
    }

    /** Single terminate path for the drain — resets iter counter, releases the ring's bundling
     *  mode, and clears the device-card busy label + status-bar transfer notification. Called
     *  from handleEventsSummary on any non-continue exit: ring exhausted (received < 255),
     *  cursor stuck, or iteration cap reached. Keeping it isolated avoids the prior risk where
     *  a new terminate branch could forget to clear the transfer notification and leave a
     *  zombie "Fetching activity data N%" entry in the status bar after the drain ended. */
    private void finishDrain() {
        drainIterations = 0;
        // Restore the user's pref'd connection priority before BleMode flip + UI clear so the
        // radio settles back to BALANCED/LOW_POWER right after the last write. HIGH was
        // requested at drain start; matching restore here keeps battery cost bounded to the
        // actual drain duration.
        final boolean lowPower = getDevicePrefs().getConnectionPriorityLowPower();
        requestPriority(lowPower ? BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
                : BluetoothGatt.CONNECTION_PRIORITY_BALANCED);
        sendBleModeNormal();
        // Merge HR from GENERIC_HEART_RATE_SAMPLE into OURA_ACTIVITY_SAMPLE.HEART_RATE for the
        // drained window. Activity-chart HR plot + min/max/avg + HealthConnect HR export all
        // read ActivitySample.getHeartRate(), but 0x50 ACTIVITY events carry no HR — this pass
        // fills the column from the per-second IBI-derived HR rows. Runs once per drain at the
        // end so both source tables are fully populated before the merge query.
        mergeHeartRateIntoActivitySamples();
        getDevice().unsetBusyTask();
        GB.updateTransferNotification(null, "", false, 100, getContext());
        // State already INITIALIZED from postSyncTimePipeline. sendDeviceUpdateIntent here
        // keeps any UI bound to per-sample additions in sync once the drain settles.
        getDevice().sendDeviceUpdateIntent(getContext());
    }

    /** Walk OURA_ACTIVITY_SAMPLE rows in the drain wall-clock window and stamp each with the
     *  mean HR over a ±30 s window pulled from GENERIC_HEART_RATE_SAMPLE. Persisted via
     *  greenDAO insertOrReplace so subsequent drains' merges overwrite idempotently. */
    private void mergeHeartRateIntoActivitySamples() {
        if (bootClock == null) {
            return;
        }
        if (pendingCursor <= drainStartCursor) {
            return;
        }
        final long startSec = Math.max(0L, bootClock.wallClockOf(drainStartCursor));
        final long endSec = bootClock.wallClockOf(pendingCursor);
        if (endSec <= startSec) {
            return;
        }
        try (final DBHandler db = GBApplication.acquireDB()) {
            final DaoSession session = db.getDaoSession();
            final OuraActivitySampleProvider provider = new OuraActivitySampleProvider(getDevice(), session);
            final List<OuraActivitySample> activities = provider.getAllActivitySamples((int) startSec, (int) endSec);
            if (activities.isEmpty()) {
                return;
            }
            final List<GenericHeartRateSample> hrSamples = new ArrayList<>(
                    new GenericHeartRateSampleProvider(getDevice(), session)
                            .getAllSamples(startSec * 1000L, endSec * 1000L));
            if (hrSamples.isEmpty()) {
                return;
            }
            // AbstractTimeSampleProvider.getAllSamples does NOT add orderAsc() to the query,
            // so the underlying greenDAO list is in undefined (rowid-ish) order. Sort here so
            // the two-pointer walk below can advance hrCursor monotonically.
            hrSamples.sort(Comparator.comparingLong(GenericHeartRateSample::getTimestamp));
            // Two-pointer linear walk: activity samples already ASC (provider.getGBActivitySamples
            // calls orderAsc), HR samples now ASC after the sort above. For each activity
            // sample at wallMs T, average all HR samples in [T-30s, T+30s] clamped to 20-220 bpm.
            final long windowMs = 60_000L;
            int hrCursor = 0;
            final List<OuraActivitySample> updated = new ArrayList<>();
            for (final OuraActivitySample act : activities) {
                final long actMs = act.getTimestamp() * 1000L;
                final long lo = actMs - (windowMs / 2);
                final long hi = actMs + (windowMs / 2);
                while (hrCursor < hrSamples.size() && hrSamples.get(hrCursor).getTimestamp() < lo) {
                    hrCursor++;
                }
                int sum = 0;
                int n = 0;
                for (int i = hrCursor; i < hrSamples.size(); i++) {
                    final GenericHeartRateSample hr = hrSamples.get(i);
                    if (hr.getTimestamp() > hi) {
                        break;
                    }
                    final int v = hr.getHeartRate();
                    if (v >= 20 && v <= 220) {
                        sum += v;
                        n++;
                    }
                }
                if (n > 0) {
                    final int avg = sum / n;
                    if (act.getHeartRate() != avg) {
                        act.setHeartRate(avg);
                        updated.add(act);
                    }
                }
            }
            if (!updated.isEmpty()) {
                // Single insertOrReplaceInTx for all changed rows. Much faster than per-sample
                // addGBActivitySample which wraps each call in its own implicit greenDAO tx.
                provider.addGBActivitySamples(updated.toArray(new OuraActivitySample[0]));
            }
            LOG.info("Oura HR merge: {} activity samples updated in window {}..{} ({} HR rows scanned)",
                    updated.size(), startSec, endSec, hrSamples.size());
        } catch (final Exception e) {
            LOG.error("Failed to merge HR into Oura activity samples", e);
        }
    }

    private void requestPriority(final int priority) {
        if (!getDevice().getDeviceCoordinator().supportsConnectionPriority()) {
            return;
        }
        final TransactionBuilder b = createTransactionBuilder("oura-priority-" + priority);
        b.requestConnectionPriority(priority);
        b.queue();
    }

    private void handleExtended(final byte[] payload) {
        if (payload.length < 1) {
            return;
        }
        final int subTag = payload[0] & 0xff;
        switch (subTag) {
            case OuraOpcode.EXT_NONCE_RESP:
                handleNonceResponse(payload);
                break;
            case OuraOpcode.EXT_AUTH_RESP:
                handleAuthResponse(payload);
                break;
            case OuraOpcode.EXT_FEATURE_STATUS_RESP:
                handleFeatureStatusResponse(payload);
                break;
            case OuraOpcode.EXT_SET_FEATURE_MODE_RESP:
            case OuraOpcode.EXT_SET_FEATURE_SUBSCRIPTION_RESP:
            case OuraOpcode.EXT_SET_BUNDLING_RESP:
            case OuraOpcode.EXT_CAPS_RESP:
                LOG.debug("Oura ext ack 0x{}: {}", Integer.toHexString(subTag), StringUtils.bytesToHex(payload));
                break;
            default:
                LOG.debug("Unhandled Oura ext sub-tag 0x{}", Integer.toHexString(subTag));
                break;
        }
    }

    private void handleFeatureStatusResponse(final byte[] extPayload) {
        // Layout: [0x21][feature][mode][status][state][sub]
        if (extPayload.length < 6) {
            return;
        }
        final int feature = extPayload[1] & 0xff;
        final int mode = extPayload[2] & 0xff;
        final String prefKey = prefKeyForFeature(feature);
        if (prefKey == null) {
            return;
        }
        final boolean enabled = mode == OuraOpcode.FEATURE_MODE_AUTO;
        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        prefs.edit().putBoolean(prefKey, enabled).apply();
        LOG.debug("Oura feature 0x{} synced from ring: enabled={} (pref={})", Integer.toHexString(feature), enabled, prefKey);
    }

    private void handleNonceResponse(final byte[] extPayload) {
        if (extPayload.length < 1 + OuraAuth.NONCE_LEN) {
            LOG.error("Short nonce response: {}", StringUtils.bytesToHex(extPayload));
            disconnectWithError("Auth nonce malformed");
            return;
        }
        final byte[] nonce = new byte[OuraAuth.NONCE_LEN];
        System.arraycopy(extPayload, 1, nonce, 0, OuraAuth.NONCE_LEN);

        final byte[] key = readAuthKey();
        if (key == null) {
            LOG.error("Oura auth key missing or malformed");
            disconnectWithError("Auth key missing — paste 32 hex chars in device auth settings");
            return;
        }

        final byte[] ciphertext;
        try {
            ciphertext = OuraAuth.computeAuthResponse(nonce, key);
        } catch (final Exception e) {
            LOG.error("Auth ciphertext computation failed", e);
            disconnectWithError("Auth crypto failed");
            return;
        }
        sendCommand(OuraPacket.frameExt(OuraOpcode.EXT_AUTH, ciphertext), "oura-auth");
    }

    private void handleAuthResponse(final byte[] extPayload) {
        if (extPayload.length < 2) {
            return;
        }
        final int status = extPayload[1] & 0xff;
        switch (status) {
            case OuraOpcode.AUTH_OK:
                LOG.info("Oura auth OK");
                postAuthPipeline();
                break;
            case OuraOpcode.AUTH_FAIL:
                disconnectWithError("Auth rejected — verify the auth key");
                break;
            case OuraOpcode.AUTH_FACTORY_RESET:
                disconnectWithError("Ring is in factory reset");
                break;
            case OuraOpcode.AUTH_DIFFERENT_DEVICE:
                disconnectWithError("Auth key does not match this ring");
                break;
            default:
                disconnectWithError("Unknown auth status 0x" + Integer.toHexString(status));
                break;
        }
    }

    private void requestNonce() {
        sendCommand(OuraPacket.frameExt(OuraOpcode.EXT_GET_NONCE, new byte[0]), "oura-get-nonce");
    }

    private void preAuthPipeline() {
        // Capability probes match the observed pairing flow (pages 0 and 1). The
        // payload is informational; we don't act on it but the ring expects them
        // before issuing a nonce.
        sendCommand(OuraPacket.frameExt(OuraOpcode.EXT_GET_CAPS, new byte[]{0x00}), "oura-get-caps-0");
        sendCommand(OuraPacket.frameExt(OuraOpcode.EXT_GET_CAPS, new byte[]{0x01}), "oura-get-caps-1");
        requestNonce();
    }

    private void postAuthPipeline() {
        // Ring expects BLE-mode SYNC before the time-sync write; without it some firmwares
        // refuse to flush the event log.
        sendCommand(OuraPacket.frame(OuraOpcode.OP_SET_BLE_MODE, new byte[]{(byte) OuraOpcode.BLE_MODE_SYNC}), "oura-ble-mode-sync");
        syncUnixAtSync = System.currentTimeMillis() / 1000L;
        sendCommand(OuraTimeSync.buildSyncTimeFrameNow(), "oura-sync-time");
    }

    private void postSyncTimePipeline() {
        // Notification flags 0xBF = 0b10111111 — subscribes to all observed event categories.
        sendCommand(OuraPacket.frame(OuraOpcode.OP_SET_NOTIFICATION, new byte[]{(byte) 0xBF}), "oura-set-notification");
        // Product-info fields drained one at a time. Format: [field, off=0x00, len]. See BLE.md § 0x18.
        sendProductInfo(0x14, 0x10);
        sendProductInfo(0x18, 0x10);
        sendProductInfo(0x28, 0x09);
        sendProductInfo(0x34, 0x04);
        sendProductInfo(0x04, 0x10);
        sendProductInfo(0x08, 0x10);
        sendCommand(OuraPacket.frame(OuraOpcode.OP_GET_BATTERY, new byte[0]), "oura-get-battery");
        // Sync toggle states from ring → prefs before the user sees the screen.
        sendGetFeatureStatus(OuraOpcode.FEATURE_SPO2);
        sendGetFeatureStatus(OuraOpcode.FEATURE_DAYTIME_HR);
        sendGetFeatureStatus(OuraOpcode.FEATURE_RESTING_HR);
        sendGetFeatureStatus(OuraOpcode.FEATURE_EXERCISE_HR);
        sendGetFeatureStatus(OuraOpcode.FEATURE_REAL_STEPS);
        sendGetFeatureStatus(OuraOpcode.FEATURE_TAP_TO_TAG);
        sendGetFeatureStatus(OuraOpcode.FEATURE_CHARGING_CONTROL);
        sendCommand(OuraPacket.frameExt(OuraOpcode.EXT_SET_BUNDLING, new byte[]{0x01}), "oura-bundling-on");
        sendCommand(OuraPacket.frame(OuraOpcode.OP_CHECK_SLEEP_ANALYSIS, new byte[]{0x00}), "oura-check-sleep-analysis");
        // Flip to INITIALIZED before the event drain. State.CONNECTED maps to "Connecting…"
        // in the simple state string used by the device card (see GBDevice.State); the actual
        // "Connected" label belongs to INITIALIZED. Drain summary (0x11) can take minutes on
        // a large backlog — gating the UI state on it stranded the user in "Connecting…".
        // Battery + firmware + features have already arrived, so the card renders fully.
        // handleEventsSummary still toggles BLE mode back to NORMAL when the drain ends.
        getDevice().setUpdateState(GBDevice.State.INITIALIZED, getContext());
        startEventDrain();
    }

    private void sendProductInfo(final int field, final int len) {
        sendCommand(OuraPacket.frame(OuraOpcode.OP_GET_PRODUCT_INFO,
                new byte[]{(byte) (field & 0xff), 0x00, (byte) (len & 0xff)}),
                "oura-product-info-" + Integer.toHexString(field));
    }

    private void sendGetFeatureStatus(final int featureId) {
        sendCommand(OuraPacket.frameExt(OuraOpcode.EXT_GET_FEATURE_STATUS, new byte[]{(byte) (featureId & 0xff)}),
                "oura-get-feature-" + Integer.toHexString(featureId));
    }

    void sendSetFeatureMode(final int featureId, final int mode) {
        sendCommand(OuraPacket.frameExt(OuraOpcode.EXT_SET_FEATURE_MODE,
                new byte[]{(byte) (featureId & 0xff), (byte) (mode & 0xff)}),
                "oura-set-feature-" + Integer.toHexString(featureId));
    }

    private void startEventDrain() {
        final long cursor = readCursor();
        iterationStartCursor = cursor;
        pendingCursor = cursor;
        if (drainIterations == 0) {
            // First iteration of a fresh drain — surface "Fetching activity data N%" in the
            // device card + status-bar transfer notification so the user knows sync is in flight
            // and can gauge how much backlog remains. drainStartCursor is captured here so the
            // % moves across the full backlog (not just the current iteration). Subsequent
            // iterations re-fire updateTransferNotification with the new % but skip the
            // setBusyTask call (idempotent label) and the device-update intent (only needed
            // for the initial show).
            drainStartCursor = cursor;
            lastPublishedPercent = -1;
            publishDrainProgress(cursor);
            getDevice().setBusyTask(R.string.busy_task_fetch_activity_data, getContext());
            getDevice().sendDeviceUpdateIntent(getContext());
            // Request HIGH connection priority for the duration of the drain. Default BALANCED
            // negotiates a ~30-50 ms BLE interval; HIGH drops it to ~7.5-15 ms, which is the
            // dominant factor in drain throughput because each iteration is a single GetEvents
            // round-trip + multiple HVN bundle frames. finishDrain() restores the user's pref
            // (BALANCED or LOW_POWER) so we don't keep the radio hot idle.
            requestPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
        }
        final byte[] start = OuraPacket.u32LE(cursor);
        final byte[] max = new byte[]{(byte) 0xff};
        final byte[] tail = new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff};
        final byte[] body = new byte[start.length + max.length + tail.length];
        System.arraycopy(start, 0, body, 0, start.length);
        System.arraycopy(max, 0, body, start.length, max.length);
        System.arraycopy(tail, 0, body, start.length + max.length, tail.length);
        sendCommand(OuraPacket.frame(OuraOpcode.OP_GET_EVENTS, body), "oura-get-events");
    }

    private void sendBleModeNormal() {
        sendCommand(OuraPacket.frame(OuraOpcode.OP_SET_BLE_MODE, new byte[]{(byte) OuraOpcode.BLE_MODE_NORMAL}), "oura-ble-mode-normal");
    }

    /** Sink reused across all bundles in a drain to skip per-bundle allocation cost. Reset
     *  before each parse + flushed to DB once afterwards. Not thread-safe — only ever touched
     *  on the BLE in thread. */
    private final OuraEventSink reusableSink = new OuraEventSink();

    private void handleBundledEvents(final byte[] frame) {
        reusableSink.reset();
        final int parsed = OuraEventParser.parseFrame(frame, reusableSink, bootClock);
        if (parsed > 0 && reusableSink.maxBootTs > 0) {
            pendingCursor = Math.max(pendingCursor, reusableSink.maxBootTs + 1);
            publishDrainProgress(pendingCursor);
        }
        if (parsed > 0) {
            reusableSink.flush();
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Oura parsed {} events, cursor={}", parsed, pendingCursor);
        }
    }

    /** Push current drain progress to the status-bar transfer notification. Percentage =
     *  (cursor - drainStartCursor) / (drainTargetCursor - drainStartCursor), clamped to
     *  [0, 100]. If drainTargetCursor is 0 (SyncTime response not yet handled) or equal to
     *  drainStartCursor (nothing to drain), fall back to indeterminate (0). */
    private void publishDrainProgress(final long cursor) {
        final int percent;
        final long span = drainTargetCursor - drainStartCursor;
        if (drainTargetCursor <= 0 || span <= 0) {
            percent = 0;
        } else {
            final long done = Math.max(0L, cursor - drainStartCursor);
            percent = (int) Math.min(100L, (done * 100L) / span);
        }
        if (percent == lastPublishedPercent) {
            return;
        }
        lastPublishedPercent = percent;
        final String label = getContext().getString(R.string.busy_task_fetch_activity_data)
                + " " + percent + "%";
        GB.updateTransferNotification(label, "", true, percent, getContext());
    }

    /** Emit a per-tag payload dump at INFO until the per-connection budget is exhausted,
     *  then fall through to DEBUG. Keeps the first N records of high-volume / unknown event
     *  tags visible in logcat while keeping the rest in DEBUG so logs stay readable. The
     *  bytesToHex call is gated on the actual log level so DEBUG-disabled builds skip the
     *  string allocation entirely (a non-trivial CPU cost across thousands of bundled events
     *  per drain). */
    private void logBoundedInfo(final int budgetKey, final int budgetSize,
                                final int tag, final long wallMs, final byte[] payload) {
        final Integer current = infoLogBudget.get(budgetKey);
        final int remaining = current != null ? current : budgetSize;
        if (remaining > 0) {
            if (LOG.isInfoEnabled()) {
                LOG.info("Oura event 0x{} ts={} payload={}", Integer.toHexString(tag), wallMs, StringUtils.bytesToHex(payload));
            }
            infoLogBudget.put(budgetKey, remaining - 1);
            return;
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Oura event 0x{} ts={} payload={}", Integer.toHexString(tag), wallMs, StringUtils.bytesToHex(payload));
        }
    }

    private void persistCursor(final long cursor) {
        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        prefs.edit().putLong(OuraConstants.PREF_OURA_LAST_EVENT_TS, cursor).apply();
    }

    private long readCursor() {
        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        final long stored = prefs.getLong(OuraConstants.PREF_OURA_LAST_EVENT_TS, 0L);
        // Orphan-cursor detection must only fire at the very start of a session (drainIterations
        // == 0). The drain loop re-enters readCursor on every iteration via startEventDrain;
        // running cursorIsOrphan() per-iter is wrong because:
        //   1. After iter N persists cursor=X, sample tables are still empty IFF the drained
        //      events haven't reached HR/IBI/activity-emitting wall-clock windows yet.
        //   2. cursorIsOrphan() would then return true and reset stored cursor to 0.
        //   3. Iter N+1 sends GetEvents(0) again — ring returns the same earliest batch.
        //   4. Cursor oscillates between 0 and the first batch's max tick until
        //      DRAIN_MAX_ITERATIONS hits. Observed in 17:54 capture: iter 1271-1320+ stuck.
        if (stored > 0 && drainIterations == 0 && cursorIsOrphan()) {
            // DB import (via ZipBackupImportJob.run) wipes Oura sample rows but does not touch
            // device-specific pref files — it iterates only the devices present in the restored
            // DB, so Oura's prefs (including this cursor) survive. Detect that mismatch and
            // reset to 0 so the next drain starts from the ring's oldest stored event instead
            // of skipping the entire backlog. Also catches manual DB wipes / fresh-install
            // restores from older backups that predate Oura support.
            LOG.warn("Oura cursor {} present but no sample rows for this device — DB import or wipe detected. Resetting cursor to 0 to re-drain ring backlog.", stored);
            prefs.edit().putLong(OuraConstants.PREF_OURA_LAST_EVENT_TS, 0L).apply();
            return 0L;
        }
        return stored;
    }

    private boolean cursorIsOrphan() {
        try (final DBHandler db = GBApplication.acquireDbReadOnly()) {
            final DaoSession session = db.getDaoSession();
            final Device d = DBHelper.findDevice(getDevice(), session);
            if (d == null) {
                return false;
            }
            final long deviceId = d.getId();
            // Check the two tables guaranteed to receive rows on any non-trivial drain. Activity
            // (0x50) fires hourly during wear; IBI (0x60) fires multiple times per minute on
            // a worn ring. If BOTH are empty for this device, no prior drain has persisted —
            // either fresh pair (cursor would be 0, never reach here) or post-import orphan.
            final long activityCount = session.getOuraActivitySampleDao().queryBuilder()
                    .where(OuraActivitySampleDao.Properties.DeviceId.eq(deviceId)).count();
            if (activityCount > 0) {
                return false;
            }
            final long ibiCount = session.getHeartRrIntervalSampleDao().queryBuilder()
                    .where(HeartRrIntervalSampleDao.Properties.DeviceId.eq(deviceId)).count();
            return ibiCount == 0;
        } catch (final Exception e) {
            LOG.error("Failed to probe Oura cursor orphan state — leaving stored cursor as-is", e);
            return false;
        }
    }

    private byte[] readAuthKey() {
        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        final String hex = prefs.getString(DeviceSettingsPreferenceConst.PREF_AUTH_KEY, null);
        return OuraAuth.parseHexKey(hex);
    }

    private void disconnectWithError(final String message) {
        LOG.error("Oura: {}", message);
        GB.toast(getContext(), message, Toast.LENGTH_LONG, GB.ERROR);
        // Clear any in-flight drain transfer notification — GBDevice.reset() drops busyTask
        // automatically on disconnect, but the status-bar transfer notif is independent.
        GB.updateTransferNotification(null, "", false, 100, getContext());
        getDevice().setState(GBDevice.State.NOT_CONNECTED);
        getDevice().sendDeviceUpdateIntent(getContext());
    }

    private void sendCommand(final byte[] frame, final String taskName) {
        final TransactionBuilder builder = createTransactionBuilder(taskName);
        write(builder, frame);
        builder.queue();
    }

    private void write(final TransactionBuilder builder, final byte[] frame) {
        builder.write(OuraUUIDs.CHAR_WRITE, frame);
    }

    private final class OuraEventSink implements OuraEventParser.EventSink {
        long maxBootTs = 0;
        // Per-bundle accumulation buffers. Each on* handler appends here; flush() writes them all
        // in a single DBHandler scope via insertOrReplaceInTx, avoiding ~14 separate DB opens per
        // HVN bundle. Lists are reset (cleared) at the start of each handleBundledEvents call.
        private final List<HeartRrIntervalSample> ibis = new ArrayList<>();
        private final List<GenericHeartRateSample> heartRates = new ArrayList<>();
        private final List<GenericTemperatureSample> temperatures = new ArrayList<>();
        private final List<GenericSpo2Sample> spo2s = new ArrayList<>();
        private final List<GenericHrvValueSample> hrvs = new ArrayList<>();
        private final List<GenericSleepStageSample> sleepStages = new ArrayList<>();
        private final List<OuraSleepSessionSample> sleepSessions = new ArrayList<>();
        private final List<OuraRecoverySample> recoveries = new ArrayList<>();
        private final List<OuraActivitySample> activities = new ArrayList<>();

        void reset() {
            maxBootTs = 0;
            ibis.clear();
            heartRates.clear();
            temperatures.clear();
            spo2s.clear();
            hrvs.clear();
            sleepStages.clear();
            sleepSessions.clear();
            recoveries.clear();
            activities.clear();
        }

        /** Single-pass DB write of all accumulated samples for this bundle. Resolves
         *  userId/deviceId once, then issues one insertOrReplaceInTx per non-empty type list.
         *  Activity samples go through OuraActivitySampleProvider.addGBActivitySample
         *  one-by-one (it overrides for steps merge / kind normalization), but greenDAO's
         *  tx-per-call is still amortized across the whole bundle vs. opening a new
         *  DBHandler+session per event in the prior code path. */
        void flush() {
            if (ibis.isEmpty() && heartRates.isEmpty() && temperatures.isEmpty()
                    && spo2s.isEmpty() && hrvs.isEmpty() && sleepStages.isEmpty()
                    && sleepSessions.isEmpty() && recoveries.isEmpty() && activities.isEmpty()) {
                return;
            }
            try (final DBHandler db = GBApplication.acquireDB()) {
                final DaoSession session = db.getDaoSession();
                final long userId = resolveUserId(session);
                final long deviceId = resolveDeviceId(session);
                if (!ibis.isEmpty()) {
                    for (final HeartRrIntervalSample s : ibis) { s.setUserId(userId); s.setDeviceId(deviceId); }
                    new HeartRrIntervalSampleProvider(getDevice(), session).addSamples(ibis);
                }
                if (!heartRates.isEmpty()) {
                    for (final GenericHeartRateSample s : heartRates) { s.setUserId(userId); s.setDeviceId(deviceId); }
                    new GenericHeartRateSampleProvider(getDevice(), session).addSamples(heartRates);
                }
                if (!temperatures.isEmpty()) {
                    for (final GenericTemperatureSample s : temperatures) { s.setUserId(userId); s.setDeviceId(deviceId); }
                    new GenericTemperatureSampleProvider(getDevice(), session,
                            TemperatureSample.TYPE_SKIN, TemperatureSample.LOCATION_FINGER)
                            .addSamples(temperatures);
                }
                if (!spo2s.isEmpty()) {
                    for (final GenericSpo2Sample s : spo2s) { s.setUserId(userId); s.setDeviceId(deviceId); }
                    new GenericSpo2SampleProvider(getDevice(), session).addSamples(spo2s);
                }
                if (!hrvs.isEmpty()) {
                    for (final GenericHrvValueSample s : hrvs) { s.setUserId(userId); s.setDeviceId(deviceId); }
                    new GenericHrvValueSampleProvider(getDevice(), session).addSamples(hrvs);
                }
                if (!sleepStages.isEmpty()) {
                    for (final GenericSleepStageSample s : sleepStages) { s.setUserId(userId); s.setDeviceId(deviceId); }
                    new GenericSleepStageSampleProvider(getDevice(), session).addSamples(sleepStages);
                }
                if (!sleepSessions.isEmpty()) {
                    for (final OuraSleepSessionSample s : sleepSessions) { s.setUserId(userId); s.setDeviceId(deviceId); }
                    new OuraSleepSessionSampleProvider(getDevice(), session).addSamples(sleepSessions);
                }
                if (!recoveries.isEmpty()) {
                    for (final OuraRecoverySample s : recoveries) { s.setUserId(userId); s.setDeviceId(deviceId); }
                    new OuraRecoverySampleProvider(getDevice(), session).addSamples(recoveries);
                }
                if (!activities.isEmpty()) {
                    final OuraActivitySampleProvider activityProvider = new OuraActivitySampleProvider(getDevice(), session);
                    for (final OuraActivitySample a : activities) {
                        a.setUserId(userId);
                        a.setDeviceId(deviceId);
                        activityProvider.addGBActivitySample(a);
                    }
                }
            } catch (final Exception e) {
                LOG.error("Failed to flush Oura sample batch", e);
            }
        }

        private long resolveUserId(final DaoSession session) {
            try {
                final User user = DBHelper.getUser(session);
                return user != null ? user.getId() : 0L;
            } catch (final Exception e) {
                return 0L;
            }
        }

        private long resolveDeviceId(final DaoSession session) {
            try {
                final Device d = DBHelper.findDevice(getDevice(), session);
                return d != null ? d.getId() : 0L;
            } catch (final Exception e) {
                return 0L;
            }
        }

        @Override
        public void onRecordBootTs(final long bootTs) {
            // Parser feeds raw u32LE bootTs directly here, before wall-clock conversion. Cursor
            // advance must use raw bootTs — bootTicksOf(wallClockOf(x)) is lossy: BootClock
            // divides by TICKS_PER_SECOND (= 10) with integer math, dropping up to 9 ticks on
            // roundtrip. Near-cursor batches would otherwise produce maxBootTs ==
            // iterationStartCursor → cursorAdvanced=false → drain halts mid-backlog.
            if (bootTs > 0) {
                maxBootTs = Math.max(maxBootTs, bootTs);
            }
        }

        @Override
        public void onUnknown(final int tag, final long wallClockMs, final byte[] payload) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Oura event 0x{} ts={} payload={}", Integer.toHexString(tag), wallClockMs, StringUtils.bytesToHex(payload));
            }
        }

        @Override
        public void onIbi(final long wallClockMs, final byte[] payload) {
            if (payload.length < 2 || wallClockMs <= 0) {
                return;
            }
            // TBD: payload layout inferred — first u16LE interpreted as IBI ms.
            final int ibiMs = OuraPacket.readU16LE(payload, 0);
            final HeartRrIntervalSample rr = new HeartRrIntervalSample();
            rr.setTimestamp(wallClockMs);
            rr.setRrMillis(ibiMs);
            rr.setSeq((int) (wallClockMs & 0x7fffffff));
            ibis.add(rr);
            if (ibiMs > 250 && ibiMs < 2000) {
                final GenericHeartRateSample hr = new GenericHeartRateSample();
                hr.setTimestamp(wallClockMs);
                hr.setHeartRate((int) Math.round(60000.0 / ibiMs));
                heartRates.add(hr);
            }
        }

        @Override
        public void onTemp(final long wallClockMs, final byte[] payload) {
            // Tag 0x46 layout per BLE.md: three u16LE channels (skinA, skinB, ambient). Use the
            // mean of skin A/B as the canonical body temperature; ambient is the case sensor.
            if (payload.length < 6 || wallClockMs <= 0) {
                return;
            }
            final int skinA = OuraPacket.readU16LE(payload, 0);
            final int skinB = OuraPacket.readU16LE(payload, 2);
            queueTemperature(wallClockMs, ((skinA + skinB) / 2f) / 100f);
        }

        @Override
        public void onSleepTemp(final long wallClockMs, final byte[] payload) {
            // Tag 0x75 carries 7× u16LE readings of the skin thermistor for a ~10-min window.
            // Persist the mean as a single sample at wallClockMs — 7 nearly-identical points
            // distributed across the window would just add chart noise.
            if (payload.length < 2 || wallClockMs <= 0) {
                return;
            }
            int sum = 0;
            int valid = 0;
            for (int i = 0; i + 2 <= payload.length; i += 2) {
                final int raw = OuraPacket.readU16LE(payload, i);
                if (raw > 2000 && raw < 4500) {
                    sum += raw;
                    valid++;
                }
            }
            if (valid == 0) {
                return;
            }
            queueTemperature(wallClockMs, (sum / (float) valid) / 100f);
        }

        private void queueTemperature(final long wallClockMs, final float celsius) {
            if (celsius < 20f || celsius > 45f) {
                return;
            }
            final GenericTemperatureSample sample = new GenericTemperatureSample();
            sample.setTimestamp(wallClockMs);
            sample.setTemperature(celsius);
            sample.setTemperatureType(TemperatureSample.TYPE_SKIN);
            sample.setTemperatureLocation(TemperatureSample.LOCATION_FINGER);
            temperatures.add(sample);
        }

        @Override
        public void onSpo2(final long wallClockMs, final byte[] payload) {
            logBoundedInfo(OuraOpcode.EVT_SPO2 << 8, 20, OuraOpcode.EVT_SPO2, wallClockMs, payload);
            queueSpo2(wallClockMs, payload);
        }

        @Override
        public void onSpo2Smoothed(final long wallClockMs, final byte[] payload) {
            logBoundedInfo(OuraOpcode.EVT_SPO2_SMOOTH << 8, 20, OuraOpcode.EVT_SPO2_SMOOTH, wallClockMs, payload);
            queueSpo2(wallClockMs, payload);
        }

        private void queueSpo2(final long wallClockMs, final byte[] payload) {
            if (payload.length < 2 || wallClockMs <= 0) {
                return;
            }
            // Layout per BLE.md: [counter|quality u8][N× u8 percent]. Persist mean of valid readings.
            int sum = 0;
            int valid = 0;
            for (int i = 1; i < payload.length; i++) {
                final int v = payload[i] & 0xff;
                if (v >= 70 && v <= 100) {
                    sum += v;
                    valid++;
                }
            }
            if (valid == 0) {
                return;
            }
            final GenericSpo2Sample sample = new GenericSpo2Sample();
            sample.setTimestamp(wallClockMs);
            sample.setSpo2(sum / valid);
            spo2s.add(sample);
        }

        @Override
        public void onHrv(final long wallClockMs, final byte[] payload) {
            if (payload.length < 1 || wallClockMs <= 0) {
                return;
            }
            // Tag 0x5D carries N× u8 RMSSD values in ms (60–84 ms typical during sleep).
            // Sample interval unknown; spread 60 s apart so the chart shows a trend.
            for (int i = 0; i < payload.length; i++) {
                final int rmssd = payload[i] & 0xff;
                if (rmssd < 5 || rmssd > 250) {
                    continue;
                }
                final GenericHrvValueSample sample = new GenericHrvValueSample();
                sample.setTimestamp(wallClockMs + i * 60_000L);
                sample.setValue(rmssd);
                hrvs.add(sample);
            }
        }

        @Override
        public void onSleepHr(final long wallClockMs, final byte[] payload) {
            if (payload.length < 1 || wallClockMs <= 0) {
                return;
            }
            final int hr = payload[0] & 0xff;
            if (hr < 20 || hr > 220) {
                return;
            }
            final GenericHeartRateSample sample = new GenericHeartRateSample();
            sample.setTimestamp(wallClockMs);
            sample.setHeartRate(hr);
            heartRates.add(sample);
        }

        @Override
        public void onSleepPhase(final long wallClockMs, final byte[] payload) {
            if (payload.length < 1 || wallClockMs <= 0) {
                return;
            }
            final OuraSleepSessionSample sample = new OuraSleepSessionSample();
            sample.setTimestamp(wallClockMs);
            sample.setWakeupTime(wallClockMs);
            sleepSessions.add(sample);
        }

        @Override
        public void onSleepPhaseDetail(final long wallClockMs, final byte[] payload) {
            if (payload.length < 2 || wallClockMs <= 0) {
                return;
            }
            final int stage = payload[0] & 0xff;
            final int durationFiveMin = payload[1] & 0xff;
            final GenericSleepStageSample sample = new GenericSleepStageSample();
            sample.setTimestamp(wallClockMs);
            sample.setStage(mapSleepStage(stage));
            sample.setDuration(durationFiveMin * 300);
            sleepStages.add(sample);
        }

        @Override
        public void onActivity(final long wallClockMs, final byte[] payload) {
            if (payload.length < 1 || wallClockMs <= 0) {
                return;
            }
            // Tag 0x50 API_ACTIVITY_INFO — see BLE.md for proto layout. byte 0 = stepCount,
            // bytes 1..N = 13× u8 metLevel counts (sum used as coarse intensity proxy).
            if (LOG.isInfoEnabled()) {
                LOG.info("Oura activity event ts={} payload={} len={}",
                        wallClockMs, StringUtils.bytesToHex(payload), payload.length);
            }
            final int stepCount = payload[0] & 0xff;
            int metSum = 0;
            for (int i = 1; i < payload.length; i++) {
                metSum += payload[i] & 0xff;
            }
            final OuraActivitySample sample = new OuraActivitySample();
            sample.setTimestamp((int) (wallClockMs / 1000L));
            sample.setSteps(stepCount);
            sample.setRawIntensity(metSum);
            sample.setRawKind(stepCount > 0 || metSum > 0
                    ? ActivityKind.ACTIVITY.getCode()
                    : ActivityKind.NOT_WORN.getCode());
            sample.setHeartRate(0);
            activities.add(sample);
        }

        @Override
        public void onBedtime(final long wallClockMs, final byte[] payload) {
            if (payload.length < 8 || wallClockMs <= 0 || bootClock == null) {
                return;
            }
            // Tag 0x76: [start u32LE bootTs][end u32LE bootTs] — single record per sleep window.
            final long startBoot = OuraPacket.readU32LE(payload, 0);
            final long endBoot = OuraPacket.readU32LE(payload, 4);
            final long startMs = bootClock.wallClockOf(startBoot) * 1000L;
            final long endMs = bootClock.wallClockOf(endBoot) * 1000L;
            if (endMs <= startMs) {
                return;
            }
            final OuraSleepSessionSample sample = new OuraSleepSessionSample();
            sample.setTimestamp(startMs);
            sample.setWakeupTime(endMs);
            sleepSessions.add(sample);
        }

        @Override
        public void onRecovery(final long wallClockMs, final byte[] payload) {
            if (payload.length < 1 || wallClockMs <= 0) {
                return;
            }
            final int readiness = payload[0] & 0xff;
            final int sleepScore = payload.length > 1 ? payload[1] & 0xff : 0;
            final int activityScore = payload.length > 2 ? payload[2] & 0xff : 0;
            final OuraRecoverySample sample = new OuraRecoverySample();
            sample.setTimestamp(wallClockMs);
            sample.setReadinessScore(readiness);
            sample.setSleepScore(sleepScore);
            sample.setActivityScore(activityScore);
            recoveries.add(sample);
        }

        @Override
        public void onTap(final long wallClockMs, final byte[] payload) {
            // Tag 0x7A — tap-to-tag gesture. Layout not yet reverse-engineered. Surface as a
            // system notification + log line. Notification is the user-visible artifact, so we
            // pay the bytesToHex cost unconditionally here (one event per tap, not in the hot
            // drain path).
            final String hex = StringUtils.bytesToHex(payload);
            LOG.info("Oura tap detected ts={} payload={}", wallClockMs, hex);
            postTapNotification(wallClockMs, hex);
        }

        @Override
        public void onStress(final long wallClockMs, final byte[] payload) {
            // Tag 0x59 — EDA-derived daytime stress. Layout TBD; log raw bytes for analysis.
            if (LOG.isInfoEnabled()) {
                LOG.info("Oura stress event ts={} payload={}", wallClockMs, StringUtils.bytesToHex(payload));
            }
        }

        // -- Bounded-INFO payload dumps for analysis (semantics inferred / unknown) ----------------

        @Override
        public void onIbiAmp(final long wallClockMs, final byte[] payload) {
            // Tag 0x60 API_IBI_AND_AMPLITUDE_EVENT — proto: parallel ibi[]/amp[]/timestamp[].
            // Wire layout (decoded against ground truth — overnight ~50 bpm, daytime max ~120 bpm):
            //   bytes 0..6  = u8 IBI codes (multiply by 8 → ms)
            //   bytes 7..13 = u8 amplitude quality (currently unused for charting)
            // Zero IBI bytes are end-of-record sentinels (record carried fewer than 7 beats).
            // Record's bootTs marks the wall clock of the LAST beat — earlier beats are placed
            // backwards by accumulating their IBI so HR samples land at their true wall time.
            logBoundedInfo(OuraOpcode.EVT_IBI_AMP << 8, 20, OuraOpcode.EVT_IBI_AMP, wallClockMs, payload);
            queueIbiAmpRecord(wallClockMs, payload);
        }

        @Override
        public void onGreenIbiAmp(final long wallClockMs, final byte[] payload) {
            // Tag 0x71 API_GREEN_IBI_AND_AMP_EVENT — same protobuf schema as 0x60. Reuse decoder.
            queueIbiAmpRecord(wallClockMs, payload);
        }

        @Override
        public void onSpo2IbiAmp(final long wallClockMs, final byte[] payload) {
            // Tag 0x6E API_SPO2_IBI_AND_AMPLITUDE_EVENT — IBI captured during SpO2 measurement.
            // Same proto schema as 0x60. The HR derived from these beats is a real HR signal.
            queueIbiAmpRecord(wallClockMs, payload);
        }

        /** Decode a 14-byte IbiAndAmp record (tags 0x60, 0x71, 0x6E share the same proto schema)
         *  and append derived RR + HR samples to the bundle batch. Earliest beat backward in
         *  time from the record's wall-clock anchor. Persisted by flush() in one tx. */
        private void queueIbiAmpRecord(final long wallClockMs, final byte[] payload) {
            if (payload.length < 7 || wallClockMs <= 0) {
                return;
            }
            final int[] ibiMsList = new int[7];
            int validCount = 0;
            for (int i = 0; i < 7; i++) {
                final int code = payload[i] & 0xff;
                if (code == 0) continue;
                final int ibiMs = code * 8;
                if (ibiMs < 250 || ibiMs > 2000) continue;
                ibiMsList[validCount++] = ibiMs;
            }
            if (validCount == 0) {
                return;
            }
            long ts = wallClockMs;
            for (int i = validCount - 1; i >= 0; i--) {
                final int ibiMs = ibiMsList[i];
                final HeartRrIntervalSample rr = new HeartRrIntervalSample();
                rr.setTimestamp(ts);
                rr.setRrMillis(ibiMs);
                rr.setSeq((int) (ts & 0x7fffffff));
                ibis.add(rr);

                final GenericHeartRateSample hr = new GenericHeartRateSample();
                hr.setTimestamp(ts);
                hr.setHeartRate((int) Math.round(60000.0 / ibiMs));
                heartRates.add(hr);

                ts -= ibiMs;
            }
        }

        @Override
        public void onMetric(final long wallClockMs, final byte[] payload) {
            // Sub-typed: first body byte identifies the metric family. Cap is per sub-type.
            final int sub = payload.length > 0 ? (payload[0] & 0xff) : 0;
            logBoundedInfo((OuraOpcode.EVT_METRIC << 8) | sub, 5, OuraOpcode.EVT_METRIC, wallClockMs, payload);
        }

        // Log-only tags below. No DB persistence — pure noise-budget-bounded dumps for analysis.
        // Most of these are emitted at very high cadence; the bounded-INFO budget keeps the first
        // N records of a connection at INFO, the rest at DEBUG (gated by isDebugEnabled in
        // logBoundedInfo so we don't pay bytesToHex for filtered-out DEBUG output).

        @Override
        public void onSensor6E(final long wallClockMs, final byte[] payload) {
            logBoundedInfo(OuraOpcode.EVT_SENSOR_6E << 8, 20, OuraOpcode.EVT_SENSOR_6E, wallClockMs, payload);
        }

        @Override
        public void onMetric72(final long wallClockMs, final byte[] payload) {
            logBoundedInfo(OuraOpcode.EVT_METRIC_72 << 8, 20, OuraOpcode.EVT_METRIC_72, wallClockMs, payload);
        }

        @Override
        public void onStream77(final long wallClockMs, final byte[] payload) {
            logBoundedInfo(OuraOpcode.EVT_STREAM_77 << 8, 20, OuraOpcode.EVT_STREAM_77, wallClockMs, payload);
        }

        @Override
        public void onPpgDelta(final long wallClockMs, final byte[] payload) {
            logBoundedInfo(OuraOpcode.EVT_PPG_DELTA_81 << 8, 20, OuraOpcode.EVT_PPG_DELTA_81, wallClockMs, payload);
        }

        @Override
        public void onTempPeriod(final long wallClockMs, final byte[] payload) {
            // Tag 0x69 API_TEMP_PERIOD — single u16LE skin-temp summary for a multi-minute window.
            logBoundedInfo(OuraOpcode.EVT_TEMP_PERIOD << 8, 200, OuraOpcode.EVT_TEMP_PERIOD, wallClockMs, payload);
            if (payload.length < 2 || wallClockMs <= 0) {
                return;
            }
            final int raw = OuraPacket.readU16LE(payload, 0);
            if (raw < 2000 || raw > 4500) {
                return;
            }
            queueTemperature(wallClockMs, raw / 100f);
        }

        @Override
        public void onStatus82(final long wallClockMs, final byte[] payload) {
            logBoundedInfo(OuraOpcode.EVT_STATUS_82 << 8, 200, OuraOpcode.EVT_STATUS_82, wallClockMs, payload);
        }

        @Override
        public void onPeriod83(final long wallClockMs, final byte[] payload) {
            logBoundedInfo(OuraOpcode.EVT_PERIOD_83 << 8, 200, OuraOpcode.EVT_PERIOD_83, wallClockMs, payload);
        }

        @Override
        public void onState6C(final long wallClockMs, final byte[] payload) {
            logBoundedInfo(OuraOpcode.EVT_STATE_6C << 8, 200, OuraOpcode.EVT_STATE_6C, wallClockMs, payload);
        }

        @Override
        public void onInternal5B(final long wallClockMs, final byte[] payload) {
            logBoundedInfo(OuraOpcode.EVT_INTERNAL_5B << 8, 200, OuraOpcode.EVT_INTERNAL_5B, wallClockMs, payload);
        }

        @Override
        public void onRawPpg6B(final long wallClockMs, final byte[] payload) {
            logBoundedInfo(OuraOpcode.EVT_RAW_PPG_6B << 8, 20, OuraOpcode.EVT_RAW_PPG_6B, wallClockMs, payload);
        }
    }

    private void postTapNotification(final long wallClockMs, final String payloadHex) {
        final Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        final int notificationId = OURA_TAP_NOTIFICATION_BASE_ID
                + (int) ((wallClockMs / 1000L) & 0x7fffffff);
        final String body = "Payload: " + (payloadHex.isEmpty() ? "(empty)" : payloadHex);
        final Notification notification = new NotificationCompat.Builder(ctx, GB.NOTIFICATION_CHANNEL_HIGH_PRIORITY_ID)
                .setSmallIcon(R.drawable.ic_device_smartring)
                .setContentTitle("Oura: Tap detected")
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();
        GB.notify(notificationId, notification, ctx);
    }

    private static final int OURA_TAP_NOTIFICATION_BASE_ID = 0x4f550000;

    private int mapSleepStage(final int rawStage) {
        switch (rawStage) {
            case 1:
                return OuraConstants.SLEEP_STAGE_AWAKE;
            case 2:
                return OuraConstants.SLEEP_STAGE_LIGHT;
            case 3:
                return OuraConstants.SLEEP_STAGE_DEEP;
            case 4:
                return OuraConstants.SLEEP_STAGE_REM;
            default:
                return rawStage;
        }
    }

}
