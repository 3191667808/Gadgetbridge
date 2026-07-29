/*  Copyright (C) 2026 Liu Haoxin

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
package nodomain.freeyourgadget.gadgetbridge.mcp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

import de.greenrobot.dao.query.QueryBuilder;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.XiaomiDailySummarySampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.XiaomiSleepTimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.xiaomi.XiaomiCoordinator;
import nodomain.freeyourgadget.gadgetbridge.entities.BatteryLevel;
import nodomain.freeyourgadget.gadgetbridge.entities.BatteryLevelDao;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.entities.XiaomiDailySummarySample;
import nodomain.freeyourgadget.gadgetbridge.entities.XiaomiSleepTimeSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.StressSample;

/** Builds the MCP data snapshot directly from Gadgetbridge's read-only provider APIs. */
final class GadgetbridgeSnapshotReader {
    private static final long STRESS_LOOKBACK_MILLIS = 30L * 24L * 60L * 60L * 1000L;
    private static final long SLEEP_LOOKBACK_MILLIS = 14L * 24L * 60L * 60L * 1000L;
    private static final DateTimeFormatter CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    @NonNull
    ReadResult read(@NonNull final GBDevice device) throws Exception {
        try (DBHandler db = GBApplication.acquireDbReadOnly()) {
            final DaoSession session = db.getDaoSession();
            final Device dbDevice = DBHelper.findDevice(device, session);
            if (dbDevice == null) {
                throw new IllegalStateException("Selected device is not present in the Gadgetbridge database");
            }

            final DeviceCoordinator coordinator = device.getDeviceCoordinator();
            McpSnapshot snapshot = McpSnapshot.empty();
            long newestTimestamp = 0L;

            final JsonObject profile = new JsonObject();
            profile.addProperty("name", Objects.requireNonNullElse(device.getName(), ""));
            profile.addProperty("manufacturer", Objects.requireNonNullElse(coordinator.getManufacturer(), ""));
            profile.addProperty("model", Objects.requireNonNullElse(device.getModel(), ""));
            McpSnapshot.putNullable(profile, "alias", device.getAlias());
            profile.addProperty("typeName", device.getType().name());
            McpSnapshot.putNullable(profile, "firmwareVersion", device.getFirmwareVersion());
            snapshot = snapshot.replace(McpSnapshot.SECTION_DEVICE, profile);

            final ActivityData activityData = readActivity(device, coordinator, session);
            newestTimestamp = Math.max(newestTimestamp, activityData.latestTimestamp);

            XiaomiDailySummarySample dailySummary = null;
            if (coordinator instanceof XiaomiCoordinator) {
                dailySummary = new XiaomiDailySummarySampleProvider(device, session).getLatestSample();
            }

            final JsonObject activity = new JsonObject();
            activity.addProperty(
                    "stepsToday",
                    dailySummary != null && dailySummary.getTimestamp() >= startOfTodayMillis()
                            ? intValue(dailySummary.getSteps())
                            : activityData.steps
            );
            snapshot = snapshot.replace(McpSnapshot.SECTION_ACTIVITY, activity);

            final JsonObject dailyMetrics = createEmptyDailyMetrics();
            if (dailySummary != null) {
                final long summaryTimestamp = dailySummary.getTimestamp();
                newestTimestamp = Math.max(newestTimestamp, summaryTimestamp);
                fillDailyMetrics(dailyMetrics, dailySummary);
            } else {
                // ActivitySample stores calories in thousandths of a kcal. The public MiBandMCP
                // schema exposes whole kcal, matching Gadgetbridge's own charts and dashboard.
                dailyMetrics.addProperty("caloriesToday", activityData.activeCalories / 1000);
            }
            snapshot = snapshot.replace(McpSnapshot.SECTION_DAILY_METRICS, dailyMetrics);

            final JsonObject heartRate = new JsonObject();
            heartRate.addProperty("bpm", activityData.latestHeartRate);
            McpSnapshot.putNullable(
                    heartRate,
                    "measuredAtEpochMillis",
                    zeroToNull(activityData.latestHeartRateTimestamp)
            );
            snapshot = snapshot.replace(McpSnapshot.SECTION_HEART_RATE, heartRate);

            final TimedInt battery = readBattery(session, dbDevice);
            newestTimestamp = Math.max(newestTimestamp, battery.timestamp);
            final JsonObject batteryStatus = new JsonObject();
            batteryStatus.addProperty("levelPercent", battery.value);
            McpSnapshot.putNullable(
                    batteryStatus,
                    "measuredAtEpochMillis",
                    zeroToNull(battery.timestamp)
            );
            snapshot = snapshot.replace(McpSnapshot.SECTION_BATTERY, batteryStatus);

            final TimedInt stress = readLatestStress(device, coordinator, session);
            newestTimestamp = Math.max(newestTimestamp, stress.timestamp);
            final JsonObject stressStatus = new JsonObject();
            stressStatus.addProperty("level", stress.value);
            McpSnapshot.putNullable(
                    stressStatus,
                    "measuredAtEpochMillis",
                    zeroToNull(stress.timestamp)
            );
            snapshot = snapshot.replace(McpSnapshot.SECTION_STRESS, stressStatus);

            final SleepData sleepData = readSleep(device, coordinator, session);
            newestTimestamp = Math.max(newestTimestamp, sleepData.updatedAt);
            snapshot = snapshot.replace(McpSnapshot.SECTION_SLEEP, sleepData.toJson());

            final boolean dataReady = newestTimestamp > 0;
            final JsonObject bandStatus = new JsonObject();
            bandStatus.addProperty("gadgetbridgeInstalled", true);
            bandStatus.addProperty("exportGranted", true);
            bandStatus.addProperty("dataReady", dataReady);
            bandStatus.addProperty(
                    "detail",
                    dataReady
                            ? "Reading Gadgetbridge data directly for " + device.getAliasOrName()
                            : "No synchronized samples are available for " + device.getAliasOrName()
            );
            snapshot = snapshot.replace(McpSnapshot.SECTION_BAND_STATUS, bandStatus);

            return new ReadResult(snapshot, zeroToNull(newestTimestamp));
        }
    }

    @NonNull
    private ActivityData readActivity(
            @NonNull final GBDevice device,
            @NonNull final DeviceCoordinator coordinator,
            @NonNull final DaoSession session
    ) {
        final SampleProvider<? extends ActivitySample> provider = coordinator.getSampleProvider(device, session);
        if (provider == null) {
            return new ActivityData();
        }

        final long nowMillis = System.currentTimeMillis();
        final long startMillis = startOfTodayMillis();
        final List<? extends ActivitySample> samples = provider.getAllActivitySamples(
                Math.toIntExact(startMillis / 1000L),
                Math.toIntExact(nowMillis / 1000L)
        );

        final ActivityData result = new ActivityData();
        for (final ActivitySample sample : samples) {
            if (sample.getSteps() > 0) {
                result.steps = saturatedAdd(result.steps, sample.getSteps());
            }
            if (sample.getActiveCalories() > 0) {
                result.activeCalories = saturatedAdd(result.activeCalories, sample.getActiveCalories());
            }
            final long timestamp = sample.getTimestamp() * 1000L;
            result.latestTimestamp = Math.max(result.latestTimestamp, timestamp);
            if (sample.getHeartRate() > 0 && sample.getHeartRate() < 255) {
                result.latestHeartRate = sample.getHeartRate();
                result.latestHeartRateTimestamp = timestamp;
            }
        }
        return result;
    }

    @NonNull
    private TimedInt readBattery(@NonNull final DaoSession session, @NonNull final Device device) {
        final QueryBuilder<BatteryLevel> query = session.getBatteryLevelDao().queryBuilder();
        query.where(
                        BatteryLevelDao.Properties.DeviceId.eq(device.getId()),
                        BatteryLevelDao.Properties.BatteryIndex.eq(0)
                )
                .orderDesc(BatteryLevelDao.Properties.Timestamp)
                .limit(1);
        final List<BatteryLevel> levels = query.list();
        if (levels.isEmpty()) {
            return new TimedInt();
        }
        final BatteryLevel latest = levels.get(0);
        return new TimedInt(latest.getLevel(), latest.getTimestamp() * 1000L);
    }

    @NonNull
    private TimedInt readLatestStress(
            @NonNull final GBDevice device,
            @NonNull final DeviceCoordinator coordinator,
            @NonNull final DaoSession session
    ) {
        final TimeSampleProvider<? extends StressSample> provider =
                coordinator.getStressSampleProvider(device, session);
        if (provider == null) {
            return new TimedInt();
        }

        final long now = System.currentTimeMillis();
        final List<? extends StressSample> samples = provider.getAllSamples(
                now - STRESS_LOOKBACK_MILLIS,
                now
        );
        for (int index = samples.size() - 1; index >= 0; index--) {
            final StressSample sample = samples.get(index);
            if (sample.getStress() > 0) {
                return new TimedInt(sample.getStress(), sample.getTimestamp());
            }
        }
        return new TimedInt();
    }

    @NonNull
    private SleepData readSleep(
            @NonNull final GBDevice device,
            @NonNull final DeviceCoordinator coordinator,
            @NonNull final DaoSession session
    ) {
        if (!(coordinator instanceof XiaomiCoordinator)) {
            return new SleepData();
        }

        final long now = System.currentTimeMillis();
        final List<XiaomiSleepTimeSample> samples = new XiaomiSleepTimeSampleProvider(device, session)
                .getAllSamples(now - SLEEP_LOOKBACK_MILLIS, now);
        XiaomiSleepTimeSample fallback = null;
        XiaomiSleepTimeSample primary = null;
        for (final XiaomiSleepTimeSample sample : samples) {
            if (fallback == null || wakeupTime(sample) > wakeupTime(fallback)) {
                fallback = sample;
            }
            if (intValue(sample.getTotalDuration()) >= 120
                    && (primary == null || wakeupTime(sample) > wakeupTime(primary))) {
                primary = sample;
            }
        }
        final XiaomiSleepTimeSample selected = primary != null ? primary : fallback;
        return selected == null ? new SleepData() : new SleepData(selected);
    }

    private static void fillDailyMetrics(
            @NonNull final JsonObject target,
            @NonNull final XiaomiDailySummarySample sample
    ) {
        McpSnapshot.putNullable(target, "summaryEpochMillis", sample.getTimestamp());
        target.addProperty("caloriesToday", intValue(sample.getCalories()));
        target.addProperty("restingHeartRate", intValue(sample.getHrResting()));
        target.addProperty("averageHeartRate", intValue(sample.getHrAvg()));
        target.addProperty("maxHeartRate", intValue(sample.getHrMax()));
        McpSnapshot.putNullable(target, "maxHeartRateAtEpochMillis", normalizeTimestamp(sample.getHrMaxTs()));
        target.addProperty("minHeartRate", intValue(sample.getHrMin()));
        McpSnapshot.putNullable(target, "minHeartRateAtEpochMillis", normalizeTimestamp(sample.getHrMinTs()));
        target.addProperty("averageStress", intValue(sample.getStressAvg()));
        target.addProperty("maxStress", intValue(sample.getStressMax()));
        target.addProperty("minStress", intValue(sample.getStressMin()));
        target.addProperty("averageSpo2", intValue(sample.getSpo2Avg()));
        target.addProperty("maxSpo2", intValue(sample.getSpo2Max()));
        McpSnapshot.putNullable(target, "maxSpo2AtEpochMillis", normalizeTimestamp(sample.getSpo2MaxTs()));
        target.addProperty("minSpo2", intValue(sample.getSpo2Min()));
        McpSnapshot.putNullable(target, "minSpo2AtEpochMillis", normalizeTimestamp(sample.getSpo2MinTs()));
        target.addProperty("vitalityCurrent", intValue(sample.getVitalityCurrent()));
    }

    @NonNull
    private static JsonObject createEmptyDailyMetrics() {
        final JsonObject value = new JsonObject();
        McpSnapshot.putNullable(value, "summaryEpochMillis", (Long) null);
        value.addProperty("caloriesToday", 0);
        value.addProperty("restingHeartRate", 0);
        value.addProperty("averageHeartRate", 0);
        value.addProperty("maxHeartRate", 0);
        McpSnapshot.putNullable(value, "maxHeartRateAtEpochMillis", (Long) null);
        value.addProperty("minHeartRate", 0);
        McpSnapshot.putNullable(value, "minHeartRateAtEpochMillis", (Long) null);
        value.addProperty("averageStress", 0);
        value.addProperty("maxStress", 0);
        value.addProperty("minStress", 0);
        value.addProperty("averageSpo2", 0);
        value.addProperty("maxSpo2", 0);
        McpSnapshot.putNullable(value, "maxSpo2AtEpochMillis", (Long) null);
        value.addProperty("minSpo2", 0);
        McpSnapshot.putNullable(value, "minSpo2AtEpochMillis", (Long) null);
        value.addProperty("vitalityCurrent", 0);
        return value;
    }

    private static int intValue(@Nullable final Integer value) {
        return value == null ? 0 : value;
    }

    private static long startOfTodayMillis() {
        return LocalDate.now()
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
    }

    private static long wakeupTime(@NonNull final XiaomiSleepTimeSample sample) {
        return Objects.requireNonNullElse(sample.getWakeupTime(), sample.getTimestamp());
    }

    @Nullable
    private static Long normalizeTimestamp(@Nullable final Integer value) {
        if (value == null || value <= 0) {
            return null;
        }
        return value.longValue() * 1000L;
    }

    @Nullable
    private static Long zeroToNull(final long value) {
        return value <= 0 ? null : value;
    }

    private static int saturatedAdd(final int first, final int second) {
        final long result = (long) first + second;
        return result > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }

    static final class ReadResult {
        private final McpSnapshot snapshot;
        private final Long latestTimestamp;

        private ReadResult(@NonNull final McpSnapshot snapshot, @Nullable final Long latestTimestamp) {
            this.snapshot = snapshot;
            this.latestTimestamp = latestTimestamp;
        }

        @NonNull
        McpSnapshot getSnapshot() {
            return snapshot;
        }

        @Nullable
        Long getLatestTimestamp() {
            return latestTimestamp;
        }
    }

    private static final class ActivityData {
        private int steps;
        private int activeCalories;
        private int latestHeartRate;
        private long latestHeartRateTimestamp;
        private long latestTimestamp;
    }

    private static final class TimedInt {
        private final int value;
        private final long timestamp;

        private TimedInt() {
            this(0, 0L);
        }

        private TimedInt(final int value, final long timestamp) {
            this.value = value;
            this.timestamp = timestamp;
        }
    }

    private static final class SleepData {
        private final int totalMinutes;
        private final int deepSleepMinutes;
        private final int lightSleepMinutes;
        private final int remSleepMinutes;
        private final int awakeMinutes;
        private final long fellAsleepAt;
        private final long wokeUpAt;
        private final long updatedAt;

        private SleepData() {
            totalMinutes = 0;
            deepSleepMinutes = 0;
            lightSleepMinutes = 0;
            remSleepMinutes = 0;
            awakeMinutes = 0;
            fellAsleepAt = 0L;
            wokeUpAt = 0L;
            updatedAt = 0L;
        }

        private SleepData(@NonNull final XiaomiSleepTimeSample sample) {
            totalMinutes = intValue(sample.getTotalDuration());
            deepSleepMinutes = intValue(sample.getDeepSleepDuration());
            lightSleepMinutes = intValue(sample.getLightSleepDuration());
            remSleepMinutes = intValue(sample.getRemSleepDuration());
            awakeMinutes = intValue(sample.getAwakeDuration());
            fellAsleepAt = sample.getTimestamp();
            wokeUpAt = wakeupTime(sample);
            updatedAt = Math.max(fellAsleepAt, wokeUpAt);
        }

        @NonNull
        private JsonObject toJson() {
            final JsonObject value = new JsonObject();
            value.addProperty("totalMinutes", totalMinutes);
            value.addProperty("deepSleepMinutes", deepSleepMinutes);
            value.addProperty("lightSleepMinutes", lightSleepMinutes);
            value.addProperty("remSleepMinutes", remSleepMinutes);
            value.addProperty("awakeMinutes", awakeMinutes);
            value.addProperty("fellAsleepLabel", formatClock(fellAsleepAt));
            value.addProperty("wokeUpLabel", formatClock(wokeUpAt));
            McpSnapshot.putNullable(value, "updatedAtEpochMillis", zeroToNull(updatedAt));
            return value;
        }

        @NonNull
        private static String formatClock(final long timestamp) {
            if (timestamp <= 0) {
                return "--:--";
            }
            return Instant.ofEpochMilli(timestamp)
                    .atZone(ZoneId.systemDefault())
                    .format(CLOCK_FORMAT);
        }
    }
}
