/*  Copyright (C) 2026 Ariel Saghiv

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.vring;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Calendar;
import java.util.TimeZone;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHeartRateSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSpo2SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericStressSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.vring.VRingR26Constants;
import nodomain.freeyourgadget.gadgetbridge.devices.vring.samples.VRingR26ActivitySampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSpo2Sample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericStressSample;
import nodomain.freeyourgadget.gadgetbridge.entities.VRingR26ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattCharacteristic;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;

/**
 * Gadgetbridge driver for the VRing R26 smart ring (MO YOUNG LTD "Da Ring" app).
 * <p>
 * Protocol reverse-engineered from {@code com.moyoung.ring} (2026-05).
 * <p>
 * Verified working:
 * <ul>
 *     <li>Connect / pair (no auth handshake)</li>
 *     <li>Battery level via standard 0x2A19</li>
 *     <li>Set time (GMT+8-adjusted LE32 + 0x08 tz byte)</li>
 *     <li>Direct FDD1 read = current steps/distance/calories (3 LE24 fields)</li>
 *     <li>HR history list (cat=2 cmd=9)</li>
 *     <li>Step history per day (cat=2 cmd=13) and per-bucket details (cat=2 cmd=18)</li>
 *     <li>SpO2 (cat=2 cmd=11) and stress (cat=2 cmd=32) history</li>
 *     <li>Manual heart-rate measurement</li>
 * </ul>
 */
public class VRingR26DeviceSupport extends AbstractBTLESingleDeviceSupport {

    private static final Logger LOG = LoggerFactory.getLogger(VRingR26DeviceSupport.class);

    private static final int HISTORY_DAYS = 7;

    public VRingR26DeviceSupport() {
        super(LOG);
        addSupportedService(GattService.UUID_SERVICE_GENERIC_ACCESS);
        addSupportedService(GattService.UUID_SERVICE_GENERIC_ATTRIBUTE);
        addSupportedService(GattService.UUID_SERVICE_BATTERY_SERVICE);
        addSupportedService(VRingR26Constants.UUID_SERVICE);
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
    public boolean getSendWriteRequestResponse() {
        return false;
    }

    // -------------------------------------------------------------------------
    // Connection lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        builder.setDeviceState(GBDevice.State.INITIALIZING);

        // Subscribe to command notifications (FDD3) and step pushes (FDD1).
        BluetoothGattCharacteristic notify = getCharacteristic(VRingR26Constants.UUID_CHARACTERISTIC_NOTIFY);
        if (notify != null) builder.notify(notify, true);

        BluetoothGattCharacteristic fdd1 = getCharacteristic(VRingR26Constants.UUID_CHARACTERISTIC_NOTIFY_ALT);
        if (fdd1 != null) builder.notify(fdd1, true);

        BluetoothGattCharacteristic batt = getCharacteristic(GattCharacteristic.UUID_CHARACTERISTIC_BATTERY_LEVEL);
        if (batt != null) {
            builder.notify(batt, true);
            builder.read(batt);
        }

        // Sync time FIRST so subsequent history queries return non-zero windows.
        writeCmd(builder, VRingR26Packet.setTime(System.currentTimeMillis() / 1000L));

        // Query firmware/device info (cat=2 cmd=0/12).
        writeCmd(builder, VRingR26Packet.queryDeviceInfo());
        writeCmd(builder, VRingR26Packet.queryFirmware());

        // Read current steps directly from FDD1.
        if (fdd1 != null) builder.read(fdd1);

        // Pull history for the last 7 days. Sample providers will dedup by timestamp.
        for (int day = 0; day > -HISTORY_DAYS; day--) {
            writeCmd(builder, VRingR26Packet.queryHistoryStepsDay(day));
            writeCmd(builder, VRingR26Packet.queryHistoryStepsDetailsDay(day));
            writeCmd(builder, VRingR26Packet.queryHistoryHeartRate(day));
        }

        builder.setDeviceState(GBDevice.State.INITIALIZED);
        return builder;
    }

    private void writeCmd(TransactionBuilder builder, VRingR26Packet pkt) {
        BluetoothGattCharacteristic chr = getCharacteristic(pkt.writeCharacteristic());
        if (chr == null) {
            LOG.warn("No characteristic for cat={} cmd={}", pkt.getCategory(), pkt.getCommand());
            return;
        }
        builder.write(chr, pkt.encode());
    }

    // -------------------------------------------------------------------------
    // GATT callbacks
    // -------------------------------------------------------------------------

    @Override
    public boolean onCharacteristicChanged(BluetoothGatt gatt,
                                           BluetoothGattCharacteristic characteristic,
                                           byte[] value) {
        if (super.onCharacteristicChanged(gatt, characteristic, value)) {
            return true;
        }
        UUID uuid = characteristic.getUuid();
        if (GattCharacteristic.UUID_CHARACTERISTIC_BATTERY_LEVEL.equals(uuid)) {
            handleBattery(value);
            return true;
        }
        if (VRingR26Constants.UUID_CHARACTERISTIC_NOTIFY.equals(uuid)) {
            handleFddaFrame(value);
            return true;
        }
        if (VRingR26Constants.UUID_CHARACTERISTIC_NOTIFY_ALT.equals(uuid)) {
            handleCurrentStepsRead(value);
            return true;
        }
        return false;
    }

    @Override
    public boolean onCharacteristicRead(BluetoothGatt gatt,
                                        BluetoothGattCharacteristic characteristic,
                                        byte[] value, int status) {
        if (super.onCharacteristicRead(gatt, characteristic, value, status)) {
            return true;
        }
        UUID uuid = characteristic.getUuid();
        if (GattCharacteristic.UUID_CHARACTERISTIC_BATTERY_LEVEL.equals(uuid)) {
            handleBattery(value);
            return true;
        }
        if (VRingR26Constants.UUID_CHARACTERISTIC_NOTIFY_ALT.equals(uuid)) {
            handleCurrentStepsRead(value);
            return true;
        }
        return false;
    }

    private void handleBattery(byte[] value) {
        if (value == null || value.length == 0) return;
        GBDeviceEventBatteryInfo evt = new GBDeviceEventBatteryInfo();
        evt.level = (short) (value[0] & 0xFF);
        handleGBDeviceEvent(evt);
    }

    // -------------------------------------------------------------------------
    // FDD1 (current step counters) - direct GATT read response
    // -------------------------------------------------------------------------

    /** FDD1 payload = 3 LE24 fields = steps, distance(m), calories. Optional 4th = time. */
    private void handleCurrentStepsRead(byte[] value) {
        if (value == null || value.length < 9 || value.length % 3 != 0) {
            LOG.debug("FDD1 read ignored, len={} hex={}", value == null ? 0 : value.length, hex(value));
            return;
        }
        int steps = le24(value, 0);
        int distanceM = le24(value, 3);
        int calories = le24(value, 6);
        LOG.info("VRing current: steps={} distance={}m calories={}", steps, distanceM, calories);

        // Persist as a snapshot at "now" (rounded to start of current 5-min bucket).
        int nowSec = (int) (System.currentTimeMillis() / 1000L);
        int bucket = nowSec - (nowSec % 300);
        persistDailyTotals(bucket, steps, distanceM, calories, -1);
    }

    // -------------------------------------------------------------------------
    // FDDA / FDD3 command-channel frames
    // -------------------------------------------------------------------------

    private void handleFddaFrame(byte[] value) {
        VRingR26Packet pkt = VRingR26Packet.decode(value);
        if (pkt == null) {
            LOG.debug("Non-FDDA frame: {}", hex(value));
            return;
        }
        int cat = pkt.getCategory();
        int cmd = pkt.getCommand();
        byte[] p = pkt.getPayload();
        LOG.debug("FDDA cat={} cmd={} payload={}", cat, cmd, hex(p));

        switch (cat) {
            case VRingR26Constants.CAT_SET:
                handleSetResponse(cmd, p);
                break;
            case VRingR26Constants.CAT_QUERY:
                handleQueryResponse(cmd, p);
                break;
            default:
                LOG.debug("Unhandled cat={} cmd={}", cat, cmd);
        }
    }

    private void handleSetResponse(int cmd, byte[] p) {
        // cat=1 contains live measurement pushes triggered by set commands.
        switch (cmd) {
            case VRingR26Constants.CMD_START_HR_MEASURE: { // cat=1 cmd=9: live HR
                if (p.length >= 1) {
                    int bpm = p[0] & 0xFF;
                    if (bpm > 0 && bpm < 220) {
                        LOG.info("VRing live HR: {} bpm", bpm);
                        persistHeartRate(System.currentTimeMillis(), bpm);
                    } else if (bpm == 0xFF) {
                        LOG.warn("VRing HR rejected (ring off finger)");
                    }
                }
                break;
            }
            default:
                LOG.debug("set-response cmd={} payload={}", cmd, hex(p));
        }
    }

    private void handleQueryResponse(int cmd, byte[] p) {
        switch (cmd) {
            case VRingR26Constants.CMD_QUERY_DEVICE_INFO: {
                LOG.debug("VRing device info: {}", hex(p));
                GBDeviceEventVersionInfo vi = new GBDeviceEventVersionInfo();
                // Use only first two bytes (likely model/major) to avoid leaking any serial in fwVersion.
                String tag = p.length >= 2
                        ? String.format("%02X%02X", p[0], p[1])
                        : "0000";
                vi.fwVersion = "R26-" + tag;
                vi.hwVersion = "VRing R26";
                handleGBDeviceEvent(vi);
                break;
            }
            case VRingR26Constants.CMD_QUERY_HR_LATEST: {
                // cat=2 cmd=9: HR history list. Format: [skip byte] then N x [bpm + ts_u32_LE].
                parseHrHistory(p);
                break;
            }
            case VRingR26Constants.CMD_QUERY_SPO2_LATEST: {
                // cat=2 cmd=11: SpO2 history; payload[0]=count then N entries.
                parseSpo2History(p);
                break;
            }
            case VRingR26Constants.CMD_QUERY_HISTORY_STEPS: {
                // cat=2 cmd=13: day_byte + 4 x LE32 = (steps, distance, calories, activeTime).
                parseHistoryStepsDay(p);
                break;
            }
            case VRingR26Constants.CMD_QUERY_HISTORY_STEPS_DETAIL: {
                // cat=2 cmd=18: day_byte + N x LE16 step buckets (30-min bins).
                parseHistoryStepsDetail(p);
                break;
            }
            case VRingR26Constants.CMD_QUERY_STRESS_HISTORY: {
                parseStressHistory(p);
                break;
            }
            default:
                LOG.debug("query-response cmd={} payload={}", cmd, hex(p));
        }
    }

    // -------------------------------------------------------------------------
    // Parsers
    // -------------------------------------------------------------------------

    private void parseHrHistory(byte[] p) {
        if (p == null || p.length < 6 || (p.length - 1) % 5 != 0) {
            LOG.debug("HR history payload invalid: {}", hex(p));
            return;
        }
        java.util.List<GenericHeartRateSample> batch = new java.util.ArrayList<>();
        for (int i = 1; i + 4 < p.length; i += 5) {
            int bpm = p[i] & 0xFF;
            long tsSec = le32(p, i + 1);
            if (bpm < 30 || bpm > 220) continue;
            if (tsSec < 1_500_000_000L || tsSec > 4_000_000_000L) continue;
            batch.add(new GenericHeartRateSample(tsSec * 1000L, 0L, 0L, bpm));
        }
        persistHeartRateBatch(batch);
        LOG.info("Persisted {} HR history samples", batch.size());
    }

    private void parseSpo2History(byte[] p) {
        if (p == null || p.length < 1) return;
        java.util.List<GenericSpo2Sample> batch = new java.util.ArrayList<>();
        // Best-effort: same shape as HR list (1 marker + N x [val + ts_u32_LE]).
        if ((p.length - 1) % 5 == 0) {
            for (int i = 1; i + 4 < p.length; i += 5) {
                int spo2 = p[i] & 0xFF;
                long tsSec = le32(p, i + 1);
                if (spo2 < 70 || spo2 > 100) continue;
                if (tsSec < 1_500_000_000L || tsSec > 4_000_000_000L) continue;
                batch.add(new GenericSpo2Sample(tsSec * 1000L, 0L, 0L, spo2));
            }
        }
        persistSpo2Batch(batch);
        LOG.info("Persisted {} SpO2 samples", batch.size());
    }

    private void parseStressHistory(byte[] p) {
        if (p == null || p.length < 1) return;
        java.util.List<GenericStressSample> batch = new java.util.ArrayList<>();
        if ((p.length - 1) % 5 == 0) {
            for (int i = 1; i + 4 < p.length; i += 5) {
                int stress = p[i] & 0xFF;
                long tsSec = le32(p, i + 1);
                if (stress <= 0 || stress > 100) continue;
                if (tsSec < 1_500_000_000L || tsSec > 4_000_000_000L) continue;
                batch.add(new GenericStressSample(tsSec * 1000L, 0L, 0L, stress));
            }
        }
        persistStressBatch(batch);
        LOG.info("Persisted {} stress samples", batch.size());
    }

    private void parseHistoryStepsDay(byte[] p) {
        if (p == null || p.length < 17) {
            LOG.debug("History steps day payload too short: {}", hex(p));
            return;
        }
        int dayOffset = p[0];   // 0=today, -1=yesterday, ...
        long steps    = le32(p, 1);
        long distance = le32(p, 5);
        long calories = le32(p, 9);
        long activeTimeMin = le32(p, 13);
        LOG.info("Day {}: steps={} distance={}m calories={} activeMin={}",
                dayOffset, steps, distance, calories, activeTimeMin);

        // Anchor at noon of the target day, local time.
        int ts = startOfDayLocal(dayOffset) + 12 * 3600;
        persistDailyTotals(ts, (int) steps, (int) distance, (int) calories, (int) activeTimeMin);
    }

    private void parseHistoryStepsDetail(byte[] p) {
        if (p == null || p.length < 3 || (p.length - 1) % 2 != 0) {
            LOG.debug("Step detail payload invalid: {}", hex(p));
            return;
        }
        int dayOffset = p[0];
        int dayStart = startOfDayLocal(dayOffset);
        int buckets = (p.length - 1) / 2;
        // Buckets are 30-min slots: 48 per day. If we got fewer, assume sparse trailing zeros.
        int bucketSec = (buckets == 48) ? (24 * 3600 / 48) : (24 * 3600 / Math.max(buckets, 1));
        int count = 0;
        try (DBHandler db = GBApplication.acquireDB()) {
            DaoSession session = db.getDaoSession();
            long userId = DBHelper.getUser(session).getId();
            long deviceId = DBHelper.getDevice(getDevice(), session).getId();
            VRingR26ActivitySampleProvider prov =
                    new VRingR26ActivitySampleProvider(getDevice(), session);
            for (int i = 0; i < buckets; i++) {
                int idx = 1 + i * 2;
                int steps = ((p[idx] & 0xFF)) | ((p[idx + 1] & 0xFF) << 8);
                if (steps <= 0) continue;
                int ts = dayStart + i * bucketSec;
                VRingR26ActivitySample s = new VRingR26ActivitySample(
                        ts, deviceId, userId,
                        VRingR26ActivitySampleProvider.RAW_KIND_ACTIVITY,
                        -1,            // heartRate unknown for this stream
                        steps * 2,     // intensity ~ steps/min (bucket=30min so /15 ~ x2 to match)
                        steps,
                        null, null);
                prov.addGBActivitySample(s);
                count++;
            }
        } catch (Exception e) {
            LOG.error("Failed to persist step details: {}", e.getMessage(), e);
        }
        LOG.info("Day {}: persisted {} step-detail buckets", dayOffset, count);
    }

    // -------------------------------------------------------------------------
    // Persistence helpers
    // -------------------------------------------------------------------------

    private void persistHeartRate(long timestampMs, int bpm) {
        persistHeartRateBatch(java.util.Collections.singletonList(
                new GenericHeartRateSample(timestampMs, 0L, 0L, bpm)));
    }

    private void persistHeartRateBatch(java.util.List<GenericHeartRateSample> samples) {
        if (samples == null || samples.isEmpty()) return;
        try (DBHandler db = GBApplication.acquireDB()) {
            DaoSession session = db.getDaoSession();
            long userId = DBHelper.getUser(session).getId();
            long deviceId = DBHelper.getDevice(getDevice(), session).getId();
            GenericHeartRateSampleProvider provider =
                    new GenericHeartRateSampleProvider(getDevice(), session);
            for (GenericHeartRateSample s : samples) {
                s.setUserId(userId);
                s.setDeviceId(deviceId);
                provider.addSample(s);
            }
        } catch (Exception e) {
            LOG.error("Failed to persist HR batch ({}): {}", samples.size(), e.getMessage(), e);
        }
    }

    private void persistSpo2Batch(java.util.List<GenericSpo2Sample> samples) {
        if (samples == null || samples.isEmpty()) return;
        try (DBHandler db = GBApplication.acquireDB()) {
            DaoSession session = db.getDaoSession();
            long userId = DBHelper.getUser(session).getId();
            long deviceId = DBHelper.getDevice(getDevice(), session).getId();
            GenericSpo2SampleProvider provider =
                    new GenericSpo2SampleProvider(getDevice(), session);
            for (GenericSpo2Sample s : samples) {
                s.setUserId(userId);
                s.setDeviceId(deviceId);
                provider.addSample(s);
            }
        } catch (Exception e) {
            LOG.error("Failed to persist SpO2 batch ({}): {}", samples.size(), e.getMessage(), e);
        }
    }

    private void persistStressBatch(java.util.List<GenericStressSample> samples) {
        if (samples == null || samples.isEmpty()) return;
        try (DBHandler db = GBApplication.acquireDB()) {
            DaoSession session = db.getDaoSession();
            long userId = DBHelper.getUser(session).getId();
            long deviceId = DBHelper.getDevice(getDevice(), session).getId();
            GenericStressSampleProvider provider =
                    new GenericStressSampleProvider(getDevice(), session);
            for (GenericStressSample s : samples) {
                s.setUserId(userId);
                s.setDeviceId(deviceId);
                provider.addSample(s);
            }
        } catch (Exception e) {
            LOG.error("Failed to persist stress batch ({}): {}", samples.size(), e.getMessage(), e);
        }
    }

    private void persistDailyTotals(int timestampSec, int steps, int distanceM, int calories,
                                    int activeMinutes) {
        try (DBHandler db = GBApplication.acquireDB()) {
            DaoSession session = db.getDaoSession();
            long userId = DBHelper.getUser(session).getId();
            long deviceId = DBHelper.getDevice(getDevice(), session).getId();
            VRingR26ActivitySampleProvider prov =
                    new VRingR26ActivitySampleProvider(getDevice(), session);
            VRingR26ActivitySample sample = new VRingR26ActivitySample(
                    timestampSec, deviceId, userId,
                    VRingR26ActivitySampleProvider.RAW_KIND_ACTIVITY,
                    -1,
                    activeMinutes > 0 ? activeMinutes : 0,
                    steps,
                    distanceM, calories);
            prov.addGBActivitySample(sample);
        } catch (Exception e) {
            LOG.error("Failed to persist daily totals: {}", e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // External actions
    // -------------------------------------------------------------------------

    @Override
    public void onFindDevice(boolean start) {
        TransactionBuilder tb = createTransactionBuilder("findDevice");
        writeCmd(tb, VRingR26Packet.findDevice(start));
        tb.queue();
    }

    @Override
    public void onHeartRateTest() {
        TransactionBuilder tb = createTransactionBuilder("hrTest");
        writeCmd(tb, VRingR26Packet.startHeartRateMeasurement(true));
        tb.queue();
    }

    @Override
    public void onSetTime() {
        TransactionBuilder tb = createTransactionBuilder("setTime");
        writeCmd(tb, VRingR26Packet.setTime(System.currentTimeMillis() / 1000L));
        tb.queue();
    }

    @Override
    public void onFetchRecordedData(int dataTypes) {
        LOG.info("onFetchRecordedData(types=0x{}): re-pulling 7-day history",
                Integer.toHexString(dataTypes));
        TransactionBuilder tb = createTransactionBuilder("fetchData");
        // Refresh time first so timestamps are consistent.
        writeCmd(tb, VRingR26Packet.setTime(System.currentTimeMillis() / 1000L));
        // Read current totals.
        BluetoothGattCharacteristic fdd1 = getCharacteristic(VRingR26Constants.UUID_CHARACTERISTIC_NOTIFY_ALT);
        if (fdd1 != null) tb.read(fdd1);
        for (int day = 0; day > -HISTORY_DAYS; day--) {
            writeCmd(tb, VRingR26Packet.queryHistoryStepsDay(day));
            writeCmd(tb, VRingR26Packet.queryHistoryStepsDetailsDay(day));
            writeCmd(tb, VRingR26Packet.queryHistoryHeartRate(day));
        }
        tb.queue();
    }

    // -------------------------------------------------------------------------
    // Byte-order helpers
    // -------------------------------------------------------------------------

    /** Little-endian unsigned 24-bit int. */
    private static int le24(byte[] b, int off) {
        if (b == null || off < 0 || off + 3 > b.length) return 0;
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16);
    }

    /** Little-endian unsigned 32-bit, returned as long so callers don't need post-hoc masking. */
    private static long le32(byte[] b, int off) {
        if (b == null || off < 0 || off + 4 > b.length) return 0L;
        return ((long) (b[off]     & 0xFF))
                | (((long) (b[off + 1] & 0xFF)) << 8)
                | (((long) (b[off + 2] & 0xFF)) << 16)
                | (((long) (b[off + 3] & 0xFF)) << 24);
    }

    /** Returns the unix epoch (seconds) at 00:00 local time for today + dayOffset. */
    private static int startOfDayLocal(int dayOffset) {
        Calendar c = Calendar.getInstance(TimeZone.getDefault());
        c.add(Calendar.DAY_OF_YEAR, dayOffset);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return (int) (c.getTimeInMillis() / 1000L);
    }

    private static String hex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) sb.append(String.format("%02X ", b));
        return sb.toString();
    }
}
