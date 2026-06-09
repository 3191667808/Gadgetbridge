/*  Copyright (C) 2026 The Gadgetbridge Project

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.r20;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHeartRateSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHrvValueSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericMetricSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSleepStageSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSpo2SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericStressSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericTemperatureSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.r20.R20Constants;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericBloodPressureSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericBloodPressureSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHrvValueSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericMetricSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSleepStageSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSpo2Sample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericStressSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.MetricSample;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser;
import nodomain.freeyourgadget.gadgetbridge.model.RecordedDataTypes;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattCharacteristic;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/**
 * Gadgetbridge driver for the R20 smart ring (Yucheng YCBT family).
 * Internal firmware name "R11M".
 *
 * <p>Verified working on firmware 2.32:
 * <ul>
 *   <li>Connect / no-bond pairing</li>
 *   <li>Set time, enable health sensors, enable background SpO2 monitor</li>
 *   <li>Query device name, info / fw version, battery, step count</li>
 *   <li>Standard 0x180D Heart Rate Service notify (with sensor-contact filter)</li>
 *   <li>Manual HR / BP / SpO2 measurement via {@code APP_START_MEASUREMENT}</li>
 *   <li>Real-time push frames: HR (0x0601 = bpm), SpO2 (0x0602 = %),
 *       BP (0x0603 = sys/dia/HR)</li>
 *   <li>History sync: Sport / Sleep / HR / BP (multi-packet, app sends ACK back)</li>
 *   <li>Derived metrics: HRV proxy, sleep score, sleep debt, VO2max, body battery,
 *       heart-rate recovery, sleep regularity index — see {@link R20DerivedMetrics}</li>
 * </ul>
 */
public class R20DeviceSupport extends AbstractBTLESingleDeviceSupport {

    private static final Logger LOG = LoggerFactory.getLogger(R20DeviceSupport.class);

    /** Buffer real-time HR notifications to one DB write per N samples
     *  (the standard 0x2A37 stream fires at ~1 Hz). Lowered from 30 → 5
     *  to avoid losing samples on early disconnect / sparse HR streams. */
    private static final int HR_BUFFER_FLUSH_THRESHOLD = 5;
    /** Force-flush HR buffer at least this often (ms) even if threshold not reached. */
    private static final long HR_BUFFER_FLUSH_INTERVAL_MS = 30_000L;

    /**
     * Read-only ring policy: Gadgetbridge never instructs the R20 to delete
     * its on-device history buffer.  The ring's records remain intact so other
     * companion apps (the OEM "SmartHealth" app, other watches/rings sharing
     * this Yucheng firmware family, etc.) keep working.
     *
     * <p>To avoid re-processing the same records on every reconnect we track a
     * per-metric "high-water mark" (newest record timestamp we've already
     * persisted) in the device-scoped {@link android.content.SharedPreferences}.
     * Records with timestamp ≤ HWM are dropped before any DB write, which keeps
     * DAO traffic linear in <em>new</em> data instead of total on-ring history.
     *
     * <p>Pref keys live under the per-device prefs namespace so multiple R20
     * rings paired to the same phone don't collide.
     */
    private static final String PREF_HWM_PREFIX = "r20_history_hwm_";
    static final String HWM_HR     = PREF_HWM_PREFIX + "hr";
    static final String HWM_BP     = PREF_HWM_PREFIX + "bp";
    static final String HWM_SLEEP  = PREF_HWM_PREFIX + "sleep";
    static final String HWM_SPORT  = PREF_HWM_PREFIX + "sport";
    static final String HWM_SPORT_MODE = PREF_HWM_PREFIX + "sport_mode";
    static final String HWM_ALL    = PREF_HWM_PREFIX + "all";
    private final List<GenericHeartRateSample> hrBuffer = new ArrayList<>();
    private final Object hrBufferLock = new Object();
    private long hrBufferLastFlushMs = 0L;

    /** Tracks whether we've already attempted a stale-bond recovery this session
     *  to avoid spinning if the firmware persistently refuses our commands. */
    private final java.util.concurrent.atomic.AtomicBoolean recoveryAttempted = new java.util.concurrent.atomic.AtomicBoolean(false);

    public R20DeviceSupport() {
        super(LOG);
        addSupportedService(GattService.UUID_SERVICE_HEART_RATE);
        addSupportedService(R20Constants.UUID_SERVICE);
    }

    @Override
    public boolean useAutoConnect() {
        return false;
    }

    @Override
    public boolean getImplicitCallbackModify() {
        return true;
    }

    @Override
    protected TransactionBuilder initializeDevice(final TransactionBuilder builder) {
        builder.setDeviceState(GBDevice.State.INITIALIZING);

        // Enable indications on the vendor write+indicate characteristic (BE94/0001)
        // and the vendor notify-only characteristic (BE94/0003).  The SDK enables
        // them in this order on connect.
        builder.notify(R20Constants.UUID_CHAR_WRITE,  true);
        builder.notify(R20Constants.UUID_CHAR_NOTIFY, true);

        // Standard heart-rate service push (0x2A37).
        builder.notify(GattCharacteristic.UUID_CHARACTERISTIC_HEART_RATE_MEASUREMENT, true);

        // Time + sensor activation. Without ENABLE_HEALTH_SENSORS the firmware
        // accepts measurement-start commands but never fires the PPG LEDs
        // (the standard 0x2A37 stream returns a stale value with sensor-contact
        // flag = NO).
        writePacket(builder, R20Packet.settingTime());
        applySpo2MonitoringPreferences(builder);
        writePacket(builder, R20Packet.enableHealthSensors(true));

        // Initial info pulls.
        writePacket(builder, R20Packet.getDeviceName());
        writePacket(builder, R20Packet.getDeviceInfo());
        writePacket(builder, R20Packet.getPowerStatistics());
        writePacket(builder, R20Packet.getNowStep());

        // Drain the on-ring composite history buffer (HR / BP / SpO2 / sleep / sport)
        // immediately on connect. The firmware samples autonomously between sessions;
        // without this initial drain the user only sees data after a manual "sync now"
        // from the device-detail screen.
        writePacket(builder, R20Packet.healthHistory(R20Constants.HEALTH_HISTORY_HEART));
        writePacket(builder, R20Packet.healthHistory(R20Constants.HEALTH_HISTORY_BLOOD));
        writePacket(builder, R20Packet.healthHistory(R20Constants.HEALTH_HISTORY_SLEEP));
        writePacket(builder, R20Packet.healthHistory(R20Constants.HEALTH_HISTORY_SPORT));

        builder.setDeviceState(GBDevice.State.INITIALIZED);
        return builder;
    }

    private static void writePacket(TransactionBuilder builder, R20Packet pkt) {
        builder.write(R20Constants.UUID_CHAR_WRITE, pkt.encode());
    }

    /**
     * Read user-configured background SpO2 monitoring preferences and push
     * the corresponding firmware-side enable + interval commands. The R20's
     * onboard MCU then samples SpO2 autonomously and writes results to the
     * internal composite history buffer (opcode 0x0518), which we drain on
     * the next sync — no phone-side AlarmManager/WorkManager scheduling is
     * needed, so the BLE radio is only used during the existing sync
     * cadence.
     *
     * <p>Preference keys reused from the standard SpO2 settings screen:
     * <ul>
     *   <li>{@code spo2_all_day_monitoring_enabled} (boolean, default false)
     *   <li>{@code spo2_measurement_interval} (string, seconds; default "600" = 10 min)
     * </ul>
     * The interval is clamped to 1..240 minutes for the firmware opcode
     * (Yucheng SDK uses a single byte).
     */
    private void applySpo2MonitoringPreferences(TransactionBuilder builder) {
        boolean enabled = getDevicePrefs().getBoolean(
                DeviceSettingsPreferenceConst.PREF_SPO2_ALL_DAY_MONITORING, false);
        int intervalSec;
        try {
            intervalSec = Integer.parseInt(getDevicePrefs().getString(
                    DeviceSettingsPreferenceConst.PREF_SPO2_MEASUREMENT_INTERVAL, "600"));
        } catch (NumberFormatException e) {
            intervalSec = 600;
        }
        int intervalMin = Math.max(1, Math.min(240, intervalSec / 60));
        LOG.info("R20 background SpO2 monitoring: enabled={} interval={}min",
                enabled, intervalMin);
        writePacket(builder, R20Packet.setMonitorInterval(intervalMin));
        writePacket(builder, R20Packet.enableBgSpO2Monitor(enabled));
    }

    @Override
    public void onSendConfiguration(final String config) {
        if (DeviceSettingsPreferenceConst.PREF_SPO2_ALL_DAY_MONITORING.equals(config)
                || DeviceSettingsPreferenceConst.PREF_SPO2_MEASUREMENT_INTERVAL.equals(config)) {
            try {
                TransactionBuilder b = createTransactionBuilder("R20 update SpO2 monitor pref");
                applySpo2MonitoringPreferences(b);
                b.queue();
            } catch (Exception e) {
                LOG.warn("R20 onSendConfiguration({}) failed", config, e);
            }
            return;
        }
        super.onSendConfiguration(config);
    }

    @Override
    public boolean onCharacteristicChanged(BluetoothGatt gatt,
                                           BluetoothGattCharacteristic ch,
                                           byte[] data) {
        if (super.onCharacteristicChanged(gatt, ch, data)) {
            return true;
        }
        UUID uuid = ch.getUuid();
        if (data == null || data.length == 0) return true;

        if (R20Constants.UUID_CHAR_WRITE.equals(uuid) || R20Constants.UUID_CHAR_NOTIFY.equals(uuid)) {
            R20Packet pkt = R20Packet.decode(data);
            if (pkt == null) {
                LOG.debug("R20 dropped malformed frame: {}", GB.hexdump(data));
                return true;
            }
            if (isRejection(pkt)) {
                triggerStaleBondRecovery(pkt);
                return true;
            }
            handleVendor(pkt);
            return true;
        }
        if (GattCharacteristic.UUID_CHARACTERISTIC_HEART_RATE_MEASUREMENT.equals(uuid)) {
            handleStandardHeartRate(data);
            return true;
        }
        return false;
    }

    private void handleVendor(R20Packet pkt) {
        int dtype = pkt.getDataType();
        byte[] p  = pkt.getPayload();
        switch (dtype) {
            case R20Constants.GET_DEVICE_NAME:
                LOG.info("R20 device name: {}", R20Packet.parseDeviceName(p));
                break;
            case R20Constants.GET_DEVICE_INFO: {
                String fw = R20Packet.parseFirmwareVersion(p);
                GBDeviceEventVersionInfo ev = new GBDeviceEventVersionInfo();
                ev.fwVersion = fw;
                evaluateGBDeviceEvent(ev);
                LOG.info("R20 fw version: {}", fw);
                break;
            }
            case R20Constants.GET_POWER_STATISTICS: {
                int pct = R20Packet.parseBatteryPercent(p);
                if (pct >= 0) {
                    GBDeviceEventBatteryInfo ev = new GBDeviceEventBatteryInfo();
                    ev.level = pct;
                    evaluateGBDeviceEvent(ev);
                }
                break;
            }
            case R20Constants.GET_NOW_STEP:
                LOG.info("R20 current step count: {}", R20Packet.parseStepCount(p));
                break;
            case R20Constants.REAL_UPLOAD_HEART:
                if (p.length >= 1) persistHr(System.currentTimeMillis(), p[0] & 0xFF);
                break;
            case R20Constants.REAL_UPLOAD_BLOOD_OXY:
                if (p.length >= 1) persistSpo2(System.currentTimeMillis(), p[0] & 0xFF);
                break;
            case R20Constants.REAL_UPLOAD_BLOOD_PRESS:
                if (p.length >= 3) {
                    int sys = p[0] & 0xFF, dia = p[1] & 0xFF, hr = p[2] & 0xFF;
                    LOG.info("R20 BP (0x0603): {}/{} mmHg @ {} bpm", sys, dia, hr);
                    persistBp(System.currentTimeMillis(), sys, dia, hr);
                    if (hr > 0) persistHr(System.currentTimeMillis(), hr);
                }
                break;
            case R20Constants.MEASUREMENT_COMPLETE:
                LOG.info("R20 measurement complete (0x040E), {} bytes", p.length);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("R20 measurement complete raw: {}", GB.hexdump(p));
                }
                break;
            case R20Constants.HEALTH_STREAM_HEART:
                handleHrHistory(p);
                break;
            case R20Constants.HEALTH_STREAM_BLOOD:
                handleBpHistory(p);
                break;
            case R20Constants.HEALTH_STREAM_SLEEP:
                handleSleepHistory(p);
                break;
            case R20Constants.HEALTH_STREAM_ALL:
                handleAllHistory(p);
                break;
            case R20Constants.HEALTH_STREAM_SPORT:
                handleSportHistory(p);
                break;
            case R20Constants.HEALTH_HISTORY_SPORT_MODE:
                // Empty-payload ACKs for our HEALTH_HISTORY_SPORT_MODE request
                // are handled by the no-op block below; non-empty frames carry
                // 25-byte session records.
                if (p.length >= 25) {
                    handleSportModeHistory(p);
                } else {
                    LOG.debug("R20 sport-mode history ACK dtype=0x{} (no data)",
                            Integer.toHexString(dtype));
                }
                break;
            case R20Constants.REAL_UPLOAD_SNAPSHOT:
                // Real-time multi-metric snapshot push (0x0600). Last byte is HR;
                // bytes 0-3 are a timestamp/sequence header — exact layout TBD.
                if (p.length >= 5) {
                    int hr = p[4] & 0xFF;
                    LOG.info("R20 snapshot (0x0600): hr={} bpm", hr);
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("R20 snapshot raw ({}b): {}", p.length, GB.hexdump(p));
                    }
                    if (hr > 0 && hr < 240) {
                        persistHr(System.currentTimeMillis(), hr);
                    }
                }
                break;
            case R20Constants.HEALTH_HISTORY_HEART:
            case R20Constants.HEALTH_HISTORY_BLOOD:
            case R20Constants.HEALTH_HISTORY_SLEEP:
            case R20Constants.HEALTH_HISTORY_SPORT:
            case R20Constants.HEALTH_HISTORY_ALL:
                // Empty-payload ACK from the ring acknowledging our history-fetch request.
                // Real data arrives separately via the HEALTH_STREAM_* opcodes; nothing to do here.
                LOG.debug("R20 history ACK dtype=0x{} (no data)", Integer.toHexString(dtype));
                break;
            case R20Constants.HEALTH_DELETE_SPORT:
            case R20Constants.HEALTH_DELETE_SLEEP:
            case R20Constants.HEALTH_DELETE_HEART:
            case R20Constants.HEALTH_DELETE_BLOOD:
            case R20Constants.HEALTH_DELETE_ALL:
            case R20Constants.HEALTH_HISTORY_ACK:
                // Echo from another companion app (or the firmware itself) of
                // a history delete or history-ACK frame. Gadgetbridge never sends
                // these in production, but we silence them defensively to avoid
                // adding "unhandled frame" payload dumps to the log if another
                // app paired to the ring issues them.
                LOG.debug("R20 history control echo dtype=0x{}", Integer.toHexString(dtype));
                break;
            case R20Constants.SETTING_TIME:
            case R20Constants.SETTING_USER_INFO:
            case R20Constants.ENABLE_HEALTH_SENSORS:
            case R20Constants.SETTING_USER_BIND_ECHO:
            case R20Constants.SET_MONITOR_INTERVAL:
            case R20Constants.SETTING_REMINDER_ECHO:
            case R20Constants.ENABLE_BG_SPO2_MONITOR:
                LOG.debug("R20 setting ACK dtype=0x{}", Integer.toHexString(dtype));
                break;
            default:
                LOG.debug("R20 unhandled frame dtype=0x{} payload={}",
                        Integer.toHexString(dtype), GB.hexdump(p));
        }
    }

    private void handleStandardHeartRate(byte[] data) {
        int flags = data[0] & 0xFF;
        int bpm = (flags & 1) == 0
                ? data[1] & 0xFF
                : (data[1] & 0xFF) | ((data[2] & 0xFF) << 8);
        // Skip stale values — flag bits "sensor contact supported, not detected".
        if ((flags & 0x06) == 0x04) return;
        LOG.debug("R20 std HR: {} bpm (flags=0x{})", bpm, Integer.toHexString(flags));
        persistHr(System.currentTimeMillis(), bpm);
    }

    // -------- History --------

    /**
     * High-water-mark gate. Returns {@code true} if a record at {@code recordTsMs}
     * is strictly newer than the persisted HWM and should be written to the DB.
     * The caller is responsible for advancing the HWM after the batch is
     * processed via {@link #advanceHwm(String, long)}.
     *
     * <p>Visible-for-testing pure function.
     */
    static boolean isNewRecord(long recordTsMs, long hwmTsMs) {
        return recordTsMs > 0L && recordTsMs > hwmTsMs;
    }

    /** Read the HWM for a given metric. Returns 0 if no records have ever been processed.
     *
     *  <p>Defensive: if a stored HWM is more than 5 minutes in the future (which used
     *  to happen with the pre-2026-06-08 buggy TZ handling that left HWMs ~3h ahead of
     *  wall-clock UTC), treat it as 0 so we can start fresh instead of dropping every
     *  real record. A 5-minute tolerance accommodates normal BLE clock skew between
     *  the ring's wall clock and the phone's clock (typically &lt; 1 s) without
     *  triggering on every persist. */
    private static final long HWM_FUTURE_TOLERANCE_MS = 5L * 60L * 1000L;

    private long getHwm(String key) {
        long raw;
        try {
            raw = getDevicePrefs().getLong(key, 0L);
        } catch (Exception e) {
            try {
                raw = Long.parseLong(getDevicePrefs().getString(key, "0"));
            } catch (Exception e2) {
                raw = 0L;
            }
        }
        long now = System.currentTimeMillis();
        if (raw > now + HWM_FUTURE_TOLERANCE_MS) {
            LOG.warn("R20 HWM {} is >5 min in the future ({} > {}+5min), resetting to 0",
                    key, raw, now);
            try {
                getDevicePrefs().getPreferences().edit().remove(key).apply();
            } catch (Exception ignored) {}
            return 0L;
        }
        return raw;
    }

    /** Advance the per-metric HWM if {@code candidateMs} exceeds the current value. */
    private void advanceHwm(String key, long candidateMs) {
        if (candidateMs <= 0L) return;
        long cur = getHwm(key);
        if (candidateMs > cur) {
            try {
                getDevicePrefs().getPreferences().edit().putLong(key, candidateMs).apply();
                LOG.debug("R20 HWM {} -> {}", key, candidateMs);
            } catch (Exception e) {
                LOG.warn("R20 failed to persist HWM {}", key, e);
            }
        }
    }

    /**
     * Single point for per-block summary logging. Logs at INFO when there is
     * at least one new record (signal worth surfacing); at DEBUG when every
     * record was filtered as already-seen (expected on every reconnect once
     * the HWM has caught up). Keeps the log readable across long sessions.
     */
    private void logHistoryBlock(String label, int total, int newCount, long hwm, int payloadBytes) {
        if (newCount > 0 || total == 0) {
            LOG.info("R20 {} history block: {} records ({} new, {} already-seen, hwm={}, {}b)",
                    label, total, newCount, total - newCount, hwm, payloadBytes);
        } else {
            LOG.debug("R20 {} history block: {} records (all already-seen, hwm={}, {}b)",
                    label, total, hwm, payloadBytes);
        }
    }

    private long handleHrHistory(byte[] payload) {
        final List<R20Packet.HrRecord> all = R20Packet.parseHrRecords(payload);
        final long hwm = getHwm(HWM_HR);
        final List<R20Packet.HrRecord> records = new ArrayList<>(all.size());
        for (R20Packet.HrRecord r : all) if (isNewRecord(r.timestampMs, hwm)) records.add(r);
        logHistoryBlock("HR", all.size(), records.size(), hwm, payload.length);
        withDb((session, deviceId, userId) -> {
            GenericHeartRateSampleProvider hrProvider =
                    new GenericHeartRateSampleProvider(getDevice(), session);
            for (R20Packet.HrRecord r : records) {
                hrProvider.addSample(new GenericHeartRateSample(r.timestampMs, deviceId, userId, r.bpm));
            }
            // HRV proxy (RMSSD-style) per block
            double hrvProxy = R20DerivedMetrics.hrvProxyRmssd(records);
            if (hrvProxy > 0 && !records.isEmpty()) {
                long ts = records.get(records.size() / 2).timestampMs;
                int hrvInt = (int) Math.round(hrvProxy);
                LOG.info("R20 derived HRV proxy (RMSSD-like): {} ms over {} samples",
                        hrvInt, records.size());
                new GenericHrvValueSampleProvider(getDevice(), session).addSample(
                        new GenericHrvValueSample(ts, deviceId, userId, hrvInt));
            }
        });
        // Only ACK the block when it carries records we actually persisted.
        // The firmware treats ACK as "send next page": ACKing an already-seen
        // block in our read-only-ring mode (no HEALTH_DELETE_* afterwards) puts
        // us in an infinite loop because the read cursor never advances.
        // Skipping the ACK lets the firmware time out and stop the stream.
        if (!records.isEmpty()) {
            sendHistoryAck(R20Constants.HEALTH_STREAM_HEART, payload.length);
        }
        long newest = 0L;
        for (R20Packet.HrRecord r : records) if (r.timestampMs > newest) newest = r.timestampMs;
        advanceHwm(HWM_HR, newest);
        return newest;
    }

    private long handleBpHistory(byte[] payload) {
        final List<R20Packet.BpRecord> all = R20Packet.parseBpRecords(payload);
        final long hwm = getHwm(HWM_BP);
        final List<R20Packet.BpRecord> records = new ArrayList<>(all.size());
        for (R20Packet.BpRecord r : all) if (isNewRecord(r.timestampMs, hwm)) records.add(r);
        logHistoryBlock("BP", all.size(), records.size(), hwm, payload.length);
        withDb((session, deviceId, userId) -> {
            // Batch-build, then a single addSamples() per provider — addSample()
            // in a loop opens a fresh transaction per row which is wasteful
            // (see review comment on PR #6239).
            final java.util.List<GenericHeartRateSample> hrBatch = new ArrayList<>(records.size());
            final java.util.List<GenericBloodPressureSample> bpBatch = new ArrayList<>(records.size());
            for (R20Packet.BpRecord r : records) {
                LOG.debug("  BP @{}: {}/{} mmHg @ {} bpm",
                        r.timestampMs, r.systolic, r.diastolic, r.hr);
                bpBatch.add(new GenericBloodPressureSample(
                        r.timestampMs, deviceId, userId,
                        r.systolic, r.diastolic, /*userIndex*/ null,
                        /*MAP*/ null, r.hr > 0 ? r.hr : null,
                        /*measurementStatus*/ 0));
                if (r.hr > 0) {
                    hrBatch.add(new GenericHeartRateSample(
                            r.timestampMs, deviceId, userId, r.hr));
                }
            }
            new GenericBloodPressureSampleProvider(getDevice(), session).addSamples(bpBatch);
            if (!hrBatch.isEmpty()) {
                new GenericHeartRateSampleProvider(getDevice(), session).addSamples(hrBatch);
            }
        });
        if (!records.isEmpty()) sendHistoryAck(R20Constants.HEALTH_STREAM_BLOOD, payload.length);
        long newest = 0L;
        for (R20Packet.BpRecord r : records) if (r.timestampMs > newest) newest = r.timestampMs;
        advanceHwm(HWM_BP, newest);
        return newest;
    }

    private void persistBp(long timestampMs, int systolic, int diastolic, int hr) {
        if (systolic <= 0 || diastolic <= 0 || systolic > 250 || diastolic > 200) {
            LOG.warn("R20 persistBp: rejected out-of-range sys={} dia={} hr={}", systolic, diastolic, hr);
            return;
        }
        LOG.debug("R20 persistBp: sys={} dia={} hr={} ts={}", systolic, diastolic, hr, timestampMs);
        withDb((session, deviceId, userId) -> {
            GenericBloodPressureSampleProvider provider =
                    new GenericBloodPressureSampleProvider(getDevice(), session);
            provider.addSample(new GenericBloodPressureSample(
                    timestampMs, deviceId, userId,
                    systolic, diastolic, null, null, hr > 0 ? hr : null, 0));
        });
    }

    private long handleAllHistory(byte[] payload) {
        final List<R20Packet.AllRecord> all = R20Packet.parseAllRecords(payload);
        final long hwm = getHwm(HWM_ALL);
        final List<R20Packet.AllRecord> records = new ArrayList<>(all.size());
        for (R20Packet.AllRecord r : all) if (isNewRecord(r.timestampMs, hwm)) records.add(r);
        logHistoryBlock("All-metrics", all.size(), records.size(), hwm, payload.length);
        withDb((session, deviceId, userId) -> {
            final java.util.List<GenericHeartRateSample> hrBatch = new ArrayList<>(records.size());
            final java.util.List<GenericSpo2Sample> spo2Batch = new ArrayList<>(records.size());
            final java.util.List<GenericBloodPressureSample> bpBatch = new ArrayList<>(records.size());
            final java.util.List<GenericHrvValueSample> hrvBatch = new ArrayList<>(records.size());
            final java.util.List<nodomain.freeyourgadget.gadgetbridge.entities.GenericTemperatureSample> tempBatch = new ArrayList<>(records.size());
            for (R20Packet.AllRecord r : records) {
                if (r.hr > 0 && r.hr < 240) {
                    hrBatch.add(new GenericHeartRateSample(r.timestampMs, deviceId, userId, r.hr));
                }
                if (r.spo2 >= 50 && r.spo2 <= 100) {
                    spo2Batch.add(new GenericSpo2Sample(r.timestampMs, deviceId, userId, r.spo2));
                }
                if (r.systolic > 0 && r.diastolic > 0) {
                    bpBatch.add(new GenericBloodPressureSample(
                            r.timestampMs, deviceId, userId,
                            r.systolic, r.diastolic, null, null,
                            r.hr > 0 ? r.hr : null, 0));
                }
                if (r.hrv > 0 && r.hrv < 200) {
                    hrvBatch.add(new GenericHrvValueSample(r.timestampMs, deviceId, userId, r.hrv));
                }
                if (r.temperature >= 30.0 && r.temperature <= 45.0) {
                    tempBatch.add(new nodomain.freeyourgadget.gadgetbridge.entities.GenericTemperatureSample(
                            r.timestampMs, deviceId, userId, (float) r.temperature,
                            nodomain.freeyourgadget.gadgetbridge.model.TemperatureSample.TYPE_UNKNOWN,
                            nodomain.freeyourgadget.gadgetbridge.model.TemperatureSample.LOCATION_FINGER));
                }
                // Body fat, blood sugar, respiration logged but not persisted:
                // no Gadgetbridge generic providers exist for these yet.
                if (r.respiratoryRate > 0 || r.bloodSugar > 0 || r.bodyFatPct > 0) {
                    LOG.debug("R20 ext metrics @{}: rr={} bf={} bs={} cvrr={}",
                            r.timestampMs, r.respiratoryRate,
                            String.format("%.2f", r.bodyFatPct),
                            r.bloodSugar, r.cvrr);
                }
            }
            if (!hrBatch.isEmpty())   new GenericHeartRateSampleProvider(getDevice(), session).addSamples(hrBatch);
            if (!spo2Batch.isEmpty()) new GenericSpo2SampleProvider(getDevice(), session).addSamples(spo2Batch);
            if (!bpBatch.isEmpty())   new GenericBloodPressureSampleProvider(getDevice(), session).addSamples(bpBatch);
            if (!hrvBatch.isEmpty())  new GenericHrvValueSampleProvider(getDevice(), session).addSamples(hrvBatch);
            if (!tempBatch.isEmpty()) new GenericTemperatureSampleProvider(getDevice(), session).addSamples(tempBatch);
        });
        if (!records.isEmpty()) sendHistoryAck(R20Constants.HEALTH_STREAM_ALL, payload.length);
        long newest = 0L;
        for (R20Packet.AllRecord r : records) if (r.timestampMs > newest) newest = r.timestampMs;
        advanceHwm(HWM_ALL, newest);
        return newest;
    }

    /** Parse + log sport-history records (active periods with steps / distance / calories).
     *  Records older than the per-metric HWM are dropped without DB writes. */
    private long handleSportHistory(byte[] payload) {
        final List<R20Packet.SportRecord> all = R20Packet.parseSportRecords(payload);
        final long hwm = getHwm(HWM_SPORT);
        final List<R20Packet.SportRecord> records = new ArrayList<>(all.size());
        for (R20Packet.SportRecord r : all) if (isNewRecord(r.endTimeMs, hwm)) records.add(r);
        logHistoryBlock("Sport", all.size(), records.size(), hwm, payload.length);
        long newest = 0L;
        for (R20Packet.SportRecord r : records) {
            LOG.info("  Sport @{}..{}: steps={} distance={}m kcal={} duration={}s",
                    r.startTimeMs, r.endTimeMs, r.steps, r.distanceMeters, r.calorieKcal, r.durationSec());
            if (r.endTimeMs > newest) newest = r.endTimeMs;
        }
        if (!records.isEmpty()) sendHistoryAck(R20Constants.HEALTH_STREAM_SPORT, payload.length);
        advanceHwm(HWM_SPORT, newest);
        return newest;
    }

    /**
     * Parse + log manual-workout / sport-mode session records (25 bytes each)
     * from the {@code HEALTH_HISTORY_SPORT_MODE} response stream.
     *
     * <p>Sessions are HWM-filtered (read-only-ring + idempotent-resync pattern,
     * matching the other history paths). Persistence as {@code BaseActivitySummary}
     * rows for the dashboard "Workouts" UI is deferred until the layout is
     * validated against a real captured workout — without one we cannot be 100%
     * certain the firmware streams responses on this opcode rather than a
     * sibling code. The parser itself is validated by
     * {@code R20PacketSportModeRecordTest} against the SDK byte arithmetic.
     */
    private long handleSportModeHistory(byte[] payload) {
        final List<R20Packet.SportModeRecord> all = R20Packet.parseSportModeRecords(payload);
        final long hwm = getHwm(HWM_SPORT_MODE);
        final List<R20Packet.SportModeRecord> records = new ArrayList<>(all.size());
        for (R20Packet.SportModeRecord r : all) if (isNewRecord(r.endTimeMs, hwm)) records.add(r);
        logHistoryBlock("SportMode", all.size(), records.size(), hwm, payload.length);
        long newest = 0L;
        for (R20Packet.SportModeRecord r : records) {
            LOG.info("  Workout {} ({}): @{}..{} steps={} dist={}m kcal={} HR avg/min/max={}/{}/{} active={}s",
                    R20Packet.sportModeName(r.sportMode),
                    r.isManualStart() ? "manual" : "auto",
                    r.startTimeMs, r.endTimeMs,
                    r.sportSteps, r.distanceMeters, r.calorieKcal,
                    r.avgHr, r.minHr, r.maxHr, r.activeDurationSec);
            if (r.endTimeMs > newest) newest = r.endTimeMs;
        }
        if (!records.isEmpty()) sendHistoryAck(R20Constants.HEALTH_HISTORY_SPORT_MODE, payload.length);
        advanceHwm(HWM_SPORT_MODE, newest);
        return newest;
    }

    private long handleSleepHistory(byte[] payload) {
        final List<R20Packet.SleepSession> allSessions = R20Packet.parseSleepSessions(payload);
        final long hwm = getHwm(HWM_SLEEP);
        final List<R20Packet.SleepSession> sessions = new ArrayList<>(allSessions.size());
        for (R20Packet.SleepSession s : allSessions) if (isNewRecord(s.endTimeMs, hwm)) sessions.add(s);
        logHistoryBlock("Sleep", allSessions.size(), sessions.size(), hwm, payload.length);
        withDb((session, deviceId, userId) -> {
            GenericSleepStageSampleProvider provider =
                    new GenericSleepStageSampleProvider(getDevice(), session);
            for (R20Packet.SleepSession s : sessions) {
                int score = R20DerivedMetrics.sleepScore(s);
                LOG.info("  Sleep {} -> {}: deep={}s light={}s rem={}s wake={}s ({} stages) score={}",
                        s.startTimeMs, s.endTimeMs, s.deepSleepSec, s.lightSleepSec,
                        s.remSleepSec, s.wakeDurationSec, s.stages.size(), score);
                for (R20Packet.SleepStage st : s.stages) {
                    GenericSleepStageSample sample = new GenericSleepStageSample(
                            st.startTimeMs, deviceId, userId, st.durationSec, mapSleepStage(st.type));
                    provider.addSample(sample);
                }
            }
            double debtH = R20DerivedMetrics.sleepDebtHours(sessions, 8.0);
            LOG.info("R20 sleep debt over last {} sessions: {} hours",
                    sessions.size(), String.format("%.1f", debtH));

            // Persist sleep score per session + Sleep Regularity Index for the block
            GenericMetricSampleProvider metricProvider =
                    new GenericMetricSampleProvider(getDevice(), session);
            for (R20Packet.SleepSession s : sessions) {
                int score = R20DerivedMetrics.sleepScore(s);
                if (score >= 0) {
                    long durSec = (long)(s.deepSleepSec + s.lightSleepSec + s.remSleepSec);
                    GenericMetricSample gms = new GenericMetricSample(
                            s.endTimeMs, deviceId, userId,
                            MetricSample.Metric.GENERIC_SLEEP_SCORE.getDbId(),
                            (double) score, durSec);
                    metricProvider.addSample(gms);
                }
            }
            int sri = R20DerivedMetrics.sleepRegularityIndex(sessions);
            if (sri >= 0 && !sessions.isEmpty()) {
                long ts = sessions.get(sessions.size() - 1).endTimeMs;
                GenericMetricSample gms = new GenericMetricSample(
                        ts, deviceId, userId,
                        MetricSample.Metric.GENERIC_SLEEP_REGULARITY.getDbId(),
                        (double) sri, (long) sessions.size());
                metricProvider.addSample(gms);
            }

            // Cardiorespiratory fitness — VO2max from HRmax/HRrest ratio (Uth 2004).
            try {
                int age = new ActivityUser().getAge();
                int hrMax = R20DerivedMetrics.tanakaHrMax(age);
                // Compute true min/max of the session block: the firmware can stream
                // sessions out of order, so trusting List index 0/last would yield
                // an inverted (from > to) window and an empty HR query.
                long minStartMs = Long.MAX_VALUE;
                long maxEndMs   = Long.MIN_VALUE;
                for (R20Packet.SleepSession s : sessions) {
                    if (s.startTimeMs < minStartMs) minStartMs = s.startTimeMs;
                    if (s.endTimeMs   > maxEndMs)   maxEndMs   = s.endTimeMs;
                }
                // Cap the HR window at 24 h preceding maxEndMs.  Anything longer
                // pulls thousands of rows into RAM on the BLE thread for no
                // benefit — the Uth resting-HR estimator only uses the lowest
                // overnight readings anyway.
                long windowMs = 24L * 3600_000L;
                if (maxEndMs - minStartMs > windowMs) {
                    minStartMs = maxEndMs - windowMs;
                }
                List<R20Packet.HrRecord> hrSamples = new ArrayList<>();
                if (maxEndMs > minStartMs) {
                    for (GenericHeartRateSample gs : new GenericHeartRateSampleProvider(getDevice(), session)
                            .getAllSamples((int) (minStartMs / 1000),
                                           (int) (maxEndMs   / 1000))) {
                        hrSamples.add(new R20Packet.HrRecord(gs.getTimestamp(), gs.getHeartRate()));
                    }
                }
                int rhr = R20DerivedMetrics.restingHrFromSleep(hrSamples, sessions);
                double vo2 = R20DerivedMetrics.vo2MaxUth(hrMax, rhr);
                double ageDelta = R20DerivedMetrics.cardiovascularAgeDelta(vo2, age);
                if (vo2 > 0) {
                    LOG.info("R20 derived VO2max: {} ml/kg/min (HRmax={} RHR={}); cv-age delta: {} years",
                            String.format("%.1f", vo2), hrMax, rhr, String.format("%+.1f", ageDelta));
                    // Persist VO2max only; the cv-age delta is logged not stored —
                    // GenericMetricSample's extra slot is "duration seconds" for
                    // other metrics in this file, mis-using it would corrupt any
                    // future generic consumer.
                    long ts = maxEndMs;
                    GenericMetricSample gms = new GenericMetricSample(
                            ts, deviceId, userId,
                            MetricSample.Metric.GENERIC_MAXIMUM_OXYGEN_UPTAKE.getDbId(),
                            vo2, /*extra=*/ null);
                    metricProvider.addSample(gms);
                }
            } catch (Exception e) {
                LOG.debug("R20 VO2max compute skipped: {}", e.getMessage());
            }
        });
        if (!sessions.isEmpty()) sendHistoryAck(R20Constants.HEALTH_STREAM_SLEEP, payload.length);
        long newest = 0L;
        for (R20Packet.SleepSession s : sessions) if (s.endTimeMs > newest) newest = s.endTimeMs;
        advanceHwm(HWM_SLEEP, newest);
        return newest;
    }

    /** Map Yucheng sleep-stage type code to Gadgetbridge {@link ActivityKind}. */
    private static int mapSleepStage(int yucheng) {
        switch (yucheng) {
            case R20Packet.SleepStage.TYPE_DEEP:  return ActivityKind.DEEP_SLEEP.getCode();
            case R20Packet.SleepStage.TYPE_LIGHT: return ActivityKind.LIGHT_SLEEP.getCode();
            case R20Packet.SleepStage.TYPE_REM:   return ActivityKind.REM_SLEEP.getCode();
            case R20Packet.SleepStage.TYPE_AWAKE: return ActivityKind.AWAKE_SLEEP.getCode();
            default: return ActivityKind.UNKNOWN.getCode();
        }
    }

    private void sendHistoryAck(int streamDataType, int blockLen) {
        try {
            TransactionBuilder b = createTransactionBuilder("R20 history ACK");
            writePacket(b, R20Packet.historyAck(streamDataType, blockLen));
            b.queue();
        } catch (Exception e) {
            LOG.warn("R20 history ACK failed", e);
        }
    }

    // -------- Persistence helpers --------

    /** Single point of access for sample writes. The block runs inside a
     *  try-with-resources DBHandler with user+device IDs already resolved. */
    private void withDb(DbAction action) {
        try (DBHandler db = GBApplication.acquireDB()) {
            DaoSession session = db.getDaoSession();
            Long userId        = DBHelper.getUser(session).getId();
            Long deviceId      = DBHelper.getDevice(getDevice(), session).getId();
            action.run(session, deviceId, userId);
        } catch (Exception e) {
            LOG.error("R20 DB action failed", e);
        }
    }

    @FunctionalInterface
    private interface DbAction {
        void run(DaoSession session, long deviceId, long userId) throws Exception;
    }

    /**
     * Buffer real-time HR samples and flush in batches.  Without this the
     * 1 Hz standard 0x2A37 stream would open a fresh DBHandler every second.
     */
    private void persistHr(long timestampMs, int bpm) {
        if (bpm <= 0 || bpm >= 240) {
            LOG.debug("R20 persistHr: rejected out-of-range bpm={}", bpm);
            return;
        }
        List<GenericHeartRateSample> toFlush = null;
        synchronized (hrBufferLock) {
            // We don't yet know deviceId/userId, but the sample constructor
            // needs them — defer by stashing tuples; resolve in flush().
            hrBuffer.add(new GenericHeartRateSample(timestampMs, 0L, 0L, bpm));
            long now = System.currentTimeMillis();
            boolean sizeReady = hrBuffer.size() >= HR_BUFFER_FLUSH_THRESHOLD;
            boolean timeReady = hrBufferLastFlushMs > 0 && (now - hrBufferLastFlushMs) >= HR_BUFFER_FLUSH_INTERVAL_MS;
            if (sizeReady || timeReady) {
                toFlush = new ArrayList<>(hrBuffer);
                hrBuffer.clear();
                hrBufferLastFlushMs = now;
            } else if (hrBufferLastFlushMs == 0L) {
                hrBufferLastFlushMs = now;
            }
        }
        if (toFlush != null) flushHrBuffer(toFlush);
    }

    private void flushHrBuffer(final List<GenericHeartRateSample> buf) {
        LOG.debug("R20 flushHrBuffer: persisting {} HR samples", buf.size());
        withDb((session, deviceId, userId) -> {
            GenericHeartRateSampleProvider provider =
                    new GenericHeartRateSampleProvider(getDevice(), session);
            List<GenericHeartRateSample> renumbered = new ArrayList<>(buf.size());
            for (GenericHeartRateSample s : buf) {
                renumbered.add(new GenericHeartRateSample(
                        s.getTimestamp(), deviceId, userId, s.getHeartRate()));
            }
            provider.addSamples(renumbered);
        });
    }

    private void persistSpo2(long timestampMs, int pct) {
        if (pct < 50 || pct > 100) {
            LOG.debug("R20 persistSpo2: rejected out-of-range pct={}", pct);
            return;
        }
        LOG.debug("R20 persistSpo2: pct={} ts={}", pct, timestampMs);
        withDb((session, deviceId, userId) -> {
            GenericSpo2SampleProvider provider =
                    new GenericSpo2SampleProvider(getDevice(), session);
            provider.addSample(new GenericSpo2Sample(timestampMs, deviceId, userId, pct));
        });
    }

    // -------- Public APIs --------

    @Override
    public void onHeartRateTest() {
        try {
            TransactionBuilder b = createTransactionBuilder("R20 manual HR");
            writePacket(b, R20Packet.startMeasurement(R20Constants.MEASURE_HEART_RATE, true));
            b.queue();
        } catch (Exception e) {
            LOG.warn("R20 onHeartRateTest failed", e);
        }
    }

    @Override
    public void onFetchRecordedData(int dataTypes) {
        try {
            TransactionBuilder b = createTransactionBuilder("R20 history sync");
            boolean any = false;
            if ((dataTypes & RecordedDataTypes.TYPE_HEART_RATE) != 0) {
                writePacket(b, R20Packet.healthHistory(R20Constants.HEALTH_HISTORY_HEART));
                any = true;
            }
            if ((dataTypes & RecordedDataTypes.TYPE_ACTIVITY) != 0) {
                // BP isn't its own bit; piggy-back on TYPE_ACTIVITY (most "sync everything"
                // callers set the activity bit).  TODO: split if upstream adds a TYPE_BP.
                writePacket(b, R20Packet.healthHistory(R20Constants.HEALTH_HISTORY_BLOOD));
                writePacket(b, R20Packet.healthHistory(R20Constants.HEALTH_HISTORY_SPORT));
                any = true;
            }
            if ((dataTypes & RecordedDataTypes.TYPE_WORKOUTS) != 0) {
                // Manual workout sessions started from the ring's UI
                // (Group_Health=5, KEY_Health.HistorySportMode=45 → 0x052D).
                writePacket(b, R20Packet.healthHistory(R20Constants.HEALTH_HISTORY_SPORT_MODE));
                any = true;
            }
            if ((dataTypes & RecordedDataTypes.TYPE_SLEEP) != 0) {
                writePacket(b, R20Packet.healthHistory(R20Constants.HEALTH_HISTORY_SLEEP));
                any = true;
            }
            if (any) {
                b.queue();
            } else {
                LOG.debug("R20 onFetchRecordedData: bitmask 0x{} matched no R20 categories",
                        Integer.toHexString(dataTypes));
            }
        } catch (Exception e) {
            LOG.warn("R20 onFetchRecordedData failed", e);
        }
    }

    /**
     * Flush any pending real-time HR samples before the BLE session is torn
     * down.  Without this the {@link #HR_BUFFER_FLUSH_THRESHOLD}-sample
     * batching window silently discards up to N-1 samples whenever the ring
     * disconnects, which on a wearable BLE link is a routine event.
     */
    @Override
    public void dispose() {
        try {
            List<GenericHeartRateSample> tail;
            synchronized (hrBufferLock) {
                if (hrBuffer.isEmpty()) {
                    super.dispose();
                    return;
                }
                tail = new ArrayList<>(hrBuffer);
                hrBuffer.clear();
                hrBufferLastFlushMs = System.currentTimeMillis();
            }
            LOG.debug("R20 dispose: flushing {} buffered HR samples", tail.size());
            flushHrBuffer(tail);
        } catch (Exception e) {
            LOG.warn("R20 dispose: HR buffer flush failed", e);
        }
        super.dispose();
    }

    /**
     * Yucheng error-ack pattern: a single-byte payload of 0xFC signals
     * "command refused". We saw 26+ of these in the 2026-06-07 capture when
     * the ring's bonded-user state had drifted after re-pairing or a firmware
     * reboot — every battery / history / measurement request returned 0xFC
     * and no real-time HR/SpO2/BP data streamed at all.
     */
    private static boolean isRejection(R20Packet pkt) {
        byte[] p = pkt.getPayload();
        return p != null && p.length == 1 && (p[0] & 0xFF) == 0xFC;
    }

    /**
     * Send a fresh time + sensor-enable + identity burst to re-prime the
     * ring's bonded-user state. Called once per session when we first see
     * a 0xFC rejection; further rejections after that are logged and ignored
     * so we don't spin on a permanently-mis-paired device.
     */
    private void triggerStaleBondRecovery(R20Packet pkt) {
        if (!recoveryAttempted.compareAndSet(false, true)) {
            LOG.warn("R20 still rejecting after recovery attempt (dtype=0x{}); user must re-pair the ring",
                    Integer.toHexString(pkt.getDataType()));
            return;
        }
        LOG.info("R20 received 0xFC rejection on dtype=0x{}; attempting stale-bond recovery",
                Integer.toHexString(pkt.getDataType()));
        try {
            TransactionBuilder b = createTransactionBuilder("R20 stale-bond recovery");
            writePacket(b, R20Packet.settingTime());
            applySpo2MonitoringPreferences(b);
            writePacket(b, R20Packet.enableHealthSensors(true));
            writePacket(b, R20Packet.getDeviceInfo());
            writePacket(b, R20Packet.getPowerStatistics());
            b.queue();
        } catch (Exception e) {
            LOG.warn("R20 stale-bond recovery transaction failed", e);
        }
    }
}
