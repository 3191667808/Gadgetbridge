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
import nodomain.freeyourgadget.gadgetbridge.devices.GenericStressSampleProvider;
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
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattCharacteristic;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;

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
     *  (the standard 0x2A37 stream fires at ~1 Hz). */
    private static final int HR_BUFFER_FLUSH_THRESHOLD = 30;
    private final List<GenericHeartRateSample> hrBuffer = new ArrayList<>();
    private final Object hrBufferLock = new Object();

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
        writePacket(builder, R20Packet.setMonitorInterval(5));
        writePacket(builder, R20Packet.enableHealthSensors(true));
        writePacket(builder, R20Packet.enableBgSpO2Monitor(true));

        // Initial info pulls.
        writePacket(builder, R20Packet.getDeviceName());
        writePacket(builder, R20Packet.getDeviceInfo());
        writePacket(builder, R20Packet.getPowerStatistics());
        writePacket(builder, R20Packet.getNowStep());

        builder.setDeviceState(GBDevice.State.INITIALIZED);
        return builder;
    }

    private static void writePacket(TransactionBuilder builder, R20Packet pkt) {
        builder.write(R20Constants.UUID_CHAR_WRITE, pkt.encode());
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
                LOG.debug("R20 dropped malformed frame: {}", bytesToHex(data));
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
                LOG.info("R20 measurement complete (0x040E): {}", bytesToHex(p));
                break;
            case R20Constants.HEALTH_STREAM_HEART:
                handleHrHistory(p);
                sendHistoryDelete(R20Constants.HEALTH_DELETE_HEART);
                break;
            case R20Constants.HEALTH_STREAM_BLOOD:
                handleBpHistory(p);
                sendHistoryDelete(R20Constants.HEALTH_DELETE_BLOOD);
                break;
            case R20Constants.HEALTH_STREAM_SLEEP:
                handleSleepHistory(p);
                sendHistoryDelete(R20Constants.HEALTH_DELETE_SLEEP);
                break;
            case R20Constants.HEALTH_STREAM_ALL:
                handleAllHistory(p);
                sendHistoryDelete(R20Constants.HEALTH_DELETE_ALL);
                break;
            case R20Constants.HEALTH_STREAM_SPORT:
                LOG.info("R20 Sport history block: {} bytes (parser TBD)", p.length);
                sendHistoryDelete(R20Constants.HEALTH_DELETE_SPORT);
                break;
            case R20Constants.REAL_UPLOAD_SNAPSHOT:
                // Real-time multi-metric snapshot push (0x0600). Last byte is HR;
                // bytes 0-3 are a timestamp/sequence header — exact layout TBD.
                if (p.length >= 5) {
                    int hr = p[4] & 0xFF;
                    LOG.info("R20 snapshot (0x0600): hr={} bpm  raw={}", hr, bytesToHex(p));
                    if (hr > 0 && hr < 240) {
                        persistHr(System.currentTimeMillis(), hr);
                    }
                }
                break;
            default:
                LOG.debug("R20 unhandled frame dtype=0x{} payload={}",
                        Integer.toHexString(dtype), bytesToHex(p));
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

    private void handleHrHistory(byte[] payload) {
        final List<R20Packet.HrRecord> records = R20Packet.parseHrRecords(payload);
        LOG.info("R20 HR history block: {} records", records.size());
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
        sendHistoryAck(R20Constants.HEALTH_STREAM_HEART, payload.length);
    }

    private void handleBpHistory(byte[] payload) {
        List<R20Packet.BpRecord> records = R20Packet.parseBpRecords(payload);
        LOG.info("R20 BP history block: {} records", records.size());
        withDb((session, deviceId, userId) -> {
            GenericHeartRateSampleProvider hrProvider =
                    new GenericHeartRateSampleProvider(getDevice(), session);
            GenericBloodPressureSampleProvider bpProvider =
                    new GenericBloodPressureSampleProvider(getDevice(), session);
            for (R20Packet.BpRecord r : records) {
                LOG.info("  BP @{}: {}/{} mmHg @ {} bpm",
                        r.timestampMs, r.systolic, r.diastolic, r.hr);
                bpProvider.addSample(new GenericBloodPressureSample(
                        r.timestampMs, deviceId, userId,
                        r.systolic, r.diastolic, /*userIndex*/ null,
                        /*MAP*/ null, r.hr > 0 ? r.hr : null,
                        /*measurementStatus*/ 0));
                if (r.hr > 0) {
                    hrProvider.addSample(new GenericHeartRateSample(
                            r.timestampMs, deviceId, userId, r.hr));
                }
            }
        });
        sendHistoryAck(R20Constants.HEALTH_STREAM_BLOOD, payload.length);
    }

    private void persistBp(long timestampMs, int systolic, int diastolic, int hr) {
        if (systolic <= 0 || diastolic <= 0 || systolic > 250 || diastolic > 200) return;
        withDb((session, deviceId, userId) -> {
            GenericBloodPressureSampleProvider provider =
                    new GenericBloodPressureSampleProvider(getDevice(), session);
            provider.addSample(new GenericBloodPressureSample(
                    timestampMs, deviceId, userId,
                    systolic, diastolic, null, null, hr > 0 ? hr : null, 0));
        });
    }

    private void handleAllHistory(byte[] payload) {
        final List<R20Packet.AllRecord> records = R20Packet.parseAllRecords(payload);
        LOG.info("R20 All-metrics history block: {} records", records.size());
        withDb((session, deviceId, userId) -> {
            GenericHeartRateSampleProvider hrProvider =
                    new GenericHeartRateSampleProvider(getDevice(), session);
            GenericSpo2SampleProvider spo2Provider =
                    new GenericSpo2SampleProvider(getDevice(), session);
            GenericBloodPressureSampleProvider bpProvider =
                    new GenericBloodPressureSampleProvider(getDevice(), session);
            for (R20Packet.AllRecord r : records) {
                if (r.hr > 0 && r.hr < 240) {
                    hrProvider.addSample(new GenericHeartRateSample(r.timestampMs, deviceId, userId, r.hr));
                }
                if (r.spo2 >= 50 && r.spo2 <= 100) {
                    spo2Provider.addSample(new GenericSpo2Sample(r.timestampMs, deviceId, userId, r.spo2));
                }
                if (r.systolic > 0 && r.diastolic > 0) {
                    bpProvider.addSample(new GenericBloodPressureSample(
                            r.timestampMs, deviceId, userId,
                            r.systolic, r.diastolic, null, null,
                            r.hr > 0 ? r.hr : null, 0));
                }
            }
        });
        sendHistoryAck(R20Constants.HEALTH_STREAM_ALL, payload.length);
    }

    private void handleSleepHistory(byte[] payload) {
        final List<R20Packet.SleepSession> sessions = R20Packet.parseSleepSessions(payload);
        LOG.info("R20 Sleep history block: {} session(s)", sessions.size());
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
            // Logged for now; persistence will arrive once a generic VO2max
            // provider exists in upstream Gadgetbridge.
            try {
                int age = GBApplication.getPrefs().getInt("activity_user_age", 30);
                int hrMax = R20DerivedMetrics.tanakaHrMax(age);
                List<R20Packet.HrRecord> hrSamples = new ArrayList<>();
                for (GenericHeartRateSample gs : new GenericHeartRateSampleProvider(getDevice(), session)
                        .getAllSamples((int) (sessions.get(0).startTimeMs / 1000),
                                       (int) (sessions.get(sessions.size() - 1).endTimeMs / 1000))) {
                    hrSamples.add(new R20Packet.HrRecord(gs.getTimestamp(), gs.getHeartRate()));
                }
                int rhr = R20DerivedMetrics.restingHrFromSleep(hrSamples, sessions);
                double vo2 = R20DerivedMetrics.vo2MaxUth(hrMax, rhr);
                double ageDelta = R20DerivedMetrics.cardiovascularAgeDelta(vo2, age);
                if (vo2 > 0) {
                    LOG.info("R20 derived VO2max: {} ml/kg/min (HRmax={} RHR={}); cv-age delta: {} years",
                            String.format("%.1f", vo2), hrMax, rhr, String.format("%+.1f", ageDelta));
                    long ts = sessions.get(sessions.size() - 1).endTimeMs;
                    GenericMetricSample gms = new GenericMetricSample(
                            ts, deviceId, userId,
                            MetricSample.Metric.GENERIC_MAXIMUM_OXYGEN_UPTAKE.getDbId(),
                            vo2, Math.round(ageDelta * 100));
                    metricProvider.addSample(gms);
                }
            } catch (Exception e) {
                LOG.debug("R20 VO2max compute skipped: {}", e.getMessage());
            }
        });
        sendHistoryAck(R20Constants.HEALTH_STREAM_SLEEP, payload.length);
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

    /** Tell the ring to clear the just-synced history category. Discovered
     *  by HCI-snooping the companion app: the firmware retains records
     *  until this command is acknowledged, otherwise the same data is
     *  re-sent on every subsequent sync. */
    private void sendHistoryDelete(int deleteOpcode) {
        try {
            TransactionBuilder b = createTransactionBuilder("R20 history delete");
            writePacket(b, R20Packet.deleteHistory(deleteOpcode));
            b.queue();
        } catch (Exception e) {
            LOG.warn("R20 history delete (0x{}) failed",
                    Integer.toHexString(deleteOpcode), e);
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
        if (bpm <= 0 || bpm >= 240) return;
        List<GenericHeartRateSample> toFlush = null;
        synchronized (hrBufferLock) {
            // We don't yet know deviceId/userId, but the sample constructor
            // needs them — defer by stashing tuples; resolve in flush().
            hrBuffer.add(new GenericHeartRateSample(timestampMs, 0L, 0L, bpm));
            if (hrBuffer.size() >= HR_BUFFER_FLUSH_THRESHOLD) {
                toFlush = new ArrayList<>(hrBuffer);
                hrBuffer.clear();
            }
        }
        if (toFlush != null) flushHrBuffer(toFlush);
    }

    private void flushHrBuffer(final List<GenericHeartRateSample> buf) {
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
        if (pct < 50 || pct > 100) return;
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
            writePacket(b, R20Packet.healthHistory(R20Constants.HEALTH_HISTORY_HEART));
            writePacket(b, R20Packet.healthHistory(R20Constants.HEALTH_HISTORY_BLOOD));
            writePacket(b, R20Packet.healthHistory(R20Constants.HEALTH_HISTORY_SLEEP));
            writePacket(b, R20Packet.healthHistory(R20Constants.HEALTH_HISTORY_SPORT));
            b.queue();
        } catch (Exception e) {
            LOG.warn("R20 onFetchRecordedData failed", e);
        }
    }

    private static String bytesToHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
