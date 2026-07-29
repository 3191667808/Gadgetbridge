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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.util.List;

/**
 * Immutable JSON-ready view of the data published through MCP.
 *
 * <p>The server serves instances of this class from an atomic reference. Database reads can
 * therefore replace a complete snapshot without exposing partially updated health data to a
 * concurrent HTTP request.</p>
 */
public final class McpSnapshot {
    static final String SECTION_SERVICE_STATUS = "serviceStatus";
    static final String SECTION_BAND_STATUS = "bandStatus";
    static final String SECTION_DEVICE = "deviceProfile";
    static final String SECTION_ACTIVITY = "activitySummary";
    static final String SECTION_DAILY_METRICS = "dailyMetrics";
    static final String SECTION_HEART_RATE = "heartRateSample";
    static final String SECTION_BATTERY = "batteryStatus";
    static final String SECTION_STRESS = "stressSample";
    static final String SECTION_SLEEP = "sleepSummary";
    static final String SECTION_SYNC_STATUS = "syncStatus";

    private static final List<String> DATA_SECTIONS = List.of(
            SECTION_BAND_STATUS,
            SECTION_DEVICE,
            SECTION_ACTIVITY,
            SECTION_DAILY_METRICS,
            SECTION_HEART_RATE,
            SECTION_BATTERY,
            SECTION_STRESS,
            SECTION_SLEEP
    );

    private final JsonObject root;

    private McpSnapshot(@NonNull final JsonObject root) {
        this.root = root.deepCopy();
    }

    @NonNull
    public static McpSnapshot empty() {
        final JsonObject root = new JsonObject();
        root.add(SECTION_SERVICE_STATUS, serviceStatus(false, null, null));

        final JsonObject bandStatus = new JsonObject();
        bandStatus.addProperty("gadgetbridgeInstalled", true);
        bandStatus.addProperty("exportGranted", true);
        bandStatus.addProperty("dataReady", false);
        bandStatus.addProperty("detail", "Waiting for Gadgetbridge data");
        root.add(SECTION_BAND_STATUS, bandStatus);

        root.add(SECTION_DEVICE, new JsonObject());

        final JsonObject activity = new JsonObject();
        activity.addProperty("stepsToday", 0);
        root.add(SECTION_ACTIVITY, activity);

        root.add(SECTION_DAILY_METRICS, new JsonObject());
        root.add(SECTION_HEART_RATE, timedValue("bpm", 0, null));
        root.add(SECTION_BATTERY, timedValue("levelPercent", 0, null));
        root.add(SECTION_STRESS, timedValue("level", 0, null));

        final JsonObject sleep = new JsonObject();
        sleep.addProperty("totalMinutes", 0);
        sleep.addProperty("deepSleepMinutes", 0);
        sleep.addProperty("lightSleepMinutes", 0);
        sleep.addProperty("remSleepMinutes", 0);
        sleep.addProperty("awakeMinutes", 0);
        sleep.addProperty("fellAsleepLabel", "--:--");
        sleep.addProperty("wokeUpLabel", "--:--");
        putNullable(sleep, "updatedAtEpochMillis", (Long) null);
        root.add(SECTION_SLEEP, sleep);

        root.add(SECTION_SYNC_STATUS, syncStatus(false, null, null, null));
        return new McpSnapshot(root);
    }

    @NonNull
    public JsonObject toJson() {
        return root.deepCopy();
    }

    @NonNull
    JsonObject getSyncStatus() {
        return section(SECTION_SYNC_STATUS);
    }

    @NonNull
    McpSnapshot withServiceStatus(
            final boolean running,
            @Nullable final String endpoint,
            @Nullable final String message
    ) {
        return replace(SECTION_SERVICE_STATUS, serviceStatus(running, endpoint, message));
    }

    @NonNull
    McpSnapshot withSyncStatus(
            final boolean refreshing,
            @Nullable final Long lastSyncEpochMillis,
            @Nullable final String errorMessage,
            @Nullable final String statusMessage
    ) {
        return replace(
                SECTION_SYNC_STATUS,
                syncStatus(refreshing, lastSyncEpochMillis, errorMessage, statusMessage)
        );
    }

    /** Replaces health/device sections while preserving live server and synchronization state. */
    @NonNull
    McpSnapshot mergeData(@NonNull final McpSnapshot freshData) {
        final JsonObject merged = root.deepCopy();
        for (final String section : DATA_SECTIONS) {
            merged.add(section, freshData.section(section));
        }
        return new McpSnapshot(merged);
    }

    @NonNull
    McpSnapshot replace(@NonNull final String name, @NonNull final JsonElement value) {
        final JsonObject copy = root.deepCopy();
        copy.add(name, value.deepCopy());
        return new McpSnapshot(copy);
    }

    @NonNull
    JsonObject section(@NonNull final String name) {
        final JsonElement element = root.get(name);
        if (element == null || !element.isJsonObject()) {
            return new JsonObject();
        }
        return element.getAsJsonObject().deepCopy();
    }

    @NonNull
    private static JsonObject serviceStatus(
            final boolean running,
            @Nullable final String endpoint,
            @Nullable final String message
    ) {
        final JsonObject value = new JsonObject();
        value.addProperty("isRunning", running);
        if (endpoint != null) {
            final URI uri = URI.create(endpoint);
            final JsonObject endpointInfo = new JsonObject();
            endpointInfo.addProperty("host", uri.getHost());
            endpointInfo.addProperty("port", uri.getPort());
            endpointInfo.addProperty("url", endpoint);
            value.add("endpoint", endpointInfo);
        }
        putNullable(value, "message", message);
        return value;
    }

    @NonNull
    private static JsonObject syncStatus(
            final boolean refreshing,
            @Nullable final Long lastSyncEpochMillis,
            @Nullable final String errorMessage,
            @Nullable final String statusMessage
    ) {
        final JsonObject value = new JsonObject();
        value.addProperty("isRefreshing", refreshing);
        putNullable(value, "lastSyncEpochMillis", lastSyncEpochMillis);
        putNullable(value, "errorMessage", errorMessage);
        putNullable(value, "statusMessage", statusMessage);
        return value;
    }

    @NonNull
    private static JsonObject timedValue(
            @NonNull final String valueName,
            final int value,
            @Nullable final Long timestamp
    ) {
        final JsonObject object = new JsonObject();
        object.addProperty(valueName, value);
        putNullable(object, "measuredAtEpochMillis", timestamp);
        return object;
    }

    static void putNullable(
            @NonNull final JsonObject target,
            @NonNull final String name,
            @Nullable final String value
    ) {
        if (value != null) {
            target.addProperty(name, value);
        }
    }

    static void putNullable(
            @NonNull final JsonObject target,
            @NonNull final String name,
            @Nullable final Number value
    ) {
        if (value != null) {
            target.addProperty(name, value);
        }
    }
}
