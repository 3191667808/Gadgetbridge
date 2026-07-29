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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

/**
 * Owns the single immutable snapshot exposed to HTTP threads and serializes all database reads.
 */
final class McpSnapshotRepository implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(McpSnapshotRepository.class);

    private final GadgetbridgeSnapshotReader reader = new GadgetbridgeSnapshotReader();
    private final AtomicReference<McpSnapshot> snapshot =
            new AtomicReference<>(McpSnapshot.empty());
    private final AtomicReference<String> selectedAddress = new AtomicReference<>("");
    private final ExecutorService databaseExecutor = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "Gadgetbridge-MCP-Database");
        thread.setDaemon(true);
        return thread;
    });

    @NonNull
    McpSnapshot get() {
        return snapshot.get();
    }

    void setServiceStatus(
            final boolean running,
            @Nullable final String endpoint,
            @Nullable final String message
    ) {
        snapshot.updateAndGet(current -> current.withServiceStatus(running, endpoint, message));
    }

    void selectDevice(@Nullable final GBDevice device) {
        final String address = device == null ? "" : device.getAddress();
        selectedAddress.set(address);

        final McpSnapshot current = snapshot.get();
        final McpSnapshot cleared = McpSnapshot.empty()
                .replace(
                        McpSnapshot.SECTION_SERVICE_STATUS,
                        current.section(McpSnapshot.SECTION_SERVICE_STATUS)
                )
                .withSyncStatus(false, null, null, device == null ? "No device selected" : "Loading data");
        snapshot.set(cleared);

        if (device != null) {
            reload(device, false);
        }
    }

    void markRefreshing() {
        snapshot.updateAndGet(current -> current.withSyncStatus(
                true,
                lastSync(current),
                null,
                "Synchronization requested"
        ));
    }

    boolean isRefreshing() {
        final var value = snapshot.get().getSyncStatus().get("isRefreshing");
        return value != null && value.isJsonPrimitive() && value.getAsBoolean();
    }

    void markRefreshError(@NonNull final String message) {
        snapshot.updateAndGet(current -> current.withSyncStatus(
                false,
                lastSync(current),
                message,
                "Synchronization failed"
        ));
    }

    void reloadAfterSync(@NonNull final GBDevice device) {
        reload(device, true);
    }

    private void reload(@NonNull final GBDevice device, final boolean syncCompleted) {
        final String requestedAddress = device.getAddress();
        databaseExecutor.execute(() -> {
            if (!requestedAddress.equals(selectedAddress.get())) {
                return;
            }
            try {
                final GadgetbridgeSnapshotReader.ReadResult result = reader.read(device);
                if (!requestedAddress.equals(selectedAddress.get())) {
                    return;
                }
                snapshot.updateAndGet(current -> {
                    final Long lastSync = syncCompleted
                            ? System.currentTimeMillis()
                            : result.getLatestTimestamp();
                    final McpSnapshot merged = current.mergeData(result.getSnapshot());
                    if (!syncCompleted && isRefreshing(current)) {
                        return merged.withSyncStatus(
                                true,
                                lastSync,
                                null,
                                "Synchronization requested"
                        );
                    }
                    return merged.withSyncStatus(false, lastSync, null, "Ready");
                });
            } catch (final Exception exception) {
                LOG.error("Failed to build MCP snapshot for {}", device.getAliasOrName(), exception);
                if (requestedAddress.equals(selectedAddress.get())) {
                    markRefreshError(exception.getLocalizedMessage() == null
                            ? exception.getClass().getSimpleName()
                            : exception.getLocalizedMessage());
                }
            }
        });
    }

    @Nullable
    private static Long lastSync(@NonNull final McpSnapshot current) {
        final var value = current.getSyncStatus().get("lastSyncEpochMillis");
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        return value.getAsLong();
    }

    private static boolean isRefreshing(@NonNull final McpSnapshot current) {
        final var value = current.getSyncStatus().get("isRefreshing");
        return value != null && value.isJsonPrimitive() && value.getAsBoolean();
    }

    @Override
    public void close() {
        databaseExecutor.shutdownNow();
    }
}
